package com.nova.glucose

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Sounds (this phone): one per alert type, plus general notifications (eat timer, AI tips, other).
 * Every chosen sound — a file or one of the phone's sounds — is copied into the app and test-played when chosen,
 * so it keeps working on phones like Xiaomi/Redmi that withdraw access to other apps' files.
 * If a sound can't play, the phone's default sound plays, and if that fails too, a built-in beep: never silent.
 */
object Sounds {
    val KINDS = listOf("urgent_low", "low", "high", "very_high", "stale")
    val GENERAL = listOf("eat", "tip", "other")
    const val SILENT = "silent"
    val EXT = setOf("mp3", "wav", "ogg", "oga", "m4a", "aac", "mp4", "3gp", "amr", "flac", "opus", "mid", "midi", "xmf", "mkv", "webm")

    fun label(st: Store, kind: String) = when (kind) {
        "urgent_low" -> "Urgent low (below ${st.urgentLow})"; "low" -> "Low (below ${st.low})"
        "high" -> "High (above ${st.high})"; "very_high" -> "Very high (above ${st.veryHigh})"; "stale" -> "No signal"
        "eat" -> "🍽 Time to eat"; "tip" -> "✦ AI tips after alerts"; else -> "👨‍👩‍👧 Other notifications (family, tests)"
    }

    private fun isAlarm(kind: String) = kind in KINDS
    private fun lowKind(kind: String) = kind == "low" || kind == "urgent_low"

    fun defaultUri(kind: String): Uri? =
        (if (isAlarm(kind) && (lowKind(kind) || kind == "very_high")) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    /** The chosen sound, or null for the phone default ("" or a sound that disappeared). */
    fun chosen(st: Store, kind: String): Uri? {
        val s = st.soundFor(kind)
        if (s.isBlank() || s == SILENT) return null
        val u = Uri.parse(s)
        if (u.scheme == "file" && !File(u.path ?: "").exists()) return null
        return u
    }

    fun isSilent(st: Store, kind: String) = st.soundFor(kind) == SILENT && kind !in KINDS

    // ---------------------------------------------------------------- choosing
    /** The phone's own sounds (alarms, ringtones, notifications): always readable, listed by the app itself. */
    fun phoneSounds(c: Context): List<Triple<String, String, Uri>> {
        val out = mutableListOf<Triple<String, String, Uri>>()
        for ((type, group) in listOf(RingtoneManager.TYPE_ALARM to "Alarms", RingtoneManager.TYPE_RINGTONE to "Ringtones",
                RingtoneManager.TYPE_NOTIFICATION to "Notifications")) {
            try {
                val rm = RingtoneManager(c.applicationContext).apply { setType(type) }
                val cur = rm.cursor
                while (cur.moveToNext()) {
                    val title = cur.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
                    out += Triple(group, title, rm.getRingtoneUri(cur.position))
                }
            } catch (_: Exception) { }
        }
        return out.distinctBy { it.third.toString() }
    }

    /**
     * Copies a sound into the app and checks it plays. Returns null when OK, or the reason it can't be used.
     * Falls back to keeping the link for the phone's own sounds that can't be copied but do play.
     */
    fun use(c: Context, kind: String, uri: Uri, name: String): String? {
        val st = Store(c)
        val clean = name.replace(Regex("[^\\p{L}0-9 ._()-]"), "_").take(60).ifBlank { "sound" }
        val ext = clean.substringAfterLast('.', "").lowercase().takeIf { it in EXT }
            ?: c.contentResolver.getType(uri)?.substringAfter('/')?.let { mt -> when { "mpeg" in mt -> "mp3"; "wav" in mt -> "wav"; "ogg" in mt -> "ogg"; "mp4" in mt || "m4a" in mt -> "m4a"; "aac" in mt -> "aac"; "amr" in mt -> "amr"; "flac" in mt -> "flac"; else -> null } }
            ?: "mp3"
        val dir = File(c.filesDir, "sounds").apply { mkdirs() }
        val f = File(dir, "${kind}_${System.currentTimeMillis()}.$ext")
        val copied = try {
            c.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }; f.length() > 0
        } catch (_: Exception) { false }
        val target = if (copied) Uri.fromFile(f) else uri
        if (!playable(c, target)) {
            f.delete()
            return "This sound can't be played on this phone — choose another (mp3, wav, ogg, m4a, flac, amr…)."
        }
        dir.listFiles()?.filter { it.name.startsWith("${kind}_") && it != f }?.forEach { it.delete() }
        st.setSound(kind, target.toString(), clean.substringBeforeLast('.'))
        return null
    }

    fun setSilent(c: Context, kind: String) { if (kind !in KINDS) Store(c).setSound(kind, SILENT, "Silent") }
    fun setDefault(c: Context, kind: String) { Store(c).setSound(kind, "", "Phone default") }

    private fun playable(c: Context, u: Uri): Boolean = try {
        MediaPlayer().run { setDataSource(c.applicationContext, u); prepare(); val ok = duration != 0; release(); ok }
    } catch (_: Exception) { false }

    /**
     * Sounds saved by older builds as links to another app's file: copy them in now, or reset to the phone default.
     * Returns a short note when something had to be reset.
     */
    fun repair(c: Context): String {
        val st = Store(c)
        val reset = mutableListOf<String>()
        for (k in KINDS + GENERAL) {
            val s = st.soundFor(k)
            if (s.isBlank() || s == SILENT || s.startsWith("file:")) continue
            val u = Uri.parse(s)
            if (u.authority == "media" && playable(c, u)) continue                // the phone's own media: always readable
            if (use(c, k, u, st.soundNameFor(k)) != null) { setDefault(c, k); reset += label(st, k) }
        }
        return if (reset.isEmpty()) "" else "Reset to the phone default (the old file can't be read any more): " + reset.joinToString(", ")
    }

    // ---------------------------------------------------------------- playing
    private var once: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private val main = Handler(Looper.getMainLooper())

    /** Plays [kind]'s sound once; chosen → phone default → built-in beep. Returns "chosen", "default", "beep" or "". */
    private fun play(c: Context, kind: String, usage: Int, maxMs: Long): String {
        stop()
        val st = Store(c)
        if (isSilent(st, kind)) return ""
        for ((u, how) in listOf(chosen(st, kind) to "chosen", defaultUri(kind) to "default")) {
            if (u == null) continue
            once = try {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(c.applicationContext, u); isLooping = false
                    setOnCompletionListener { stop() }
                    prepare(); start()
                }
            } catch (_: Exception) { null }
            if (once != null) { main.postDelayed({ stop() }, maxMs); return how }
        }
        beep(if (usage == AudioAttributes.USAGE_ALARM) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION, 2500)
        return "beep"
    }

    /** Built-in beep (last resort when no sound file plays). */
    fun beep(stream: Int, ms: Int) {
        try {
            tone?.release()
            tone = ToneGenerator(stream, 100).apply { startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, ms) }
            main.postDelayed({ try { tone?.release() } catch (_: Exception) { }; tone = null }, ms + 200L)
        } catch (_: Exception) { }
    }

    private fun quiet(c: Context): Boolean {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            (android.os.Build.VERSION.SDK_INT >= 23 && nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    /**
     * Normal (non-urgent) alert: the type's sound once. Lows play on the alarm stream (heard like before);
     * other alerts at notification volume and only when the phone isn't silent or in Do Not Disturb.
     */
    fun playOnce(c: Context, kind: String) {
        val low = lowKind(kind)
        if (!low && quiet(c)) return
        play(c, kind, if (low) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION, 20_000)
    }

    /** General notifications (eat timer, AI tips, other): notification volume, respects silent mode. */
    fun playGeneral(c: Context, kind: String) { if (!quiet(c)) play(c, kind, AudioAttributes.USAGE_NOTIFICATION, 15_000) }

    /** Settings › ▶ Test (tap again to stop). Returns what played. */
    fun test(c: Context, kind: String): String {
        if (once != null || tone != null) { stop(); return "stopped" }
        return play(c, kind, if (isAlarm(kind)) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION, 8_000)
    }

    /** Preview of a phone sound in the list. */
    fun preview(c: Context, u: Uri) {
        stop()
        once = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(c.applicationContext, u); setOnCompletionListener { stop() }; prepare(); start()
            }
        } catch (_: Exception) { null }
        main.postDelayed({ stop() }, 6_000)
    }

    fun stop() {
        try { once?.stop(); once?.release() } catch (_: Exception) { }
        once = null
        try { tone?.release() } catch (_: Exception) { }
        tone = null
    }
}
