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
    val GENERAL = listOf("eat", "msg", "lantus", "guard", "update", "tip", "other")
    const val SILENT = "silent"
    /** Sounds built into the app (same on every phone). */
    val BUILTIN = listOf("builtin:blip" to "Nova Blip", "builtin:medical" to "Nova Medical", "builtin:beep" to "Nova Beep",
        "builtin:blip5" to "Nova Blip 5s", "builtin:beep5" to "Nova Beep 5s")
    fun defaultFor(kind: String) = when (kind) {
        "low", "urgent_low" -> "builtin:medical"; "very_high", "stale", "guard" -> "builtin:blip5"; "eat" -> "builtin:beep5"; "lantus" -> "builtin:medical"; else -> "builtin:blip" }

    /**
     * Each phone keeps its own notification choices (never shared, never reset by updates). Safety net at every update:
     * urgent low is switched back on, urgent, and not Silent.
     */
    fun applyOnUpdate(c: Context) {
        val st = Store(c); val b = Updater.current(c)
        if (b <= 0 || st.raw.getInt("last_snd_build", -1) == b) return
        st.urgentOn = true; st.urgUrgentLow = true; st.setOn("urgent_low", true)
        if (isSilent(st, "urgent_low")) setBuiltin(c, "urgent_low", "builtin:medical")
        st.raw.edit().putInt("last_snd_build", b).apply()
    }
    fun builtinUri(c: Context, v: String): Uri? = when (v) {
        "builtin:blip" -> R.raw.nova_blip; "builtin:medical" -> R.raw.nova_medical; "builtin:beep" -> R.raw.nova_beep
        "builtin:blip5" -> R.raw.nova_blip5; "builtin:beep5" -> R.raw.nova_beep5; else -> null
    }?.let { Uri.parse("android.resource://${c.packageName}/$it") }
    /** Silent is never allowed for lows; for very high / no signal only when not urgent. */
    /** Lines the patient may change while Settings is locked: never urgent low, low, very high, or a line made urgent. */
    fun patientMay(st: Store, kind: String) = kind !in setOf("urgent_low", "low", "very_high") && !(kind in KINDS && st.urgentFor(kind))

    /** Silent is allowed on every line that is not an urgent alarm (lows included when not urgent). */
    @Suppress("UNUSED_PARAMETER") fun silentAllowed(st: Store, kind: String) = true      // every line may be Silent (family's choice)
    fun current(st: Store, kind: String): String = st.soundFor(kind).ifBlank { defaultFor(kind) }
    fun displayName(st: Store, kind: String): String {
        val v = current(st, kind)
        return BUILTIN.firstOrNull { it.first == v }?.second ?: if (v == SILENT) "Silent" else st.soundNameFor(kind)
    }
    val EXT = setOf("mp3", "wav", "ogg", "oga", "m4a", "aac", "mp4", "3gp", "amr", "flac", "opus", "mid", "midi", "xmf", "mkv", "webm")

    fun label(st: Store, kind: String) = when (kind) {
        "urgent_low" -> "Urgent low (below ${st.urgentLow})"; "low" -> "Low (below ${st.low})"
        "high" -> "High (above ${st.high})"; "very_high" -> "Very high (above ${st.veryHigh})"; "stale" -> "No signal"
        "eat" -> "🍽 Time to eat"; "msg" -> "💬 Family messages"; "lantus" -> "💉 Night Lantus reminder"
        "guard" -> "📵 Patient's phone not seen 2 h"; "update" -> "⬆ App update available"
        "tip" -> "✦ AI tips after alerts"; else -> "👨‍👩‍👧 Other notifications (family, tests)"
    }

    private fun isAlarm(kind: String) = kind in KINDS
    private fun lowKind(kind: String) = kind == "low" || kind == "urgent_low"

    fun defaultUri(kind: String): Uri? =
        (if (isAlarm(kind) && (lowKind(kind) || kind == "very_high")) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    /** The chosen sound (built-in, file or phone sound); a silent or missing choice falls back to the Nova default. */
    fun chosen(st: Store, kind: String, c: Context? = null): Uri? {
        var s = current(st, kind)
        if (s == SILENT) s = defaultFor(kind)
        if (s.startsWith("builtin:")) return c?.let { builtinUri(it, s) } ?: appCtx?.let { builtinUri(it, s) }
        val u = Uri.parse(s)
        if (u.scheme == "file" && !File(u.path ?: "").exists()) return appCtx?.let { builtinUri(it, defaultFor(kind)) }
        return u
    }
    @Volatile private var appCtx: Context? = null
    fun init(c: Context) { ErrorLog.init(c); appCtx = c.applicationContext; try { applyOnUpdate(c) } catch (_: Exception) { } }

    fun isSilent(st: Store, kind: String) = current(st, kind) == SILENT && silentAllowed(st, kind)

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

    fun setSilent(c: Context, kind: String) { if (silentAllowed(Store(c), kind)) Store(c).setSound(kind, SILENT, "Silent") }
    fun setDefault(c: Context, kind: String) { Store(c).setSound(kind, "", "") }
    fun setBuiltin(c: Context, kind: String, v: String) { Store(c).setSound(kind, v, BUILTIN.firstOrNull { it.first == v }?.second ?: "") }

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
            if (s.isBlank() || s == SILENT || s.startsWith("file:") || s.startsWith("builtin:")) continue
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
        appCtx = c.applicationContext
        for ((u, how) in listOf(chosen(st, kind, c) to "chosen", builtinUri(c, defaultFor(kind)) to "default", defaultUri(kind) to "default")) {
            if (u == null) continue
            once = try {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(c.applicationContext, u); isLooping = false
                    setOnCompletionListener { stop() }
                    prepare(); start()
                }
            } catch (_: Exception) { null }
            if (once != null) { main.removeCallbacks(stopper); main.postDelayed(stopper, maxMs); return how }
        }
        beep(if (usage == AudioAttributes.USAGE_ALARM) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION, 2500)
        return "beep"
    }

    /** Built-in beep (last resort when no sound file plays). */
    fun beep(stream: Int, ms: Int) {
        try {
            tone?.release()
            tone = ToneGenerator(stream, 100).apply { startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, ms) }
            main.removeCallbacks(stopper); main.postDelayed(stopper, ms + 200L)
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
    /** Glucose alert (not urgent): at the chosen alarm volume (raised for 20 s if the phone's is lower), even on vibrate / silent / night mode. */
    fun playOnce(c: Context, kind: String) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM); val target = (max * Store(c).alarmVolume / 100.0).toInt().coerceIn(1, max)
            val was = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (was < target) { am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
                main.postDelayed({ try { if (AlarmPlayer.kind.isEmpty() || !AlarmPlayer.playing) am.setStreamVolume(AudioManager.STREAM_ALARM, was, 0) } catch (_: Exception) { } }, 21_000) }
        } catch (_: Exception) { }
        play(c, kind, AudioAttributes.USAGE_ALARM, 20_000)
    }

    /** General notifications (eat timer, AI tips, other): notification volume, respects silent mode. */
    /** 💬 family message: Nova Blip at the alarm volume, even on silent. */
    fun playMessage(c: Context) = after(c, "msg")

    fun playGeneral(c: Context, kind: String) {
        play(c, kind, AudioAttributes.USAGE_ALARM, 15_000)       // every notification: alarm volume, even on silent / night mode
    }

    /** Settings › ▶ Test (tap again to stop). Returns what played. */
    /** Settings › ▶ Test: always out loud (alarm volume), whatever the phone's mode. Tap again to stop. */
    fun test(c: Context, kind: String): String {
        if (once != null || tone != null) { stop(); return "stopped" }
        val st = Store(c)
        if (isSilent(st, kind)) return "silent"
        return play(c, kind, AudioAttributes.USAGE_ALARM, 10_000)
    }

    /** Why the non-urgent notifications might be quiet on this phone right now ("" if all is fine). */
    fun volumeNote(c: Context): String {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when {
            am.getStreamVolume(AudioManager.STREAM_ALARM) == 0 -> "Alarm volume is 0 — raise it in the phone's sound settings, or these notifications will be silent."
            else -> ""
        }
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
        main.removeCallbacks(stopper); main.postDelayed(stopper, 6_000)
    }

    // ---------------------------------------------------------------- channels and vibration (Android stays silent; the app decides)
    const val CH_POP = "nova_pop"; const val CH_POP_ALL = "nova_pop_all"; const val CH_POP_LOW = "nova_pop_low"; const val CH_QUIET = "nova_quiet"

    /** Notification channel for a line: pops up or goes quietly to the list; never Android's own sound or vibration. */
    fun channel(c: Context, kind: String): String {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            fun mk(id: String, name: String, imp: Int, dnd: Boolean = false) = nm.createNotificationChannel(
                android.app.NotificationChannel(id, name, imp).apply { setSound(null, null); enableVibration(false); if (dnd) setBypassDnd(true) })
            mk(CH_POP, "Glucose alerts and notifications (pop up)", NotificationManager.IMPORTANCE_HIGH)
            mk(CH_POP_ALL, "All notifications (pop up, night mode too)", NotificationManager.IMPORTANCE_HIGH, dnd = true)
            mk(CH_POP_LOW, "Low glucose (pop up)", NotificationManager.IMPORTANCE_HIGH, dnd = true)
            mk(CH_QUIET, "Quiet notifications (in the list only)", NotificationManager.IMPORTANCE_LOW)
            listOf("alerts3", "alerts_low3", "ai_tips2", "eat_timer2").forEach { try { nm.deleteNotificationChannel(it) } catch (_: Exception) { } }
        }
        val st = Store(c)
        return if (!st.popFor(kind)) CH_QUIET else if (lowKind(kind)) CH_POP_LOW else CH_POP_ALL
    }

    /** After posting a notification: the chosen sound and vibration for that line. */
    fun after(c: Context, kind: String) {
        if (!Store(c).onFor(kind)) return
        if (kind in KINDS) playOnce(c, kind) else playGeneral(c, kind)
        if (Store(c).vibFor(kind)) vibrate(c, lowKind(kind) || kind == "eat")
    }

    @Suppress("DEPRECATION")
    private fun vibrate(c: Context, always: Boolean) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!always && am.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val v = (if (android.os.Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
            else c.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator) ?: return
        val p = longArrayOf(0, 350, 200, 350)
        try { if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(p, -1)) else v.vibrate(p, -1) } catch (_: Exception) { }
    }

    private val stopper = Runnable { stop() }

    fun stop() {
        main.removeCallbacks(stopper)
        try { once?.stop(); once?.release() } catch (_: Exception) { }
        once = null
        try { tone?.release() } catch (_: Exception) { }
        tone = null
    }
}
