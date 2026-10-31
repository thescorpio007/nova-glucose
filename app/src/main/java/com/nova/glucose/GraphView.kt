package com.nova.glucose

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 12-hour glucose graph with the target band, like LibreLinkUp. */
class GraphView(c: Context) : View(c) {
    var readings: List<Reading> = emptyList(); set(v) { field = v; invalidate() }
    var low = 70; var high = 180; var lowLine = 70; var highLine = 180
    var hours = 12; set(v) { field = v; invalidate() }
    var meals: List<Long> = emptyList(); set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF; strokeWidth = d }
    private val band = Paint().apply { color = 0x267DCB6B }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = 11 * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT; strokeWidth = 2.5f * d; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mealP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; textSize = 13 * d }
    private val tf = SimpleDateFormat("ha", Locale.US)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(w), (220 * d).toInt())
    }

    override fun onDraw(cv: Canvas) {
        val left = 34 * d; val right = width - 10 * d; val top = 10 * d; val bottom = height - 22 * d
        val now = System.currentTimeMillis()
        val t0 = now - hours * 3600_000L
        val maxV = maxOf(300, (readings.maxOfOrNull { it.mg } ?: 0) + 20)
        val minV = 40
        fun y(v: Int) = bottom - (v - minV).toFloat() / (maxV - minV) * (bottom - top)
        fun x(t: Long) = left + (t - t0).toFloat() / (now - t0) * (right - left)

        cv.drawRect(left, y(high), right, y(low), band)
        var v = 50
        while (v <= maxV) {
            cv.drawLine(left, y(v), right, y(v), grid)
            cv.drawText(v.toString(), 2 * d, y(v) + 4 * d, label)
            v += if (maxV > 320) 100 else 50
        }
        dash.color = Ui.BAD; cv.drawLine(left, y(lowLine), right, y(lowLine), dash)
        dash.color = Ui.WARN; cv.drawLine(left, y(highLine), right, y(highLine), dash)

        // time labels every 3 hours
        val step = 3 * 3600_000L
        var t = (t0 / step + 1) * step
        while (t < now) {
            cv.drawText(tf.format(Date(t)).lowercase(), x(t) - 10 * d, height - 6 * d, label)
            t += step
        }

        meals.filter { it in t0..now }.forEach { cv.drawText("🍽", x(it) - 7 * d, top + 12 * d, mealP) }

        val pts = readings.filter { it.ts >= t0 }
        if (pts.isEmpty()) {
            cv.drawText("No readings yet", (width / 2f) - 45 * d, height / 2f, label); return
        }
        val path = Path()
        var prev: Reading? = null
        for (r in pts) {
            // gap of more than 30 minutes = sensor/phone offline: break the line
            if (prev == null || r.ts - prev.ts > 30 * 60_000) path.moveTo(x(r.ts), y(r.mg)) else path.lineTo(x(r.ts), y(r.mg))
            prev = r
        }
        cv.drawPath(path, line)
        val last = pts.last()
        dot.color = when {
            last.mg < lowLine -> Ui.BAD
            last.mg > highLine -> Ui.WARN
            else -> Ui.IN_RANGE
        }
        cv.drawCircle(x(last.ts), y(last.mg), 6 * d, dot)
    }
}
