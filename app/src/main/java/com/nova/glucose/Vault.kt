package com.nova.glucose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DayStats(
    val count: Int, val avg: Int, val min: Int, val max: Int,
    val inRange: Int, val below: Int, val veryLow: Int, val above: Int, val veryHigh: Int,
    val lowEpisodes: Int, val gmi: Double
)

/**
 * The Download/NovaGlucose folder: readings (CSV per month), meals (with photos), alerts, a daily glucose
 * assessment and the backup file. The user picks the folder once; after a reinstall, picking it again
 * lets the app resume everything from it.
 */
object Vault {
    const val FOLDER = "NovaGlucose"
    private val lock = Any()
    private val dayF get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val monF get() = SimpleDateFormat("yyyy-MM", Locale.US)
    private val tsF get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val hmF get() = SimpleDateFormat("HH:mm", Locale.US)

    // ---------------------------------------------------------------- folder access

    /** Creates Download/NovaGlucose when Android allows it, so the picker can open straight in it. */
    fun prepare() {
        try { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).mkdirs() } catch (_: Exception) { }
    }

    fun pickIntent(): Intent {
        prepare()
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (Build.VERSION.SDK_INT >= 26) putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/$FOLDER"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
    }

    fun onPicked(c: Context, uri: Uri) {
        c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Store(c).folderUri = uri.toString()
    }

    fun root(c: Context): DocumentFile? {
        val u = Store(c).folderUri.ifBlank { return null }
        val uri = Uri.parse(u)
        if (c.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isWritePermission }) return null
        return DocumentFile.fromTreeUri(c, uri)?.takeIf { it.canWrite() }
    }

    fun ready(c: Context) = try { root(c) != null } catch (_: Exception) { false }

    fun folderLabel(c: Context): String {
        val r = try { root(c) } catch (_: Exception) { null } ?: return "Not chosen"
        val path = Uri.decode(Store(c).folderUri).substringAfterLast(':', r.name ?: FOLDER)
        return path.ifBlank { r.name ?: FOLDER }
    }

    fun hasBackup(c: Context): Boolean = try { root(c)?.findFile(Backup.FILE) != null } catch (_: Exception) { false }

    // ---------------------------------------------------------------- file helpers

    private fun dir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
            ?: throw IllegalStateException("Can't create folder $name")

    private fun file(dir: DocumentFile, name: String, mime: String): DocumentFile =
        dir.findFile(name) ?: dir.createFile(mime, name) ?: throw IllegalStateException("Can't create $name")

    private fun write(c: Context, f: DocumentFile, text: String) {
        c.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun writeBytes(c: Context, f: DocumentFile, b: ByteArray) {
        c.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(b) }
    }

    private fun read(c: Context, f: DocumentFile): String =
        c.contentResolver.openInputStream(f.uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun append(c: Context, f: DocumentFile, text: String) {
        try {
            c.contentResolver.openOutputStream(f.uri, "wa")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
            write(c, f, (try { read(c, f) } catch (_: Exception) { "" }) + text)
        }
    }

    // ---------------------------------------------------------------- export

    /** Everything new since last time: readings, meals, assessments, and the daily backup. */
    fun exportAll(c: Context, forceBackup: Boolean = false): String? = synchronized(lock) {
        val r = root(c) ?: return "Folder not chosen"
        val st = Store(c)
        val errors = mutableListOf<String>()
        fun part(name: String, f: () -> Unit) = try { f() } catch (e: Exception) { errors += "$name: ${e.message}" }
        part("readings") { exportReadings(c, r, st) }
        part("meals") { exportMeals(c, r) }
        part("assessment") { exportAssessments(c, r, st) }
        if (forceBackup || System.currentTimeMillis() - st.lastBackupTs > 24 * 3600_000L) part("backup") { backupTo(c, r) }
        st.lastExport = System.currentTimeMillis()
        errors.firstOrNull()
    }

    private fun exportReadings(c: Context, r: DocumentFile, st: Store) {
        val list = Db.get(c).unexported()
        if (list.isEmpty()) return
        val d = dir(r, "readings")
        list.groupBy { monF.format(Date(it.ts)) }.forEach { (mon, rs) ->
            val name = "readings-$mon.csv"
            val existing = d.findFile(name)
            val f = existing ?: file(d, name, "text/csv")
            val sb = StringBuilder()
            if (existing == null) sb.append("epoch_ms,date_time,mg_dl,trend\n")
            val tf = tsF
            rs.forEach { sb.append("${it.ts},${tf.format(Date(it.ts))},${it.mg},${Libre.trendText(it.trend)}\n") }
            append(c, f, sb.toString())
        }
        Db.get(c).markExported(list)
        st.lastExportReading = list.last().ts
    }

    fun exportMeals(c: Context) = synchronized(lock) { root(c)?.let { try { exportMeals(c, it) } catch (_: Exception) { } } }

    private fun exportMeals(c: Context, r: DocumentFile) {
        val meals = Db.get(c).meals(100_000)
        val d = dir(r, "meals")
        val photos = dir(d, "photos")
        val have = photos.listFiles().mapNotNull { it.name }.toSet()
        val arr = JSONArray()
        val csv = StringBuilder("date_time,meal,carbs_g,likely_low_g,likely_high_g,glucose_at_meal,glucose_2h_after,note\n")
        val db = Db.get(c)
        val tf = tsF
        for (m in meals.sortedBy { it.ts }) {
            var photoName = ""
            val pf = File(m.photo)
            if (m.photo.isNotBlank() && pf.exists()) {
                photoName = pf.name
                if (photoName !in have) writeBytes(c, file(photos, photoName, "image/jpeg"), pf.readBytes())
            }
            arr.put(JSONObject().put("ts", m.ts).put("title", m.title).put("items", JSONArray(m.itemsJson))
                .put("carbs", m.carbs).put("low", m.low).put("high", m.high).put("mgAt", m.mgAt)
                .put("photo", photoName).put("note", m.note))
            val after = db.near(m.ts + 2 * 3600_000L)?.mg?.toString() ?: ""
            csv.append("${tf.format(Date(m.ts))},${q(m.title)},${m.carbs},${m.low},${m.high},${if (m.mgAt > 0) m.mgAt else ""},$after,${q(m.note)}\n")
        }
        write(c, file(d, "meals.json", "application/json"), arr.toString(1))
        write(c, file(d, "meals.csv", "text/csv"), csv.toString())
    }

    private fun q(s: String) = "\"" + s.replace("\"", "'").replace("\n", " ") + "\""

    fun logAlert(c: Context, title: String, body: String) = synchronized(lock) {
        try {
            val r = root(c) ?: return@synchronized
            val d = dir(r, "alerts")
            val now = System.currentTimeMillis()
            val name = "alerts-${monF.format(Date(now))}.csv"
            val existing = d.findFile(name)
            val f = existing ?: file(d, name, "text/csv")
            append(c, f, (if (existing == null) "date_time,alert,advice\n" else "") + "${tsF.format(Date(now))},${q(title)},${q(body)}\n")
        } catch (_: Exception) { }
    }

    fun backupNow(c: Context): String = synchronized(lock) {
        val r = root(c) ?: throw IllegalStateException("Choose the Download/NovaGlucose folder first.")
        backupTo(c, r); "Backup saved to ${folderLabel(c)}/${Backup.FILE}"
    }

    private fun backupTo(c: Context, r: DocumentFile) {
        val st = Store(c)
        write(c, file(r, Backup.FILE, "application/json"), Backup.build(c, st.backupPw))
        st.lastBackupTs = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------- assessment

    /** Readings sampled every 5 minutes (one per slot), so minute and 15-minute points weigh the same. */
    private fun sample(rs: List<Reading>): List<Reading> =
        rs.groupBy { it.ts / 300_000L }.map { it.value.first() }.sortedBy { it.ts }

    fun stats(st: Store, all: List<Reading>): DayStats? {
        val rs = sample(all)
        if (rs.isEmpty()) return null
        val n = rs.size
        fun pct(p: (Int) -> Boolean) = (rs.count { p(it.mg) } * 100.0 / n).let { Math.round(it).toInt() }
        var episodes = 0; var inLow = false
        for (r in rs) { if (r.mg < st.low && !inLow) { episodes++; inLow = true } else if (r.mg >= st.low) inLow = false }
        val avg = rs.sumOf { it.mg } / n
        return DayStats(n, avg, rs.minOf { it.mg }, rs.maxOf { it.mg },
            pct { it in st.low..st.high }, pct { it < st.low }, pct { it < st.urgentLow },
            pct { it > st.high }, pct { it > st.veryHigh }, episodes, 3.31 + 0.02392 * avg)
    }

    fun dayRange(day: Calendar): Pair<Long, Long> {
        val a = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return a.timeInMillis to a.timeInMillis + 24 * 3600_000L - 1
    }

    fun assessmentText(c: Context, day: Calendar): String {
        val st = Store(c)
        val (a, b) = dayRange(day)
        val db = Db.get(c)
        val s = stats(st, db.readings(a, b))
        val sb = StringBuilder()
        sb.append("Nova Glucose — daily glucose assessment\n")
        sb.append("${st.patientName.ifBlank { "Glucose" }} · ${SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).format(Date(a))}\n\n")
        if (s == null) { sb.append("No readings for this day.\n") } else {
            sb.append("Readings (5-min samples): ${s.count}  ·  coverage ${s.count * 100 / 288}%\n")
            sb.append("Average: ${s.avg} mg/dL  ·  GMI (estimated A1c): ${"%.1f".format(Locale.US, s.gmi)}%\n")
            sb.append("Lowest: ${s.min}  ·  Highest: ${s.max} mg/dL\n\n")
            sb.append("Time in range ${st.low}–${st.high}: ${s.inRange}%   (common goal: over 70%)\n")
            sb.append("Below ${st.low}: ${s.below}%   (goal: under 4%)\n")
            sb.append("Below ${st.urgentLow}: ${s.veryLow}%   (goal: under 1%)\n")
            sb.append("Above ${st.high}: ${s.above}%   ·   above ${st.veryHigh}: ${s.veryHigh}%   (goal: under 5%)\n")
            sb.append("Low episodes: ${s.lowEpisodes}\n\n")
            sb.append("Hourly average (mg/dL):\n")
            val byHour = db.readings(a, b).groupBy { Calendar.getInstance().apply { timeInMillis = it.ts }.get(Calendar.HOUR_OF_DAY) }
            for (h in 0..23) byHour[h]?.let { l -> sb.append("  %02d:00  %d\n".format(h, l.sumOf { it.mg } / l.size)) }
        }
        val meals = db.meals(100_000).filter { it.ts in a..b }.sortedBy { it.ts }
        sb.append("\nMeals (${meals.sumOf { it.carbs }} g carbs logged):\n")
        if (meals.isEmpty()) sb.append("  none logged\n")
        meals.forEach { m ->
            val after = db.near(m.ts + 2 * 3600_000L)?.mg
            sb.append("  ${hmF.format(Date(m.ts))}  ${m.title} — ${m.carbs} g")
            sb.append("  ·  glucose ${if (m.mgAt > 0) m.mgAt else "–"} → ${after ?: "–"} after 2 h\n")
        }
        sb.append("\n").append(Foods.CARE_NOTE).append("\n")
        return sb.toString()
    }

    private fun exportAssessments(c: Context, r: DocumentFile, st: Store) {
        val d = dir(r, "assessment")
        for (back in 0..1) {
            val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }
            val name = "assessment-${dayF.format(day.time)}.txt"
            if (back == 1 && d.findFile(name) != null && st.lastExport > dayRange(day).second + 3600_000L) continue
            write(c, file(d, name, "text/plain"), assessmentText(c, day))
        }
    }

    // ---------------------------------------------------------------- resume after reinstall

    /** Restores settings (with the password), readings and meals from the folder. Returns a summary. */
    fun restoreAll(c: Context, password: String): String = synchronized(lock) {
        val r = root(c) ?: throw IllegalStateException("Choose the Download/NovaGlucose folder first.")
        val out = mutableListOf<String>()
        r.findFile(Backup.FILE)?.let { out += Backup.restore(c, read(c, it), password) }
        val db = Db.get(c)
        var nR = 0; var maxTs = 0L
        r.findFile("readings")?.listFiles()?.filter { (it.name ?: "").endsWith(".csv") }?.forEach { f ->
            val batch = mutableListOf<Reading>()
            read(c, f).lineSequence().drop(1).forEach { line ->
                val p = line.split(',')
                val ts = p.getOrNull(0)?.toLongOrNull() ?: return@forEach
                val mg = p.getOrNull(2)?.toIntOrNull() ?: return@forEach
                val trend = when (p.getOrNull(3)) {
                    "falling fast" -> 1; "falling" -> 2; "steady" -> 3; "rising" -> 4; "rising fast" -> 5; else -> 0
                }
                batch += Reading(ts, mg, trend)
            }
            db.addReadings(batch, markNew = false); nR += batch.size
            batch.maxOfOrNull { it.ts }?.let { if (it > maxTs) maxTs = it }
        }
        var nM = 0
        r.findFile("meals")?.let { md ->
            val photos = md.findFile("photos")
            md.findFile("meals.json")?.let { jf ->
                val arr = JSONArray(read(c, jf))
                val local = File(c.filesDir, "meals").apply { mkdirs() }
                for (k in 0 until arr.length()) {
                    val o = arr.getJSONObject(k)
                    val ts = o.optLong("ts")
                    if (ts == 0L || db.hasMealAt(ts)) continue
                    var path = ""
                    val pn = o.optString("photo")
                    if (pn.isNotBlank()) photos?.findFile(pn)?.let { pf ->
                        val dst = File(local, pn)
                        c.contentResolver.openInputStream(pf.uri)?.use { inp -> dst.outputStream().use { inp.copyTo(it) } }
                        path = dst.absolutePath
                    }
                    db.addMeal(Meal(0, ts, o.optString("title"), (o.optJSONArray("items") ?: JSONArray()).toString(),
                        o.optInt("carbs"), o.optInt("low"), o.optInt("high"), o.optInt("mgAt"), path, o.optString("note")))
                    nM++
                }
            }
        }
        val st = Store(c)
        if (maxTs > st.lastExportReading) st.lastExportReading = maxTs
        st.resumeAsked = true
        out += "$nR readings and $nM meals restored"
        out.joinToString(" · ")
    }
}
