package com.nova.glucose

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** AI review of the last 1, 7 or 14 days: patterns (meals, times, sick days) and questions for the doctor. Never doses. */
class AiReviewActivity : BaseActivity() {
    private lateinit var st: Store
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var out: TextView
    private lateinit var actions: LinearLayout
    private var days = 7
    private var busy = false

    private val system = """
You review the glucose data of a child with type 1 diabetes for her family, to prepare the next talk with her diabetes care team.
Write in plain text for a phone screen, with exactly these two headings:
What the AI noticed
(4 to 8 short bullets "• …": when highs and lows happen (time of day, after which meals or foods), nights, sick days or other incidents,
how photo carb estimates were corrected, anything improving; when watch data is given, how sport, steps, sleep and resting heart rate
line up with her glucose (e.g. night lows after sport, higher mornings after short nights); quote numbers from the data)
Questions for the doctor
(2 to 4 short bullets "• …" the family can ask)
Rules: ${AiData.NO_DOSE} Do not diagnose. Do not invent data; if there is too little data, say so. No markdown symbols other than "•".
""".trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        val root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "📊 AI review"), Ui.lp(0, weight = 1f))
        lateinit var lb: TextView
        lb = Ui.smallButton(this, "🌐 ${L.short(revLang())} ▾") {
            Lang.pick(this, lb, revLang()) { code ->
                st.aiReviewLang = code; lb.text = "🌐 ${L.short(code)} ▾"
                if (st.aiReview.isNotBlank() && code != st.aiReviewDoneLang) { days = st.aiReviewDays; renderChips(); review() }
            }
        }.apply { tag = "free" }
        head.addView(lb, Ui.lp(-2, Ui.dp(this, 36), end = Ui.dp(this, 8)))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        root.addView(Ui.text(this, "AI: ${st.current().label} · " + L.s("looks at her profile ({0}), readings, meals, insulin times, incidents and watch data (activity, sleep, heart rate)")
            .replace("{0}", listOfNotNull("age ${st.age}", st.heightCm.takeIf { it > 0 }?.let { "${it.fmt()} cm" }, st.weightKg.takeIf { it > 0 }?.let { "${it.fmt()} kg" }).joinToString(", ")), 13f, Ui.MUTED))
        chips = Ui.row(this)
        root.addView(chips, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.button(this, "✦ Review now", true) { review() }, Ui.lp(top = Ui.dp(this, 10)))
        status = Ui.text(this, "", 13f, Ui.MUTED)
        root.addView(status, Ui.lp(top = Ui.dp(this, 8)))
        val card = Ui.card(this)
        out = Ui.text(this, "", 15f, Ui.TEXT).apply { setTextIsSelectable(true) }
        card.addView(out)
        actions = Ui.row(this)
        actions.addView(Ui.button(this, "📤 Share") { share() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        actions.addView(Ui.button(this, "📄 Doctor report") { DoctorReport.pick(this) }, Ui.lp(0, weight = 1f))
        card.addView(actions, Ui.lp(top = Ui.dp(this, 12)))
        card.addView(Ui.text(this, "AI review, not medical advice. It never suggests insulin doses — discuss changes with her diabetes care team. " +
            "The last review is added to the doctor report.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        root.addView(card, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.signature(this))
        setContentView(sv)
        renderChips(); showSaved()
    }

    private fun Double.fmt() = if (this % 1.0 == 0.0) this.toInt().toString() else "%.1f".format(Locale.US, this)

    /** The review's own language (defaults to the app language). */
    private fun revLang() = st.aiReviewLang.ifBlank { L.lang }

    private fun renderChips() {
        chips.removeAllViews()
        listOf(1 to "Today", 7 to "7 days", 14 to "14 days").forEach { (d, l) ->
            chips.addView(Ui.chip(this, l, days == d) { days = d; renderChips() }, Ui.lp(-2, -2, end = Ui.dp(this, 8)))
        }
    }

    private fun showSaved() {
        if (st.aiReview.isBlank()) { out.text = "Choose a period and tap Review now."; actions.visibility = android.view.View.GONE; return }
        out.text = st.aiReview
        status.text = "Last review: ${SimpleDateFormat("EEE d MMM HH:mm", L.loc()).format(Date(st.aiReviewTs))} · ${st.aiReviewDays} day(s)"
        actions.visibility = android.view.View.VISIBLE
    }

    private fun review() {
        if (busy) return
        val p = st.current()
        if (st.key(p).isBlank()) { status.setTextColor(Ui.BAD); status.text = "Add your ${p.label} API key in Settings first."; return }
        busy = true
        status.setTextColor(Ui.MUTED); status.text = "${p.label} is reviewing ${if (days == 1) "today" else "the last $days days"}…"
        val app = applicationContext; val d = days; val lg = revLang()
        Thread {
            try {
                val a = Providers.ask(st, p, system + (if (lg != "en") "\nWrite the whole review in ${L.name(lg)}, including the two headings." else ""), AiData.period(app, d)) { s -> runOnUiThread { status.text = s } }
                val txt = a.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?m)^#{1,6}\\s*"), "").replace(Regex("(?m)^\\s*[*-]\\s+"), "• ").trim()
                st.aiReview = txt; st.aiReviewTs = System.currentTimeMillis(); st.aiReviewDays = d; st.aiReviewDoneLang = lg
                runOnUiThread { status.setTextColor(Ui.MUTED); showSaved() }
            } catch (e: Exception) {
                runOnUiThread { status.setTextColor(Ui.BAD); status.text = e.message ?: "Failed" }
            } finally { busy = false }
        }.start()
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Nova Glucose AI review")
            .putExtra(Intent.EXTRA_TEXT, "Nova Glucose — AI review (${st.aiReviewDays} day(s), " +
                SimpleDateFormat("d MMM yyyy", L.loc()).format(Date(st.aiReviewTs)) + ")\n\n" + st.aiReview +
                "\n\nAI review, not medical advice."), "Share the review"))
    }
}
