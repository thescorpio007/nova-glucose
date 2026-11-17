package com.nova.glucose

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 📡 Readings without Abbott's server, on the patient's phone (the sensor stays with the official LibreLink app):
 *  A. LibreLink's own alarms (low, high, signal loss) are relayed to the family phones within seconds;
 *  B. each time LibreLink is on screen, the glucose number shown is read and shared with the family (chart + card).
 * Also: a clear message and one notification when LibreLinkUp is down for maintenance, and one when it is back.
 */
object LibreLocal {
    fun isLibre(pkg: String?) = pkg != null && pkg.startsWith("com.freestylelibre")

    // ------------------------------------------------------------ switching the two services on (patient's phone)
    fun listenerOn(c: Context) = (Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: "").contains(c.packageName + "/")
    fun screenOn(c: Context) = (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(c.packageName + "/")

    /** With the ADB permission WRITE_SECURE_SETTINGS Nova switches both on by itself; otherwise Settings shows two buttons. */
    fun ensure(c: Context) {
        val st = Store(c)
        if (!st.isPatient || !st.libreRelay) return
        if (!Net.nfcAllowed(c)) return
        try {
            if (!listenerOn(c)) {
                val cur = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: ""
                val me = ComponentName(c, LibreNotes::class.java).flattenToString()
                Settings.Secure.putString(c.contentResolver, "enabled_notification_listeners", listOf(cur, me).filter { it.isNotBlank() }.joinToString(":"))
                ErrorLog.e("Relay", "LibreLink alarm relay switched on")
            }
            if (!screenOn(c)) {
                val cur = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val me = ComponentName(c, LibreScreen::class.java).flattenToString()
                Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, listOf(cur, me).filter { it.isNotBlank() }.joinToString(":"))
                Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                ErrorLog.e("Relay", "LibreLink screen reading switched on")
            }
        } catch (e: Exception) { if (!st.raw.getBoolean("ll_err", false)) { st.raw.edit().putBoolean("ll_err", true).apply(); ErrorLog.e("Relay", "could not switch on: ${e.javaClass.simpleName}") } }
    }

    // ------------------------------------------------------------ A. LibreLink alarms
    fun onAlarm(c: Context, title: String, text: String) {
        val st = Store(c)
        if (!st.isPatient || !st.libreRelay) return
        val t = "$title $text".lowercase()
        val mg = Regex("(\\d{2,3})\\s*mg").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val kind = when {
            listOf("signal", "perte", "إشارة", "sensor", "capteur", "مستشعر").any { it in t } && !listOf("low", "high", "bas", "élev", "منخفض", "مرتفع").any { it in t } -> "stale"
            listOf("low", "bas", "faible", "منخفض", "hypo").any { it in t } -> if (mg in 1..st.urgentLow) "urgent_low" else "low"
            listOf("high", "élev", "haut", "مرتفع", "hyper").any { it in t } -> if (mg == 0 || mg >= st.veryHigh) "very_high" else "high"
            else -> return
        }
        val now = System.currentTimeMillis()
        if (st.raw.getString("ll_last_kind", "") == kind && now - st.raw.getLong("ll_last_ts", 0L) < 2 * 60_000L) return    // same alarm, already sent
        st.raw.edit().putString("ll_last_kind", kind).putLong("ll_last_ts", now).apply()
        ErrorLog.e("Relay", "LibreLink alarm on the patient's phone: $kind${if (mg > 0) " $mg mg/dL" else ""}")
        Relay.send(c, kind, mg, "LibreLink: " + title.ifBlank { kind }, text.ifBlank { L.s("Alarm on the patient's phone") }, fromLibre = true)
        FamilySync.kick(c)
    }

    // ------------------------------------------------------------ B. number read from LibreLink's screen
    fun onScreenReading(c: Context, mg: Int) {
        val st = Store(c)
        if (!st.isPatient || !st.libreRelay || mg !in 40..500) return
        val now = System.currentTimeMillis()
        if (st.raw.getInt("ll_scr_mg", 0) == mg && now - st.raw.getLong("ll_scr_ts", 0L) < 5 * 60_000L) return
        if (now - st.raw.getLong("ll_scr_ts", 0L) < 45_000L) return
        st.raw.edit().putInt("ll_scr_mg", mg).putLong("ll_scr_ts", now).apply()
        val db = Db.get(c)
        if (db.readings(now - 4 * 60_000L).isEmpty()) db.addReadings(listOf(Reading(now, mg)))
        if (now - st.lastTs > 4 * 60_000L) { st.lastMg = mg; st.lastTrend = 0; st.lastTs = now; st.raw.edit().putString("last_src", "ll").apply() }
        db.queue("rd-$now", "rd")
        FamilySync.kick(c)
    }

    fun item(c: Context, gid: String): JSONObject? {
        val ts = gid.removePrefix("rd-").toLongOrNull() ?: return null
        val st = Store(c)
        if (st.raw.getLong("ll_scr_ts", 0L) != ts) return null
        return JSONObject().put("t", "rd").put("g", gid).put("mg", st.raw.getInt("ll_scr_mg", 0)).put("ts", ts).put("u", ts)
    }

    /** Family phone: a number read on the patient's phone (used when this phone has nothing newer from LibreLinkUp). */
    fun received(c: Context, it: JSONObject): String {
        val st = Store(c); if (st.isPatient) return ""
        val mg = it.optInt("mg"); val ts = it.optLong("ts")
        if (mg !in 40..500 || ts <= 0) return "rd"
        val db = Db.get(c)
        if (db.readings(ts - 4 * 60_000L, ts + 4 * 60_000L).isEmpty()) db.addReadings(listOf(Reading(ts, mg)), markNew = false)
        if (ts > st.lastTs + 4 * 60_000L) { st.lastMg = mg; st.lastTrend = 0; st.lastTs = ts; st.raw.edit().putString("last_src", "ll").apply() }
        return "rd"
    }

    // ------------------------------------------------------------ maintenance message (every phone)
    private const val NOTE = 7995
    fun maintStarted(c: Context) {
        val st = Store(c)
        if (st.raw.getLong("maint_since", 0L) > 0) return
        st.raw.edit().putLong("maint_since", System.currentTimeMillis()).apply()
        ErrorLog.e("Libre", "LibreLinkUp down for maintenance (status 911)")
        note(c, "🛠 " + L.s("LibreLinkUp is down for maintenance"), L.s("Abbott's server is being maintained. Readings will resume by themselves. Her LibreLink app on her phone still alarms; LibreLink alarms are relayed to the family."))
    }
    fun maintEnded(c: Context) {
        val st = Store(c); val since = st.raw.getLong("maint_since", 0L)
        if (since <= 0) return
        st.raw.edit().putLong("maint_since", 0L).apply()
        val min = (System.currentTimeMillis() - since) / 60_000
        ErrorLog.e("Libre", "LibreLinkUp back after $min min")
        note(c, "✓ " + L.s("LibreLinkUp is back"), L.s("Readings are coming in again (down for {0} min).").replace("{0}", "$min"))
    }
    private fun note(c: Context, title: String, text: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, Sounds.channel(c, "other")) else @Suppress("DEPRECATION") Notification.Builder(c)
            nm.notify(NOTE, b.setSmallIcon(c.applicationInfo.icon).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build())
        } catch (_: Exception) { }
    }
}

/** A. Reads LibreLink's alarm notifications on the patient's phone (nothing else is read or kept). */
class LibreNotes : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (!LibreLocal.isLibre(n.packageName)) return
        if (n.isOngoing) return                                    // "FreeStyle Libre 2 Sensor · Tap to open the app"
        val ex = n.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        try { LibreLocal.onAlarm(applicationContext, title, text) } catch (_: Exception) { }
    }
}

/** B. When LibreLink is on screen, reads the big glucose number it shows (only LibreLink's window is looked at). */
class LibreScreen : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || !LibreLocal.isLibre(e.packageName?.toString())) return
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        if (!LibreLocal.isLibre(root.packageName?.toString())) return
        val nums = mutableListOf<Pair<Int, Int>>()      // value, height on screen
        var unit = false
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.contains("mg/dL", true)) unit = true
            Regex("^(\\d{2,3})$").find(t)?.let { m -> val r = Rect(); n.getBoundsInScreen(r); nums += m.groupValues[1].toInt() to r.height() }
            for (i in 0 until n.childCount) walk(try { n.getChild(i) } catch (_: Exception) { null }, depth + 1)
        }
        walk(root, 0)
        if (!unit || nums.isEmpty()) return
        val s = nums.sortedByDescending { it.second }
        val big = s[0]; val second = s.getOrNull(1)?.second ?: 0
        if (big.second < 60 || (second > 0 && big.second < second * 1.8)) return    // the big current value, not chart labels
        try { LibreLocal.onScreenReading(applicationContext, big.first) } catch (_: Exception) { }
    }
    override fun onInterrupt() { }
}
