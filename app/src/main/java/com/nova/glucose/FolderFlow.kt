package com.nova.glucose

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.EditText

/** Choosing the Download/NovaGlucose folder, and resuming from it after a reinstall. */
object FolderFlow {
    const val REQ = 7701

    fun pick(a: Activity) {
        AlertDialog.Builder(a)
            .setTitle("Download/NovaGlucose folder")
            .setMessage("Readings, meals, alerts, daily assessments and the backup are saved there, so nothing is lost if the app is reinstalled.\n\n" +
                "On the next screen:\n1. Open Download → NovaGlucose\n   (if it's missing: ⋮ or the folder icon → New folder → NovaGlucose)\n" +
                "2. Tap \"Use this folder\"\n3. Tap \"Allow\"")
            .setPositiveButton("Choose folder") { _, _ ->
                try { a.startActivityForResult(Vault.pickIntent(), REQ) } catch (_: Exception) { Ui.toast(a, "No file manager found") }
            }
            .setNegativeButton("Later", null).show()
    }

    /** Call from onActivityResult. [done] runs on the UI thread once finished. */
    fun onResult(a: Activity, req: Int, res: Int, data: Intent?, done: () -> Unit): Boolean {
        if (req != REQ) return false
        val uri = data?.data
        if (res != Activity.RESULT_OK || uri == null) return true
        try { Vault.onPicked(a, uri) } catch (e: Exception) { Ui.toast(a, "Couldn't use that folder: ${e.message}"); return true }
        val st = Store(a)
        if (!st.loggedIn && Vault.hasBackup(a)) askResume(a, done)
        else {
            val app = a.applicationContext
            Thread {
                val err = Vault.exportAll(app, forceBackup = st.backupPw.isNotBlank())
                a.runOnUiThread { Ui.toast(a, err?.let { "Folder chosen · $it" } ?: "Folder ready: ${Vault.folderLabel(a)}"); done() }
            }.start()
        }
        return true
    }

    fun askResume(a: Activity, done: () -> Unit) {
        val pw = EditText(a).apply {
            hint = "Backup password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = Ui.col(a, 20).apply { addView(pw) }
        AlertDialog.Builder(a)
            .setTitle("Resume from backup?")
            .setMessage("A Nova Glucose backup was found in this folder. Restore settings, LibreLinkUp login, API keys, readings and meals?")
            .setView(box)
            .setPositiveButton("Resume") { _, _ -> resume(a, pw.text.toString(), done) }
            .setNegativeButton("Not now") { _, _ -> Store(a).resumeAsked = true; done() }
            .show()
    }

    private fun resume(a: Activity, password: String, done: () -> Unit) {
        Ui.toast(a, "Restoring…")
        val app = a.applicationContext
        Thread {
            val r = try { Result.success(Vault.restoreAll(app, password)) } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                r.onSuccess { msg ->
                    MonitorService.stop(app); MonitorService.start(app)
                    AlertDialog.Builder(a).setTitle("Resumed").setMessage(msg).setPositiveButton("OK") { _, _ -> done() }.show()
                }.onFailure { e ->
                    Ui.toast(a, e.message ?: "Restore failed")
                    if ((e.message ?: "").contains("password", true)) askResume(a, done) else done()
                }
            }
        }.start()
    }
}
