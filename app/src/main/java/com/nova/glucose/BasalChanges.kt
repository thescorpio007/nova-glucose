package com.nova.glucose

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Long-acting (Lantus) dose changes: "9 Nov · 17 → 18 u". Added by hand (+ Add dose change) or automatically when the night
 * pop-up confirms a new dose; shared with the family. Reports draw a purple line at each change and compare before → after
 * over 3, 7 and 14 days (same number of days on each side). The AI gets the same comparison.
 */
object BasalChanges {
    private const val KEY = "sync_basal"          // {id: {id, ts, from, to, note, del, u}}
    const val DAY = 86_400_000L

    class Chg(val id: String, val ts: Long, val from: Double, val to: Double, val note: String)

    private fun all(c: Context) = try { JSONObject(Store(c).raw.getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun save(c: Context, o: JSONObject) { Store(c).raw.edit().putString(KEY, o.toString()).apply() }

    fun list(c: Context): List<Chg> {
        val o = all(c)
        return o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.filter { !it.optBoolean("del") }
            .map { Chg(it.optString("id"), it.optLong("ts"), it.optDouble("from", 0.0), it.optDouble("to", 0.0), it.optString("note")) }
            .sortedBy { it.ts }.toList()
    }

    fun put(c: Context, ts: Long, from: Double, to: Double, note: String, id: String = java.util.UUID.randomUUID().toString().take(10), del: Boolean = false) {
        val o = all(c)
        o.put(id, JSONObject().put("id", id).put("ts", ts).put("from", from).put("to", to).put("note", note).put("del", del).put("u", System.currentTimeMillis()))
        save(c, o); Db.get(c).queue("bdc-$id", "bdc")
    }

    fun delete(c: Context, ch: Chg) = put(c, ch.ts, ch.from, ch.to, ch.note, ch.id, del = true)

    fun item(c: Context, gid: String): JSONObject? = all(c).optJSONObject(gid.removePrefix("bdc-"))?.let { JSONObject(it.toString()).put("t", "bdc").put("g", gid) }

    fun received(c: Context, it: JSONObject): String {
        val id = it.optString("id"); if (id.isBlank()) return ""
        val o = all(c); val cur = o.optJSONObject(id)
        if (cur != null && cur.optLong("u") >= it.optLong("u")) return ""
        val x = JSONObject(it.toString()); x.remove("t"); x.remove("g"); o.put(id, x); save(c, o); return "bdc"
    }

    fun label(ch: Chg) = "Lantus ${Lantus.fmt(ch.from)} → ${Lantus.fmt(ch.to)} u"
    fun line(ch: Chg) = SimpleDateFormat("d MMM", Locale.US).format(Date(ch.ts)) + ": " + label(ch) + (ch.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

    /** The changes inside a time window. */
    fun between(c: Context, a: Long, b: Long) = list(c).filter { it.ts in a..b }

    class Side(val avg: Int, val tir: Int, val below: Int, val lows: Int, val night: Int?, val fasting: Int?)

    private fun hourAvg(rs: List<Reading>, h0: Int, h1: Int): Int? {
        val cal = Calendar.getInstance()
        val l = rs.filter { cal.timeInMillis = it.ts; cal.get(Calendar.HOUR_OF_DAY) in h0 until h1 }
        return if (l.isEmpty()) null else l.sumOf { it.mg } / l.size
    }

    private fun side(c: Context, a: Long, b: Long): Side? {
        val st = Store(c); val rs = Db.get(c).readings(a, b)
        val s = Vault.stats(st, rs) ?: return null
        return Side(s.avg, s.inRange, s.below, s.lowEpisodes, hourAvg(rs, 0, 6), hourAvg(rs, 6, 8))
    }

    /** Before → after for [days] days on each side of the change; null "after" side = not enough days yet. */
    class Cmp(val days: Int, val before: Side?, val after: Side?, val daysAfter: Int)

    fun compare(c: Context, ch: Chg, days: Int): Cmp {
        val now = System.currentTimeMillis()
        val daysAfter = ((now - ch.ts) / DAY).toInt()
        val before = side(c, ch.ts - days * DAY, ch.ts)
        val after = if (daysAfter >= days) side(c, ch.ts, ch.ts + days * DAY) else null
        return Cmp(days, before, after, daysAfter)
    }

    /** Plain text for the AI review, Ask AI and reports. */
    fun aiText(c: Context): String {
        val l = list(c).filter { System.currentTimeMillis() - it.ts < 45 * DAY }
        if (l.isEmpty()) return ""
        return buildString {
            append("Long-acting (Lantus) dose changes — IMPORTANT, compare before and after and judge the effect (never suggest a dose):\n")
            l.forEach { ch ->
                append("- ${line(ch)}\n")
                listOf(3, 7, 14).forEach { d ->
                    val m = compare(c, ch, d)
                    val b = m.before; val a = m.after
                    if (b == null) append("  $d days: no readings before the change\n")
                    else if (a == null) append("  $d days: before avg ${b.avg}, in range ${b.inRange()}% · after: only ${m.daysAfter} of $d days so far — too early\n")
                    else append("  $d days before → after: average ${b.avg} → ${a.avg} mg/dL · in range ${b.tir}% → ${a.tir}% · below ${Store(c).low} ${b.below}% → ${a.below}% · " +
                        "low episodes ${b.lows} → ${a.lows} · night 00–06 avg ${b.night ?: "–"} → ${a.night ?: "–"} · fasting 06–08 avg ${b.fasting ?: "–"} → ${a.fasting ?: "–"}\n")
                }
            }
        }
    }

    private fun Side.inRange() = tir

    /** Purple dashed line + "Lantus 17 → 18 u" on a report chart, for each change between [t0] and [t1]. */
    fun drawLines(cv: android.graphics.Canvas, r: android.graphics.RectF, t0: Long, t1: Long, chg: List<Chg>) {
        val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.4f
            color = android.graphics.Color.rgb(140, 90, 220); pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f) }
        val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f; isFakeBoldText = true; color = android.graphics.Color.rgb(100, 50, 180) }
        val box = android.graphics.Paint().apply { color = android.graphics.Color.rgb(242, 234, 255) }
        chg.filter { it.ts in t0..t1 }.forEach { ch ->
            val x = r.left + r.width() * ((ch.ts - t0).toFloat() / (t1 - t0).toFloat())
            cv.drawLine(x, r.top, x, r.bottom, pen)
            val s = label(ch); val w = txt.measureText(s) + 6
            val bx = (x - w / 2).coerceIn(r.left, r.right - w)
            cv.drawRect(bx, r.top + 1, bx + w, r.top + 11, box); cv.drawText(s, bx + 3, r.top + 9, txt)
        }
    }

    /** Rows for the report table: measure | 3 days | 7 days | 14 days (before → after). */
    fun tableRows(c: Context, ch: Chg): List<List<String>> {
        val low = Store(c).low
        val cmps = listOf(3, 7, 14).map { compare(c, ch, it) }
        fun cell(m: Cmp, f: (Side) -> String): String {
            val b = m.before ?: return "–"
            return f(b) + " → " + (m.after?.let(f) ?: "… (${m.daysAfter}/${m.days} d)")
        }
        return listOf(
            listOf(label(ch) + " · " + SimpleDateFormat("d MMM", Locale.US).format(Date(ch.ts)), "3 days", "7 days", "14 days"),
            listOf("Average glucose") + cmps.map { m -> cell(m) { "${it.avg}" } },
            listOf("Time in range") + cmps.map { m -> cell(m) { "${it.tir}%" } },
            listOf("Below $low · lows") + cmps.map { m -> cell(m) { "${it.below}% · ${it.lows}" } },
            listOf("Night 00–06 avg") + cmps.map { m -> cell(m) { "${it.night ?: "–"}" } },
            listOf("Fasting 06–08 avg") + cmps.map { m -> cell(m) { "${it.fasting ?: "–"}" } })
    }

    /** "8 Nov ✓ 17 u · 9 Nov ✓ 18 u · 10 Nov — skipped" for the night Lantus of each day in [a, b]. */
    fun nights(c: Context, a: Long, b: Long): String {
        val st = Store(c); val db = Db.get(c); val f = SimpleDateFormat("d MMM", Locale.US)
        val out = ArrayList<String>()
        var d = WatchData.dayStart(a); val now = System.currentTimeMillis()
        while (d <= b && out.size < 31) {
            val t = Lantus.at(st, d + 12 * 3600_000L)
            val longs = db.doses(t - 6 * 3600_000L, t + 6 * 3600_000L).filter { it.kind == "long" }
            val state = st.raw.getString("lantus_st_" + WatchData.dayKey(t), "") ?: ""
            val txt = when {
                longs.isNotEmpty() -> "✓ ${Lantus.fmt(longs.first().units)} u"
                state == "skip" -> "skipped"
                t > now -> null
                else -> "— not confirmed"
            }
            if (txt != null) out += "${f.format(Date(d))} $txt"
            d = WatchData.addDays(d, 1)
        }
        return out.joinToString(" · ")
    }
}
