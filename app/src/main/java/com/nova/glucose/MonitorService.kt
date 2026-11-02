package com.nova.glucose

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager

/** Reads the glucose every minute in the background, keeps a live notification and raises alerts with food tips. */
class MonitorService : Service() {

    companion object {
        const val CH_STATUS = "status"
        const val CH_ALERT = "alerts3"           // silent channels: the app plays the chosen sound per alert type
        const val CH_ALERT_LOW = "alerts_low3"
        const val CH_TIP = "ai_tips2"
        private const val ID_TIP = 9
        private const val ID_STATUS = 1
        private const val ID_ALERT = 2
        private const val POLL_MS = 60_000L

        /** Screens that want fresh data register here. */
        val listeners = mutableSetOf<(Snapshot?, String?) -> Unit>()
        @Volatile var latest: Snapshot? = null

        fun start(c: Context) {
            if (!Store(c).monitorOn || !Store(c).loggedIn) return
            val i = Intent(c, MonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Exception) { }
        }

        fun stop(c: Context) { c.stopService(Intent(c, MonitorService::class.java)) }

        fun channels(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Live glucose", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
            listOf("alerts2", "alerts_low2").forEach { try { nm.deleteNotificationChannel(it) } catch (_: Exception) { } }
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var wake: PowerManager.WakeLock? = null
    private val st by lazy { Store(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
        goForeground(statusNote("Connecting to LibreLinkUp…"))
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaGlucose:monitor").apply { setReferenceCounted(false) }
        thread = HandlerThread("monitor").apply { start() }
        handler = Handler(thread.looper)
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("now", false) == true) { handler.removeCallbacks(tick); handler.post(tick) }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            wake?.acquire(2 * POLL_MS)
            L.init(this@MonitorService); Sounds.init(this@MonitorService)
            var next = POLL_MS
            try {
                val snap = Libre(st).fetch()
                latest = snap
                Db.get(this@MonitorService).addReadings(snap.graph)
                snap.latest?.let { r ->
                    st.lastMg = r.mg; st.lastTrend = r.trend; st.lastTs = r.ts
                }
                st.lastError = ""
                notifyListeners(snap, null)
                update(snap.latest)
                if (System.currentTimeMillis() - st.lastExport >= 15 * 60_000L) Vault.exportAll(this@MonitorService)
                try { DriveSync.maybeRun(this@MonitorService) } catch (_: Exception) { }
            } catch (e: LibreException) {
                st.lastError = e.message ?: "error"
                notifyListeners(null, st.lastError)
                if (e.kind == LibreException.Kind.LOCKED) next = 5 * POLL_MS
                if (e.kind == LibreException.Kind.LOGIN || e.kind == LibreException.Kind.TERMS) next = 10 * POLL_MS
                update(null)
            } catch (e: Exception) {
                st.lastError = e.message ?: e.javaClass.simpleName
                notifyListeners(null, st.lastError)
                update(null)
            }
            try { HealthRead.maybe(this@MonitorService) } catch (_: Exception) { }   // watch data every 15 min
            FamilySync.maybeRun(this@MonitorService)      // family sync every minute (even without a reading)
            handler.postDelayed(this, next)
        }
    }

    private fun notifyListeners(s: Snapshot?, err: String?) {
        android.os.Handler(mainLooper).post { listeners.toList().forEach { it(s, err) } }
    }

    // ---------------------------------------------------------------- notifications

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun builder(ch: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun statusNote(text: String, title: String = "Nova Glucose"): Notification =
        builder(CH_STATUS).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp()).build()

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID_STATUS, n)
    }

    private fun update(r: Reading?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val shown = r ?: if (st.lastTs > 0) Reading(st.lastTs, st.lastMg, st.lastTrend) else null
        val title = shown?.let { "${it.mg} ${Libre.trendArrow(it.trend)} mg/dL" } ?: "Nova Glucose"
        val ago = shown?.let { minutesAgo(it.ts) } ?: ""
        val text = when {
            r == null && st.lastError.isNotBlank() -> "${st.lastError.take(80)} $ago".trim()
            shown != null -> "${st.patientName.ifBlank { "Glucose" }} · $ago"
            else -> "Waiting for a reading…"
        }
        nm.notify(ID_STATUS, statusNote(L.s(text), title))
        watchGlucose(r, nm)
        if (st.alertsOn) checkAlerts(shown, nm)
    }

    /** The AI's short food tip, a few seconds after the normal alert (never delays it). */
    private fun aiTip(kind: String, r: Reading) {
        val app = applicationContext
        Thread {
            val tip = AiData.alertTip(app, kind) ?: return@Thread
            val nm = app.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) try { nm.deleteNotificationChannel("ai_tips") } catch (_: Exception) { }
            Sounds.after(app, "tip")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, Sounds.channel(app, "tip")) else @Suppress("DEPRECATION") Notification.Builder(app)
            nm.notify(ID_TIP, b.setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(L.s("✦ AI tip · ${r.mg} ${Libre.trendArrow(r.trend)} mg/dL"))
                .setContentText(tip).setStyle(Notification.BigTextStyle().bigText(tip + L.s("\nAI tip — food only, not medical advice.")))
                .setAutoCancel(true).setContentIntent(openApp()).setLocalOnly(!st.watchAlerts).build())
        }.start()
    }

    /** Watches don't show the permanent live notification, so post a quiet glucose message every N minutes. */
    private fun watchGlucose(r: Reading?, nm: NotificationManager) {
        val every = st.watchGlucose
        if (every <= 0 || r == null || System.currentTimeMillis() - r.ts > 15 * 60_000L) return
        if (System.currentTimeMillis() - st.lastWatchPost < every * 60_000L - 20_000L) return
        st.lastWatchPost = System.currentTimeMillis()
        Watch.glucose(this, r, st)
    }

    private fun minutesAgo(ts: Long): String {
        val m = ((System.currentTimeMillis() - ts) / 60_000).toInt()
        return if (m <= 0) "just now" else "$m min ago"
    }

    private fun checkAlerts(r: Reading?, nm: NotificationManager) {
        val now = System.currentTimeMillis()
        val lv = Foods.level(st, r, now)
        val noSignal = r != null && now - r.ts > st.staleMin.coerceIn(5, 240) * 60_000L
        val kind = if (noSignal) { if (st.staleAlert || st.urgNoSignal) "stale" else null } else when (lv) {
            Level.URGENT_LOW -> "urgent_low"; Level.LOW -> "low"; Level.HIGH -> "high"; Level.VERY_HIGH -> "very_high"
            else -> null
        }
        if (kind == null) { nm.cancel(ID_ALERT); if (AlarmPlayer.kind != "test") AlarmPlayer.dismiss(this); return }
        val urgent = st.urgentFor(kind)
        if (urgent && now < st.snoozedUntil(kind)) return
        val repeat = if (urgent) st.urgentRepeat.coerceIn(1, 60) else if (kind == "urgent_low") 5 else st.repeatMin
        if (now - st.lastAlert(kind) < repeat * 60_000L) return
        st.setLastAlert(kind, now)

        val adv = Foods.advise(st, r)
        val tip = adv.foods.take(2).joinToString(" or ") { "${it.portion} ${it.name.lowercase()}" }
        val body = when (kind) {
            "urgent_low", "low" -> "Have ${st.lowTreatG} g fast sugar now: $tip. Recheck in 15 min." +
                if (kind == "urgent_low") " Tell an adult." else ""
            "high" -> "Drink water and choose low-carb food (e.g. $tip). Follow her high plan."
            "very_high" -> "Drink water, check ketones as advised. Low-carb food: $tip."
            else -> "Last reading ${r!!.mg} ${Libre.trendArrow(r.trend)} at ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(r.ts))}. " +
                "Check her phone is online, the Libre app is open and the sensor is working."
        }
        val title = when (kind) {
            "stale" -> "No glucose signal for ${(now - r!!.ts) / 60_000} min"
            else -> "${adv.title}: ${r!!.mg} ${Libre.trendArrow(r.trend)} mg/dL"
        }
        val low = kind == "urgent_low" || kind == "low"
        if (kind != "stale" && st.aiTips) aiTip(kind, r!!)
        if (urgent) {
            // Rings through silent / Do Not Disturb at the chosen volume, full screen, Stop / Snooze.
            AlarmPlayer.start(this, kind, L.s(title), L.s(body))
            Vault.logAlert(this, "URGENT ALARM · $title", body)
            return
        }
        val n = builder(Sounds.channel(this, kind))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(L.s(title)).setContentText(L.s(body))
            .setStyle(Notification.BigTextStyle().bigText(L.s(body)))
            .setAutoCancel(true).setContentIntent(openApp())
            .apply { if (Build.VERSION.SDK_INT < 26) { @Suppress("DEPRECATION") setPriority(Notification.PRIORITY_MAX) } }
            .setCategory(if (low) Notification.CATEGORY_ALARM else Notification.CATEGORY_STATUS)
            .setLocalOnly(!(if (kind == "stale") st.watchStale else st.watchAlerts))
            .build()
        nm.notify(ID_ALERT, n)
        Sounds.after(this, kind)
        Vault.logAlert(this, title, body)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { MonitorService.start(c) }
}
