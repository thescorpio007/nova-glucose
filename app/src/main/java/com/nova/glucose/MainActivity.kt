package com.nova.glucose

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : BaseActivity() {
    private lateinit var st: Store
    private lateinit var root: LinearLayout
    private lateinit var name: TextView
    private lateinit var bar: View
    private lateinit var value: TextView
    private lateinit var arrow: TextView
    private lateinit var ago: TextView
    private lateinit var error: TextView
    private lateinit var graph: GraphView
    private lateinit var advice: LinearLayout
    private lateinit var connectCard: LinearLayout
    private lateinit var folderCard: LinearLayout
    private lateinit var eatLine: TextView
    private lateinit var rangeBtn: TextView
    private lateinit var navRow: LinearLayout
    private lateinit var navLabel: TextView
    private lateinit var chartLegend: TextView
    private lateinit var tirBox: Pair<LinearLayout, TextView>
    private lateinit var avgBox: Pair<LinearLayout, TextView>
    private lateinit var periodExtra: TextView
    private lateinit var gmiCard: LinearLayout
    private var offset = 0
    private lateinit var folderText: TextView
    private var snap: Snapshot? = null
    private val ui = Handler(Looper.getMainLooper())

    private val listener: (Snapshot?, String?) -> Unit = { s, e ->
        if (s != null) snap = s
        render(e)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        MonitorService.channels(this)
        if (intent?.getBooleanExtra("selftest", false) == true && SelfTest.canRun(this)) {
            val app = applicationContext
            Thread {
                val r = SelfTest.run(app)
                android.util.Log.i("NOVA_SELFTEST", r)
                runOnUiThread { android.app.AlertDialog.Builder(this).setTitle("Backup self-test").setMessage(r.replace(" · ", "\n")).setPositiveButton("OK", null).show() }
            }.start()
        }
        if (intent?.getBooleanExtra("synctest", false) == true && SelfTest.canRun(this)) {
            val app = applicationContext
            Thread {
                val r = SyncTest.run(app)
                android.util.Log.i("NOVA_SYNCTEST", r)
                runOnUiThread { android.app.AlertDialog.Builder(this).setTitle("Family sync self-test").setMessage(r.replace(" · ", "\n")).setPositiveButton("OK", null).show() }
            }.start()
        }
        build()
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        MonitorService.listeners += listener
        snap = MonitorService.latest ?: snap
        render(st.lastError.ifBlank { null })
        refresh()
        ui.post(clock)
        FamilySync.listener = { ui.post { render(st.lastError.ifBlank { null }) } }
        FamilySync.kick(this)
    }

    override fun onPause() {
        MonitorService.listeners -= listener
        FamilySync.listener = null
        ui.removeCallbacks(clock)
        super.onPause()
    }

    private val clock = object : Runnable {
        override fun run() { renderAgo(); renderEat(); ui.postDelayed(this, 1_000) }
    }

    private fun c() = this

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        FolderFlow.onResult(this, req, res, data) { render(null); refresh() }
    }

    @SuppressLint("SetTextI18n")
    private fun build() {
        root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }

        val head = Ui.row(this)
        val titles = Ui.col(this)
        titles.addView(Ui.title(this, "Nova Glucose"))
        name = Ui.text(this, "", 14f, Ui.MUTED)
        titles.addView(name)
        head.addView(titles, Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "↻") { refresh(true) }, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 36), end = Ui.dp(this, 8)))
        head.addView(Ui.smallButton(this, "⚙ Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(head)

        folderCard = Ui.card(this).apply {
            addView(Ui.text(c(), "📁 Download/NovaGlucose folder", 17f, Ui.TEXT, true))
            folderText = Ui.text(c(), "", 14f, Ui.MUTED)
            addView(folderText)
            addView(Ui.button(c(), "Choose folder", true) { FolderFlow.pick(c()) }, Ui.lp(top = Ui.dp(c(), 10)))
        }
        root.addView(folderCard, Ui.lp(top = Ui.dp(this, 12)))

        connectCard = Ui.card(this).apply {
            addView(Ui.text(c(), "Connect LibreLinkUp", 17f, Ui.TEXT, true))
            addView(Ui.text(c(), "Sign in with the LibreLinkUp follower account you use to see her readings.", 14f, Ui.MUTED))
            addView(Ui.button(c(), "Open Settings", true) { startActivity(Intent(c(), SettingsActivity::class.java)) }, Ui.lp(top = Ui.dp(c(), 10)))
        }
        root.addView(connectCard, Ui.lp(top = Ui.dp(this, 12)))

        // reading card
        val card = Ui.row(this).apply { background = Ui.round(Ui.CARD, Ui.dp(c(), 16).toFloat()) }
        bar = View(this)
        card.addView(bar, LinearLayout.LayoutParams(Ui.dp(this, 10), LinearLayout.LayoutParams.MATCH_PARENT))
        val inner = Ui.col(this, 14)
        inner.addView(Ui.text(this, "Glucose", 15f, Ui.MUTED))
        val vr = Ui.row(this).apply { gravity = Gravity.BOTTOM }
        value = Ui.text(this, "--", 56f, Ui.TEXT, true).apply { includeFontPadding = false }
        arrow = Ui.text(this, "", 40f, Ui.TEXT, true).apply { setPadding(Ui.dp(c(), 8), 0, 0, 0) }
        vr.addView(value); vr.addView(arrow)
        vr.addView(Ui.text(this, " mg/dL", 16f, Ui.MUTED))
        inner.addView(vr)
        ago = Ui.text(this, "", 13f, Ui.MUTED)
        inner.addView(ago)
        card.addView(inner, Ui.lp(0, weight = 1f))
        root.addView(card, Ui.lp(top = Ui.dp(this, 12)))

        error = Ui.text(this, "", 13f, Ui.BAD).apply {
            background = Ui.round(0x22FF6B6B, Ui.dp(c(), 10).toFloat())
            setPadding(Ui.dp(c(), 12), Ui.dp(c(), 8), Ui.dp(c(), 12), Ui.dp(c(), 8)); visibility = View.GONE
        }
        root.addView(error, Ui.lp(top = Ui.dp(this, 8)))

        val gc = Ui.card(this)
        rangeBtn = Ui.smallButton(this, "") { pickRange() }
        gc.addView(rangeBtn, Ui.lp(-2, Ui.dp(this, 36)))
        navRow = Ui.row(this).apply { setPadding(0, Ui.dp(c(), 8), 0, 0) }
        navRow.addView(Ui.smallButton(this, "‹") { offset--; renderChart() }, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 32)))
        navLabel = Ui.text(this, "", 14f, Ui.TEXT).apply { gravity = android.view.Gravity.CENTER }
        navRow.addView(navLabel, Ui.lp(0, weight = 1f))
        navRow.addView(Ui.smallButton(this, "›") { if (offset < 0) { offset++; renderChart() } }, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 32)))
        gc.addView(navRow)
        graph = GraphView(this)
        gc.addView(graph, Ui.lp(top = Ui.dp(this, 6)))
        chartLegend = Ui.text(this, "", 11f, Ui.MUTED)
        gc.addView(chartLegend)
        val sr = Ui.row(this).apply { setPadding(0, Ui.dp(c(), 10), 0, 0) }
        tirBox = statBox("Time in range"); avgBox = statBox("Average")
        sr.addView(tirBox.first, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8))); sr.addView(avgBox.first, Ui.lp(0, weight = 1f))
        gc.addView(sr)
        periodExtra = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(c(), 6), 0, 0) }
        gc.addView(periodExtra)
        gmiCard = Ui.col(this, 12).apply {
            background = Ui.round(Ui.CARD2, Ui.dp(c(), 12).toFloat(), Ui.dp(c(), 1), 0x552EC4B6)
        }
        gc.addView(gmiCard, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(gc, Ui.lp(top = Ui.dp(this, 12)))

        // actions
        eatLine = Ui.text(this, "", 16f, Ui.GOOD, true).apply {
            background = Ui.round(0x223DDC97, Ui.dp(c(), 12).toFloat())
            setPadding(Ui.dp(c(), 14), Ui.dp(c(), 10), Ui.dp(c(), 14), Ui.dp(c(), 10)); visibility = View.GONE
            setOnClickListener { startActivity(Intent(c(), InsulinActivity::class.java)) }
        }
        root.addView(eatLine, Ui.lp(top = Ui.dp(this, 12)))
        root.addView(Ui.button(this, "💉  Rapid insulin before meal", true) {
            startActivity(Intent(this, InsulinActivity::class.java))
        }, Ui.lp(top = Ui.dp(this, 12)))
        root.addView(Ui.button(this, "🕘  Add earlier injection or meal") { PastEntry.show(this) { render(null) } },
            Ui.lp(top = Ui.dp(this, 8)))
        root.addView(Ui.button(this, "📷  Carbs from photo / video", true) {
            startActivity(Intent(this, CarbActivity::class.java))
        }, Ui.lp(top = Ui.dp(this, 8)))
        val ar = Ui.row(this)
        ar.addView(Ui.button(this, "✦ Ask AI for food ideas") { startActivity(Intent(this, AskActivity::class.java)) },
            Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        ar.addView(Ui.button(this, "📒 Logbook") { startActivity(Intent(this, LogbookActivity::class.java)) },
            Ui.lp(0, weight = 0.7f))
        root.addView(ar, Ui.lp(top = Ui.dp(this, 8)))
        root.addView(Ui.button(this, "📤 Share with doctor") { DoctorReport.pick(this) }, Ui.lp(top = Ui.dp(this, 8)))

        advice = Ui.card(this)
        root.addView(advice, Ui.lp(top = Ui.dp(this, 12)))

        root.addView(Ui.signature(this))
        setContentView(sv)
    }

    private fun current(): Reading? =
        snap?.latest ?: if (st.lastTs > 0) Reading(st.lastTs, st.lastMg, st.lastTrend) else null

    @SuppressLint("SetTextI18n")
    private fun render(err: String?) {
        connectCard.visibility = if (st.loggedIn) View.GONE else View.VISIBLE
        val folderOk = Vault.ready(this)
        folderCard.visibility = if (folderOk) View.GONE else View.VISIBLE
        folderText.text = if (st.loggedIn) "Choose it once so readings, meals, alerts, daily assessments and the backup are kept safe in Download."
            else "Reinstalled the app? Choose Download/NovaGlucose to resume everything from your backup. New here? Choose it to keep all data safe."
        val r = current()
        name.text = st.patientName.ifBlank { if (st.loggedIn) "Loading…" else "Not connected" }
        value.text = r?.mg?.toString() ?: "--"
        arrow.text = r?.let { Libre.trendArrow(it.trend) } ?: ""
        val lv = Foods.level(st, r)
        val col = when (lv) {
            Level.URGENT_LOW, Level.LOW, Level.VERY_HIGH -> Ui.BAD
            Level.HIGH, Level.DROPPING, Level.STALE -> Ui.WARN
            Level.IN_RANGE -> Ui.IN_RANGE
            Level.NO_DATA -> Ui.MUTED
        }
        bar.background = Ui.round(col, Ui.dp(this, 4).toFloat())
        value.setTextColor(if (lv == Level.STALE) Ui.MUTED else Ui.TEXT)
        renderAgo()

        if (err.isNullOrBlank() || !st.loggedIn) error.visibility = View.GONE
        else { error.text = err; error.visibility = View.VISIBLE }

        renderChart()

        renderAdvice(r)
    }

    private val ranges = listOf("12" to "Last 12 hours", "24" to "24 hours (full day)", "w" to "Week", "m" to "Month")

    private fun statBox(label: String): Pair<LinearLayout, TextView> {
        val b = Ui.col(this, 10).apply { background = Ui.round(Ui.CARD2, Ui.dp(c(), 10).toFloat()) }
        b.addView(Ui.text(this, label, 12f, Ui.MUTED))
        val v = Ui.text(this, "–", 19f, Ui.TEXT, true); b.addView(v)
        return b to v
    }

    private fun pickRange() {
        val pm = android.widget.PopupMenu(this, rangeBtn)
        ranges.forEachIndexed { k, (id, label) -> pm.menu.add(0, k, k, (if (id == st.chartRange) "✓  " else "     ") + label) }
        pm.setOnMenuItemClickListener { st.chartRange = ranges[it.itemId].first; offset = 0; renderChart(); true }
        pm.show()
    }

    /** Start and end of the period shown, and its title. */
    private fun period(): Triple<Long, Long, String> {
        val now = System.currentTimeMillis()
        val df = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        return when (st.chartRange) {
            "24" -> { cal.add(java.util.Calendar.DAY_OF_YEAR, offset); val a = cal.timeInMillis
                Triple(a, a + 24 * 3600_000L - 1, if (offset == 0) "Today · ${df.format(java.util.Date(a))}" else df.format(java.util.Date(a))) }
            "w" -> { cal.add(java.util.Calendar.DAY_OF_YEAR, -6 + offset * 7); val a = cal.timeInMillis; val b = a + 7 * 24 * 3600_000L - 1
                Triple(a, b, "${java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.util.Date(a))} – ${java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.util.Date(b))}") }
            "m" -> { cal.set(java.util.Calendar.DAY_OF_MONTH, 1); cal.add(java.util.Calendar.MONTH, offset); val a = cal.timeInMillis
                cal.add(java.util.Calendar.MONTH, 1)
                Triple(a, cal.timeInMillis - 1, java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US).format(java.util.Date(a))) }
            else -> Triple(now - 12 * 3600_000L, now, "")
        }
    }

    private fun renderChart() {
        val mode = st.chartRange
        rangeBtn.text = (ranges.firstOrNull { it.first == mode }?.second ?: "Last 12 hours") + "  ▾"
        val (a, b, title) = period()
        navRow.visibility = if (mode == "12") View.GONE else View.VISIBLE
        navLabel.text = title
        val db = Db.get(this)
        val pts = db.readings(a, b).ifEmpty { if (mode == "12") snap?.graph ?: emptyList() else emptyList() }
        graph.low = snap?.targetLow ?: st.low; graph.high = snap?.targetHigh ?: st.high
        graph.lowLine = st.low; graph.highLine = st.high
        graph.meals = db.meals(500).map { it.ts }
        graph.doses = db.doses(a, b).filter { it.kind == "rapid" }.map { it.ts }
        graph.set(mode, a, b, pts)
        chartLegend.text = when (mode) {
            "12" -> "Every hour labelled · M = meal · I = rapid insulin"
            "24" -> "Full day · ‹ › other days · M = meal · I = rapid insulin"
            "w" -> "All readings of the 7 days · ‹ › other weeks"
            else -> "Line = daily average · band = lowest to highest · ‹ › other months"
        }
        val s = Vault.stats(st, pts)
        tirBox.second.text = s?.let { "${it.inRange}%" } ?: "–"
        tirBox.second.setTextColor(when { s == null -> Ui.MUTED; s.inRange >= 70 -> Ui.GOOD; s.inRange >= 50 -> Ui.WARN; else -> Ui.BAD })
        avgBox.second.text = s?.let { "${it.avg} mg/dL" } ?: "–"
        periodExtra.text = s?.let { "Below ${st.low}: ${it.below}% · above ${st.high}: ${it.above}% · low episodes: ${it.lowEpisodes} · lowest ${it.min} · highest ${it.max}" } ?: ""
        renderGmi()
    }

    private fun renderGmi() {
        gmiCard.removeAllViews()
        gmiCard.addView(Ui.text(this, "Estimated HbA1c (GMI)", 12f, Ui.MUTED))
        val now = System.currentTimeMillis()
        val db = Db.get(this)
        fun est(days: Int): Pair<DayStats?, Double> {
            val rs = db.readings(now - days * 24 * 3600_000L)
            val s = Vault.stats(st, rs)
            return s to (s?.count ?: 0) / 288.0      // days of data (5-minute samples)
        }
        val (s14, d14) = est(14)
        if (s14 == null) {
            gmiCard.addView(Ui.text(this, "–", 22f, Ui.MUTED, true))
            gmiCard.addView(Ui.text(this, "Needs 14 days of readings.", 12f, Ui.MUTED)); return
        }
        val enough = d14 >= 14 * 0.7
        gmiCard.addView(Ui.text(this, "%.1f%%".format(java.util.Locale.US, s14.gmi), 24f, if (enough) Ui.ACCENT else Ui.MUTED, true))
        val (s30, d30) = est(30)
        gmiCard.addView(Ui.text(this, if (enough) "Last 14 days · average ${s14.avg} mg/dL" +
                (if (s30 != null && d30 >= 21) " · 30 days: %.1f%%".format(java.util.Locale.US, s30.gmi) else "")
            else "Partial estimate: %.1f of 14 days so far — needs %d more day(s).".format(java.util.Locale.US, d14, kotlin.math.ceil(14 * 0.7 - d14).toInt().coerceAtLeast(1)),
            12f, Ui.TEXT))
        gmiCard.addView(Ui.text(this, "From sensor readings, not a lab test (can differ by about ±0.5%).", 11f, Ui.MUTED))
    }

    private fun renderEat() {
        val left = st.eatAt - System.currentTimeMillis()
        if (st.eatAt == 0L || left <= 0) { eatLine.visibility = View.GONE; return }
        eatLine.visibility = View.VISIBLE
        eatLine.text = "⏱ Eat in %d:%02d — notification when it's time".format(left / 60_000, (left / 1000) % 60)
    }

    private fun renderAgo() {
        val r = current() ?: run { ago.text = ""; return }
        val m = ((System.currentTimeMillis() - r.ts) / 60_000).toInt()
        ago.text = (if (m <= 0) "Just now" else "$m min ago") + " · " + Libre.trendText(r.trend)
    }

    private fun renderAdvice(r: Reading?) {
        advice.removeAllViews()
        val a = Foods.advise(st, r)
        advice.addView(Ui.text(this, "What to eat now", 12f, Ui.MUTED, true).apply { letterSpacing = 0.06f })
        advice.addView(Ui.text(this, a.title, 20f, a.color, true))
        advice.addView(Ui.text(this, a.message, 14f, Ui.TEXT))
        a.steps.forEach { advice.addView(Ui.text(this, "•  $it", 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(c(), 4), 0, 0) }) }
        if (a.foods.isNotEmpty()) {
            advice.addView(Ui.section(this, a.foodsTitle))
            a.foods.forEach { f ->
                val row = Ui.row(this).apply {
                    background = Ui.round(Ui.CARD2, Ui.dp(c(), 12).toFloat())
                    setPadding(Ui.dp(c(), 12), Ui.dp(c(), 10), Ui.dp(c(), 12), Ui.dp(c(), 10))
                }
                val left = Ui.col(this)
                left.addView(Ui.text(this, f.name, 15f, Ui.TEXT, true))
                left.addView(Ui.text(this, "${f.portion}  ·  ${f.style}", 12f, Ui.MUTED))
                row.addView(left, Ui.lp(0, weight = 1f))
                row.addView(Ui.text(this, f.carbs, 14f, Ui.ACCENT, true))
                advice.addView(row, Ui.lp(top = Ui.dp(this, 6)))
            }
        }
        if (st.loggedIn) advice.addView(Ui.button(this, "✦ More ideas from AI") {
            startActivity(Intent(this, AskActivity::class.java))
        }, Ui.lp(top = Ui.dp(this, 10)))
        advice.addView(Ui.text(this, Foods.CARE_NOTE, 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(c(), 10), 0, 0) })
    }

    private fun refresh(now: Boolean = false) {
        if (!st.loggedIn) { render(null); return }
        if (st.monitorOn) {
            MonitorService.start(this)
            if (now) try { startService(Intent(this, MonitorService::class.java).putExtra("now", true)) } catch (_: Exception) { }
        } else {
            Thread {
                try {
                    val s = Libre(st).fetch()
                    Db.get(this).addReadings(s.graph)
                    s.latest?.let { st.lastMg = it.mg; st.lastTrend = it.trend; st.lastTs = it.ts }
                    st.lastError = ""
                    runOnUiThread { snap = s; render(null) }
                } catch (e: Exception) {
                    st.lastError = e.message ?: "error"
                    runOnUiThread { render(st.lastError) }
                }
            }.start()
        }
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (Build.VERSION.SDK_INT >= 23 && st.loggedIn) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) try {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(i)
            } catch (_: Exception) { }
        }
    }
}
