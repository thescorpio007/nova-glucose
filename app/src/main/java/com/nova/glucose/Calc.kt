package com.nova.glucose

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import java.util.Locale

/**
 * A plain calculator (digits, + − × ÷, brackets). The family types every number themselves:
 * nothing is pre-filled and nothing is suggested.
 */
object Calc {
    /** Evaluates "60÷12+(194−100)÷50"; null when incomplete. */
    fun eval(src: String): Double? {
        val s = src.replace('×', '*').replace('÷', '/').replace('−', '-').replace(',', '.').replace(" ", "")
        if (s.isEmpty()) return null
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        lateinit var expr: () -> Double
        fun factor(): Double {
            if (peek() == '-') { i++; return -factor() }
            if (peek() == '(') {
                i++; val v = expr(); if (peek() != ')') throw IllegalStateException(); i++; return v
            }
            val start = i
            while (peek().isDigit() || peek() == '.') i++
            if (start == i) throw IllegalStateException()
            return s.substring(start, i).toDouble()
        }
        fun term(): Double {
            var v = factor()
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]; val r = factor()
                v = if (op == '*') v * r else { if (r == 0.0) throw IllegalStateException(); v / r }
            }
            return v
        }
        expr = {
            var v = term()
            while (peek() == '+' || peek() == '-') { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            v
        }
        return try { val v = expr(); if (i != s.length || v.isNaN() || v.isInfinite()) null else v } catch (_: Exception) { null }
    }

    fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", Math.round(v * 10) / 10.0)

    /** Shows the calculator; [onUse] gets (result, what was typed). */
    fun show(a: Activity, reference: String, onUse: (Double, String) -> Unit) {
        var ex = ""
        val root = Ui.col(a, 16)
        root.addView(Ui.text(a, reference, 12f, Ui.MUTED).apply {
            background = Ui.round(Ui.CARD2, Ui.dp(a, 10).toFloat())
            setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8))
        })
        val exV = Ui.text(a, "", 17f, Ui.MUTED).apply { gravity = Gravity.END; setPadding(0, Ui.dp(a, 10), 0, 0) }
        val res = Ui.text(a, "0", 32f, Ui.TEXT, true).apply { gravity = Gravity.END }
        root.addView(exV); root.addView(res)
        fun refresh() {
            exV.text = ex
            res.text = if (ex.isEmpty()) "0" else eval(ex)?.let { fmt(it) } ?: "…"
        }
        val keys = listOf("7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "−", "0", ".", "C", "+", "(", ")", "⌫", "=")
        val grid = GridLayout(a).apply { columnCount = 4 }
        keys.forEach { k ->
            val b: TextView = Ui.smallButton(a, k) {
                when (k) {
                    "C" -> ex = ""
                    "⌫" -> ex = ex.dropLast(1)
                    "=" -> eval(ex)?.let { ex = fmt(it) }
                    else -> ex += k
                }
                refresh()
            }.apply { textSize = 20f; if (k == "=") background = Ui.round(Ui.ACCENT, Ui.dp(a, 10).toFloat()) }
            grid.addView(b, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = Ui.dp(a, 52); setMargins(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3))
            })
        }
        root.addView(grid, Ui.lp(top = Ui.dp(a, 8)))
        root.addView(Ui.text(a, "Plain calculator: you type every number. Nothing is pre-filled or suggested.", 11f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(a, 8), 0, 0) })
        val d = Tr.Builder(a).setTitle("🧮 Calculator").setView(android.widget.ScrollView(a).apply { addView(root) })
            .setPositiveButton("Put in Units injected", null).setNegativeButton("Close", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = eval(ex)
                if (v == null || v <= 0 || v > 50) { Ui.toast(a, "Finish the calculation first (result 0–50)"); return@setOnClickListener }
                onUse(Math.round(v * 10) / 10.0, ex); d.dismiss()
            }
        }
        d.show()
    }
}
