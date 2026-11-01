package com.nova.glucose

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** One sound per alert type (this phone): urgent alarms ring with it; normal alerts play it once. */
object Sounds {
    val KINDS = listOf("urgent_low", "low", "high", "very_high", "stale")

    fun label(st: Store, kind: String) = when (kind) {
        "urgent_low" -> "Urgent low (below ${st.urgentLow})"; "low" -> "Low (below ${st.low})"
        "high" -> "High (above ${st.high})"; "very_high" -> "Very high (above ${st.veryHigh})"; else -> "No signal"
    }

    fun uri(st: Store, kind: String): Uri =
        st.soundFor(kind).takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(if (kind == "low" || kind == "urgent_low") RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private var once: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Normal (non-urgent) alert: the type's sound once. Lows play on the alarm stream (heard like before);
     * other alerts at notification volume and only when the phone isn't silent or in Do Not Disturb.
     */
    fun playOnce(c: Context, kind: String) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val low = kind == "low" || kind == "urgent_low"
        if (!low && (am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                (android.os.Build.VERSION.SDK_INT >= 23 && nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL))) return
        play(c, uri(Store(c), kind), if (low) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION, 20_000)
    }

    /** Settings › ▶ Test (tap again to stop). Returns true if it started. */
    fun test(c: Context, kind: String): Boolean {
        if (once != null) { stop(); return false }
        play(c, uri(Store(c), kind), AudioAttributes.USAGE_ALARM, 8_000)
        return once != null
    }

    private fun play(c: Context, u: Uri, usage: Int, maxMs: Long) {
        stop()
        once = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(c.applicationContext, u); isLooping = false
                setOnCompletionListener { stop() }
                prepare(); start()
            }
        } catch (_: Exception) { null }
        main.postDelayed({ stop() }, maxMs)
    }

    fun stop() { try { once?.stop(); once?.release() } catch (_: Exception) { }; once = null }
}
