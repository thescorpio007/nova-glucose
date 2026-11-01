package com.nova.glucose

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager

/**
 * Urgent alarm: plays on the ALARM stream (heard in silent/vibrate mode), raises the alarm volume to the
 * level chosen in Settings over 5 s, lets the sound through Do Not Disturb when the app has DND access,
 * shows a full-screen alarm, rings for [Store.ringSec] seconds, then restores everything.
 */
object AlarmPlayer {
    const val CH_URGENT = "urgent_alarm"
    const val ID_URGENT = 3
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var savedFilter = -1
    /** The exact vibrator that started the vibration: Android only cancels it through the same object. */
    private var vib: Vibrator? = null
    @Volatile var kind: String = ""; private set
    @Volatile var title: String = ""; private set
    @Volatile var body: String = ""; private set
    val playing get() = player != null

    fun channel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_URGENT, "Urgent glucose alarm (full screen)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)            // the sound is played by the app on the alarm stream
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    /** Starts ringing (from any thread). */
    fun start(c: Context, kind: String, title: String, body: String, notify: Boolean = true) {
        val app = c.applicationContext
        this.kind = kind; this.title = title; this.body = body
        main.post {
            stopSound(app)
            if (notify) notifyFullScreen(app)
            ring(app)
        }
    }

    private fun ring(c: Context) {
        val st = Store(c)
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Do Not Disturb: let everything through while ringing, if the user gave the app DND access.
        try {
            if (Build.VERSION.SDK_INT >= 23 && nm.isNotificationPolicyAccessGranted &&
                nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                savedFilter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: Exception) { }
        // Volume: rise to the chosen level over 5 seconds.
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = (max * st.alarmVolume / 100.0).toInt().coerceIn(1, max)
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val start = minOf(target, maxOf(1, target / 3))
            am.setStreamVolume(AudioManager.STREAM_ALARM, start, 0)
            for (k in 1..5) main.postDelayed({
                try { am.setStreamVolume(AudioManager.STREAM_ALARM, start + (target - start) * k / 5, 0) } catch (_: Exception) { }
            }, k * 1000L)
        } catch (_: Exception) { savedVolume = -1 }

        // The chosen sound, else the phone's alarm, else its ringtone, else a built-in beep: never silent.
        val tries = listOfNotNull(Sounds.chosen(st, kind), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        player = null
        for (u in tries) {
            player = try {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(c, u); isLooping = true; prepare(); start()
                }
            } catch (_: Exception) { null }
            if (player != null) break
        }
        if (player == null) Sounds.beep(AudioManager.STREAM_ALARM, st.ringSec.coerceIn(10, 600) * 1000)
        vibrate(c, st.alarmVibrate, st.ringSec.coerceIn(10, 600))
        main.postDelayed({ stopSound(c) }, st.ringSec.coerceIn(10, 600) * 1000L)
    }

    @Suppress("DEPRECATION")
    private fun vibrator(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (c.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        else c.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    @Suppress("DEPRECATION")
    private fun vibrate(c: Context, mode: String, seconds: Int) {
        if (mode == "off") return
        val v = vibrator(c) ?: return
        vib = v
        // A finite pattern as long as the ring time: it ends by itself even if Stop were missed.
        val unit = if (mode == "strong") longArrayOf(900, 300) else longArrayOf(500, 700)
        val reps = maxOf(1, (seconds * 1000 / (unit[0] + unit[1])).toInt())
        val pattern = LongArray(1 + reps * 2).also { p -> for (k in 0 until reps) { p[1 + 2 * k] = unit[0]; p[2 + 2 * k] = unit[1] } }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val amps = IntArray(pattern.size) { if (it % 2 == 1) (if (mode == "strong") 255 else 160) else 0 }
                v.vibrate(VibrationEffect.createWaveform(pattern, amps, -1))
            } else v.vibrate(pattern, -1)
        } catch (_: Exception) { }
    }

    /** Stops the sound and vibration and restores volume / Do Not Disturb (the alert stays in the list). */
    fun stopSound(ctx: Context) {
        val c = ctx.applicationContext
        main.removeCallbacksAndMessages(null)
        player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        player = null
        Sounds.stop()
        try { vib?.cancel() } catch (_: Exception) { }
        try { vibrator(c)?.cancel() } catch (_: Exception) { }
        vib = null
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (savedVolume >= 0) { try { am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: Exception) { }; savedVolume = -1 }
        if (savedFilter >= 0 && Build.VERSION.SDK_INT >= 23) {
            try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setInterruptionFilter(savedFilter) } catch (_: Exception) { }
            savedFilter = -1
        }
    }

    fun dismiss(c: Context) {
        stopSound(c.applicationContext)
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_URGENT)
    }

    private fun pi(c: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(c, code, Intent(c, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyFullScreen(c: Context) {
        channel(c)
        val full = PendingIntent.getActivity(c, 31, Intent(c, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH_URGENT) else @Suppress("DEPRECATION") Notification.Builder(c)
        val n = b.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(full, true).setContentIntent(full).setOngoing(false).setAutoCancel(true)
            .setLocalOnly(!Store(c).watchUrgent)
            .addAction(Notification.Action.Builder(null, "Stop", pi(c, AlarmReceiver.STOP, 32)).build())
            .addAction(Notification.Action.Builder(null, "Snooze 15 min", pi(c, AlarmReceiver.SNOOZE, 33)).build())
            .apply { if (Build.VERSION.SDK_INT < 26) { @Suppress("DEPRECATION") setPriority(Notification.PRIORITY_MAX) } }
            .build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_URGENT, n)
    }
}

/** Stop / Snooze buttons on the alarm, and the "time to eat" timer. */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val STOP = "com.nova.glucose.STOP"
        const val SNOOZE = "com.nova.glucose.SNOOZE"
        const val EAT = "com.nova.glucose.EAT"
    }

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            STOP -> AlarmPlayer.dismiss(c)
            SNOOZE -> {
                val k = AlarmPlayer.kind
                if (k.isNotBlank()) Store(c).snooze(k, System.currentTimeMillis() + 15 * 60_000L)
                AlarmPlayer.dismiss(c)
            }
            EAT -> EatTimer.fire(c)
        }
    }
}

/** Full-screen alarm shown over the lock screen. */
class AlarmActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.getBooleanExtra("test", false) && !AlarmPlayer.playing)
            AlarmPlayer.start(this, "test", "Test alarm: 64 ↘ mg/dL", "This is how an urgent alarm looks and sounds.", notify = false)
        val low = AlarmPlayer.kind == "urgent_low" || AlarmPlayer.kind == "low" || AlarmPlayer.kind == "test"
        val root = Ui.col(this, 28).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(if (low) 0xFF5A1414.toInt() else 0xFF5A3A0A.toInt())
        }
        root.addView(Ui.text(this, "⚠", 64f, Ui.TEXT, true).apply { gravity = Gravity.CENTER })
        root.addView(Ui.text(this, AlarmPlayer.title.ifBlank { "Glucose alarm" }, 28f, Ui.TEXT, true).apply { gravity = Gravity.CENTER })
        root.addView(Ui.text(this, AlarmPlayer.body, 17f, Ui.TEXT).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(context, 16), 0, Ui.dp(context, 28)) })
        root.addView(Ui.button(this, "Stop", true) { AlarmPlayer.dismiss(this); finish() }, Ui.lp(top = Ui.dp(this, 8)))
        root.addView(Ui.button(this, "Snooze 15 min") {
            val k = AlarmPlayer.kind
            if (k.isNotBlank() && k != "test") Store(this).snooze(k, System.currentTimeMillis() + 15 * 60_000L)
            AlarmPlayer.dismiss(this); finish()
        }, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.button(this, "Open Nova Glucose") {
            AlarmPlayer.stopSound(this)
            startActivity(Intent(this, MainActivity::class.java)); finish()
        }, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.signature(this).apply { setTextColor(0xCCFFFFFF.toInt()) }, Ui.lp(top = Ui.dp(this, 20)))
        setContentView(root)
    }
}

/** "Time to eat" reminder after rapid insulin. */
object EatTimer {
    const val CH_EAT = "eat_timer2"          // silent channel: the app plays the chosen "Time to eat" sound
    private const val ID_EAT = 4

    /** [share]: also start it on the family's other phones (family sync). */
    fun schedule(c: Context, at: Long, share: Boolean = true) {
        Store(c).eatAt = at
        if (share) { Store(c).syncEatU = System.currentTimeMillis(); Db.get(c).queue("eat", "eat") }
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, 41, Intent(c, AlarmReceiver::class.java).setAction(AlarmReceiver.EAT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun cancel(c: Context, share: Boolean = true) {
        Store(c).eatAt = 0
        if (share) { Store(c).syncEatU = System.currentTimeMillis(); Db.get(c).queue("eat", "eat") }
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(PendingIntent.getBroadcast(c, 41, Intent(c, AlarmReceiver::class.java).setAction(AlarmReceiver.EAT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    fun fire(c: Context) {
        L.init(c)
        val st = Store(c)
        if (st.eatAt == 0L) return
        st.eatAt = 0
        if (!st.notifyEat) return                  // this phone chose no eat-timer reminder
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CH_EAT, "Time to eat (after rapid insulin)", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400); setSound(null, null)
            }).also { try { nm.deleteNotificationChannel("eat_timer") } catch (_: Exception) { } }
        val open = PendingIntent.getActivity(c, 42, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mg = if (System.currentTimeMillis() - st.lastTs < 15 * 60_000) " · glucose ${st.lastMg} ${Libre.trendArrow(st.lastTrend)}" else ""
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH_EAT) else @Suppress("DEPRECATION") Notification.Builder(c)
        nm.notify(ID_EAT, b.setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(L.s("🍽 Time to eat$mg"))
            .setContentText(L.s("The wait after rapid insulin is over — start eating now."))
            .setAutoCancel(true).setContentIntent(open).setCategory(Notification.CATEGORY_REMINDER).setLocalOnly(!st.watchEat)
            .also { Sounds.playGeneral(c, "eat") }
            .apply { if (Build.VERSION.SDK_INT < 26) { @Suppress("DEPRECATION") setPriority(Notification.PRIORITY_MAX); setDefaults(Notification.DEFAULT_ALL) } }
            .build())
    }
}
