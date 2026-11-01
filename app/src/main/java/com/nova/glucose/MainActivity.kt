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
    }

    override fun onPause() {
        MonitorService.listeners -= listener
        ui.removeCallbacks(clock)
        super.onPause()
    }

    private val clock = object : Runnable {
        override fun run() { renderAgo(); ui.postDelayed(this, 30_000) }
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
        gc.addView(Ui.text(this, "Last 12 hours", 13f, Ui.MUTED))
        graph = GraphView(this)
        gc.addView(graph, Ui.lp(top = Ui.dp(this, 6)))
        root.addView(gc, Ui.lp(top = Ui.dp(this, 12)))

        // actions
        root.addView(Ui.button(this, "📷  Carbs from photo / video", true) {
            startActivity(Intent(this, CarbActivity::class.java))
        }, Ui.lp(top = Ui.dp(this, 12)))
        val ar = Ui.row(this)
        ar.addView(Ui.button(this, "✦ Ask AI for food ideas") { startActivity(Intent(this, AskActivity::class.java)) },
            Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        ar.addView(Ui.button(this, "📒 Logbook") { startActivity(Intent(this, LogbookActivity::class.java)) },
            Ui.lp(0, weight = 0.7f))
        root.addView(ar, Ui.lp(top = Ui.dp(this, 8)))

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

        val t0 = System.currentTimeMillis() - 12 * 3600_000L
        val pts = Db.get(this).readings(t0).ifEmpty { snap?.graph ?: emptyList() }
        graph.low = snap?.targetLow ?: st.low; graph.high = snap?.targetHigh ?: st.high
        graph.lowLine = st.low; graph.highLine = st.high
        graph.meals = Db.get(this).meals(30).map { it.ts }
        graph.readings = pts

        renderAdvice(r)
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
