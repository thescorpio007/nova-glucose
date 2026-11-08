package com.nova.glucose

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * [Meal] [Correction] beside the units: red = what will be saved. Meal (I + M) by default; Correction (I only)
 * when tapped, or when the carbs are 0 / 00 / 000, or the note says "correction". Tapping Correction puts 0 in the carbs.
 */
class MealToggle(private val c: Context, private val carbs: EditText) {
    val view: LinearLayout = Ui.row(c).apply { gravity = Gravity.CENTER_VERTICAL }
    private var note: EditText? = null
    private val meal = pill("Meal"); private val corr = pill("Correction")
    private var busy = false

    init {
        view.addView(meal, Ui.lp(-2, Ui.dp(c, 44), end = Ui.dp(c, 6)))
        view.addView(corr, Ui.lp(-2, Ui.dp(c, 44)))
        meal.setOnClickListener { busy = true
            if (Carbs.parse(carbs.text.toString()) == Carbs.CORRECTION) carbs.setText("")
            note?.let { n -> if (Carbs.saysCorrection(n.text.toString()))
                n.setText(n.text.toString().replace(Regex("(?i)correction|تصحيح"), "").trim()) }
            busy = false; refresh() }
        corr.setOnClickListener { busy = true; carbs.setText("0"); busy = false; refresh() }
        carbs.addTextChangedListener(watch())
        refresh()
    }

    fun attachNote(n: EditText) { note = n; n.addTextChangedListener(watch()); refresh() }

    val isCorrection get() = Carbs.parse(carbs.text.toString(), note?.text?.toString() ?: "") == Carbs.CORRECTION

    private fun watch() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: Editable?) { if (!busy) refresh() }
    }

    private fun pill(t: String) = TextView(c).apply {
        text = L.s(t); textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0); isClickable = true
    }

    fun refresh() {
        val k = isCorrection
        style(meal, !k); style(corr, k)
    }

    private fun style(v: TextView, on: Boolean) {
        v.background = if (on) Ui.round(RED, Ui.dp(c, 22).toFloat()) else Ui.round(Ui.CARD2, Ui.dp(c, 22).toFloat(), Ui.dp(c, 1), 0xFF5A5F6E.toInt())
        v.setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.MUTED)
    }

    companion object { const val RED = 0xFFE53935.toInt() }
}
