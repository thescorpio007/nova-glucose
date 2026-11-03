package com.nova.glucose

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 🍳 How to make it at home: ingredients for the number of people, steps, time and carbs for her portion. */
class RecipeActivity : BaseActivity() {
    private lateinit var st: Store
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var peopleTxt: TextView
    private lateinit var update: TextView
    private lateinit var askIn: EditText
    private lateinit var askOut: TextView
    private var idea = FoodAi.Idea("", "", 0, 0)
    private var cuisine = "Any"; private var cat = "Other"
    private var people = 4
    private var recipe: FoodAi.Recipe? = null
    private var bookId = 0L
    private var busy = false

    companion object {
        fun open(a: Activity, i: FoodAi.Idea, cuisine: String, cat: String, people: Int) {
            a.startActivity(Intent(a, RecipeActivity::class.java).putExtra("name", i.name).putExtra("descr", i.descr).putExtra("carbs", i.carbs)
                .putExtra("min", i.minutes).putExtra("cuisine", cuisine).putExtra("cat", cat).putExtra("people", people))
        }
        fun openSaved(a: Activity, id: Long) { a.startActivity(Intent(a, RecipeActivity::class.java).putExtra("book", id)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        val db = Db.get(this)
        bookId = intent.getLongExtra("book", 0L)
        val saved = if (bookId > 0) db.recipe(bookId) else null
        if (saved != null) {
            idea = FoodAi.Idea(saved.title, saved.descr, saved.carbs, saved.minutes); cuisine = saved.cuisine.ifBlank { "Any" }; cat = saved.cat
            recipe = FoodAi.Recipe.from(saved).takeIf { it.complete }
            people = recipe?.serves?.takeIf { it > 0 } ?: st.foodPeople
        } else {
            idea = FoodAi.Idea(intent.getStringExtra("name") ?: "", intent.getStringExtra("descr") ?: "", intent.getIntExtra("carbs", 0), intent.getIntExtra("min", 0))
            cuisine = intent.getStringExtra("cuisine") ?: "Any"; cat = intent.getStringExtra("cat") ?: "Other"
            people = intent.getIntExtra("people", st.foodPeople)
            db.findRecipe(idea.name)?.let { r -> FoodAi.Recipe.from(r).takeIf { it.complete }?.let { recipe = it; bookId = r.id } }
        }

        val root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "🍳 " + idea.name), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        root.addView(Ui.text(this, listOfNotNull(L.s(cuisine).takeIf { cuisine != "Any" }, if (idea.minutes > 0) "${idea.minutes} min" else null,
            idea.descr.ifBlank { null }).joinToString(" · "), 13f, Ui.MUTED))

        val pc = Ui.card(this)
        val pr = Ui.row(this)
        pr.addView(Ui.text(this, "People", 14f, Ui.TEXT), Ui.lp(0, weight = 1f))
        pr.addView(Ui.smallButton(this, "−") { people = (people - 1).coerceAtLeast(1); renderPeople() })
        peopleTxt = Ui.text(this, "", 16f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }
        pr.addView(peopleTxt, Ui.lp(Ui.dp(this, 40), -2))
        pr.addView(Ui.smallButton(this, "+") { people = (people + 1).coerceAtMost(12); renderPeople() })
        pc.addView(pr)
        update = Ui.text(this, "", 13f, Ui.ACCENT, true).apply { setPadding(0, Ui.dp(context, 8), 0, 0); visibility = View.GONE; setOnClickListener { make() } }
        pc.addView(update)
        root.addView(pc, Ui.lp(top = Ui.dp(this, 10)))

        status = Ui.text(this, "", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 8), 0, 0) }
        root.addView(status)
        body = Ui.col(this)
        root.addView(body)

        val br = Ui.row(this).apply { setPadding(0, Ui.dp(context, 12), 0, 0) }
        br.addView(Ui.button(this, "⭐ Save", true) { saveIt() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        br.addView(Ui.button(this, "📝 Log meal") { FoodAi.logMeal(this, idea.name, recipe?.carbs?.takeIf { it > 0 } ?: idea.carbs) }, Ui.lp(0, weight = 1f))
        root.addView(br)

        val ac = Ui.card(this)
        ac.addView(Ui.text(this, "❓ Ask about this recipe", 14f, Ui.TEXT, true))
        askIn = Ui.input(this, "e.g. can I use brown rice? can I prepare it the day before?", minLines = 2)
        ac.addView(askIn, Ui.lp(top = Ui.dp(this, 6)))
        ac.addView(Ui.button(this, "✦ Ask") { askAbout() }, Ui.lp(top = Ui.dp(this, 6)))
        askOut = Ui.text(this, "", 14f, Ui.TEXT).apply { setTextIsSelectable(true); setPadding(0, Ui.dp(context, 8), 0, 0) }
        ac.addView(askOut)
        root.addView(ac, Ui.lp(top = Ui.dp(this, 12)))
        root.addView(Ui.text(this, Foods.CARE_NOTE, 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        root.addView(Ui.signature(this))
        setContentView(sv)
        renderPeople()
        if (recipe != null) render() else make()
    }

    private fun renderPeople() {
        peopleTxt.text = "$people"
        val r = recipe
        update.visibility = if (r != null && r.serves > 0 && r.serves != people) View.VISIBLE else View.GONE
        update.text = L.s("⟳ Update the ingredients for {0} people").replace("{0}", "$people")
    }

    private fun make() {
        if (busy) return
        val p = st.current()
        if (st.key(p).isBlank()) { status.setTextColor(Ui.BAD); status.text = L.s("Add your {0} API key in Settings first.").replace("{0}", p.label); return }
        busy = true
        status.setTextColor(Ui.MUTED); status.text = L.s("{0} is writing the recipe…").replace("{0}", p.label)
        val app = applicationContext; val n = people
        Thread {
            try {
                val user = FoodAi.context(app) + "Cuisine: ${if (cuisine == "Any") "any" else cuisine}. Cooking for $n people (carbs for the patient's portion).\n" +
                    "Dish: ${idea.name}" + (if (idea.descr.isNotBlank()) " — ${idea.descr}" else "") + (if (idea.carbs > 0) " (about ${idea.carbs} g carbs for her portion)" else "")
                val raw = Providers.ask(st, p, FoodAi.recipeSystem(app), user) { s -> runOnUiThread { status.text = L.s(s) } }
                val r = FoodAi.parseRecipe(raw, idea.name, n)
                runOnUiThread {
                    if (!r.complete) { status.setTextColor(Ui.WARN); status.text = L.s("The answer had no recipe — tap to try again."); status.setOnClickListener { make() }; return@runOnUiThread }
                    recipe = r; status.text = ""
                    if (bookId > 0) Db.get(this).recipe(bookId)?.let { old -> Db.get(this).updateRecipe(bookId, old.copy(body = r.json(), serves = r.serves,
                        carbs = if (r.carbs > 0) r.carbs else old.carbs, minutes = if (r.minutes > 0) r.minutes else old.minutes)) }
                    render(); renderPeople()
                }
            } catch (e: Exception) {
                runOnUiThread { status.setTextColor(Ui.BAD); status.text = L.s(e.message ?: "Failed") }
            } finally { busy = false }
        }.start()
    }

    private fun render() {
        val r = recipe ?: return
        body.removeAllViews()
        val tiles = Ui.row(this).apply { gravity = Gravity.TOP }
        fun tile(l: String, v: String, n: String, color: Int = Ui.TEXT, last: Boolean = false) {
            val b = Ui.col(this, 10).apply { background = Ui.round(Ui.CARD2, Ui.dp(context, 12).toFloat()) }
            b.addView(Ui.text(this, l, 11.5f, Ui.MUTED)); b.addView(Ui.text(this, v, 19f, color, true))
            if (n.isNotBlank()) b.addView(Ui.text(this, n, 11.5f, Ui.MUTED))
            tiles.addView(b, Ui.lp(0, -1, weight = 1f, end = if (last) 0 else Ui.dp(this, 8)))
        }
        tile("Carbs / portion", if (r.carbs > 0) "≈ ${r.carbs} g" else "–", "patient's portion", Ui.ACCENT)
        tile("Time", if (r.minutes > 0) "${r.minutes} min" else "–", "")
        tile("Serves", "${r.serves}", "people", last = true)
        body.addView(tiles, Ui.lp(top = Ui.dp(this, 10)))
        val ic = Ui.card(this)
        ic.addView(Ui.text(this, L.s("Ingredients ({0} people)").replace("{0}", "${r.serves}").uppercase(), 12f, Ui.TEXT, true))
        r.ings.forEach { ic.addView(Ui.text(this, "•  $it", 14.5f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 4), 0, 0) }) }
        body.addView(ic, Ui.lp(top = Ui.dp(this, 10)))
        val sc = Ui.card(this)
        sc.addView(Ui.text(this, L.s("How to make it").uppercase(), 12f, Ui.TEXT, true))
        r.steps.forEachIndexed { k, s -> sc.addView(Ui.text(this, "${k + 1}.  $s", 14.5f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 6), 0, 0) }) }
        if (r.carbsNote.isNotBlank()) sc.addView(Ui.text(this, L.s("Carbs: {0} (estimates)").replace("{0}", r.carbsNote), 12.5f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        if (r.tip.isNotBlank()) sc.addView(Ui.text(this, "💡 " + r.tip, 13f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        body.addView(sc, Ui.lp(top = Ui.dp(this, 10)))
    }

    private fun saveIt() {
        val r = recipe
        if (bookId > 0 && r != null) {
            Db.get(this).recipe(bookId)?.let { old -> Db.get(this).updateRecipe(bookId, old.copy(body = r.json(), serves = r.serves)) }
            Ui.toast(this, "⭐ Saved in the recipe book"); return
        }
        bookId = FoodAi.save(this, idea, cuisine, cat, r)
    }

    private fun askAbout() {
        val qq = askIn.text.toString().trim()
        if (qq.isBlank() || busy) return
        val p = st.current()
        if (st.key(p).isBlank()) { askOut.setTextColor(Ui.BAD); askOut.text = L.s("Add your {0} API key in Settings first.").replace("{0}", p.label); return }
        busy = true
        askOut.setTextColor(Ui.MUTED); askOut.text = L.s("Asking {0}…").replace("{0}", p.label)
        val app = applicationContext
        val r = recipe
        Thread {
            try {
                val sys = "You answer a question about a home recipe for a person with type 1 diabetes. " + AiData.audience(st) + "  Short, practical, plain text, " +
                    "carb numbers for the patient's portion as estimates. ${AiData.NO_DOSE}\n" + L.aiLine()
                val user = FoodAi.context(app) + "Recipe: ${idea.name}\n" + (r?.let { "Ingredients: ${it.ings.joinToString("; ")}\nSteps: ${it.steps.joinToString(" / ")}\n" } ?: "") +
                    "Question: $qq"
                val a = Providers.ask(st, p, sys, user) { s -> runOnUiThread { askOut.text = L.s(s) } }
                runOnUiThread { askOut.setTextColor(Ui.TEXT); askOut.text = a.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?m)^#{1,6}\\s*"), "").trim() }
            } catch (e: Exception) {
                runOnUiThread { askOut.setTextColor(Ui.BAD); askOut.text = L.s(e.message ?: "Failed") }
            } finally { busy = false }
        }.start()
    }
}
