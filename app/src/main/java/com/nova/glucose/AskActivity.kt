package com.nova.glucose

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Ask AI" — food ideas from the chosen AI, using the current reading and trend. */
class AskActivity : BaseActivity() {
    private lateinit var st: Store
    private lateinit var q: EditText
    private lateinit var answer: TextView
    private lateinit var status: TextView
    private lateinit var chips: LinearLayout
    private var busy = false

    private val presets = listOf(
        "What should she eat now?",
        "Plan today's meals",
        "Snack ideas",
        "Low-carb dinner ideas",
        "School lunchbox ideas",
        "Something sweet that won't spike much"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        val root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "Ask AI"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)

        val r = reading()
        val ctx = if (r != null) "${r.mg} ${Libre.trendArrow(r.trend)} mg/dL (${Libre.trendText(r.trend)})" else "no recent reading"
        root.addView(Ui.text(this, "AI: ${st.current().label}  ·  glucose $ctx", 13f, Ui.MUTED))

        val (csv, row) = Ui.chipRow(this)
        chips = row
        presets.forEach { p -> row.addView(Ui.chip(this, p, false) { q.setText(p); ask() }, Ui.lp(-2, -2, end = Ui.dp(this, 8))) }
        root.addView(csv, Ui.lp(top = Ui.dp(this, 12)))

        q = Ui.input(this, "Ask about food… e.g. \"ideas with couscous for dinner\"", minLines = 2)
        root.addView(q, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.button(this, "✦ Ask", true) { ask() }, Ui.lp(top = Ui.dp(this, 8)))
        status = Ui.text(this, "", 13f, Ui.MUTED)
        root.addView(status, Ui.lp(top = Ui.dp(this, 8)))

        val card = Ui.card(this)
        answer = Ui.text(this, "Pick a question above or type your own.", 15f, Ui.TEXT).apply { setTextIsSelectable(true) }
        card.addView(answer)
        card.addView(Ui.text(this, Foods.CARE_NOTE, 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        root.addView(card, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.signature(this))
        setContentView(sv)
    }

    private fun reading(): Reading? =
        if (st.lastTs > 0 && System.currentTimeMillis() - st.lastTs < 20 * 60_000) Reading(st.lastTs, st.lastMg, st.lastTrend) else null

    private fun context(): String {
        val now = System.currentTimeMillis()
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        val r = reading()
        val hist = Db.get(this).readings(now - 3 * 3600_000L)
            .filterIndexed { i, _ -> i % 2 == 0 }.takeLast(12)
            .joinToString(", ") { "${tf.format(Date(it.ts))} ${it.mg}" }
        val meals = Db.get(this).meals(5).filter { now - it.ts < 8 * 3600_000L }
            .joinToString("; ") { "${tf.format(Date(it.ts))} ${it.title} (${it.carbs} g)" }
        return buildString {
            append("Time now: ${tf.format(Date(now))} (${Foods.mealSlot()} time).\n")
            append("Current glucose: ").append(r?.let { "${it.mg} mg/dL, ${Libre.trendText(it.trend)}" } ?: "unknown (no recent reading)").append("\n")
            if (hist.isNotBlank()) append("Last 3 hours (time mg/dL): $hist\n")
            if (meals.isNotBlank()) append("Meals logged today: $meals\n")
            append("Her ranges: low below ${st.low}, high above ${st.high} mg/dL. ")
            append("Carb plan: meals ${st.mealMin}–${st.mealMax} g, snacks ${st.snackMin}–${st.snackMax} g, low treatment ${st.lowTreatG} g fast sugar.\n")
            if (st.foodNotes.isNotBlank()) append("Family food notes: ${st.foodNotes}\n")
        }
    }

    private val system = """
You help a family choose food for their teenage daughter (13–17) who has diabetes and uses rapid insulin.
Suggest practical food ideas from a mix of Tunisian, Gulf/Middle Eastern and international cuisine, each with a portion and an
approximate carb count in grams. Keep answers short, friendly and easy to scan on a phone (short lines, simple bullets, no tables).
Rules:
- NEVER suggest insulin doses, units, or changes to her treatment. If asked, say to follow her diabetes care team.
- If glucose is low (below her low limit): first say to have the fast-sugar treatment from her plan and recheck in 15 minutes,
  avoid fatty/chocolate treatments; only then any snack advice.
- If glucose is very high, mention drinking water and checking ketones as her care team advised; suggest low-carb choices.
- Use the context numbers given; do not invent readings.
- End with one short line reminding that carb counts are approximate.
""".trimIndent()

    private fun ask() {
        if (busy) return
        val question = q.text.toString().trim().ifBlank { presets[0] }
        val p = st.current()
        if (st.key(p).isBlank()) { status.setTextColor(Ui.BAD); status.text = "Add your ${p.label} API key in Settings first."; return }
        busy = true
        status.setTextColor(Ui.MUTED); status.text = "Asking ${p.label}…"
        val user = context() + "\nQuestion: " + question
        Thread {
            try {
                val a = Providers.ask(st, p, system, user) { s -> runOnUiThread { status.text = s } }
                runOnUiThread { answer.text = clean(a); status.text = "" }
            } catch (e: Exception) {
                runOnUiThread { status.setTextColor(Ui.BAD); status.text = e.message ?: "Failed" }
            } finally { busy = false }
        }.start()
    }

    /** Light clean-up of markdown so it reads well as plain text. */
    private fun clean(s: String) = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[*-]\\s+"), "•  ")
}
