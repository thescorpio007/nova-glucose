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
            dateB.text = "📅  " + SimpleDateFormat("EEE d MMM", L.loc()).format(cal.time)
            timeB.text = "🕘  " + SimpleDateFormat("HH:mm", L.loc()).format(cal.time)
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
        val dlg = Tr.Builder(a).setTitle("✏️ Edit $name").setView(ScrollView(a).apply { addView(root) })
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
        val carbs = Ui.input(a, "g", multi = false).apply { setText(if (m.known) m.carbs.toString() else ""); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(carbs)
        db.ai(id)?.let { (_, ai) -> root.addView(Ui.text(a, "AI said $ai g — your number is kept as the family's count.", 12f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(a, 6), 0, 0) }) }
        val dlg = Tr.Builder(a).setTitle("✏️ Edit meal").setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = Carbs.parse(carbs.text.toString())?.takeIf { it != Carbs.CORRECTION }
                if (c == null) { carbs.error = L.s("Grams 1–500, or empty if not known"); return@setOnClickListener }
                if (cal.timeInMillis > System.currentTimeMillis() + 60_000) { Ui.toast(a, "Time is in the future"); return@setOnClickListener }
                val t = title.text.toString().trim().ifBlank { m.title }
                db.updateMeal(id, m.copy(ts = cal.timeInMillis, title = t, carbs = c, low = if (c != m.carbs) c else m.low, high = if (c != m.carbs) c else m.high),
                    System.currentTimeMillis(), share = true)
                Ui.toast(a, "Saved"); dlg.dismiss(); done()
            }
        }
        dlg.show()
    }

    private const val NEAR = 60 * 60_000L

    /** The meal that goes with a rapid dose (nearest within an hour), and the reverse. */
    fun mealFor(db: Db, d: Dose): Meal? = db.meals(400).filter { kotlin.math.abs(it.ts - d.ts) <= NEAR }.minByOrNull { kotlin.math.abs(it.ts - d.ts) }
    fun doseFor(db: Db, m: Meal): Dose? = db.doses(m.ts - NEAR, m.ts + NEAR).filter { it.kind == "rapid" }.minByOrNull { kotlin.math.abs(it.ts - m.ts) }

    /** Opens the right editor: long-acting → its own; rapid insulin or a meal → insulin and meal together. */
    fun open(a: Activity, doseId: Long?, mealId: Long?, done: () -> Unit) {
        val db = Db.get(a)
        val d = doseId?.let { db.dose(it) }
        if (d != null && d.kind != "rapid") { dose(a, d.id, done); return }
        combined(a, d, mealId?.let { db.meal(it) }, done)
    }

    /** ✏️ Edit insulin + meal: units, carbs, meal name, time, eat after, note — saved together. */
    fun combined(a: Activity, d0: Dose?, m0: Meal?, done: () -> Unit) {
        val db = Db.get(a); val st = Store(a)
        val d = d0 ?: m0?.let { doseFor(db, it) }
        val m = m0 ?: d?.let { mealFor(db, it) }
        if (d == null && m == null) return
        val anchor = d?.ts ?: m!!.ts
        val cal = Calendar.getInstance().apply { timeInMillis = anchor }
        val root = Ui.col(a, 18)
        root.addView(label(a, "Date and time").apply { setPadding(0, 0, 0, Ui.dp(a, 4)) })
        timeRow(a, root, cal)
        root.addView(label(a, (st.rapidName.ifBlank { L.s("Rapid insulin") }) + " — " + L.s("units injected")))
        val units = Ui.input(a, if (d == null) "u (empty = no insulin)" else "u", multi = false).apply {
            setText(d?.let { fmt(it.units) } ?: ""); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        root.addView(units)
        root.addView(label(a, "Carbs eaten (g)"))
        val carbs = Ui.input(a, "g", multi = false).apply {
            setText(when { m != null -> if (m.known) m.carbs.toString() else ""; d!!.carbs > 0 -> d.carbs.toString(); d.carbs == Carbs.UNKNOWN -> ""; else -> "0" })
            inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(carbs)
        root.addView(Ui.text(a, "0 = correction only, no meal · empty = meal eaten, carbs not recorded · or write \"correction\" in the note", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 4), 0, 0) })
        m?.let { mm -> db.ai(mm.id)?.let { (_, ai) -> root.addView(Ui.text(a, "AI said $ai g — your number is kept as the family's count.", 12f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(a, 4), 0, 0) }) } }
        val mealBox = Ui.col(a)
        mealBox.addView(label(a, "Meal name"))
        val title = Ui.input(a, "Meal name", multi = false).apply { setText(m?.title ?: "") }
        mealBox.addView(title)
        mealBox.addView(label(a, "Eat after (min)"))
        val wait = Ui.input(a, "min", multi = false).apply { setText(d?.waitMin?.takeIf { it > 0 }?.toString() ?: ""); inputType = InputType.TYPE_CLASS_NUMBER }
        mealBox.addView(wait)
        root.addView(mealBox)
        val corrNote = Ui.text(a, "Correction only: no meal, \"eat after\" not applicable." + if (m != null) " " + L.s("The linked meal will be removed.") else "", 12f, Ui.WARN)
            .apply { setPadding(0, Ui.dp(a, 10), 0, 0) }
        root.addView(corrNote)
        root.addView(label(a, "Note"))
        val note = Ui.input(a, "Note", multi = false).apply { setText(d?.note ?: m?.note ?: "") }
        root.addView(note)
        fun toggle() {
            val corr = Carbs.parse(carbs.text.toString(), note.text.toString()) == Carbs.CORRECTION
            mealBox.visibility = if (corr) android.view.View.GONE else android.view.View.VISIBLE
            corrNote.visibility = if (corr) android.view.View.VISIBLE else android.view.View.GONE
        }
        val tw = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { toggle() }
        }
        carbs.addTextChangedListener(tw); note.addTextChangedListener(tw)
        toggle()
        val dlg = Tr.Builder(a).setTitle("✏️ Edit insulin + meal").setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ut = units.text.toString().replace(',', '.').trim()
                val u = ut.toDoubleOrNull()
                if (ut.isNotEmpty() && (u == null || u <= 0 || u > 100)) { units.error = L.s("Enter the units injected"); return@setOnClickListener }
                if (d != null && u == null) { units.error = L.s("Enter the units injected"); return@setOnClickListener }
                val c = Carbs.parse(carbs.text.toString(), note.text.toString())
                if (c == null) { carbs.error = L.s("Grams 1–500, 0 for a correction, or empty if not known"); return@setOnClickListener }
                if (c == Carbs.CORRECTION && u == null) { units.error = L.s("A correction needs the units injected"); return@setOnClickListener }
                if (cal.timeInMillis > System.currentTimeMillis() + 60_000) { Ui.toast(a, "Time is in the future"); return@setOnClickListener }
                val shift = cal.timeInMillis - anchor
                val now = System.currentTimeMillis()
                val corr = c == Carbs.CORRECTION
                val w = if (corr) 0 else (wait.text.toString().toIntOrNull() ?: 0).coerceIn(0, 120)
                val n = note.text.toString().trim()
                val doseTs = (d?.ts ?: m!!.ts) + shift
                if (d != null) db.updateDose(d.id, d.copy(ts = doseTs, units = u!!, carbs = c, waitMin = w, note = n), now, share = true)
                else if (u != null) db.addDose(Dose(0, doseTs, "rapid", u, c, w, db.near(doseTs)?.mg ?: 0, n))
                if (corr) { if (m != null) db.deleteMeal(m.id) }
                else {
                    val t = title.text.toString().trim().ifBlank { m?.title?.takeIf { it.isNotBlank() } ?: L.s(if (c < 0) "Meal (carbs not recorded)" else "Meal") }
                    val mealNote = if (d == null && u == null) n else m?.note ?: ""
                    if (m != null) db.updateMeal(m.id, m.copy(ts = m.ts + shift, title = t, carbs = c, low = if (c != m.carbs) c else m.low,
                        high = if (c != m.carbs) c else m.high, note = mealNote), now, share = true)
                    else { val mt = minOf(doseTs + w * 60_000L, now)
                        db.addMeal(Meal(0, mt, t, org.json.JSONArray().put(org.json.JSONObject().put("name", t).put("portion", "").put("carbs", c)).toString(),
                            c, c, c, db.near(mt)?.mg ?: 0, "", mealNote)) }
                }
                Ui.toast(a, "Saved"); dlg.dismiss(); done()
            }
        }
        dlg.show()
    }

    @Suppress("unused") private val keep = TextView::class
}
