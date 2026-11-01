package com.nova.glucose

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Tap on an M (meal) or I (rapid insulin) mark of the chart: details of that moment + the whole meals/insulin history. */
object MarkHistory {
    private const val NEAR = 60 * 60_000L
    private const val RED = GraphView.MARK

    /** Chart caption with red M and I. */
    fun legend(prefix: String): CharSequence {
        val sb = SpannableStringBuilder(prefix)
        fun red(s: String) { val a = sb.length; sb.append(s); sb.setSpan(ForegroundColorSpan(RED), a, sb.length, 0); sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, 0) }
        red("M"); sb.append(" = meal · "); red("I"); sb.append(" = rapid insulin")
        return sb
    }

    private sealed class Entry(val ts: Long) {
        class M(val m: Meal) : Entry(m.ts)
        class I(val d: Dose) : Entry(d.ts)
        class X(val i: Incident) : Entry(i.ts)
    }

    fun show(a: Activity, t: Long) {
        val db = Db.get(a)
        val since = System.currentTimeMillis() - 90L * 24 * 3600_000
        val all = (db.meals(400).map { Entry.M(it) } + db.doses(since).map { Entry.I(it) } + db.incidents(since).map { Entry.X(it) })
            .sortedByDescending { it.ts }.take(250)
        val now = System.currentTimeMillis()

        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(a, 18), Ui.dp(a, 8), Ui.dp(a, 18), Ui.dp(a, 4)) }
        val title = Ui.text(a, "", 17f, Ui.TEXT, true)
        val details = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(title)
        root.addView(details, Ui.lp(top = Ui.dp(a, 8)))
        root.addView(Ui.text(a, "History of meals, insulin and incidents (newest first)", 12f, Ui.MUTED), Ui.lp(top = Ui.dp(a, 14)))
        root.addView(list, Ui.lp(top = Ui.dp(a, 6)))
        root.addView(Ui.signature(a))
        val sv = ScrollView(a).apply { addView(root); setBackgroundColor(Ui.CARD) }

        var dlg: AlertDialog? = null
        val reopen: (Long) -> Unit = { at -> dlg?.dismiss(); show(a, at) }
        lateinit var select: (Long) -> Unit
        select = { at ->
            val group = all.filter { e -> when (e) {
                    is Entry.M -> kotlin.math.abs(e.ts - at) <= NEAR
                    is Entry.I -> e.d.kind == "rapid" && kotlin.math.abs(e.ts - at) <= NEAR
                    is Entry.X -> at >= e.i.ts - NEAR && at <= (if (e.i.ongoing) now else e.i.end) + NEAR
                } }.sortedBy { it.ts }.ifEmpty { all.filter { it.ts == at } }
            val hasM = group.any { it is Entry.M }; val hasI = group.any { it is Entry.I }
            title.text = (if (hasM && hasI) "Meal and insulin" else if (hasM) "Meal" else if (hasI) "Insulin" else "Incident") + " · " + day(at)
            details.removeAllViews()
            if (group.isEmpty()) details.addView(Ui.text(a, "Nothing saved at this time.", 13f, Ui.MUTED))
            group.forEach { details.addView(detail(a, it) { reopen(at) }) }
            list.removeAllViews()
            all.forEach { e -> list.addView(row(a, e, e in group) { if (e is Entry.X) IncidentActivity.open(a, e.i.id) else select(e.ts) }) }
        }
        select(t)

        dlg = AlertDialog.Builder(a).setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Open logbook") { _, _ -> a.startActivity(Intent(a, LogbookActivity::class.java)) }
            .show()
    }

    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private fun day(t: Long): String {
        val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR); val y = c.get(Calendar.YEAR)
        c.timeInMillis = t
        val d = c.get(Calendar.DAY_OF_YEAR)
        return when {
            c.get(Calendar.YEAR) == y && d == today -> hm.format(Date(t))
            c.get(Calendar.YEAR) == y && d == today - 1 -> "Yesterday ${hm.format(Date(t))}"
            else -> SimpleDateFormat("EEE d MMM HH:mm", Locale.US).format(Date(t))
        }
    }

    private fun letter(sb: SpannableStringBuilder, s: String) {
        val a = sb.length; sb.append(s)
        sb.setSpan(ForegroundColorSpan(if (s == "!") Incidents.COLOR else RED), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun units(u: Double) = if (u % 1.0 == 0.0) u.toInt().toString() else u.toString()

    private fun detail(a: Activity, e: Entry, edited: () -> Unit): View {
        val st = Store(a); val db = Db.get(a)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Ui.BG, Ui.dp(a, 12).toFloat())
            setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10))
        }
        val head = SpannableStringBuilder()
        val more = mutableListOf<String>()
        when (e) {
            is Entry.M -> {
                val m = e.m
                letter(head, "M"); head.append("  ${hm.format(Date(m.ts))} · ${m.title} · ${m.carbs} g carbs")
                try {
                    val arr = JSONArray(m.itemsJson)
                    if (arr.length() > 1) more += (0 until arr.length()).joinToString("\n") { k ->
                        arr.getJSONObject(k).let { o -> "•  ${o.optString("name")} ${o.optString("portion").let { p -> if (p.isNotBlank()) "($p) " else "" }}— ${o.optInt("carbs")} g" } }
                } catch (_: Exception) { }
                db.ai(m.id)?.let { (_, ai) -> more += if (ai == m.carbs) "AI said $ai g · kept by the family" else "AI said $ai g · family corrected to ${m.carbs} g" }
                listOfNotNull(if (m.mgAt > 0) "glucose then ${m.mgAt} mg/dL" else null,
                    db.who("meals", m.id).takeIf { it.isNotBlank() }?.let { "by $it" }).takeIf { it.isNotEmpty() }?.let { more += it.joinToString(" · ") }
                if (m.note.isNotBlank()) more += "Note: ${m.note}"
            }
            is Entry.I -> {
                val d = e.d
                val name = if (d.kind == "rapid") st.rapidName.ifBlank { "Rapid insulin" } else st.longName.ifBlank { "Long-acting insulin" }
                letter(head, if (d.kind == "rapid") "I" else "L")
                head.append("  ${hm.format(Date(d.ts))} · $name · ${units(d.units)} u" + if (d.waitMin > 0) " · eat after ${d.waitMin} min" else "")
                listOfNotNull(if (d.carbs > 0) "for ${d.carbs} g carbs" else null, if (d.mgAt > 0) "glucose then ${d.mgAt} mg/dL" else null,
                    db.who("insulin", d.id).takeIf { it.isNotBlank() }?.let { "by $it" }).takeIf { it.isNotEmpty() }?.let { more += it.joinToString(" · ") }
                if (d.note.isNotBlank()) more += "Note: ${d.note}"
            }
            is Entry.X -> {
                val i = e.i
                letter(head, "!"); head.append("  ${i.kind}")
                more += Incidents.period(i)
                if (i.details.isNotBlank()) more += i.details
                if (i.meds.isNotBlank()) more += "Prescription:\n${i.meds}"
                if (i.photo.isNotBlank()) more += "📄 Prescription photo saved — tap to open"
                db.who("incidents", i.id).takeIf { it.isNotBlank() }?.let { more += "by $it" }
                box.setOnClickListener { IncidentActivity.open(a, i.id) }
            }
        }
        box.addView(TextView(a).apply { text = head; textSize = 15f; setTextColor(Ui.TEXT) })
        more.forEach { box.addView(Ui.text(a, it, 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 3), 0, 0) }) }
        when (e) {
            is Entry.M -> box.addView(Ui.smallButton(a, "✏️ Edit") { EditEntry.meal(a, e.m.id) { edited() } }, Ui.lp(-2, Ui.dp(a, 36), top = Ui.dp(a, 8)))
            is Entry.I -> box.addView(Ui.smallButton(a, "✏️ Edit") { EditEntry.dose(a, e.d.id) { edited() } }, Ui.lp(-2, Ui.dp(a, 36), top = Ui.dp(a, 8)))
            is Entry.X -> { }
        }
        return LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(box); setPadding(0, 0, 0, Ui.dp(a, 8)) }
    }

    private fun row(a: Activity, e: Entry, on: Boolean, click: () -> Unit): View {
        val st = Store(a)
        val sb = SpannableStringBuilder()
        val right: String
        when (e) {
            is Entry.M -> { letter(sb, "M"); sb.append("  ${day(e.ts)} · ${e.m.title}"); right = "${e.m.carbs} g" }
            is Entry.I -> {
                letter(sb, if (e.d.kind == "rapid") "I" else "L")
                sb.append("  ${day(e.ts)} · " + if (e.d.kind == "rapid") st.rapidName.ifBlank { "Rapid insulin" } else st.longName.ifBlank { "Long-acting insulin" })
                right = "${units(e.d.units)} u"
            }
            is Entry.X -> { letter(sb, "!"); sb.append("  ${day(e.ts)} · ${e.i.kind}"); right = if (e.i.ongoing) "ongoing" else "›" }
        }
        val r = Ui.row(a).apply {
            setPadding(Ui.dp(a, 10), Ui.dp(a, 9), Ui.dp(a, 10), Ui.dp(a, 9))
            background = if (on) Ui.round(0x33FF5A5F, Ui.dp(a, 10).toFloat(), Ui.dp(a, 1), RED) else null
            setOnClickListener { click() }
        }
        r.addView(TextView(a).apply { text = sb; textSize = 13f; setTextColor(Ui.TEXT) }, Ui.lp(0, weight = 1f))
        r.addView(Ui.text(a, right, 13f, Ui.TEXT, true))
        return r
    }
}
