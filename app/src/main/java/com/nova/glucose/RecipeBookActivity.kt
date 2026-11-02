package com.nova.glucose

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** ⭐ Recipe book: recipes saved on this phone (kept in the backup, not shared). */
class RecipeBookActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var search: EditText
    private var filter = "All"
    private val cats = listOf("All", "Breakfast", "Lunch", "Dinner", "Snacks", "School", "Sport", "Other")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.col(this, 16)
        val head = Ui.row(this)
        head.addView(Ui.title(this, "⭐ Recipe book"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        root.addView(Ui.text(this, "Saved on this phone · included in the backup", 13f, Ui.MUTED))
        search = Ui.input(this, "🔎 Search recipes", multi = false)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { render() }
        })
        root.addView(search, Ui.lp(top = Ui.dp(this, 10)))
        val (hs, row) = Ui.chipRow(this); chips = row
        root.addView(hs, Ui.lp(top = Ui.dp(this, 10)))
        list = Ui.col(this)
        root.addView(list)
        root.addView(Ui.button(this, "✦ Find new ideas with the AI", true) { startActivity(Intent(this, AskActivity::class.java)) }, Ui.lp(top = Ui.dp(this, 14)))
        root.addView(Ui.signature(this))
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val all = Db.get(this).recipes()
        chips.removeAllViews()
        cats.filter { it == "All" || all.any { r -> r.cat == it } }.forEach { c ->
            chips.addView(Ui.chip(this, c, filter == c) { filter = c; render() }, Ui.lp(-2, -2, end = Ui.dp(this, 6)))
        }
        val q = search.text.toString().trim()
        val shown = all.filter { (filter == "All" || it.cat == filter) && (q.isBlank() || it.title.contains(q, true) || it.descr.contains(q, true) || it.body.contains(q, true)) }
        list.removeAllViews()
        if (shown.isEmpty()) {
            list.addView(Ui.text(this, if (all.isEmpty()) "No recipes yet. In Ask AI, tap ⭐ Save on an idea or a recipe." else "No recipe matches.", 14f, Ui.MUTED)
                .apply { setPadding(0, Ui.dp(context, 14), 0, 0) })
            return
        }
        shown.forEach { r ->
            val card = Ui.card(this)
            val top = Ui.row(this)
            top.addView(Ui.text(this, r.title, 15.5f, Ui.TEXT, true), Ui.lp(0, weight = 1f))
            if (r.carbs > 0) top.addView(Ui.text(this, "≈ ${r.carbs} g", 14f, Ui.ACCENT, true))
            card.addView(top)
            card.addView(Ui.text(this, listOfNotNull(L.s(r.cat), L.s(r.cuisine).takeIf { r.cuisine.isNotBlank() && r.cuisine != "Any" },
                if (r.minutes > 0) "${r.minutes} min" else null, if (r.body.isBlank()) L.s("recipe not written yet") else null).joinToString(" · "), 12f, Ui.MUTED)
                .apply { setPadding(0, Ui.dp(context, 3), 0, Ui.dp(context, 8)) })
            val b = Ui.row(this)
            b.addView(FoodAi.btn(this, "Open", true) { RecipeActivity.openSaved(this, r.id) }, Ui.lp(-2, -2, end = Ui.dp(this, 6)))
            b.addView(FoodAi.btn(this, "📝 Log meal") { FoodAi.logMeal(this, r.title, r.carbs) }, Ui.lp(-2, -2, end = Ui.dp(this, 6)))
            b.addView(FoodAi.btn(this, "🗑") {
                Tr.Builder(this).setTitle("Delete this recipe?").setMessage(r.title)
                    .setPositiveButton("Delete") { _, _ -> Db.get(this).deleteRecipe(r.id); render() }.setNegativeButton("Cancel", null).show()
            })
            card.addView(b)
            card.setOnClickListener { RecipeActivity.openSaved(this, r.id) }
            list.addView(card, Ui.lp(top = Ui.dp(this, 10)))
        }
    }
}
