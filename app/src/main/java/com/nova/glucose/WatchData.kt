package com.nova.glucose

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A piece of sleep: [a, b) with its stage (1 awake, 2 asleep, 4 light, 5 deep, 6 REM). */
data class Seg(val a: Long, val b: Long, val st: Int)
/** An exercise session from the watch. */
data class Ex(val a: Long, val b: Long, val type: Int, val title: String)

/** One day of her Huawei watch data (read from Health Connect on her phone, shared with the family). */
class WatchDay(
    val day: String,
    val steps: IntArray = IntArray(24),
    val hr: IntArray = IntArray(288),          // 5-minute average heart rate, 0 = none
    val sleep: List<Seg> = emptyList(),        // pieces of sleep that start this day
    val ex: List<Ex> = emptyList(),            // sessions that start this day
    val rhr: Int = 0,
    val spo2: Double = 0.0
) {
    val totalSteps get() = steps.sum()
    val maxHr get() = hr.maxOrNull() ?: 0
    val exMin get() = ex.sumOf { ((it.b - it.a) / 60_000).toInt() }
    fun isEmpty() = totalSteps == 0 && hr.all { it == 0 } && sleep.isEmpty() && ex.isEmpty() && rhr == 0 && spo2 == 0.0

    fun json(): String = JSONObject().apply {
        put("s", JSONArray(steps.toList()))
        put("h", hr.joinToString(",").replace(Regex("(,0)+$"), ""))
        put("sl", JSONArray(sleep.map { JSONArray(listOf(it.a, it.b, it.st)) }))
        put("ex", JSONArray(ex.map { JSONArray().put(it.a).put(it.b).put(it.type).put(it.title) }))
        put("r", rhr); put("o", spo2)
    }.toString()

    companion object {
        fun parse(day: String, s: String?): WatchDay {
            if (s.isNullOrBlank()) return WatchDay(day)
            return try {
                val o = JSONObject(s)
                val st = IntArray(24); o.optJSONArray("s")?.let { a -> for (k in 0 until minOf(24, a.length())) st[k] = a.optInt(k) }
                val hr = IntArray(288); o.optString("h").split(',').forEachIndexed { k, v -> if (k < 288) hr[k] = v.trim().toIntOrNull() ?: 0 }
                val sl = mutableListOf<Seg>(); o.optJSONArray("sl")?.let { a -> for (k in 0 until a.length()) a.getJSONArray(k).let { sl += Seg(it.getLong(0), it.getLong(1), it.optInt(2, 2)) } }
                val ex = mutableListOf<Ex>(); o.optJSONArray("ex")?.let { a -> for (k in 0 until a.length()) a.getJSONArray(k).let { ex += Ex(it.getLong(0), it.getLong(1), it.optInt(2), it.optString(3)) } }
                WatchDay(day, st, hr, sl, ex, o.optInt("r"), o.optDouble("o", 0.0).let { if (it.isNaN()) 0.0 else it })
            } catch (_: Exception) { WatchDay(day) }
        }
    }
}

/** Day summary: watch + glucose of the same day. */
data class DaySum(
    val start: Long, val day: String, val hasWatch: Boolean,
    val steps: Int, val exMin: Int, val ex: List<Ex>,
    val sleepMin: Int, val deepMin: Int, val wake: Long,
    val rhr: Int, val maxHr: Int, val spo2: Double,
    val tir: Int?, val avg: Int?, val lows: Int, val firstLow: Reading?, val wakeMg: Int?
)

/** After one exercise session: glucose at the start, the drop within 2 h and a low in the next 12 h. */
data class SportEffect(val ex: Ex, val atStart: Int?, val drop2h: Int?, val lowAt: Reading?)

object WatchData {
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun dayKey(t: Long): String = dayFmt.format(Date(t))
    fun dayStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun addDays(start: Long, n: Int): Long = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_YEAR, n) }.timeInMillis
    fun startOf(day: String): Long = try { dayFmt.parse(day)!!.time } catch (_: Exception) { dayStart(System.currentTimeMillis()) }

    fun get(c: Context, dayStartMs: Long): WatchDay { val k = dayKey(dayStartMs); return WatchDay.parse(k, Db.get(c).watchDay(k)?.second) }

    fun hasAny(c: Context) = Db.get(c).watchCount() > 0

    /** Sleep pieces of the night ending on this day (from 18:00 the day before to 14:00). */
    fun night(c: Context, dayStartMs: Long): List<Seg> {
        val a = dayStartMs - 6 * 3600_000L; val b = dayStartMs + 14 * 3600_000L
        return (get(c, addDays(dayStartMs, -1)).sleep + get(c, dayStartMs).sleep).filter { it.a >= a && it.a < b }.sortedBy { it.a }
    }

    /** Every sleep piece touching [a, b). */
    fun sleepIn(c: Context, a: Long, b: Long): List<Seg> =
        (get(c, addDays(dayStart(a), -1)).sleep + get(c, dayStart(a)).sleep + if (dayStart(b - 1) != dayStart(a)) get(c, dayStart(b - 1)).sleep else emptyList())
            .filter { it.b > a && it.a < b }.distinct().sortedBy { it.a }

    fun asleep(s: Seg) = s.st != 1 && s.st != 3 && s.st != 7

    fun sum(c: Context, start: Long, st: Store = Store(c)): DaySum {
        val db = Db.get(c)
        val w = get(c, start)
        val raw = db.readings(start, start + 24 * 3600_000L - 1)
        val s = Vault.stats(st, raw)
        val n = night(c, start)
        val sleepMin = n.filter { asleep(it) }.sumOf { ((it.b - it.a) / 60_000).toInt() }
        val deep = n.filter { it.st == 5 }.sumOf { ((it.b - it.a) / 60_000).toInt() }
        val wake = n.filter { asleep(it) }.maxOfOrNull { it.b } ?: 0L
        val wakeMg = if (wake > 0) db.readings(wake, wake + 60 * 60_000L).takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.mg } / l.size } else null
        val firstLow = raw.firstOrNull { it.mg < st.low }
        return DaySum(start, w.day, !w.isEmpty() || n.isNotEmpty(), w.totalSteps, w.exMin, w.ex, sleepMin, deep, wake,
            w.rhr.takeIf { it > 0 } ?: restFromHr(w), w.maxHr, w.spo2, s?.inRange, s?.avg, s?.lowEpisodes ?: 0, firstLow, wakeMg)
    }

    /** No resting value from the watch: the lowest half-hour average of the day. */
    private fun restFromHr(w: WatchDay): Int {
        var best = 0
        for (k in 0..282) { val l = (k until k + 6).map { w.hr[it] }.filter { it > 0 }; if (l.size >= 4) { val a = l.sum() / l.size; if (best == 0 || a < best) best = a } }
        return best
    }

    fun sport(c: Context, e: Ex, st: Store = Store(c)): SportEffect {
        val db = Db.get(c)
        val atStart = db.near(e.a)?.mg
        val after = db.readings(e.a, e.b + 2 * 3600_000L)
        val drop = if (atStart != null && after.isNotEmpty()) atStart - after.minOf { it.mg } else null
        val low = db.readings(e.b, e.b + 12 * 3600_000L).firstOrNull { it.mg < st.low }
        return SportEffect(e, atStart, drop, low)
    }

    /** Last [days] days (oldest first), today included. */
    fun period(c: Context, days: Int): List<DaySum> {
        val st = Store(c); val today = dayStart(System.currentTimeMillis())
        return (days - 1 downTo 0).map { sum(c, addDays(today, -it), st) }
    }

    // ---------------------------------------------------------------- comparisons used by the page, the AI and the doctor report
    class Compare(
        val activeLabel: String, val quietLabel: String,
        val active: List<DaySum>, val quiet: List<DaySum>,
        val sports: List<SportEffect>,
        val shortSleep: List<DaySum>, val longSleep: List<DaySum>,
        val rhrBase: Int, val rhrHigh: List<DaySum>
    ) {
        fun tir(l: List<DaySum>) = l.mapNotNull { it.tir }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        fun avg(l: List<DaySum>) = l.mapNotNull { it.avg }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        fun nightLows(c: Context, l: List<DaySum>): Int = l.sumOf { d -> Db.get(c).readings(d.start, d.start + 6 * 3600_000L).let { WatchData.lowEpisodes(Store(c), it) } }
        fun wake(l: List<DaySum>) = l.mapNotNull { it.wakeMg }.takeIf { it.isNotEmpty() }?.average()?.toInt()
    }

    fun lowEpisodes(st: Store, rs: List<Reading>): Int {
        var n = 0; var inLow = false
        for (r in rs.sortedBy { it.ts }) { if (r.mg < st.low && !inLow) { n++; inLow = true } else if (r.mg >= st.low) inLow = false }
        return n
    }

    fun compare(c: Context, ds: List<DaySum>): Compare {
        val st = Store(c)
        val w = ds.filter { it.hasWatch && it.steps > 0 }
        var hi = w.filter { it.steps >= 10_000 }; var lo = w.filter { it.steps < 6_000 }
        var hiL = "Active days (over 10,000 steps)"; var loL = "Quiet days (under 6,000 steps)"
        if ((hi.size < 2 || lo.size < 2) && w.size >= 4) {
            val med = w.map { it.steps }.sorted()[w.size / 2]
            hi = w.filter { it.steps >= med }; lo = w.filter { it.steps < med }
            hiL = "More active days (%,d steps or more)".format(Locale.US, med); loL = "Less active days (under %,d steps)".format(Locale.US, med)
        }
        val sports = ds.flatMap { it.ex }.filter { it.b - it.a >= 15 * 60_000L }.map { sport(c, it, st) }
        val sl = ds.filter { it.sleepMin > 0 }
        val short = sl.filter { it.sleepMin < 7 * 60 }; val long = sl.filter { it.sleepMin >= 8 * 60 }
        val rh = ds.filter { it.rhr > 0 }
        val base = rh.map { it.rhr }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        return Compare(hiL, loL, hi, lo, sports, short, long, base, rh.filter { base > 0 && it.rhr >= base + 6 })
    }

    fun hm(m: Int) = "${m / 60} h %02d".format(m % 60)
    private val tf get() = SimpleDateFormat("HH:mm", Locale.US)
    private val df get() = SimpleDateFormat("EEE d MMM", Locale.US)

    fun exName(type: Int, title: String): String = title.ifBlank {
        when (type) {
            8 -> "Cycling"; 9 -> "Indoor cycling"; 14 -> "Dancing"; 25 -> "Football"; 56 -> "Running"; 57 -> "Treadmill running"
            73, 74 -> "Swimming"; 79 -> "Walking"; 37 -> "Hiking"; 5 -> "Basketball"; 33 -> "Gymnastics"; 70 -> "Strength training"
            61 -> "Skipping rope"; 82 -> "Yoga"; 81 -> "Volleyball"; 77 -> "Tennis"; 4 -> "Badminton"; 36 -> "Handball"; 0 -> "Exercise"
            else -> "Sport"
        }
    }

    // ---------------------------------------------------------------- for the AI
    /** Last 24 h for Ask AI and alert tips. */
    fun aiNow(c: Context): String {
        val st = Store(c)
        if (!st.hcAi || !hasAny(c)) return ""
        val now = System.currentTimeMillis(); val today = dayStart(now)
        val d = sum(c, today, st)
        val w = get(c, today)
        val k = ((now - today) / 300_000L).toInt().coerceIn(0, 287)
        val hrNow = (k downTo maxOf(0, k - 3)).map { w.hr[it] }.firstOrNull { it > 0 }
        val recent = (get(c, addDays(today, -1)).ex + w.ex).filter { it.b > now - 18 * 3600_000L }
        return buildString {
            append("Watch (Huawei, through Health Connect): today ${d.steps} steps so far")
            if (recent.isNotEmpty()) append("; exercise " + recent.joinToString(", ") { "${exName(it.type, it.title)} ${tf.format(Date(it.a))}–${tf.format(Date(it.b))}" })
            if (d.sleepMin > 0) append("; last night slept ${hm(d.sleepMin)} (deep ${hm(d.deepMin)}), woke ${tf.format(Date(d.wake))}")
            if (d.rhr > 0) append("; resting heart rate ${d.rhr}")
            if (hrNow != null) append("; heart rate now about $hrNow")
            append(".\n")
        }
    }

    /** The review period, day by day, plus the comparisons. */
    fun aiPeriod(c: Context, days: Int): String {
        val st = Store(c)
        if (!st.hcAi || !hasAny(c)) return ""
        val ds = period(c, days).filter { it.hasWatch }
        if (ds.isEmpty()) return ""
        val cmp = compare(c, period(c, days))
        return buildString {
            append("Watch data (Huawei through Health Connect; consumer watch, approximate) per day: date, steps, exercise, sleep the night before (deep), wake time, wake-up glucose, resting HR, max HR, SpO2:\n")
            ds.forEach { d ->
                append("- ${df.format(Date(d.start))}: ${d.steps} steps")
                if (d.ex.isNotEmpty()) append(", " + d.ex.joinToString(", ") { "${exName(it.type, it.title)} ${tf.format(Date(it.a))}–${tf.format(Date(it.b))}" })
                if (d.sleepMin > 0) append(", sleep ${hm(d.sleepMin)} (deep ${hm(d.deepMin)}), woke ${tf.format(Date(d.wake))}" + (d.wakeMg?.let { " at $it mg/dL" } ?: ""))
                if (d.rhr > 0) append(", RHR ${d.rhr}"); if (d.maxHr > 0) append(", max HR ${d.maxHr}")
                if (d.spo2 > 0) append(", SpO2 ${"%.0f".format(Locale.US, d.spo2)}%")
                d.tir?.let { append(" | glucose: in range $it%, average ${d.avg}, lows ${d.lows}") }
                append("\n")
            }
            append(lines(c, cmp).joinToString("\n", postfix = "\n"))
            append("Treat these as associations in the data, not causes.\n")
        }
    }

    /** Short sentences of the comparisons (AI, page, doctor report). */
    fun lines(c: Context, k: Compare): List<String> {
        val out = mutableListOf<String>()
        if (k.active.isNotEmpty() && k.quiet.isNotEmpty())
            out += "${k.activeLabel}: in range ${k.tir(k.active) ?: "?"}%, average ${k.avg(k.active) ?: "?"}, night lows ${k.nightLows(c, k.active)} (${k.active.size} days) · " +
                "${k.quietLabel}: in range ${k.tir(k.quiet) ?: "?"}%, average ${k.avg(k.quiet) ?: "?"}, night lows ${k.nightLows(c, k.quiet)} (${k.quiet.size} days)."
        if (k.sports.isNotEmpty()) {
            val drops = k.sports.mapNotNull { it.drop2h }
            val lows = k.sports.filter { it.lowAt != null }
            out += "After ${k.sports.size} sport session(s): " + (if (drops.isNotEmpty()) "glucose fell ${drops.average().toInt()} mg/dL on average within 2 h; " else "") +
                "${lows.size} of ${k.sports.size} followed by a low within 12 h" +
                (if (lows.isNotEmpty()) " (" + lows.joinToString(", ") { "${df.format(Date(it.lowAt!!.ts))} ${tf.format(Date(it.lowAt.ts))} ${it.lowAt.mg}" } + ")" else "") + "."
        }
        val ws = k.wake(k.shortSleep); val wl = k.wake(k.longSleep)
        if (ws != null && wl != null) out += "Wake-up glucose after nights under 7 h: $ws (${k.shortSleep.size} nights) vs 8 h or more: $wl (${k.longSleep.size} nights)."
        if (k.rhrHigh.isNotEmpty()) {
            val inc = k.rhrHigh.mapNotNull { d -> Db.get(c).incidents(d.start, d.start + 24 * 3600_000L - 1).firstOrNull()?.kind }.distinct()
            out += "Resting heart rate above usual (${k.rhrBase}) on " + k.rhrHigh.joinToString(", ") { "${df.format(Date(it.start))} (${it.rhr}" + (it.avg?.let { a -> ", glucose average $a" } ?: "") + ")" } +
                (if (inc.isNotEmpty()) "; incident logged: ${inc.joinToString(", ")}" else "") + "."
        }
        return out
    }
}
