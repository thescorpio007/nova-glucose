package com.nova.glucose

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import java.util.Calendar

/** School hours (per phone): urgent low and very high use their "school hours" sound then, their other sound otherwise. */
object School {
    val SCHED = setOf("urgent_low", "very_high")

    private fun mins(t: String, def: Int) = t.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: -1) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }.takeIf { it >= 0 } ?: def

    fun now(st: Store, t: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val iso = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1          // Mon = 1 … Sun = 7
        if (iso.toString() !in st.schoolDays) return false
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return m >= mins(st.schoolStart, 480) && m < mins(st.schoolEnd, 1020)
    }

    /** The sound key an alert of [kind] uses right now. */
    fun live(st: Store, kind: String) = if (kind in SCHED && st.schedFor(kind) && now(st)) "$kind@school" else kind
}

/** 📳 Meeting mode: for a while, every Nova Glucose sound on this phone becomes very strong vibration (urgent alarms too). */
object Meeting {
    fun on(st: Store) = System.currentTimeMillis() < st.meetingUntil
    fun minutesFor(st: Store) = if (st.isPatient) 15 else 60
    fun minutesLeft(st: Store) = ((st.meetingUntil - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(0)

    @Suppress("DEPRECATION")
    private fun vibrator(c: Context): android.os.Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        else c.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator

    /** The strongest vibration the phone allows: full strength (255), long pulses; as an alarm so night mode lets it through. */
    @Suppress("DEPRECATION")
    fun strong(c: Context, seconds: Int) {
        val v = vibrator(c) ?: return
        val reps = (seconds * 1000 / 1500).coerceAtLeast(2)
        val p = LongArray(1 + reps * 2).also { a -> for (k in 0 until reps) { a[1 + 2 * k] = 1200; a[2 + 2 * k] = 300 } }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val amps = IntArray(p.size) { if (it % 2 == 1) 255 else 0 }
                val eff = VibrationEffect.createWaveform(p, if (v.hasAmplitudeControl()) amps else IntArray(p.size) { if (it % 2 == 1) VibrationEffect.DEFAULT_AMPLITUDE else 0 }, -1)
                v.vibrate(eff, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            } else v.vibrate(p, -1)
        } catch (_: Exception) { }
    }

    /** From the monitor: tell when the meeting time is over. */
    fun maybeEnd(c: Context) {
        val st = Store(c)
        if (st.meetingUntil == 0L || on(st)) return
        st.meetingUntil = 0L
        strong(c, 2)
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, Sounds.channel(c, "other")) else @Suppress("DEPRECATION") android.app.Notification.Builder(c)
        nm.notify(7961, b.setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setContentTitle(L.s("📳 Meeting mode ended"))
            .setContentText(L.s("Nova Glucose sounds are back on this phone.")).setAutoCancel(true).build())
    }
}
