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
        val ch = Sounds.channel(c, "other")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ch) else @Suppress("DEPRECATION") Notification.Builder(c)
        val open = PendingIntent.getActivity(c, 7902, Intent(c, MainActivity::class.java).putExtra("openchat", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = b.setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle("💬 ${m.name}").setContentText(m.text)
            .setStyle(Notification.BigTextStyle().bigText(m.text)).setAutoCancel(true).setContentIntent(open).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE, n)
        Sounds.playMessage(c)
    }

    /** The conversation pop-up. */
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
            val msgs = all(a); val sig = msgs.size * 1000 + msgs.sumOf { seenLine(a, it).length }
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
            sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
            markRead(a, unread(a).map { it.id })
        }
        row.addView(Ui.button(a, "Send", true) {
            val t = input.text.toString(); if (t.isBlank()) return@button
            send(a, t); input.setText(""); render(true)
        }, Ui.lp(-2, -2))
        root.addView(row, Ui.lp(top = Ui.dp(a, 10)))
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
