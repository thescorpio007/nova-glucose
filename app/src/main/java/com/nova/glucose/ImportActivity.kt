package com.nova.glucose

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opens a .novaglucose backup sent by WhatsApp, e-mail, Drive or a file manager (or picked in Settings):
 * shows what it is, asks the parent PIN (when on), then the backup password, restores and shows the summary.
 */
class ImportActivity : BaseActivity() {
    private lateinit var st: Store
    private var bytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        val root = Ui.col(this, 24).apply { gravity = Gravity.CENTER }
        root.addView(Ui.title(this, "Nova Glucose backup").apply { gravity = Gravity.CENTER })
        root.addView(Ui.text(this, "Opening the file…", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        root.addView(Ui.signature(this))
        setContentView(root)

        val uri: Uri? = intent.data ?: if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri == null) { fail("No file was received."); return }
        Thread {
            val r = try {
                val b = contentResolver.openInputStream(uri)!!.use { s ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); var n: Int; var total = 0
                    while (s.read(buf).also { n = it } > 0) { total += n; if (total > 80_000_000) throw IllegalStateException("File too large"); out.write(buf, 0, n) }
                    out.toByteArray()
                }
                Result.success(b)
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { b ->
                    if (!Backup.isBackupFile(b)) fail("This isn't a Nova Glucose backup file.") else { bytes = b; confirm() }
                }.onFailure { fail("Couldn't read the file: ${it.message}") }
            }
        }.start()
    }

    private fun fail(msg: String) {
        Tr.Builder(this).setTitle("Nova Glucose").setMessage(msg).setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private var merge = false

    private fun confirm() {
        if (Backup.kind(bytes!!) == "family") { confirmFamily(); return }
        val h = Backup.header(bytes!!)
        val df = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US)
        val k = h?.optJSONObject("contents")
        val details = buildString {
            h?.optString("name")?.takeIf { it.isNotBlank() }?.let { append("$it\n") }
            h?.optLong("created")?.takeIf { it > 0 }?.let { append("Saved ${df.format(Date(it))}\n") }
            h?.optString("version")?.takeIf { it.isNotBlank() }?.let { append("Nova Glucose $it\n") }
            if (k != null) append("${k.optInt("settings")} settings · ${k.optInt("readings")} readings · ${k.optInt("meals")} meals · ${k.optInt("doses")} doses\n")
            append(if (Backup.isEncrypted(bytes!!)) "🔒 Encrypted with the backup password" else "Older backup format (not fully encrypted)")
        }
        Tr.Builder(this).setTitle("Restore this backup?")
            .setMessage("$details\n\nAll settings, the LibreLinkUp login, API keys, readings, meals and doses on this phone will be replaced by the backup.")
            .setPositiveButton("Replace all") { _, _ ->
                merge = false
                if (Parent.enabled(st)) Parent.ask(this, "Enter the parent PIN to restore a backup.") { askPassword() } else askPassword()
            }
            .setNeutralButton("Add meals & doses only") { _, _ ->
                merge = true
                if (Parent.enabled(st)) Parent.ask(this, "Enter the parent PIN to add from a backup.") { tryKnown() } else tryKnown()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    /** Family update file from another phone: adds/updates meals and doses, never touches settings. */
    private fun confirmFamily() {
        merge = true
        val h = Backup.header(bytes!!)
        val df = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US)
        val k = h?.optJSONObject("contents")
        val details = buildString {
            h?.optString("name")?.takeIf { it.isNotBlank() }?.let { append("From: $it\n") }
            h?.optLong("created")?.takeIf { it > 0 }?.let { append("Made ${df.format(Date(it))}\n") }
            if (k != null) append("${k.optInt("meals")} meals · ${k.optInt("doses")} insulin doses · ${k.optInt("deletions")} deletions\n")
            append("🔒 Encrypted")
        }
        Tr.Builder(this).setTitle("👨‍👩‍👧 Family update")
            .setMessage("$details\n\nAdds what this phone is missing (meals, insulin, earlier entries, AI corrections) and applies deletions. " +
                "Settings, alarms and logins on this phone stay as they are.")
            .setPositiveButton("Add to this phone") { _, _ ->
                if (Parent.enabled(st)) Parent.ask(this, "Enter the parent PIN to add a family update.") { tryKnown() } else tryKnown()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    /** Tries the family and backup passwords saved on this phone first; asks only if neither opens the file. */
    private fun tryKnown() {
        val b = bytes!!
        Thread {
            val pw = listOf(st.syncPw, st.backupPw).filter { it.isNotBlank() }.firstOrNull { p ->
                try { Backup.unpack(b, p); true } catch (_: Exception) { false }
            }
            runOnUiThread { if (pw != null) restore(pw) else askPassword() }
        }.start()
    }

    private fun askPassword(error: String? = null) {
        val pw = Ui.input(this, if (Backup.kind(bytes!!) == "family") "Family (or backup) password" else "Backup password", multi = false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (error != null) this.error = error
        }
        Tr.Builder(this).setTitle("🔒 Password").setView(Ui.col(this, 20).apply { addView(pw) })
            .setPositiveButton(if (merge) "Add" else "Restore") { _, _ -> restore(pw.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun restore(password: String) {
        Ui.toast(this, if (merge) "Adding…" else "Restoring…")
        val app = applicationContext
        val b = bytes!!
        val m = merge
        Thread {
            val r = try {
                Result.success(if (m) FamilySync.mergeText(app, Backup.unpack(b, password)) else Backup.restoreFile(app, b, password))
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { msg ->
                    if (!m) { MonitorService.stop(app); MonitorService.start(app) }
                    Tr.Builder(this).setTitle(if (m) "✓ Added" else "✓ Restored").setMessage(msg.replace(" · ", "\n"))
                        .setPositiveButton("OK") { _, _ ->
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            finish()
                        }.setCancelable(false).show()
                }.onFailure { e ->
                    if ((e.message ?: "").contains("password", true)) askPassword("Wrong password")
                    else fail(e.message ?: "Restore failed")
                }
            }
        }.start()
    }
}
