package com.nova.glucose

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import org.json.JSONObject

/**
 * 🔔 Family bell: any family phone rings another one ("Papa would like to talk with you").
 * Urgent: full screen, alarm stream, through silent and Do Not Disturb; again every 2 min until answered (10 min at most).
 * The answer (📞 Calling you now / ⏳ In 10 min / ✓ OK) goes back to the phone that rang.
 */
object Bell {
    private const val KEY = "sync_bell"          // {id: {id, to, toName, by, byUid, u, ans, ansTs, ansBy}}
    private const val CH = "nova_bell"
    private const val NOTE = 7970
    const val REPEAT_MS = 2 * 60_000L
    const val MAX_MS = 10 * 60_000L
    val ANSWERS = listOf("call" to "📞 Calling you now", "later" to "⏳ In 10 min", "ok" to "✓ OK")
    fun answerText(a: String) = L.s(ANSWERS.firstOrNull { it.first == a }?.second ?: a)

    private fun all(c: Context): JSONObject = try { JSONObject(Store(c).raw.getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun save(c: Context, o: JSONObject) {
        val now = System.currentTimeMillis()          // keep 3 days
        o.keys().asSequence().toList().forEach { if (now - (o.optJSONObject(it)?.optLong("u") ?: 0L) > 3 * 24 * 3600_000L) o.remove(it) }
        Store(c).raw.edit().putString(KEY, o.toString()).apply()
    }
    fun get(c: Context, id: String): JSONObject? = all(c).optJSONObject(id)

    /** Ring [toUid] from this phone. */
    fun send(c: Context, toUid: String, toName: String) {
        val st = Store(c); val now = System.currentTimeMillis()
        val id = "${st.syncUid.take(6)}$now"
        val e = JSONObject().put("id", id).put("to", toUid).put("toName", toName).put("by", Chat.myName(st)).put("byUid", st.syncUid).put("u", now)
        val o = all(c); o.put(id, e); save(c, o)
        Db.get(c).queue("bell-$id", "bell")
        ErrorLog.e("Bell", "rang $toName")
        FamilySync.kick(c)
    }

    /** The last bell this phone sent (last 30 min), for the card in 💬 Messages. */
    fun lastSent(c: Context): JSONObject? {
        val me = Store(c).syncUid; val now = System.currentTimeMillis()
        return all(c).let { o -> o.keys().asSequence().mapNotNull { o.optJSONObject(it) } }
            .filter { it.optString("byUid") == me && now - it.optLong("u") < 30 * 60_000L }.maxByOrNull { it.optLong("u") }
    }

    fun item(c: Context, gid: String): JSONObject? {
        val k = gid.removePrefix("bell-")
        val e = get(c, k) ?: get(c, k.substringBeforeLast("-")) ?: return null
        return JSONObject(e.toString()).put("t", "bell").put("g", gid)
    }

    fun received(c: Context, it: JSONObject): String {
        val st = Store(c)
        val id = it.optString("id").ifBlank { return "" }
        val o = all(c); val old = o.optJSONObject(id)
        val x = JSONObject(it.toString()).apply { remove("t"); remove("g") }
        if (old != null && old.optString("ans").isNotBlank() && x.optString("ans").isBlank()) return ""      // already answered here
        if (old != null && old.optString("ans") == x.optString("ans")) return ""
        o.put(id, x); save(c, o)
        val now = System.currentTimeMillis()
        if (x.optString("to") == st.syncUid && x.optString("ans").isBlank() && old == null && now - x.optLong("u") < MAX_MS) ring(c, x)
        if (x.optString("ans").isNotBlank()) {
            if (x.optString("to") == st.syncUid) stop(c)                          // answered elsewhere
            if (x.optString("byUid") == st.syncUid) answered(c, x)
        }
        return "bell"
    }

    /** Every minute (monitor): ring again every 2 min until answered, 10 min at most. */
    fun maybe(c: Context) {
        val st = Store(c); val now = System.currentTimeMillis()
        val o = all(c)
        o.keys().asSequence().mapNotNull { o.optJSONObject(it) }
            .filter { it.optString("to") == st.syncUid && it.optString("ans").isBlank() && now - it.optLong("u") < MAX_MS }
            .forEach { e -> if (now - st.raw.getLong("bell_ring_" + e.optString("id"), 0L) >= REPEAT_MS - 5_000L) ring(c, e) }
    }

    /** A bell to this phone, not answered for [ms] (and less than 30 min old). */
    fun unanswered(c: Context, ms: Long): JSONObject? {
        val me = Store(c).syncUid; val now = System.currentTimeMillis(); val o = all(c)
        return o.keys().asSequence().mapNotNull { o.optJSONObject(it) }
            .filter { it.optString("to") == me && it.optString("ans").isBlank() && now - it.optLong("u") in ms..30 * 60_000L }.maxByOrNull { it.optLong("u") }
    }

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH, "🔔 Family bell (urgent)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
    }

    fun title(e: JSONObject) = "🔔 " + L.s("{0} would like to talk with you").replace("{0}", e.optString("by"))

    private fun ring(c: Context, e: JSONObject) {
        val st = Store(c); val id = e.optString("id")
        st.raw.edit().putLong("bell_ring_$id", System.currentTimeMillis()).apply()
        if (!st.onFor("bell")) { ErrorLog.e("Bell", "bell from ${e.optString("by")} not rung: turned off on this phone"); return }
        channel(c)
        val i = Intent(c, BellActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, NOTE, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val rang = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(e.optLong("u")))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH) else @Suppress("DEPRECATION") Notification.Builder(c)
        val n = b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title(e))
            .setContentText(L.s("Rang at {0} · tap to answer").replace("{0}", rang))
            .setCategory(Notification.CATEGORY_CALL).setContentIntent(pi).setFullScreenIntent(pi, true).setOngoing(true).build()
        try { c.getSystemService(NotificationManager::class.java).notify(NOTE, n) } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(c)) try { c.startActivity(i) } catch (_: Exception) { }
        when {
            Meeting.on(st) -> Meeting.strong(c, 10)                                       // 📳 meeting mode: vibration only
            Sounds.isSilent(st, "bell") -> Meeting.strong(c, 8)                           // bell set to Silent: vibration only
            else -> AlarmPlayer.start(c, "bell", title(e), L.s("Rang at {0}").replace("{0}", rang), notify = false)
        }
        ErrorLog.e("Bell", "ringing: bell from ${e.optString("by")}")
    }

    fun answer(c: Context, id: String, ans: String) {
        val st = Store(c); val o = all(c); val e = o.optJSONObject(id) ?: return
        e.put("ans", ans).put("ansTs", System.currentTimeMillis()).put("ansBy", Chat.myName(st)).put("u", e.optLong("u"))
        o.put(id, e); save(c, o)
        Db.get(c).queue("bell-$id-${System.currentTimeMillis()}", "bell")
        stop(c); FamilySync.kick(c)
        ErrorLog.e("Bell", "answered: $ans")
    }

    fun stop(c: Context) {
        try { c.getSystemService(NotificationManager::class.java).cancel(NOTE) } catch (_: Exception) { }
        if (AlarmPlayer.kind == "bell") AlarmPlayer.dismiss(c)
    }

    private fun answered(c: Context, e: JSONObject) {
        try {
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, Sounds.channel(c, "msg")) else @Suppress("DEPRECATION") Notification.Builder(c)
            val t = L.s("{0} answered: {1}").replace("{0}", e.optString("ansBy").ifBlank { e.optString("toName") }).replace("{1}", answerText(e.optString("ans")))
            c.getSystemService(NotificationManager::class.java).notify(NOTE + 1, b.setSmallIcon(c.applicationInfo.icon).setContentTitle(t)
                .setContentText(L.s("Your bell {0} · seen {1}").replace("{0}", hm.format(java.util.Date(e.optLong("u")))).replace("{1}", hm.format(java.util.Date(e.optLong("ansTs")))))
                .setAutoCancel(true).build())
            Sounds.after(c, "msg")
        } catch (_: Exception) { }
    }
}

/** Full screen on the phone that is rung: who rings, and three answers. */
class BellActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); render() }
    private fun render() {
        val id = intent.getStringExtra("id") ?: run { finish(); return }
        val e = Bell.get(this, id) ?: run { finish(); return }
        val root = Ui.col(this, 24).apply { gravity = Gravity.CENTER; setBackgroundColor(Ui.BG) }
        root.addView(Ui.text(this, "🔔", 64f, Ui.TEXT).apply { gravity = Gravity.CENTER })
        root.addView(Ui.text(this, Bell.title(e).removePrefix("🔔 "), 24f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 8)))
        val rang = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(e.optLong("u")))
        root.addView(Ui.text(this, L.s("Rang at {0}").replace("{0}", rang) + if (e.optString("ans").isBlank()) " · " + L.s("rings again in 2 min") else "", 14f, Ui.MUTED)
            .apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 8)))
        if (e.optString("ans").isNotBlank()) root.addView(Ui.text(this, "✓ " + Bell.answerText(e.optString("ans")), 16f, Ui.GOOD).apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 14)))
        else Bell.ANSWERS.forEachIndexed { k, (a, label) ->
            root.addView(Ui.button(this, L.s(label), k == 0) { Bell.answer(this, id, a); finish() }, Ui.lp(top = Ui.dp(this, if (k == 0) 22 else 10)))
        }
        root.addView(Ui.button(this, "Close") { Bell.stop(this); finish() }, Ui.lp(top = Ui.dp(this, 18)))
        setContentView(android.widget.ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Ui.BG); addView(root) })
    }
}
