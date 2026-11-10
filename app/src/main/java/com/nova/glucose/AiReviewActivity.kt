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
    private lateinit var convo: LinearLayout
    private lateinit var input: android.widget.EditText
    private lateinit var askRow: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var sv: ScrollView
    private var conv: AiChats.Conv? = null
    private var days = 7
    private var busy = false

    private val system = """
You review the glucose data of a person with type 1 diabetes (a child or teenager — see the profile), to prepare the next talk with their diabetes care team.
Write in plain text for a phone screen, with exactly these two headings:
What the AI noticed
(4 to 8 short bullets "• …": when highs and lows happen (time of day, after which meals or foods), nights, sick days or other incidents,
how photo carb estimates were corrected, anything improving; when watch data is given, how sport, steps, sleep and resting heart rate
line up with her glucose (e.g. night lows after sport, higher mornings after short nights); quote numbers from the data)
Questions for the doctor
(2 to 4 short bullets "• …" the family can ask)
When Lantus (long-acting) dose changes are given, say what changed before → after (3, 7, 14 days) and whether nights and fasting improved; if too few days, say it is too early.
Rules: ${AiData.NO_DOSE} Do not diagnose. Do not invent data; if there is too little data, say so. No markdown symbols other than "•".
""".trimIndent()

    private val chatSystem = """
You are discussing a person's type 1 diabetes data with their family, after an AI review (shown in the conversation). Answer the new question
in plain text for a phone screen: short, clear, quoting numbers and times from the data. Use "•" bullets only when listing.
When asked about Lantus (long-acting) dose changes, compare before → after using the given 3/7/14-day numbers and the nights; say if it is too early.
Rules: ${AiData.NO_DOSE} Do not diagnose. Do not invent data; if the data does not show it, say so. No markdown other than "•".
""".trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        val root = Ui.col(this, 16)
        sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "📊 AI review"), Ui.lp(0, weight = 1f))
        lateinit var lb: TextView
        lb = Ui.smallButton(this, "🌐 ${L.short(revLang())} ▾") {
            Lang.pick(this, lb, revLang()) { code -> st.aiReviewLang = code; lb.text = "🌐 ${L.short(code)} ▾" }
        }.apply { tag = "free" }
        head.addView(lb, Ui.lp(-2, Ui.dp(this, 36), end = Ui.dp(this, 6)))
        head.addView(Ui.smallButton(this, "🕘") { history() }, Ui.lp(-2, Ui.dp(this, 36), end = Ui.dp(this, 6)))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        root.addView(Ui.text(this, "AI: ${st.current().label} · " + L.s("looks at the patient's profile ({0}), readings, meals, insulin times, incidents and watch data (activity, sleep, heart rate)")
            .replace("{0}", listOfNotNull("age ${st.age}", st.heightCm.takeIf { it > 0 }?.let { "${it.fmt()} cm" }, st.weightKg.takeIf { it > 0 }?.let { "${it.fmt()} kg" }).joinToString(", ")), 13f, Ui.MUTED))
        chips = Ui.row(this)
        root.addView(chips, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.button(this, "✦ New review", true) { review() }, Ui.lp(top = Ui.dp(this, 10)))
        status = Ui.text(this, "", 13f, Ui.MUTED)
        root.addView(status, Ui.lp(top = Ui.dp(this, 8)))
        convo = Ui.col(this)
        root.addView(convo, Ui.lp(top = Ui.dp(this, 6)))
        askRow = Ui.row(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        input = Ui.input(this, "Ask the AI about this review…", multi = true)
        askRow.addView(input, Ui.lp(0, -2, weight = 1f, end = Ui.dp(this, 8)))
        askRow.addView(Ui.button(this, "Send", true) { ask() }, Ui.lp(-2, -2))
        root.addView(askRow, Ui.lp(top = Ui.dp(this, 10)))
        actions = Ui.row(this)
        actions.addView(Ui.button(this, "📤 Share") { share() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        actions.addView(Ui.button(this, "📄 Doctor report") { DoctorReport.pick(this) }, Ui.lp(0, weight = 1f))
        root.addView(actions, Ui.lp(top = Ui.dp(this, 12)))
        root.addView(Ui.text(this, "AI review, not medical advice. It never suggests insulin doses — discuss changes with the diabetes care team. " +
            "The last review is added to the doctor report. Conversations are kept 30 days (🕘).", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        root.addView(Ui.signature(this))
        setContentView(sv)
        conv = AiChats.latest(this)
        conv?.let { days = it.days }
        renderChips(); render()
    }

    private fun Double.fmt() = if (this % 1.0 == 0.0) this.toInt().toString() else "%.1f".format(Locale.US, this)

    /** The review's own language (defaults to the app language). */
    private fun revLang() = st.aiReviewLang.ifBlank { L.lang }
    private fun langLine(lg: String) = if (lg != "en") "\nWrite in ${L.name(lg)}." else ""

    private fun renderChips() {
        chips.removeAllViews()
        listOf(1 to "Today", 3 to "3 days", 7 to "7 days", 14 to "14 days").forEach { (d, l) ->
            chips.addView(Ui.chip(this, l, days == d) { days = d; renderChips() }, Ui.lp(-2, -2, end = Ui.dp(this, 6)))
        }
    }

    private fun bubble(text: String, me: Boolean) {
        val b = Ui.col(this).apply {
            background = Ui.round(if (me) 0xFF1E6E68.toInt() else Ui.CARD, Ui.dp(context, 16).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 14), Ui.dp(context, 10))
        }
        b.addView(Ui.text(this, if (me) L.s("You") else "✦ AI", 11.5f, if (me) 0xFFC8F0EC.toInt() else Ui.ACCENT, true))
        b.addView(Ui.text(this, text, 15f, Ui.TEXT).apply { setTextIsSelectable(true) })
        val holder = LinearLayout(this).apply { gravity = if (me) android.view.Gravity.END else android.view.Gravity.START }
        holder.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (me) marginStart = Ui.dp(this@AiReviewActivity, 36) else marginEnd = Ui.dp(this@AiReviewActivity, 24) })
        convo.addView(holder, Ui.lp(top = Ui.dp(this, 8)))
    }

    private fun render() {
        convo.removeAllViews()
        val c = conv
        if (c == null || c.turns.isEmpty()) {
            convo.addView(Ui.text(this, "Choose a period and tap ✦ New review. Then ask the AI anything about it in the same conversation.", 14f, Ui.MUTED))
            askRow.visibility = android.view.View.GONE; actions.visibility = android.view.View.GONE; status.text = ""; return
        }
        status.text = L.s("Review of {0} · {1} day(s)").replace("{0}", SimpleDateFormat("EEE d MMM HH:mm", L.loc()).format(Date(c.ts))).replace("{1}", "${c.days}")
        c.turns.forEach { bubble(it.second, it.first) }
        askRow.visibility = android.view.View.VISIBLE; actions.visibility = android.view.View.VISIBLE
    }

    private fun clean(a: String) = a.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?m)^#{1,6}\\s*"), "").replace(Regex("(?m)^\\s*[*-]\\s+"), "• ").trim()

    private fun review() {
        if (busy) return
        val p = st.current()
        if (st.key(p).isBlank()) { status.setTextColor(Ui.BAD); status.text = "Add your ${p.label} API key in Settings first."; return }
        busy = true
        status.setTextColor(Ui.MUTED); status.text = "${p.label} is reviewing ${if (days == 1) "today" else "the last $days days"}…"
        val app = applicationContext; val d = days; val lg = revLang()
        Thread {
            try {
                val a = Providers.ask(st, p, system + "\n" + AiData.audience(st) + (if (lg != "en") "\nWrite the whole review in ${L.name(lg)}, including the two headings." else ""), AiData.period(app, d)) { s -> runOnUiThread { status.text = s } }
                val txt = clean(a)
                st.aiReview = txt; st.aiReviewTs = System.currentTimeMillis(); st.aiReviewDays = d; st.aiReviewDoneLang = lg
                val c = AiChats.Conv(java.util.UUID.randomUUID().toString().take(10), System.currentTimeMillis(), d, mutableListOf(false to txt))
                AiChats.save(app, c)
                runOnUiThread { conv = c; status.setTextColor(Ui.MUTED); render() }
            } catch (e: Exception) {
                runOnUiThread { status.setTextColor(Ui.BAD); status.text = e.message ?: "Failed" }
            } finally { busy = false }
        }.start()
    }

    /** A follow-up question in the same conversation: the AI gets the data, the review and the earlier questions. */
    private fun ask() {
        val q = input.text.toString().trim(); val c = conv ?: return
        if (q.isEmpty() || busy) return
        val p = st.current()
        busy = true; input.setText("")
        c.turns += true to q; AiChats.save(this, c); render()
        status.setTextColor(Ui.MUTED); status.text = L.s("✦ AI is answering…")
        sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
        val app = applicationContext; val lg = revLang()
        Thread {
            try {
                val user = AiData.period(app, c.days) + "\n\nThe conversation so far:\n" +
                    c.turns.dropLast(1).joinToString("\n\n") { (me, t) -> (if (me) "Family: " else "AI: ") + t } + "\n\nNew question from the family: $q"
                val a = clean(Providers.ask(st, p, chatSystem + "\n" + AiData.audience(st) + langLine(lg), user) { s -> runOnUiThread { status.text = s } })
                c.turns += false to a; AiChats.save(app, c)
                runOnUiThread { render(); sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) } }
            } catch (e: Exception) {
                runOnUiThread { status.setTextColor(Ui.BAD); status.text = e.message ?: "Failed" }
            } finally { busy = false }
        }.start()
    }

    private fun history() {
        val l = AiChats.all(this)
        if (l.isEmpty()) { Ui.toast(this, "No conversations yet"); return }
        val df = SimpleDateFormat("EEE d MMM HH:mm", L.loc())
        val names = l.map { c -> val qs = c.turns.count { it.first }
            "${df.format(Date(c.ts))} · ${c.days} d · " + (if (qs == 0) L.s("review only") else L.s("{0} question(s)").replace("{0}", "$qs")) +
                (c.turns.lastOrNull { it.first }?.let { "\n" + it.second.take(60) } ?: "") as CharSequence }.toTypedArray()
        Tr.Builder(this).setTitle("🕘 " + L.s("Past conversations (30 days)")).setItems(names) { _, i ->
            conv = l[i]; days = l[i].days; renderChips(); render() }.setNegativeButton("Close", null).show()
    }

    private fun share() {
        val c = conv ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Nova Glucose AI review")
            .putExtra(Intent.EXTRA_TEXT, "Nova Glucose — AI review (${c.days} day(s), " + SimpleDateFormat("d MMM yyyy", L.loc()).format(Date(c.ts)) + ")\n\n" +
                c.turns.joinToString("\n\n") { (me, t) -> (if (me) "Q: " else "") + t } + "\n\nAI review, not medical advice."), "Share the review"))
    }
}

/** AI review conversations, kept 30 days on this phone. */
object AiChats {
    class Conv(val id: String, val ts: Long, val days: Int, val turns: MutableList<Pair<Boolean, String>>)
    private const val FILE = "ai_chats.json"
    private const val KEEP = 30L * 86_400_000

    @Synchronized fun all(c: android.content.Context): List<Conv> {
        val f = java.io.File(c.filesDir, FILE); if (!f.exists()) return emptyList()
        return try {
            val a = org.json.JSONArray(f.readText()); val now = System.currentTimeMillis()
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val t = o.optJSONArray("t") ?: org.json.JSONArray()
                Conv(o.optString("id"), o.optLong("ts"), o.optInt("d", 7), (0 until t.length()).map { t.getJSONObject(it) }.map { it.optBoolean("me") to it.optString("x") }.toMutableList())
            }.filter { now - it.ts < KEEP }.sortedByDescending { it.ts }
        } catch (_: Exception) { emptyList() }
    }

    fun latest(c: android.content.Context) = all(c).firstOrNull()

    @Synchronized fun save(c: android.content.Context, conv: Conv) {
        val l = all(c).filter { it.id != conv.id } + conv
        val a = org.json.JSONArray()
        l.sortedByDescending { it.ts }.take(60).forEach { x ->
            a.put(org.json.JSONObject().put("id", x.id).put("ts", x.ts).put("d", x.days).put("t", org.json.JSONArray().apply {
                x.turns.forEach { (me, t) -> put(org.json.JSONObject().put("me", me).put("x", t)) } }))
        }
        java.io.File(c.filesDir, FILE).writeText(a.toString())
    }
}
