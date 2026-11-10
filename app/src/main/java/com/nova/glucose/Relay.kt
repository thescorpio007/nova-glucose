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
    private const val FRESH = 15 * 60_000L

    /** Patient's phone: send the urgent alarm to the family. */
    fun send(c: Context, kind: String, mg: Int, title: String, body: String) {
        val st = Store(c)
        if (!st.isPatient || st.syncFam.isBlank()) return
        val now = System.currentTimeMillis()
        st.raw.edit().putString("relay_last", JSONObject().put("kind", kind).put("mg", mg).put("ts", now).put("ti", title).put("b", body).toString()).apply()
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
        if (st.isPatient || kind.isBlank() || now - ts > FRESH) return ""
        when {
            !st.onFor(kind) -> ErrorLog.e("Alert", "patient's urgent $kind not rung here: line turned off on this phone")
            !st.urgentFor(kind) -> ErrorLog.e("Alert", "patient's urgent $kind not rung here: not ticked urgent on this phone")
            now < st.snoozedUntil(kind) -> ErrorLog.e("Alert", "patient's urgent $kind not rung here: snoozed")
            now - st.lastAlert(kind) < st.urgentRepeat.coerceIn(1, 60) * 60_000L -> { }   // this phone already rang for it
            else -> {
                st.setLastAlert(kind, now)
                AlarmPlayer.start(c, kind, "🚨 " + it.optString("ti") + " · " + L.s("patient's phone"), it.optString("b"))
                ErrorLog.e("Alert", "rang the patient's urgent $kind (${it.optInt("mg")} mg/dL) · " + mode(c))
            }
        }
        return "urg"
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
