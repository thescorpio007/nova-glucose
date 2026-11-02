package com.nova.glucose

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Food ideas, recipes and the fridge: prompts, answers read into cards, and the card buttons. */
object FoodAi {
    data class Idea(val name: String, val descr: String, val carbs: Int, val minutes: Int)
    data class Answer(val intro: String, val ideas: List<Idea>, val note: String, val raw: String)
    data class Recipe(val title: String, val serves: Int, val minutes: Int, val carbs: Int, val carbsNote: String,
                      val ings: List<String>, val steps: List<String>, val tip: String) {
        fun json(): String = JSONObject().put("serves", serves).put("ings", JSONArray(ings)).put("steps", JSONArray(steps))
            .put("note", carbsNote).put("tip", tip).toString()
        val complete get() = ings.isNotEmpty() && steps.isNotEmpty()
        companion object {
            fun from(r: Db.Recipe): Recipe {
                val o = try { JSONObject(r.body.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                return Recipe(r.title, o.optInt("serves", r.serves), r.minutes, r.carbs, o.optString("note"), list("ings"), list("steps"), o.optString("tip"))
            }
        }
    }

    val CUISINES = listOf("Tunisian", "Mediterranean", "International", "Any")

    /** What the AI knows: glucose picture, watch, carb plan, food notes, profile, insulin names. */
    fun context(c: Context): String {
        val st = Store(c)
        return buildString {
            append(AiData.picture(c, 24))
            append("Carb plan: meals ${st.mealMin}–${st.mealMax} g, snacks ${st.snackMin}–${st.snackMax} g, low treatment ${st.lowTreatG} g fast sugar.\n")
            if (st.foodNotes.isNotBlank()) append("Family food notes (likes, dislikes, allergies — always respect): ${st.foodNotes}\n")
            append("About her: ${st.aboutLine()}\n")
            val ins = listOfNotNull(st.longName.takeIf { it.isNotBlank() }?.let { "long-acting $it" }, st.rapidName.takeIf { it.isNotBlank() }?.let { "rapid $it" })
            if (ins.isNotEmpty()) append("Her insulins: ${ins.joinToString(", ")}.\n")
        }
    }

    private fun base(st: Store) = """
You help a family choose food for their daughter (age ${st.age}) who has type 1 diabetes and uses rapid insulin before meals.
Rules:
- ${AiData.NO_DOSE}
- If glucose is low (below her low limit): first say to have the fast-sugar treatment from her plan and recheck in 15 minutes; only then other food.
- If glucose is very high: mention water and checking ketones as her care team advised, and prefer lower-carb choices.
- Respect the family food notes (allergies, dislikes) strictly. Carb numbers are for HER portion and are estimates.
- If watch data shows sport today or planned, take it into account (e.g. a slow-carb snack before sport, a balanced dinner after).
- Use the numbers given; do not invent readings.
""".trimIndent()

    fun ideasSystem(c: Context): String = base(Store(c)) + "\n" + """
Reply in exactly this format and nothing else (keep the words INTRO, IDEA and NOTE in English, write the rest in the answer language):
INTRO: <one or two short sentences>
IDEA: <dish name> | <what is in it and her portion> | <carbs in grams for her portion, a number> | <total minutes to make it, a number>
(2 to 4 IDEA lines. For a question that is not asking for dishes, write only INTRO and NOTE lines.)
NOTE: <optional, one short line>
""".trimIndent() + "\n" + L.aiLine().let { if (it.isNotBlank()) "Answer language: " + it.removePrefix("Answer in ").removeSuffix(".") + "." else "" }

    fun recipeSystem(c: Context): String = base(Store(c)) + "\n" + """
Write a home recipe for the dish asked. Reply in exactly this format and nothing else (keep the keywords in English, write the content in the answer language):
TITLE: <dish name>
SERVES: <number of people>
TIME: <total minutes, a number>
CARBS: <carbs in grams for her portion, a number>
CARBS_NOTE: <short breakdown of where the carbs come from>
ING: <one ingredient with its quantity for the number of people>   (one ING line per ingredient)
STEP: <one clear step>   (one STEP line per step, in order, 4 to 9 steps)
TIP: <optional short tip, e.g. how to make it lower in carbs>
""".trimIndent() + "\n" + L.aiLine().let { if (it.isNotBlank()) "Answer language: " + it.removePrefix("Answer in ").removeSuffix(".") + "." else "" }

    const val FRIDGE_SYSTEM = "List the foods and ingredients you can see in these photos of a fridge or kitchen. " +
        "Reply with one line only: ITEMS: item, item, item (simple names, at most 25 items, no quantities)."

    private fun clean(s: String) = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace("**", "").trim()
    private fun num(s: String) = Regex("\\d+").find(s)?.value?.toIntOrNull() ?: 0

    fun parse(raw: String): Answer {
        val intro = StringBuilder(); val ideas = mutableListOf<Idea>(); val note = StringBuilder(); val other = StringBuilder()
        raw.lines().map { clean(it).trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotBlank() }.forEach { l ->
            val u = l.uppercase()
            when {
                u.startsWith("INTRO:") -> intro.append(l.substringAfter(':').trim()).append(' ')
                u.startsWith("IDEA:") -> {
                    val p = l.substringAfter(':').split('|').map { it.trim() }
                    if (p.isNotEmpty() && p[0].isNotBlank()) ideas += Idea(p[0], p.getOrElse(1) { "" }, num(p.getOrElse(2) { "" }), num(p.getOrElse(3) { "" }))
                }
                u.startsWith("NOTE:") -> note.append(l.substringAfter(':').trim()).append(' ')
                else -> other.append(l).append('\n')
            }
        }
        val i = intro.toString().trim().ifBlank { if (ideas.isEmpty()) other.toString().trim() else "" }
        return Answer(i, ideas, note.toString().trim(), raw)
    }

    fun parseRecipe(raw: String, fallbackTitle: String, people: Int): Recipe {
        var title = fallbackTitle; var serves = people; var time = 0; var carbs = 0; var cn = ""; var tip = ""
        val ings = mutableListOf<String>(); val steps = mutableListOf<String>()
        raw.lines().map { clean(it).trim() }.filter { it.isNotBlank() }.forEach { l ->
            val k = l.substringBefore(':').trim().uppercase(); val v = l.substringAfter(':', "").trim()
            when (k) {
                "TITLE" -> if (v.isNotBlank()) title = v
                "SERVES" -> num(v).takeIf { it > 0 }?.let { serves = it }
                "TIME" -> time = num(v)
                "CARBS" -> carbs = num(v)
                "CARBS_NOTE" -> cn = v
                "ING" -> if (v.isNotBlank()) ings += v
                "STEP" -> if (v.isNotBlank()) steps += v.replace(Regex("^\\d+[.)]\\s*"), "")
                "TIP" -> tip = v
            }
        }
        return Recipe(title, serves, time, carbs, cn, ings, steps, tip)
    }

    /** Category of a question (for the recipe book). */
    fun catOf(q: String): String {
        val l = q.lowercase()
        return when {
            "breakfast" in l -> "Breakfast"; "lunchbox" in l || "school" in l || "break snack" in l -> "School"
            "lunch" in l -> "Lunch"; "dinner" in l -> "Dinner"; "sport" in l -> "Sport"; "snack" in l || "bed" in l -> "Snacks"
            else -> "Other"
        }
    }

    // ---------------------------------------------------------------- views
    fun btn(a: Activity, s: String, primary: Boolean = false, click: () -> Unit) = Ui.text(a, s, 12.5f, if (primary) android.graphics.Color.WHITE else Ui.TEXT, primary).apply {
        gravity = Gravity.CENTER; includeFontPadding = false
        setPadding(Ui.dp(a, 10), Ui.dp(a, 7), Ui.dp(a, 10), Ui.dp(a, 7))
        background = Ui.round(if (primary) Ui.ACCENT else 0xFF2A3040.toInt(), Ui.dp(a, 10).toFloat())
        setOnClickListener { click() }
    }

    /** One idea as a card with its five buttons. */
    fun card(a: Activity, idea: Idea, cuisine: String, cat: String, people: Int, onOther: (Idea) -> Unit, onAsk: (Idea) -> Unit): View {
        val box = Ui.col(a, 12).apply { background = Ui.round(Ui.CARD2, Ui.dp(a, 14).toFloat()) }
        val top = Ui.row(a)
        top.addView(Ui.text(a, idea.name, 15f, Ui.TEXT, true), Ui.lp(0, weight = 1f))
        if (idea.carbs > 0) top.addView(Ui.text(a, "≈ ${idea.carbs} g", 14f, Ui.ACCENT, true))
        box.addView(top)
        val sub = listOfNotNull(idea.descr.ifBlank { null }, if (idea.minutes > 0) "${idea.minutes} min" else null).joinToString(" · ")
        if (sub.isNotBlank()) box.addView(Ui.text(a, sub, 12.5f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 3), 0, Ui.dp(a, 8)) })
        val r1 = Ui.row(a); val r2 = Ui.row(a)
        r1.addView(btn(a, "🍳 How to make it", true) { RecipeActivity.open(a, idea, cuisine, cat, people) }, Ui.lp(-2, -2, end = Ui.dp(a, 6)))
        r1.addView(btn(a, "🔁 Other options") { onOther(idea) }, Ui.lp(-2, -2, end = Ui.dp(a, 6)))
        r1.addView(btn(a, "❓ Ask") { onAsk(idea) })
        r2.addView(btn(a, "⭐ Save") { save(a, idea, cuisine, cat) }, Ui.lp(-2, -2, end = Ui.dp(a, 6)))
        r2.addView(btn(a, "📝 Log meal") { logMeal(a, idea.name, idea.carbs) })
        box.addView(r1); box.addView(r2, Ui.lp(top = Ui.dp(a, 6)))
        return LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(box); setPadding(0, Ui.dp(a, 8), 0, 0) }
    }

    fun save(a: Activity, idea: Idea, cuisine: String, cat: String, recipe: Recipe? = null): Long {
        val db = Db.get(a)
        db.findRecipe(idea.name)?.let { old ->
            if (recipe != null && recipe.complete) db.updateRecipe(old.id, old.copy(body = recipe.json(), serves = recipe.serves,
                minutes = if (recipe.minutes > 0) recipe.minutes else old.minutes, carbs = if (recipe.carbs > 0) recipe.carbs else old.carbs))
            Ui.toast(a, "Already in the recipe book"); return old.id
        }
        val id = db.addRecipe(Db.Recipe(0, System.currentTimeMillis(), idea.name, cat, cuisine, if ((recipe?.carbs ?: 0) > 0) recipe!!.carbs else idea.carbs,
            if ((recipe?.minutes ?: 0) > 0) recipe!!.minutes else idea.minutes, recipe?.serves ?: 0, idea.descr, recipe?.json() ?: ""))
        Ui.toast(a, "⭐ Saved in the recipe book")
        return id
    }

    /** Saves it as a meal now (carbs of her portion; the family can change them). */
    fun logMeal(a: Activity, name: String, carbs: Int) {
        val root = Ui.col(a, 18)
        root.addView(Ui.text(a, "Meal name", 13f, Ui.MUTED))
        val title = Ui.input(a, "Meal name", multi = false).apply { setText(name) }
        root.addView(title)
        root.addView(Ui.text(a, "Carbs eaten (g) — her portion", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4)) })
        val cb = Ui.input(a, "g", multi = false).apply { setText(if (carbs > 0) carbs.toString() else ""); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(cb)
        root.addView(Ui.text(a, "Saved now, with her current glucose. You can edit it later from the chart or the logbook.", 12f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(a, 8), 0, 0) })
        val d = Tr.Builder(a).setTitle("📝 Log meal").setView(root).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = cb.text.toString().toIntOrNull()
                if (c == null || c <= 0 || c > 500) { cb.error = L.s("Enter the carbs eaten"); return@setOnClickListener }
                val t = title.text.toString().trim().ifBlank { name }
                val st = Store(a); val now = System.currentTimeMillis()
                val mg = if (now - st.lastTs < 20 * 60_000) st.lastMg else 0
                Db.get(a).addMeal(Meal(0, now, t, JSONArray().put(JSONObject().put("name", t).put("portion", "").put("carbs", c)).toString(), c, c, c, mg, "", "from food ideas"))
                val app = a.applicationContext
                Thread { try { Vault.exportAll(app, pdfNow = false) } catch (_: Exception) { } }.start()
                Ui.toast(a, "Meal saved"); d.dismiss()
            }
        }
        d.show()
    }

    /** Photo → small JPEG for the AI. */
    fun jpeg(c: Context, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rot = try {
            c.contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
            } ?: 0f
        } catch (_: Exception) { 0f }
        val scale = minOf(1f, 1152f / maxOf(bmp.width, bmp.height))
        val m = Matrix().apply { postScale(scale, scale); if (rot != 0f) postRotate(rot) }
        val b = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() }
    }

    @Suppress("unused") private val keep = Intent::class
}
