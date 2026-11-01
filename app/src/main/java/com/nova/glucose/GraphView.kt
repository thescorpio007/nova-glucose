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
    var low = 70; var high = 180; var lowLine = 70; var highLine = 180
    var meals: List<Long> = emptyList()
    var doses: List<Long> = emptyList()
    /** Tap on an M or I mark: time of the meal/dose. */
    var onMark: ((Long) -> Unit)? = null
    private var gx0 = 0f; private var gx1 = 0f; private var markBottom = 0f

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (onMark == null || mode == "m" || t1 <= t0) return false
        if (e.y > markBottom + 10 * d) return false
        if (e.action == android.view.MotionEvent.ACTION_DOWN) return true
        if (e.action == android.view.MotionEvent.ACTION_UP) {
            fun x(t: Long) = gx0 + (t - t0).toFloat() / (t1 - t0) * (gx1 - gx0)
            val hit = (meals + doses).filter { it in t0..t1 }.minByOrNull { kotlin.math.abs(x(it) - e.x) }
            if (hit != null && kotlin.math.abs(x(hit) - e.x) < 22 * d) { performClick(); onMark?.invoke(hit); return true }
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
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * d; isFakeBoldText = true; color = MARK }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(w), (230 * d).toInt())
    }

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }

    override fun onDraw(cv: Canvas) {
        if (t1 <= t0) return
        val left = 34 * d; val right = width - 10 * d; val top = 18 * d; val bottom = height - 22 * d
        gx0 = left; gx1 = right; markBottom = top + 12 * d
        val maxV = maxOf(300, (readings.maxOfOrNull { it.mg } ?: 0) + 20)
        val minV = 40
        fun y(v: Int) = bottom - (v - minV).toFloat() / (maxV - minV) * (bottom - top)
        fun x(t: Long) = left + (t - t0).toFloat() / (t1 - t0) * (right - left)

        cv.drawRect(left, y(high), right, y(low), band)
        var v = 50
        while (v <= maxV) {
            cv.drawLine(left, y(v), right, y(v), grid)
            cv.drawText(v.toString(), 2 * d, y(v) + 4 * d, label)
            v += if (maxV > 320) 100 else 50
        }
        dash.color = Ui.BAD; cv.drawLine(left, y(lowLine), right, y(lowLine), dash)
        dash.color = Ui.WARN; cv.drawLine(left, y(highLine), right, y(highLine), dash)

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
                    val s = names[c0.get(Calendar.DAY_OF_WEEK) - 1]
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
            val s = "No readings for this period"
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
        if (mode == "12") {
            val last = pts.last()
            dot.color = colorFor(last.mg)
            cv.drawCircle(x(last.ts), y(last.mg), 6 * d, dot)
        }
    }

    companion object { const val MARK = 0xFFFF5A5F.toInt() }

    private fun colorFor(mg: Int) = when {
        mg < lowLine -> Ui.BAD
        mg > highLine -> Ui.WARN
        else -> Ui.IN_RANGE
    }
}
