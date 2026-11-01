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
 * The Download/NovaGlucose folder:
 *   Readings/2026-11/readings-2026-11-01.csv     one file per day
 *   Daily/2026-11/Aycha-Daily-2026-11-01.pdf     daily report: graph, assessment, meals with photos, insulin
 *   Meals/meals.csv · meals.json · Photos/
 *   Insulin/insulin.csv · insulin.json
 *   Alerts/alerts-2026-11.csv
 *   AI-Training/corrections.csv                  what the AI estimated vs what the family counted
 *   Backup/Aycha-Glucose-Backup-2026-11-01-0843.novaglucose   (newest 7 kept)
 * The user picks the folder once; after a reinstall, picking it again lets the app resume everything.
 */
object Vault {
    const val FOLDER = "NovaGlucose"
    const val EXT = ".novaglucose"
    private const val KEEP_BACKUPS = 7
    private val lock = Any()
    private val dayF get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val monF get() = SimpleDateFormat("yyyy-MM", Locale.US)
    private val tsF get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val stampF get() = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)

    // ---------------------------------------------------------------- folder access

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

    fun hasBackup(c: Context): Boolean = try { root(c)?.let { latestBackup(it) } != null } catch (_: Exception) { false }

    private fun latestBackup(r: DocumentFile): DocumentFile? =
        r.findFile("Backup")?.listFiles()?.filter { (it.name ?: "").endsWith(EXT) }?.maxByOrNull { it.name ?: "" }
            ?: r.findFile("NovaGlucose-backup.json")

    // ---------------------------------------------------------------- file helpers

    private fun dir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
            ?: throw IllegalStateException("Can't create folder $name")

    private fun file(dir: DocumentFile, name: String, mime: String): DocumentFile =
        dir.findFile(name) ?: dir.createFile(mime, name) ?: throw IllegalStateException("Can't create $name")

    private fun write(c: Context, f: DocumentFile, text: String) = writeBytes(c, f, text.toByteArray(Charsets.UTF_8))

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

    private fun q(s: String) = "\"" + s.replace("\"", "'").replace("\n", " ") + "\""

    // ---------------------------------------------------------------- layout upgrade (build 05 → 06)

    private fun migrate(c: Context, r: DocumentFile, st: Store) {
        if (st.layout >= 2) return
        val oldAlerts = mutableListOf<Pair<String, String>>()
        r.findFile("alerts")?.let { d ->
            d.listFiles().forEach { f -> f.name?.let { n -> try { oldAlerts += n to read(c, f) } catch (_: Exception) { } } }
        }
        // Old lowercase folders are rebuilt from the app's data in the new layout.
        for (n in listOf("readings", "assessment", "meals", "alerts")) r.listFiles().filter { it.name == n }.forEach { it.delete() }
        if (oldAlerts.isNotEmpty()) {
            val d = dir(r, "Alerts")
            oldAlerts.forEach { (n, t) -> write(c, file(d, n, "text/csv"), t) }
        }
        Db.get(c).resetExport()
        st.layout = 2
    }

    // ---------------------------------------------------------------- export

    /** Everything new since last time. [pdfNow] rebuilds today's report immediately (after a meal or dose). */
    fun exportAll(c: Context, forceBackup: Boolean = false, pdfNow: Boolean = false): String? = synchronized(lock) {
        val r = try { root(c) } catch (_: Exception) { null } ?: return "Folder not chosen"
        val st = Store(c)
        val errors = mutableListOf<String>()
        fun part(name: String, f: () -> Unit) = try { f() } catch (e: Exception) { errors += "$name: ${e.message}" }
        part("upgrade") { migrate(c, r, st) }
        part("readings") { exportReadings(c, r) }
        part("meals") { exportMeals(c, r) }
        part("insulin") { exportInsulin(c, r) }
        part("training") { exportTraining(c, r) }
        if (pdfNow || System.currentTimeMillis() - st.lastPdf > 60 * 60_000L) part("daily report") { exportDaily(c, r, st) }
        if (forceBackup || System.currentTimeMillis() - st.lastBackupTs > 24 * 3600_000L) part("backup") { backupTo(c, r) }
        st.lastExport = System.currentTimeMillis()
        errors.firstOrNull()
    }

    private fun exportReadings(c: Context, r: DocumentFile) {
        val db = Db.get(c)
        val list = db.unexported()
        if (list.isEmpty()) return
        val top = dir(r, "Readings")
        val tf = tsF
        list.groupBy { dayF.format(Date(it.ts)) }.forEach { (day, rs) ->
            val d = dir(top, day.substring(0, 7))
            val name = "readings-$day.csv"
            val existing = d.findFile(name)
            val f = existing ?: file(d, name, "text/csv")
            val sb = StringBuilder()
            if (existing == null) sb.append("epoch_ms,date_time,mg_dl,trend\n")
            rs.forEach { sb.append("${it.ts},${tf.format(Date(it.ts))},${it.mg},${Libre.trendText(it.trend)}\n") }
            append(c, f, sb.toString())
        }
        db.markExported(list)
    }

    fun exportMeals(c: Context) = synchronized(lock) {
        root(c)?.let { try { migrate(c, it, Store(c)); exportMeals(c, it); exportTraining(c, it); exportDaily(c, it, Store(c)) } catch (_: Exception) { } }
    }

    private fun exportMeals(c: Context, r: DocumentFile) {
        val db = Db.get(c)
        val meals = db.meals(100_000)
        val d = dir(r, "Meals")
        val photos = dir(d, "Photos")
        val have = photos.listFiles().mapNotNull { it.name }.toSet()
        val arr = JSONArray()
        val csv = StringBuilder("date_time,meal,carbs_g,likely_low_g,likely_high_g,ai_estimate_g,glucose_at_meal,glucose_2h_after,photo,note\n")
        val tf = tsF
        for (m in meals.sortedBy { it.ts }) {
            var photoName = ""
            val pf = File(m.photo)
            if (m.photo.isNotBlank() && pf.exists()) {
                photoName = pf.name
                if (photoName !in have) writeBytes(c, file(photos, photoName, "image/jpeg"), pf.readBytes())
            }
            val ai = db.ai(m.id)
            arr.put(JSONObject().put("ts", m.ts).put("title", m.title).put("items", JSONArray(m.itemsJson))
                .put("carbs", m.carbs).put("low", m.low).put("high", m.high).put("mgAt", m.mgAt)
                .put("photo", photoName).put("note", m.note)
                .apply { if (ai != null) { put("aiItems", JSONArray(ai.first)); put("aiCarbs", ai.second) } })
            val after = db.near(m.ts + 2 * 3600_000L)?.mg?.toString() ?: ""
            csv.append("${tf.format(Date(m.ts))},${q(m.title)},${m.carbs},${m.low},${m.high},${ai?.second ?: ""}," +
                "${if (m.mgAt > 0) m.mgAt else ""},$after,$photoName,${q(m.note)}\n")
        }
        write(c, file(d, "meals.json", "application/json"), arr.toString(1))
        write(c, file(d, "meals.csv", "text/csv"), csv.toString())
    }

    private fun exportInsulin(c: Context, r: DocumentFile) {
        val st = Store(c)
        val list = Db.get(c).doses().sortedBy { it.ts }
        val d = dir(r, "Insulin")
        val tf = tsF
        val csv = StringBuilder("date_time,type,insulin,units,meal_carbs_g,wait_before_eating_min,glucose_at_dose\n")
        val arr = JSONArray()
        list.forEach { x ->
            val name = if (x.kind == "long") st.longName else st.rapidName
            csv.append("${tf.format(Date(x.ts))},${x.kind},${q(name)},${x.units},${if (x.carbs > 0) x.carbs else ""}," +
                "${if (x.kind == "rapid") x.waitMin else ""},${if (x.mgAt > 0) x.mgAt else ""}\n")
            arr.put(JSONObject().put("ts", x.ts).put("kind", x.kind).put("units", x.units).put("carbs", x.carbs)
                .put("wait", x.waitMin).put("mgAt", x.mgAt).put("note", x.note))
        }
        val prescribed = "Prescribed (reference): long-acting ${st.longName} ${st.longDose} u at ${st.longTime} · " +
            "rapid ${st.rapidName} · ratio ${if (st.carbRatio > 0) "1 u per ${st.carbRatio} g" else "not set"}\n"
        write(c, file(d, "insulin.csv", "text/csv"), csv.toString())
        write(c, file(d, "insulin.json", "application/json"), arr.toString(1))
        write(c, file(d, "prescription.txt", "text/plain"), prescribed)
    }

    private fun exportTraining(c: Context, r: DocumentFile) {
        val rows = Training.rows(c)
        if (rows.isEmpty()) return
        val d = dir(r, "AI-Training")
        val sb = StringBuilder("date_time,meal_photo,food,ai_estimate_g,family_count_g,difference_g,change\n")
        rows.forEach { sb.append("${tsF.format(Date(it.ts))},${it.photo},${q(it.food)},${it.ai ?: ""},${it.family ?: ""},${it.diff},${it.change}\n") }
        write(c, file(d, "corrections.csv", "text/csv"), sb.toString())
        if (d.findFile("README.txt") == null) write(c, file(d, "README.txt", "text/plain"),
            "Each row is one food: what the AI estimated from the photo and what the family counted after correcting it.\n" +
            "Nova Glucose sends recent corrections with each new photo, so estimates move closer to how this family counts.\n" +
            "Photos are in Meals/Photos (same file name).\n")
    }

    private fun exportDaily(c: Context, r: DocumentFile, st: Store) {
        val top = dir(r, "Daily")
        for (back in 0..1) {
            val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back) }
            val stamp = dayF.format(day.time)
            val d = dir(top, stamp.substring(0, 7))
            val name = "${st.fileName}-Daily-$stamp.pdf"
            if (back == 1 && d.findFile(name) != null && st.lastPdf > dayRange(day).second + 3600_000L) continue
            writeBytes(c, file(d, name, "application/pdf"), DailyPdf.build(c, day))
        }
        st.lastPdf = System.currentTimeMillis()
    }

    fun logAlert(c: Context, title: String, body: String) = synchronized(lock) {
        try {
            val r = root(c) ?: return@synchronized
            migrate(c, r, Store(c))
            val d = dir(r, "Alerts")
            val now = System.currentTimeMillis()
            val name = "alerts-${monF.format(Date(now))}.csv"
            val existing = d.findFile(name)
            val f = existing ?: file(d, name, "text/csv")
            append(c, f, (if (existing == null) "date_time,alert,advice\n" else "") + "${tsF.format(Date(now))},${q(title)},${q(body)}\n")
        } catch (_: Exception) { }
    }

    fun backupFileName(c: Context) = "${Store(c).fileName}-Glucose-Backup-${stampF.format(Date())}$EXT"

    fun backupNow(c: Context): String = synchronized(lock) {
        val r = root(c) ?: throw IllegalStateException("Choose the Download/NovaGlucose folder first.")
        "Backup saved: ${folderLabel(c)}/Backup/${backupTo(c, r)}"
    }

    private fun backupTo(c: Context, r: DocumentFile): String {
        val st = Store(c)
        val d = dir(r, "Backup")
        val name = backupFileName(c)
        writeBytes(c, d.createFile("application/octet-stream", name) ?: file(d, name, "application/octet-stream"),
            Backup.build(c, st.backupPw).toByteArray(Charsets.UTF_8))
        // Android may add an extension: make sure the file keeps the exact name.
        d.listFiles().firstOrNull { it.name?.startsWith(name) == true && it.name != name }?.renameTo(name)
        d.listFiles().filter { (it.name ?: "").endsWith(EXT) }.sortedByDescending { it.name }.drop(KEEP_BACKUPS).forEach { it.delete() }
        r.findFile("NovaGlucose-backup.json")?.delete()
        st.lastBackupTs = System.currentTimeMillis()
        return name
    }

    // ---------------------------------------------------------------- assessment

    private fun sample(rs: List<Reading>): List<Reading> =
        rs.groupBy { it.ts / 300_000L }.map { it.value.first() }.sortedBy { it.ts }

    fun stats(st: Store, all: List<Reading>): DayStats? {
        val rs = sample(all)
        if (rs.isEmpty()) return null
        val n = rs.size
        fun pct(p: (Int) -> Boolean) = Math.round(rs.count { p(it.mg) } * 100.0 / n).toInt()
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

    // ---------------------------------------------------------------- resume after reinstall

    fun restoreAll(c: Context, password: String): String = synchronized(lock) {
        val r = root(c) ?: throw IllegalStateException("Choose the Download/NovaGlucose folder first.")
        val out = mutableListOf<String>()
        latestBackup(r)?.let { out += Backup.restore(c, read(c, it), password) }
        val db = Db.get(c)
        var nR = 0
        fun csvFiles(d: DocumentFile?): List<DocumentFile> = d?.listFiles()?.flatMap {
            if (it.isDirectory) csvFiles(it) else if ((it.name ?: "").endsWith(".csv")) listOf(it) else emptyList()
        } ?: emptyList()
        (csvFiles(r.findFile("Readings")) + csvFiles(r.findFile("readings"))).forEach { f ->
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
        }
        var nM = 0
        (r.findFile("Meals") ?: r.findFile("meals"))?.let { md ->
            val photos = md.findFile("Photos") ?: md.findFile("photos")
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
                    val id = db.addMeal(Meal(0, ts, o.optString("title"), (o.optJSONArray("items") ?: JSONArray()).toString(),
                        o.optInt("carbs"), o.optInt("low"), o.optInt("high"), o.optInt("mgAt"), path, o.optString("note")))
                    o.optJSONArray("aiItems")?.let { db.setAi(id, it.toString(), o.optInt("aiCarbs")) }
                    nM++
                }
            }
        }
        var nD = 0
        r.findFile("Insulin")?.findFile("insulin.json")?.let { jf ->
            val arr = JSONArray(read(c, jf))
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val ts = o.optLong("ts")
                if (ts == 0L || db.hasDoseAt(ts)) continue
                db.addDose(Dose(0, ts, o.optString("kind", "rapid"), o.optDouble("units"), o.optInt("carbs"),
                    o.optInt("wait"), o.optInt("mgAt"), o.optString("note"))); nD++
            }
        }
        Store(c).resumeAsked = true
        out += "$nR readings, $nM meals and $nD insulin doses restored"
        out.joinToString(" · ")
    }
}
