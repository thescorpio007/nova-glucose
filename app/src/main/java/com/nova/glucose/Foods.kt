package com.nova.glucose

import java.util.Calendar

data class Food(val name: String, val portion: String, val carbs: String, val style: String)

enum class Level { NO_DATA, STALE, URGENT_LOW, LOW, DROPPING, IN_RANGE, HIGH, VERY_HIGH }

data class Advice(
    val level: Level,
    val title: String,
    val message: String,
    val color: Int,
    val steps: List<String>,
    val foodsTitle: String,
    val foods: List<Food>
)

/**
 * Built-in food ideas (work offline). Carb numbers are typical approximate values for the portion shown.
 * Standard guidance only — every number is editable in Settings once her care team gives her own plan.
 */
object Foods {
    const val CARE_NOTE = "Food ideas only, standard guidance — check with her diabetes care team. This app never suggests insulin doses."

    fun level(st: Store, r: Reading?, now: Long = System.currentTimeMillis()): Level {
        if (r == null) return Level.NO_DATA
        if (now - r.ts > 15 * 60_000) return Level.STALE
        return when {
            r.mg < st.urgentLow -> Level.URGENT_LOW
            r.mg < st.low -> Level.LOW
            r.mg > st.veryHigh -> Level.VERY_HIGH
            r.mg > st.high -> Level.HIGH
            (r.trend == 1 && r.mg < 130) || (r.trend == 2 && r.mg < st.low + 25) -> Level.DROPPING
            else -> Level.IN_RANGE
        }
    }

    fun mealSlot(cal: Calendar = Calendar.getInstance()): String = when (cal.get(Calendar.HOUR_OF_DAY)) {
        in 5..10 -> "breakfast"
        in 11..15 -> "lunch"
        in 18..21 -> "dinner"
        else -> "snack"
    }

    fun advise(st: Store, r: Reading?): Advice {
        val lv = level(st, r)
        val g = st.lowTreatG
        val seed = System.currentTimeMillis() / (60 * 60_000)   // choices change every hour
        return when (lv) {
            Level.NO_DATA -> Advice(lv, "No reading yet", "Connect LibreLinkUp in Settings to see her glucose here.",
                Ui.MUTED, emptyList(), "", emptyList())
            Level.STALE -> Advice(lv, "Reading is old",
                "No new reading for over 15 minutes. Check that her phone is online, the Libre app is open and the sensor is working.",
                Ui.WARN, listOf("Check with a finger-prick test before treating or eating based on an old reading."), "", emptyList())
            Level.URGENT_LOW -> Advice(lv, "Urgent low — treat now",
                "Glucose is below ${st.urgentLow} mg/dL.", Ui.BAD,
                listOf(
                    "Have $g g of fast sugar now (choose one below).",
                    "Tell an adult right away. Don't exercise or drive.",
                    "Recheck in 15 minutes. If still below ${st.low}, repeat.",
                    "If she can't swallow safely or is not responding: use glucagon if prescribed and call 998 (UAE ambulance)."
                ), "Fast sugar ($g g)", fast(g))
            Level.LOW -> Advice(lv, "Low — have fast sugar",
                "Glucose is below ${st.low} mg/dL.", Ui.BAD,
                listOf(
                    "Have $g g of fast sugar now (choose one below).",
                    "Recheck in 15 minutes. If still below ${st.low}, repeat.",
                    "When back in range: if the next meal is more than 1 hour away, have a small snack.",
                    "Avoid chocolate or fatty food to treat a low — they act too slowly."
                ), "Fast sugar ($g g)", fast(g))
            Level.DROPPING -> Advice(lv, "Dropping — watch it",
                "${r!!.mg} mg/dL and ${Libre.trendText(r.trend)}.", Ui.WARN,
                listOf("A small snack may help prevent a low — follow her care plan.",
                    "Check again in 15–20 minutes."),
                "Small snack (~${st.snackMin} g)", pick(snacks, 4, seed))
            Level.IN_RANGE -> {
                val slot = mealSlot()
                val list = when (slot) { "breakfast" -> breakfast; "lunch", "dinner" -> mains; else -> snacks }
                val range = if (slot == "snack") "${st.snackMin}–${st.snackMax} g" else "${st.mealMin}–${st.mealMax} g"
                Advice(lv, "In range", "${r!!.mg} mg/dL, ${Libre.trendText(r.trend)}. Great time for a balanced choice.",
                    Ui.IN_RANGE, listOf("Pair carbs with protein and vegetables for a steadier curve."),
                    "${slot.replaceFirstChar { it.uppercase() }} ideas ($range)", pick(list, 4, seed))
            }
            Level.HIGH -> Advice(lv, "High",
                "${r!!.mg} mg/dL, ${Libre.trendText(r.trend)}.", Ui.WARN,
                listOf("Drink water.", "Choose low-carb food until it comes down.",
                    "Follow her care team's plan for highs."),
                "Low-carb choices", pick(lowCarb, 4, seed))
            Level.VERY_HIGH -> Advice(lv, "Very high",
                "${r!!.mg} mg/dL, above ${st.veryHigh}.", Ui.BAD,
                listOf("Drink water.", "Check ketones as her care team advised.",
                    "Ketones moderate/large, vomiting or tummy pain → call her diabetes team or 998 straight away.",
                    "Choose low-carb food until it comes down."),
                "Low-carb choices", pick(lowCarb, 4, seed))
        }
    }

    private fun pick(list: List<Food>, n: Int, seed: Long): List<Food> =
        list.shuffled(java.util.Random(seed)).take(n)

    private fun fast(g: Int): List<Food> {
        val f = g / 15.0
        fun q(x: Double) = if (x == Math.floor(x)) x.toInt().toString() else "%.1f".format(x)
        return listOf(
            Food("Fruit juice (not 'no sugar')", "${(150 * f).toInt()} ml", "$g g", "Any"),
            Food("Glucose tablets", "${q(4 * f)} tablets (4 g each)", "$g g", "Any"),
            Food("Regular soft drink (not diet)", "${(150 * f).toInt()} ml", "$g g", "Any"),
            Food("Honey", "${q(1 * f)} tablespoon", "≈ ${(17 * f).toInt()} g", "Any"),
            Food("Deglet Nour dates", "${q(3 * f)} dates", "≈ ${(16 * f).toInt()} g", "TN / Gulf"),
            Food("Sugar dissolved in water", "${q(4 * f)} teaspoons", "≈ ${(16 * f).toInt()} g", "Any")
        )
    }

    private val breakfast = listOf(
        Food("Ojja (tomato & pepper eggs) + bread", "2 eggs + 1 slice bread", "≈ 25 g", "Tunisian"),
        Food("Bsissa with milk", "3 tbsp bsissa + 200 ml milk", "≈ 40 g", "Tunisian"),
        Food("Tabouna bread with olive oil, eggs & olives", "¼ round + 2 eggs", "≈ 35 g", "Tunisian"),
        Food("Foul medames + Arabic bread", "1 cup foul + ½ khubz", "≈ 45 g", "Gulf"),
        Food("Shakshuka + Arabic bread", "2 eggs + ½ khubz", "≈ 25 g", "Gulf"),
        Food("Labneh, cucumber, olives + bread", "3 tbsp labneh + 1 khubz", "≈ 35 g", "Gulf"),
        Food("Oatmeal with milk & berries", "½ cup oats + 200 ml milk + ½ cup berries", "≈ 45 g", "Intl"),
        Food("Greek yogurt + granola + fruit", "170 g yogurt + ¼ cup granola + ½ banana", "≈ 40 g", "Intl"),
        Food("Wholegrain toast + eggs + cheese", "2 slices + 2 eggs", "≈ 30 g", "Intl")
    )

    private val mains = listOf(
        Food("Couscous with chicken & vegetables", "1 cup couscous + chicken + veg", "≈ 50 g", "Tunisian"),
        Food("Lablabi (chickpea soup)", "1 bowl, little bread", "≈ 50 g", "Tunisian"),
        Food("Grilled fish + salade méchouia + bread", "1 fillet + salad + 1 slice", "≈ 25 g", "Tunisian"),
        Food("Brik with tuna & egg + salad", "1 brik + green salad", "≈ 20 g", "Tunisian"),
        Food("Chicken machboos", "1 cup rice + chicken + salad", "≈ 50 g", "Gulf"),
        Food("Shish tawook + hummus + fattoush", "skewer + 4 tbsp hummus + ½ khubz", "≈ 35 g", "Gulf"),
        Food("Harees", "1 cup", "≈ 35 g", "Gulf"),
        Food("Grilled kofta + tabbouleh + bread", "3 kofta + salad + ½ khubz", "≈ 30 g", "Gulf"),
        Food("Grilled chicken + brown rice + salad", "1 cup rice", "≈ 50 g", "Intl"),
        Food("Wholewheat pasta with tomato & turkey", "1 cup cooked pasta", "≈ 45 g", "Intl"),
        Food("Salmon + potato + vegetables", "1 medium potato", "≈ 40 g", "Intl"),
        Food("Chicken wrap (wholewheat)", "1 wrap + salad", "≈ 35 g", "Intl")
    )

    private val snacks = listOf(
        Food("Apple + peanut butter", "1 small apple + 1 tbsp", "≈ 20 g", "Intl"),
        Food("Dates + almonds", "3 Deglet Nour + 6 almonds", "≈ 16 g", "TN / Gulf"),
        Food("Yogurt + berries", "150 g + ½ cup", "≈ 18 g", "Intl"),
        Food("Popcorn (plain)", "3 cups", "≈ 18 g", "Intl"),
        Food("Hummus + carrot sticks", "4 tbsp + 1 carrot", "≈ 15 g", "Gulf"),
        Food("Orange", "1 medium", "≈ 15 g", "Any"),
        Food("Bread + labneh / cheese", "½ khubz or 1 slice", "≈ 17 g", "Gulf"),
        Food("Laban (buttermilk) + crackers", "200 ml + 3 crackers", "≈ 20 g", "Gulf")
    )

    private val lowCarb = listOf(
        Food("Grilled chicken or fish + green salad", "palm-size portion", "≈ 3–5 g", "Any"),
        Food("Salade tunisienne / méchouia (no bread)", "1 bowl", "≈ 8 g", "Tunisian"),
        Food("Boiled eggs + cucumber", "2 eggs", "≈ 2 g", "Any"),
        Food("Labneh or cheese + vegetables", "3 tbsp + cucumber, pepper", "≈ 5 g", "Gulf"),
        Food("Nuts (unsalted)", "small handful", "≈ 5 g", "Any"),
        Food("Fattoush without the bread chips", "1 bowl", "≈ 8 g", "Gulf"),
        Food("Vegetable soup (no pasta/rice)", "1 bowl", "≈ 10 g", "Any"),
        Food("Water / sparkling water", "large glass", "0 g", "Any")
    )

    /** Estimating tips, shown on the photo screen (as agreed with the family). */
    val PHOTO_TIPS = listOf(
        "Photo estimates are approximate — they can be noticeably off with mixed dishes, sauces, hidden sugar and portion size.",
        "Treat the number as a starting point. Use the food label or a kitchen scale whenever you can.",
        "Take 1–2 photos: one from the top and one from the side, or a short video around the plate.",
        "Use a normal-size plate and put a fork or her hand next to it for scale.",
        "Add a short note like \"large portion\" or \"with honey\" — hidden sugar and sauces matter.",
        "Tap any item to fix its portion or carbs, and add anything the photo can't show (juice, sauce).",
        "The app shows carbs only, not an insulin dose — convert carbs to units with her doctor's carb ratio, as you do now.",
        "For the first few weeks, ask her diabetes team to check the estimates against your usual counting."
    )
}
