package com.nova.glucose

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One backup file with all settings. The LibreLinkUp password and the API keys are locked with the
 * user's backup password (AES-256-GCM, key from PBKDF2). Without the password everything else still restores.
 * Format 2 also carries ALL readings, meals (without photos), insulin doses and AI corrections, compressed,
 * so one file is enough to move everything to a new phone.
 */
object Backup {
    const val FILE = "NovaGlucose-backup.json"
    private const val ITER = 120_000

    fun build(c: Context, password: String): String {
        val st = Store(c)
        val settings = JSONObject()
        for ((k, v) in st.raw.all) {
            if (Store.privateKey(k)) continue
            when (v) {
                is Boolean -> settings.put(k, JSONObject().put("t", "b").put("v", v))
                is Int -> settings.put(k, JSONObject().put("t", "i").put("v", v))
                is Long -> settings.put(k, JSONObject().put("t", "l").put("v", v))
                is String -> settings.put(k, JSONObject().put("t", "s").put("v", v))
            }
        }
        val secrets = JSONObject().put("password", st.password)
        Providers.all.forEach { p -> st.key(p).takeIf { it.isNotBlank() }?.let { secrets.put("key_${p.id}", it) } }
        val data = dataJson(c)
        val root = JSONObject()
            .put("app", "Nova Glucose").put("format", 2).put("created", System.currentTimeMillis())
            .put("version", Ui.version(c))
            .put("contents", JSONObject().put("settings", settings.length())
                .put("readings", data.getJSONArray("readings").length())
                .put("meals", data.getJSONArray("meals").length())
                .put("doses", data.getJSONArray("doses").length()))
            .put("settings", settings)
            .put("data", gzip(data.toString()))
        if (password.isNotBlank()) root.put("secrets", encrypt(secrets.toString(), password))
        return root.toString()
    }

    private fun dataJson(c: Context): JSONObject {
        val db = Db.get(c)
        val rs = org.json.JSONArray()
        db.readings(0).forEach { rs.put(org.json.JSONArray().put(it.ts).put(it.mg).put(it.trend)) }
        val ms = org.json.JSONArray()
        db.meals(1_000_000).forEach { m ->
            val ai = db.ai(m.id)
            ms.put(JSONObject().put("ts", m.ts).put("title", m.title).put("items", m.itemsJson).put("carbs", m.carbs)
                .put("low", m.low).put("high", m.high).put("mgAt", m.mgAt).put("note", m.note)
                .put("photo", if (m.photo.isNotBlank()) java.io.File(m.photo).name else "")
                .apply { if (ai != null) { put("aiItems", ai.first); put("aiCarbs", ai.second) } })
        }
        val ds = org.json.JSONArray()
        db.doses().forEach { d ->
            ds.put(JSONObject().put("ts", d.ts).put("kind", d.kind).put("units", d.units).put("carbs", d.carbs)
                .put("wait", d.waitMin).put("mgAt", d.mgAt).put("note", d.note))
        }
        return JSONObject().put("readings", rs).put("meals", ms).put("doses", ds)
    }

    private fun gzip(s: String): String {
        val bo = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bo).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }

    private fun gunzip(s: String): String =
        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(Base64.decode(s, Base64.NO_WRAP))).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Full round-trip check of a backup against this phone: every setting value, the LibreLinkUp login, every API key,
     * and the number of readings, meals and doses. Returns a summary line, or throws with what is wrong.
     */
    fun verify(text: String, c: Context, password: String): String {
        val root = JSONObject(text)
        require(root.optString("app") == "Nova Glucose") { "not a Nova Glucose backup" }
        val st = Store(c)
        val settings = root.getJSONObject("settings")
        var nSet = 0
        for ((k, v) in st.raw.all) {
            if (Store.privateKey(k)) continue
            val o = settings.optJSONObject(k) ?: throw IllegalStateException("setting '$k' missing")
            val same = when (v) {
                is Boolean -> o.optBoolean("v") == v
                is Int -> o.optInt("v") == v
                is Long -> o.optLong("v") == v
                is String -> o.optString("v") == v
                else -> true
            }
            require(same) { "setting '$k' differs" }
            nSet++
        }
        var secretsNote = "no backup password: login and keys NOT included"
        if (password.isNotBlank()) {
            val enc = root.optJSONObject("secrets") ?: throw IllegalStateException("login and keys missing")
            val s = JSONObject(decrypt(enc, password))
            require(s.optString("password") == st.password) { "LibreLinkUp password differs" }
            var nk = 0
            Providers.all.forEach { p ->
                val k = st.key(p)
                if (k.isNotBlank()) { require(s.optString("key_${p.id}") == k) { "${p.label} key differs" }; nk++ }
            }
            secretsNote = "login ${if (st.password.isNotBlank()) "✓" else "(none)"} · $nk API key(s) ✓"
        }
        val d = JSONObject(gunzip(root.getString("data")))
        val db = Db.get(c)
        val nr = d.getJSONArray("readings").length(); val nm = d.getJSONArray("meals").length(); val nd = d.getJSONArray("doses").length()
        require(nr == db.readings(0).size) { "readings count differs" }
        require(nm == db.meals(1_000_000).size) { "meals count differs" }
        require(nd == db.doses().size) { "doses count differs" }
        return "✓ $nSet settings · $secretsNote · $nr readings · $nm meals · $nd doses"
    }

    // ---------------------------------------------------------------- encrypted file (format 3)
    // NOVAGLUC | 1 byte version | 4 bytes header length | header JSON (name, date, counts — no health data)
    // | 16 bytes salt | 12 bytes IV | AES-256-GCM( gzip( full backup JSON ) ) with the backup password.
    private val MAGIC = "NOVAGLUC".toByteArray()
    const val MIME = "application/vnd.nova.glucose"

    fun isEncrypted(b: ByteArray) = b.size > 13 && b.copyOfRange(0, 8).contentEquals(MAGIC)

    /** Whole-file encrypted backup. The backup password is required. */
    fun pack(c: Context, password: String): ByteArray {
        require(password.isNotBlank()) { "Set a backup password first (Settings › Backup)." }
        val inner = build(c, password)
        val root = JSONObject(inner)
        val st = Store(c)
        val header = JSONObject().put("app", "Nova Glucose").put("name", st.patientName.ifBlank { st.fileName })
            .put("created", root.optLong("created")).put("version", Ui.version(c)).put("contents", root.optJSONObject("contents"))
            .toString().toByteArray(Charsets.UTF_8)
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val bo = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bo).use { it.write(inner.toByteArray(Charsets.UTF_8)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFrom(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        val enc = cipher.doFinal(bo.toByteArray())
        return java.io.ByteArrayOutputStream().apply {
            write(MAGIC); write(3)
            write(java.nio.ByteBuffer.allocate(4).putInt(header.size).array()); write(header)
            write(salt); write(iv); write(enc)
        }.toByteArray()
    }

    /** Readable details shown before the password is typed (no health data). */
    fun header(b: ByteArray): JSONObject? = try {
        if (!isEncrypted(b)) JSONObject(String(b, Charsets.UTF_8)).let { o ->
            JSONObject().put("app", "Nova Glucose").put("created", o.optLong("created")).put("version", o.optString("version", "older build"))
                .put("contents", o.optJSONObject("contents"))
        } else {
            val n = java.nio.ByteBuffer.wrap(b, 9, 4).int
            JSONObject(String(b, 13, n, Charsets.UTF_8))
        }
    } catch (_: Exception) { null }

    /** The full backup JSON inside the file (decrypts format 3; older files are returned as they are). */
    fun unpack(b: ByteArray, password: String): String {
        if (!isEncrypted(b)) {
            val t = String(b, Charsets.UTF_8)
            require(isBackup(t)) { "This isn't a Nova Glucose backup file." }
            return t
        }
        require(b[8].toInt() == 3) { "This backup was made by a newer Nova Glucose — update the app first." }
        val n = java.nio.ByteBuffer.wrap(b, 9, 4).int
        val header = b.copyOfRange(13, 13 + n)
        val salt = b.copyOfRange(13 + n, 29 + n); val iv = b.copyOfRange(29 + n, 41 + n)
        val enc = b.copyOfRange(41 + n, b.size)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyFrom(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            cipher.doFinal(enc)
        } catch (_: Exception) { throw IllegalArgumentException("Wrong backup password.") }
        return java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(plain)).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun isBackupFile(b: ByteArray) = isEncrypted(b) || isBackup(String(b, Charsets.UTF_8))
    fun restoreFile(c: Context, b: ByteArray, password: String) = restore(c, unpack(b, password), password)
    fun verifyFile(b: ByteArray, c: Context, password: String): String {
        require(isEncrypted(b)) { "file is not encrypted" }
        return verify(unpack(b, password), c, password) + " · encrypted ✓"
    }

    fun isBackup(text: String) = try { JSONObject(text).optString("app") == "Nova Glucose" } catch (_: Exception) { false }

    /** Restores settings; returns a summary line. Throws with a clear message on a bad file. */
    fun restore(c: Context, text: String, password: String): String {
        val root = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("This isn't a Nova Glucose backup file.") }
        if (root.optString("app") != "Nova Glucose") throw IllegalArgumentException("This isn't a Nova Glucose backup file.")
        val st = Store(c)
        val e = st.raw.edit()
        val settings = root.optJSONObject("settings") ?: JSONObject()
        for (k in settings.keys()) {
            if (Store.privateKey(k)) continue
            val o = settings.getJSONObject(k)
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.optBoolean("v"))
                "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v"))
                "s" -> e.putString(k, o.optString("v"))
            }
        }
        // Per-phone: Google Drive must be connected again on this phone.
        e.putBoolean("drive_on", false)
        e.commit()
        var note = "no passwords or API keys in the file"
        root.optJSONObject("secrets")?.let { enc ->
            note = try {
                val s = JSONObject(decrypt(enc, password))
                if (s.optString("password").isNotBlank()) st.password = s.optString("password")
                var n = 0
                Providers.all.forEach { p -> s.optString("key_${p.id}").takeIf { it.isNotBlank() }?.let { st.setKey(p, it); n++ } }
                st.backupPw = password
                "LibreLinkUp login ${if (st.password.isNotBlank()) "✓" else "–"} · $n API key(s) ✓"
            } catch (_: Exception) {
                throw IllegalArgumentException("Wrong backup password.")
            }
        }
        st.token = ""
        // Data: readings, meals, doses (format 2)
        var dataNote = ""
        root.optString("data").takeIf { it.isNotBlank() }?.let { packed ->
            val d = JSONObject(gunzip(packed))
            val db = Db.get(c)
            val ra = d.getJSONArray("readings")
            val list = ArrayList<Reading>(ra.length())
            for (i in 0 until ra.length()) ra.getJSONArray(i).let { list += Reading(it.getLong(0), it.getInt(1), it.optInt(2)) }
            list.chunked(5000).forEach { db.addReadings(it) }
            var nm = 0
            val ma = d.getJSONArray("meals")
            val local = java.io.File(c.filesDir, "meals")
            for (i in 0 until ma.length()) {
                val o = ma.getJSONObject(i); val ts = o.getLong("ts")
                if (db.hasMealAt(ts)) continue
                val pn = o.optString("photo")
                val path = if (pn.isNotBlank() && java.io.File(local, pn).exists()) java.io.File(local, pn).absolutePath else ""
                val id = db.addMeal(Meal(0, ts, o.optString("title"), o.optString("items", "[]"), o.optInt("carbs"), o.optInt("low"),
                    o.optInt("high"), o.optInt("mgAt"), path, o.optString("note")))
                if (o.has("aiItems")) db.setAi(id, o.optString("aiItems"), o.optInt("aiCarbs"))
                nm++
            }
            var nd = 0
            val da = d.getJSONArray("doses")
            for (i in 0 until da.length()) {
                val o = da.getJSONObject(i); val ts = o.getLong("ts")
                if (db.hasDoseAt(ts)) continue
                db.addDose(Dose(0, ts, o.optString("kind", "rapid"), o.optDouble("units"), o.optInt("carbs"), o.optInt("wait"),
                    o.optInt("mgAt"), o.optString("note"))); nd++
            }
            dataNote = " · ${ra.length()} readings · $nm meals · $nd doses"
        }
        return "Settings ${settings.length()} ✓ · $note$dataNote"
    }

    private fun keyFrom(password: String, salt: ByteArray): SecretKeySpec {
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITER, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun encrypt(plain: String, password: String): JSONObject {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFrom(password, salt), GCMParameterSpec(128, iv))
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        return JSONObject().put("alg", "AES-256-GCM/PBKDF2-SHA256").put("iter", ITER)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(out))
    }

    private fun decrypt(o: JSONObject, password: String): String {
        fun d(s: String) = Base64.decode(o.getString(s), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyFrom(password, d("salt")), GCMParameterSpec(128, d("iv")))
        return String(cipher.doFinal(d("data")), Charsets.UTF_8)
    }
}
