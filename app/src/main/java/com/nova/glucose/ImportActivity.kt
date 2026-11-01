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
        AlertDialog.Builder(this).setTitle("Nova Glucose").setMessage(msg).setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun confirm() {
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
        AlertDialog.Builder(this).setTitle("Restore this backup?")
            .setMessage("$details\n\nAll settings, the LibreLinkUp login, API keys, readings, meals and doses on this phone will be replaced by the backup.")
            .setPositiveButton("Restore") { _, _ ->
                if (Parent.enabled(st)) Parent.ask(this, "Enter the parent PIN to restore a backup.") { askPassword() } else askPassword()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun askPassword(error: String? = null) {
        val pw = Ui.input(this, "Backup password", multi = false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (error != null) this.error = error
        }
        AlertDialog.Builder(this).setTitle("🔒 Backup password").setView(Ui.col(this, 20).apply { addView(pw) })
            .setPositiveButton("Restore") { _, _ -> restore(pw.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun restore(password: String) {
        Ui.toast(this, "Restoring…")
        val app = applicationContext
        val b = bytes!!
        Thread {
            val r = try { Result.success(Backup.restoreFile(app, b, password)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { msg ->
                    MonitorService.stop(app); MonitorService.start(app)
                    AlertDialog.Builder(this).setTitle("✓ Restored").setMessage(msg.replace(" · ", "\n"))
                        .setPositiveButton("OK") { _, _ ->
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            finish()
                        }.setCancelable(false).show()
                }.onFailure { e ->
                    if ((e.message ?: "").contains("password", true)) askPassword("Wrong backup password")
                    else fail(e.message ?: "Restore failed")
                }
            }
        }.start()
    }
}
