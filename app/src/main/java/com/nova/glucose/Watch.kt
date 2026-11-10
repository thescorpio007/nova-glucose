package com.nova.glucose

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Smart watch (e.g. HUAWEI WATCH GT 3 via Huawei Health): the watch shows the app's normal notifications.
 * "Glucose on the watch" posts a quiet message every N minutes because watches skip the permanent live notification.
 */
object Watch {
    const val CH_WATCH = "watch_glucose"
    private const val ID_WATCH = 7
    private const val ID_TEST = 8

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CH_WATCH, "Glucose on the watch", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false); description = "Quiet glucose message every few minutes, for the smart watch"
            })
    }

    private fun builder(c: Context, ch: String) = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ch) else @Suppress("DEPRECATION") Notification.Builder(c)

    private fun open(c: Context) = PendingIntent.getActivity(c, 71, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun glucose(c: Context, r: Reading, st: Store) {
        channel(c)
        val lv = Foods.level(st, r)
        val word = when (lv) { Level.URGENT_LOW -> "URGENT LOW"; Level.LOW -> "Low"; Level.HIGH -> "High"; Level.VERY_HIGH -> "Very high"; else -> "In range" }
        val n = builder(c, CH_WATCH).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("${r.mg} ${Libre.trendArrow(r.trend)} mg/dL · ${L.s(word)}")
            .setContentText("${st.patientName.ifBlank { "Glucose" }} · ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(r.ts))}")
            .setAutoCancel(true).setContentIntent(open(c)).setCategory(Notification.CATEGORY_STATUS)
            .apply { if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(st.watchGlucose * 60_000L + 60_000L) }
            .build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_WATCH, n)
    }

    /** Settings › "Send a test to the watch". */
    fun test(c: Context) {
        channel(c); MonitorService.channels(c)
        val st = Store(c)
        if (!Store(c).onFor("other")) return
        val n = builder(c, Sounds.channel(c, "other")).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(L.s("Nova Glucose test ⌚"))
            .setContentText(L.s(if (st.lastTs > 0) "Last glucose ${st.lastMg} ${Libre.trendArrow(st.lastTrend)} mg/dL — if you see this on the watch, it works."
                else "If you see this on the watch, notifications reach it."))
            .setAutoCancel(true).setContentIntent(open(c)).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_TEST, n)
        Sounds.after(c, "other")
    }
}
