package com.nova.glucose

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ⌚ Activity & sleep: her watch data next to the glucose — one day, or links over 7 / 14 days. */
class HealthActivity : BaseActivity() {
    private lateinit var st: Store
    private lateinit var body: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var sub: TextView
    private var days = 1
    private var day = 0L
    private lateinit var famBox: LinearLayout
    private lateinit var famPeer: TextView
    private lateinit var famReq: TextView
    private val tick = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        day = WatchData.dayStart(System.currentTimeMillis())
        intent?.getIntExtra("days", 0)?.takeIf { it > 0 }?.let { days = it }
        val root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "⌚ Activity & sleep"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        sub = Ui.text(this, "", 13f, Ui.MUTED)
        root.addView(sub)
        // Family phone: the patient's phone status, and "read now"
        famBox = Ui.col(this, 12).apply { background = Ui.round(Ui.CARD, Ui.dp(context, 14).toFloat()) }
        famPeer = Ui.text(this, "", 12.5f, Ui.TEXT)
        famBox.addView(famPeer)
        famReq = Ui.text(this, "", 12.5f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 4), 0, 0) }
        famBox.addView(famReq)
        famBox.addView(Ui.smallButton(this, "⟳ Ask the patient's phone to read the watch now") {
            if (st.syncFam.isBlank()) { Ui.toast(this, "Family sync is off"); return@smallButton }
            WatchRequest.ask(this); renderFam(); poll() }, Ui.lp(-2, Ui.dp(this, 36), top = Ui.dp(this, 8)))
        root.addView(famBox, Ui.lp(top = Ui.dp(this, 8)))
        chips = Ui.row(this)
        root.addView(chips, Ui.lp(top = Ui.dp(this, 10)))
        body = Ui.col(this)
        root.addView(body)
        root.addView(Ui.signature(this))
        setContentView(sv)
        render()
    }

    override fun onResume() {
        super.onResume()
        HealthRead.maybe(this)
        if (WatchRequest.autoAsk(this)) Ui.toast(this, "Asked the patient's phone for fresh watch data…")
        renderFam(); poll()
    }

    override fun onPause() { tick.removeCallbacksAndMessages(null); super.onPause() }

    private fun renderFam() {
        famBox.visibility = if (st.isFamily) android.view.View.VISIBLE else android.view.View.GONE
        famPeer.text = WatchRequest.peerLine(this)
        famReq.text = WatchRequest.requestLine(this)
        famReq.visibility = if (famReq.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** While waiting for the patient's phone: refresh every 5 s; redraw the page when the answer arrives. */
    private fun poll() {
        tick.removeCallbacksAndMessages(null)
        if (!WatchRequest.pending(this)) return
        tick.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                val waiting = WatchRequest.pending(this@HealthActivity)
                renderFam()
                if (waiting) tick.postDelayed(this, 5_000) else render()
            }
        }, 5_000)
    }

    private fun renderChips() {
        chips.removeAllViews()
        listOf(1 to "Day", 7 to "7 days", 14 to "14 days").forEach { (d, l) ->
            chips.addView(Ui.chip(this, l, days == d) { days = d; render() }, Ui.lp(-2, -2, end = Ui.dp(this, 8)))
        }
    }

    private fun render() {
        renderChips()
        val f = SimpleDateFormat("HH:mm", L.loc())
        val fd = SimpleDateFormat("d MMM HH:mm", L.loc())
        sub.text = when {
            !WatchData.hasAny(this) -> L.s("No watch data yet. On the patient's phone: Settings → Huawei watch data → Connect.")
            st.hcLastData > 0 -> L.s("Huawei watch · via Health Connect · last data {0}").replace("{0}",
                if (System.currentTimeMillis() - st.hcLastData < 20 * 3600_000L) f.format(Date(st.hcLastData)) else fd.format(Date(st.hcLastData)))
            else -> L.s("Huawei watch · via Health Connect")
        }
        body.removeAllViews()
        if (days == 1) dayView() else periodView()
    }

    // ---------------------------------------------------------------- one day
    private fun dayView() {
        val nav = Ui.row(this).apply { setPadding(0, Ui.dp(context, 12), 0, 0) }
        nav.addView(Ui.smallButton(this, "‹") { day = WatchData.addDays(day, -1); render() }.apply { tag = "free" })
        val today = WatchData.dayStart(System.currentTimeMillis())
        val label = when (day) {
            today -> L.s("Today") + " · " + SimpleDateFormat("EEE d MMM", L.loc()).format(Date(day))
            WatchData.addDays(today, -1) -> L.s("Yesterday") + " · " + SimpleDateFormat("EEE d MMM", L.loc()).format(Date(day))
            else -> SimpleDateFormat("EEE d MMM yyyy", L.loc()).format(Date(day))
        }
        nav.addView(Ui.text(this, label, 16f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(0, weight = 1f))
        nav.addView(Ui.smallButton(this, "›") { if (day < today) { day = WatchData.addDays(day, 1); render() } }.apply { alpha = if (day < today) 1f else 0.35f })
        body.addView(nav)

        val db = Db.get(this)
        val end = WatchData.addDays(day, 1) - 1
        val w = WatchData.get(this, day)
        val chart = DayChart(this).apply {
            start = day; readings = db.readings(day, end); this.w = w
            sleep = WatchData.sleepIn(this@HealthActivity, day, end + 1)
            ex = (WatchData.get(this@HealthActivity, WatchData.addDays(day, -1)).ex + w.ex).filter { it.b > day && it.a <= end }
            meals = db.meals(400).filter { it.ts in day..end }.map { it.ts }
            val ds = db.doses(day, end)
            doses = ds.filter { it.kind == "rapid" }.map { it.ts }; longs = ds.filter { it.kind == "long" }.map { it.ts }
            low = st.low; high = st.high
        }
        val cc = Ui.card(this).apply { setPadding(Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10)) }
        cc.addView(chart)
        cc.addView(Ui.text(this, L.s("Touch the chart to see every value at that moment.") + "  " +
            L.s("Sleep: dark = deep · light · pale = REM · orange = awake") + "  · M = " + L.s("meal") + " · I = " + L.s("rapid insulin") + " · LI = " + L.s("long-acting insulin"), 11f, Ui.MUTED)
            .apply { setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), 0) })
        body.addView(cc, Ui.lp(top = Ui.dp(this, 10)))
        chart.ex.firstOrNull()?.let { e -> chart.showAt(minOf(e.b + 20 * 60_000L, end, System.currentTimeMillis())) }

        val s = WatchData.sum(this, day, st)
        val card = Ui.card(this)
        card.addView(sec("Watch that day"))
        card.addView(grid(listOf(
            Tile("Steps", "%,d".format(Locale.US, s.steps), ""),
            Tile("Exercise", if (s.exMin > 0) "${s.exMin} min" else "–", s.ex.firstOrNull()?.let { L.s(WatchData.exName(it.type, it.title)) } ?: ""),
            Tile("Sleep", if (s.sleepMin > 0) WatchData.hm(s.sleepMin) else "–", if (s.deepMin > 0) L.s("deep") + " " + WatchData.hm(s.deepMin) else ""),
            Tile("Resting HR", if (s.rhr > 0) "${s.rhr}" else "–", "bpm"),
            Tile("Max HR", if (s.maxHr > 0) "${s.maxHr}" else "–", "bpm"),
            Tile("SpO2", if (s.spo2 > 0) "%.0f%%".format(Locale.US, s.spo2) else "–", ""))))
        card.addView(sec("Glucose that day").apply { setPadding(0, Ui.dp(context, 14), 0, Ui.dp(context, 6)) })
        card.addView(grid(listOf(
            Tile("Time in range", s.tir?.let { "$it%" } ?: "–", "", if ((s.tir ?: 0) >= 70) Ui.GOOD else Ui.TEXT),
            Tile("Average", s.avg?.toString() ?: "–", "mg/dL"),
            Tile("Lows", "${s.lows}", s.firstLow?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it.ts)) + " · ${it.mg}" } ?: "", if (s.lows > 0) Ui.WARN else Ui.TEXT))))
        if (!s.hasWatch) card.addView(Ui.text(this, "No watch data for this day.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        card.addView(Ui.text(this, "Stress: not available — Health Connect has no stress data.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        body.addView(card, Ui.lp(top = Ui.dp(this, 10)))
        body.addView(Ui.button(this, "📊 AI review with the watch data") { startActivity(Intent(this, AiReviewActivity::class.java)) }, Ui.lp(top = Ui.dp(this, 10)))
    }

    // ---------------------------------------------------------------- 7 / 14 days
    private fun periodView() {
        val ds = WatchData.period(this, days)
        val k = WatchData.compare(this, ds)
        body.addView(Ui.text(this, L.s("Links between the watch and the glucose · last {0} days").replace("{0}", "$days"), 13f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        if (ds.none { it.hasWatch }) {
            body.addView(Ui.card(this).apply { addView(Ui.text(context, "No watch data in this period yet.", 14f, Ui.MUTED)) }, Ui.lp(top = Ui.dp(this, 10)))
            return
        }
        // active vs quiet
        val c1 = Ui.card(this)
        c1.addView(sec("Active days vs quiet days"))
        if (k.active.isEmpty() || k.quiet.isEmpty()) c1.addView(Ui.text(this, "Not enough days with steps to compare yet.", 13f, Ui.MUTED))
        else {
            c1.addView(Ui.text(this, L.s(k.activeLabel) + " (${k.active.size}) · " + L.s(k.quietLabel) + " (${k.quiet.size})", 12f, Ui.MUTED))
            fun pair(label: String, a: Int?, b: Int?, max: Int, unit: String, good: Int) {
                c1.addView(bar(label, a, max, unit, good)); c1.addView(bar("", b, max, unit, 0xFF4B5468.toInt()))
            }
            pair("Time in range", k.tir(k.active), k.tir(k.quiet), 100, "%", Ui.GOOD)
            pair("Average", k.avg(k.active), k.avg(k.quiet), 250, "", Ui.GOOD)
            pair("Night lows", k.nightLows(this, k.active), k.nightLows(this, k.quiet), maxOf(4, k.nightLows(this, k.active), k.nightLows(this, k.quiet)), "", Ui.WARN)
            c1.addView(Ui.text(this, "Top bar = active days · grey bar = quiet days", 11f, Ui.MUTED))
        }
        body.addView(c1, Ui.lp(top = Ui.dp(this, 10)))

        // after sport
        val c2 = Ui.card(this)
        c2.addView(sec(L.s("After sport ({0} sessions)").replace("{0}", "${k.sports.size}")))
        if (k.sports.isEmpty()) c2.addView(Ui.text(this, "No exercise sessions in this period.", 13f, Ui.MUTED))
        else {
            val drops = k.sports.mapNotNull { it.drop2h }
            val lows = k.sports.filter { it.lowAt != null }
            val hours = lows.map { SimpleDateFormat("EEE HH:mm", L.loc()).format(Date(it.lowAt!!.ts)) }
            c2.addView(grid(listOf(
                Tile("Glucose drop in 2 h", if (drops.isNotEmpty()) "−${drops.average().toInt()}" else "–", "mg/dL on average"),
                Tile("Low within 12 h", L.s("{0} of {1}").replace("{0}", "${lows.size}").replace("{1}", "${k.sports.size}"),
                    if (hours.isNotEmpty()) hours.joinToString(", ") else "", if (lows.isNotEmpty()) Ui.WARN else Ui.GOOD)), 2))
        }
        body.addView(c2, Ui.lp(top = Ui.dp(this, 10)))

        // sleep and morning
        val c3 = Ui.card(this)
        c3.addView(sec("Sleep and the next morning"))
        val ws = k.wake(k.shortSleep); val wl = k.wake(k.longSleep)
        c3.addView(grid(listOf(
            Tile(L.s("Under 7 h sleep ({0} nights)").replace("{0}", "${k.shortSleep.size}"), ws?.toString() ?: "–", "wake-up average"),
            Tile(L.s("8 h or more ({0} nights)").replace("{0}", "${k.longSleep.size}"), wl?.toString() ?: "–", "wake-up average")), 2))
        body.addView(c3, Ui.lp(top = Ui.dp(this, 10)))

        // resting heart rate
        val c4 = Ui.card(this)
        c4.addView(sec("Resting heart rate"))
        val sp = Spark(this).apply { values = ds.map { it.rhr }; marks = ds.withIndex().filter { it.value in k.rhrHigh }.map { it.index }.toSet() }
        c4.addView(sp, Ui.lp(top = Ui.dp(this, 4)))
        val df = SimpleDateFormat("d MMM", L.loc())
        val ends = Ui.row(this)
        ends.addView(Ui.text(this, df.format(Date(ds.first().start)), 11f, Ui.MUTED), Ui.lp(0, weight = 1f))
        ends.addView(Ui.text(this, df.format(Date(ds.last().start)), 11f, Ui.MUTED))
        c4.addView(ends)
        c4.addView(Ui.text(this, if (k.rhrHigh.isEmpty()) L.s("Steady around {0} bpm.").replace("{0}", "${k.rhrBase}") else
            WatchData.lines(this, k).lastOrNull { it.startsWith("Resting") } ?: "", 13f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        body.addView(c4, Ui.lp(top = Ui.dp(this, 10)))

        // day by day
        val c5 = Ui.card(this)
        c5.addView(sec("Day by day"))
        val ddf = SimpleDateFormat("EEE d", L.loc())
        c5.addView(tableRow(listOf("Day", "Steps", "Sleep", "RHR", "TIR", "Lows"), true))
        ds.reversed().forEach { d ->
            c5.addView(tableRow(listOf(ddf.format(Date(d.start)), if (d.steps > 0) "%,d".format(Locale.US, d.steps) else "–",
                if (d.sleepMin > 0) "${d.sleepMin / 60}:%02d".format(d.sleepMin % 60) else "–", if (d.rhr > 0) "${d.rhr}" else "–",
                d.tir?.let { "$it%" } ?: "–", "${d.lows}"), false) { day = d.start; days = 1; render() })
        }
        c5.addView(Ui.text(this, "Tap a day to open it.", 11f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        body.addView(c5, Ui.lp(top = Ui.dp(this, 10)))
        body.addView(Ui.text(this, "These are links seen in the data, not causes. Discuss any change with the diabetes care team.", 12f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
    }

    // ---------------------------------------------------------------- small views
    private class Tile(val label: String, val value: String, val note: String, val color: Int = Ui.TEXT)

    private fun sec(s: String) = Ui.text(this, L.s(s).uppercase(), 12f, Ui.TEXT, true).apply { letterSpacing = 0.06f; setPadding(0, 0, 0, Ui.dp(context, 6)) }

    private fun grid(tiles: List<Tile>, cols: Int = 3): View {
        val col = Ui.col(this)
        tiles.chunked(cols).forEachIndexed { r, row ->
            val line = Ui.row(this).apply { gravity = Gravity.TOP }
            row.forEachIndexed { i, t ->
                val b = Ui.col(this, 10).apply { background = Ui.round(Ui.CARD2, Ui.dp(context, 12).toFloat()) }
                b.addView(Ui.text(this, t.label, 11.5f, Ui.MUTED))
                b.addView(Ui.text(this, t.value, 19f, t.color, true))
                if (t.note.isNotBlank()) b.addView(Ui.text(this, t.note, 11.5f, Ui.MUTED))
                line.addView(b, Ui.lp(0, -1, weight = 1f, end = if (i < cols - 1) Ui.dp(this, 8) else 0))
            }
            repeat(cols - row.size) { line.addView(View(this), Ui.lp(0, 1, weight = 1f)) }
            col.addView(line, Ui.lp(top = if (r > 0) Ui.dp(this, 8) else 0))
        }
        return col
    }

    private fun bar(label: String, v: Int?, max: Int, unit: String, color: Int): View {
        val r = Ui.row(this).apply { setPadding(0, Ui.dp(context, 4), 0, 0) }
        r.addView(Ui.text(this, label, 13f, Ui.MUTED), Ui.lp(Ui.dp(this, 110), -2))
        val track = LinearLayout(this)
        val frac = ((v ?: 0).toFloat() / max).coerceIn(0.02f, 1f)
        track.addView(View(this).apply { background = Ui.round(color, Ui.dp(context, 7).toFloat()) }, LinearLayout.LayoutParams(0, Ui.dp(this, 14), frac))
        track.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f - frac))
        r.addView(track, Ui.lp(0, -2, weight = 1f, end = Ui.dp(this, 8)))
        r.addView(Ui.text(this, (v?.toString() ?: "–") + if (v != null) unit else "", 13f, Ui.TEXT, true), Ui.lp(Ui.dp(this, 44), -2))
        return r
    }

    private fun tableRow(cells: List<String>, head: Boolean, click: (() -> Unit)? = null): View {
        val r = Ui.row(this).apply { setPadding(0, Ui.dp(context, 7), 0, Ui.dp(context, 7)) }
        val weights = listOf(1.3f, 1.2f, 0.9f, 0.7f, 0.8f, 0.7f)
        cells.forEachIndexed { i, s ->
            r.addView(Ui.text(this, s, 12.5f, if (head) Ui.MUTED else Ui.TEXT).apply { gravity = if (i == 0) Gravity.START else Gravity.END },
                Ui.lp(0, -2, weight = weights[i]))
        }
        click?.let { cl -> r.setOnClickListener { cl() } }
        return r
    }
}
