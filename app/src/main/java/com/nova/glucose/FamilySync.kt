package com.nova.glucose

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Family sync between the family's phones (any Gmail account, no sign-in): meals, insulin doses, earlier entries,
 * deletions, the eat timer and AI corrections. Settings, alarms, logins and keys stay on each phone.
 *
 * Firebase (project nova-glucose): anonymous sign-in + Firestore, used over plain HTTPS.
 *   families/{famId}               owner, check (proves the password)
 *   families/{famId}/members/{uid} n (phone name, encrypted), seen
 *   families/{famId}/items/{gid}   d (AES-256-GCM, key from the family password), w (writer), s (server time)
 * famId is a hash of the family code; the content is end-to-end encrypted, so Google only stores unreadable data.
 */
object FamilySync {
    private const val PROJECT = "nova-glucose"
    private const val API_KEY = "AIzaSyBEpER57iLtNkL68kE_xtKlppfXlPyQQ_8"
    private const val PKG = "com.nova.glucose"
    private const val CERT = "41635CCBF57859833EE2E3F15A8A4FF8D7DE8F56"
    private const val ROOT = "projects/$PROJECT/databases/(default)/documents"
    private const val BASE = "https://firestore.googleapis.com/v1/$ROOT"
    private const val ITER = 120_000
    private const val ALPHA = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val MIN_PW = 6

    class SyncError(val code: Int, msg: String) : Exception(msg)

    /** Self-test: don't start the monitor with test logins. */
    @Volatile var testing = false

    /** One anonymous Firebase identity. */
    class Session(var uid: String, var idToken: String, var refresh: String, var exp: Long)

    // ================================================================ HTTP
    private fun http(method: String, url: String, body: String?, token: String?, form: Boolean = false): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000; c.readTimeout = 30_000
            c.setRequestProperty("X-Android-Package", PKG)
            c.setRequestProperty("X-Android-Cert", CERT)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to txt
        } finally { c.disconnect() }
    }

    private fun errText(code: Int, body: String): String {
        val m = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
        return when (code) {
            403 -> "Not allowed (not a member of this family)"
            404 -> "Not found"
            else -> "Sync error $code${if (!m.isNullOrBlank()) ": $m" else ""}"
        }
    }

    private fun call(s: Session, method: String, url: String, body: String? = null): String {
        val (code, txt) = http(method, url, body, s.idToken)
        if (code !in 200..299) throw SyncError(code, errText(code, txt))
        return txt
    }

    // ================================================================ anonymous sign-in
    fun signUp(): Session {
        val (code, txt) = http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY",
            JSONObject().put("returnSecureToken", true).toString(), null)
        if (code !in 200..299) throw SyncError(code, "Firebase sign-in failed ($code): " + errText(code, txt))
        val o = JSONObject(txt)
        return Session(o.getString("localId"), o.getString("idToken"), o.getString("refreshToken"),
            System.currentTimeMillis() + o.optLong("expiresIn", 3600) * 1000)
    }

    private fun refresh(rt: String): Session? {
        val (code, txt) = http("POST", "https://securetoken.googleapis.com/v1/token?key=$API_KEY",
            "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8"), null, form = true)
        if (code in 400..499) return null                       // account gone: sign up again
        if (code !in 200..299) throw SyncError(code, errText(code, txt))
        val o = JSONObject(txt)
        return Session(o.getString("user_id"), o.getString("id_token"), o.getString("refresh_token"),
            System.currentTimeMillis() + o.optString("expires_in", "3600").toLong() * 1000)
    }

    /** This phone's identity (kept in Store, renewed when needed). If it had to be re-created, re-joins the family. */
    private fun session(c: Context, force: Boolean = false): Session {
        val st = Store(c)
        if (!force && st.syncIdToken.isNotBlank() && st.syncIdExp > System.currentTimeMillis() + 120_000)
            return Session(st.syncUid, st.syncIdToken, st.syncRefresh, st.syncIdExp)
        val s = (if (st.syncRefresh.isNotBlank()) refresh(st.syncRefresh) else null) ?: signUp()
        val changed = st.syncUid.isNotBlank() && st.syncUid != s.uid
        st.syncUid = s.uid; st.syncIdToken = s.idToken; st.syncRefresh = s.refresh; st.syncIdExp = s.exp
        if (changed && st.syncFam.isNotBlank()) writeMember(s, st.syncFam, key(st), st.syncName.ifBlank { "Phone" })
        return s
    }

    // ================================================================ crypto
    fun norm(code: String) = code.uppercase(Locale.US).filter { it.isLetterOrDigit() }
    fun pretty(code: String) = norm(code).chunked(4).joinToString("-")

    fun newCode(): String {
        val r = SecureRandom()
        return pretty((1..12).map { ALPHA[r.nextInt(ALPHA.length)] }.joinToString(""))
    }

    fun famId(code: String): String =
        MessageDigest.getInstance("SHA-256").digest("nova-glucose-family-id|${norm(code)}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(40)

    fun deriveKey(code: String, password: String): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), "nova-glucose-family|${norm(code)}".toByteArray(), ITER, 256)).encoded

    private fun key(st: Store): ByteArray = Base64.decode(st.syncKey, Base64.NO_WRAP)

    fun seal(key: ByteArray, aad: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        ci.updateAAD(aad.toByteArray())
        return Base64.encodeToString(iv + ci.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun open(key: ByteArray, aad: String, data: String): String {
        val b = Base64.decode(data, Base64.NO_WRAP)
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, 0, 12))
        ci.updateAAD(aad.toByteArray())
        return String(ci.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }

    // ================================================================ Firestore (works on a Session: used by the app and the self-test)
    private fun sv(v: String) = JSONObject().put("stringValue", v)
    private fun doc(path: String) = "$ROOT/$path"
    private fun commit(s: Session, writes: JSONArray) { call(s, "POST", "$BASE:commit", JSONObject().put("writes", writes).toString()) }
    private fun serverTime(field: String) = JSONArray().put(JSONObject().put("fieldPath", field).put("setToServerValue", "REQUEST_TIME"))

    private fun memberWrite(s: Session, fam: String, key: ByteArray, name: String) = JSONObject()
        .put("update", JSONObject().put("name", doc("families/$fam/members/${s.uid}"))
            .put("fields", JSONObject().put("n", sv(seal(key, "name", name)))))
        .put("updateTransforms", serverTime("seen"))

    private fun writeMember(s: Session, fam: String, key: ByteArray, name: String) = commit(s, JSONArray().put(memberWrite(s, fam, key, name)))

    /** New family: the family record (with a password check) and this phone as first member. */
    fun createFamily(s: Session, fam: String, key: ByteArray, name: String) {
        val famDoc = JSONObject()
            .put("update", JSONObject().put("name", doc("families/$fam")).put("fields", JSONObject()
                .put("owner", sv(s.uid)).put("check", sv(seal(key, "check", "nova-family-ok")))
                .put("created", JSONObject().put("integerValue", System.currentTimeMillis().toString()))))
            .put("currentDocument", JSONObject().put("exists", false))
        commit(s, JSONArray().put(famDoc).put(memberWrite(s, fam, key, name)))
    }

    /** Joins with code + password: adds this phone, then proves the password; undoes the join when wrong. */
    fun joinFamily(s: Session, fam: String, key: ByteArray, name: String) {
        writeMember(s, fam, key, name)
        val check = try {
            JSONObject(call(s, "GET", "$BASE/families/$fam")).getJSONObject("fields").getJSONObject("check").getString("stringValue")
        } catch (e: SyncError) {
            leaveFamily(s, fam)
            throw if (e.code == 404) SyncError(404, "Family code not found. Check the code.") else e
        }
        val ok = try { open(key, "check", check) == "nova-family-ok" } catch (_: Exception) { false }
        if (!ok) { leaveFamily(s, fam); throw SyncError(401, "Wrong family password.") }
    }

    fun leaveFamily(s: Session, fam: String) {
        try { call(s, "DELETE", "$BASE/families/$fam/members/${s.uid}") } catch (_: Exception) { }
    }

    /** Sends encrypted items (gid → item JSON). */
    fun push(s: Session, fam: String, key: ByteArray, items: List<Pair<String, JSONObject>>) {
        val parts = mutableListOf<MutableList<Pair<String, JSONObject>>>(mutableListOf())
        var size = 0
        for (x in items) {
            val n = x.second.toString().length
            if (parts.last().size >= 200 || (size + n > 4_000_000 && parts.last().isNotEmpty())) { parts += mutableListOf<Pair<String, JSONObject>>(); size = 0 }
            parts.last() += x; size += n
        }
        parts.filter { it.isNotEmpty() }.forEach { part ->
            val w = JSONArray()
            part.forEach { (gid, item) ->
                w.put(JSONObject().put("update", JSONObject().put("name", doc("families/$fam/items/$gid"))
                    .put("fields", JSONObject().put("d", sv(seal(key, gid, item.toString()))).put("w", sv(s.uid))))
                    .put("updateTransforms", serverTime("s")))
            }
            commit(s, w)
        }
    }

    class Pulled(val items: List<JSONObject>, val since: String, val raw: Int)

    /** Items changed since [since] (server time) by other phones, decrypted. */
    fun pull(s: Session, fam: String, key: ByteArray, since: String, skipOwn: Boolean = true): Pulled {
        val out = mutableListOf<JSONObject>()
        var cursor = since.ifBlank { "1970-01-01T00:00:00Z" }
        var newest = since
        var raw = 0
        while (true) {
            val q = JSONObject().put("structuredQuery", JSONObject()
                .put("from", JSONArray().put(JSONObject().put("collectionId", "items")))
                .put("where", JSONObject().put("fieldFilter", JSONObject()
                    .put("field", JSONObject().put("fieldPath", "s")).put("op", "GREATER_THAN")
                    .put("value", JSONObject().put("timestampValue", cursor))))
                .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "s")).put("direction", "ASCENDING")))
                .put("limit", 300))
            val arr = JSONArray(call(s, "POST", "$BASE/families/$fam:runQuery", q.toString()))
            var n = 0
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i).optJSONObject("document") ?: continue
                n++; raw++
                val f = d.getJSONObject("fields")
                val ts = f.optJSONObject("s")?.optString("timestampValue") ?: continue
                cursor = ts; newest = ts
                if (skipOwn && f.optJSONObject("w")?.optString("stringValue") == s.uid) continue
                val gid = d.getString("name").substringAfterLast('/')
                try { out += JSONObject(open(key, gid, f.getJSONObject("d").getString("stringValue"))) } catch (_: Exception) { }
            }
            if (n < 300) break
        }
        return Pulled(out, newest, raw)
    }

    /** Removes items (self-test cleanup). */
    fun deleteItems(s: Session, fam: String, gids: List<String>) {
        val w = JSONArray(); gids.forEach { w.put(JSONObject().put("delete", doc("families/$fam/items/$it"))) }
        if (w.length() > 0) commit(s, w)
    }

    /** Raw stored text of the family's items (self-test: proves nothing is readable). */
    fun rawItems(s: Session, fam: String): String = call(s, "GET", "$BASE/families/$fam/items?pageSize=50")

    /** Phones in the family: (name, last seen ms, is this phone). */
    fun members(s: Session, fam: String, key: ByteArray): List<Triple<String, Long, Boolean>> {
        val o = JSONObject(call(s, "GET", "$BASE/families/$fam/members?pageSize=50"))
        val a = o.optJSONArray("documents") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val d = a.getJSONObject(i); val f = d.getJSONObject("fields")
            val name = try { open(key, "name", f.getJSONObject("n").getString("stringValue")) } catch (_: Exception) { "?" }
            Triple(name, parseTs(f.optJSONObject("seen")?.optString("timestampValue") ?: ""), d.getString("name").endsWith("/${s.uid}"))
        }
    }

    private fun parseTs(t: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(t.take(19))!!.time
    } catch (_: Exception) { 0L }

    private fun minus2min(t: String): String {
        val ms = parseTs(t); if (ms == 0L) return t
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms - 120_000)
    }

    // ================================================================ items ⇄ local database
    fun mealItem(c: Context, id: Long): JSONObject? {
        val db = Db.get(c); val m = db.meal(id) ?: return null
        val ai = db.ai(id)
        return JSONObject().put("t", "meal").put("g", db.gid("meals", id)).put("u", db.upd("meals", id))
            .put("ts", m.ts).put("title", m.title).put("items", m.itemsJson).put("carbs", m.carbs).put("low", m.low).put("high", m.high)
            .put("mgAt", m.mgAt).put("note", m.note).put("by", db.who("meals", id).ifBlank { Store(c).syncName })
            .apply { if (ai != null) { put("ai", ai.first); put("aiC", ai.second) } }
    }

    fun doseItem(c: Context, id: Long): JSONObject? {
        val db = Db.get(c); val d = db.dose(id) ?: return null
        return JSONObject().put("t", "dose").put("g", db.gid("insulin", id)).put("u", db.upd("insulin", id))
            .put("ts", d.ts).put("kind", d.kind).put("units", d.units).put("carbs", d.carbs).put("wait", d.waitMin)
            .put("mgAt", d.mgAt).put("note", d.note).put("by", db.who("insulin", id).ifBlank { Store(c).syncName })
    }

    // ================================================================ shared settings (LibreLinkUp login, AI keys, profile…)
    /** The settings this phone shares with the family (secrets included: the whole item is end-to-end encrypted). */
    fun settingsPayload(c: Context): JSONObject {
        val st = Store(c)
        val v = JSONObject()
        for ((k, x) in st.raw.all.toSortedMap()) {
            if (!(Store.shared(k) || (!st.syncOwnLibre && k in Store.LIBRE_KEYS))) continue
            if (st.syncOwnAi && Store.aiKey(k)) continue
            when (x) {
                is Boolean -> v.put(k, JSONObject().put("t", "b").put("v", x))
                is Int -> v.put(k, JSONObject().put("t", "i").put("v", x))
                is Long -> v.put(k, JSONObject().put("t", "l").put("v", x))
                is String -> v.put(k, JSONObject().put("t", "s").put("v", x))
            }
        }
        val keys = JSONObject()
        if (!st.syncOwnAi) Providers.all.forEach { p -> st.key(p).takeIf { it.isNotBlank() }?.let { keys.put(p.id, it) } }
        val o = JSONObject().put("v", v).put("keys", keys)
        if (!st.syncOwnLibre && st.password.isNotBlank()) o.put("pw", st.password)
        return o
    }

    private fun hash(o: JSONObject) = MessageDigest.getInstance("SHA-256").digest(o.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    /** Notices a local change of the shared settings and queues it for the family. */
    private fun checkSettings(c: Context) {
        val st = Store(c)
        val h = hash(settingsPayload(c))
        if (st.syncSetHash.isBlank()) {             // first time on this build: the family's creator shares, the others receive
            st.syncSetHash = h
            if (st.syncOwner) { st.syncSetU = System.currentTimeMillis(); Db.get(c).queue("settings", "set", kick = false) }
            return
        }
        if (h != st.syncSetHash) { st.syncSetHash = h; st.syncSetU = System.currentTimeMillis(); Db.get(c).queue("settings", "set", kick = false) }
    }

    private fun applySettings(c: Context, item: JSONObject): String {
        val st = Store(c)
        val u = item.optLong("u")
        if (u <= st.syncSetU) return ""
        val o = item.optJSONObject("s") ?: return ""
        val oldLogin = st.email + "|" + st.password + "|" + st.patientId
        val e = st.raw.edit()
        val v = o.optJSONObject("v") ?: JSONObject()
        for (k in v.keys()) {
            if (!(Store.shared(k) || (!st.syncOwnLibre && k in Store.LIBRE_KEYS))) continue
            if (st.syncOwnAi && Store.aiKey(k)) continue
            val x = v.getJSONObject(k)
            when (x.optString("t")) {
                "b" -> e.putBoolean(k, x.optBoolean("v")); "i" -> e.putInt(k, x.optInt("v"))
                "l" -> e.putLong(k, x.optLong("v")); "s" -> e.putString(k, x.optString("v"))
            }
        }
        e.commit()
        if (!st.syncOwnAi) o.optJSONObject("keys")?.let { keys -> Providers.all.forEach { p -> keys.optString(p.id).takeIf { it.isNotBlank() }?.let { st.setKey(p, it) } } }
        if (!st.syncOwnLibre && o.optString("pw").isNotBlank()) st.password = o.optString("pw")
        st.syncSetU = u
        st.syncSetHash = hash(settingsPayload(c))
        if (oldLogin != st.email + "|" + st.password + "|" + st.patientId) {
            st.token = ""
            if (st.loggedIn && st.monitorOn && !testing) try { MonitorService.start(c) } catch (_: Exception) { }
        }
        return "set"
    }

    fun bodyItem(c: Context, id: Long): JSONObject? {
        val db = Db.get(c); val b = db.body(id) ?: return null
        return JSONObject().put("t", "body").put("g", db.gid("body", id)).put("u", db.upd("body", id)).put("ts", b.ts).put("h", b.height).put("w", b.weight)
            .put("src", b.src).put("by", db.who("body", id))
    }

    /** Current state of a pending item (null = nothing to send). */
    fun itemFor(c: Context, gid: String, kind: String): JSONObject? {
        val db = Db.get(c)
        if (kind == "set") return JSONObject().put("t", "set").put("g", "settings").put("u", Store(c).syncSetU).put("s", settingsPayload(c))
        if (kind == "eat") { val st = Store(c); return JSONObject().put("t", "eat").put("g", "eat").put("u", st.syncEatU).put("at", st.eatAt) }
        if (kind == "hcreq") { val st = Store(c); return JSONObject().put("t", "hcreq").put("g", "hcreq").put("u", st.hcReq).put("by", st.syncName) }
        if (kind == "hcst") return WatchRequest.status(c)
        db.byGid("meals", gid)?.let { return mealItem(c, it.first) }
        db.byGid("insulin", gid)?.let { return doseItem(c, it.first) }
        db.byGid("incidents", gid)?.let { return Incidents.item(c, it.first) }
        if (kind == "hc" && gid.startsWith("hc-")) {
            val day = gid.removePrefix("hc-")
            return db.watchDay(day)?.let { (u, d) -> JSONObject().put("t", "hc").put("g", gid).put("u", u).put("day", day).put("d", d) }
        }
        db.byGid("body", gid)?.let { return bodyItem(c, it.first) }
        val del = db.deletedAt(gid)
        return if (del > 0) JSONObject().put("t", "del").put("g", gid).put("u", del) else null
    }

    /** Everything on this phone as items (family update file). */
    fun allItems(c: Context): List<JSONObject> {
        val db = Db.get(c)
        val out = mutableListOf<JSONObject>()
        db.meals(1_000_000).forEach { m -> mealItem(c, m.id)?.let { out += it } }
        db.doses().forEach { d -> doseItem(c, d.id)?.let { out += it } }
        db.incidents().forEach { i -> Incidents.item(c, i.id)?.let { out += it } }
        db.bodies().forEach { b -> bodyItem(c, b.id)?.let { out += it } }
        db.deletedGids().forEach { (g, ts) -> out += JSONObject().put("t", "del").put("g", g).put("u", ts) }
        return out
    }

    /** Applies one item from another phone. Returns "meal+", "dose+", "upd", "del", "eat" or "" (no change). */
    fun apply(c: Context, item: JSONObject): String {
        val db = Db.get(c)
        val g = item.optString("g"); val u = item.optLong("u")
        if (g.isBlank()) return ""
        when (item.optString("t")) {
            "meal" -> {
                if (db.deletedAt(g) > 0) return ""
                val m = Meal(0, item.getLong("ts"), item.optString("title"), item.optString("items", "[]"), item.optInt("carbs"), item.optInt("low"),
                    item.optInt("high"), item.optInt("mgAt"), "", item.optString("note"))
                var row = db.byGid("meals", g)
                if (row == null) db.findMeal(m.ts, m.title)?.let { same -> if (!resolveTwin(db, "meals", "meal", same, g)) return ""; row = db.byGid("meals", g) }
                val r = row
                if (r == null) {
                    val id = db.addMeal(m, g, u, share = false, who = item.optString("by"))
                    if (item.has("ai")) db.setAi(id, item.optString("ai"), item.optInt("aiC"), share = false, upd = u)
                    return "meal+"
                }
                if (u <= r.second) return ""
                db.updateMeal(r.first, m, u)
                if (item.has("ai")) db.setAi(r.first, item.optString("ai"), item.optInt("aiC"), share = false, upd = u) else db.clearAi(r.first)
                return "upd"
            }
            "dose" -> {
                if (db.deletedAt(g) > 0) return ""
                val d = Dose(0, item.getLong("ts"), item.optString("kind", "rapid"), item.optDouble("units"), item.optInt("carbs"), item.optInt("wait"),
                    item.optInt("mgAt"), item.optString("note"))
                var row = db.byGid("insulin", g)
                if (row == null) db.findDose(d.ts, d.kind, d.units)?.let { same -> if (!resolveTwin(db, "insulin", "dose", same, g)) return ""; row = db.byGid("insulin", g) }
                val r = row
                if (r == null) { db.addDose(d, g, u, share = false, who = item.optString("by")); return "dose+" }
                if (u <= r.second) return ""
                db.updateDose(r.first, d, u); return "upd"
            }
            "inc" -> return Incidents.apply(c, item)
            "set" -> return applySettings(c, item)
            "hc" -> {
                val day = item.optString("day"); val d = item.optString("d")
                if (day.isBlank() || d.isBlank()) return ""
                if (!db.putWatch(day, d, u, share = false)) return ""
                val st = Store(c)
                val w = WatchDay.parse(day, d)
                val newest = maxOf(w.sleep.maxOfOrNull { it.b } ?: 0L, w.ex.maxOfOrNull { it.b } ?: 0L,
                    w.hr.indexOfLast { it > 0 }.takeIf { it >= 0 }?.let { WatchData.startOf(day) + it * 300_000L + 300_000L } ?: 0L,
                    w.steps.indexOfLast { it > 0 }.takeIf { it >= 0 }?.let { WatchData.startOf(day) + (it + 1) * 3600_000L } ?: 0L)
                if (newest in (st.hcLastData + 1)..(System.currentTimeMillis() + 3600_000L)) st.hcLastData = newest
                return "hc"
            }
            "body" -> {
                if (db.deletedAt(g) > 0) return ""
                val ts = item.optLong("ts"); val h = item.optDouble("h", 0.0); val w = item.optDouble("w", 0.0)
                var row = db.byGid("body", g)
                if (row == null) db.findBody(ts)?.let { same -> if (!resolveTwin(db, "body", "body", same, g)) return ""; row = db.byGid("body", g) }
                val r = row
                if (r == null) { db.addBody(ts, h, w, item.optString("src"), g, u, share = false, who = item.optString("by")); return "body+" }
                if (u <= r.second) return ""
                db.updateBody(r.first, ts, h, w, u); return "upd"
            }
            "del" -> {
                db.byGid("meals", g)?.let { (id, _) -> db.meal(id)?.photo?.takeIf { p -> p.isNotBlank() }?.let { p -> java.io.File(p).delete() } }
                return if (db.applyDelete(g, if (u > 0) u else System.currentTimeMillis())) "del" else ""
            }
            "hcreq" -> return WatchRequest.received(c, u)
            "hcst" -> {
                val st = Store(c)
                if (u <= st.hcPeerU) return ""
                st.hcPeerU = u; st.hcPeer = item.toString()
                val d = item.optLong("data"); if (d > st.hcLastData && d < System.currentTimeMillis() + 3600_000L) st.hcLastData = d
                return "hcst"
            }
            "eat" -> {
                val st = Store(c)
                if (u <= st.syncEatU) return ""
                st.syncEatU = u
                val at = item.optLong("at")
                if (at > System.currentTimeMillis()) EatTimer.schedule(c, at, share = false) else EatTimer.cancel(c, share = false)
                return "eat"
            }
        }
        return ""
    }

    /**
     * The same entry exists here under another id (e.g. both phones restored the same backup before syncing).
     * Both phones keep the smaller id, so they agree without duplicates. Returns true if the incoming id was adopted.
     */
    private fun resolveTwin(db: Db, table: String, kind: String, localId: Long, remote: String): Boolean {
        val mine = db.gid(table, localId)
        if (mine == remote) return true
        return if (mine.isBlank() || remote < mine) {
            db.adoptGid(table, localId, remote)
            if (mine.isNotBlank()) { db.markDeleted(mine, System.currentTimeMillis()); db.queue(mine, "del", kick = false) }
            true
        } else {
            db.markDeleted(remote, System.currentTimeMillis()); db.queue(remote, "del", kick = false); db.queue(mine, kind, kick = false)
            false
        }
    }

    // ================================================================ app actions
    private fun saveFamily(st: Store, code: String, pw: String, key: ByteArray, name: String, owner: Boolean) {
        st.syncCode = pretty(code); st.syncPw = pw; st.syncKey = Base64.encodeToString(key, Base64.NO_WRAP)
        st.syncName = name; st.syncOwner = owner; st.syncSince = ""; st.syncSeen = System.currentTimeMillis(); st.syncInfo = ""
        st.syncFam = famId(code)
    }

    /** Creates a new family; returns the family code to give the other phones. Run off the main thread. */
    fun create(c: Context, password: String, name: String): String {
        require(password.length >= MIN_PW) { "The family password needs at least $MIN_PW characters." }
        val st = Store(c)
        val code = newCode()
        val key = deriveKey(code, password)
        val s = session(c)
        createFamily(s, famId(code), key, name)
        saveFamily(st, code, password, key, name, true)
        st.syncSetHash = hash(settingsPayload(c)); st.syncSetU = System.currentTimeMillis()
        Db.get(c).queueAll(); queueEat(c); Db.get(c).queue("settings", "set", kick = false)
        try { run(c) } catch (_: Exception) { }
        return pretty(code)
    }

    /** Joins an existing family with its code and password. Run off the main thread. */
    fun join(c: Context, code: String, password: String, name: String): String {
        require(norm(code).length == 12) { "The family code has 12 letters/digits (like ABCD-EFGH-JK23)." }
        require(password.isNotBlank()) { "Enter the family password." }
        val st = Store(c)
        val key = deriveKey(code, password)
        val s = session(c)
        joinFamily(s, famId(code), key, name)
        saveFamily(st, code, password, key, name, false)
        st.syncSetHash = hash(settingsPayload(c)); st.syncSetU = 0        // receive the family's settings
        Db.get(c).queueAll()
        return try { run(c).replace("✓", "✓ Joined ·") } catch (e: Exception) { "✓ Joined · first sync will retry (${e.message})" }
    }

    fun leave(c: Context) {
        val st = Store(c)
        if (st.syncFam.isNotBlank()) try { leaveFamily(session(c), st.syncFam) } catch (_: Exception) { }
        st.syncFam = ""; st.syncCode = ""; st.syncPw = ""; st.syncKey = ""; st.syncSince = ""; st.syncInfo = ""; st.syncOwner = false
        st.syncSetHash = ""; st.syncSetU = 0
        Db.get(c).clearOutbox()
    }

    private fun queueEat(c: Context) { if (Store(c).syncEatU > 0) Db.get(c).queue("eat", "eat", kick = false) }

    fun members(c: Context): List<Triple<String, Long, Boolean>> {
        val st = Store(c)
        if (st.syncFam.isBlank()) return emptyList()
        return members(session(c), st.syncFam, key(st))
    }

    private val running = AtomicBoolean(false)
    @Volatile private var again = false
    @Volatile var listener: (() -> Unit)? = null

    /** Sync soon (after a change on this phone, or when the app opens). */
    fun kick(c: Context) {
        if (Store(c).syncFam.isBlank()) return
        val app = c.applicationContext
        Thread { try { run(app) } catch (_: Exception) { } }.start()
    }

    /** Called every minute by the monitor. */
    fun maybeRun(c: Context) { if (Store(c).syncFam.isNotBlank()) try { run(c) } catch (_: Exception) { } }

    /** Sends what changed here, receives what changed on the other phones. Returns a short summary. */
    fun run(c: Context): String {
        val st = Store(c)
        if (st.syncFam.isBlank()) return "Family sync is off"
        if (!running.compareAndSet(false, true)) { again = true; return "Syncing…" }
        try {
            var summary: String
            do {
                again = false
                summary = once(c)
            } while (again)
            return summary
        } catch (e: Exception) {
            st.syncInfo = "⚠ ${e.message ?: "Sync failed"}"
            throw e
        } finally { running.set(false) }
    }

    private fun once(c: Context): String {
        val st = Store(c); val db = Db.get(c)
        val fam = st.syncFam; val key = key(st)
        var s = session(c)
        fun <T> retry(block: (Session) -> T): T = try { block(s) } catch (e: SyncError) {
            if (e.code == 401) { s = session(c, force = true); block(s) }
            else if (e.code == 403) { s = session(c, force = true); writeMember(s, fam, key, st.syncName.ifBlank { "Phone" }); block(s) }
            else throw e
        }
        checkSettings(c)
        // 1. send
        var sent = 0
        while (true) {
            val box = db.outbox(200)
            if (box.isEmpty()) break
            val items = box.mapNotNull { (g, k, _) -> itemFor(c, g, k)?.let { g to it } }
            if (items.isNotEmpty()) retry { push(it, fam, key, items) }
            db.sent(box); sent += items.size
            if (box.size < 200) break
        }
        // 2. receive
        val p = retry { pull(it, fam, key, if (st.syncSince.isBlank()) "" else minus2min(st.syncSince)) }
        var got = 0
        for (item in p.items) if (apply(c, item).isNotBlank()) got++
        if (p.since.isNotBlank() && p.since > st.syncSince) st.syncSince = p.since
        // the twin rule may have queued items: send them next time
        // 3. "last seen" for the member list (every 30 min)
        if (System.currentTimeMillis() - st.syncSeen > 30 * 60_000L) {
            try { retry { writeMember(it, fam, key, st.syncName.ifBlank { "Phone" }) }; st.syncSeen = System.currentTimeMillis() } catch (_: Exception) { }
        }
        st.syncLast = System.currentTimeMillis()
        val waiting = db.outboxCount()
        st.syncInfo = "✓ sent $sent · received $got" + if (waiting > 0) " · $waiting waiting" else ""
        if (got > 0 || sent > 0) listener?.invoke()
        return st.syncInfo
    }

    // ================================================================ family update file (WhatsApp, no internet sync needed)
    /** Encrypted .novaglucose file with all meals, doses, deletions (no settings). */
    fun exportFile(c: Context, password: String): ByteArray {
        val items = JSONArray(); allItems(c).forEach { items.put(it) }
        val st = Store(c)
        val nm = (0 until items.length()).count { items.getJSONObject(it).optString("t") == "meal" }
        val nd = (0 until items.length()).count { items.getJSONObject(it).optString("t") == "dose" }
        val header = JSONObject().put("app", "Nova Glucose").put("kind", "family")
            .put("name", st.syncName.ifBlank { st.patientName.ifBlank { "Nova Glucose" } })
            .put("created", System.currentTimeMillis()).put("version", Ui.version(c))
            .put("contents", JSONObject().put("meals", nm).put("doses", nd).put("deletions", items.length() - nm - nd))
        val inner = JSONObject().put("app", "Nova Glucose").put("kind", "family").put("items", items)
        return Backup.seal(header, inner.toString(), password)
    }

    /** Merges a family update file or the meals/doses of a full backup. Nothing is replaced; returns a summary. */
    fun mergeText(c: Context, text: String): String {
        val root = JSONObject(text)
        val db = Db.get(c)
        val counts = HashMap<String, Int>()
        val changed = mutableListOf<Pair<String, String>>()
        if (root.optString("kind") == "family") {
            val a = root.getJSONArray("items")
            for (i in 0 until a.length()) {
                val one = a.getJSONObject(i)
                val r = apply(c, one)
                if (r.isNotBlank()) { counts[r] = (counts[r] ?: 0) + 1; if (r != "eat") changed += one.optString("g") to one.optString("t") }
            }
        } else {
            Backup.dataOf(root)?.let { d ->
                val ma = d.getJSONArray("meals")
                for (i in 0 until ma.length()) {
                    val o = ma.getJSONObject(i)
                    val g = o.optString("gid")
                    val item = JSONObject().put("t", "meal").put("g", g.ifBlank { "b" + o.getLong("ts") + o.optString("title").hashCode() })
                        .put("u", o.optLong("upd", o.getLong("ts"))).put("ts", o.getLong("ts")).put("title", o.optString("title"))
                        .put("items", o.optString("items", "[]")).put("carbs", o.optInt("carbs")).put("low", o.optInt("low"))
                        .put("high", o.optInt("high")).put("mgAt", o.optInt("mgAt")).put("note", o.optString("note"))
                        .apply { if (o.has("aiItems")) { put("ai", o.optString("aiItems")); put("aiC", o.optInt("aiCarbs")) } }
                    if (g.isBlank() && db.findMeal(o.getLong("ts"), o.optString("title")) != null) continue
                    val r = apply(c, item); if (r.isNotBlank()) { counts[r] = (counts[r] ?: 0) + 1; changed += item.getString("g") to "meal" }
                }
                val da = d.getJSONArray("doses")
                for (i in 0 until da.length()) {
                    val o = da.getJSONObject(i)
                    val g = o.optString("gid")
                    if (g.isBlank() && db.findDose(o.getLong("ts"), o.optString("kind", "rapid"), o.optDouble("units")) != null) continue
                    val item = JSONObject().put("t", "dose").put("g", g.ifBlank { "b" + o.getLong("ts") + o.optString("kind") + o.optDouble("units") })
                        .put("u", o.optLong("upd", o.getLong("ts"))).put("ts", o.getLong("ts")).put("kind", o.optString("kind", "rapid"))
                        .put("units", o.optDouble("units")).put("carbs", o.optInt("carbs")).put("wait", o.optInt("wait"))
                        .put("mgAt", o.optInt("mgAt")).put("note", o.optString("note"))
                    val r = apply(c, item); if (r.isNotBlank()) { counts[r] = (counts[r] ?: 0) + 1; changed += item.getString("g") to "dose" }
                }
            }
        }
        // Pass what was new here on to the rest of the family (when live sync is on).
        changed.forEach { (g, k) -> db.queue(g, if (k == "del") "del" else k, kick = false) }
        if (changed.isNotEmpty()) kick(c)
        val parts = listOfNotNull(
            counts["meal+"]?.let { "$it new meal(s)" }, counts["dose+"]?.let { "$it new dose(s)" },
            counts["inc+"]?.let { "$it new incident(s)" }, counts["upd"]?.let { "$it updated" }, counts["del"]?.let { "$it deleted" }, counts["eat"]?.let { "eat timer" })
        return if (parts.isEmpty()) "Nothing new — this phone already had everything." else parts.joinToString(" · ")
    }
}
