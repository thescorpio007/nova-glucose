package com.nova.glucose

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Calendar

/**
 * Glucose chart with the target band.
 * mode "12": last 12 hours, every hour labelled · "24": one full day · "w": one week · "m": one month (daily average + low–high band).
 */
class GraphView(c: Context) : View(c) {
    var readings: List<Reading> = emptyList()
    var mode = "12"
    var t0 = 0L; var t1 = 0L
    var low = 70; var high = 180; var lowLine = 70; var highLine = 180; var urgentLine = 55; var veryHighLine = 250
    /** Crosshair: null = live (latest reading); while the finger moves, the time under it. Returns to live on release. */
    private var cursorT: Long? = null
    /** The small card hides 10 s after the chart appears (or after a drag); the guide lines and axis labels stay. */
    private var cardUntil = System.currentTimeMillis() + 10_000L
    fun showCard() { cardUntil = System.currentTimeMillis() + 10_000L; invalidate(); postInvalidateDelayed(10_100L) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); showCard() }
    private var dragging = false
    private var downX = 0f; private var downY = 0f
    private val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop
    var meals: List<Long> = emptyList()
    var doses: List<Long> = emptyList()
    /** Tap on an M or I mark: time of the meal/dose. */
    var onMark: ((Long) -> Unit)? = null
    var incidents: List<Incident> = emptyList()
    var onIncident: ((Long) -> Unit)? = null
    private var gTop = 0f; private var gBottom = 0f
    private var gx0 = 0f; private var gx1 = 0f; private var markBottom = 0f

    private fun xOf(t: Long) = gx0 + (t - t0).toFloat() / (t1 - t0) * (gx1 - gx0)

    private fun markAt(ex: Float, ey: Float): Long? {
        if (onMark == null || mode == "m" || ey > markBottom + 10 * d) return null
        val hit = (meals + doses).filter { it in t0..t1 }.minByOrNull { kotlin.math.abs(xOf(it) - ex) } ?: return null
        return if (kotlin.math.abs(xOf(hit) - ex) < 22 * d) hit else null
    }

    private fun incidentAt(ex: Float, ey: Float): Incident? {
        if (onIncident == null || ey < gTop || ey > gBottom + 12 * d) return null
        val now = System.currentTimeMillis()
        return incidents.filter { ex >= xOf(maxOf(it.ts, t0)) - 10 * d && ex <= xOf(minOf(if (it.ongoing) now else it.end, t1)) + 10 * d }
            .maxByOrNull { it.ts }
    }

    private fun timeAt(x: Float): Long = t0 + ((x.coerceIn(gx0, gx1) - gx0) / (gx1 - gx0) * (t1 - t0)).toLong()

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (t1 <= t0) return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; return true }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (!dragging && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    dragging = true; parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) { cursorT = timeAt(e.x); invalidate() }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (dragging) { dragging = false; cursorT = null; showCard(); return true }     // back to live immediately
                if (kotlin.math.abs(e.x - downX) < slop && kotlin.math.abs(e.y - downY) < slop) {
                    val m = markAt(e.x, e.y)
                    val inc = if (m == null) incidentAt(e.x, e.y) else null
                    if (m != null || inc != null) { performClick(); if (m != null) onMark?.invoke(m) else inc?.let { onIncident?.invoke(it.id) } }
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> { dragging = false; cursorT = null; showCard(); return true }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun set(mode: String, t0: Long, t1: Long, rs: List<Reading>) {
        this.mode = mode; this.t0 = t0; this.t1 = t1; readings = rs; invalidate()
    }

    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF; strokeWidth = d }
    private val vgrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF; strokeWidth = d }
    private val band = Paint().apply { color = 0x267DCB6B }
    private val range = Paint().apply { color = 0x337C6CFF }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = 11 * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val limLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f * d; isFakeBoldText = true }
    private val crossLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.2f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF062B27.toInt(); textSize = 11 * d; isFakeBoldText = true }
    private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0232A34.toInt() }
    private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f * d }
    private val card1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textSize = 12.5f * d; isFakeBoldText = true }
    private val card2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = 11 * d }
    private val incBand = Paint().apply { color = 0x22F5A623 }
    private val incEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88F5A623.toInt(); strokeWidth = 1.2f * d }
    private val incLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Incidents.COLOR; textSize = 11 * d; isFakeBoldText = true }
    private val incMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Incidents.COLOR; textSize = 14 * d; isFakeBoldText = true }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * d; isFakeBoldText = true; color = MARK }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(w), (230 * d).toInt())
    }

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }

    override fun onDraw(cv: Canvas) {
        if (t1 <= t0) return
        val left = 34 * d; val right = width - 30 * d; val top = 18 * d; val bottom = height - 22 * d
        gx0 = left; gx1 = right; markBottom = top + 12 * d; gTop = top; gBottom = bottom
        val maxV = maxOf(300, (readings.maxOfOrNull { it.mg } ?: 0) + 20)
        val minV = 40
        fun y(v: Int) = bottom - (v - minV).toFloat() / (maxV - minV) * (bottom - top)
        fun x(t: Long) = left + (t - t0).toFloat() / (t1 - t0) * (right - left)

        cv.drawRect(left, y(high), right, y(low), band)
        // Incidents: shaded period, name at the top, "!" at the bottom
        val now = System.currentTimeMillis()
        incidents.forEach { inc ->
            val a = maxOf(inc.ts, t0); val b = minOf(if (inc.ongoing) now else inc.end, t1)
            if (b < t0 || a > t1) return@forEach
            val xa = x(a); val xb = maxOf(x(b), xa + 3 * d)
            cv.drawRect(xa, top, xb, bottom, incBand)
            cv.drawLine(xa, top, xa, bottom, incEdge)
            val name = inc.kind.let { if (incLabel.measureText(it) > xb - xa - 6 * d && xb - xa < 60 * d) "" else it }
            if (name.isNotEmpty()) cv.drawText(L.s(name), xa + 4 * d, top + 24 * d, incLabel)
            cv.drawText("!", xa - 2 * d, bottom - 4 * d, incMark)
        }
        var v = 50
        while (v <= maxV) {
            cv.drawLine(left, y(v), right, y(v), grid)
            cv.drawText(v.toString(), 2 * d, y(v) + 4 * d, label)
            v += if (maxV > 320) 100 else 50
        }
        // The four limits, labelled on the right: very high, high, low (dashed), urgent low (solid); extreme zones shaded
        cv.drawRect(left, top, right, y(veryHighLine).coerceAtLeast(top), Paint().apply { color = 0x12FF5A5F })
        cv.drawRect(left, y(urgentLine), right, bottom, Paint().apply { color = 0x1FFF5A5F })
        fun limit(v: Int, col: Int, solid: Boolean) {
            val p = if (solid) Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; strokeWidth = 1.6f * d } else dash.apply { color = col }
            cv.drawLine(left, y(v), right, y(v), p)
            limLabel.color = col; cv.drawText(v.toString(), right + 3 * d, y(v) + 4 * d, limLabel)
        }
        limit(veryHighLine, 0xFFFF5A5F.toInt(), false); limit(highLine, Ui.WARN, false)
        limit(lowLine, 0xFFFF9B9E.toInt(), false); limit(urgentLine, 0xFFFF5A5F.toInt(), true)

        // ---------------------------------------------------------- x axis
        when (mode) {
            "12", "24" -> {
                val stepH = if (mode == "12") 1 else 3
                val c0 = cal(t0).apply { set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                if (c0.timeInMillis < t0) c0.add(Calendar.HOUR_OF_DAY, 1)
                while (c0.get(Calendar.HOUR_OF_DAY) % stepH != 0) c0.add(Calendar.HOUR_OF_DAY, 1)
                while (c0.timeInMillis <= t1) {
                    val xx = x(c0.timeInMillis)
                    cv.drawLine(xx, top, xx, bottom, vgrid)
                    val s = "%02d".format(c0.get(Calendar.HOUR_OF_DAY))
                    val tw = label.measureText(s)
                    if (xx - tw / 2 > left - 4 * d && xx + tw / 2 < width) cv.drawText(s, xx - tw / 2, height - 6 * d, label)
                    c0.add(Calendar.HOUR_OF_DAY, stepH)
                }
            }
            "w" -> {
                val names = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                val c0 = cal(t0)
                for (k in 0 until 7) {
                    val xs = x(c0.timeInMillis)
                    cv.drawLine(xs, top, xs, bottom, vgrid)
                    val s = L.s(names[c0.get(Calendar.DAY_OF_WEEK) - 1])
                    val mid = xs + (right - left) / 14
                    cv.drawText(s, mid - label.measureText(s) / 2, height - 6 * d, label)
                    c0.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            "m" -> {
                val c0 = cal(t0)
                while (c0.timeInMillis < t1) {
                    val day = c0.get(Calendar.DAY_OF_MONTH)
                    if (day == 1 || day % 7 == 1 || day == c0.getActualMaximum(Calendar.DAY_OF_MONTH)) {
                        val xx = x(c0.timeInMillis + 12 * 3600_000L)
                        val s = day.toString()
                        cv.drawText(s, xx - label.measureText(s) / 2, height - 6 * d, label)
                    }
                    c0.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }

        val pts = readings.filter { it.ts in t0..t1 }
        if (pts.isEmpty()) {
            val s = L.s("No readings for this period")
            cv.drawText(s, (width - label.measureText(s)) / 2f, height / 2f, label); return
        }

        if (mode == "m") {
            // Daily average line with lowest–highest band.
            val byDay = pts.groupBy { val c = cal(it.ts); c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR) }
            val days = byDay.values.map { l ->
                val c = cal(l.first().ts).apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }
                Triple(c.timeInMillis, l.sumOf { it.mg } / l.size, l.minOf { it.mg } to l.maxOf { it.mg })
            }.sortedBy { it.first }
            val w = (right - left) / 31f * 0.6f
            days.forEach { (t, _, mm) -> cv.drawRect(x(t) - w / 2, y(mm.second), x(t) + w / 2, y(mm.first), range) }
            val p = Path()
            days.forEachIndexed { k, (t, avg, _) -> if (k == 0) p.moveTo(x(t), y(avg)) else p.lineTo(x(t), y(avg)) }
            line.strokeWidth = 2.5f * d; cv.drawPath(p, line)
            days.forEach { (t, avg, _) -> dot.color = colorFor(avg); cv.drawCircle(x(t), y(avg), 3 * d, dot) }
            val ct = cursorT
            val pick = if (ct == null) days.last() else days.minByOrNull { kotlin.math.abs(it.first - ct) }!!
            cross(cv, x(pick.first), y(pick.second), pick.second, ct == null && dayOf(pick.first) == dayOf(System.currentTimeMillis()),
                java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.US).format(java.util.Date(pick.first)) + " · average ${pick.second}",
                "lowest ${pick.third.first} · highest ${pick.third.second}", java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.util.Date(pick.first)),
                left, right, top, bottom)
            return
        }

        // Meal (M) and rapid insulin (I) marks
        meals.filter { it in t0..t1 }.forEach { cv.drawText("M", x(it) - 5 * d, top - 4 * d, mark) }
        doses.filter { it in t0..t1 }.forEach { cv.drawText("I", x(it) - 2 * d, top + 10 * d, mark) }

        val path = Path()
        var prev: Reading? = null
        val gap = if (mode == "w") 60 * 60_000L else 30 * 60_000L
        for (r in pts) {
            if (prev == null || r.ts - prev.ts > gap) path.moveTo(x(r.ts), y(r.mg)) else path.lineTo(x(r.ts), y(r.mg))
            prev = r
        }
        line.strokeWidth = (if (mode == "w") 1.3f else 2.5f) * d
        cv.drawPath(path, line)
        // Crosshair: live by default, follows the finger while dragging
        val ct = cursorT
        val k = if (ct == null) pts.size - 1 else pts.indices.minByOrNull { kotlin.math.abs(pts[it].ts - ct) }!!
        val r = pts[k]
        val live = ct == null && now - r.ts < 15 * 60_000L
        val arrow = if (live) Libre.trendArrow(r.trend) else {
            val prev = pts.getOrNull(k - 1)?.takeIf { r.ts - it.ts < 20 * 60_000L }
            when { prev == null -> ""; r.mg - prev.mg > 3 -> "↗"; r.mg - prev.mg < -3 -> "↘"; else -> "→" }
        }
        val hm = java.text.SimpleDateFormat(if (mode == "w") "EEE HH:mm" else "HH:mm", java.util.Locale.US).format(java.util.Date(r.ts))
        val ago = ((now - r.ts) / 60_000).let { m -> if (m < 1) "now" else if (m < 60) "$m min ago" else if (m < 48 * 60) "${m / 60} h ${m % 60} min ago" else "${m / 1440} days ago" }
        if (mode == "12" || live) { dot.color = colorFor(r.mg); cv.drawCircle(x(r.ts), y(r.mg), 6 * d, dot) }
        cross(cv, x(r.ts), y(r.mg), r.mg, live,
            (if (live) L.s("LIVE") + " · " else "$hm · ") + "${r.mg} mg/dL $arrow".trim(),
            L.s(if (live) "Now" else ago) + " · " + L.s(zone(r.mg)), if (live) L.s("Now") else hm, left, right, top, bottom)
    }

    private fun dayOf(t: Long) = cal(t).let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }

    private fun zone(v: Int) = when { v < urgentLine -> "urgent low"; v < lowLine -> "low"; v > veryHighLine -> "very high"; v > highLine -> "high"; else -> "in range" }

    /** Two guide lines (time and value) with their labels on the axes and a small card. Teal = live, blue = earlier time. */
    private fun cross(cv: Canvas, x: Float, y: Float, v: Int, live: Boolean, l1: String, l2: String, xText: String,
                      left: Float, right: Float, top: Float, bottom: Float) {
        val col = if (live) Ui.ACCENT else 0xFF8FA8FF.toInt()
        crossLine.color = col
        cv.drawLine(x, top, x, bottom, crossLine); cv.drawLine(left, y, right, y, crossLine)
        fill.color = col
        // value on the left axis
        val vs = v.toString(); val vw = chip.measureText(vs) + 8 * d
        cv.drawRoundRect(android.graphics.RectF(left - vw - 2 * d, y - 9 * d, left - 2 * d, y + 9 * d), 5 * d, 5 * d, fill)
        cv.drawText(vs, left - vw + 2 * d, y + 4 * d, chip)
        // time on the bottom axis
        val xw = chip.measureText(xText) + 10 * d
        val xl = (x - xw / 2).coerceIn(left - 4 * d, width - xw)
        cv.drawRoundRect(android.graphics.RectF(xl, bottom + 2 * d, xl + xw, bottom + 20 * d), 5 * d, 5 * d, fill)
        cv.drawText(xText, xl + 5 * d, bottom + 15 * d, chip)
        // card (hidden 10 s after the chart appears, shown while dragging)
        if (!dragging && System.currentTimeMillis() > cardUntil) return
        val w = maxOf(card1.measureText(l1), card2.measureText(l2)) + 18 * d
        val h = 40 * d
        val bx = if (x + 10 * d + w < right) x + 8 * d else x - 8 * d - w
        val by = (y - h - 10 * d).coerceIn(top, bottom - h)
        val rr = android.graphics.RectF(bx, by, bx + w, by + h)
        cv.drawRoundRect(rr, 8 * d, 8 * d, cardBg)
        cardEdge.color = col; cv.drawRoundRect(rr, 8 * d, 8 * d, cardEdge)
        cv.drawText(l1, bx + 9 * d, by + 17 * d, card1)
        cv.drawText(l2, bx + 9 * d, by + 32 * d, card2)
    }

    companion object { const val MARK = 0xFFFF5A5F.toInt() }

    private fun colorFor(mg: Int) = when {
        mg < lowLine -> Ui.BAD
        mg > highLine -> Ui.WARN
        else -> Ui.IN_RANGE
    }
}
