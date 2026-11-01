package com.nova.glucose

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved meals with their carb estimate and how her glucose responded 2 hours later. */
class LogbookActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var today: LinearLayout
    private val fmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "Logbook"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        root.addView(Ui.text(this, "Each meal shows glucose at the meal and 2 hours later, to help judge future estimates. Long-press a meal to delete it.",
            13f, Ui.MUTED))
        today = Ui.card(this)
        root.addView(today, Ui.lp(top = Ui.dp(this, 10)))
        list = Ui.col(this)
        root.addView(list, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.signature(this))
        setContentView(sv)
    }

    override fun onResume() { super.onResume(); render() }

    private fun renderToday() {
        today.removeAllViews()
        val st = Store(this)
        val (a, b) = Vault.dayRange(java.util.Calendar.getInstance())
        val s = Vault.stats(st, Db.get(this).readings(a, b))
        today.addView(Ui.text(this, "Today's glucose assessment", 13f, Ui.MUTED, true))
        if (s == null) { today.addView(Ui.text(this, "No readings yet today.", 14f, Ui.MUTED)); return }
        today.addView(Ui.text(this, "${s.inRange}% in range (${st.low}–${st.high})", 20f,
            if (s.inRange >= 70) Ui.GOOD else if (s.inRange >= 50) Ui.WARN else Ui.BAD, true))
        today.addView(Ui.text(this, "Average ${s.avg} mg/dL · lowest ${s.min} · highest ${s.max}\n" +
            "Below ${st.low}: ${s.below}% · above ${st.high}: ${s.above}% · low episodes: ${s.lowEpisodes}", 13f, Ui.TEXT))
        today.addView(Ui.text(this, if (Vault.ready(this)) "Full daily report saved in ${Vault.folderLabel(this)}/assessment"
            else "Choose the Download/NovaGlucose folder in Settings to save daily reports.", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(context, 6), 0, 0) })
    }

    private fun render() {
        renderToday()
        list.removeAllViews()
        val db = Db.get(this)
        val meals = db.meals()
        if (meals.isEmpty()) {
            list.addView(Ui.card(this).apply {
                addView(Ui.text(context, "No meals yet. Use \"Carbs from photo / video\" and tap Save to logbook.", 14f, Ui.MUTED))
            })
            return
        }
        val now = System.currentTimeMillis()
        for (m in meals) {
            val card = Ui.card(this)
            val top = Ui.row(this)
            if (m.photo.isNotBlank() && File(m.photo).exists()) {
                val bmp = BitmapFactory.decodeFile(m.photo, BitmapFactory.Options().apply { inSampleSize = 4 })
                top.addView(ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Ui.round(Ui.CARD2, Ui.dp(context, 10).toFloat()); clipToOutline = true
                }, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)).apply { marginEnd = Ui.dp(this@LogbookActivity, 12) })
            }
            val col = Ui.col(this)
            col.addView(Ui.text(this, fmt.format(Date(m.ts)), 12f, Ui.MUTED))
            col.addView(Ui.text(this, m.title.ifBlank { "Meal" }, 15f, Ui.TEXT, true))
            val range = if (m.high > m.low) "  (${m.low}–${m.high})" else ""
            col.addView(Ui.text(this, "${m.carbs} g carbs$range", 15f, Ui.ACCENT, true))
            top.addView(col, Ui.lp(0, weight = 1f))
            card.addView(top)

            val after = db.near(m.ts + 2 * 3600_000L)
            val at = if (m.mgAt > 0) "${m.mgAt}" else "–"
            val line = when {
                after != null -> {
                    val diff = if (m.mgAt > 0) after.mg - m.mgAt else null
                    "Glucose: $at → ${after.mg} mg/dL after 2 h" + (diff?.let { "  (${if (it >= 0) "+" else ""}$it)" } ?: "")
                }
                now - m.ts < 2 * 3600_000L -> "Glucose at meal: $at mg/dL · 2-hour check pending"
                else -> "Glucose at meal: $at mg/dL · no reading 2 h later"
            }
            card.addView(Ui.text(this, line, 13f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })

            val items = try { JSONArray(m.itemsJson) } catch (_: Exception) { JSONArray() }
            if (items.length() > 1) {
                val sb = StringBuilder()
                for (k in 0 until items.length()) items.getJSONObject(k).let {
                    sb.append("•  ${it.optString("name")} ${it.optString("portion").let { p -> if (p.isNotBlank()) "($p)" else "" }} — ${it.optInt("carbs")} g\n")
                }
                card.addView(Ui.text(this, sb.toString().trim(), 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
            }
            if (m.note.isNotBlank()) card.addView(Ui.text(this, "Note: ${m.note}", 12f, Ui.MUTED))

            card.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("Delete this meal?")
                    .setPositiveButton("Delete") { _, _ ->
                        db.deleteMeal(m.id); if (m.photo.isNotBlank()) File(m.photo).delete(); render()
                    }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            list.addView(card, Ui.lp(top = Ui.dp(this, 8)))
        }
    }
}
