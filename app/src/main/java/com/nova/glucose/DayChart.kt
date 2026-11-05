package com.nova.glucose

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One day on one timeline: glucose on top, then steps per hour, heart rate and sleep under it.
 * Sport is shaded across all rows; M / I marks as on the home chart. Touch shows a crosshair with all values.
 */
class DayChart(c: Context) : View(c) {
    var start = 0L
    var readings: List<Reading> = emptyList()
    var w: WatchDay = WatchDay("")
    var sleep: List<Seg> = emptyList()
    var ex: List<Ex> = emptyList()
    var meals: List<Long> = emptyList()
    var doses: List<Long> = emptyList()
    var longs: List<Long> = emptyList()
    var low = 70; var high = 180
    private var touchX = -1f

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f); color = Ui.MUTED }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11f); isFakeBoldText = true }
    private val hm = SimpleDateFormat("HH:mm", Locale.US)

    companion object {
        const val STEPS = 0xFF5B8CFF.toInt(); const val HR = 0xFFFF7A9A.toInt(); const val SLEEP = 0xFF8E7CFF.toInt()
        val STAGE = mapOf(5 to 0xFF5A45D6.toInt(), 4 to 0xFF8E7CFF.toInt(), 6 to 0xFFB39DFF.toInt(), 2 to 0xFF7B6BE0.toInt(), 1 to 0xFFFFB347.toInt())
    }

    override fun onMeasure(wm: Int, hm0: Int) { setMeasuredDimension(MeasureSpec.getSize(wm), dp(410f).toInt()) }

    private val left get() = dp(34f)
    private val right get() = width - dp(8f)
    private fun x(t: Long) = left + (right - left) * ((t - start).toFloat() / (24 * 3600_000f))
    private fun tAt(px: Float) = start + ((px - left) / (right - left) * 24 * 3600_000f).toLong()

    private val gTop get() = dp(18f); private val gBot get() = dp(200f)
    private fun gy(v: Int) = gBot - (gBot - gTop) * ((v.coerceIn(40, 300) - 40f) / 260f)
    private val sTop get() = dp(220f); private val sBot get() = dp(272f)
    private val hTop get() = dp(292f); private val hBot get() = dp(344f)
    private fun hy(v: Int) = hBot - (hBot - hTop) * ((v.coerceIn(40, 200) - 40f) / 160f)
    private val lTop get() = dp(364f); private val lBot get() = dp(388f)

    override fun onDraw(cv: Canvas) {
        if (width == 0) return
        val day = 24 * 3600_000L
        // sport shading
        p.style = Paint.Style.FILL
        ex.forEach { e -> p.color = 0x262EC4B6; cv.drawRect(x(maxOf(e.a, start)), dp(4f), x(minOf(e.b, start + day)), lBot, p) }
        ex.firstOrNull()?.let { e -> txt.color = Ui.ACCENT; cv.drawText("🏃 " + L.s(WatchData.exName(e.type, e.title)), x(maxOf(e.a, start)) + dp(2f), dp(12f), txt); txt.color = Ui.MUTED }

        // glucose
        p.color = 0x1F7DCB6B; cv.drawRect(left, gy(high), right, gy(low), p)
        val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = dp(0.6f) }
        for (v in listOf(50, 100, 150, 200, 250, 300)) { cv.drawLine(left, gy(v), right, gy(v), grid); cv.drawText("$v", dp(2f), gy(v) + dp(3f), txt) }
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f) }
        dash.color = Ui.WARN; cv.drawLine(left, gy(high), right, gy(high), dash)
        dash.color = Ui.BAD; cv.drawLine(left, gy(low), right, gy(low), dash)
        head.color = Ui.TEXT; cv.drawText(L.s("Glucose mg/dL"), left, dp(12f) + (if (ex.isNotEmpty()) 0f else 0f), head)
        if (readings.isNotEmpty()) {
            val path = Path(); var first = true; var lastT = 0L
            for (r in readings.sortedBy { it.ts }) {
                if (!first && r.ts - lastT > 20 * 60_000L) first = true
                if (first) path.moveTo(x(r.ts), gy(r.mg)) else path.lineTo(x(r.ts), gy(r.mg))
                first = false; lastT = r.ts
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2.2f); p.color = Ui.TEXT; cv.drawPath(path, p); p.style = Paint.Style.FILL
        } else cv.drawText(L.s("No glucose readings this day"), left + dp(8f), (gTop + gBot) / 2, txt)
        val mk = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11f); isFakeBoldText = true; color = GraphView.MARK }
        doses.forEach { cv.drawText("I", x(it) - dp(8f), gy(low) + dp(14f), mk) }
        longs.forEach { cv.drawText("LI", x(it) - dp(8f), gy(low) + dp(28f), mk) }
        meals.forEach { cv.drawText("M", x(it) + dp(1f), gy(low) + dp(14f), mk) }

        // steps
        head.color = STEPS; cv.drawText(L.s("Steps / hour"), left, sTop - dp(4f), head)
        val maxS = maxOf(1000, w.steps.maxOrNull() ?: 0)
        cv.drawText(if (maxS >= 1000) "${maxS / 1000}k" else "$maxS", dp(2f), sTop + dp(10f), txt)
        p.color = STEPS
        val bw = (right - left) / 24f
        w.steps.forEachIndexed { h, v -> if (v > 0) { val top = sBot - (sBot - sTop) * v / maxS; cv.drawRoundRect(RectF(left + h * bw + dp(1.5f), top, left + (h + 1) * bw - dp(1.5f), sBot), dp(2f), dp(2f), p) } }
        cv.drawLine(left, sBot, right, sBot, grid)

        // heart rate
        head.color = HR; cv.drawText(L.s("Heart rate bpm"), left, hTop - dp(4f), head)
        for (v in listOf(60, 120, 180)) { cv.drawLine(left, hy(v), right, hy(v), grid); cv.drawText("$v", dp(2f), hy(v) + dp(3f), txt) }
        val hp = Path(); var started = false; var gap = 0
        w.hr.forEachIndexed { k, v ->
            if (v <= 0) { gap++; if (gap > 3) started = false; return@forEachIndexed }
            gap = 0
            val t = start + k * 300_000L + 150_000L
            if (!started) { hp.moveTo(x(t), hy(v)); started = true } else hp.lineTo(x(t), hy(v))
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.6f); p.color = HR; cv.drawPath(hp, p); p.style = Paint.Style.FILL
        if (w.hr.all { it == 0 }) cv.drawText(L.s("no heart rate"), left + dp(8f), (hTop + hBot) / 2, txt)

        // sleep
        head.color = SLEEP; cv.drawText(L.s("Sleep"), left, lTop - dp(4f), head)
        sleep.forEach { s ->
            val a = maxOf(s.a, start); val b = minOf(s.b, start + day); if (b <= a) return@forEach
            val depth = when (s.st) { 5 -> 1f; 4, 2 -> 0.66f; 6 -> 0.5f; else -> 0.3f }
            p.color = STAGE[s.st] ?: SLEEP
            cv.drawRect(x(a), lBot - (lBot - lTop) * depth, maxOf(x(b), x(a) + dp(1f)), lBot, p)
        }
        // hours
        for (h in 0..24 step 3) cv.drawText("%02d".format(h % 24), x(start + h * 3600_000L) - dp(6f), height - dp(4f), txt)

        // crosshair
        if (touchX >= left) {
            val t = tAt(touchX)
            p.color = 0x99ECEEF3.toInt(); p.strokeWidth = dp(1f)
            var yy = gTop; while (yy < lBot) { cv.drawLine(touchX, yy, touchX, yy + dp(4f), p); yy += dp(7f) }
            val r = readings.minByOrNull { kotlin.math.abs(it.ts - t) }?.takeIf { kotlin.math.abs(it.ts - t) < 15 * 60_000L }
            val k = ((t - start) / 300_000L).toInt().coerceIn(0, 287)
            val hr = (k downTo maxOf(0, k - 2)).map { w.hr[it] }.firstOrNull { it > 0 }
            val h = ((t - start) / 3600_000L).toInt().coerceIn(0, 23)
            val after = ex.lastOrNull { it.b <= t && t - it.b < 3 * 3600_000L }
            val during = ex.firstOrNull { t in it.a..it.b }
            val asleep = sleep.firstOrNull { t in it.a until it.b && WatchData.asleep(it) }
            val lines = listOfNotNull(
                hm.format(Date(t)) + (r?.let { " · ${it.mg} mg/dL" } ?: ""),
                hr?.let { L.s("HR {0} bpm".replace("{0}", "$it")) },
                "%,d".format(Locale.US, w.steps[h]) + " " + L.s("steps this hour"),
                when {
                    during != null -> L.s("During sport")
                    after != null -> L.s("{0} min after sport".replace("{0}", "${(t - after.b) / 60_000}"))
                    asleep != null -> L.s("Asleep")
                    else -> null
                })
            r?.let { p.color = if (it.mg < low) Ui.WARN else Ui.TEXT; cv.drawCircle(touchX, gy(it.mg), dp(4f), p) }
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f); color = Ui.TEXT }
            val bw2 = lines.maxOf { tp.measureText(it) } + dp(16f); val bh = lines.size * dp(15f) + dp(10f)
            // beside the line when it fits, otherwise moved inward: always fully on screen
            val bx = (if (touchX + bw2 + dp(8f) < right) touchX + dp(8f) else touchX - bw2 - dp(8f))
                .coerceAtMost(width - bw2 - dp(2f)).coerceAtLeast(dp(2f))
            val by = gTop + dp(4f)
            p.color = 0xEE222836.toInt(); cv.drawRoundRect(RectF(bx, by, bx + bw2, by + bh), dp(8f), dp(8f), p)
            lines.forEachIndexed { i, s -> tp.isFakeBoldText = i == 0; cv.drawText(s, bx + dp(8f), by + dp(17f) + i * dp(15f), tp) }
        }
    }

    private var downX = 0f; private var downY = 0f; private var dragging = false
    private val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop

    /** Sideways drag or a tap moves the crosshair; an up/down swipe still scrolls the page. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(e.x - downX); val dy = kotlin.math.abs(e.y - downY)
                if (!dragging && dx > slop && dx > dy) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                if (dragging) { touchX = e.x.coerceIn(left, right); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && kotlin.math.abs(e.x - downX) < slop && kotlin.math.abs(e.y - downY) < slop) { touchX = e.x.coerceIn(left, right); invalidate() }
                dragging = false; parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    fun showAt(t: Long) { post { touchX = x(t); invalidate() } }
}

/** Small line chart (resting heart rate over the days) with highlighted points. */
class Spark(c: Context) : View(c) {
    var values: List<Int> = emptyList()
    var marks: Set<Int> = emptySet()
    var color = DayChart.HR
    private val d = resources.displayMetrics.density
    override fun onMeasure(wm: Int, hm: Int) { setMeasuredDimension(MeasureSpec.getSize(wm), (56 * d).toInt()) }
    override fun onDraw(cv: Canvas) {
        val v = values.withIndex().filter { it.value > 0 }
        if (v.size < 2) return
        val mn = v.minOf { it.value } - 2; val mx = v.maxOf { it.value } + 2
        fun x(i: Int) = 6 * d + (width - 12 * d) * i / (values.size - 1).coerceAtLeast(1)
        fun y(x0: Int) = height - 6 * d - (height - 12 * d) * (x0 - mn) / (mx - mn).toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = this@Spark.color }
        val path = Path(); v.forEachIndexed { k, iv -> if (k == 0) path.moveTo(x(iv.index), y(iv.value)) else path.lineTo(x(iv.index), y(iv.value)) }
        cv.drawPath(path, p)
        p.style = Paint.Style.FILL; p.color = Incidents.COLOR
        v.filter { it.index in marks }.forEach { cv.drawCircle(x(it.index), y(it.value), 4 * d, p) }
    }
}
