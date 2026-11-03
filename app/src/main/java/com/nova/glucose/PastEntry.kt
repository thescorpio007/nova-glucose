package com.nova.glucose

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** "Add earlier injection or meal": something that already happened, at its real time. */
object PastEntry {
    fun show(a: Activity, done: () -> Unit) {
        val st = Store(a)
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, -30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        var kind = "rapid"
        val root = Ui.col(a, 18)
        val chips = Ui.row(a)
        root.addView(chips)
        val dateB = Ui.smallButton(a, "") {}
        val timeB = Ui.smallButton(a, "") {}
        fun renderTime() {
            val today = Calendar.getInstance()
            val sameDay = today.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
            dateB.text = "📅  " + if (sameDay) "Today" else SimpleDateFormat("EEE d MMM", L.loc()).format(cal.time)
            timeB.text = "🕘  " + SimpleDateFormat("HH:mm", L.loc()).format(cal.time)
        }
        dateB.setOnClickListener {
            DatePickerDialog(a, { _, y, m, d -> cal.set(y, m, d); renderTime() },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        timeB.setOnClickListener {
            TimePickerDialog(a, { _, h, m -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); renderTime() },
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        val tr = Ui.row(a).apply { setPadding(0, Ui.dp(a, 12), 0, 0) }
        tr.addView(dateB, Ui.lp(0, Ui.dp(a, 44), weight = 1f, end = Ui.dp(a, 8)))
        tr.addView(timeB, Ui.lp(0, Ui.dp(a, 44), weight = 1f))
        root.addView(tr)
        renderTime()

        fun num(hint: String, dec: Boolean) = Ui.input(a, hint, multi = false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or if (dec) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
        }
        val unitsL = Ui.text(a, "Units injected", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4)) }
        val units = num("u", true)
        val carbs = num("g (approx)", false)
        val note = Ui.input(a, "Note, e.g. school lunch", multi = false)
        root.addView(unitsL); root.addView(units)
        root.addView(Ui.text(a, "Carbs eaten (approx)", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4)) })
        root.addView(carbs)
        root.addView(Ui.text(a, "Note (optional)", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4)) })
        root.addView(note)
        root.addView(Ui.text(a, "The glucose at that time is filled in from the readings.", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(a, 10), 0, 0) })

        fun renderChips() {
            chips.removeAllViews()
            listOf("rapid" to "Rapid", "long" to "Long-acting", "meal" to "Meal only").forEach { (id, l) ->
                chips.addView(Ui.chip(a, l, kind == id) { kind = id; renderChips() }, Ui.lp(-2, -2, end = Ui.dp(a, 8)))
            }
            val ins = kind != "meal"
            unitsL.visibility = if (ins) android.view.View.VISIBLE else android.view.View.GONE
            units.visibility = unitsL.visibility
            if (kind == "long" && units.text.isBlank()) units.setText(st.longDose)
        }
        renderChips()

        val d = Tr.Builder(a).setTitle("🕘 Add earlier injection or meal")
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("Save to logbook", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ts = cal.timeInMillis
                if (ts > System.currentTimeMillis() + 60_000) { Ui.toast(a, "Time is in the future"); return@setOnClickListener }
                val u = units.text.toString().replace(',', '.').toDoubleOrNull()
                val c = carbs.text.toString().toIntOrNull() ?: 0
                val n = note.text.toString().trim()
                if (kind != "meal" && (u == null || u <= 0 || u > 100)) { units.error = "Enter the units injected"; return@setOnClickListener }
                if (kind == "meal" && c <= 0) { carbs.error = "Enter the carbs eaten"; return@setOnClickListener }
                val db = Db.get(a)
                val mg = db.near(ts)?.mg ?: 0
                if (kind != "meal") db.addDose(Dose(0, ts, kind, u!!, if (kind == "rapid") c else 0, 0, mg, "entered later" + if (n.isNotBlank()) ": $n" else ""))
                if (c > 0 && kind != "long") {
                    val title = n.ifBlank { "Meal (entered later)" }
                    db.addMeal(Meal(0, ts, title, JSONArray().put(JSONObject().put("name", title).put("portion", "").put("carbs", c)).toString(),
                        c, c, c, mg, "", "entered later"))
                }
                val app = a.applicationContext
                Thread { Vault.exportAll(app, pdfNow = true) }.start()
                Ui.toast(a, "Saved at " + SimpleDateFormat("HH:mm", L.loc()).format(cal.time))
                d.dismiss(); done()
            }
        }
        d.show()
    }

    @Suppress("unused") private val keep = LinearLayout::class
}
