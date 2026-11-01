package com.nova.glucose

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs the insulin the family gives (this app never calculates or suggests a dose) and starts the
 * "time to eat" countdown after rapid insulin.
 */
class InsulinActivity : BaseActivity() {
    private lateinit var st: Store
    private lateinit var units: EditText
    private lateinit var carbs: EditText
    private lateinit var wait: EditText
    private lateinit var timer: TextView
    private lateinit var history: LinearLayout
    private lateinit var refBox: TextView
    private var calcNote = ""
    private val ui = Handler(Looper.getMainLooper())
    private val hm get() = SimpleDateFormat("EEE HH:mm", L.loc())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        val root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "💉 Insulin"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)

        val now = if (System.currentTimeMillis() - st.lastTs < 15 * 60_000) "Glucose now ${st.lastMg} ${Libre.trendArrow(st.lastTrend)} mg/dL" else "No recent reading"
        root.addView(Ui.text(this, now, 14f, Ui.MUTED))

        timer = Ui.text(this, "", 17f, Ui.GOOD, true).apply {
            background = Ui.round(0x223DDC97, Ui.dp(context, 12).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        root.addView(timer, Ui.lp(top = Ui.dp(this, 10)))

        // ---------------------------------------------------------- rapid
        val rc = Ui.card(this)
        rc.addView(Ui.text(this, "Rapid insulin before meal", 17f, Ui.TEXT, true))
        rc.addView(Ui.text(this, st.rapidName.ifBlank { "Insulin name not set — Settings › Profile and insulin" }, 13f, Ui.MUTED))

        carbs = num("Meal carbs (g)")
        recentMealCarbs()?.let { carbs.setText(it.toString()) }
        rc.addView(label("Meal carbs (g) — from the last photo estimate, edit if needed")); rc.addView(carbs)

        // Split row: units injected (left) · reference box (right, no units calculated)
        val split = Ui.row(this).apply { gravity = android.view.Gravity.TOP; setPadding(0, Ui.dp(context, 12), 0, 0) }
        val left = Ui.col(this)
        left.addView(Ui.text(this, "Units injected", 13f, Ui.MUTED).apply { setPadding(0, 0, 0, Ui.dp(context, 4)) })
        val ur = Ui.row(this)
        units = num("– u", decimal = true).apply {
            textSize = 24f; gravity = android.view.Gravity.CENTER
            background = Ui.round(Ui.CARD2, Ui.dp(context, 14).toFloat(), Ui.dp(context, 2), Ui.ACCENT)
        }
        ur.addView(units, Ui.lp(0, Ui.dp(this, 64), weight = 1f, end = Ui.dp(this, 6)))
        ur.addView(Ui.smallButton(this, "🧮") { openCalc() }, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 64)))
        left.addView(ur)
        left.addView(Ui.text(this, "What you actually gave", 11f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 4), 0, 0) })
        split.addView(left, Ui.lp(0, weight = 1f, end = Ui.dp(this, 10)))
        refBox = Ui.text(this, "", 12f, Ui.TEXT).apply {
            background = Ui.round(Ui.CARD2, Ui.dp(context, 12).toFloat())
            setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
            setLineSpacing(0f, 1.25f)
        }
        split.addView(refBox, Ui.lp(0, weight = 1.25f))
        rc.addView(split)
        carbs.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { renderRef() }
        })
        renderRef()

        // Wait + save on one row
        val wr = Ui.row(this).apply { gravity = android.view.Gravity.BOTTOM; setPadding(0, Ui.dp(context, 14), 0, 0) }
        val wc = Ui.col(this)
        wc.addView(Ui.text(this, "Wait before eating (min)", 12f, Ui.MUTED).apply { setPadding(0, 0, 0, Ui.dp(context, 4)) })
        wait = num("min").apply { gravity = android.view.Gravity.CENTER }
        if (st.eatWait > 0) wait.setText(st.eatWait.toString())
        wc.addView(wait)
        wr.addView(wc, Ui.lp(0, weight = 1f, end = Ui.dp(this, 10)))
        wr.addView(Ui.button(this, "💾 Save & start eat timer", true) { saveRapid() }, Ui.lp(0, weight = 1.4f))
        rc.addView(wr)
        rc.addView(Ui.text(this, "Records doses only. It never calculates or suggests insulin units.", 12f, Ui.WARN).apply {
            setPadding(0, Ui.dp(context, 8), 0, 0) })
        root.addView(rc, Ui.lp(top = Ui.dp(this, 12)))

        // ---------------------------------------------------------- long-acting
        val lc = Ui.card(this)
        lc.addView(Ui.text(this, "Long-acting insulin", 17f, Ui.TEXT, true))
        lc.addView(Ui.text(this, listOf(st.longName.ifBlank { "Name not set" },
            st.longDose.takeIf { it.isNotBlank() }?.let { "prescribed $it u" },
            st.longTime.takeIf { it.isNotBlank() }?.let { "at $it" }).filterNotNull().joinToString(" · "), 13f, Ui.MUTED))
        val lr = Ui.row(this).apply { gravity = android.view.Gravity.BOTTOM; setPadding(0, Ui.dp(context, 10), 0, 0) }
        val lcol = Ui.col(this)
        lcol.addView(Ui.text(this, "Units given", 12f, Ui.MUTED).apply { setPadding(0, 0, 0, Ui.dp(context, 4)) })
        val lu = num("u", decimal = true).apply { setText(st.longDose); gravity = android.view.Gravity.CENTER }
        lcol.addView(lu)
        lr.addView(lcol, Ui.lp(0, weight = 1f, end = Ui.dp(this, 10)))
        lr.addView(Ui.button(this, "💾 Log long-acting dose") {
            val u = lu.text.toString().replace(',', '.').toDoubleOrNull()
            if (u == null || u <= 0 || u > 100) { Ui.toast(this, "Enter the units given (numbers only)"); return@button }
            Db.get(this).addDose(Dose(0, System.currentTimeMillis(), "long", u, mgAt = mgNow()))
            exportLater(); Ui.toast(this, "Long-acting dose logged"); renderHistory()
        }, Ui.lp(0, weight = 1.4f))
        lc.addView(lr)
        root.addView(lc, Ui.lp(top = Ui.dp(this, 12)))

        root.addView(Ui.section(this, "Recent doses — tap to edit · long-press to delete"))
        history = Ui.col(this)
        root.addView(history)
        root.addView(Ui.signature(this))
        setContentView(sv)
        renderHistory()
    }

    override fun onResume() { super.onResume(); ui.post(tick); if (::refBox.isInitialized) renderRef() }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    private val tick = object : Runnable {
        override fun run() {
            val left = st.eatAt - System.currentTimeMillis()
            if (st.eatAt == 0L || left <= 0) { timer.visibility = View.GONE }
            else {
                timer.visibility = View.VISIBLE
                timer.text = "⏱ Eat in %d:%02d — you'll get a notification".format(left / 60_000, (left / 1000) % 60)
                timer.setOnClickListener {
                    Tr.Builder(this@InsulinActivity).setMessage("Cancel the eat timer?")
                        .setPositiveButton("Cancel timer") { _, _ -> EatTimer.cancel(this@InsulinActivity) }
                        .setNegativeButton("Keep", null).show()
                }
            }
            ui.postDelayed(this, 1000)
        }
    }

    /** Rapid insulin still active (last 4 hours). */
    private fun active(): List<Dose> = Db.get(this).doses(System.currentTimeMillis() - 4 * 3600_000L).filter { it.kind == "rapid" }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).toInt()
        return if (m < 60) "$m min ago" else "${m / 60} h ${m % 60} min ago"
    }

    /** Reference box: prescription and glucose against target. Never a number of units. */
    private fun refLines(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        val c = carbs.text.toString().toIntOrNull()
        out += "Reference (as prescribed)" to Ui.MUTED
        out += ("Carbs ${c?.let { "$it g" } ?: "–"}" + if (st.carbRatio > 0) " · ratio 1 u / ${st.carbRatio} g" else " · ratio not set") to Ui.TEXT
        val mg = mgNow()
        if (mg > 0) {
            val d = mg - st.targetMg
            out += ("Glucose $mg → " + when {
                d > 0 -> "$d above target ${st.targetMg}"
                d < 0 -> "${-d} below target ${st.targetMg}"
                else -> "at target ${st.targetMg}"
            }) to (if (d > 0) Ui.WARN else if (d < 0) Ui.BAD else Ui.GOOD)
        } else out += "Glucose: no recent reading (target ${st.targetMg})" to Ui.MUTED
        if (st.corrFactor > 0) out += "Correction 1 u / ${st.corrFactor} mg/dL" to Ui.TEXT
        val act = active()
        out += (if (act.isEmpty()) "✓ No rapid insulin in last 4 h"
                else "⚠ Active: " + act.joinToString(", ") { "${fmt(it.units)} u ${ago(it.ts)}" }) to (if (act.isEmpty()) Ui.GOOD else Ui.WARN)
        return out
    }

    private fun renderRef() {
        val sb = android.text.SpannableStringBuilder()
        refLines().forEachIndexed { k, (t, col) ->
            if (k > 0) sb.append("\n")
            val s0 = sb.length; sb.append(L.s(t))
            sb.setSpan(android.text.style.ForegroundColorSpan(col), s0, sb.length, 0)
            if (k == 0) sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s0, sb.length, 0)
        }
        refBox.text = sb
    }

    private fun openCalc() {
        Calc.show(this, refLines().drop(1).joinToString(" · ") { it.first }) { v, typed ->
            units.setText(Calc.fmt(v)); calcNote = "calc: $typed = ${Calc.fmt(v)}"
        }
    }

    private fun label(s: String) = Ui.text(this, s, 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4)) }

    private fun num(hint: String, decimal: Boolean = false) = Ui.input(this, hint, multi = false).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
    }

    private fun mgNow() = if (System.currentTimeMillis() - st.lastTs < 15 * 60_000) st.lastMg else 0

    private fun recentMealCarbs(): Int? =
        Db.get(this).meals(1).firstOrNull()?.takeIf { System.currentTimeMillis() - it.ts < 45 * 60_000 }?.carbs

    private fun saveRapid() {
        val u = units.text.toString().replace(',', '.').toDoubleOrNull()
        if (u == null || u <= 0 || u > 50) { Ui.toast(this, "Enter the units given"); return }
        val w = wait.text.toString().toIntOrNull()
        if (w == null || w < 0 || w > 90) { Ui.toast(this, "Enter the wait before eating (0–90 min)"); return }
        val c = carbs.text.toString().toIntOrNull() ?: 0
        val now = System.currentTimeMillis()
        val note = calcNote.takeIf { it.endsWith("= ${Calc.fmt(u)}") } ?: ""
        Db.get(this).addDose(Dose(0, now, "rapid", u, c, w, mgNow(), note))
        calcNote = ""; units.setText(""); renderRef()
        if (st.eatWait == 0 || st.eatWait != w) {
            Tr.Builder(this).setMessage("Use $w minutes as the usual wait before eating?")
                .setPositiveButton("Yes, remember") { _, _ -> st.eatWait = w }
                .setNegativeButton("Only this time", null).show()
        }
        if (w > 0) { EatTimer.schedule(this, now + w * 60_000L); Ui.toast(this, "Saved · eat timer $w min") }
        else Ui.toast(this, "Saved")
        exportLater(); renderHistory()
    }

    private fun exportLater() { val app = applicationContext; Thread { Vault.exportAll(app, pdfNow = true) }.start() }

    private fun renderHistory() {
        history.removeAllViews()
        val list = Db.get(this).doses(System.currentTimeMillis() - 7 * 24 * 3600_000L).take(30)
        if (list.isEmpty()) { history.addView(Ui.text(this, "No doses logged yet.", 14f, Ui.MUTED)); return }
        list.forEach { d ->
            val row = Ui.row(this).apply {
                background = Ui.round(Ui.CARD, Ui.dp(context, 12).toFloat())
                setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
            }
            val name = if (d.kind == "long") st.longName.ifBlank { "Long-acting" } else st.rapidName.ifBlank { "Rapid" }
            val extra = listOfNotNull(
                d.carbs.takeIf { it > 0 }?.let { "$it g carbs" },
                d.waitMin.takeIf { d.kind == "rapid" && it > 0 }?.let { "wait $it min" },
                d.mgAt.takeIf { it > 0 }?.let { "glucose $it" },
                d.note.takeIf { it.startsWith("calc:") }?.removePrefix("calc: ")).joinToString(" · ")
            val col = Ui.col(this)
            col.addView(Ui.text(this, "${hm.format(Date(d.ts))} · $name", 13f, Ui.MUTED))
            if (extra.isNotBlank()) col.addView(Ui.text(this, extra, 13f, Ui.TEXT))
            row.addView(col, Ui.lp(0, weight = 1f))
            row.addView(Ui.text(this, "${fmt(d.units)} u", 17f, Ui.ACCENT, true))
            row.setOnClickListener { EditEntry.dose(this, d.id) { renderHistory(); exportLater() } }
            row.setOnLongClickListener {
                Parent.guard(this, "Enter the PIN to delete a dose.") {
                    Tr.Builder(this).setMessage("Delete this dose?")
                        .setPositiveButton("Delete") { _, _ -> Db.get(this).deleteDose(d.id); renderHistory(); exportLater() }
                        .setNegativeButton("Cancel", null).show()
                }; true
            }
            history.addView(row, Ui.lp(top = Ui.dp(this, 6)))
        }
    }

    private fun fmt(u: Double) = if (u == Math.floor(u)) u.toInt().toString() else "%.1f".format(Locale.US, u)
}
