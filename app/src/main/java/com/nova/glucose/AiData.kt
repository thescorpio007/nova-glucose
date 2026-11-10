package com.nova.glucose

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Everything the integrated AI gets about her glucose: limits, readings, statistics, meals, insulin (as edited) and incidents. */
object AiData {
    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val dhm = SimpleDateFormat("EEE d MMM HH:mm", Locale.US)

    const val NO_DOSE = "NEVER suggest insulin doses or units, and never say to inject more or less or to change the ratio or correction: " +
        "for any dose question, say to follow the diabetes care team. Food, timing, patterns and questions for the doctor only. " +
        "Height and weight are background only: never comment on weight, body shape or BMI, and never suggest losing or gaining weight. " +
        "A meal marked \"carbs not recorded\" was eaten: never count it as 0 g. A \"correction only\" rapid dose had no meal."

    /** Who reads the answer: the patient on their own phone, or the family. */
    fun audience(st: Store) = if (st.isPatient)
        "You are talking directly to the person with diabetes, on their own phone (see the profile for age and gender). Address them as \"you\", " +
            "in simple, friendly and encouraging words suited to their age."
    else "You are talking to a parent or caregiver, on the family's phone, about the person with diabetes (\"the patient\"; see the profile for age " +
        "and gender, and use matching pronouns)."

    fun limits(st: Store) = "The patient's limits (mg/dL): urgent low below ${st.urgentLow}, low below ${st.low}, target ${st.low}–${st.high}, " +
        "high above ${st.high}, very high above ${st.veryHigh}."

    private fun units(u: Double) = if (u % 1.0 == 0.0) u.toInt().toString() else u.toString()

    /** Short lines shown in "The AI sees". */
    fun summary(c: Context): List<String> {
        val st = Store(c); val db = Db.get(c); val now = System.currentTimeMillis()
        val rs = db.readings(now - 24 * 3600_000L)
        val s = Vault.stats(st, rs)
        val (a, _) = Vault.dayRange(Calendar.getInstance())
        val meals = db.meals(200).filter { it.ts >= a }
        val doses = db.doses(a)
        val inc = db.incidents(now - 3 * 24 * 3600_000L, now)
        return listOfNotNull(
            if (st.lastTs > 0) "Now ${st.lastMg} ${Libre.trendArrow(st.lastTrend)} · last 24 h: ${rs.groupBy { it.ts / 300_000 }.size} readings (every 5 min)" else "No current reading",
            s?.let { "In range ${it.inRange}% · average ${it.avg} · highest ${it.max} · lowest ${it.min}" },
            "Limits: urgent low ${st.urgentLow} · low ${st.low} · high ${st.high} · very high ${st.veryHigh}",
            "Today: ${Carbs.counts(meals, doses)} · ${doses.count { it.kind == "rapid" }} rapid doses (${units(doses.filter { it.kind == "rapid" }.sumOf { it.units })} u)" +
                (doses.firstOrNull { it.kind == "long" }?.let { " · long-acting ${units(it.units)} u" } ?: ""),
            inc.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "Incident: ${it.kind}" + if (it.ongoing) " (ongoing)" else "" },
            "Profile: ${st.aboutLine()}".take(160),
            WatchData.aiNow(c).trim().takeIf { it.isNotBlank() })
    }

    /** Full picture for Ask AI and alert tips: last [hours] h of readings every 5 min, statistics, today's food and insulin, incidents. */
    fun picture(c: Context, hours: Int = 24): String {
        val st = Store(c); val db = Db.get(c); val now = System.currentTimeMillis()
        val rs = db.readings(now - hours * 3600_000L)
        val five = rs.groupBy { it.ts / 300_000 }.map { it.value.last() }
        val s = Vault.stats(st, rs)
        val (a, _) = Vault.dayRange(Calendar.getInstance())
        return buildString {
            append("Time now: ${hm.format(Date(now))} (${Foods.mealSlot()} time).\n")
            append(if (st.lastTs > 0 && now - st.lastTs < 20 * 60_000) "Current glucose: ${st.lastMg} mg/dL, ${Libre.trendText(st.lastTrend)}.\n"
                else "Current glucose: unknown (no recent reading).\n")
            append(limits(st)).append("\n")
            if (s != null) append("Last $hours h: in range ${s.inRange}%, below ${st.low}: ${s.below}%, above ${st.high}: ${s.above}%, " +
                "average ${s.avg}, lowest ${s.min}, highest ${s.max}, low episodes ${s.lowEpisodes}.\n")
            if (five.isNotEmpty()) append("Readings last $hours h, every 5 min (time mg/dL): " + five.joinToString(", ") { "${hm.format(Date(it.ts))} ${it.mg}" } + "\n")
            val meals = db.meals(300).filter { it.ts >= minOf(a, now - hours * 3600_000L) }.sortedBy { it.ts }
            if (meals.isNotEmpty()) append("Meals (time, food, carbs counted by the family): " + meals.joinToString("; ") {
                "${hm.format(Date(it.ts))} ${it.title} ${Carbs.ai(it)}" + if (it.mgAt > 0) " (glucose ${it.mgAt} at the meal)" else "" } + "\n")
            val doses = db.doses(minOf(a, now - hours * 3600_000L)).sortedBy { it.ts }
            if (doses.isNotEmpty()) append("Insulin given (as entered by the family; for timing only — never comment on doses): " + doses.joinToString("; ") {
                "${hm.format(Date(it.ts))} ${if (it.kind == "rapid") "rapid" else "long-acting"}" + if (it.correction) " (correction only, no meal)" else "" } + "\n")
            if (st.eatAt > now) append("An eat timer is running: the patient should start eating at ${hm.format(Date(st.eatAt))}.\n")
            append(Incidents.context(c))
            append(WatchData.aiNow(c))
        }
    }

    // ---------------------------------------------------------------- AI review (1 / 7 / 14 days)
    /** Data for the review: hourly averages per day, meals with glucose 2 h later, AI corrections, insulin times, incidents. */
    fun period(c: Context, days: Int): String {
        val st = Store(c); val db = Db.get(c); val now = System.currentTimeMillis()
        val start = Vault.dayRange(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }).first
        val rs = db.readings(start, now)
        val s = Vault.stats(st, rs)
        val df = SimpleDateFormat("EEE d MMM", Locale.US)
        return buildString {
            append("Period: last $days day(s), ${df.format(Date(start))} to today ${hm.format(Date(now))}.\n")
            append(limits(st)).append("\n")
            append("About the patient: ${st.aboutLine()}.\n")
            if (s != null) append("Whole period: in range ${s.inRange}%, below ${st.low}: ${s.below}%, above ${st.high}: ${s.above}%, average ${s.avg}, " +
                "lowest ${s.min}, highest ${s.max}, low episodes ${s.lowEpisodes}.\n")
            append("Hourly glucose per day (hour:average[min-max]):\n")
            rs.groupBy { df.format(Date(it.ts)) }.forEach { (day, l) ->
                append("$day: ")
                append(l.groupBy { Calendar.getInstance().apply { timeInMillis = it.ts }.get(Calendar.HOUR_OF_DAY) }.toSortedMap()
                    .map { (h, x) -> "$h:${x.sumOf { it.mg } / x.size}[${x.minOf { it.mg }}-${x.maxOf { it.mg }}]" }.joinToString(" "))
                append("\n")
            }
            val meals = db.meals(5000).filter { it.ts >= start }.sortedBy { it.ts }
            if (meals.isNotEmpty()) {
                append("Meals (when, food, carbs counted, glucose at meal → 2 h later, AI photo estimate if any):\n")
                meals.forEach { m ->
                    val after = db.near(m.ts + 2 * 3600_000L)?.mg
                    val ai = db.ai(m.id)?.second
                    append("- ${dhm.format(Date(m.ts))} ${m.title}: ${Carbs.ai(m)}, ${if (m.mgAt > 0) m.mgAt else "?"} → ${after ?: "?"}" +
                        (if (ai != null) ", AI said $ai g" else "") + "\n")
                }
            }
            val doses = db.doses(start)
            if (doses.isNotEmpty()) append("Insulin times (for timing only): " + doses.sortedBy { it.ts }.joinToString("; ") {
                "${dhm.format(Date(it.ts))} ${if (it.kind == "rapid") "rapid" else "long"}" + if (it.correction) " (correction only, no meal)" else "" } + "\n")
            append("Period totals: " + Carbs.counts(meals, doses) + "\n")
            BasalChanges.aiText(c).takeIf { it.isNotBlank() }?.let { append(it) }
            append("Night Lantus each day: " + BasalChanges.nights(c, start, System.currentTimeMillis()) + "\n")
            val inc = db.incidents(start, now)
            if (inc.isNotEmpty()) append("Incidents: " + inc.joinToString("; ") { "${it.kind} ${Incidents.period(it)}" + if (it.details.isNotBlank()) " (${it.details})" else "" } + "\n")
            append(WatchData.aiPeriod(c, days))
        }
    }

    // ---------------------------------------------------------------- AI tip after an alert
    private val TIP_SYSTEM = """
You add ONE short food tip (2 sentences at most, under 40 words) to a glucose alert for a person with type 1 diabetes (a child or teenager — see the profile).
Use the readings, meals and incidents given. Practical food or drink only, from Tunisian, Gulf or international food.
$NO_DOSE
If glucose is low: the fast-sugar treatment from the plan comes first (already shown); you may add what to eat after the recheck.
Plain text, no title, no emoji at the start.
""".trimIndent()

    /** Blocking: the AI's tip for this alert, or null (no key, no internet, error). */
    fun alertTip(c: Context, kind: String): String? {
        val st = Store(c)
        val p = st.current()
        if (st.key(p).isBlank()) return null
        val what = when (kind) { "urgent_low" -> "URGENT LOW"; "low" -> "LOW"; "high" -> "HIGH"; "very_high" -> "VERY HIGH"; else -> return null }
        return try {
            Providers.ask(st, p, TIP_SYSTEM + "\n" + audience(st) + "\n" + L.aiLine(), "Alert: $what.\n" + picture(c, 6) + "Food notes: ${st.foodNotes}\nAbout the patient: ${st.aboutLine()}")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").trim().take(300)
        } catch (_: Exception) { null }
    }
}
