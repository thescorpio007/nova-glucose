package com.nova.glucose

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "Share with doctor": a 14/30/90-day PDF report (no photos) shared through Android's share menu. */
object DoctorReport {
    private const val W = 595; private const val H = 842; private const val M = 36f
    private val teal = Color.rgb(15, 110, 86)
    private val muted = Color.rgb(110, 110, 110)
    private val red = Color.rgb(180, 40, 40)
    private val amber = Color.rgb(190, 120, 20)
    private val green = Color.rgb(59, 109, 17)
    private val purple = Color.rgb(83, 74, 183)

    fun pick(a: Activity) {
        val opts = arrayOf("Last 14 days", "Last 30 days", "Last 90 days")
        Tr.Builder(a).setTitle("📤 Share with doctor").setItems(opts) { _, w ->
            val days = intArrayOf(14, 30, 90)[w]
            Ui.toast(a, "Preparing the report…")
            val app = a.applicationContext
            Thread {
                val r = try { Result.success(make(app, days)) } catch (e: Exception) { Result.failure(e) }
                a.runOnUiThread {
                    r.onSuccess { f -> share(a, f) }.onFailure { Ui.toast(a, "Couldn't make the report: ${it.message}") }
                }
            }.start()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun share(a: Activity, f: File) {
        val uri = FileProvider.getUriForFile(a, "com.nova.glucose.files", f)
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension.replace('-', ' '))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, "Send the report to the doctor"))
    }

    // ---------------------------------------------------------------- drawing helpers

    private class Doc(val pdf: PdfDocument, val foot: String) {
        var page: PdfDocument.Page? = null; var cv: Canvas? = null; var y = 0f; var n = 0
        fun newPage() {
            page?.let { finish() }
            n++; page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()); cv = page!!.canvas; y = M
        }
        fun finish() {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f; color = Color.rgb(120, 120, 120) }
            cv!!.drawText("Page $n", W / 2f - 12, H - 22f, p)
            cv!!.drawText("Designed by A.HAMROUNI", W - M - p.measureText("Designed by A.HAMROUNI"), H - 28f, p)
            cv!!.drawText(foot, W - M - p.measureText(foot), H - 18f, p)
            pdf.finishPage(page); page = null
        }
        fun need(h: Float) { if (y + h > H - 48) newPage() }
    }

    private fun paint(size: Float, color: Int = Color.rgb(30, 30, 30), bold: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; isFakeBoldText = bold }

    private fun text(d: Doc, s: String, p: Paint, x: Float = M, width: Float = W - 2 * M) {
        var line = ""
        for (w in s.split(" ")) {
            val t = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(t) > width && line.isNotEmpty()) {
                d.need(p.textSize + 4); d.cv!!.drawText(line, x, d.y + p.textSize, p); d.y += p.textSize + 4; line = w
            } else line = t
        }
        d.need(p.textSize + 4); d.cv!!.drawText(line, x, d.y + p.textSize, p); d.y += p.textSize + 4
    }

    private fun h2(d: Doc, s: String) { d.y += 8; d.need(40f); text(d, s, paint(12.5f, bold = true)); d.y += 2 }

    /** Simple table; widths in points. */
    private fun table(d: Doc, rows: List<List<String>>, widths: List<Float>, headColor: Int) {
        val p = paint(8f); val hp = paint(8f, bold = true)
        val rh = 15f
        rows.forEachIndexed { k, r ->
            d.need(rh)
            var x = M
            if (k == 0) d.cv!!.drawRect(M, d.y, M + widths.sum(), d.y + rh, Paint().apply { color = headColor })
            else if (k % 2 == 0) d.cv!!.drawRect(M, d.y, M + widths.sum(), d.y + rh, Paint().apply { color = Color.rgb(247, 247, 247) })
            r.forEachIndexed { i, c ->
                d.cv!!.drawText(c, x + 4, d.y + 10.5f, if (k == 0) hp else p); x += widths[i]
            }
            d.cv!!.drawLine(M, d.y + rh, M + widths.sum(), d.y + rh, Paint().apply { color = Color.rgb(221, 221, 221); strokeWidth = 0.4f })
            d.y += rh
        }
    }

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }

    // ---------------------------------------------------------------- report

    fun make(c: Context, days: Int): File {
        val st = Store(c); val db = Db.get(c)
        val endCal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val now = System.currentTimeMillis()
        val start = endCal.timeInMillis - (days - 1) * 24 * 3600_000L
        val raw = db.readings(start, now)
        val rs = raw.groupBy { it.ts / 300_000L }.map { it.value.first() }.sortedBy { it.ts }   // 5-minute samples
        val s = Vault.stats(st, raw)
        val meals = db.meals(100_000).filter { it.ts in start..now }.sortedBy { it.ts }
        val doses = db.doses(start, now).sortedBy { it.ts }
        val df = SimpleDateFormat("d MMM yyyy", Locale.US)
        val dfs = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val name = st.patientName.ifBlank { "Glucose" }
        val doc = Doc(PdfDocument(), Ui.version(c).let { "Nova Glucose $it" })
        doc.newPage()

        text(doc, "Glucose report for the diabetes team", paint(17f, teal, true))
        text(doc, "$name · age ${st.age} · ${df.format(Date(start))} – ${df.format(Date(now))} ($days days) · FreeStyle Libre via LibreLinkUp · created ${df.format(Date(now))}", paint(9f))
        doc.y += 6

        if (s == null) {
            text(doc, "No readings in this period.", paint(11f, muted))
        } else {
            val sd = Math.sqrt(rs.sumOf { (it.mg - s.avg) * (it.mg - s.avg).toDouble() } / rs.size)
            val cv = sd / s.avg * 100
            val coverage = minOf(100, rs.size * 100 / (days * 288))
            // KPI boxes
            val kp = listOf(
                Triple("Estimated HbA1c (GMI)", "%.1f%%".format(Locale.US, s.gmi), ""),
                Triple("Average glucose", "${s.avg} mg/dL", "SD ${sd.toInt()} mg/dL"),
                Triple("Time in range ${st.low}–${st.high}", "${s.inRange}%", "goal >70%"),
                Triple("Variability (CV)", "${cv.toInt()}%", "goal ≤36%"),
                Triple("Sensor data", "$coverage%", "goal ≥70%"))
            val bw = (W - 2 * M) / 5
            kp.forEachIndexed { k, (l, v, g) ->
                val x = M + k * bw
                doc.cv!!.drawRect(x + 1, doc.y, x + bw - 1, doc.y + 52, Paint().apply { color = Color.rgb(243, 245, 247) })
                doc.cv!!.drawText(l, x + 6, doc.y + 12, paint(7f, muted))
                val vc = when (k) { 0 -> Color.rgb(24, 95, 165); 2 -> if (s.inRange >= 70) green else amber; else -> Color.rgb(30, 30, 30) }
                doc.cv!!.drawText(v, x + 6, doc.y + 32, paint(15f, vc, true))
                doc.cv!!.drawText(g, x + 6, doc.y + 45, paint(7f, muted))
            }
            doc.y += 60

            h2(doc, "Time in ranges and daily pattern (AGP — all $days days on one 24-hour chart)")
            doc.need(170f)
            // TIR stacked bar
            val parts = listOf(s.veryLow to red, (s.below - s.veryLow) to Color.rgb(226, 75, 74), s.inRange to green,
                (s.above - s.veryHigh) to Color.rgb(239, 159, 39), s.veryHigh to Color.rgb(133, 79, 11))
            val bt = doc.y; val bh = 150f
            var yy = bt + bh
            parts.forEach { (v, col) ->
                val h = bh * v / 100f
                doc.cv!!.drawRect(M + 4, yy - h, M + 34, yy, Paint().apply { color = col })
                if (v >= 3) doc.cv!!.drawText("$v%", M + 38, yy - h / 2 + 3, paint(7.5f))
                yy -= h
            }
            // AGP
            agp(doc.cv!!, RectF(M + 92, bt, W - M, bt + bh), rs, st)
            doc.y += bh + 18
            text(doc, "Very low <${st.urgentLow}: ${s.veryLow}% (goal <1%) · Low <${st.low}: ${s.below}% (goal <4%) · In range: ${s.inRange}% (goal >70%) · " +
                "High >${st.high}: ${s.above}% · Very high >${st.veryHigh}: ${s.veryHigh}% (goal <5%) · Low episodes: ${s.lowEpisodes}", paint(8.5f))

            // patterns
            h2(doc, "Patterns seen (from the readings)")
            val med = median24(rs)
            fun peak(a: Int, b: Int) = (a * 4 until b * 4).maxByOrNull { med[it] ?: 0 }?.let { (med[it] ?: 0) to it }
            val lunch = peak(12, 17); val dinner = peak(19, 24)
            lunch?.let { (v, k) -> text(doc, "• After lunch: median peak $v mg/dL around %02d:%02d.".format(k / 4, (k % 4) * 15), paint(9f)) }
            dinner?.let { (v, k) -> text(doc, "• After dinner: median peak $v mg/dL around %02d:%02d.".format(k / 4, (k % 4) * 15), paint(9f)) }
            val night = (0 until 24).mapNotNull { med[it] }
            if (night.isNotEmpty()) {
                val lowK = (0 until 96).filter { med[it] != null }.minByOrNull { med[it]!! }!!
                text(doc, "• Night 00:00–06:00: median ${night.minOrNull()}–${night.maxOrNull()} mg/dL; lowest point of the day around %02d:%02d.".format(lowK / 4, (lowK % 4) * 15), paint(9f))
            }
            text(doc, "• ${s.lowEpisodes} low episodes in $days days — listed on the next page.", paint(9f))
            med[78]?.let { text(doc, "• Median glucose at 19:30: $it mg/dL vs target ${st.targetMg} mg/dL.", paint(9f)) }

            // daily average chart
            h2(doc, "Daily average with lowest–highest range")
            doc.need(130f)
            daily(doc.cv!!, RectF(M + 28, doc.y, W - M, doc.y + 110), rs, start, days, st)
            doc.y += 128
        }

        // ---------------------------------------------------------- page 2
        doc.newPage()
        h2(doc, "Insulin and prescription (as entered by the family)")
        val longs = doses.filter { it.kind == "long" }; val rapids = doses.filter { it.kind == "rapid" }
        table(doc, listOf(
            listOf("Long-acting", st.longName.ifBlank { "–" }, "Prescribed ${st.longDose.ifBlank { "–" }} u at ${st.longTime.ifBlank { "–" }}",
                "Logged ${longs.size} doses" + (if (longs.isNotEmpty()) ", avg ${fmt(longs.map { it.units }.average())} u" else "")),
            listOf("Rapid-acting", st.rapidName.ifBlank { "–" },
                "Ratio " + (if (st.carbRatio > 0) "1 u / ${st.carbRatio} g" else "–") + " · corr. " + (if (st.corrFactor > 0) "1 u / ${st.corrFactor}" else "–"),
                "Target ${st.targetMg} mg/dL"),
            listOf("Rapid logged", "${rapids.size} doses" + (if (rapids.isNotEmpty()) " · avg ${fmt(rapids.map { it.units }.average())} u" else ""),
                "Avg wait before eating: " + (rapids.filter { it.waitMin > 0 }.map { it.waitMin }.average().takeIf { !it.isNaN() }?.let { "${it.toInt()} min" } ?: "–"),
                "Meals logged: ${meals.size}" + (if (meals.isNotEmpty()) " · avg ${meals.map { it.carbs }.average().toInt()} g" else ""))),
            listOf(80f, 140f, 170f, 133f), Color.WHITE)

        h2(doc, "By meal (average over the period)")
        fun slot(t: Long) = when (cal(t).get(Calendar.HOUR_OF_DAY)) { in 5..10 -> "Breakfast"; in 11..15 -> "Lunch"; in 18..22 -> "Dinner"; else -> "Snacks" }
        val rowsM = mutableListOf(listOf("Meal", "Meals", "Avg carbs", "Avg rapid insulin", "Glucose before", "2 h after", "Change"))
        for (k in listOf("Breakfast", "Lunch", "Dinner", "Snacks")) {
            val ms = meals.filter { slot(it.ts) == k }
            if (ms.isEmpty()) { rowsM += listOf(k, "0", "–", "–", "–", "–", "–"); continue }
            val ins = ms.mapNotNull { m -> rapids.filter { kotlin.math.abs(it.ts - m.ts) < 60 * 60_000L }.minByOrNull { kotlin.math.abs(it.ts - m.ts) }?.units }
            val before = ms.mapNotNull { m -> db.near(m.ts)?.mg }; val after = ms.mapNotNull { m -> db.near(m.ts + 2 * 3600_000L)?.mg }
            val b = before.average().takeIf { !it.isNaN() }?.toInt(); val a = after.average().takeIf { !it.isNaN() }?.toInt()
            rowsM += listOf(k, "${ms.size}", "${ms.map { it.carbs }.average().toInt()} g",
                if (ins.isEmpty()) "–" else "${fmt(ins.average())} u", b?.toString() ?: "–", a?.toString() ?: "–",
                if (a != null && b != null) (if (a - b >= 0) "+" else "") + (a - b) else "–")
        }
        table(doc, rowsM, listOf(70f, 45f, 65f, 95f, 85f, 75f, 88f), Color.rgb(225, 245, 238))

        h2(doc, "Low and high events (lasting 15 minutes or more)")
        val ev = mutableListOf<Triple<Long, String, Pair<Int, Int>>>()
        fun episodes(cond: (Int) -> Boolean, kind: String, low: Boolean) {
            var cur = mutableListOf<Reading>()
            fun flush() {
                if (cur.size >= 3) ev += Triple(cur.first().ts, kind, (if (low) cur.minOf { it.mg } else cur.maxOf { it.mg }) to cur.size * 5)
                cur = mutableListOf()
            }
            for (r in rs) { if (cond(r.mg)) { if (cur.isNotEmpty() && r.ts - cur.last().ts > 15 * 60_000) flush(); cur += r } else flush() }
            flush()
        }
        episodes({ it < st.low }, "Low", true); episodes({ it > st.veryHigh }, "Very high", false)
        val tf = SimpleDateFormat("d MMM HH:mm", Locale.US)
        val evRows = mutableListOf(listOf("Date and time", "Event", "Lowest / highest", "Duration"))
        ev.sortedBy { it.first }.take(25).forEach { (t, k, vm) ->
            evRows += listOf(tf.format(Date(t)), k, "${vm.first} mg/dL", if (vm.second >= 60) "${vm.second / 60} h ${vm.second % 60} min" else "${vm.second} min")
        }
        if (evRows.size == 1) evRows += listOf("–", "No events", "", "")
        table(doc, evRows, listOf(130f, 100f, 130f, 163f), Color.rgb(252, 235, 235))

        h2(doc, "Incidents (illness and other events)")
        val incRows = mutableListOf(listOf("From", "Until", "Incident", "Details and prescription"))
        db.incidents(start, now).sortedBy { it.ts }.take(30).forEach { i ->
            incRows += listOf(tf.format(Date(i.ts)), if (i.ongoing) "ongoing" else tf.format(Date(i.end)), i.kind,
                i.details.let { if (it.length > 58) it.take(56) + "…" else it })
        }
        if (incRows.size == 1) incRows += listOf("–", "", "No incidents", "")
        table(doc, incRows, listOf(85f, 85f, 95f, 258f), Color.rgb(253, 243, 224))
        db.incidents(start, now).sortedBy { it.ts }.filter { it.meds.isNotBlank() || it.details.length > 58 }.take(10).forEach { i ->
            doc.y += 4
            text(doc, "${i.kind} (${tf.format(Date(i.ts))}): " + listOf(i.details, i.meds.replace("\n", "; ").let { if (it.isNotBlank()) "Prescription: $it" else "" })
                .filter { it.isNotBlank() }.joinToString(" · "), paint(8f))
        }

        if (st.aiReview.isNotBlank() && st.aiReviewTs >= start) {
            h2(doc, "AI review (${tf.format(Date(st.aiReviewTs))}, ${st.aiReviewDays} day(s)) — not medical advice")
            st.aiReview.lines().filter { it.isNotBlank() }.forEach { text(doc, it.trim(), paint(8.5f)) }
        }

        h2(doc, "Day by day")
        val dRows = mutableListOf(listOf("Date", "Average", "In range", "Lowest", "Highest", "Carbs logged", "Rapid insulin"))
        val ddf = SimpleDateFormat("EEE d MMM", Locale.US)
        for (k in 0 until days) {
            val a = start + k * 24 * 3600_000L; val b = a + 24 * 3600_000L - 1
            val dayR = rs.filter { it.ts in a..b }
            if (dayR.isEmpty()) continue
            val ds = Vault.stats(st, dayR)!!
            dRows += listOf(ddf.format(Date(a)), "${ds.avg}", "${ds.inRange}%", "${ds.min}", "${ds.max}",
                "${meals.filter { it.ts in a..b }.sumOf { it.carbs }} g", "${fmt(rapids.filter { it.ts in a..b }.sumOf { it.units })} u")
        }
        table(doc, dRows, listOf(90f, 60f, 60f, 60f, 60f, 95f, 98f), Color.rgb(238, 237, 254))
        doc.y += 10
        text(doc, "Notes: estimated HbA1c (GMI) is calculated from sensor glucose and can differ from a lab HbA1c by about ±0.5%. " +
            "Carbs are counted by the family (photo estimates corrected by hand). Insulin doses are as given by the family; " +
            "Nova Glucose does not calculate doses.", paint(7.5f, muted))

        doc.finish()
        val dir = File(c.cacheDir, "capture").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.contains("Doctor-Report") }?.forEach { it.delete() }
        val f = File(dir, "${st.fileName}-Glucose-Doctor-Report-${dfs.format(Date(start))}-to-${dfs.format(Date(now))}.pdf")
        f.outputStream().use { doc.pdf.writeTo(it) }; doc.pdf.close()
        Vault.saveReport(c, f)
        return f
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(Locale.US, v)

    /** Median per 15-minute slot of the day (index 0..95). */
    private fun median24(rs: List<Reading>): Map<Int, Int> =
        rs.groupBy { val c = cal(it.ts); c.get(Calendar.HOUR_OF_DAY) * 4 + c.get(Calendar.MINUTE) / 15 }
            .mapValues { (_, l) -> l.map { it.mg }.sorted()[l.size / 2] }

    private fun agp(cv: Canvas, r: RectF, rs: List<Reading>, st: Store) {
        val bySlot = rs.groupBy { val c = cal(it.ts); c.get(Calendar.HOUR_OF_DAY) * 4 + c.get(Calendar.MINUTE) / 15 }.mapValues { it.value.map { r -> r.mg }.sorted() }
        fun q(l: List<Int>, p: Double) = l[(p * (l.size - 1)).toInt()]
        fun x(k: Int) = r.left + (k + 0.5f) / 96f * r.width()
        fun y(v: Int) = r.bottom - (v - 40f) / 280f * r.height()
        cv.drawRect(r.left, y(st.high), r.right, y(st.low), Paint().apply { color = Color.argb(45, 125, 203, 107) })
        val lab = paint(7f, muted)
        for (v in listOf(50, 100, 150, 200, 250, 300)) {
            cv.drawLine(r.left, y(v), r.right, y(v), Paint().apply { color = Color.argb(30, 0, 0, 0); strokeWidth = 0.4f })
            cv.drawText("$v", r.left - 18, y(v) + 3, lab)
        }
        for (h in 0..24 step 3) cv.drawText("%02d:00".format(h % 24), r.left + h / 24f * r.width() - 9, r.bottom + 10, lab)
        val keys = bySlot.keys.sorted()
        fun band(lo: Double, hi: Double, col: Int) {
            if (keys.isEmpty()) return
            val p = Path()
            keys.forEachIndexed { i, k -> val v = q(bySlot[k]!!, hi); if (i == 0) p.moveTo(x(k), y(v)) else p.lineTo(x(k), y(v)) }
            keys.reversed().forEach { k -> p.lineTo(x(k), y(q(bySlot[k]!!, lo))) }
            p.close(); cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        }
        band(0.05, 0.95, Color.argb(46, 127, 119, 221))
        band(0.25, 0.75, Color.argb(90, 83, 74, 183))
        val p = Path()
        keys.forEachIndexed { i, k -> val v = q(bySlot[k]!!, 0.5); if (i == 0) p.moveTo(x(k), y(v)) else p.lineTo(x(k), y(v)) }
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; color = Color.rgb(38, 33, 92) })
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f) }
        dash.color = red; cv.drawLine(r.left, y(st.low), r.right, y(st.low), dash)
        dash.color = amber; cv.drawLine(r.left, y(st.high), r.right, y(st.high), dash)
        cv.drawText("Median (line) · 25–75% (dark band) · 5–95% (light band)", r.left + 4, r.top + 8, lab)
    }

    private fun daily(cv: Canvas, r: RectF, rs: List<Reading>, start: Long, days: Int, st: Store) {
        fun y(v: Int) = r.bottom - (v - 40f) / 300f * r.height()
        fun x(k: Int) = r.left + (k + 0.5f) / days * r.width()
        cv.drawRect(r.left, y(st.high), r.right, y(st.low), Paint().apply { color = Color.argb(45, 125, 203, 107) })
        val lab = paint(7f, muted)
        for (v in listOf(50, 100, 150, 200, 250, 300)) cv.drawText("$v", r.left - 18, y(v) + 3, lab)
        val p = Path(); var first = true
        val bar = Paint().apply { color = Color.argb(110, 127, 119, 221) }
        val step = maxOf(1, days / 7)
        val ddf = SimpleDateFormat("d MMM", Locale.US)
        for (k in 0 until days) {
            val a = start + k * 24 * 3600_000L
            val l = rs.filter { it.ts in a until a + 24 * 3600_000L }
            if (k % step == 0) cv.drawText(ddf.format(Date(a)), x(k) - 10, r.bottom + 10, lab)
            if (l.isEmpty()) { first = true; continue }
            val w = maxOf(2f, r.width() / days * 0.45f)
            cv.drawRect(x(k) - w / 2, y(l.maxOf { it.mg }), x(k) + w / 2, y(l.minOf { it.mg }), bar)
            val avg = l.sumOf { it.mg } / l.size
            if (first) p.moveTo(x(k), y(avg)) else p.lineTo(x(k), y(avg)); first = false
            cv.drawCircle(x(k), y(avg), 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 33, 92) })
        }
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f; color = Color.rgb(38, 33, 92) })
        @Suppress("UNUSED_VARIABLE") val u = purple
    }
}
