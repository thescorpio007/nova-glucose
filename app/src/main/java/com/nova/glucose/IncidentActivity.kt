package com.nova.glucose

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Add or open an incident: what happened, when, details and the doctor's prescription (photo + what the AI read). */
class IncidentActivity : BaseActivity() {
    private lateinit var st: Store
    private var id = 0L
    private var kind = ""
    private val start = Calendar.getInstance().apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    private val end = Calendar.getInstance().apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    private var photo = ""          // saved path
    private var newPhoto: ByteArray? = null
    private var capture: File? = null

    private lateinit var chips: LinearLayout
    private lateinit var other: android.widget.EditText
    private lateinit var startB: TextView; private lateinit var startT: TextView
    private lateinit var endB: TextView; private lateinit var endT: TextView
    private lateinit var endRow: LinearLayout
    private lateinit var ongoing: Switch
    private lateinit var details: android.widget.EditText
    private lateinit var meds: android.widget.EditText
    private lateinit var thumb: ImageView
    private lateinit var rxStatus: TextView

    companion object {
        private const val REQ_CAM = 1; private const val REQ_GAL = 2
        fun open(a: android.app.Activity, id: Long = 0) = a.startActivity(Intent(a, IncidentActivity::class.java).putExtra("id", id))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        id = intent.getLongExtra("id", 0)
        val ex = if (id > 0) Db.get(this).incident(id) else null
        if (ex != null) {
            kind = ex.kind; start.timeInMillis = ex.ts; if (!ex.ongoing) end.timeInMillis = ex.end; photo = ex.photo
        }

        val root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, if (ex == null) "Add incident" else "Incident"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        if (ex != null) root.addView(Ui.text(this, Db.get(this).who("incidents", id).takeIf { it.isNotBlank() }?.let { "Added by $it" } ?: "", 12f, Ui.MUTED))

        // ---- what
        val c1 = Ui.card(this)
        c1.addView(Ui.text(this, "What happened?", 15f, Ui.TEXT, true))
        chips = Ui.col(this); c1.addView(chips, Ui.lp(top = Ui.dp(this, 6)))
        other = Ui.input(this, "Other — type it (e.g. ear infection)", multi = false)
        c1.addView(other, Ui.lp(top = Ui.dp(this, 6)))
        if (ex != null && Incidents.TYPES.flatten().none { it == ex.kind }) other.setText(ex.kind)
        renderChips()

        // ---- when
        c1.addView(Ui.text(this, "Started", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 4)) })
        val sr = Ui.row(this)
        startB = Ui.smallButton(this, "") { pickDate(start) }; startT = Ui.smallButton(this, "") { pickTime(start) }
        sr.addView(startB, Ui.lp(0, Ui.dp(this, 44), weight = 1f, end = Ui.dp(this, 8))); sr.addView(startT, Ui.lp(0, Ui.dp(this, 44), weight = 1f))
        c1.addView(sr)
        ongoing = Switch(this).apply {
            text = "Still ongoing"; isChecked = ex?.ongoing ?: true; setTextColor(Ui.TEXT); textSize = 14f
            setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4))
            setOnCheckedChangeListener { _, _ -> renderTimes() }
        }
        c1.addView(ongoing)
        endRow = Ui.row(this)
        endB = Ui.smallButton(this, "") { pickDate(end) }; endT = Ui.smallButton(this, "") { pickTime(end) }
        endRow.addView(endB, Ui.lp(0, Ui.dp(this, 44), weight = 1f, end = Ui.dp(this, 8))); endRow.addView(endT, Ui.lp(0, Ui.dp(this, 44), weight = 1f))
        c1.addView(endRow)
        c1.addView(Ui.text(this, "Details", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 4)) })
        details = Ui.input(this, "Temperature, symptoms, what you did (e.g. 38.4 °C, no appetite, extra water)", minLines = 3)
        ex?.let { details.setText(it.details) }
        c1.addView(details)
        root.addView(c1, Ui.lp(top = Ui.dp(this, 10)))
        renderTimes()

        // ---- prescription
        val c2 = Ui.card(this)
        c2.addView(Ui.text(this, "Doctor's prescription", 15f, Ui.TEXT, true))
        val pr = Ui.row(this)
        pr.addView(Ui.button(this, "📷 Scan with camera") { camera() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        pr.addView(Ui.button(this, "🖼 From gallery") { gallery() }, Ui.lp(0, weight = 1f))
        c2.addView(pr, Ui.lp(top = Ui.dp(this, 8)))
        thumb = ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE
            setOnClickListener { showPhoto() }
        }
        c2.addView(thumb, Ui.lp(-1, Ui.dp(this, 220), top = Ui.dp(this, 10)))
        rxStatus = Ui.text(this, "", 13f, Ui.MUTED)
        c2.addView(rxStatus, Ui.lp(top = Ui.dp(this, 6)))
        c2.addView(Ui.text(this, "Medicines (check what the AI read and correct it)", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 4)) })
        meds = Ui.input(this, "• Medicine · dose · how often · days", minLines = 3)
        ex?.let { meds.setText(it.meds) }
        c2.addView(meds)
        c2.addView(Ui.button(this, "✦ Read the prescription again with AI") { readRx() }, Ui.lp(top = Ui.dp(this, 8)))
        c2.addView(Ui.text(this, "The AI can misread handwriting — always check with the paper prescription. Nova Glucose doesn't give medical advice.",
            12f, Ui.WARN).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        root.addView(c2, Ui.lp(top = Ui.dp(this, 10)))
        renderThumb()

        root.addView(Ui.button(this, "💾 Save incident", true) { save() }, Ui.lp(top = Ui.dp(this, 12)))
        if (ex != null) root.addView(Ui.button(this, "🗑 Delete incident") {
            Parent.guard(this, "Enter the parent PIN to delete an incident.") {
                Tr.Builder(this).setTitle("Delete this incident?").setMessage("It's removed on all family phones too.")
                    .setPositiveButton("Delete") { _, _ -> Db.get(this).deleteIncident(id); finish() }
                    .setNegativeButton("Cancel", null).show()
            }
        }, Ui.lp(top = Ui.dp(this, 8)))
        root.addView(Ui.signature(this))
        setContentView(sv)
    }

    private fun renderChips() {
        chips.removeAllViews()
        Incidents.TYPES.forEach { group ->
            val (scroll, row) = Ui.chipRow(this)
            group.forEach { t -> row.addView(Ui.chip(this, t, kind == t) { kind = t; other.setText(""); renderChips() }, Ui.lp(-2, -2, end = Ui.dp(this, 8))) }
            chips.addView(scroll, Ui.lp(top = Ui.dp(this, 4)))
        }
    }

    private val dfD = SimpleDateFormat("EEE d MMM", Locale.US)
    private val dfT = SimpleDateFormat("HH:mm", Locale.US)
    private fun renderTimes() {
        startB.text = "📅  " + dfD.format(start.time); startT.text = "🕘  " + dfT.format(start.time)
        endB.text = "📅  " + dfD.format(end.time); endT.text = "🕘  " + dfT.format(end.time)
        endRow.visibility = if (ongoing.isChecked) View.GONE else View.VISIBLE
    }

    private fun pickDate(c: Calendar) {
        DatePickerDialog(this, { _, y, m, d -> c.set(y, m, d); renderTimes() }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            .apply { datePicker.maxDate = System.currentTimeMillis() + 24 * 3600_000L }.show()
    }

    private fun pickTime(c: Calendar) {
        TimePickerDialog(this, { _, h, m -> c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); renderTimes() },
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    // ---------------------------------------------------------------- prescription photo
    private fun camera() {
        val dir = File(cacheDir, "capture").apply { mkdirs() }
        val f = File(dir, "rx_${System.currentTimeMillis()}.jpg"); capture = f
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(this, "com.nova.glucose.files", f))
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivityForResult(i, REQ_CAM) } catch (_: Exception) { Ui.toast(this, "No camera app found") }
    }

    private fun gallery() {
        startActivityForResult(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),
            "Prescription photo"), REQ_GAL)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        val uri = if (req == REQ_CAM) capture?.let { Uri.fromFile(it) } else data?.data
        uri ?: return
        rxStatus.setTextColor(Ui.MUTED); rxStatus.text = "Preparing the photo…"
        Thread {
            try {
                val b = load(uri)
                newPhoto = b
                runOnUiThread { renderThumb(); readRx() }
            } catch (e: Exception) { runOnUiThread { rxStatus.setTextColor(Ui.BAD); rxStatus.text = "Couldn't read that photo: ${e.message}" } }
        }.start()
    }

    private fun load(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2800) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalStateException("not an image")
        val rot = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        return Incidents.compress(bmp, rot)
    }

    private fun currentPhoto(): ByteArray? = newPhoto ?: photo.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }?.readBytes()

    private fun renderThumb() {
        val b = currentPhoto()
        if (b == null) { thumb.visibility = View.GONE; return }
        thumb.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)); thumb.visibility = View.VISIBLE
    }

    private fun showPhoto() {
        val b = currentPhoto() ?: return
        val iv = ImageView(this).apply { setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)); adjustViewBounds = true }
        Tr.Builder(this).setView(ScrollView(this).apply { addView(iv) }).setPositiveButton("Close", null).show()
    }

    private fun readRx() {
        val b = currentPhoto() ?: run { Ui.toast(this, "Scan or choose the prescription first"); return }
        val p = st.current()
        if (st.key(p).isBlank()) { rxStatus.setTextColor(Ui.WARN); rxStatus.text = "Photo saved. Add your ${p.label} API key in Settings to let the AI read it."; return }
        rxStatus.setTextColor(Ui.MUTED); rxStatus.text = "${p.label} is reading the prescription…"
        val app = applicationContext
        Thread {
            try {
                val txt = Incidents.readPrescription(app, b) { s -> runOnUiThread { rxStatus.text = s } }
                runOnUiThread {
                    if (meds.text.isNotBlank() && meds.text.toString().trim() != txt) {
                        Tr.Builder(this).setTitle("Replace the medicines text?").setMessage(txt)
                            .setPositiveButton("Replace") { _, _ -> meds.setText(txt) }.setNegativeButton("Keep mine", null).show()
                    } else meds.setText(txt)
                    rxStatus.setTextColor(Ui.GOOD); rxStatus.text = "✓ Read by ${p.label} — please check it"
                }
            } catch (e: Exception) { runOnUiThread { rxStatus.setTextColor(Ui.BAD); rxStatus.text = e.message ?: "The AI couldn't read it" } }
        }.start()
    }

    // ---------------------------------------------------------------- save
    private fun save() {
        val k = other.text.toString().trim().ifBlank { kind }
        if (k.isBlank()) { Ui.toast(this, "Choose what happened"); return }
        val e = if (ongoing.isChecked) 0L else end.timeInMillis
        if (e != 0L && e < start.timeInMillis) { Ui.toast(this, "The end is before the start"); return }
        val db = Db.get(this)
        val path = newPhoto?.let { Incidents.savePhoto(this, it) }
        if (path != null && photo.isNotBlank() && photo != path) File(photo).delete()
        val inc = Incident(id, start.timeInMillis, e, k, details.text.toString().trim(), meds.text.toString().trim(), path ?: photo)
        if (id > 0) db.updateIncident(id, inc) else id = db.addIncident(inc)
        Ui.toast(this, "Incident saved")
        finish()
    }
}
