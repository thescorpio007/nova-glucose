package com.nova.glucose

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🐞 Error report: crashes and errors (LibreLinkUp, family sync, watch, AI, update, alarm) with the phone and
 * settings info, kept 30 days on this phone, shared as a .txt file for development.
 * No personal data: no names, passwords, keys, family code, glucose values or meals.
 */
object ErrorLog {
    private const val KEEP_MS = 30L * 24 * 3600_000
    private const val FILE = "errlog.txt"
    @Volatile private var app: Context? = null
    private val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val main = t === android.os.Looper.getMainLooper().thread
            try { write(if (main) "CRASH" else "BACKGROUND", "${e.javaClass.name}: ${e.message ?: ""} [thread ${t.name}]\n" + e.stackTraceToString().lines().take(25).joinToString("\n")) } catch (_: Throwable) { }
            // a failure in a background task (network, backup, AI…) only stops that task — the app, alarms and readings keep running
            if (main) prev?.uncaughtException(t, e)
        }
    }

    /** Records an error (area: Libre, Sync, Watch, AI, Update, Alarm…). */
    fun e(area: String, msg: String?, err: Throwable? = null) {
        val m = (msg ?: err?.message ?: err?.javaClass?.simpleName ?: "").trim()
        if (m.isEmpty()) return
        try { write(area, m + (err?.let { " (${it.javaClass.simpleName})" } ?: "")) } catch (_: Throwable) { }
    }

    /** Removes personal data from a message. */
    fun scrub(s: String): String = s
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "<email>")
        .replace(Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}\\b"), "<family code>")
        .replace(Regex("(AIza|sk-|gsk_|xai-)[\\w-]{8,}"), "<key>")
        .replace(Regex("\\b[A-Za-z0-9_\\-]{32,}\\b"), "<token>")
        .replace(Regex("(?i)(password|token|key|secret)=\\S+"), "$1=<hidden>")

    @Synchronized private fun write(area: String, msg: String) {
        val c = app ?: return
        val f = File(c.filesDir, FILE)
        val now = System.currentTimeMillis()
        f.appendText("${tf.format(Date(now))} | $area | ${scrub(msg).replace("\n", "\n    ")}\n")
        if (f.length() > 400_000) prune(f, now)
    }

    private fun entries(f: File): List<Pair<Long, String>> {
        if (!f.exists()) return emptyList()
        val out = ArrayList<Pair<Long, String>>()
        var cur: StringBuilder? = null; var ts = 0L
        f.readLines().forEach { l ->
            val t = if (l.length > 19 && l[4] == '-' && l[13] == ':') try { tf.parse(l.substring(0, 19))?.time } catch (_: Exception) { null } else null
            if (t != null) { cur?.let { out += ts to it.toString() }; cur = StringBuilder(l); ts = t } else cur?.append("\n")?.append(l)
        }
        cur?.let { out += ts to it.toString() }
        return out
    }

    @Synchronized private fun prune(f: File, now: Long) {
        val keep = entries(f).filter { now - it.first < KEEP_MS }.takeLast(1500)
        f.writeText(keep.joinToString("") { it.second + "\n" })
    }

    fun count(c: Context): Int { app ?: init(c); val f = File(c.filesDir, FILE); prune(f, System.currentTimeMillis()); return entries(f).size }

    fun clear(c: Context) { File(c.filesDir, FILE).delete() }

    private fun yn(b: Boolean) = if (b) "✓" else "✗"

    /** The full report text. */
    fun report(c: Context): String {
        val st = Store(c); val now = System.currentTimeMillis()
        val f = File(c.filesDir, FILE); prune(f, now)
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pm = c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val am = c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val p = Providers.byId(st.aiProvider)
        fun ago(t: Long) = if (t <= 0) "never" else ((now - t) / 60_000).let { if (it < 120) "$it min ago" else "${it / 60} h ago" }
        val sb = StringBuilder()
        sb.append("Nova Glucose — error report\n")
        sb.append("App: ${Updater.versionName(c)} · build ${Updater.current(c)} · made ${tf.format(Date(now))}\n")
        sb.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · language ${st.lang.ifBlank { L.lang }}\n")
        sb.append("No personal data: no names, passwords, keys, family code, glucose values or meals.\n\n")
        sb.append("— Setup —\n")
        sb.append("Phone role: ${st.role.ifBlank { "not chosen" }} · LibreLinkUp signed in ${yn(st.loggedIn)} · last reading ${ago(st.lastTs)}\n")
        sb.append("Family sync ${yn(st.syncFam.isNotBlank())} · last sync ${ago(st.syncLast)} · ${scrub(st.syncInfo).take(120)}\n")
        sb.append("AI: ${p?.label ?: st.aiProvider} · ${p?.let { st.model(it) } ?: ""} · key set ${yn(p != null && st.key(p).isNotBlank())}\n")
        sb.append("Watch data ${yn(st.hcOn)} · permissions ${HealthRead.granted(c).size}/${HealthRead.PERMS.size} · background ${yn(HealthRead.background(c))} · ${scrub(st.hcInfo).take(120)}\n")
        sb.append("Parental lock ${yn(st.pinOn)} · uninstall protection ${yn(Guard.protected(c))} · watch glucose every ${st.watchGlucose} min\n")
        sb.append("Update: ${scrub(Updater.status(c)).take(160)}\n\n")
        sb.append("— Permissions —\n")
        sb.append("Notifications ${yn(Build.VERSION.SDK_INT < 33 || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED)}")
        sb.append(" · full-screen ${yn(Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent())}")
        sb.append(" · Do Not Disturb ${yn(nm.isNotificationPolicyAccessGranted)}")
        sb.append(" · exact timers ${yn(Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())}")
        sb.append(" · no battery limits ${yn(Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(c.packageName))}")
        sb.append(" · over other apps ${yn(Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c))}\n\n")
        sb.append("— Alerts —\n")
        sb.append("Urgent alarm ${yn(st.urgentOn)}: urgent low ${yn(st.urgUrgentLow)} · low ${yn(st.urgLow)} · high ${yn(st.urgHigh)} · very high ${yn(st.urgVeryHigh)} · no signal ${yn(st.urgNoSignal)}")
        sb.append(" · ring ${st.ringSec} s · volume ${st.alarmVolume}%\n")
        (Sounds.KINDS + Sounds.GENERAL).forEach { k ->
            sb.append("  $k: ${scrub(Sounds.displayName(st, k))} · vibrate ${yn(st.vibFor(k))} · pop-up ${yn(st.popFor(k))}\n")
        }
        val list = entries(f).reversed()
        sb.append("\n— Errors and crashes, last 30 days (${list.size}) —\n")
        if (list.isEmpty()) sb.append("None recorded.\n") else list.forEach { sb.append(it.second).append("\n") }
        return sb.toString()
    }

    fun share(a: Activity) {
        try {
            val dir = File(a.cacheDir, "capture").apply { mkdirs() }
            val out = File(dir, "NovaGlucose-error-report-build${Updater.current(a)}.txt")
            out.writeText(report(a))
            val uri = FileProvider.getUriForFile(a, "com.nova.glucose.files", out)
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Nova Glucose error report · build ${Updater.current(a)}")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), L.s("Share the error report")))
        } catch (e: Exception) { Ui.toast(a, e.message ?: "Couldn't make the report") }
    }
}
