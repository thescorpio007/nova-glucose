package com.nova.glucose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Incidents (illness and other events that change glucose): types, prescription photo, AI reading, sync items, AI context. */
object Incidents {
    const val COLOR = 0xFFF5A623.toInt()

    /** Ready-to-tap types, in groups (one row each). */
    val TYPES = listOf(
        listOf("Flu / cold", "Fever", "Vomiting", "Stomach"),
        listOf("Hypo treated", "Sport", "Stress"),
        listOf("Sensor / pump issue", "Doctor visit", "Hospital"),
    )

    private val df = SimpleDateFormat("EEE d MMM HH:mm", Locale.US)
    fun period(i: Incident): String = df.format(Date(i.ts)) + if (i.ongoing) " · still ongoing" else " → " + df.format(Date(i.end))

    fun dir(c: Context) = File(c.filesDir, "incidents").apply { mkdirs() }

    /** Prescription photo: at most 1400 px, JPEG ~70% (readable, small enough to sync). */
    fun compress(src: Bitmap, rotate: Float = 0f): ByteArray {
        val scale = minOf(1f, 1400f / maxOf(src.width, src.height))
        val m = Matrix().apply { postScale(scale, scale); if (rotate != 0f) postRotate(rotate) }
        val b = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        var q = 72
        while (true) {
            val out = ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.JPEG, q, it); it.toByteArray() }
            if (out.size < 420_000 || q <= 35) return out
            q -= 10
        }
    }

    fun savePhoto(c: Context, bytes: ByteArray, name: String = "rx_${System.currentTimeMillis()}"): String =
        File(dir(c), "$name.jpg").apply { writeBytes(bytes) }.absolutePath

    fun thumb(path: String, max: Int = 400): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > max) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Exception) { null }

    private val RX_SYSTEM = """
You read a photo of a doctor's prescription for a child with type 1 diabetes.
Write ONLY what is written on it, one medicine per line, like:
• Medicine name and strength · dose · how often · for how many days
Then, if visible, one line: Doctor: name · Date: date
Rules: plain text, no advice, no extra comments. Do not guess unreadable words: write [unreadable] instead.
If the photo is not a prescription, answer exactly: Not a prescription.
""".trimIndent()

    /** AI reads the prescription (the family checks and edits the text before saving). */
    fun readPrescription(c: Context, photo: ByteArray, status: (String) -> Unit = {}): String {
        val st = Store(c)
        val p = st.current()
        return Providers.ask(st, p, RX_SYSTEM, "Read this prescription.", listOf(photo), status = status).trim()
    }

    // ---------------------------------------------------------------- family sync
    fun item(c: Context, id: Long): JSONObject? {
        val db = Db.get(c); val i = db.incident(id) ?: return null
        val o = JSONObject().put("t", "inc").put("g", db.gid("incidents", id)).put("u", db.upd("incidents", id))
            .put("ts", i.ts).put("end", i.end).put("kind", i.kind).put("details", i.details).put("meds", i.meds)
            .put("by", db.who("incidents", id).ifBlank { Store(c).syncName })
        return o          // photos stay on the phone that took them
    }

    /** Applies an incident from another phone: "inc+", "upd" or "". */
    fun apply(c: Context, o: JSONObject): String {
        val db = Db.get(c)
        val g = o.optString("g"); val u = o.optLong("u")
        if (g.isBlank() || db.deletedAt(g) > 0) return ""
        val i = Incident(0, o.getLong("ts"), o.optLong("end"), o.optString("kind"), o.optString("details"), o.optString("meds"))
        fun photo() = o.optString("photo").takeIf { it.isNotBlank() }?.let {
            try { savePhoto(c, Base64.decode(it, Base64.NO_WRAP), "rx_$g") } catch (_: Exception) { null }
        }
        val row = db.byGid("incidents", g)
        if (row == null) { db.addIncident(i.copy(photo = photo() ?: ""), g, u, share = false, who = o.optString("by")); return "inc+" }
        if (u <= row.second) return ""
        db.updateIncident(row.first, i, u, share = false, photo = photo())
        return "upd"
    }

    // ---------------------------------------------------------------- AI context (Ask AI)
    /** Ongoing incidents and those of the last 3 days, for the AI's food ideas. */
    fun context(c: Context): String {
        val now = System.currentTimeMillis()
        val list = Db.get(c).incidents(now - 3 * 24 * 3600_000L, now)
        if (list.isEmpty()) return ""
        return "\nCurrent or recent incidents (take them into account, e.g. sick-day eating):\n" + list.joinToString("\n") { i ->
            "- ${i.kind}: ${period(i)}" + (if (i.details.isNotBlank()) " — ${i.details}" else "") +
                (if (i.meds.isNotBlank()) " — prescribed: ${i.meds.replace("\n", "; ")}" else "")
        } + "\n"
    }
}
