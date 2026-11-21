package com.nova.glucose

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** AI review of the last 1, 7 or 14 days: patterns (meals, times, sick days) and questions for the doctor. Never doses. */
class AiReviewActivity : BaseActivity() {
    private lateinit var st: Store
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var convo: LinearLayout
    private lateinit var input: android.widget.EditText
    private lateinit var askRow: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var sv: ScrollView
    private var conv: AiChats.Conv? = null
    private var days = 7
    private var busy = false
    /** 💬 Ask Nova AI: a free conversation (not tied to a review), days = 0. */
    private var coach = false
    // 📷 🖼 🎤 🔊 (Ask Nova): photos waiting to be sent, voice, reading answers aloud
    private val pics = mutableListOf<ByteArray>()
    private lateinit var thumbs: LinearLayout
    private var capture: java.io.File? = null
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private val simple get() = coach && st.isPatient
    private val REQ_CAM = 41; private val REQ_GAL = 42; private val REQ_MIC = 43

    private val system = """
You review the glucose data of a person with type 1 diabetes (a child or teenager — see the profile), to prepare the next talk with their diabetes care team.
Write in plain text for a phone screen, with exactly these two headings:
What the AI noticed
(4 to 8 short bullets "• …": when highs and lows happen (time of day, after which meals or foods), nights, sick days or other incidents,
how photo carb estimates were corrected, anything improving; when watch data is given, how sport, steps, sleep and resting heart rate
line up with her glucose (e.g. night lows after sport, higher mornings after short nights); quote numbers from the data)
Questions for the doctor
(2 to 4 short bullets "• …" the family can ask)
When Lantus (long-acting) dose changes are given, say what changed before → after (3, 7, 14 days) and whether nights and fasting improved; if too few days, say it is too early.
Rules: ${AiData.OPEN_DATA} Do not diagnose. Do not invent data; if there is too little data, say so. No markdown symbols other than "•".
""".trimIndent()

    private val chatSystem = """
You are discussing a person's type 1 diabetes data with their family, after an AI review (shown in the conversation). You have all the recorded data:
the daily log of every recorded day (glucose, insulin units, carbs, with averages) and the details of the review period. Answer the new question directly
in plain text for a phone screen: clear, with the exact numbers, totals, averages and times asked for. Use "•" bullets only when listing.
When asked about Lantus (long-acting) dose changes, compare before → after using the given 3/7/14-day numbers and the nights; say if it is too early.
Rules: ${AiData.OPEN_DATA} Do not diagnose. Do not invent data; if the data does not show it, say so. No markdown other than "•".
""".trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        coach = intent.getBooleanExtra("coach", false)
        val root = Ui.col(this, 16)
        sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, if (coach) "💬 Ask Nova AI" else "📊 AI review"), Ui.lp(0, weight = 1f))
        lateinit var lb: TextView
        lb = Ui.smallButton(this, "🌐 ${L.short(revLang())} ▾") {
            Lang.pick(this, lb, revLang()) { code -> st.aiReviewLang = code; lb.text = "🌐 ${L.short(code)} ▾" }
        }.apply { tag = "free" }
        head.addView(lb, Ui.lp(-2, Ui.dp(this, 36), end = Ui.dp(this, 6)))
        head.addView(Ui.smallButton(this, "🕘") { history() }, Ui.lp(-2, Ui.dp(this, 36), end = Ui.dp(this, 6)))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        root.addView(Ui.text(this, "AI: ${st.current().label} · " + L.s("looks at the patient's profile ({0}), readings, meals, insulin times, incidents and watch data (activity, sleep, heart rate)")
            .replace("{0}", listOfNotNull("age ${st.age}", st.heightCm.takeIf { it > 0 }?.let { "${it.fmt()} cm" }, st.weightKg.takeIf { it > 0 }?.let { "${it.fmt()} kg" }).joinToString(", ")), 13f, Ui.MUTED))
        chips = Ui.row(this)
        if (!coach) {
            root.addView(chips, Ui.lp(top = Ui.dp(this, 10)))
            root.addView(Ui.button(this, "✦ New review", true) { review() }, Ui.lp(top = Ui.dp(this, 10)))
        } else root.addView(Ui.button(this, "+ New conversation") { conv = null; render() }, Ui.lp(top = Ui.dp(this, 10)))
        if (coach) {
            tts = android.speech.tts.TextToSpeech(this) { ok -> ttsReady = ok == android.speech.tts.TextToSpeech.SUCCESS
                if (ttsReady) try { tts?.language = L.loc() } catch (_: Exception) { } }
            if (simple) {        // her phone: three big buttons
                root.addView(Ui.text(this, L.s("Hi {0} 👋 Take a photo of a snack or ask me anything.").replace("{0}", st.patientName.substringBefore(" ").ifBlank { "" }), 15f, Ui.MUTED)
                    .apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
                fun big(t: String, col: Int, go: () -> Unit) = Ui.button(this, t, true) { go() }.apply { setBackgroundColor(col); textSize = 20f; minHeight = Ui.dp(context, 64) }
                root.addView(big("📷 " + L.s("Product"), 0xFF2EC4B6.toInt()) { camera() }, Ui.lp(top = Ui.dp(this, 10)))
                root.addView(big("🖼 " + L.s("Photo from gallery"), 0xFF5E35B1.toInt()) { gallery() }, Ui.lp(top = Ui.dp(this, 10)))
                root.addView(big("🎤 " + L.s("Ask with my voice"), 0xFF1565C0.toInt()) { mic() }, Ui.lp(top = Ui.dp(this, 10)))
            }
        }
        status = Ui.text(this, "", 13f, Ui.MUTED)
        root.addView(status, Ui.lp(top = Ui.dp(this, 8)))
        convo = Ui.col(this)
        root.addView(convo, Ui.lp(top = Ui.dp(this, 6)))
        askRow = Ui.row(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        input = Ui.input(this, if (coach) "Ask Nova AI about her readings, progress, food, routine…" else "Ask the AI about this review…", multi = true)
        askRow.addView(input, Ui.lp(0, -2, weight = 1f, end = Ui.dp(this, 8)))
        askRow.addView(Ui.button(this, "Send", true) { ask() }, Ui.lp(-2, -2))
        root.addView(askRow, Ui.lp(top = Ui.dp(this, 10)))
        thumbs = Ui.row(this)
        if (coach) {
            root.addView(thumbs, Ui.lp(top = Ui.dp(this, 6)))
            if (!simple) {
                val tools = Ui.row(this)
                tools.addView(Ui.smallButton(this, "📷 " + L.s("Camera")) { camera() }, Ui.lp(0, Ui.dp(this, 40), weight = 1f, end = Ui.dp(this, 6)))
                tools.addView(Ui.smallButton(this, "🖼 " + L.s("Gallery")) { gallery() }, Ui.lp(0, Ui.dp(this, 40), weight = 1f, end = Ui.dp(this, 6)))
                tools.addView(Ui.smallButton(this, "🎤 " + L.s("Speak")) { mic() }, Ui.lp(0, Ui.dp(this, 40), weight = 1f))
                root.addView(tools, Ui.lp(top = Ui.dp(this, 6)))
                root.addView(Ui.text(this, "Up to 4 photos per question (products, labels, screenshots) · tap an answer to hear it 🔊", 11.5f, Ui.MUTED))
            }
        }
        actions = Ui.row(this)
        actions.addView(Ui.button(this, "📤 Share") { share() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        actions.addView(Ui.button(this, "📄 Doctor report") { DoctorReport.pick(this) }, Ui.lp(0, weight = 1f))
        root.addView(actions, Ui.lp(top = Ui.dp(this, 12)))
        root.addView(Ui.text(this, "AI review, not medical advice. It never suggests insulin doses — discuss changes with the diabetes care team. " +
            "The last review is added to the doctor report. Conversations are kept 30 days (🕘).", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        root.addView(Ui.signature(this))
        setContentView(sv)
        val wanted = intent.getStringExtra("conv")
        conv = if (wanted != null) AiChats.all(this).firstOrNull { it.id == wanted } else if (coach) AiChats.latestCoach(this) else AiChats.latest(this)
        conv?.let { if (it.days > 0) days = it.days }
        renderChips(); render()
    }

    private fun Double.fmt() = if (this % 1.0 == 0.0) this.toInt().toString() else "%.1f".format(Locale.US, this)

    /** The review's own language (defaults to the app language). */
    private fun revLang() = st.aiReviewLang.ifBlank { L.lang }
    private fun langLine(lg: String) = if (lg != "en") "\nWrite in ${L.name(lg)}." else ""

    private fun renderChips() {
        chips.removeAllViews()
        listOf(1 to "Today", 3 to "3 days", 7 to "7 days", 14 to "14 days").forEach { (d, l) ->
            chips.addView(Ui.chip(this, l, days == d) { days = d; renderChips() }, Ui.lp(-2, -2, end = Ui.dp(this, 6)))
        }
    }

    private fun bubble(text: String, me: Boolean) {
        val b = Ui.col(this).apply {
            background = Ui.round(if (me) 0xFF1E6E68.toInt() else Ui.CARD, Ui.dp(context, 16).toFloat())
            setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 14), Ui.dp(context, 10))
        }
        b.addView(Ui.text(this, if (me) L.s("You") else "✦ AI", 11.5f, if (me) 0xFFC8F0EC.toInt() else Ui.ACCENT, true))
        b.addView(Ui.text(this, text, 15f, Ui.TEXT).apply { setTextIsSelectable(!coach) })
        if (coach && !me) { b.addView(Ui.text(this, "🔊 " + L.s("Read aloud"), 11.5f, Ui.MUTED)); b.setOnClickListener { speak(text) } }
        val holder = LinearLayout(this).apply { gravity = if (me) android.view.Gravity.END else android.view.Gravity.START }
        holder.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (me) marginStart = Ui.dp(this@AiReviewActivity, 36) else marginEnd = Ui.dp(this@AiReviewActivity, 24) })
        convo.addView(holder, Ui.lp(top = Ui.dp(this, 8)))
    }

    private fun render() {
        convo.removeAllViews()
        val c = conv
        if (coach && (c == null || c.turns.isEmpty())) {
            convo.addView(Ui.text(this, "Talk with Nova AI about her readings and progress: it sees every reading, meal, insulin dose (I and LI), incident and watch day, and what is happening right now. Ask anything — how this week compares, what to watch tonight, ideas for school days, questions for the doctor.", 14f, Ui.MUTED))
            status.text = ""; askRow.visibility = android.view.View.VISIBLE; actions.visibility = android.view.View.GONE; return
        }
        if (c == null || c.turns.isEmpty()) {
            convo.addView(Ui.text(this, "Choose a period and tap ✦ New review. Then ask the AI anything about it in the same conversation.", 14f, Ui.MUTED))
            askRow.visibility = android.view.View.GONE; actions.visibility = android.view.View.GONE; status.text = ""; return
        }
        status.text = if (c.days == 0) L.s("Conversation of {0}").replace("{0}", SimpleDateFormat("EEE d MMM HH:mm", L.loc()).format(Date(c.ts))) else L.s("Review of {0} · {1} day(s)").replace("{0}", SimpleDateFormat("EEE d MMM HH:mm", L.loc()).format(Date(c.ts))).replace("{1}", "${c.days}")
        c.turns.forEach { bubble(it.second, it.first) }
        askRow.visibility = android.view.View.VISIBLE; actions.visibility = android.view.View.VISIBLE
    }

    private fun clean(a: String) = a.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?m)^#{1,6}\\s*"), "").replace(Regex("(?m)^\\s*[*-]\\s+"), "• ").trim()

    private fun review() {
        if (busy) return
        val p = st.current()
        if (st.key(p).isBlank()) { status.setTextColor(Ui.BAD); status.text = "Add your ${p.label} API key in Settings first."; return }
        busy = true
        status.setTextColor(Ui.MUTED); status.text = "${p.label} is reviewing ${if (days == 1) "today" else "the last $days days"}…"
        val app = applicationContext; val d = days; val lg = revLang()
        Thread {
            try {
                val a = Providers.ask(st, p, system + "\n" + AiData.audience(st) + (if (lg != "en") "\nWrite the whole review in ${L.name(lg)}, including the two headings." else ""), AiData.period(app, d) + "\n" + AiData.dailyLog(app, 30) + "\n" + AiData.live(app)) { s -> runOnUiThread { status.text = s } }
                val txt = clean(a)
                st.aiReview = txt; st.aiReviewTs = System.currentTimeMillis(); st.aiReviewDays = d; st.aiReviewDoneLang = lg
                val c = AiChats.Conv(java.util.UUID.randomUUID().toString().take(10), System.currentTimeMillis(), d, mutableListOf(false to txt))
                AiChats.save(app, c)
                runOnUiThread { conv = c; status.setTextColor(Ui.MUTED); render() }
            } catch (e: Exception) {
                runOnUiThread { status.setTextColor(Ui.BAD); status.text = e.message ?: "Failed" }
            } finally { busy = false }
        }.start()
    }

    /** A follow-up question in the same conversation: the AI gets the data, the review and the earlier questions. */
    private fun ask() {
        var q = input.text.toString().trim()
        if (busy) return
        if (q.isEmpty() && pics.isNotEmpty()) q = L.s(if (pics.size > 1) "Which one is the better choice for her? Compare them." else "Is this a good choice for her? How much can she have?")
        if (q.isEmpty()) return
        val imgs = pics.toList(); pics.clear(); renderThumbs()
        val c = conv ?: (if (coach) AiChats.Conv(java.util.UUID.randomUUID().toString().take(10), System.currentTimeMillis(), 0, mutableListOf()).also { conv = it } else return)
        val p = st.current()
        busy = true; input.setText("")
        c.turns += true to (q + if (imgs.isNotEmpty()) "  [📷 ×${imgs.size}]" else ""); AiChats.save(this, c); render()
        status.setTextColor(Ui.MUTED); status.text = L.s("✦ AI is answering…")
        sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
        val app = applicationContext; val lg = revLang()
        Thread {
            try {
                val user = AiData.dailyLog(app) + "\nDetails of the " + (if (c.days == 0) "last 14 days" else "review period") + ":\n" + AiData.period(app, if (c.days == 0) 14 else c.days) +
                    "\n" + AiData.live(app) + "\n\nThe conversation so far:\n" +
                    c.turns.dropLast(1).joinToString("\n\n") { (me, t) -> (if (me) "Family: " else "AI: ") + t } + "\n\nNew question from the family: $q"
                val sys = (if (c.days == 0) Coach.SYSTEM else chatSystem) + (if (imgs.isNotEmpty()) "\n" + Coach.PRODUCTS else "") +
                    (if (simple) "\n" + Coach.SIMPLE else "") + "\n" + AiData.audience(st) + langLine(lg)
                val a = clean(Providers.ask(st, p, sys, user, imgs) { s -> runOnUiThread { status.text = s } })
                c.turns += false to a; AiChats.save(app, c)
                runOnUiThread { render(); sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }; if (simple) speak(a) }
            } catch (e: Exception) {
                runOnUiThread { status.setTextColor(Ui.BAD); status.text = e.message ?: "Failed" }
            } finally { busy = false }
        }.start()
    }

    // ---------------------------------------------------------------- 📷 🖼 🎤 🔊
    private fun camera() {
        if (pics.size >= 4) { Ui.toast(this, "Up to 4 photos"); return }
        val dir = java.io.File(cacheDir, "capture").apply { mkdirs() }
        val f = java.io.File(dir, "ask_${System.currentTimeMillis()}.jpg"); capture = f
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.nova.glucose.files", f)
        try { startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_CAM) } catch (_: Exception) { Ui.toast(this, "No camera app found") }
    }
    private fun gallery() {
        if (pics.size >= 4) { Ui.toast(this, "Up to 4 photos"); return }
        startActivityForResult(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), L.s("Photos or screenshots")), REQ_GAL)
    }
    private fun mic() {
        try { startActivityForResult(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, L.loc().toLanguageTag())
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, L.s("Ask Nova…")), REQ_MIC) } catch (_: Exception) { Ui.toast(this, "Voice input is not available on this phone") }
    }
    private fun speak(t: String) {
        if (!ttsReady) { Ui.toast(this, "Reading aloud is not available"); return }
        try { tts?.speak(t.replace("•", "").replace(Regex("[🥇🥈🥉📷🔊✦]"), ""), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "nova") } catch (_: Exception) { }
    }
    override fun onDestroy() { try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }; super.onDestroy() }

    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        if (req == REQ_MIC) {
            val t = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            input.setText(t); input.setSelection(t.length)
            if (simple) ask()
            return
        }
        Thread {
            try {
                if (req == REQ_CAM) capture?.let { addPic(android.net.Uri.fromFile(it)) }
                if (req == REQ_GAL) { val clip = data?.clipData
                    if (clip != null) for (k in 0 until clip.itemCount) addPic(clip.getItemAt(k).uri) else data?.data?.let { addPic(it) } }
                runOnUiThread { renderThumbs(); if (simple && pics.isNotEmpty()) ask() }
            } catch (e: Exception) { runOnUiThread { Ui.toast(this, "Couldn't read that photo") } }
        }.start()
    }
    private fun addPic(uri: android.net.Uri) {
        if (pics.size >= 4) return
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return
        val rot = try { contentResolver.openInputStream(uri)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f } } ?: 0f } catch (_: Exception) { 0f }
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val m = android.graphics.Matrix().apply { postScale(scale, scale); if (rot != 0f) postRotate(rot) }
        val b = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        pics += java.io.ByteArrayOutputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
    }
    private fun renderThumbs() {
        thumbs.removeAllViews()
        pics.forEachIndexed { k, j ->
            val bm = android.graphics.BitmapFactory.decodeByteArray(j, 0, j.size)
            thumbs.addView(android.widget.ImageView(this).apply { setImageBitmap(bm); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setOnClickListener { pics.removeAt(k); renderThumbs() } }, Ui.lp(Ui.dp(this, 64), Ui.dp(this, 64), end = Ui.dp(this, 6)))
        }
        if (pics.isNotEmpty()) thumbs.addView(Ui.text(this, L.s("{0} photo(s) · tap one to remove · type a question or just Send").replace("{0}", "${pics.size}"), 11.5f, Ui.MUTED), Ui.lp(0, -2, weight = 1f))
    }

    private fun history() {
        val l = AiChats.all(this)
        if (l.isEmpty()) { Ui.toast(this, "No conversations yet"); return }
        val df = SimpleDateFormat("EEE d MMM HH:mm", L.loc())
        val names = l.map { c -> val qs = c.turns.count { it.first }
            "${df.format(Date(c.ts))} · " + (if (c.days == 0) "💬 · " else "📊 ${c.days} d · ") + (if (qs == 0) L.s("review only") else L.s("{0} question(s)").replace("{0}", "$qs")) +
                (c.turns.lastOrNull { it.first }?.let { "\n" + it.second.take(60) } ?: "") as CharSequence }.toTypedArray()
        Tr.Builder(this).setTitle("🕘 " + L.s("Past conversations (30 days)")).setItems(names) { _, i ->
            conv = l[i]; if (l[i].days > 0) days = l[i].days; renderChips(); render() }.setNegativeButton("Close", null).show()
    }

    private fun share() {
        val c = conv ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Nova Glucose AI review")
            .putExtra(Intent.EXTRA_TEXT, "Nova Glucose — AI review (${c.days} day(s), " + SimpleDateFormat("d MMM yyyy", L.loc()).format(Date(c.ts)) + ")\n\n" +
                c.turns.joinToString("\n\n") { (me, t) -> (if (me) "Q: " else "") + t } + "\n\nAI review, not medical advice."), "Share the review"))
    }
}

/** AI review conversations, kept 30 days on this phone. */
object AiChats {
    class Conv(val id: String, val ts: Long, val days: Int, val turns: MutableList<Pair<Boolean, String>>)
    private const val FILE = "ai_chats.json"
    private const val KEEP = 30L * 86_400_000

    @Synchronized fun all(c: android.content.Context): List<Conv> {
        val f = java.io.File(c.filesDir, FILE); if (!f.exists()) return emptyList()
        return try {
            val a = org.json.JSONArray(f.readText()); val now = System.currentTimeMillis()
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val t = o.optJSONArray("t") ?: org.json.JSONArray()
                Conv(o.optString("id"), o.optLong("ts"), o.optInt("d", 7), (0 until t.length()).map { t.getJSONObject(it) }.map { it.optBoolean("me") to it.optString("x") }.toMutableList())
            }.filter { now - it.ts < KEEP }.sortedByDescending { it.ts }
        } catch (_: Exception) { emptyList() }
    }

    fun latest(c: android.content.Context) = all(c).firstOrNull { it.days > 0 }
    fun latestCoach(c: android.content.Context) = all(c).firstOrNull { it.days == 0 }

    @Synchronized fun save(c: android.content.Context, conv: Conv) {
        val l = all(c).filter { it.id != conv.id } + conv
        val a = org.json.JSONArray()
        l.sortedByDescending { it.ts }.take(60).forEach { x ->
            a.put(org.json.JSONObject().put("id", x.id).put("ts", x.ts).put("d", x.days).put("t", org.json.JSONArray().apply {
                x.turns.forEach { (me, t) -> put(org.json.JSONObject().put("me", me).put("x", t)) } }))
        }
        java.io.File(c.filesDir, FILE).writeText(a.toString())
    }
}
