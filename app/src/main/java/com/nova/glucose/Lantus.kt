package com.nova.glucose

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * 💉 Night Lantus reminder: at the chosen time (20:30 by default) every family phone shows a full-screen pop-up with the
 * usual dose (− / + to change). "✓ Injected" saves it as LI (shared); whoever confirms first counts. Not confirmed:
 * again every 15 min, "not confirmed yet" after 30 min. A changed dose becomes the usual dose from that day.
 */
object Lantus {
    const val NOTE = 7951
    private const val REPEAT = 15 * 60_000L
    private const val WINDOW = 6 * 3600_000L

    fun at(st: Store, day: Long = System.currentTimeMillis()): Long {
        val (h, m) = st.lantusTime.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 20) to (it.getOrNull(1)?.toIntOrNull() ?: 30) }
        return Calendar.getInstance().apply { timeInMillis = day; set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23)); set(Calendar.MINUTE, m.coerceIn(0, 59))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    }

    fun usual(c: Context): Double {
        val st = Store(c)
        return st.longDose.replace(',', '.').toDoubleOrNull()
            ?: Db.get(c).doses(System.currentTimeMillis() - 30L * 86_400_000, System.currentTimeMillis()).filter { it.kind == "long" }.maxByOrNull { it.ts }?.units ?: 0.0
    }

    private fun state(c: Context, day: String) = Store(c).raw.getString("lantus_st_$day", "") ?: ""

    fun setState(c: Context, day: String, s: String, share: Boolean) {
        Store(c).raw.edit().putString("lantus_st_$day", s).apply()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE)
        if (share) Db.get(c).queue("lantus-$day", "lnt")
    }

    fun item(c: Context, gid: String): JSONObject? {
        val day = gid.removePrefix("lantus-"); val s = state(c, day).ifBlank { return null }
        return JSONObject().put("t", "lnt").put("g", gid).put("u", System.currentTimeMillis()).put("day", day).put("s", s)
    }

    fun received(c: Context, it: JSONObject): String {
        val day = it.optString("day"); val s = it.optString("s"); if (day.isBlank() || s.isBlank() || state(c, day).isNotBlank()) return ""
        setState(c, day, s, share = false); return "lnt"
    }

    /** Every minute from the monitor. */
    fun maybe(c: Context) {
        val st = Store(c)
        if (!st.lantusOn || !st.onFor("lantus")) return
        val now = System.currentTimeMillis(); val t = at(st)
        if (now < t || now - t > WINDOW) return
        val day = WatchData.dayKey(t)
        if (state(c, day).isNotBlank()) return
        if (Db.get(c).doses(t - 2 * 3600_000L, now + 60_000).any { it.kind == "long" }) { setState(c, day, "done", share = false); return }
        if (now < st.raw.getLong("lantus_next", 0L)) return
        st.raw.edit().putLong("lantus_next", now + REPEAT).apply()
        fire(c, now - t)
    }

    private fun fire(c: Context, late: Long) {
        val u = usual(c)
        val title = if (late >= 30 * 60_000L) L.s("💉 Night Lantus not confirmed yet") else L.s("💉 Night Lantus")
        val text = (if (u > 0) "${fmt(u)} u · " else "") + L.s("tap to confirm the injection")
        val i = Intent(c, LantusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(c, 7952, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ch = Sounds.channel(c, "lantus")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ch) else @Suppress("DEPRECATION") Notification.Builder(c)
        val n = b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER).setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE, n)
        if (Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c)) try { c.startActivity(i) } catch (_: Exception) { }
        Sounds.after(c, "lantus")
    }

    fun fmt(u: Double) = if (u % 1.0 == 0.0) u.toInt().toString() else String.format(Locale.US, "%.1f", u)

    /** "10 Nov 18 → 20 u" for every change of the long-acting dose (AI review and reports). */
    fun changes(doses: List<Dose>): List<String> {
        val f = java.text.SimpleDateFormat("d MMM", Locale.US)
        val l = doses.filter { it.kind == "long" }.sortedBy { it.ts }
        return l.zipWithNext().filter { (a, b) -> a.units != b.units }.map { (a, b) -> "${f.format(java.util.Date(b.ts))}: ${fmt(a.units)} → ${fmt(b.units)} u" }
    }
}

class LantusActivity : BaseActivity() {
    private var u = 0.0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val st = Store(this)
        val usual = Lantus.usual(this); u = usual
        val root = Ui.col(this, 24).apply { gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Ui.BG) }
        root.addView(Ui.text(this, "💉", 64f, Ui.TEXT).apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 30)))
        root.addView(Ui.text(this, "Night Lantus", 28f, Ui.TEXT, true).apply { gravity = Gravity.CENTER })
        root.addView(Ui.text(this, st.longName.ifBlank { L.s("Long-acting insulin") }, 14f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        root.addView(Ui.text(this, "Units to inject", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 24)))
        val row = Ui.row(this).apply { gravity = Gravity.CENTER }
        val num = Ui.text(this, Lantus.fmt(u), 64f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }
        lateinit var save: android.widget.TextView
        fun upd() { num.text = Lantus.fmt(u); save.text = L.s("✓ Injected — save {0} u").replace("{0}", Lantus.fmt(u)) }
        row.addView(Ui.button(this, "−") { if (u >= 1) { u -= 1; upd() } }, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76)))
        row.addView(num, LinearLayout.LayoutParams(Ui.dp(this, 150), -2))
        row.addView(Ui.button(this, "+") { if (u < 100) { u += 1; upd() } }, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76)))
        root.addView(row, Ui.lp(top = Ui.dp(this, 8)))
        root.addView(Ui.text(this, L.s("Usual dose {0} u · change it with − / + if needed. A new dose becomes the usual dose from today.")
            .replace("{0}", Lantus.fmt(usual)), 12.5f, Ui.MUTED).apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 10)))
        fun doSave() {
            val now = System.currentTimeMillis(); val db = Db.get(this)
            val note = if (usual > 0 && u != usual) "night reminder · dose changed ${Lantus.fmt(usual)} → ${Lantus.fmt(u)} u" else "night reminder"
            db.addDose(Dose(0, now, "long", u, 0, 0, db.near(now)?.mg ?: 0, note))
            if (u != usual) { st.longDose = Lantus.fmt(u); if (usual > 0) BasalChanges.put(this, now, usual, u, "night reminder") }
            Lantus.setState(this, WatchData.dayKey(Lantus.at(st)), "done", share = true)
            FamilySync.kick(this); Sounds.stop(); Ui.toast(this, "Saved"); finish()
        }
        save = Ui.button(this, "", true) {
            if (u <= 0) { Ui.toast(this, "Enter the units"); return@button }
            // already saved in the last 2 hours (other phone, or a double tap)? ask before saving a second one
            val now = System.currentTimeMillis(); val db = Db.get(this)
            val prev = db.doses(now - 2 * 3600_000L, now + 60_000).filter { it.kind == "long" }.maxByOrNull { it.ts }
            if (prev != null) {
                val who = db.who("insulin", prev.id).ifBlank { L.s("this phone") }
                Tr.Builder(this).setTitle("⚠ " + L.s("Lantus already saved"))
                    .setMessage(L.s("Lantus {0} u was already saved at {1} by {2}. Save another one?").replace("{0}", Lantus.fmt(prev.units))
                        .replace("{1}", java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(prev.ts))).replace("{2}", who))
                    .setPositiveButton("Yes, save another") { _, _ -> doSave() }
                    .setNegativeButton("No") { _, _ -> Lantus.setState(this, WatchData.dayKey(Lantus.at(st)), "done", share = false); Sounds.stop(); finish() }.show()
            } else doSave()
        }
        root.addView(save, Ui.lp(top = Ui.dp(this, 20)))
        val r2 = Ui.row(this)
        r2.addView(Ui.button(this, "Remind in 15 min") {
            st.raw.edit().putLong("lantus_next", System.currentTimeMillis() + 15 * 60_000L).apply(); Sounds.stop()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(Lantus.NOTE); finish() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        r2.addView(Ui.button(this, "Skip tonight") {
            Lantus.setState(this, WatchData.dayKey(Lantus.at(st)), "skip", share = true); FamilySync.kick(this); Sounds.stop(); finish() }, Ui.lp(0, weight = 1f))
        root.addView(r2, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.text(this, "Saved as LI on the chart · editable in the history. The family phones see it was given.", 12f, Ui.MUTED)
            .apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 16)))
        upd()
        // another phone confirmed (or skipped): close this pop-up by itself
        val day = WatchData.dayKey(Lantus.at(st))
        val h = android.os.Handler(mainLooper)
        h.post(object : Runnable { override fun run() {
            if (isFinishing) return
            val s = st.raw.getString("lantus_st_$day", "") ?: ""
            if (s.isNotBlank()) { Sounds.stop(); Ui.toast(this@LantusActivity, if (s == "skip") "Skipped tonight" else "Already confirmed"); finish(); return }
            FamilySync.kick(this@LantusActivity); h.postDelayed(this, 20_000) } })
        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
    }
}
