package com.nova.glucose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One A4 PDF per day: assessment, glucose graph, meals with photos and foods, insulin, alerts. */
object DailyPdf {
    private const val W = 595; private const val H = 842; private const val M = 36

    private class Doc(val pdf: PdfDocument) {
        var page: PdfDocument.Page? = null
        var cv: Canvas? = null
        var y = 0f
        var n = 0
        fun newPage() {
            page?.let { pdf.finishPage(it) }
            n++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, n).create())
            cv = page!!.canvas; y = M.toFloat()
        }
        fun need(h: Float) { if (y + h > H - M) newPage() }
    }

    private fun paint(size: Float, color: Int = Color.rgb(30, 30, 30), bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; isFakeBoldText = bold
    }

    private val teal = Color.rgb(15, 110, 86)
    private val muted = Color.rgb(110, 110, 110)
    private val red = Color.rgb(180, 40, 40)
    private val amber = Color.rgb(190, 120, 20)

    /** Draws wrapped text, returns new y. */
    private fun text(d: Doc, s: String, p: Paint, x: Float = M.toFloat(), width: Float = (W - 2 * M).toFloat()) {
        for (para in s.split("\n")) {
            var line = ""
            for (w in para.split(" ")) {
                val t = if (line.isEmpty()) w else "$line $w"
                if (p.measureText(t) > width && line.isNotEmpty()) {
                    d.need(p.textSize + 4); d.cv!!.drawText(line, x, d.y + p.textSize, p); d.y += p.textSize + 4; line = w
                } else line = t
            }
            d.need(p.textSize + 4); d.cv!!.drawText(line, x, d.y + p.textSize, p); d.y += p.textSize + 4
        }
    }

    fun build(c: Context, day: Calendar): ByteArray {
        val st = Store(c)
        val db = Db.get(c)
        val (a, b) = Vault.dayRange(day)
        val readings = db.readings(a, b)
        val s = Vault.stats(st, readings)
        val meals = db.meals(100_000).filter { it.ts in a..b }.sortedBy { it.ts }
        val doses = db.doses(a, b).sortedBy { it.ts }
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        val doc = Doc(PdfDocument())
        doc.newPage()

        text(doc, "${st.patientName.ifBlank { "Glucose" }} — daily glucose report", paint(18f, teal, true))
        text(doc, SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).format(Date(a)) +
            (if (st.age > 0) " · age ${st.age}" else "") + " · Nova Glucose", paint(11f, muted))
        doc.y += 8

        // ---------------------------------------------------------- assessment
        text(doc, "Assessment", paint(14f, bold = true))
        if (s == null) text(doc, "No readings for this day.", paint(11f, muted)) else {
            val tirColor = if (s.inRange >= 70) teal else if (s.inRange >= 50) amber else red
            text(doc, "Time in range ${st.low}–${st.high}: ${s.inRange}%   (common goal: over 70%)", paint(13f, tirColor, true))
            text(doc, "Average ${s.avg} mg/dL · GMI ${"%.1f".format(Locale.US, s.gmi)}% · lowest ${s.min} · highest ${s.max} · " +
                "coverage ${minOf(100, s.count * 100 / 288)}%", paint(11f))
            text(doc, "Below ${st.low}: ${s.below}% (goal <4%) · below ${st.urgentLow}: ${s.veryLow}% (goal <1%) · " +
                "above ${st.high}: ${s.above}% · above ${st.veryHigh}: ${s.veryHigh}% · low episodes: ${s.lowEpisodes}", paint(11f))
        }
        doc.y += 8

        // ---------------------------------------------------------- graph
        doc.need(190f)
        drawGraph(doc.cv!!, RectF(M + 28f, doc.y, (W - M).toFloat(), doc.y + 170f), readings, a, b, st, meals.map { it.ts }, doses.filter { it.kind == "rapid" }.map { it.ts }, doses.filter { it.kind == "long" }.map { it.ts })
        doc.y += 190f

        // ---------------------------------------------------------- insulin
        text(doc, "Insulin given", paint(14f, bold = true))
        val presc = listOfNotNull(
            st.longName.takeIf { it.isNotBlank() }?.let { "Long-acting $it" + (st.longDose.takeIf { d -> d.isNotBlank() }?.let { d -> " $d u" } ?: "") +
                (st.longTime.takeIf { t -> t.isNotBlank() }?.let { t -> " at $t" } ?: "") },
            st.rapidName.takeIf { it.isNotBlank() }?.let { "Rapid $it" },
            st.carbRatio.takeIf { it > 0 }?.let { "ratio 1 u per $it g" })
        if (presc.isNotEmpty()) text(doc, "Prescribed (reference): " + presc.joinToString(" · "), paint(10f, muted))
        if (doses.isEmpty()) text(doc, "No doses logged.", paint(11f, muted))
        doses.forEach { x ->
            val name = if (x.kind == "long") st.longName.ifBlank { "Long-acting" } else st.rapidName.ifBlank { "Rapid" }
            text(doc, "${hm.format(Date(x.ts))}  $name  ${fmt(x.units)} u" +
                (if (x.carbs > 0) " · meal ${x.carbs} g" else if (x.correction) " · correction" else if (x.carbs == Carbs.UNKNOWN) " · meal, carbs unknown" else "") +
                (if (x.kind == "rapid" && x.waitMin > 0) " · waited ${x.waitMin} min" else "") +
                (if (x.mgAt > 0) " · glucose ${x.mgAt}" else ""), paint(11f))
        }
        doc.y += 8

        // ---------------------------------------------------------- meals
        text(doc, L.s("Meals") + " (" + Carbs.total(meals) + ")", paint(14f, bold = true))
        if (meals.isEmpty()) text(doc, "No meals logged.", paint(11f, muted))
        for (m in meals) {
            doc.need(150f)
            val top = doc.y
            val photo = m.photo.takeIf { it.isNotBlank() && File(it).exists() }?.let { thumb(it) }
            var x = M.toFloat()
            if (photo != null) {
                val dst = RectF(M.toFloat(), top + 4, M + 130f, top + 4 + 130f * photo.height / photo.width)
                doc.cv!!.drawBitmap(photo, null, dst, null); x = M + 142f
            }
            val after = db.near(m.ts + 2 * 3600_000L)?.mg
            val w = (W - M) - x
            text(doc, "${hm.format(Date(m.ts))} · ${m.title}", paint(12f, bold = true), x, w)
            val range = if (m.high > m.low) " (likely ${m.low}–${m.high})" else ""
            val ai = db.ai(m.id)
            text(doc, (if (m.known) "Carbs counted: ${m.carbs} g$range" else "Carbs: ? g · not recorded") + (ai?.let { " · AI first estimate ${it.second} g" } ?: ""), paint(11f, teal, true), x, w)
            text(doc, "Glucose ${if (m.mgAt > 0) m.mgAt else "–"} → ${after ?: "–"} mg/dL after 2 h", paint(11f), x, w)
            try {
                val arr = JSONArray(m.itemsJson)
                for (k in 0 until arr.length()) arr.getJSONObject(k).let {
                    val p = it.optString("portion")
                    text(doc, "• ${it.optString("name")}${if (p.isNotBlank()) " ($p)" else ""} — ${it.optInt("carbs")} g", paint(10f), x, w)
                }
            } catch (_: Exception) { }
            if (m.note.isNotBlank()) text(doc, "Note: ${m.note}", paint(10f, muted), x, w)
            val photoBottom = if (photo != null) top + 8 + 130f * photo.height / photo.width else top
            if (doc.y < photoBottom) doc.y = photoBottom
            doc.y += 10
        }

        // ---------------------------------------------------------- hourly
        if (readings.isNotEmpty()) {
            doc.y += 4
            text(doc, "Hourly average (mg/dL)", paint(14f, bold = true))
            val byHour = readings.groupBy { Calendar.getInstance().apply { timeInMillis = it.ts }.get(Calendar.HOUR_OF_DAY) }
            text(doc, (0..23).mapNotNull { h -> byHour[h]?.let { l -> "%02d:00 %d".format(h, l.sumOf { it.mg } / l.size) } }.joinToString("   "), paint(10f))
        }
        doc.y += 10
        text(doc, Foods.CARE_NOTE, paint(9f, muted))
        text(doc, "Designed by A.HAMROUNI", paint(9f, muted))
        text(doc, Ui.version(c), paint(9f, muted))

        doc.page?.let { doc.pdf.finishPage(it) }
        val out = ByteArrayOutputStream()
        doc.pdf.writeTo(out); doc.pdf.close()
        return out.toByteArray()
    }

    private fun fmt(u: Double) = if (u == Math.floor(u)) u.toInt().toString() else "%.1f".format(Locale.US, u)

    private fun thumb(path: String): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 600) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Exception) { null }

    private fun drawGraph(cv: Canvas, r: RectF, rs: List<Reading>, t0: Long, t1: Long, st: Store, meals: List<Long>, rapid: List<Long>, longs: List<Long> = emptyList()) {
        val maxV = maxOf(300, (rs.maxOfOrNull { it.mg } ?: 0) + 20); val minV = 40
        fun y(v: Int) = r.bottom - (v - minV).toFloat() / (maxV - minV) * r.height()
        fun x(t: Long) = r.left + (t - t0).toFloat() / (t1 - t0) * r.width()
        cv.drawRect(r.left, y(st.high), r.right, y(st.low), Paint().apply { color = Color.argb(45, 125, 203, 107) })
        val grid = Paint().apply { color = Color.argb(40, 0, 0, 0); strokeWidth = 0.6f }
        val lab = paint(8f, muted)
        var v = 50
        while (v <= maxV) { cv.drawLine(r.left, y(v), r.right, y(v), grid); cv.drawText("$v", r.left - 24, y(v) + 3, lab); v += 50 }
        for (h in 0..24 step 3) { val xx = r.left + h / 24f * r.width(); cv.drawText("%02d".format(h % 24), xx - 5, r.bottom + 11, lab) }
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f) }
        dash.color = red; cv.drawLine(r.left, y(st.low), r.right, y(st.low), dash)
        dash.color = amber; cv.drawLine(r.left, y(st.high), r.right, y(st.high), dash)
        val mark = paint(8f, teal, true)
        meals.forEach { cv.drawText("M", x(it) - 3, r.top + 8, mark) }
        rapid.forEach { cv.drawText("I", x(it) - 2, r.top + 18, paint(8f, Color.rgb(60, 80, 200), true)) }
        longs.forEach { cv.drawText("LI", x(it) - 4, r.top + 28, paint(8f, Color.rgb(60, 80, 200), true)) }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f; color = Color.rgb(25, 25, 25) }
        val path = Path(); var prev: Reading? = null
        for (p in rs) {
            if (prev == null || p.ts - prev.ts > 30 * 60_000) path.moveTo(x(p.ts), y(p.mg)) else path.lineTo(x(p.ts), y(p.mg))
            prev = p
        }
        cv.drawPath(path, line)
        cv.drawRect(r, Paint().apply { style = Paint.Style.STROKE; color = Color.argb(60, 0, 0, 0); strokeWidth = 0.6f })
        cv.drawText("M = meal · I = rapid insulin · LI = long-acting", r.right - 170, r.bottom + 22, lab)
        @Suppress("UNUSED_VARIABLE") val unused = Rect()
    }
}
