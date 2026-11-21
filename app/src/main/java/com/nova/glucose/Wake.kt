package com.nova.glucose

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * ⏰ Wake-up timer: every 2 min, even in deep sleep (Doze / Xiaomi battery saver), Nova wakes briefly to run the family sync,
 * so the 🔔 bell, messages and requests from other phones arrive within about 1–3 min instead of 15–30 min.
 */
object Wake {
    const val EVERY = 2 * 60_000L
    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 7960, Intent(c, WakeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(c: Context) {
        try {
            if (Store(c).syncFam.isBlank()) return
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + EVERY
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
        } catch (_: Exception) { }
    }
}

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val app = c.applicationContext
        val done = goAsync()
        val wl = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaGlucose:wake")
        try { wl.acquire(50_000L) } catch (_: Exception) { }
        Thread {
            try {
                val st = Store(app)
                if (System.currentTimeMillis() - st.raw.getLong("wake_sync", 0L) > 50_000L) {       // the monitor may just have synced
                    st.raw.edit().putLong("wake_sync", System.currentTimeMillis()).apply()
                    FamilySync.maybeRun(app)
                }
                Bell.maybe(app)
                try { Relay.checkStop(app) } catch (_: Exception) { }
                try { Locate.maybe(app) } catch (_: Exception) { }
            } catch (_: Exception) { }
            finally {
                Wake.schedule(app)
                try { if (wl.isHeld) wl.release() } catch (_: Exception) { }
                try { done.finish() } catch (_: Exception) { }
            }
        }.start()
    }
}
