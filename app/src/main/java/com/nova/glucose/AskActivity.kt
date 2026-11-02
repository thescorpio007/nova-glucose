package com.nova.glucose

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/**
 * "Ask AI — food": questions grouped by situation, options (cuisine asked each time, cooking time, people),
 * a conversation with follow-ups, ideas as cards (recipe, other options, ask, save, log meal) and "What's in my fridge?".
 */
class AskActivity : BaseActivity() {
    private lateinit var st: Store
    private lateinit var sv: ScrollView
    private lateinit var q: EditText
    private lateinit var status: TextView
    private lateinit var convo: LinearLayout
    private lateinit var cuisineRow: LinearLayout
    private lateinit var timeRow: LinearLayout
    private lateinit var peopleTxt: TextView
    private lateinit var fridge: LinearLayout
    private lateinit var found: LinearLayout
    private lateinit var kitchen: EditText
    private lateinit var fridgeImg: ImageView
    private lateinit var fridgeCuisine: LinearLayout
    private lateinit var askBtn: TextView
    private lateinit var bookBtn: TextView
    private var busy = false

    private class Turn(val you: Boolean, val text: String, val answer: FoodAi.Answer? = null)
    private val turns = mutableListOf<Turn>()
    private var cuisine: String? = null          // chosen for the next question (asked each time)
    private var convCuisine: String? = null      // used by follow-ups of this conversation
    private var cat = "Other"
    private val items = mutableListOf<String>()
    private var photo: ByteArray? = null
    private var captureFile: File? = null

    private val groups = listOf(
        "Now" to listOf("What to eat now" to "What should she eat now?", "Treat a low" to "Her glucose is low: what to take now, and what after the recheck?",
            "Before bed" to "A good snack before bed for the night"),
        "Meals" to listOf("Breakfast" to "Breakfast ideas", "Lunch" to "Lunch ideas", "Dinner" to "Dinner ideas",
            "Plan today" to "Plan today's meals and snacks", "Plan the week" to "Plan the week's dinners (7 days)"),
        "School" to listOf("Lunchbox" to "School lunchbox ideas", "Break snack" to "Snack ideas for the school break"),
        "Sport" to listOf("Before sport" to "What to eat before sport", "After sport" to "What to eat after sport",
            "Evening after sport" to "Dinner ideas for the evening after sport (to help avoid a night low)"),
        "Special days" to listOf("Sick day" to "Food ideas for a sick day (fever, no appetite)", "Party / Eid" to "Ideas for a party or Eid: how to enjoy it",
            "Restaurant" to "Good choices at a restaurant", "Travel" to "Food ideas for travelling"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        val root = Ui.col(this, 16)
        sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "✦ Ask AI — food"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        val r = if (st.lastTs > 0 && System.currentTimeMillis() - st.lastTs < 20 * 60_000) "${st.lastMg} ${Libre.trendArrow(st.lastTrend)} ${L.s(Libre.trendText(st.lastTrend))}" else L.s("no recent reading")
        val sport = WatchData.get(this, WatchData.dayStart(System.currentTimeMillis())).ex.lastOrNull()
        root.addView(Ui.text(this, "AI: ${st.current().label} · " + L.s("glucose") + " $r" +
            (sport?.let { " · " + L.s("sport today") + " " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(it.a)) + " (" + L.s("watch") + ")" } ?: ""), 13f, Ui.MUTED))

        // "The AI sees"
        val sees = Ui.text(this, AiData.summary(this).joinToString("\n"), 12f, Ui.TEXT).apply {
            visibility = View.GONE; setPadding(0, Ui.dp(context, 4), 0, 0) }
        val seesBtn = Ui.text(this, "▸ The AI sees (last 24 h)", 13f, Ui.ACCENT).apply { setPadding(0, Ui.dp(context, 8), 0, 0) }
        seesBtn.setOnClickListener {
            val open = sees.visibility != View.VISIBLE
            sees.visibility = if (open) View.VISIBLE else View.GONE
            seesBtn.text = L.s((if (open) "▾" else "▸") + " The AI sees (last 24 h)")
        }
        root.addView(seesBtn); root.addView(sees)

        // groups of questions
        groups.forEach { (g, list) ->
            root.addView(Ui.text(this, L.s(g).uppercase(), 11.5f, Ui.MUTED, true).apply { letterSpacing = 0.06f; setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 5)) })
            val (hs, row) = Ui.chipRow(this)
            list.forEach { (label, question) -> row.addView(Ui.chip(this, label, false) { cat = FoodAi.catOf(label + " " + g); ask(L.s(question), newTopic = true) }, Ui.lp(-2, -2, end = Ui.dp(this, 6))) }
            root.addView(hs)
        }
        root.addView(Ui.text(this, L.s("Kitchen").uppercase(), 11.5f, Ui.MUTED, true).apply { letterSpacing = 0.06f; setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 5)) })
        root.addView(Ui.chip(this, "🧊 What's in my fridge?", false) { toggleFridge(true) }, Ui.lp(-2, -2))

        // fridge panel
        fridge = Ui.card(this).apply { visibility = View.GONE }
        fridge.addView(Ui.text(this, "🧊 What's in my fridge?", 16f, Ui.TEXT, true))
        fridge.addView(Ui.text(this, "Type what you have or take a photo — the AI suggests dishes.", 12.5f, Ui.MUTED))
        val pb = Ui.row(this)
        pb.addView(Ui.button(this, "📷 Photo of the fridge", true) { takePhoto() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        pb.addView(Ui.button(this, "🖼 From gallery") { pickPhoto() }, Ui.lp(0, weight = 1f))
        fridge.addView(pb, Ui.lp(top = Ui.dp(this, 8)))
        fridgeImg = ImageView(this).apply { visibility = View.GONE; adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP }
        fridge.addView(fridgeImg, Ui.lp(-1, Ui.dp(this, 140), top = Ui.dp(this, 8)))
        fridge.addView(Ui.text(this, "Found · tap to remove", 12f, Ui.MUTED, true).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4)) })
        found = Ui.col(this); fridge.addView(found)
        kitchen = Ui.input(this, "Also in the kitchen, e.g. couscous, rice, olive oil, spices", minLines = 2)
        fridge.addView(kitchen, Ui.lp(top = Ui.dp(this, 8)))
        fridge.addView(Ui.text(this, "Cuisine for this question", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4)) })
        val (fhs, fcr) = Ui.chipRow(this); fridgeCuisine = fcr; fridge.addView(fhs)
        fridge.addView(Ui.button(this, "✦ What can we cook?", true) { cookFromFridge() }, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(fridge, Ui.lp(top = Ui.dp(this, 8)))

        // options
        val oc = Ui.card(this)
        oc.addView(Ui.text(this, L.s("Options (optional)").uppercase(), 12f, Ui.TEXT, true))
        oc.addView(Ui.text(this, "Cuisine — choose for each question", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 4)) })
        val (chs, cr) = Ui.chipRow(this); cuisineRow = cr; oc.addView(chs)
        val tr = Ui.row(this).apply { setPadding(0, Ui.dp(context, 10), 0, 0) }
        tr.addView(Ui.text(this, "Cooking time", 13f, Ui.MUTED), Ui.lp(0, weight = 1f))
        timeRow = Ui.row(this); tr.addView(timeRow); oc.addView(tr)
        val pr = Ui.row(this).apply { setPadding(0, Ui.dp(context, 10), 0, 0) }
        pr.addView(Ui.text(this, "People", 13f, Ui.MUTED), Ui.lp(0, weight = 1f))
        pr.addView(Ui.smallButton(this, "−") { st.foodPeople = (st.foodPeople - 1).coerceAtLeast(1); renderOptions() })
        peopleTxt = Ui.text(this, "", 15f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }
        pr.addView(peopleTxt, Ui.lp(Ui.dp(this, 40), -2))
        pr.addView(Ui.smallButton(this, "+") { st.foodPeople = (st.foodPeople + 1).coerceAtMost(12); renderOptions() })
        oc.addView(pr)
        oc.addView(Ui.text(this, L.s("Carbs per portion: meals {0}–{1} g · snacks {2}–{3} g (her plan)").replace("{0}", "${st.mealMin}").replace("{1}", "${st.mealMax}")
            .replace("{2}", "${st.snackMin}").replace("{3}", "${st.snackMax}"), 12.5f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        if (st.foodNotes.isNotBlank()) oc.addView(Ui.text(this, L.s("Food notes (always respected): {0}").replace("{0}", st.foodNotes), 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 4), 0, 0) })
        root.addView(oc, Ui.lp(top = Ui.dp(this, 12)))

        // conversation
        convo = Ui.col(this)
        root.addView(convo, Ui.lp(top = Ui.dp(this, 6)))
        status = Ui.text(this, "", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 8), 0, 0) }
        root.addView(status)
        val ir = Ui.row(this).apply { setPadding(0, Ui.dp(context, 10), 0, 0) }
        q = Ui.input(this, "Ask about food… e.g. \"ideas with fish for dinner\"", minLines = 2)
        ir.addView(q, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        askBtn = Ui.text(this, "✦ Ask", 16f, android.graphics.Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = Ui.round(Ui.ACCENT, Ui.dp(context, 14).toFloat())
            setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14))
            setOnClickListener { val t = q.text.toString().trim(); if (t.isNotBlank()) ask(t, newTopic = false) else Ui.toast(this@AskActivity, "Type a question or pick one above") }
        }
        ir.addView(askBtn)
        root.addView(ir)
        val fr = Ui.row(this).apply { setPadding(0, Ui.dp(context, 8), 0, 0) }
        fr.addView(Ui.text(this, "🗑 New conversation", 13f, Ui.MUTED).apply { setOnClickListener { newConversation() } }, Ui.lp(0, weight = 1f))
        bookBtn = Ui.text(this, "", 13f, Ui.ACCENT).apply { setOnClickListener { startActivity(Intent(this@AskActivity, RecipeBookActivity::class.java)) } }
        fr.addView(bookBtn)
        root.addView(fr)
        root.addView(Ui.text(this, Foods.CARE_NOTE, 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        root.addView(Ui.signature(this))
        setContentView(sv)
        renderOptions(); renderFound(); renderConvo()
        if (intent?.getBooleanExtra("fridge", false) == true) toggleFridge(true)
        intent?.getStringExtra("q")?.let { s -> if (s.isNotBlank()) { q.setText(s); q.setSelection(s.length) } }
    }

    override fun onResume() { super.onResume(); bookBtn.text = L.s("⭐ Recipe book ({0})").replace("{0}", "${Db.get(this).recipes().size}") }

    private fun renderOptions() {
        fun fill(row: LinearLayout) {
            row.removeAllViews()
            FoodAi.CUISINES.forEach { c -> row.addView(Ui.chip(this, c, cuisine == c) { cuisine = if (cuisine == c) null else c; renderOptions() }, Ui.lp(-2, -2, end = Ui.dp(this, 6))) }
        }
        fill(cuisineRow); fill(fridgeCuisine)
        timeRow.removeAllViews()
        listOf(0 to "Any", 10 to "10 min", 30 to "30 min", 60 to "60 min").forEach { (m, l) ->
            timeRow.addView(Ui.chip(this, l, st.foodTime == m) { st.foodTime = m; renderOptions() }, Ui.lp(-2, -2, end = Ui.dp(this, 4)))
        }
        peopleTxt.text = "${st.foodPeople}"
    }

    private fun toggleFridge(open: Boolean) {
        fridge.visibility = if (open) View.VISIBLE else View.GONE
        if (open) sv.post { sv.smoothScrollTo(0, fridge.top - Ui.dp(this, 10)) }
    }

    private fun newConversation() { turns.clear(); convCuisine = null; cuisine = null; renderOptions(); renderConvo(); q.setText(""); status.text = "" }

    // ---------------------------------------------------------------- asking
    private fun ask(question: String, newTopic: Boolean, images: List<ByteArray> = emptyList()) {
        if (busy) return
        val p = st.current()
        if (st.key(p).isBlank()) { status.setTextColor(Ui.BAD); status.text = L.s("Add your {0} API key in Settings first.").replace("{0}", p.label); return }
        if (newTopic && turns.isNotEmpty() && cuisine == null) convCuisine = null
        val cz = cuisine ?: convCuisine
        if (cz == null) {
            // the family asked to choose the cuisine for each question
            val opts = FoodAi.CUISINES.toTypedArray()
            Tr.Builder(this).setTitle("Which cuisine?").setItems(opts.map { L.s(it) }.toTypedArray()) { _, w -> cuisine = opts[w]; renderOptions(); ask(question, newTopic, images) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        convCuisine = cz; cuisine = null; renderOptions()
        busy = true
        turns += Turn(true, question)
        renderConvo()
        q.setText("")
        q.hint = L.s("Ask a follow-up…")
        status.setTextColor(Ui.MUTED); status.text = L.s("Asking {0}…").replace("{0}", p.label)
        val app = applicationContext
        val opts = buildString {
            append("Options: cuisine ${if (cz == "Any") "any (mix Tunisian, Mediterranean and international)" else cz}")
            if (st.foodTime > 0) append(", ready in ${st.foodTime} minutes or less")
            append(", cooking for ${st.foodPeople} people (carbs always for her portion).\n")
        }
        val history = turns.dropLast(1).takeLast(8).joinToString("\n") { t -> (if (t.you) "Family: " else "AI: ") + t.text.take(1500) }
        Thread {
            try {
                val user = FoodAi.context(app) + opts + (if (history.isNotBlank()) "Conversation so far:\n$history\n" else "") + "Family now asks: $question"
                val raw = Providers.ask(st, p, FoodAi.ideasSystem(app), user, images) { s -> runOnUiThread { status.text = L.s(s) } }
                val ans = FoodAi.parse(raw)
                runOnUiThread { turns += Turn(false, raw, ans); status.text = ""; renderConvo(scroll = true) }
            } catch (e: Exception) {
                runOnUiThread { status.setTextColor(Ui.BAD); status.text = L.s(e.message ?: "Failed") }
            } finally { busy = false }
        }.start()
    }

    private fun renderConvo(scroll: Boolean = false) {
        convo.removeAllViews()
        var lastAi: View? = null
        turns.forEach { t ->
            if (t.you) {
                val r = Ui.row(this).apply { gravity = Gravity.END; setPadding(Ui.dp(context, 40), Ui.dp(context, 10), 0, 0) }
                r.addView(Ui.text(this, t.text, 14.5f, android.graphics.Color.WHITE).apply {
                    background = Ui.round(Ui.ACCENT, Ui.dp(context, 16).toFloat())
                    setPadding(Ui.dp(context, 12), Ui.dp(context, 9), Ui.dp(context, 12), Ui.dp(context, 9)) })
                convo.addView(r, Ui.lp(-1, -2))
            } else {
                val a = t.answer ?: FoodAi.parse(t.text)
                val box = Ui.card(this)
                if (a.intro.isNotBlank()) box.addView(Ui.text(this, a.intro, 14.5f, Ui.TEXT).apply { setTextIsSelectable(true) })
                a.ideas.forEach { idea -> box.addView(FoodAi.card(this, idea, convCuisine ?: "Any", cat, st.foodPeople,
                    onOther = { i -> ask(L.s("Give 3 other options similar to {0} (some lower in carbs, some quicker)").replace("{0}", i.name), newTopic = false) },
                    onAsk = { i -> q.setText(L.s("About {0}: ").replace("{0}", i.name)); q.setSelection(q.text.length); q.requestFocus()
                        sv.post { sv.smoothScrollTo(0, q.bottom) } })) }
                if (a.note.isNotBlank()) box.addView(Ui.text(this, a.note, 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
                box.addView(Ui.text(this, "Carbs are estimates. Food ideas only — the app never suggests insulin doses.", 11.5f, Ui.MUTED)
                    .apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
                convo.addView(box, Ui.lp(top = Ui.dp(this, 10)))
                lastAi = box
            }
        }
        if (turns.isEmpty()) convo.addView(Ui.text(this, "Pick a question above or type your own. You can then ask follow-ups.", 13f, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        if (scroll) lastAi?.let { v -> sv.post { sv.smoothScrollTo(0, convo.top + v.top - Ui.dp(this, 8)) } }
    }

    // ---------------------------------------------------------------- fridge
    private fun renderFound() {
        found.removeAllViews()
        val (hs, row) = Ui.chipRow(this)
        items.forEach { i -> row.addView(Ui.chip(this, "$i ✕", false) { items.remove(i); renderFound() }, Ui.lp(-2, -2, end = Ui.dp(this, 6))) }
        row.addView(Ui.chip(this, "✏️ add", true) { addItem() })
        found.addView(hs)
    }

    private fun addItem() {
        val e = Ui.input(this, "e.g. eggs, tomatoes", multi = false)
        Tr.Builder(this).setTitle("Add food").setView(LinearLayout(this).apply { setPadding(Ui.dp(context, 18), Ui.dp(context, 8), Ui.dp(context, 18), 0); addView(e, Ui.lp()) })
            .setPositiveButton("Add") { _, _ -> e.text.toString().split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { if (it !in items) items += it }; renderFound() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "capture").apply { mkdirs() }
        val f = File(dir, "fridge_${System.currentTimeMillis()}.jpg"); captureFile = f
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(this, "com.nova.glucose.files", f))
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, REQ_PHOTO) } catch (_: Exception) { Ui.toast(this, "No camera app found") }
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(i, L.s("Fridge photo")), REQ_GALLERY)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        val uri: Uri = when (req) { REQ_PHOTO -> captureFile?.let { Uri.fromFile(it) }; REQ_GALLERY -> data?.data; else -> null } ?: return
        identify(uri)
    }

    private fun identify(uri: Uri) {
        val p = st.current()
        if (st.key(p).isBlank()) { status.setTextColor(Ui.BAD); status.text = L.s("Add your {0} API key in Settings first.").replace("{0}", p.label); return }
        toggleFridge(true)
        status.setTextColor(Ui.MUTED); status.text = L.s("Looking at the photo…")
        val app = applicationContext
        Thread {
            try {
                val jpg = FoodAi.jpeg(app, uri) ?: throw Exception("Couldn't read the photo")
                photo = jpg
                runOnUiThread { fridgeImg.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size)); fridgeImg.visibility = View.VISIBLE }
                val raw = Providers.ask(st, p, FoodAi.FRIDGE_SYSTEM + "\n" + L.aiLine(), "What foods are in these photos?", listOf(jpg)) { s -> runOnUiThread { status.text = L.s(s) } }
                val list = raw.lines().joinToString(" ").substringAfter("ITEMS:", raw).split(',', ';', '\n').map { it.trim().trim('.', '-', '•', '*').trim() }
                    .filter { it.isNotBlank() && it.length < 40 }
                runOnUiThread { list.forEach { if (it !in items) items += it }; renderFound(); status.text = L.s("✓ {0} foods found — remove or add, then tap \"What can we cook?\"").replace("{0}", "${list.size}") }
            } catch (e: Exception) {
                runOnUiThread { status.setTextColor(Ui.BAD); status.text = L.s(e.message ?: "Failed") }
            }
        }.start()
    }

    private fun cookFromFridge() {
        val extra = kitchen.text.toString().trim()
        if (items.isEmpty() && extra.isBlank()) { Ui.toast(this, "Take a photo or add what you have first"); return }
        cat = "Other"
        ask(L.s("What can we cook with what we have?") + " " + (items + listOfNotNull(extra.ifBlank { null })).joinToString(", "), newTopic = true)
    }

    companion object { private const val REQ_PHOTO = 31; private const val REQ_GALLERY = 32 }
}
