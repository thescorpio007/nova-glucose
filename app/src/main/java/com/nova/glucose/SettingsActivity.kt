package com.nova.glucose

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : BaseActivity() {
    private lateinit var st: Store
    private lateinit var root: LinearLayout

    private lateinit var email: EditText
    private lateinit var pw: EditText
    private lateinit var loginStatus: TextView
    private lateinit var patients: LinearLayout
    private lateinit var monitor: Switch
    private lateinit var alerts: Switch
    private lateinit var stale: Switch
    private val nums = mutableMapOf<String, EditText>()
    private lateinit var notes: EditText
    private val keys = mutableMapOf<String, EditText>()
    private val models = mutableMapOf<String, EditText>()
    private lateinit var aiChips: LinearLayout
    private lateinit var lluVersion: EditText
    private lateinit var folderStatus: TextView
    private lateinit var backupPw: EditText
    private lateinit var backupMsg: TextView
    private var pendingBackup: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        build()
    }

    override fun onPause() { save(false); super.onPause() }

    private fun build() {
        root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "Settings"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Done") { save(true); finish() })
        root.addView(head)

        // ---------------------------------------------------------- LibreLinkUp
        root.addView(Ui.section(this, "LibreLinkUp connection"))
        val lc = Ui.card(this)
        lc.addView(Ui.text(this, "Use the LibreLinkUp follower login (the account that sees her readings).", 13f, Ui.MUTED))
        email = Ui.input(this, "Email", multi = false).apply {
            setText(st.email); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        lc.addView(email, Ui.lp(top = Ui.dp(this, 10)))
        pw = Ui.input(this, "Password", multi = false).apply {
            setText(st.password); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        lc.addView(pw, Ui.lp(top = Ui.dp(this, 8)))
        lc.addView(Ui.button(this, "Connect", true) { connect() }, Ui.lp(top = Ui.dp(this, 10)))
        loginStatus = Ui.text(this, if (st.patientName.isNotBlank()) "Connected · following ${st.patientName}" else "", 13f, Ui.GOOD)
        lc.addView(loginStatus, Ui.lp(top = Ui.dp(this, 8)))
        patients = Ui.row(this)
        lc.addView(patients, Ui.lp(top = Ui.dp(this, 6)))
        monitor = switch("Read every minute in the background (needed for alerts)", st.monitorOn)
        lc.addView(monitor, Ui.lp(top = Ui.dp(this, 8)))
        lc.addView(Ui.text(this, "Keep LibreLinkUp's own alarms on too — this connection is unofficial and can stop after an Abbott update.",
            12f, Ui.WARN), Ui.lp(top = Ui.dp(this, 6)))
        root.addView(lc)

        // ---------------------------------------------------------- Alerts
        root.addView(Ui.section(this, "Alerts (mg/dL)"))
        val ac = Ui.card(this)
        alerts = switch("High / low alerts with food tips", st.alertsOn)
        ac.addView(alerts)
        ac.addView(numRow("urgent_low", "Urgent low below", st.urgentLow))
        ac.addView(numRow("low", "Low below", st.low))
        ac.addView(numRow("high", "High above", st.high))
        ac.addView(numRow("very_high", "Very high above", st.veryHigh))
        ac.addView(numRow("repeat", "Repeat alert after (minutes)", st.repeatMin))
        stale = switch("Alert when there's no new reading for 20 minutes", st.staleAlert)
        ac.addView(stale, Ui.lp(top = Ui.dp(this, 6)))
        ac.addView(Ui.button(this, "Notification sound & vibration…") { openNotifSettings() }, Ui.lp(top = Ui.dp(this, 8)))
        root.addView(ac)

        // ---------------------------------------------------------- Carb plan
        root.addView(Ui.section(this, "Carb plan (grams)"))
        val cc = Ui.card(this)
        cc.addView(Ui.text(this, "Standard guidance for ages 13–17. Replace with her care team's numbers when you have them.", 13f, Ui.MUTED))
        cc.addView(numRow("low_treat", "Fast sugar to treat a low", st.lowTreatG))
        cc.addView(numRow("meal_min", "Meal carbs from", st.mealMin))
        cc.addView(numRow("meal_max", "Meal carbs up to", st.mealMax))
        cc.addView(numRow("snack_min", "Snack carbs from", st.snackMin))
        cc.addView(numRow("snack_max", "Snack carbs up to", st.snackMax))
        notes = Ui.input(this, "Food notes for AI: likes, dislikes, allergies, fasting days…", minLines = 2).apply { setText(st.foodNotes) }
        cc.addView(notes, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(cc)

        // ---------------------------------------------------------- AI
        root.addView(Ui.section(this, "AI (Ask AI & photo carbs)"))
        val ic = Ui.card(this)
        ic.addView(Ui.text(this, "Pick the AI to use, then paste its API key. You can fill all three and switch anytime.", 13f, Ui.MUTED))
        aiChips = Ui.row(this)
        ic.addView(aiChips, Ui.lp(top = Ui.dp(this, 8)))
        renderAiChips()
        for (p in Providers.all) {
            ic.addView(Ui.text(this, p.label, 16f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 14), 0, 0) })
            val k = Ui.input(this, "${p.label} API key", multi = false).apply {
                setText(st.key(p)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            keys[p.id] = k
            ic.addView(k, Ui.lp(top = Ui.dp(this, 6)))
            val m = Ui.input(this, "Model (found automatically by Save & test)", multi = false).apply { setText(st.model(p)) }
            models[p.id] = m
            ic.addView(m, Ui.lp(top = Ui.dp(this, 6)))
            val r = Ui.row(this)
            val res = Ui.text(this, "", 12f, Ui.MUTED)
            r.addView(Ui.button(this, "Get key") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyPage))) },
                Ui.lp(-2, end = Ui.dp(this, 8)))
            r.addView(Ui.button(this, "💾 Save & test", true) { test(p, res) }, Ui.lp(0, weight = 1f))
            ic.addView(r, Ui.lp(top = Ui.dp(this, 8)))
            ic.addView(res, Ui.lp(top = Ui.dp(this, 4)))
        }
        root.addView(ic)

        // ---------------------------------------------------------- Photo tips
        root.addView(Ui.section(this, "How photo carb estimates work"))
        val tc = Ui.card(this)
        Foods.PHOTO_TIPS.forEach { tc.addView(Ui.text(this, "•  $it", 13f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 3), 0, 0) }) }
        root.addView(tc)

        // ---------------------------------------------------------- Backup & folder
        root.addView(Ui.section(this, "Backup & Download folder"))
        val bc = Ui.card(this)
        folderStatus = Ui.text(this, "", 14f, Ui.TEXT)
        bc.addView(folderStatus)
        bc.addView(Ui.text(this, "Saved there: readings (CSV per month, every 15 min), meals with photos, alerts, a daily glucose assessment, and the backup. After a reinstall, choose the folder again to resume.",
            12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 4), 0, 0) })
        bc.addView(Ui.button(this, "📁 Choose folder") { FolderFlow.pick(this) }, Ui.lp(top = Ui.dp(this, 10)))
        backupPw = Ui.input(this, "Backup password (locks login & API keys)", multi = false).apply {
            setText(st.backupPw); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        bc.addView(backupPw, Ui.lp(top = Ui.dp(this, 10)))
        bc.addView(Ui.text(this, "Keep this password safe — it's needed to restore the LibreLinkUp login and API keys.", 12f, Ui.WARN))
        backupMsg = Ui.text(this, "", 13f, Ui.MUTED)
        bc.addView(Ui.button(this, "💾 Save backup now", true) { backupNow() }, Ui.lp(top = Ui.dp(this, 10)))
        val br = Ui.row(this)
        br.addView(Ui.button(this, "📤 Save as file…") { save(false); exportFile() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        br.addView(Ui.button(this, "♻ Restore file…") { importFile() }, Ui.lp(0, weight = 1f))
        bc.addView(br, Ui.lp(top = Ui.dp(this, 8)))
        bc.addView(Ui.button(this, "♻ Resume everything from folder") {
            if (!Vault.ready(this)) FolderFlow.pick(this) else FolderFlow.askResume(this) { recreate() }
        }, Ui.lp(top = Ui.dp(this, 8)))
        bc.addView(backupMsg, Ui.lp(top = Ui.dp(this, 6)))
        root.addView(bc)
        renderFolder()

        // ---------------------------------------------------------- About
        root.addView(Ui.section(this, "About"))
        val ab = Ui.card(this)
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        ab.addView(Ui.text(this, "Nova Glucose $ver", 15f, Ui.TEXT, true))
        ab.addView(Ui.text(this, Foods.CARE_NOTE, 13f, Ui.MUTED))
        ab.addView(Ui.text(this, "LibreLinkUp app version sent to Abbott (updates itself when Abbott asks):", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(context, 10), 0, 0) })
        lluVersion = Ui.input(this, Libre.DEFAULT_VERSION, multi = false).apply { setText(st.appVersion) }
        ab.addView(lluVersion, Ui.lp(top = Ui.dp(this, 6)))
        ab.addView(Ui.button(this, "Sign out of LibreLinkUp") {
            st.password = ""; st.token = ""; st.patientId = ""; st.patientName = ""; st.lastTs = 0
            MonitorService.stop(this); pw.setText(""); loginStatus.text = "Signed out"
        }, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(ab)

        root.addView(Ui.signature(this))
        setContentView(sv)
    }

    private fun switch(label: String, on: Boolean) = Switch(this).apply {
        text = label; isChecked = on; setTextColor(Ui.TEXT); textSize = 14f
        setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6))
    }

    private fun numRow(id: String, label: String, v: Int): View {
        val r = Ui.row(this)
        r.addView(Ui.text(this, label, 14f, Ui.TEXT), Ui.lp(0, weight = 1f))
        val e = Ui.input(this, "", multi = false).apply {
            setText(v.toString()); inputType = InputType.TYPE_CLASS_NUMBER
            gravity = android.view.Gravity.CENTER
        }
        nums[id] = e
        r.addView(e, Ui.lp(Ui.dp(this, 84), -2))
        r.setPadding(0, Ui.dp(this, 8), 0, 0)
        return r
    }

    private fun renderAiChips() {
        aiChips.removeAllViews()
        for (p in Providers.all) aiChips.addView(Ui.chip(this, p.label, st.aiProvider == p.id) {
            st.aiProvider = p.id; renderAiChips()
        }, Ui.lp(-2, -2, end = Ui.dp(this, 8)))
    }

    private fun num(id: String, d: Int) = nums[id]?.text?.toString()?.trim()?.toIntOrNull() ?: d

    private fun save(toast: Boolean) {
        val loginChanged = email.text.toString().trim() != st.email || pw.text.toString() != st.password
        st.email = email.text.toString()
        st.password = pw.text.toString()
        if (loginChanged) { st.token = ""; st.patientId = "" }
        st.alertsOn = alerts.isChecked; st.staleAlert = stale.isChecked
        st.urgentLow = num("urgent_low", 55).coerceIn(40, 80)
        st.low = num("low", 70).coerceIn(50, 100)
        st.high = num("high", 180).coerceIn(120, 300)
        st.veryHigh = num("very_high", 250).coerceIn(180, 400)
        st.repeatMin = num("repeat", 20).coerceIn(5, 240)
        st.lowTreatG = num("low_treat", 15).coerceIn(5, 40)
        st.mealMin = num("meal_min", 45); st.mealMax = num("meal_max", 75).coerceAtLeast(st.mealMin)
        st.snackMin = num("snack_min", 15); st.snackMax = num("snack_max", 30).coerceAtLeast(st.snackMin)
        st.foodNotes = notes.text.toString().trim()
        for (p in Providers.all) {
            st.setKey(p, keys[p.id]!!.text.toString())
            st.setModel(p, models[p.id]!!.text.toString())
        }
        st.appVersion = lluVersion.text.toString().trim().ifBlank { Libre.DEFAULT_VERSION }
        st.backupPw = backupPw.text.toString()
        val wasOn = st.monitorOn
        st.monitorOn = monitor.isChecked
        if (!st.monitorOn && wasOn) MonitorService.stop(this)
        if (st.monitorOn && st.loggedIn) MonitorService.start(this)
        if (toast) {
            Ui.toast(this, "Saved")
            if (Vault.ready(this)) { val app = applicationContext; Thread { try { Vault.backupNow(app) } catch (_: Exception) { } }.start() }
        }
    }

    private fun connect() {
        save(false)
        if (!st.loggedIn) { loginStatus.setTextColor(Ui.BAD); loginStatus.text = "Enter email and password."; return }
        loginStatus.setTextColor(Ui.MUTED); loginStatus.text = "Connecting…"
        Thread {
            try {
                val list = Libre(st).login()
                runOnUiThread {
                    if (list.isEmpty()) {
                        loginStatus.setTextColor(Ui.WARN)
                        loginStatus.text = "Signed in, but this account doesn't follow anyone. Accept her invitation in LibreLinkUp first."
                        return@runOnUiThread
                    }
                    if (list.none { it.id == st.patientId }) { st.patientId = list[0].id; st.patientName = list[0].name }
                    showPatients(list)
                    MonitorService.stop(this); MonitorService.start(this)
                }
            } catch (e: Exception) {
                runOnUiThread { loginStatus.setTextColor(Ui.BAD); loginStatus.text = e.message ?: "Could not connect" }
            }
        }.start()
    }

    private fun showPatients(list: List<Patient>) {
        loginStatus.setTextColor(Ui.GOOD)
        loginStatus.text = "Connected · following ${st.patientName}"
        patients.removeAllViews()
        if (list.size < 2) return
        list.forEach { p ->
            patients.addView(Ui.chip(this, p.name, p.id == st.patientId) {
                st.patientId = p.id; st.patientName = p.name; st.lastTs = 0
                showPatients(list); MonitorService.stop(this); MonitorService.start(this)
            }, Ui.lp(-2, -2, end = Ui.dp(this, 8)))
        }
    }

    private fun test(p: Provider, out: TextView) {
        save(false)
        if (st.key(p).isBlank()) { out.setTextColor(Ui.BAD); out.text = "Paste the API key first."; return }
        st.aiProvider = p.id; renderAiChips()
        out.setTextColor(Ui.MUTED); out.text = "Saved · checking the key and finding a model…"
        Thread {
            val r = try { Result.success(Providers.saveTest(st, p) { s -> runOnUiThread { out.text = s } }) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { (m, a) ->
                    models[p.id]!!.setText(m)
                    out.setTextColor(Ui.GOOD); out.text = "✓ Saved & connected · model $m · \"${a.take(70)}\""
                }.onFailure { e -> out.setTextColor(Ui.BAD); out.text = "Key saved, but: ${e.message}" }
            }
        }.start()
    }

    // ---------------------------------------------------------- backup

    private fun renderFolder() {
        val ok = Vault.ready(this)
        folderStatus.setTextColor(if (ok) Ui.GOOD else Ui.WARN)
        val last = if (st.lastBackupTs > 0) " · last backup " +
            java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date(st.lastBackupTs)) else ""
        folderStatus.text = if (ok) "📁 ${Vault.folderLabel(this)}$last" else "📁 Folder not chosen yet"
    }

    private fun backupNow() {
        save(false)
        if (st.backupPw.isBlank()) { backupMsg.setTextColor(Ui.BAD); backupMsg.text = "Set a backup password first."; return }
        if (!Vault.ready(this)) { FolderFlow.pick(this); return }
        backupMsg.setTextColor(Ui.MUTED); backupMsg.text = "Saving…"
        val app = applicationContext
        Thread {
            val r = try {
                Vault.exportAll(app, forceBackup = true)
                Result.success(Vault.backupNow(app))
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { backupMsg.setTextColor(Ui.GOOD); backupMsg.text = "✓ $it" }
                    .onFailure { backupMsg.setTextColor(Ui.BAD); backupMsg.text = it.message ?: "Failed" }
                renderFolder()
            }
        }.start()
    }

    private fun exportFile() {
        if (st.backupPw.isBlank()) { backupMsg.setTextColor(Ui.BAD); backupMsg.text = "Set a backup password first."; return }
        pendingBackup = Backup.build(this, st.backupPw)
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, Backup.FILE)
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun restoreFromText(text: String) {
        val pw = Ui.input(this, "Backup password", multi = false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this).setTitle("Restore settings?")
            .setMessage("Settings, LibreLinkUp login and API keys will be replaced by the backup.")
            .setView(Ui.col(this, 20).apply { addView(pw) })
            .setPositiveButton("Restore") { _, _ ->
                try {
                    val msg = Backup.restore(this, text, pw.text.toString())
                    MonitorService.stop(this); MonitorService.start(this)
                    Ui.toast(this, msg); recreate()
                } catch (e: Exception) { backupMsg.setTextColor(Ui.BAD); backupMsg.text = e.message }
            }
            .setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (FolderFlow.onResult(this, req, res, data) { recreate() }) return
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        try {
            when (req) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write((pendingBackup ?: "").toByteArray()) }
                    backupMsg.setTextColor(Ui.GOOD); backupMsg.text = "✓ Backup file saved"
                }
                REQ_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (!Backup.isBackup(text)) { backupMsg.setTextColor(Ui.BAD); backupMsg.text = "This isn't a Nova Glucose backup file." }
                    else restoreFromText(text)
                }
            }
        } catch (e: Exception) { backupMsg.setTextColor(Ui.BAD); backupMsg.text = e.message }
    }

    companion object { private const val REQ_EXPORT = 11; private const val REQ_IMPORT = 12 }

    private fun openNotifSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
