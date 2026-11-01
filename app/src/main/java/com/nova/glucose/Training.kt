package com.nova.glucose

import android.content.Context
import org.json.JSONArray
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

data class TrainRow(val ts: Long, val photo: String, val food: String, val ai: Int?, val family: Int?, val diff: Int, val change: String)

/**
 * Personal calibration ("training"): every meal keeps the AI's first estimate and the family's final count.
 * Recent corrections are sent with each new photo so the AI learns how this family counts.
 */
object Training {
    private fun items(json: String): List<Pair<String, Int>> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("name").trim() to o.optInt("carbs", o.optInt("carbs_g")) } }
    } catch (_: Exception) { emptyList() }

    fun rows(c: Context, limitMeals: Int = 2000): List<TrainRow> {
        val db = Db.get(c)
        val out = mutableListOf<TrainRow>()
        for (m in db.meals(limitMeals).sortedBy { it.ts }) {
            val ai = db.ai(m.id) ?: continue
            val aiItems = items(ai.first).toMutableList()
            val photo = if (m.photo.isNotBlank()) File(m.photo).name else ""
            for ((name, g) in items(m.itemsJson)) {
                val k = aiItems.indexOfFirst { it.first.equals(name, true) }
                if (k >= 0) {
                    val a = aiItems.removeAt(k).second
                    out += TrainRow(m.ts, photo, name, a, g, g - a, if (g == a) "kept" else "corrected")
                } else out += TrainRow(m.ts, photo, name, null, g, g, "added by family")
            }
            aiItems.forEach { (name, a) -> out += TrainRow(m.ts, photo, name, a, null, -a, "removed by family") }
        }
        return out
    }

    /** Text added to the photo prompt: the family's recent corrections and the overall tendency. */
    fun context(c: Context): String {
        val db = Db.get(c)
        val recent = db.meals(40).mapNotNull { m -> db.ai(m.id)?.let { m to it } }
        if (recent.isEmpty()) return ""
        val sb = StringBuilder("\nThis family's past corrections of your estimates (learn from them):\n")
        val rs = rows(c, 40).filter { it.change != "kept" }.takeLast(25)
        rs.forEach {
            sb.append(when (it.change) {
                "corrected" -> "- ${it.food}: you said ${it.ai} g, family counted ${it.family} g\n"
                "added by family" -> "- family added ${it.food} (${it.family} g) that you missed\n"
                else -> "- family removed ${it.food} (${it.ai} g) — not in the meal\n"
            })
        }
        val aiSum = recent.sumOf { it.second.second }
        val famSum = recent.sumOf { it.first.carbs }
        if (aiSum > 0 && recent.size >= 3) {
            val pct = ((famSum - aiSum) * 100.0 / aiSum).roundToInt()
            if (abs(pct) >= 5) sb.append("Overall, over ${recent.size} meals your totals were ${abs(pct)}% ${if (pct > 0) "too low" else "too high"}.\n")
        }
        return if (rs.isEmpty() && sb.lines().size <= 2) "" else sb.toString()
    }
}
