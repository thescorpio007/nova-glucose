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
    private val ui = Handler(Looper.getMainLooper())
    private val hm get() = SimpleDateFormat("EEE HH:mm", Locale.US)

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
        rc.addView(Ui.text(this, "Insulin: ${st.rapidName.ifBlank { "not set — add it in Settings › Profile and insulin" }}", 13f, Ui.MUTED))
        if (st.carbRatio > 0) rc.addView(Ui.text(this, "Prescribed ratio (reference): 1 unit per ${st.carbRatio} g", 13f, Ui.MUTED))
        units = num("Units given", decimal = true)
        rc.addView(label("Units given (as you decided)")); rc.addView(units)
        carbs = num("Meal carbs (g) — optional")
        recentMealCarbs()?.let { carbs.setText(it.toString()) }
        rc.addView(label("Meal carbs (g)")); rc.addView(carbs)
        wait = num("Minutes")
        if (st.eatWait > 0) wait.setText(st.eatWait.toString())
        rc.addView(label("Wait before eating (minutes) — as her diabetes team advised")); rc.addView(wait)
        rc.addView(Ui.button(this, "💾 Save & start eat timer", true) { saveRapid() }, Ui.lp(top = Ui.dp(this, 12)))
        rc.addView(Ui.text(this, "This app records doses only — it never calculates or suggests insulin.", 12f, Ui.WARN).apply {
            setPadding(0, Ui.dp(context, 8), 0, 0) })
        root.addView(rc, Ui.lp(top = Ui.dp(this, 12)))

        // ---------------------------------------------------------- long-acting
        val lc = Ui.card(this)
        lc.addView(Ui.text(this, "Long-acting insulin", 17f, Ui.TEXT, true))
        lc.addView(Ui.text(this, listOf(st.longName.ifBlank { "Name not set" },
            st.longDose.takeIf { it.isNotBlank() }?.let { "prescribed $it u" },
            st.longTime.takeIf { it.isNotBlank() }?.let { "at $it" }).filterNotNull().joinToString(" · "), 13f, Ui.MUTED))
        val lu = num("Units given", decimal = true).apply { setText(st.longDose) }
        lc.addView(label("Units given")); lc.addView(lu)
        lc.addView(Ui.button(this, "💾 Log long-acting dose") {
            val u = lu.text.toString().replace(',', '.').toDoubleOrNull()
            if (u == null || u <= 0 || u > 100) { Ui.toast(this, "Enter the units given"); return@button }
            Db.get(this).addDose(Dose(0, System.currentTimeMillis(), "long", u, mgAt = mgNow()))
            exportLater(); Ui.toast(this, "Long-acting dose logged"); renderHistory()
        }, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(lc, Ui.lp(top = Ui.dp(this, 12)))

        root.addView(Ui.section(this, "Recent doses — long-press to delete"))
        history = Ui.col(this)
        root.addView(history)
        root.addView(Ui.signature(this))
        setContentView(sv)
        renderHistory()
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    private val tick = object : Runnable {
        override fun run() {
            val left = st.eatAt - System.currentTimeMillis()
            if (st.eatAt == 0L || left <= 0) { timer.visibility = View.GONE }
            else {
                timer.visibility = View.VISIBLE
                timer.text = "⏱ Eat in %d:%02d — you'll get a notification".format(left / 60_000, (left / 1000) % 60)
                timer.setOnClickListener {
                    AlertDialog.Builder(this@InsulinActivity).setMessage("Cancel the eat timer?")
                        .setPositiveButton("Cancel timer") { _, _ -> EatTimer.cancel(this@InsulinActivity) }
                        .setNegativeButton("Keep", null).show()
                }
            }
            ui.postDelayed(this, 1000)
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
        Db.get(this).addDose(Dose(0, now, "rapid", u, c, w, mgNow()))
        if (st.eatWait == 0 || st.eatWait != w) {
            AlertDialog.Builder(this).setMessage("Use $w minutes as the usual wait before eating?")
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
                d.mgAt.takeIf { it > 0 }?.let { "glucose $it" }).joinToString(" · ")
            val col = Ui.col(this)
            col.addView(Ui.text(this, "${hm.format(Date(d.ts))} · $name", 13f, Ui.MUTED))
            if (extra.isNotBlank()) col.addView(Ui.text(this, extra, 13f, Ui.TEXT))
            row.addView(col, Ui.lp(0, weight = 1f))
            row.addView(Ui.text(this, "${fmt(d.units)} u", 17f, Ui.ACCENT, true))
            row.setOnLongClickListener {
                Parent.guard(this, "Enter the PIN to delete a dose.") {
                    AlertDialog.Builder(this).setMessage("Delete this dose?")
                        .setPositiveButton("Delete") { _, _ -> Db.get(this).deleteDose(d.id); renderHistory(); exportLater() }
                        .setNegativeButton("Cancel", null).show()
                }; true
            }
            history.addView(row, Ui.lp(top = Ui.dp(this, 6)))
        }
    }

    private fun fmt(u: Double) = if (u == Math.floor(u)) u.toInt().toString() else "%.1f".format(Locale.US, u)
}
