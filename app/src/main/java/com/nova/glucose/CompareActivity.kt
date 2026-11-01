package com.nova.glucose

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compare models: every usable model of the chosen AI is timed on the same test —
 * a fixed meal with a known carb count, plus her last saved meal photo (scored against the family's count).
 * The best balance (fastest within 1 point of the top score) gets a star; tap a model to use it.
 */
class CompareActivity : BaseActivity() {
    private data class Res(val model: String, var ms: Long = 0, var score: Double? = null, var err: String? = null, var detail: String = "")

    private lateinit var st: Store
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var table: LinearLayout
    private var provider: Provider? = null
    private val results = mutableListOf<Res>()
    @Volatile private var running = false

    private val fixedMeal = "1 cup (160 g) cooked white rice, 1 medium banana, a 200 ml glass of orange juice, " +
        "100 g grilled chicken breast, a small green salad with olive oil."
    private val fixedTotal = 93.0   // 45 + 27 + 21 + 0 + 0

    private val system = "You count carbohydrates for a family managing diabetes. Never suggest insulin. " +
        "Answer with ONLY JSON: {\"total_g\": number}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        val root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "Compare models"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        root.addView(Ui.text(this, "Each model gets the same test: a fixed meal (known ≈ 93 g carbs) and, if saved, her last meal photo " +
            "scored against your count. Score 10 = exact. ★ = best balance of accuracy and speed. Tap a model to use it.", 13f, Ui.MUTED))
        val (csv, row) = Ui.chipRow(this)
        chips = row
        root.addView(csv, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.button(this, "▶ Test all models", true) { run() }, Ui.lp(top = Ui.dp(this, 10)))
        status = Ui.text(this, "", 13f, Ui.MUTED)
        root.addView(status, Ui.lp(top = Ui.dp(this, 8)))
        table = Ui.card(this)
        root.addView(table, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.signature(this))
        setContentView(sv)
        provider = Providers.all.firstOrNull { it.id == st.aiProvider && st.key(it).isNotBlank() } ?: Providers.all.firstOrNull { st.key(it).isNotBlank() }
        renderChips(); render()
    }

    private fun renderChips() {
        chips.removeAllViews()
        Providers.all.forEach { p ->
            val has = st.key(p).isNotBlank()
            chips.addView(Ui.chip(this, p.label + if (has) "" else " · no key", provider == p) {
                if (!has) { Ui.toast(this, "Add the ${p.label} key in Settings first"); return@chip }
                if (running) return@chip
                provider = p; results.clear(); renderChips(); render()
            }, Ui.lp(-2, -2, end = Ui.dp(this, 8)))
        }
    }

    private fun lastPhoto(): Pair<ByteArray, Int>? {
        val m = Db.get(this).meals(30).firstOrNull { it.photo.isNotBlank() && File(it.photo).exists() && it.carbs > 0 } ?: return null
        return File(m.photo).readBytes() to m.carbs
    }

    private fun total(ans: String): Double? {
        val a = ans.indexOf('{'); val b = ans.lastIndexOf('}')
        if (a >= 0 && b > a) try { return JSONObject(ans.substring(a, b + 1)).optDouble("total_g").takeIf { !it.isNaN() } } catch (_: Exception) { }
        return Regex("(\\d+(?:\\.\\d+)?)").find(ans)?.value?.toDoubleOrNull()
    }

    private fun score(got: Double?, truth: Double): Double =
        if (got == null) 0.0 else (10 - abs(got - truth) / truth * 20).coerceIn(0.0, 10.0)

    private fun run() {
        val p = provider ?: run { Ui.toast(this, "Add an API key in Settings first"); return }
        if (running) return
        running = true; results.clear(); render()
        status.text = "Listing ${p.label} models…"
        val photo = lastPhoto()
        Thread {
            val models = try { Providers.ranked(p, Providers.listModels(p, st.key(p))) } catch (e: Exception) {
                runOnUiThread { status.text = e.message; running = false }; return@Thread
            }
            runOnUiThread { results.addAll(models.take(15).map { Res(it) }); render() }
            val todo = models.take(15)
            for ((k, r) in results.toList().withIndex()) {
                runOnUiThread { status.text = "Testing ${k + 1}/${todo.size}: ${r.model}…" }
                val t = System.currentTimeMillis()
                try {
                    val a1 = Providers.ask(st, p, system, "How many grams of carbohydrate in total? $fixedMeal", only = r.model)
                    val g1 = total(a1)
                    var sc = score(g1, fixedTotal)
                    r.detail = "meal ${g1?.roundToInt() ?: "?"} g"
                    if (photo != null) {
                        val a2 = Providers.ask(st, p, system, "Estimate the total grams of carbohydrate in this meal photo.",
                            listOf(photo.first), only = r.model)
                        val g2 = total(a2)
                        sc = (sc + score(g2, photo.second.toDouble())) / 2
                        r.detail += " · photo ${g2?.roundToInt() ?: "?"} g (you: ${photo.second})"
                    }
                    r.score = sc
                } catch (e: Exception) { r.err = e.message ?: "Failed" }
                r.ms = System.currentTimeMillis() - t
                runOnUiThread { render() }
            }
            runOnUiThread { status.text = "Done. Tap a model to use it."; running = false; render() }
        }.start()
    }

    private fun best(): String? {
        val ok = results.filter { it.score != null }
        val top = ok.maxOfOrNull { it.score!! } ?: return null
        return ok.filter { it.score!! >= top - 1 }.minByOrNull { it.ms }?.model
    }

    private fun render() {
        table.removeAllViews()
        val p = provider
        if (p == null) { table.addView(Ui.text(this, "Add an API key in Settings to compare models.", 14f, Ui.MUTED)); return }
        table.addView(Ui.text(this, "${p.label} · in use: ${st.model(p)}", 13f, Ui.MUTED))
        if (results.isEmpty()) { table.addView(Ui.text(this, "Tap ▶ Test all models.", 14f, Ui.MUTED)); return }
        val star = best()
        results.forEach { r ->
            val row = Ui.row(this).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10)) }
            val col = Ui.col(this)
            val inUse = r.model == st.model(p)
            col.addView(Ui.text(this, (if (r.model == star) "★ " else "") + r.model + if (inUse) "  (in use)" else "", 15f,
                if (r.err != null) Ui.BAD else Ui.TEXT, true))
            col.addView(Ui.text(this, r.err ?: r.detail.ifBlank { if (r.ms == 0L) "waiting…" else "" }, 12f, Ui.MUTED))
            row.addView(col, Ui.lp(0, weight = 1f))
            row.addView(Ui.text(this, if (r.ms > 0) "%.1f s".format(r.ms / 1000.0) else "–", 14f, Ui.TEXT).apply { gravity = Gravity.END },
                Ui.lp(Ui.dp(this, 56), -2))
            row.addView(Ui.text(this, r.score?.let { "%.1f".format(it) } ?: "–", 15f,
                when { r.score == null -> Ui.MUTED; r.score!! >= 8 -> Ui.GOOD; r.score!! >= 5 -> Ui.WARN; else -> Ui.BAD }, true).apply {
                gravity = Gravity.END }, Ui.lp(Ui.dp(this, 44), -2))
            row.setOnClickListener {
                if (r.err != null) { Ui.toast(this, "This model didn't answer"); return@setOnClickListener }
                Parent.guard(this, "Enter the PIN to change the AI model.") {
                    st.setModel(p, r.model); st.aiProvider = p.id; Ui.toast(this, "Now using ${r.model}"); render()
                }
            }
            table.addView(row)
        }
    }
}
