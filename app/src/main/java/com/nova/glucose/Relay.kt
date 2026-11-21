package com.nova.glucose

import android.content.Context
import android.media.AudioManager
import android.app.NotificationManager
import org.json.JSONObject

/**
 * 🚨 Urgent relay: when the patient's phone rings an urgent alarm, the family phones ring their own urgent alarm too
 * (full screen, alarm volume, through vibrate / silent / night mode) — even if they couldn't read LibreLinkUp themselves.
 * Each phone keeps its own choices: a line turned off or not ticked urgent there is respected.
 */
object Relay {
    private const val FRESH = 10 * 60_000L          // older relayed alarms are never rung (only logged)

    /** Patient's phone: send the urgent alarm to the family. */
    fun send(c: Context, kind: String, mg: Int, title: String, body: String, fromLibre: Boolean = false) {
        val st = Store(c)
        if (!st.isPatient || st.syncFam.isBlank()) return
        val now = System.currentTimeMillis()
        st.raw.edit().putString("relay_last", JSONObject().put("kind", kind).put("mg", mg).put("ts", now).put("ti", title).put("b", body).put("ll", fromLibre).toString()).apply()
        Db.get(c).queue("urg-$kind-$now", "urg")
        ErrorLog.e("Alert", "relayed to the family: $kind $mg mg/dL")
    }

    fun item(c: Context, gid: String): JSONObject? {
        val o = try { JSONObject(Store(c).raw.getString("relay_last", "{}") ?: "{}") } catch (_: Exception) { return null }
        if (gid != "urg-${o.optString("kind")}-${o.optLong("ts")}") return null
        return JSONObject(o.toString()).put("t", "urg").put("g", gid).put("u", o.optLong("ts"))
    }

    /** Family phone: ring this phone's own urgent alarm for the patient's reading. */
    fun received(c: Context, it: JSONObject): String {
        val st = Store(c); val now = System.currentTimeMillis()
        val kind = it.optString("kind"); val ts = it.optLong("ts")
        if (st.isPatient || kind.isBlank()) return ""
        if (now - ts > FRESH) { ErrorLog.e("Alert", "patient's $kind (${it.optInt("mg")} mg/dL) arrived ${(now - ts) / 60_000} min late: not rung"); return "urg" }
        if (recovered(st, kind, ts)) { ErrorLog.e("Alert", "patient's $kind (${it.optInt("mg")} mg/dL) not rung: this phone already has a newer reading, ${st.lastMg} mg/dL"); return "urg" }
        val age = (now - ts) / 60_000
        val ageLine = L.s("Reading at {0}").replace("{0}", java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ts))) +
            if (age >= 1) " (" + L.s("{0} min ago").replace("{0}", "$age") + ")" else ""
        when {
            !st.onFor(kind) -> ErrorLog.e("Alert", "patient's urgent $kind not rung here: line turned off on this phone")
            !st.urgentFor(kind) && it.optBoolean("ll") -> {          // LibreLink alarm on the patient's phone, not urgent here: a normal alert with this phone's sound
                if (now - st.lastAlert("ll_$kind") > 5 * 60_000L) { st.setLastAlert("ll_$kind", now); plain(c, kind, it.optString("ti"), ageLine + " · " + it.optString("b")) }
            }
            !st.urgentFor(kind) -> ErrorLog.e("Alert", "patient's urgent $kind not rung here: not ticked urgent on this phone")
            now < st.snoozedUntil(kind) -> ErrorLog.e("Alert", "patient's urgent $kind not rung here: snoozed")
            now - st.lastAlert(kind) < st.urgentRepeat.coerceIn(1, 60) * 60_000L -> { }   // this phone already rang for it
            else -> {
                st.setLastAlert(kind, now)
                AlarmPlayer.start(c, kind, "🚨 " + it.optString("ti") + " · " + L.s("patient's phone"), ageLine + "\n" + it.optString("b"))
                st.raw.edit().putString("relay_ringing", JSONObject().put("kind", kind).put("ts", ts).toString()).apply()
                ErrorLog.e("Alert", "rang the patient's urgent $kind (${it.optInt("mg")} mg/dL) · " + mode(c))
            }
        }
        return "urg"
    }

    /** This phone's own newest reading is newer than the relayed alarm and she is no longer in that range. */
    private fun recovered(st: Store, kind: String, ts: Long): Boolean {
        if (st.lastTs <= ts) return false
        val mg = st.lastMg
        return when (kind) {
            "urgent_low" -> mg > st.urgentLow
            "low" -> mg >= st.low
            "high" -> mg <= st.high
            "very_high" -> mg < st.veryHigh
            "stale" -> true
            else -> false
        }
    }

    /** Every minute: a relayed alarm that is ringing stops by itself once a newer reading shows she has recovered. */
    fun checkStop(c: Context) {
        val st = Store(c); if (st.isPatient) return
        val r = try { JSONObject(st.raw.getString("relay_ringing", "") ?: "") } catch (_: Exception) { return }
        val kind = r.optString("kind")
        if (!AlarmPlayer.playing || AlarmPlayer.kind != kind) { st.raw.edit().remove("relay_ringing").apply(); return }
        if (recovered(st, kind, r.optLong("ts"))) {
            AlarmPlayer.dismiss(c); st.raw.edit().remove("relay_ringing").apply()
            ErrorLog.e("Alert", "patient's $kind alarm stopped here: newer reading ${st.lastMg} mg/dL")
        }
    }

    private fun plain(c: Context, kind: String, title: String, body: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val b = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, Sounds.channel(c, kind)) else @Suppress("DEPRECATION") android.app.Notification.Builder(c)
            val open = android.app.PendingIntent.getActivity(c, 7996, android.content.Intent(c, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
            nm.notify(7996 + kind.hashCode() % 50, b.setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("📡 " + title + " · " + L.s("patient's phone"))
                .setContentText(body).setContentIntent(open).setAutoCancel(true).build())
            Sounds.after(c, kind)
            ErrorLog.e("Alert", "patient's LibreLink $kind shown here · " + mode(c))
        } catch (_: Exception) { }
    }

    /** "vibrate mode · alarm volume 3/15 · night mode on" for the alert record. */
    fun mode(c: Context): String {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ring = when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent mode"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate mode"; else -> "sound on" }
        val dnd = if (android.os.Build.VERSION.SDK_INT >= 23 && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) " · night mode on" else ""
        return "$ring · alarm volume ${am.getStreamVolume(AudioManager.STREAM_ALARM)}/${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)}$dnd"
    }
}
