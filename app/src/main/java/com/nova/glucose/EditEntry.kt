package com.nova.glucose

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** ✏️ Edit a saved insulin dose (units, time, wait, note) or meal (total carbs, name, time). Shared with the family. */
object EditEntry {
    private fun label(a: Activity, s: String) = Ui.text(a, s, 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4)) }

    private fun timeRow(a: Activity, root: android.widget.LinearLayout, cal: Calendar) {
        val dateB = Ui.smallButton(a, "") {}; val timeB = Ui.smallButton(a, "") {}
        fun render() {
            dateB.text = "📅  " + SimpleDateFormat("EEE d MMM", Locale.US).format(cal.time)
            timeB.text = "🕘  " + SimpleDateFormat("HH:mm", Locale.US).format(cal.time)
        }
        dateB.setOnClickListener {
            DatePickerDialog(a, { _, y, m, d -> cal.set(y, m, d); render() }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        }
        timeB.setOnClickListener {
            TimePickerDialog(a, { _, h, m -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); render() },
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        val r = Ui.row(a)
        r.addView(dateB, Ui.lp(0, Ui.dp(a, 44), weight = 1f, end = Ui.dp(a, 8))); r.addView(timeB, Ui.lp(0, Ui.dp(a, 44), weight = 1f))
        root.addView(r); render()
    }

    private fun fmt(u: Double) = if (u % 1.0 == 0.0) u.toInt().toString() else u.toString()

    fun dose(a: Activity, id: Long, done: () -> Unit) {
        val db = Db.get(a); val st = Store(a)
        val d = db.dose(id) ?: return
        val cal = Calendar.getInstance().apply { timeInMillis = d.ts }
        val root = Ui.col(a, 18)
        root.addView(label(a, "Date and time").apply { setPadding(0, 0, 0, Ui.dp(a, 4)) })
        timeRow(a, root, cal)
        root.addView(label(a, "Units injected"))
        val units = Ui.input(a, "u", multi = false).apply {
            setText(fmt(d.units)); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        root.addView(units)
        val wait = Ui.input(a, "min", multi = false).apply { setText(if (d.waitMin > 0) d.waitMin.toString() else ""); inputType = InputType.TYPE_CLASS_NUMBER }
        if (d.kind == "rapid") { root.addView(label(a, "Eat after (min)")); root.addView(wait) }
        root.addView(label(a, "Note"))
        val note = Ui.input(a, "Note", multi = false).apply { setText(d.note) }
        root.addView(note)
        val name = if (d.kind == "rapid") st.rapidName.ifBlank { "rapid insulin" } else st.longName.ifBlank { "long-acting insulin" }
        val dlg = AlertDialog.Builder(a).setTitle("✏️ Edit $name").setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = units.text.toString().replace(',', '.').toDoubleOrNull()
                if (u == null || u <= 0 || u > 100) { units.error = "Enter the units injected"; return@setOnClickListener }
                if (cal.timeInMillis > System.currentTimeMillis() + 60_000) { Ui.toast(a, "Time is in the future"); return@setOnClickListener }
                db.updateDose(id, d.copy(ts = cal.timeInMillis, units = u, waitMin = wait.text.toString().toIntOrNull() ?: 0, note = note.text.toString().trim()),
                    System.currentTimeMillis(), share = true)
                Ui.toast(a, "Saved"); dlg.dismiss(); done()
            }
        }
        dlg.show()
    }

    fun meal(a: Activity, id: Long, done: () -> Unit) {
        val db = Db.get(a)
        val m = db.meal(id) ?: return
        val cal = Calendar.getInstance().apply { timeInMillis = m.ts }
        val root = Ui.col(a, 18)
        root.addView(label(a, "Name").apply { setPadding(0, 0, 0, Ui.dp(a, 4)) })
        val title = Ui.input(a, "Meal name", multi = false).apply { setText(m.title) }
        root.addView(title)
        root.addView(label(a, "Date and time"))
        timeRow(a, root, cal)
        root.addView(label(a, "Total carbs (g)"))
        val carbs = Ui.input(a, "g", multi = false).apply { setText(m.carbs.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(carbs)
        db.ai(id)?.let { (_, ai) -> root.addView(Ui.text(a, "AI said $ai g — your number is kept as the family's count.", 12f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(a, 6), 0, 0) }) }
        val dlg = AlertDialog.Builder(a).setTitle("✏️ Edit meal").setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = carbs.text.toString().toIntOrNull()
                if (c == null || c < 0 || c > 500) { carbs.error = "Enter the total carbs"; return@setOnClickListener }
                if (cal.timeInMillis > System.currentTimeMillis() + 60_000) { Ui.toast(a, "Time is in the future"); return@setOnClickListener }
                val t = title.text.toString().trim().ifBlank { m.title }
                db.updateMeal(id, m.copy(ts = cal.timeInMillis, title = t, carbs = c, low = if (c != m.carbs) c else m.low, high = if (c != m.carbs) c else m.high),
                    System.currentTimeMillis(), share = true)
                Ui.toast(a, "Saved"); dlg.dismiss(); done()
            }
        }
        dlg.show()
    }

    @Suppress("unused") private val keep = TextView::class
}
