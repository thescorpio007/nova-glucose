package com.nova.glucose

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 💬 Family messages: short texts between the family phones (parents and patient), through family sync
 * (end-to-end encrypted), kept 7 days. "✓ seen by …" under each message. New message: notification + Nova Blip at alarm volume.
 */
object Chat {
    private const val KEEP = 7L * 24 * 3600_000
    private const val MSGS = "sync_chat"          // [{id, uid, n, ts, x}]
    private const val SEEN = "sync_chat_seen"     // {mid: {uid: [name, ts]}}
    private const val READ = "sync_chat_read"     // [mid] read on this phone

    class Msg(val id: String, val uid: String, val name: String, val ts: Long, val text: String)

    private fun arr(c: Context, k: String) = try { JSONArray(Store(c).raw.getString(k, "[]")) } catch (_: Exception) { JSONArray() }
    private fun obj(c: Context, k: String) = try { JSONObject(Store(c).raw.getString(k, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun put(c: Context, k: String, v: Any) { Store(c).raw.edit().putString(k, v.toString()).apply() }

    fun myName(st: Store) = st.syncName.ifBlank { L.s(st.roleName()) }

    fun all(c: Context): List<Msg> {
        val a = arr(c, MSGS); val now = System.currentTimeMillis()
        val l = (0 until a.length()).map { a.getJSONObject(it) }.map { Msg(it.optString("id"), it.optString("uid"), it.optString("n"), it.optLong("ts"), it.optString("x")) }
        val keep = l.filter { now - it.ts < KEEP }
        if (keep.size != l.size) put(c, MSGS, JSONArray().apply { keep.forEach { m -> put(json(m)) } })
        return keep.sortedBy { it.ts }
    }

    private fun json(m: Msg) = JSONObject().put("id", m.id).put("uid", m.uid).put("n", m.name).put("ts", m.ts).put("x", m.text)

    @Synchronized private fun add(c: Context, m: Msg): Boolean {
        val a = arr(c, MSGS)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == m.id) return false
        a.put(json(m)); put(c, MSGS, a); return true
    }

    fun send(c: Context, text: String) {
        val st = Store(c); val t = text.trim(); if (t.isEmpty()) return
        val m = Msg(java.util.UUID.randomUUID().toString().take(12), st.syncUid, myName(st), System.currentTimeMillis(), t.take(500))
        add(c, m); markRead(c, listOf(m.id), share = false)
        Db.get(c).queue("msg-${m.id}", "msg")
    }

    fun item(c: Context, gid: String): JSONObject? {
        if (gid.startsWith("msg-")) {
            val id = gid.removePrefix("msg-"); val m = all(c).firstOrNull { it.id == id } ?: return null
            return json(m).put("t", "msg").put("g", gid).put("u", m.ts)
        }
        if (gid.startsWith("mseen-")) {
            val st = Store(c); val mid = gid.removePrefix("mseen-").substringBeforeLast("-")
            val s = obj(c, SEEN).optJSONObject(mid)?.optJSONArray(st.syncUid) ?: return null
            return JSONObject().put("t", "mseen").put("g", gid).put("u", s.optLong(1)).put("mid", mid).put("uid", st.syncUid).put("n", s.optString(0)).put("ts", s.optLong(1))
        }
        return null
    }

    fun received(c: Context, it: JSONObject): String {
        val st = Store(c)
        when (it.optString("t")) {
            "msg" -> {
                val m = Msg(it.optString("id"), it.optString("uid"), it.optString("n"), it.optLong("ts"), it.optString("x"))
                if (m.id.isBlank() || System.currentTimeMillis() - m.ts > KEEP) return ""
                if (!add(c, m)) return ""
                if (m.uid != st.syncUid) notify(c, m)
                return "msg"
            }
            "mseen" -> {
                val mid = it.optString("mid"); val uid = it.optString("uid"); if (mid.isBlank() || uid.isBlank()) return ""
                val s = obj(c, SEEN); val o = s.optJSONObject(mid) ?: JSONObject()
                if (o.has(uid)) return ""
                o.put(uid, JSONArray().put(it.optString("n")).put(it.optLong("ts"))); s.put(mid, o); put(c, SEEN, s)
                return "mseen"
            }
        }
        return ""
    }

    private fun readSet(c: Context): MutableSet<String> { val a = arr(c, READ); return (0 until a.length()).map { a.getString(it) }.toMutableSet() }

    fun unread(c: Context): List<Msg> { val st = Store(c); val r = readSet(c); return all(c).filter { it.uid != st.syncUid && it.id !in r } }

    /** Marks messages as read here and tells the others "✓ seen". */
    fun markRead(c: Context, ids: List<String>, share: Boolean = true) {
        if (ids.isEmpty()) return
        val st = Store(c); val r = readSet(c); r += ids
        val live = all(c).map { it.id }.toSet()
        put(c, READ, JSONArray(r.filter { it in live || it in ids }))
        if (!share) return
        val s = obj(c, SEEN); val now = System.currentTimeMillis()
        ids.forEach { mid -> val o = s.optJSONObject(mid) ?: JSONObject(); o.put(st.syncUid, JSONArray().put(myName(st)).put(now)); s.put(mid, o)
            Db.get(c).queue("mseen-$mid-${st.syncUid}", "mseen", kick = false) }
        put(c, SEEN, s)
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE)
        FamilySync.kick(c)
    }

    /** "✓ seen by Aycha 13:11" — the other phones that read it. */
    fun seenLine(c: Context, m: Msg): String {
        val o = obj(c, SEEN).optJSONObject(m.id) ?: return ""
        val names = o.keys().asSequence().filter { it != m.uid }.mapNotNull { o.optJSONArray(it) }.sortedBy { it.optLong(1) }.toList()
        if (names.isEmpty()) return ""
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        return "✓ " + L.s("seen by") + " " + if (names.size == 1) "${names[0].optString(0)} ${hm.format(Date(names[0].optLong(1)))}" else names.joinToString(", ") { it.optString(0) }
    }

    private const val NOTE = 7901
    private fun notify(c: Context, m: Msg) {
        if (!Store(c).onFor("msg")) return
        val ch = Sounds.channel(c, "msg")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ch) else @Suppress("DEPRECATION") Notification.Builder(c)
        val open = PendingIntent.getActivity(c, 7902, Intent(c, MainActivity::class.java).putExtra("openchat", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = b.setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle("💬 ${m.name}").setContentText(m.text)
            .setStyle(Notification.BigTextStyle().bigText(m.text)).setAutoCancel(true).setContentIntent(open).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE, n)
        Sounds.playMessage(c)
    }

    /** The conversation pop-up. */
    /** 📍 Parents: locate the patient's phone. */
    private fun locate(a: Activity, done: () -> Unit) {
        val app = a.applicationContext
        Thread {
            val res = try { FamilySync.memberDocs(app) } catch (_: Exception) { null }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                val p = res?.let { Members.rows(a, it).first.firstOrNull { r -> r.patient && !r.m.me } }
                if (p == null) { Ui.toast(a, "The patient's phone was not found in the family"); return@runOnUiThread }
                Locate.ask(a, p.m.uid, p.name); Ui.toast(a, L.s("Locating {0}…").replace("{0}", p.name)); done()
            }
        }.start()
    }

    private var tileKey = ""; private var tileBmp: android.graphics.Bitmap? = null
    /** Position card: small map (OpenStreetMap), time, accuracy, battery, live follow, Open in Google Maps. */
    private fun locCard(a: Activity, e: JSONObject): LinearLayout {
        val hm = SimpleDateFormat("HH:mm", Locale.US); val hms = SimpleDateFormat("HH:mm:ss", Locale.US)
        val card = Ui.col(a).apply { background = Ui.round(Ui.CARD2, Ui.dp(a, 12).toFloat()); setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10)) }
        val auto = e.optString("why").isNotBlank()
        card.addView(Ui.text(a, (if (auto) "🚨 " else "📍 ") + L.s("{0}'s phone").replace("{0}", e.optString("by")) + " · " + hm.format(Date(e.optLong("u"))), 14f, Ui.TEXT, true))
        if (auto) card.addView(Ui.text(a, L.s("sent automatically") + ": " + e.optString("why"), 12f, Ui.WARN))
        if (!e.has("lat")) { card.addView(Ui.text(a, L.s("No position available (GPS / internet)") + " · 🔋 ${e.optInt("batt")}%", 13f, Ui.WARN)); return card }
        val img = android.widget.ImageView(a).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF13201B.toInt()) }
        card.addView(img, LinearLayout.LayoutParams(-1, Ui.dp(a, 160)).apply { topMargin = Ui.dp(a, 8) })
        val lat = e.optDouble("lat"); val lon = e.optDouble("lon"); val key = "%.5f,%.5f".format(Locale.US, lat, lon)
        if (key == tileKey && tileBmp != null) img.setImageBitmap(tileBmp) else Thread {
            try {
                val z = 16; val n = Math.pow(2.0, z.toDouble())
                val xf = (lon + 180) / 360 * n; val yf = (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * n
                val out = android.graphics.Bitmap.createBitmap(768, 512, android.graphics.Bitmap.Config.ARGB_8888); val cv = android.graphics.Canvas(out)
                val px = xf * 256; val py = yf * 256; val ox = px - 384; val oy = py - 256
                for (tx in Math.floor(ox / 256).toInt()..Math.floor((ox + 767) / 256).toInt()) for (ty in Math.floor(oy / 256).toInt()..Math.floor((oy + 511) / 256).toInt()) {
                    val conn = java.net.URL("https://tile.openstreetmap.org/$z/$tx/$ty.png").openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "NovaGlucose/1.0 (family safety app)"); conn.connectTimeout = 8000; conn.readTimeout = 8000
                    val t = conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } ?: continue
                    cv.drawBitmap(t, (tx * 256 - ox).toFloat(), (ty * 256 - oy).toFloat(), null)
                }
                val mpp = 156543.03392 * Math.cos(Math.toRadians(lat)) / n          // metres per pixel
                val r = (e.optInt("acc", 10) / mpp).toFloat().coerceIn(8f, 240f)
                val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                pnt.color = 0x332EC4B6; cv.drawCircle(384f, 256f, r, pnt); pnt.style = android.graphics.Paint.Style.STROKE; pnt.strokeWidth = 3f; pnt.color = 0xFF2EC4B6.toInt(); cv.drawCircle(384f, 256f, r, pnt)
                pnt.style = android.graphics.Paint.Style.FILL; pnt.color = 0xFFE53935.toInt(); cv.drawCircle(384f, 256f, 14f, pnt); pnt.color = 0xFFFFFFFF.toInt(); cv.drawCircle(384f, 256f, 5f, pnt)
                pnt.textSize = 18f; pnt.color = 0xFF333333.toInt(); cv.drawText("© OpenStreetMap", 600f, 500f, pnt)
                tileKey = key; tileBmp = out
                a.runOnUiThread { img.setImageBitmap(out) }
            } catch (_: Exception) { }
        }.start()
        card.addView(Ui.text(a, "± ${e.optInt("acc")} m · ${e.optString("prov").uppercase()} · 🔋 ${e.optInt("batt")}% · " + L.s("position {0}").replace("{0}", hms.format(Date(e.optLong("fix")))), 13f, Ui.TEXT)
            .apply { setPadding(0, Ui.dp(a, 6), 0, 0) })
        val until = e.optLong("until")
        if (until > System.currentTimeMillis()) card.addView(Ui.text(a, "🔴 " + L.s("Live: updating every 1 min until {0}").replace("{0}", hm.format(Date(until))), 12f, Ui.MUTED))
        card.addView(Ui.button(a, "🗺 " + L.s("Open in Google Maps"), true) {
            try { a.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Locate.mapsUrl(e)))) } catch (_: Exception) { } }, Ui.lp(top = Ui.dp(a, 8)))
        return card
    }

    /** 🔔 Choose a family phone and ring it. */
    private fun pickBell(a: Activity, done: () -> Unit) {
        val app = a.applicationContext
        Ui.toast(a, "…")
        Thread {
            val res = try { FamilySync.memberDocs(app) } catch (_: Exception) { null }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                val rows = res?.let { Members.rows(a, it).first.filter { r -> !r.m.me } }.orEmpty()
                if (rows.isEmpty()) { Ui.toast(a, "No other family phone found"); return@runOnUiThread }
                AlertDialog.Builder(a).setTitle("🔔 " + L.s("Ring to talk"))
                    .setItems(rows.map { it.name + if (it.patient) " · " + L.s("patient") else "" }.toTypedArray()) { _, w ->
                        Bell.send(a, rows[w].m.uid, rows[w].name); Ui.toast(a, L.s("Ringing {0}…").replace("{0}", rows[w].name)); done() }
                    .setNegativeButton(L.s("Cancel"), null).show()
            }
        }.start()
    }

    fun show(a: Activity, onClose: () -> Unit = {}) {
        val st = Store(a)
        if (st.syncFam.isBlank()) { Ui.toast(a, "Turn on Family sync to send messages"); return }
        val root = Ui.col(a, 18)
        root.addView(Ui.text(a, L.s("All family phones · kept 7 days"), 12f, Ui.MUTED))
        val list = Ui.col(a)
        val sv = ScrollView(a).apply { addView(list) }
        root.addView(sv, LinearLayout.LayoutParams(-1, Ui.dp(a, 380)).apply { topMargin = Ui.dp(a, 8) })
        val row = Ui.row(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = Ui.input(a, "Type a message…", multi = false)
        row.addView(input, Ui.lp(0, -2, weight = 1f, end = Ui.dp(a, 8)))
        val hm = SimpleDateFormat("HH:mm", Locale.US); val dm = SimpleDateFormat("d MMM HH:mm", Locale.US)
        var shown = -1
        fun render(force: Boolean = false) {
            val msgs = all(a); val sig = msgs.size * 1000 + msgs.sumOf { seenLine(a, it).length } + (Locate.latest(a)?.optLong("u")?.rem(100000)?.toInt() ?: 0) + (if (Locate.pending(a) != null) 13 else 0) + (Bell.lastSent(a)?.let { it.optString("ans").length * 7 + 3 } ?: 0)
            if (!force && sig == shown) return
            shown = sig
            list.removeAllViews()
            if (msgs.isEmpty()) list.addView(Ui.text(a, "No messages yet.", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 20), 0, 0) })
            val today = WatchData.dayStart(System.currentTimeMillis())
            msgs.forEach { m ->
                val me = m.uid == st.syncUid
                val b = Ui.col(a).apply {
                    background = Ui.round(if (me) 0xFF1E6E68.toInt() else Ui.CARD2, Ui.dp(a, 14).toFloat())
                    setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8))
                }
                b.addView(Ui.text(a, (if (me) "${m.name} (${L.s("me")})" else m.name) + " · " + (if (m.ts >= today) hm else dm).format(Date(m.ts)), 11f,
                    if (me) 0xFFC8F0EC.toInt() else Ui.ACCENT, true))
                b.addView(Ui.text(a, m.text, 15f, Ui.TEXT))
                seenLine(a, m).takeIf { it.isNotEmpty() }?.let { b.addView(Ui.text(a, it, 10.5f, if (me) 0xFFAADCD7.toInt() else Ui.MUTED)) }
                val holder = LinearLayout(a).apply { gravity = if (me) Gravity.END else Gravity.START }
                holder.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (me) marginStart = Ui.dp(a, 40) else marginEnd = Ui.dp(a, 40) })
                list.addView(holder, Ui.lp(top = Ui.dp(a, 6)))
            }
            Bell.lastSent(a)?.let { e ->
                val hm2 = SimpleDateFormat("HH:mm", Locale.US)
                val card = Ui.col(a).apply { background = Ui.round(0xFF3A1416.toInt(), Ui.dp(a, 12).toFloat()); setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8)) }
                card.addView(Ui.text(a, "🔔 " + L.s("You rang {0} · {1}").replace("{0}", e.optString("toName")).replace("{1}", hm2.format(Date(e.optLong("u")))), 13.5f, Ui.TEXT, true))
                if (e.optString("ans").isBlank()) card.addView(Ui.text(a, L.s("Ringing again every 2 min until answered (up to {0})").replace("{0}", hm2.format(Date(e.optLong("u") + Bell.MAX_MS))), 11.5f, Ui.MUTED))
                else card.addView(Ui.text(a, e.optString("ansBy") + ": " + Bell.answerText(e.optString("ans")) + " · " + hm2.format(Date(e.optLong("ansTs"))), 13.5f, Ui.GOOD))
                list.addView(card, Ui.lp(top = Ui.dp(a, 10)))
            }
            Locate.pending(a)?.let { q -> list.addView(Ui.text(a, "📍 " + L.s("Locating {0}… (asked {1})").replace("{0}", q.optString("toName"))
                .replace("{1}", SimpleDateFormat("HH:mm", Locale.US).format(Date(q.optLong("u")))), 13f, Ui.ACCENT).apply { setPadding(0, Ui.dp(a, 10), 0, 0) }) }
            if (!st.isPatient) Locate.latest(a)?.let { e -> list.addView(locCard(a, e), Ui.lp(top = Ui.dp(a, 10))) }
            sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
            markRead(a, unread(a).map { it.id })
        }
        row.addView(Ui.button(a, "Send", true) {
            val t = input.text.toString(); if (t.isBlank()) return@button
            send(a, t); input.setText(""); render(true)
        }, Ui.lp(-2, -2))
        root.addView(row, Ui.lp(top = Ui.dp(a, 10)))
        root.addView(Ui.button(a, "🔔 " + L.s("Ring to talk")) { pickBell(a) { render(true) } }.apply { setBackgroundColor(0xFFC62828.toInt()) }, Ui.lp(top = Ui.dp(a, 8)))
        if (!st.isPatient) root.addView(Ui.button(a, "📍 " + L.s("Locate")) { locate(a) { render(true) } }.apply { setBackgroundColor(0xFF1565C0.toInt()) }, Ui.lp(top = Ui.dp(a, 8)))
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable { override fun run() { render(); h.postDelayed(this, 3000) } }
        val dlg = AlertDialog.Builder(a).setTitle("💬 " + L.s("Family messages")).setView(root)
            .setPositiveButton(L.s("Close"), null).create()
        dlg.setOnDismissListener { h.removeCallbacks(tick); onClose() }
        dlg.show()
        render(true); h.postDelayed(tick, 3000)
        FamilySync.kick(a)
    }
}
