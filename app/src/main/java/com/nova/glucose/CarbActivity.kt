package com.nova.glucose

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Estimate carbs from food photos or a short video, using the AI chosen in Settings. */
class CarbActivity : BaseActivity() {

    private class Item(var name: String, var portion: String, var carbs: Int, var low: Int, var high: Int)

    private lateinit var st: Store
    private lateinit var thumbs: LinearLayout
    private lateinit var note: EditText
    private lateinit var status: TextView
    private lateinit var result: LinearLayout
    private lateinit var estimateBtn: View
    private val images = mutableListOf<ByteArray>()
    private val items = mutableListOf<Item>()
    private var confidence = ""
    private var aiNotes = ""
    private var captureFile: File? = null
    private var mgAtMeal = 0

    companion object {
        private const val REQ_PHOTO = 1; private const val REQ_GALLERY = 2
        private const val REQ_VIDEO = 3; private const val REQ_VIDEO_PICK = 4
        private const val MAX_IMAGES = 8
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        mgAtMeal = if (System.currentTimeMillis() - st.lastTs < 15 * 60_000) st.lastMg else 0
        // The camera app can close this screen in the background: photos are kept in a session folder.
        if (savedInstanceState == null) sessionDir().deleteRecursively()
        else {
            savedInstanceState.getString("cap")?.let { captureFile = File(it) }
            sessionDir().listFiles()?.sortedBy { it.name }?.forEach { images += it.readBytes() }
        }
        build()
        renderThumbs()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        captureFile?.let { out.putString("cap", it.absolutePath) }
    }

    private fun sessionDir() = File(cacheDir, "session").apply { mkdirs() }

    private fun persist() {
        val d = sessionDir(); d.listFiles()?.forEach { it.delete() }
        images.forEachIndexed { k, b -> File(d, "img_$k.jpg").writeBytes(b) }
    }

    @SuppressLint("SetTextI18n")
    private fun build() {
        val root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "Carbs from photo"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        root.addView(Ui.text(this, "AI: ${st.current().label}" + if (mgAtMeal > 0) "  ·  glucose now $mgAtMeal mg/dL" else "", 13f, Ui.MUTED))

        // tips (always shown — how to estimate)
        val tips = Ui.card(this)
        tips.addView(Ui.text(this, "How to get a good estimate", 15f, Ui.ACCENT, true))
        Foods.PHOTO_TIPS.forEach { tips.addView(Ui.text(this, "•  $it", 13f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 3), 0, 0) }) }
        root.addView(tips, Ui.lp(top = Ui.dp(this, 10)))

        val r1 = Ui.row(this)
        r1.addView(Ui.button(this, "📷 Take photo", true) { takePhoto() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        r1.addView(Ui.button(this, "🖼 Gallery") { pickImages() }, Ui.lp(0, weight = 1f))
        root.addView(r1, Ui.lp(top = Ui.dp(this, 12)))
        val r2 = Ui.row(this)
        r2.addView(Ui.button(this, "🎥 Record video") { recordVideo() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        r2.addView(Ui.button(this, "📂 Video file") { pickVideo() }, Ui.lp(0, weight = 1f))
        root.addView(r2, Ui.lp(top = Ui.dp(this, 8)))

        thumbs = Ui.row(this)
        root.addView(HorizontalScrollView(this).apply { addView(thumbs); isHorizontalScrollBarEnabled = false },
            Ui.lp(top = Ui.dp(this, 10)))

        note = Ui.input(this, "Note (e.g. large portion, with honey, sauce on the side)", minLines = 2)
        root.addView(note, Ui.lp(top = Ui.dp(this, 10)))

        estimateBtn = Ui.button(this, "✦ Estimate carbs", true) { estimate() }
        root.addView(estimateBtn, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.button(this, "✎ Enter items by hand (no photo)") { manual() }, Ui.lp(top = Ui.dp(this, 8)))
        status = Ui.text(this, "", 13f, Ui.MUTED)
        root.addView(status, Ui.lp(top = Ui.dp(this, 8)))

        result = Ui.card(this).apply { visibility = View.GONE }
        root.addView(result, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.signature(this))
        setContentView(sv)
    }

    // ---------------------------------------------------------------- capture

    private fun newCapture(ext: String): Uri {
        val dir = File(cacheDir, "capture").apply { mkdirs() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.$ext")
        captureFile = f
        return FileProvider.getUriForFile(this, "com.nova.glucose.files", f)
    }

    private fun takePhoto() {
        if (images.size >= MAX_IMAGES) { Ui.toast(this, "Up to $MAX_IMAGES photos"); return }
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, newCapture("jpg"))
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, REQ_PHOTO) } catch (_: Exception) { Ui.toast(this, "No camera app found") }
    }

    private fun pickImages() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(Intent.createChooser(i, "Food photos"), REQ_GALLERY)
    }

    private fun recordVideo() {
        val i = Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, newCapture("mp4"))
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 15)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, REQ_VIDEO) } catch (_: Exception) { Ui.toast(this, "No camera app found") }
    }

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(i, "Food video"), REQ_VIDEO_PICK)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        status.text = "Preparing…"
        Thread {
            try {
                when (req) {
                    REQ_PHOTO -> captureFile?.let { addImage(Uri.fromFile(it)) }
                    REQ_GALLERY -> {
                        val clip = data?.clipData
                        if (clip != null) for (k in 0 until clip.itemCount) addImage(clip.getItemAt(k).uri)
                        else data?.data?.let { addImage(it) }
                    }
                    REQ_VIDEO -> captureFile?.let { addVideo(Uri.fromFile(it)) } ?: data?.data?.let { addVideo(it) }
                    REQ_VIDEO_PICK -> data?.data?.let { addVideo(it) }
                }
                persist()
                runOnUiThread { status.text = ""; renderThumbs() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Couldn't read that file: ${e.message}" }
            }
        }.start()
    }

    private fun addImage(uri: Uri) {
        if (images.size >= MAX_IMAGES) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return
        val rot = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        images += jpeg(bmp, rot)
    }

    private fun addVideo(uri: Uri) {
        val mr = MediaMetadataRetriever()
        try {
            mr.setDataSource(this, uri)
            val durMs = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val free = MAX_IMAGES - images.size
            val n = free.coerceAtMost(6)
            for (k in 0 until n) {
                val t = if (durMs > 0) durMs * (k + 1) / (n + 1) else 0L
                val f = mr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                images += jpeg(f, 0f)
            }
        } finally { try { mr.release() } catch (_: Exception) { } }
    }

    /** Scale to at most 1280 px and compress, so uploads are quick and cheap. */
    private fun jpeg(src: Bitmap, rotate: Float): ByteArray {
        val scale = minOf(1f, 1152f / maxOf(src.width, src.height))
        val m = Matrix().apply { postScale(scale, scale); if (rotate != 0f) postRotate(rotate) }
        val b = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() }
    }

    private fun renderThumbs() {
        thumbs.removeAllViews()
        images.forEachIndexed { idx, b ->
            val iv = ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.round(Ui.CARD2, Ui.dp(context, 10).toFloat())
                clipToOutline = true
                setOnClickListener {
                    AlertDialog.Builder(context).setMessage("Remove this photo?")
                        .setPositiveButton("Remove") { _, _ -> images.removeAt(idx); persist(); renderThumbs() }
                        .setNegativeButton("Keep", null).show()
                }
            }
            thumbs.addView(iv, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)).apply { marginEnd = Ui.dp(this@CarbActivity, 8) })
        }
        if (images.isNotEmpty()) thumbs.addView(Ui.text(this, "${images.size}/$MAX_IMAGES\ntap to remove", 12f, Ui.MUTED))
    }

    // ---------------------------------------------------------------- estimate

    private val system get() = """
You estimate carbohydrates in meals for a ${st.age}-year-old with diabetes who uses rapid insulin. The family counts carbs and
decides insulin themselves with their doctor's ratio. NEVER suggest insulin doses or units.
Foods may be Tunisian, Gulf/Middle Eastern or international. Use typical cooked-food carb values.
Count only what is visible in the photos plus what the note says. Watch for hidden sugar, sauces, drinks, bread, rice, fried coatings.
Several photos/frames may show the same plate from different angles: do not count it twice.
Answer with ONLY a JSON object, no other text:
{"items":[{"name":"...","portion":"estimated amount, e.g. 1 cup / 150 g","carbs_g":40,"low_g":32,"high_g":50}],
 "total_g":0,"total_low_g":0,"total_high_g":0,"confidence":"low|medium|high",
 "notes":"one or two short sentences: what limits accuracy and what to check (label, scale, hidden items)"}
If the photo shows no food, return an empty items list and explain in notes.
""".trimIndent()

    private fun estimate() {
        if (images.isEmpty()) { Ui.toast(this, "Add a photo or video first"); return }
        val p = st.current()
        if (st.key(p).isBlank()) { status.setTextColor(Ui.BAD); status.text = "Add your ${p.label} API key in Settings first."; return }
        estimateBtn.isEnabled = false
        status.setTextColor(Ui.MUTED); status.text = "Asking ${p.label} to look at the food…"
        val user = buildString {
            append("Estimate the carbohydrates in this meal (${images.size} image(s)).")
            if (note.text.isNotBlank()) append("\nNote from the family: ${note.text}")
            if (st.foodNotes.isNotBlank()) append("\nFamily food notes: ${st.foodNotes}")
            append("\nAbout her: ${st.aboutLine()}")
            append(Training.context(this@CarbActivity))
        }
        Thread {
            try {
                val ans = Providers.ask(st, p, system, user, images.toList()) { s -> runOnUiThread { status.text = s } }
                val o = parseJson(ans)
                runOnUiThread { load(o); estimateBtn.isEnabled = true; status.text = "" }
            } catch (e: Exception) {
                runOnUiThread { estimateBtn.isEnabled = true; status.setTextColor(Ui.BAD); status.text = e.message ?: "Failed" }
            }
        }.start()
    }

    private fun parseJson(s: String): JSONObject {
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw AiException("The AI didn't return an estimate. Try again or another AI.")
        return JSONObject(s.substring(a, b + 1))
    }

    private var aiSnapshot: String? = null
    private var aiTotal = 0

    private fun load(o: JSONObject) {
        items.clear()
        val arr = o.optJSONArray("items") ?: JSONArray()
        for (k in 0 until arr.length()) {
            val o2 = arr.getJSONObject(k)
            val c = o2.optDouble("carbs_g", 0.0).roundToInt()
            items += Item(o2.optString("name"), o2.optString("portion"), c,
                o2.optDouble("low_g", c.toDouble()).roundToInt(), o2.optDouble("high_g", c.toDouble()).roundToInt())
        }
        confidence = o.optString("confidence")
        aiNotes = o.optString("notes")
        // Keep the AI's first answer, to learn from the family's corrections.
        val snap = JSONArray()
        items.forEach { snap.put(JSONObject().put("name", it.name).put("portion", it.portion).put("carbs", it.carbs)) }
        aiSnapshot = snap.toString(); aiTotal = items.sumOf { it.carbs }
        renderResult()
    }

    private fun manual() {
        items.clear(); items += Item("", "", 0, 0, 0)
        confidence = "entered by hand"; aiNotes = ""; aiSnapshot = null
        renderResult()
    }

    @SuppressLint("SetTextI18n")
    private fun renderResult() {
        result.visibility = View.VISIBLE
        result.removeAllViews()
        result.addView(Ui.text(this, "Estimated carbs", 13f, Ui.MUTED, true))
        val total = Ui.text(this, "", 30f, Ui.ACCENT, true)
        val range = Ui.text(this, "", 13f, Ui.MUTED)
        result.addView(total); result.addView(range)
        fun updateTotal() {
            total.text = "${items.sumOf { it.carbs }} g"
            val lo = items.sumOf { it.low }; val hi = items.sumOf { it.high }
            range.text = (if (hi > lo) "likely $lo–$hi g" else "") +
                (if (confidence.isNotBlank()) "  ·  confidence: $confidence" else "")
        }
        updateTotal()

        result.addView(Ui.section(this, "Items — tap a number to correct it"))
        items.toList().forEach { item ->
            val row = Ui.row(this).apply {
                background = Ui.round(Ui.CARD2, Ui.dp(context, 12).toFloat())
                setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
            }
            val left = Ui.col(this)
            val nm = plainEdit(item.name, "Food", 15f, true).apply { onChange { item.name = it } }
            val pt = plainEdit(item.portion, "Portion", 12f, false).apply { onChange { item.portion = it } }
            left.addView(nm); left.addView(pt)
            row.addView(left, Ui.lp(0, weight = 1f))
            val g = plainEdit(item.carbs.toString(), "0", 17f, true).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.END; setTextColor(Ui.ACCENT)
                onChange { s -> val v = s.toIntOrNull() ?: 0; item.carbs = v; item.low = v; item.high = v; updateTotal() }
            }
            row.addView(g, Ui.lp(Ui.dp(this, 56), -2))
            row.addView(Ui.text(this, "g", 14f, Ui.MUTED))
            row.addView(Ui.text(this, "  ✕", 16f, Ui.MUTED).apply {
                setOnClickListener { items.remove(item); renderResult() }
            })
            result.addView(row, Ui.lp(top = Ui.dp(this, 6)))
        }
        result.addView(Ui.button(this, "+ Add item (juice, sauce, bread…)") {
            items += Item("", "", 0, 0, 0); renderResult()
        }, Ui.lp(top = Ui.dp(this, 8)))

        if (aiNotes.isNotBlank()) result.addView(Ui.text(this, "AI note: $aiNotes", 13f, Ui.TEXT).apply {
            setPadding(0, Ui.dp(context, 10), 0, 0) })
        result.addView(Ui.text(this, "Approximate — carbs only, not an insulin dose. Use her doctor's carb ratio; check labels or weigh when you can.",
            12f, Ui.WARN).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        result.addView(Ui.button(this, "💾 Save to logbook", true) { saveMeal() }, Ui.lp(top = Ui.dp(this, 10)))
    }

    private fun plainEdit(v: String, hint: String, size: Float, bold: Boolean) = EditText(this).apply {
        setText(v); this.hint = hint; textSize = size; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
        background = null; setPadding(0, 0, 0, 0); isSingleLine = true
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun EditText.onChange(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
    })

    private fun saveMeal() {
        val clean = items.filter { it.name.isNotBlank() || it.carbs > 0 }
        if (clean.isEmpty()) { Ui.toast(this, "Nothing to save"); return }
        val now = System.currentTimeMillis()
        var photo = ""
        images.firstOrNull()?.let { b ->
            val dir = File(filesDir, "meals").apply { mkdirs() }
            val f = File(dir, "meal_$now.jpg"); f.writeBytes(b); photo = f.absolutePath
        }
        val arr = JSONArray()
        clean.forEach { arr.put(JSONObject().put("name", it.name).put("portion", it.portion).put("carbs", it.carbs)) }
        val title = clean.joinToString(", ") { it.name.ifBlank { "item" } }.take(80)
        val mg = Db.get(this).near(now)?.mg ?: mgAtMeal
        val id = Db.get(this).addMeal(Meal(0, now, title, arr.toString(), clean.sumOf { it.carbs },
            clean.sumOf { it.low }, clean.sumOf { it.high }, mg, photo, note.text.toString()))
        aiSnapshot?.let { Db.get(this).setAi(id, it, aiTotal) }
        val app = applicationContext
        Thread { Vault.exportMeals(app) }.start()
        Ui.toast(this, "Saved to logbook")
        sessionDir().deleteRecursively()
        android.app.AlertDialog.Builder(this).setTitle("Saved · ${clean.sumOf { it.carbs }} g carbs")
            .setMessage("Log rapid insulin and start the eat timer now?")
            .setPositiveButton("💉 Rapid insulin") { _, _ ->
                startActivity(android.content.Intent(this, InsulinActivity::class.java)); finish() }
            .setNegativeButton("Done") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
}
