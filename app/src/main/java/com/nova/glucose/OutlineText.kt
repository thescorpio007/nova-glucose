package com.nova.glucose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView

/** White text with a black outline, readable on the strong glucose colours. */
class OutlineText(c: Context) : TextView(c) {
    var outline = 0f          // px; 0 = normal text
    private var drawing = false

    override fun onDraw(cv: Canvas) {
        if (outline <= 0f) { super.onDraw(cv); return }
        val fill = currentTextColor
        drawing = true
        paint.style = Paint.Style.STROKE; paint.strokeWidth = outline; paint.strokeJoin = Paint.Join.ROUND
        setTextColor(0xFF000000.toInt()); super.onDraw(cv)
        paint.style = Paint.Style.FILL
        setTextColor(fill); super.onDraw(cv)
        drawing = false
    }

    // changing the colour inside onDraw must not trigger another layout pass
    override fun invalidate() { if (!drawing) super.invalidate() }
    override fun requestLayout() { if (!drawing) super.requestLayout() }
}
