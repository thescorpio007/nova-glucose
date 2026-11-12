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
    private lateinit var glucoseCard: LinearLayout
    private var cardTexts: List<OutlineText> = emptyList()
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
    private lateinit var msgBtn: TextView

    /** 💬 Messages: red with the number of unread messages. */
    private lateinit var meetBtn: TextView
    private lateinit var meetStrip: TextView
    private val MEET = 0xFF9646C8.toInt()

    /** 📳 button: off → confirm 1 hour (15 min on the patient's phone); on → end now. */
    private fun meetingTap() {
        if (Meeting.on(st)) {
            Tr.Builder(this).setTitle("📳 " + L.s("End meeting mode?")).setMessage(L.s("Nova Glucose sounds come back on this phone now."))
                .setPositiveButton("End now") { _, _ -> st.meetingUntil = 0L; renderMeeting() }.setNegativeButton("Cancel", null).show()
            return
        }
        val min = Meeting.minutesFor(st)
        val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis() + min * 60_000L))
        Tr.Builder(this).setTitle("📳 " + L.s("Meeting mode — {0} min").replace("{0}", "$min"))
            .setMessage(L.s("All Nova Glucose sounds on this phone are replaced by very strong vibration until {0}.").replace("{0}", until) + "\n\n" +
                L.s("Urgent alarms still show full screen and vibrate at full strength, with no sound. Other phones are not changed. It ends by itself, or tap 📳 again."))
            .setPositiveButton("Start meeting mode") { _, _ -> st.meetingUntil = System.currentTimeMillis() + min * 60_000L; Meeting.strong(this, 1); renderMeeting() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renderMeeting() {
        if (!::meetBtn.isInitialized) return
        val on = Meeting.on(st)
        meetBtn.text = if (on) "📳 ${Meeting.minutesLeft(st)} min" else "📳"
        meetBtn.background = Ui.round(if (on) MEET else Ui.CARD2, Ui.dp(this, 12).toFloat())
        meetBtn.setTextColor(if (on) 0xFFFFFFFF.toInt() else Ui.TEXT)
        meetStrip.visibility = if (on) View.VISIBLE else View.GONE
        if (on) meetStrip.text = L.s("📳 Meeting mode · vibration only until {0} · tap to end")
            .replace("{0}", java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(st.meetingUntil)))
    }

    private fun renderMsgBtn() {
        if (!::msgBtn.isInitialized) return
        val on = st.syncFam.isNotBlank()
        msgBtn.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        val n = Chat.unread(this).size
        msgBtn.text = "💬 " + L.s("Messages") + if (n > 0) "  $n" else ""
        msgBtn.background = Ui.round(if (n > 0) MealToggle.RED else Ui.CARD2, Ui.dp(this, 18).toFloat())
        msgBtn.setTextColor(if (n > 0) 0xFFFFFFFF.toInt() else Ui.TEXT)
        msgBtn.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0)
    }
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
                runOnUiThread { Tr.Builder(this).setTitle("Backup self-test").setMessage(r.replace(" · ", "\n")).setPositiveButton("OK", null).show() }
            }.start()
        }
        if (intent?.getBooleanExtra("synctest", false) == true && SelfTest.canRun(this)) {
            val app = applicationContext
            Thread {
                val r = SyncTest.run(app)
                android.util.Log.i("NOVA_SYNCTEST", r)
                runOnUiThread { Tr.Builder(this).setTitle("Family sync self-test").setMessage(r.replace(" · ", "\n")).setPositiveButton("OK", null).show() }
            }.start()
        }
        intent?.getStringExtra("updtest")?.let { tag ->                  // automatic test: this build's release passes the update checks
            val app = applicationContext
            Thread { android.util.Log.i("NOVA_UPDTEST", Updater.selfTest(app, tag)) }.start()
        }
        intent?.getStringExtra("lang")?.let { lg ->                    // used by the automatic screenshots
            if (lg != st.lang) { st.lang = lg; L.set(this, lg); startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        }
        if (intent?.getBooleanExtra("demo", false) == true && SelfTest.canRun(this)) Demo.fill(this)
        if (intent?.getBooleanExtra("demowatch", false) == true && SelfTest.canRun(this)) Demo.watch(this)
        if (intent?.getBooleanExtra("demoreport", false) == true) {            // automatic test: both doctor reports as files
            val app = applicationContext
            Thread {
                val out = getExternalFilesDir(null)
                for (m in listOf("sum", "key")) try {
                    val f = DoctorReport.make(app, 14, m)
                    if (out != null) f.copyTo(java.io.File(out, "report-$m.pdf"), overwrite = true)
                    if (android.os.Build.VERSION.SDK_INT >= 29) {         // readable by the test in Download/
                        val v = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "nova-report-$m.pdf")
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf") }
                        contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)?.let { u ->
                            contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } } }
                    }
                    android.util.Log.i("NOVA_REPORT", "$m OK ${f.length()} bytes")
                } catch (e: Exception) { android.util.Log.i("NOVA_REPORT", "$m FAIL ${e.message}") }
            }.start()
        }
        build()
        if (intent?.getBooleanExtra("demoinc", false) == true)
            Db.get(this).incidents().firstOrNull()?.let { IncidentActivity.open(this, it.id) }
        if (intent?.getBooleanExtra("demopick", false) == true) window.decorView.postDelayed({ DoctorReport.pick(this) }, 1200)
        if (intent?.getBooleanExtra("demotap", false) == true)
            Db.get(this).meals(1).firstOrNull()?.let { m -> window.decorView.postDelayed({ MarkHistory.show(this, m.ts) }, 1500) }
        if (intent?.getBooleanExtra("openchat", false) == true) window.decorView.postDelayed({ Chat.show(this) { renderMsgBtn() } }, 600)
        if (intent?.getBooleanExtra("demopast", false) == true) window.decorView.postDelayed({ PastEntry.show(this) { } }, 1200)
        if (intent?.getBooleanExtra("democorr", false) == true)       // automatic test: the combined editor on a correction
            Db.get(this).doses().firstOrNull { it.correction }?.let { d -> window.decorView.postDelayed({ EditEntry.open(this, d.id, null) { } }, 1200) }
        if (intent?.getBooleanExtra("demounk", false) == true)
            Db.get(this).meals(50).firstOrNull { !it.known }?.let { m -> window.decorView.postDelayed({ EditEntry.open(this, null, m.id) { } }, 1200) }
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
        graph.showCard()
        FamilySync.kick(this)
        if (st.keepScreen) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { HealthRead.maybe(this) } catch (_: Exception) { }
        try { Updater.maybe(this); Updater.offerOnOpen(this) } catch (_: Exception) { }
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
        // 📳 meeting mode (replaces ↻ — refresh: tap the glucose card; it also refreshes by itself every minute)
        meetBtn = Ui.smallButton(this, "📳") { meetingTap() }
        head.addView(meetBtn, Ui.lp(-2, Ui.dp(this, 36), end = Ui.dp(this, 8)))
        lateinit var langBtn: android.widget.TextView
        langBtn = Ui.smallButton(this, "🌐 ${L.short()} ▾") { Lang.pick(this, langBtn) }
        head.addView(langBtn, Ui.lp(-2, Ui.dp(this, 36), end = Ui.dp(this, 8)))
        head.addView(Ui.smallButton(this, "⚙") { startActivity(Intent(this, SettingsActivity::class.java)) }, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 36)))
        root.addView(head)

        // First start: who uses this phone? (patient or family) — until chosen
        if (st.role.isBlank()) {
            val rc = Ui.card(this).apply { background = Ui.round(Ui.CARD, Ui.dp(c(), 16).toFloat(), Ui.dp(c(), 1), Ui.ACCENT) }
            rc.addView(Ui.text(c(), "💙 Who uses this phone?", 18f, Ui.TEXT, true))
            rc.addView(Ui.text(c(), "Everything is set up for that person. You can change it later in Settings → This phone.", 13f, Ui.MUTED))
            fun choice(id: String, title: String, sub: String) = Ui.col(c(), 14).apply {
                background = Ui.round(Ui.CARD2, Ui.dp(c(), 14).toFloat(), Ui.dp(c(), 1), 0x553A4152)
                addView(Ui.text(c(), title, 16f, Ui.TEXT, true)); addView(Ui.text(c(), sub, 12.5f, Ui.MUTED))
                setOnClickListener {
                    st.role = id; if (st.syncName.isBlank()) st.syncName = st.roleName(id)
                    rc.visibility = View.GONE; Ui.toast(c(), st.roleName(id))
                }
            }
            rc.addView(choice("patient", "🧑 Patient's phone", "The person with diabetes uses this phone · reads their watch · the AI talks to them directly · settings can be locked with the parent PIN"),
                Ui.lp(top = Ui.dp(this, 10)))
            rc.addView(choice("family", "👨‍👩‍👧 Family phone", "Parent or caregiver · watch data comes from the patient's phone (nothing to set up) · the AI talks to you about the patient"),
                Ui.lp(top = Ui.dp(this, 8)))
            root.addView(rc, Ui.lp(top = Ui.dp(this, 12)))
        }
        folderCard = Ui.card(this).apply {
            addView(Ui.text(c(), "📁 Download/NovaGlucose folder", 17f, Ui.TEXT, true))
            folderText = Ui.text(c(), "", 14f, Ui.MUTED)
            addView(folderText)
            addView(Ui.button(c(), "Choose folder", true) { FolderFlow.pick(c()) }, Ui.lp(top = Ui.dp(c(), 10)))
        }
        root.addView(folderCard, Ui.lp(top = Ui.dp(this, 12)))

        connectCard = Ui.card(this).apply {
            addView(Ui.text(c(), "Connect LibreLinkUp", 17f, Ui.TEXT, true))
            addView(Ui.text(c(), "Sign in with the LibreLinkUp follower account you use to see the patient's readings.", 14f, Ui.MUTED))
            addView(Ui.button(c(), "Open Settings", true) { startActivity(Intent(c(), SettingsActivity::class.java)) }, Ui.lp(top = Ui.dp(c(), 10)))
        }
        root.addView(connectCard, Ui.lp(top = Ui.dp(this, 12)))

        // reading card
        // reading card: the whole card takes the colour of the situation, white text with a black outline
        val card = Ui.row(this).apply { background = Ui.round(Ui.CARD, Ui.dp(c(), 16).toFloat()) }
        glucoseCard = card
        bar = View(this)
        card.addView(bar, LinearLayout.LayoutParams(Ui.dp(this, 10), LinearLayout.LayoutParams.MATCH_PARENT))
        bar.visibility = View.GONE
        val inner = Ui.col(this, 14).apply { setPadding(Ui.dp(c(), 20), Ui.dp(c(), 14), Ui.dp(c(), 14), Ui.dp(c(), 14)) }
        fun ot(t: String, sp: Float, bold: Boolean) = OutlineText(this).apply {
            text = L.s(t); textSize = sp; setTextColor(Ui.TEXT); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val glbl = ot("Glucose", 15f, true)
        inner.addView(glbl)
        val vr = Ui.row(this).apply { gravity = Gravity.BOTTOM }
        value = ot("--", 56f, true).apply { includeFontPadding = false }
        arrow = ot("", 40f, true).apply { setPadding(Ui.dp(c(), 10), 0, 0, 0) }
        vr.addView(value); vr.addView(arrow)
        val unit = ot(" mg/dL", 16f, true)
        vr.addView(unit)
        inner.addView(vr)
        ago = ot("", 13f, true)
        inner.addView(ago)
        cardTexts = listOf(glbl, value as OutlineText, arrow as OutlineText, unit, ago as OutlineText)
        card.addView(inner, Ui.lp(0, weight = 1f))
        root.addView(card, Ui.lp(top = Ui.dp(this, 12)))
        card.isClickable = true
        card.setOnClickListener { refresh(true); Ui.toast(this, "Refreshing…") }
        meetStrip = Ui.text(this, "", 13f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER; setPadding(Ui.dp(c(), 12), Ui.dp(c(), 8), Ui.dp(c(), 12), Ui.dp(c(), 8))
            background = Ui.round(MEET, Ui.dp(c(), 14).toFloat()); visibility = View.GONE; setOnClickListener { meetingTap() } }
        root.addView(meetStrip, Ui.lp(top = Ui.dp(this, 8)))

        error = Ui.text(this, "", 13f, Ui.BAD).apply {
            background = Ui.round(0x22FF6B6B, Ui.dp(c(), 10).toFloat())
            setPadding(Ui.dp(c(), 12), Ui.dp(c(), 8), Ui.dp(c(), 12), Ui.dp(c(), 8)); visibility = View.GONE
        }
        root.addView(error, Ui.lp(top = Ui.dp(this, 8)))

        val gc = Ui.card(this)
        rangeBtn = Ui.smallButton(this, "") { pickRange() }
        val topRow = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(rangeBtn, Ui.lp(-2, Ui.dp(this, 36)))
        topRow.addView(View(this), Ui.lp(0, 1, weight = 1f))
        msgBtn = Ui.smallButton(this, "") { Chat.show(this) { renderMsgBtn() }; renderMsgBtn() }
        topRow.addView(msgBtn, Ui.lp(-2, Ui.dp(this, 36)))
        gc.addView(topRow)
        navRow = Ui.row(this).apply { setPadding(0, Ui.dp(c(), 8), 0, 0) }
        navRow.addView(Ui.smallButton(this, "‹") { offset--; renderChart() }, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 32)))
        navLabel = Ui.text(this, "", 14f, Ui.TEXT).apply { gravity = android.view.Gravity.CENTER }
        navRow.addView(navLabel, Ui.lp(0, weight = 1f))
        navRow.addView(Ui.smallButton(this, "›") { if (offset < 0) { offset++; renderChart() } }, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 32)))
        gc.addView(navRow)
        graph = GraphView(this).apply {
            onMark = { t -> MarkHistory.show(this@MainActivity, t) }
            onIncident = { id -> IncidentActivity.open(this@MainActivity, id) }
        }
        gc.addView(graph, Ui.lp(top = Ui.dp(this, 6)))
        chartLegend = Ui.text(this, "", 11f, Ui.MUTED).apply { visibility = View.GONE }   // no text under the chart
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
        val er = Ui.row(this)
        er.addView(Ui.button(this, "🕘 Earlier insulin\nor meal") { PastEntry.show(this) { render(null) } }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        er.addView(Ui.button(this, "🤒 Add incident\nsick, fever…") { IncidentActivity.open(this) }.apply { setTextColor(Incidents.COLOR) },
            Ui.lp(0, weight = 1f))
        root.addView(er, Ui.lp(top = Ui.dp(this, 8)))
        root.addView(Ui.button(this, "📷  Carbs from photo / video", true) {
            startActivity(Intent(this, CarbActivity::class.java))
        }, Ui.lp(top = Ui.dp(this, 8)))
        val ar = Ui.row(this)
        ar.addView(Ui.button(this, "✦ Ask AI — food & recipes") { startActivity(Intent(this, AskActivity::class.java)) },
            Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        ar.addView(Ui.button(this, "📒 Logbook") { startActivity(Intent(this, LogbookActivity::class.java)) },
            Ui.lp(0, weight = 0.7f))
        root.addView(ar, Ui.lp(top = Ui.dp(this, 8)))
        val wr = Ui.row(this)
        wr.addView(Ui.button(this, "⌚ Activity & sleep") { startActivity(Intent(this, HealthActivity::class.java)) }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        wr.addView(Ui.button(this, "⭐ Recipe book") { startActivity(Intent(this, RecipeBookActivity::class.java)) }, Ui.lp(0, weight = 1f))
        root.addView(wr, Ui.lp(top = Ui.dp(this, 8)))
        val dr = Ui.row(this)
        dr.addView(Ui.button(this, "📊 AI review") { startActivity(Intent(this, AiReviewActivity::class.java)) }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        dr.addView(Ui.button(this, "📤 Share with doctor") { DoctorReport.pick(this) }, Ui.lp(0, weight = 1f))
        root.addView(dr, Ui.lp(top = Ui.dp(this, 8)))

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
        val fill = when (lv) {
            Level.URGENT_LOW, Level.LOW, Level.VERY_HIGH -> 0xFFCD2828.toInt()
            Level.HIGH, Level.DROPPING -> 0xFFE67E14.toInt()
            Level.STALE -> 0xFF5A6070.toInt()
            Level.IN_RANGE -> 0xFF2E8C3E.toInt()
            Level.NO_DATA -> Ui.CARD
        }
        glucoseCard.background = Ui.round(fill, Ui.dp(this, 16).toFloat())
        val strong = lv != Level.NO_DATA
        cardTexts.forEach { t -> t.outline = if (strong) Ui.dp(this, 3).toFloat() * (if (t === value) 1.6f else 1f) else 0f
            t.setTextColor(if (strong) 0xFFFFFFFF.toInt() else if (t === value || t === arrow) Ui.TEXT else Ui.MUTED); t.invalidate() }
        renderAgo()

        if (err.isNullOrBlank() || !st.loggedIn) error.visibility = View.GONE
        else { error.text = err; error.visibility = View.VISIBLE }

        renderChart()

        renderAdvice(r)
        renderMsgBtn()
        renderMeeting()
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
        val df = java.text.SimpleDateFormat("EEE d MMM", L.loc())
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        return when (st.chartRange) {
            "24" -> { cal.add(java.util.Calendar.DAY_OF_YEAR, offset); val a = cal.timeInMillis
                Triple(a, a + 24 * 3600_000L - 1, if (offset == 0) "Today · ${df.format(java.util.Date(a))}" else df.format(java.util.Date(a))) }
            "w" -> { cal.add(java.util.Calendar.DAY_OF_YEAR, -6 + offset * 7); val a = cal.timeInMillis; val b = a + 7 * 24 * 3600_000L - 1
                Triple(a, b, "${java.text.SimpleDateFormat("d MMM", L.loc()).format(java.util.Date(a))} – ${java.text.SimpleDateFormat("d MMM", L.loc()).format(java.util.Date(b))}") }
            "m" -> { cal.set(java.util.Calendar.DAY_OF_MONTH, 1); cal.add(java.util.Calendar.MONTH, offset); val a = cal.timeInMillis
                cal.add(java.util.Calendar.MONTH, 1)
                Triple(a, cal.timeInMillis - 1, java.text.SimpleDateFormat("MMMM yyyy", L.loc()).format(java.util.Date(a))) }
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
        graph.lowLine = st.low; graph.highLine = st.high; graph.urgentLine = st.urgentLow; graph.veryHighLine = st.veryHigh
        graph.meals = db.meals(500).map { it.ts }
        val allDoses = db.doses(a, b)
        graph.doses = allDoses.filter { it.kind == "rapid" }.map { it.ts }
        graph.longs = allDoses.filter { it.kind == "long" }.map { it.ts }
        graph.incidents = db.incidents(a, b)
        graph.set(mode, a, b, pts)
        chartLegend.text = when (mode) {
            "12" -> MarkHistory.legend("Every hour labelled · tap a mark · ")
            "24" -> MarkHistory.legend("Full day · ‹ › other days · tap a mark · ")
            "w" -> MarkHistory.legend("All readings of the 7 days · ‹ › other weeks · tap a mark · ")
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
