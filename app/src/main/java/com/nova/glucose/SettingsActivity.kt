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
    private var pendingBackup: ByteArray? = null
    private lateinit var genderRow: LinearLayout
    private lateinit var aboutE: EditText
    /** After a restore the screen must close WITHOUT saving its old fields over the restored values. */
    private var restored = false

    private fun reopen(msg: String?) {
        restored = true
        if (msg != null) android.app.AlertDialog.Builder(this).setTitle("✓ Restored").setMessage(msg.replace(" · ", "\n"))
            .setPositiveButton("OK") { _, _ -> finish(); startActivity(Intent(this, SettingsActivity::class.java)) }
            .setCancelable(false).show()
        else { finish(); startActivity(Intent(this, SettingsActivity::class.java)) }
    }
    private lateinit var driveStatus: TextView
    private lateinit var driveConnBtn: TextView
    private lateinit var driveAutoSw: Switch
    private lateinit var driveFreqRow: LinearLayout
    private lateinit var driveWifiCb: android.widget.CheckBox
    private lateinit var drivePhotoCb: android.widget.CheckBox
    private lateinit var driveMsgV: TextView
    private lateinit var lockBtn: TextView
    private lateinit var lockBanner: TextView
    private lateinit var pinSw: Switch
    private lateinit var allViews: View
    private var uiLocked = false
    private val lockTick = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var urgSw: Switch
    private lateinit var cbUL: android.widget.CheckBox
    private lateinit var cbL: android.widget.CheckBox
    private lateinit var cbH: android.widget.CheckBox
    private lateinit var cbVH: android.widget.CheckBox
    private lateinit var cbNS: android.widget.CheckBox
    private lateinit var soundTxt: TextView
    private lateinit var vibRow: LinearLayout
    private lateinit var volTxt: TextView
    private lateinit var volBar: android.widget.SeekBar
    private lateinit var permStatus: TextView
    private lateinit var longNameE: EditText
    private lateinit var longDoseE: EditText
    private lateinit var longTimeE: EditText
    private lateinit var rapidNameE: EditText
    private lateinit var penStatus: TextView
    private var penTarget = ""
    private var penFile: java.io.File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        build()
    }

    override fun onPause() { save(false); lockTick.removeCallbacksAndMessages(null); super.onPause() }

    override fun finish() { save(false); Parent.lock(); super.finish() }

    private val watch = object : Runnable {
        override fun run() {
            // 5 minutes passed: save what was changed, then lock the screen.
            if (!uiLocked && Parent.locked(st)) { save(false); renderLock() }
            lockTick.postDelayed(this, 5_000)
        }
    }

    private fun toggleLock() {
        if (!Parent.enabled(st)) { Ui.toast(this, "Turn on the parent PIN in Parental control first"); return }
        if (uiLocked) Parent.ask(this, "Enter the PIN to change settings.") { renderLock() }
        else { save(false); Parent.lock(); renderLock() }
    }

    /** View-only when locked: every control is disabled except Unlock and Done. */
    private fun renderLock() {
        uiLocked = Parent.locked(st)
        lockBanner.visibility = if (uiLocked) View.VISIBLE else View.GONE
        lockBtn.visibility = if (Parent.enabled(st)) View.VISIBLE else View.GONE
        lockBtn.text = if (uiLocked) "🔓 Unlock" else "🔒 Lock"
        fun walk(v: View) {
            if (v.tag == "free") return
            if (v is EditText || v is android.widget.CompoundButton || v is android.widget.SeekBar || v.hasOnClickListeners()) {
                v.isEnabled = !uiLocked; v.alpha = if (uiLocked) 0.7f else 1f
            }
            if (v is android.view.ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k))
        }
        walk(allViews)
        pinSw.setOnCheckedChangeListener(null)
        pinSw.isChecked = Parent.enabled(st)
        pinSw.setOnCheckedChangeListener { b, on ->
            if (on && st.pinHash.isBlank()) Parent.setPin(this) { ok -> if (!ok) b.isChecked = false; renderLock() }
            else { st.pinOn = on; if (on) Parent.unlockFor5(); renderLock() }
        }
    }

    private fun build() {
        root = Ui.col(this, 16)
        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) }
        val head = Ui.row(this)
        head.addView(Ui.title(this, "Settings"), Ui.lp(0, weight = 1f))
        lockBtn = Ui.smallButton(this, "") { toggleLock() }.apply { tag = "free" }
        head.addView(lockBtn, Ui.lp(-2, Ui.dp(this, 36), end = Ui.dp(this, 8)))
        head.addView(Ui.smallButton(this, "Done") { save(true); finish() }.apply { tag = "free" })
        root.addView(head)
        lockBanner = Ui.text(this, "🔒 Locked by parent — view only. Ask a parent to change settings.", 14f, Ui.WARN).apply {
            background = Ui.round(0x22FFB347, Ui.dp(context, 10).toFloat())
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }
        root.addView(lockBanner, Ui.lp(top = Ui.dp(this, 8)))

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
        stale = switch("Notify when there's no new reading (no signal)", st.staleAlert)
        ac.addView(stale, Ui.lp(top = Ui.dp(this, 6)))
        ac.addView(numRow("stale_min", "No signal after (minutes)", st.staleMin))
        ac.addView(Ui.button(this, "Normal alert sound & vibration…") { openNotifSettings() }, Ui.lp(top = Ui.dp(this, 8)))
        root.addView(ac)

        // ---------------------------------------------------------- Urgent alarm
        root.addView(Ui.section(this, "Urgent alarm (rings through silent and Do Not Disturb)"))
        val uc = Ui.card(this)
        urgSw = switch("Urgent alarm on", st.urgentOn)
        uc.addView(urgSw)
        uc.addView(Ui.text(this, "Which alerts ring as urgent alarms (full screen, alarm sound, even when the phone is silent):", 13f, Ui.MUTED))
        cbUL = check("Urgent low", st.urgUrgentLow); cbL = check("Low", st.urgLow)
        cbH = check("High", st.urgHigh); cbVH = check("Very high", st.urgVeryHigh)
        cbNS = check("No signal — no new reading for the minutes set in Alerts", st.urgNoSignal)
        listOf(cbUL, cbL, cbH, cbVH, cbNS).forEach { uc.addView(it) }
        soundTxt = Ui.text(this, "Sound: ${st.alarmSoundName}", 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 10), 0, 0) }
        uc.addView(soundTxt)
        val sr = Ui.row(this)
        sr.addView(Ui.button(this, "Phone sounds…") { pickRingtone() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        sr.addView(Ui.button(this, "Song file…") { pickAudio() }, Ui.lp(0, weight = 1f))
        uc.addView(sr, Ui.lp(top = Ui.dp(this, 6)))
        uc.addView(Ui.text(this, "Vibration", 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4)) })
        vibRow = Ui.row(this); uc.addView(vibRow); renderVib()
        uc.addView(numRow("ring_sec", "Ring for (seconds)", st.ringSec))
        uc.addView(numRow("urg_repeat", "Repeat every (minutes) while still out of range", st.urgentRepeat))
        volTxt = Ui.text(this, "", 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 12), 0, 0) }
        uc.addView(volTxt)
        volBar = android.widget.SeekBar(this).apply {
            max = 90; progress = (st.alarmVolume - 10).coerceIn(0, 90)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) { volTxt.text = "Urgent alarm volume: ${p + 10}% (rises over 5 s, restored after)" }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        }
        volTxt.text = "Urgent alarm volume: ${st.alarmVolume}% (rises over 5 s, restored after)"
        uc.addView(volBar, Ui.lp(top = Ui.dp(this, 4)))
        uc.addView(Ui.button(this, "🔔 Test urgent alarm", true) {
            save(false); startActivity(Intent(this, AlarmActivity::class.java).putExtra("test", true))
        }, Ui.lp(top = Ui.dp(this, 10)))
        uc.addView(Ui.text(this, "Phone permissions for the alarm", 14f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 14), 0, 0) })
        permStatus = Ui.text(this, "", 13f, Ui.MUTED)
        uc.addView(permStatus)
        val pr1 = Ui.row(this)
        pr1.addView(Ui.button(this, "Full-screen alarms") { openFullScreenPerm() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        pr1.addView(Ui.button(this, "Do Not Disturb access") { openDndPerm() }, Ui.lp(0, weight = 1f))
        uc.addView(pr1, Ui.lp(top = Ui.dp(this, 6)))
        val pr2 = Ui.row(this)
        pr2.addView(Ui.button(this, "Exact timers") { openExactPerm() }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        pr2.addView(Ui.button(this, "Battery: no limits") { openBatteryPerm() }, Ui.lp(0, weight = 1f))
        uc.addView(pr2, Ui.lp(top = Ui.dp(this, 6)))
        uc.addView(Ui.text(this, brandTip(), 12f, Ui.WARN).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        root.addView(uc)

        // ---------------------------------------------------------- Profile and insulin
        root.addView(Ui.section(this, "Profile and insulin (as prescribed — reference only)"))
        val pc = Ui.card(this)
        pc.addView(numRow("age", "Age", st.age))
        pc.addView(Ui.text(this, "Gender", 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4)) })
        genderRow = Ui.row(this); pc.addView(genderRow); renderGender()
        pc.addView(Ui.text(this, "About her (brief, used by the AI — never for doses)", 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4)) })
        aboutE = Ui.input(this, "e.g. type 1 since 2019, swims Mon/Wed 5 pm, school lunch 12:30, loves couscous, no nuts", minLines = 3).apply { setText(st.aboutHer) }
        pc.addView(aboutE)
        pc.addView(Ui.text(this, "Long-acting insulin", 14f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
        longNameE = Ui.input(this, "Name, e.g. Tresiba", multi = false).apply { setText(st.longName) }
        pc.addView(nameRow(longNameE, "long"), Ui.lp(top = Ui.dp(this, 6)))
        longDoseE = Ui.input(this, "Usual dose (units) from the last prescription", multi = false).apply {
            setText(st.longDose); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        pc.addView(longDoseE, Ui.lp(top = Ui.dp(this, 6)))
        longTimeE = Ui.input(this, "Usual time, e.g. 21:00", multi = false).apply { setText(st.longTime) }
        pc.addView(longTimeE, Ui.lp(top = Ui.dp(this, 6)))
        pc.addView(Ui.text(this, "Rapid insulin", 14f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
        rapidNameE = Ui.input(this, "Name, e.g. NovoRapid", multi = false).apply { setText(st.rapidName) }
        pc.addView(nameRow(rapidNameE, "rapid"), Ui.lp(top = Ui.dp(this, 6)))
        pc.addView(numRow("carb_ratio", "Prescribed ratio: 1 unit per … g carbs", st.carbRatio))
        pc.addView(numRow("eat_wait", "Wait before eating after rapid (min)", st.eatWait))
        pc.addView(numRow("target_mg", "Target glucose (mg/dL)", st.targetMg))
        pc.addView(numRow("corr_factor", "Prescribed correction: 1 unit lowers … mg/dL", st.corrFactor))
        pc.addView(Ui.text(this, "Kept as reference in the logbook and daily report, and as background for the AI. " +
            "The app never calculates or suggests a dose.", 12f, Ui.WARN).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        penStatus = Ui.text(this, "", 12f, Ui.MUTED)
        pc.addView(penStatus)
        root.addView(pc)

        // ---------------------------------------------------------- Carb plan
        root.addView(Ui.section(this, "Carb plan (grams)"))
        val cc = Ui.card(this)
        cc.addView(Ui.text(this, "Standard guidance. Replace with her care team's numbers when you have them.", 13f, Ui.MUTED))
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
        ic.addView(Ui.button(this, "⚖ Compare models (time and accuracy)", true) {
            save(false); startActivity(Intent(this, CompareActivity::class.java))
        }, Ui.lp(top = Ui.dp(this, 14)))
        root.addView(ic)

        // ---------------------------------------------------------- Photo tips
        root.addView(Ui.section(this, "How photo carb estimates work"))
        val tc = Ui.card(this)
        Foods.PHOTO_TIPS.forEach { tc.addView(Ui.text(this, "•  $it", 13f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 3), 0, 0) }) }
        root.addView(tc)

        // ---------------------------------------------------------- Parental control
        root.addView(Ui.section(this, "Parental control"))
        val lc2 = Ui.card(this)
        pinSw = switch("Lock settings with a parent PIN", Parent.enabled(st)).apply {
            setOnCheckedChangeListener { b, on ->
                if (on && st.pinHash.isBlank()) Parent.setPin(this@SettingsActivity) { ok -> if (!ok) b.isChecked = false; renderLock() }
                else { st.pinOn = on; if (on) Parent.unlockFor5(); renderLock() }
            }
        }
        lc2.addView(pinSw)
        lc2.addView(Ui.button(this, "Set / change PIN (4 digits)") { Parent.setPin(this) { renderLock() } }, Ui.lp(top = Ui.dp(this, 8)))
        lc2.addView(Ui.text(this, "When on: everyone can see Settings, but changing anything, deleting meals or insulin doses, " +
            "and changing the AI model need the PIN. Stays unlocked until you leave Settings (5 min at most). " +
            "Forgot the PIN? Use the backup password.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        root.addView(lc2)

        // ---------------------------------------------------------- Backup & folder
        root.addView(Ui.section(this, "Backup & Download folder"))
        val bc = Ui.card(this)
        folderStatus = Ui.text(this, "", 14f, Ui.TEXT)
        bc.addView(folderStatus)
        bc.addView(Ui.text(this, "Saved there: Readings (one CSV per day, every 15 min), Daily (PDF report per day), Meals with photos, Insulin, Alerts, AI-Training and Backup (newest 7). After a reinstall, choose the folder again to resume.",
            12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 4), 0, 0) })
        bc.addView(Ui.button(this, "📁 Choose folder") { FolderFlow.pick(this) }, Ui.lp(top = Ui.dp(this, 10)))
        backupPw = Ui.input(this, "Backup password (required — encrypts the whole backup)", multi = false).apply {
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
        bc.addView(Ui.button(this, "📤 Share backup (WhatsApp, e-mail…)") { shareBackup() }, Ui.lp(top = Ui.dp(this, 8)))
        bc.addView(Ui.button(this, "♻ Resume everything from folder") {
            if (!Vault.ready(this)) FolderFlow.pick(this) else FolderFlow.askResume(this) { reopen(null) }
        }, Ui.lp(top = Ui.dp(this, 8)))
        bc.addView(backupMsg, Ui.lp(top = Ui.dp(this, 6)))
        root.addView(bc)
        renderFolder()

        // ---------------------------------------------------------- Google Drive
        root.addView(Ui.section(this, "Google Drive backup"))
        val gd = Ui.card(this)
        driveStatus = Ui.text(this, "", 14f, Ui.TEXT)
        gd.addView(driveStatus)
        val gr = Ui.row(this)
        driveConnBtn = Ui.button(this, "") {
            if (st.driveOn) { DriveSync.disconnect(this); renderDrive() }
            else { save(false); driveMsg("Opening Google…", Ui.MUTED); DriveSync.connect(this) { err -> if (err != null) driveMsg(err, Ui.BAD); renderDrive() } }
        }
        gr.addView(driveConnBtn, Ui.lp(0, weight = 1f))
        gd.addView(gr, Ui.lp(top = Ui.dp(this, 8)))
        driveAutoSw = switch("Automatic backup", st.driveAuto)
        gd.addView(driveAutoSw, Ui.lp(top = Ui.dp(this, 8)))
        driveFreqRow = Ui.row(this); gd.addView(driveFreqRow); renderFreq()
        gd.addView(Ui.text(this, "Runs around 02:00. Uploads only what changed since last time.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        driveWifiCb = check("Only on Wi-Fi", st.driveWifi)
        drivePhotoCb = check("Include meal photos (bigger upload)", st.drivePhotos)
        gd.addView(driveWifiCb); gd.addView(drivePhotoCb)
        gd.addView(Ui.text(this, "Uploads readings, daily PDFs, meals, insulin, alerts, AI-Training and the settings backup " +
            "from Download/NovaGlucose to My Drive › NovaGlucose › ${st.fileName}.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        gd.addView(Ui.button(this, "☁ Back up to Drive now", true) { driveNow() }, Ui.lp(top = Ui.dp(this, 10)))
        gd.addView(Ui.button(this, "♻ Restore from Drive") { driveRestore() }, Ui.lp(top = Ui.dp(this, 8)))
        driveMsgV = Ui.text(this, "", 13f, Ui.MUTED)
        gd.addView(driveMsgV, Ui.lp(top = Ui.dp(this, 6)))
        root.addView(gd)
        renderDrive()

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
        allViews = root
        setupFolds()
        renderLock()
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
        if (uiLocked || restored) return
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
        st.urgentOn = urgSw.isChecked
        st.urgUrgentLow = cbUL.isChecked; st.urgLow = cbL.isChecked; st.urgHigh = cbH.isChecked; st.urgVeryHigh = cbVH.isChecked
        st.urgNoSignal = cbNS.isChecked; st.staleMin = num("stale_min", 20).coerceIn(5, 240)
        st.ringSec = num("ring_sec", 60).coerceIn(10, 600)
        st.urgentRepeat = num("urg_repeat", 5).coerceIn(1, 60)
        st.alarmVolume = (volBar.progress + 10).coerceIn(10, 100)
        st.age = num("age", 14).coerceIn(1, 99)
        st.longName = longNameE.text.toString(); st.longDose = longDoseE.text.toString().replace(',', '.')
        st.longTime = longTimeE.text.toString(); st.rapidName = rapidNameE.text.toString()
        st.carbRatio = num("carb_ratio", 0).coerceIn(0, 100)
        st.eatWait = num("eat_wait", 0).coerceIn(0, 90)
        st.targetMg = num("target_mg", 100).coerceIn(70, 180)
        st.aboutHer = aboutE.text.toString()
        st.corrFactor = num("corr_factor", 0).coerceIn(0, 400)
        if (::driveAutoSw.isInitialized) { st.driveAuto = driveAutoSw.isChecked; st.driveWifi = driveWifiCb.isChecked; st.drivePhotos = drivePhotoCb.isChecked }
        val wasOn = st.monitorOn
        st.monitorOn = monitor.isChecked
        if (!st.monitorOn && wasOn) MonitorService.stop(this)
        if (st.monitorOn && st.loggedIn) MonitorService.start(this)
        if (::root.isInitialized && folds.isNotEmpty()) renderFolds()
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

    private fun shareBackup() {
        save(false)
        if (st.backupPw.isBlank()) { backupMsg.setTextColor(Ui.BAD); backupMsg.text = "Set a backup password first."; return }
        backupMsg.setTextColor(Ui.MUTED); backupMsg.text = "Preparing the backup…"
        val app = applicationContext
        Thread {
            val r = try {
                val name = Vault.backupFileName(app)
                val bytes = Backup.pack(app, st.backupPw)
                Backup.verifyFile(bytes, app, st.backupPw)
                val dir = java.io.File(cacheDir, "capture").apply { mkdirs() }
                dir.listFiles()?.filter { it.name.endsWith(Vault.EXT) }?.forEach { it.delete() }
                Result.success(java.io.File(dir, name).apply { writeBytes(bytes) })
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { f ->
                    backupMsg.setTextColor(Ui.GOOD); backupMsg.text = "✓ Encrypted backup ready: ${f.name}"
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.nova.glucose.files", f)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(Backup.MIME)
                        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send the backup"))
                }.onFailure { backupMsg.setTextColor(Ui.BAD); backupMsg.text = it.message }
            }
        }.start()
    }

    private fun exportFile() {
        if (st.backupPw.isBlank()) { backupMsg.setTextColor(Ui.BAD); backupMsg.text = "Set a backup password first."; return }
        pendingBackup = Backup.pack(this, st.backupPw)
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, Vault.backupFileName(this))
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
        android.app.AlertDialog.Builder(this).setTitle("Restore everything?")
            .setMessage("All settings, the LibreLinkUp login, API keys, readings, meals and insulin doses from the backup will be restored on this phone.")
            .setView(Ui.col(this, 20).apply { addView(pw) })
            .setPositiveButton("Restore") { _, _ ->
                restored = true      // from now on this screen never saves its old fields
                backupMsg.setTextColor(Ui.MUTED); backupMsg.text = "Restoring…"
                val app = applicationContext; val p = pw.text.toString()
                Thread {
                    val r = try { Result.success(Backup.restore(app, text, p)) } catch (e: Exception) { Result.failure(e) }
                    runOnUiThread {
                        r.onSuccess { msg -> MonitorService.stop(app); MonitorService.start(app); reopen(msg) }
                            .onFailure { e -> restored = false; backupMsg.setTextColor(Ui.BAD); backupMsg.text = e.message }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (FolderFlow.onResult(this, req, res, data) { reopen(null) }) return
        if (req == DriveSync.REQ_CONNECT) {
            if (res != RESULT_OK) { driveMsg("Google access not given", Ui.WARN); return }
            DriveSync.onResult(this, req, data) { err -> if (err != null) driveMsg(err, Ui.BAD) else driveMsg("✓ Google Drive connected", Ui.GOOD); renderDrive() }
            return
        }
        if (res != RESULT_OK) return
        if (req == REQ_RINGTONE) {
            @Suppress("DEPRECATION")
            val u = data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            st.alarmSound = u?.toString() ?: ""
            st.alarmSoundName = u?.let { android.media.RingtoneManager.getRingtone(this, it)?.getTitle(this) } ?: "Default alarm"
            soundTxt.text = "Sound: ${st.alarmSoundName}"; return
        }
        if (req == REQ_PEN_CAM) { penFile?.let { identifyPen(it.readBytes()) }; return }
        val uri = data?.data ?: return
        try {
            when (req) {
                REQ_AUDIO -> {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    st.alarmSound = uri.toString()
                    st.alarmSoundName = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)?.name ?: "Song file"
                    soundTxt.text = "Sound: ${st.alarmSoundName}"
                }
                REQ_PEN_GAL -> contentResolver.openInputStream(uri)?.use { identifyPen(it.readBytes()) }
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(pendingBackup ?: ByteArray(0)) }
                    backupMsg.setTextColor(Ui.GOOD); backupMsg.text = "✓ Backup file saved"
                }
                REQ_IMPORT -> {
                    // Same path as opening a backup from WhatsApp: PIN (if on) → password → restore → summary.
                    restored = true
                    startActivity(Intent(this, ImportActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    finish()
                }
            }
        } catch (e: Exception) { backupMsg.setTextColor(Ui.BAD); backupMsg.text = e.message }
    }

    companion object {
        private const val REQ_EXPORT = 11; private const val REQ_IMPORT = 12; private const val REQ_RINGTONE = 13
        private const val REQ_AUDIO = 14; private const val REQ_PEN_CAM = 15; private const val REQ_PEN_GAL = 16
    }

    override fun onResume() {
        super.onResume()
        if (::permStatus.isInitialized) renderPerms()
        if (::allViews.isInitialized) renderLock()
        lockTick.post(watch)
    }

    private fun driveMsg(s: String, col: Int) { driveMsgV.setTextColor(col); driveMsgV.text = s }

    private fun renderFreq() {
        driveFreqRow.removeAllViews()
        listOf("d" to "Every day", "3" to "Every 3 days", "w" to "Every week").forEach { (id, l) ->
            driveFreqRow.addView(Ui.chip(this, l, st.driveFreq == id) { st.driveFreq = id; renderFreq(); renderDrive() },
                Ui.lp(-2, -2, end = Ui.dp(this, 8)))
        }
        if (::allViews.isInitialized && uiLocked) renderLock()
    }

    private fun renderDrive() {
        val f = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.US)
        driveStatus.setTextColor(if (st.driveOn) Ui.GOOD else Ui.MUTED)
        driveStatus.text = if (!st.driveOn) "☁ Not connected" else
            "☁ Connected: ${st.driveEmail.ifBlank { "Google account" }}" +
                (if (st.driveLast > 0) "\nLast upload: ${f.format(java.util.Date(st.driveLast))} · ${st.driveLastInfo}" else "\nNo upload yet") +
                (if (st.driveAuto) "\nNext: ${f.format(java.util.Date(DriveSync.nextRun(st)))}" else "")
        driveConnBtn.text = if (st.driveOn) "Disconnect Google Drive" else "Connect Google Drive"
    }

    private fun driveNow() {
        save(false)
        if (!st.driveOn) { driveMsg("Connect Google Drive first", Ui.WARN); return }
        driveMsg("Starting…", Ui.MUTED)
        val app = applicationContext
        Thread {
            val r = try { Result.success(DriveSync.backup(app) { s -> runOnUiThread { driveMsg(s, Ui.MUTED) } }) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { driveMsg("✓ $it", Ui.GOOD) }.onFailure { driveMsg(it.message ?: "Failed", Ui.BAD) }
                renderDrive()
            }
        }.start()
    }

    private fun driveRestore() {
        if (!st.driveOn) { driveMsg("Connect Google Drive first", Ui.WARN); return }
        if (!Vault.ready(this)) { FolderFlow.pick(this); return }
        android.app.AlertDialog.Builder(this).setTitle("Restore from Google Drive?")
            .setMessage("Files from My Drive › NovaGlucose are copied into Download/NovaGlucose, then you can resume settings, readings, meals and doses with the backup password.")
            .setPositiveButton("Download") { _, _ ->
                val app = applicationContext
                Thread {
                    val r = try { Result.success(DriveSync.download(app) { s -> runOnUiThread { driveMsg(s, Ui.MUTED) } }) } catch (e: Exception) { Result.failure(e) }
                    runOnUiThread {
                        r.onSuccess { driveMsg("✓ $it", Ui.GOOD); FolderFlow.askResume(this) { reopen(null) } }
                            .onFailure { driveMsg(it.message ?: "Failed", Ui.BAD) }
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renderGender() {
        genderRow.removeAllViews()
        listOf("girl" to "Girl", "boy" to "Boy").forEach { (id, l) ->
            genderRow.addView(Ui.chip(this, l, st.gender == id) { st.gender = if (st.gender == id) "" else id; renderGender(); if (uiLocked) renderLock() },
                Ui.lp(-2, -2, end = Ui.dp(this, 8)))
        }
    }

    // ---------------------------------------------------------- folding sections
    private class Fold(val head: TextView, val body: View, val title: String)
    private val folds = mutableListOf<Fold>()

    private fun setupFolds() {
        val kids = (0 until root.childCount).map { root.getChildAt(it) }
        for (k in 0 until kids.size - 1) {
            val v = kids[k]
            if (v !is TextView || v.tag != "section") continue
            val f = Fold(v, kids[k + 1], v.text.toString())
            folds += f
            v.tag = "free"
            v.background = Ui.round(Ui.CARD, Ui.dp(this, 14).toFloat())
            v.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12))
            (v.layoutParams as? LinearLayout.LayoutParams)?.topMargin = Ui.dp(this, 8)
            v.setOnClickListener {
                val open = f.body.visibility != View.VISIBLE
                f.body.visibility = if (open) View.VISIBLE else View.GONE
                if (open) st.settingsOpen = f.title
                renderFold(f)
            }
            f.body.visibility = if (f.title == st.settingsOpen) View.VISIBLE else View.GONE
            renderFold(f)
        }
    }

    private fun renderFolds() = folds.forEach { renderFold(it) }

    private fun renderFold(f: Fold) {
        val open = f.body.visibility == View.VISIBLE
        val sb = android.text.SpannableStringBuilder()
        sb.append(f.title).append(if (open) "   ▴" else "   ▾")
        sb.setSpan(android.text.style.ForegroundColorSpan(Ui.TEXT), 0, sb.length, 0)
        val sum = summary(f.title)
        if (sum.isNotBlank()) {
            sb.append("\n"); val s0 = sb.length; sb.append(sum)
            sb.setSpan(android.text.style.ForegroundColorSpan(Ui.MUTED), s0, sb.length, 0)
            sb.setSpan(android.text.style.RelativeSizeSpan(1.12f), s0, sb.length, 0)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), s0, sb.length, 0)
        }
        f.head.text = sb
        f.head.letterSpacing = 0.04f
    }

    private fun summary(t: String): String {
        val f = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US)
        return when {
            t.startsWith("LIBRELINKUP") -> if (st.patientName.isNotBlank()) "Connected · following ${st.patientName}" else "Not connected"
            t.startsWith("ALERTS") -> "${st.urgentLow} / ${st.low} / ${st.high} / ${st.veryHigh} mg/dL · " + if (st.alertsOn) "on" else "off"
            t.startsWith("URGENT") -> if (!st.urgentOn) "Off" else "On · " + listOfNotNull(
                "urgent low".takeIf { st.urgUrgentLow }, "low".takeIf { st.urgLow }, "high".takeIf { st.urgHigh },
                "very high".takeIf { st.urgVeryHigh }, "no signal".takeIf { st.urgNoSignal }).joinToString(", ")
            t.startsWith("PROFILE") -> listOfNotNull("Age ${st.age}", st.gender.ifBlank { null },
                st.rapidName.ifBlank { null }, st.longName.ifBlank { null }).joinToString(" · ")
            t.startsWith("CARB") -> "Meals ${st.mealMin}–${st.mealMax} g · snacks ${st.snackMin}–${st.snackMax} g · low ${st.lowTreatG} g"
            t.startsWith("AI") -> st.current().label + " · " + st.model(st.current())
            t.startsWith("PARENTAL") -> if (Parent.enabled(st)) "PIN on" else "Off"
            t.startsWith("BACKUP") -> if (st.lastBackupTs > 0) "Last backup ${f.format(java.util.Date(st.lastBackupTs))}" else "No backup yet"
            t.startsWith("GOOGLE") -> if (!st.driveOn) "Not connected" else (when (st.driveFreq) { "3" -> "Every 3 days"; "w" -> "Every week"; else -> "Every day" } +
                if (st.driveLast > 0) " · last ${f.format(java.util.Date(st.driveLast))}" else "")
            t.startsWith("ABOUT") -> "Nova Glucose ${Ui.version(this)}"
            else -> ""
        }
    }

    private fun check(label: String, on: Boolean) = android.widget.CheckBox(this).apply {
        text = label; isChecked = on; setTextColor(Ui.TEXT); textSize = 15f
    }

    private fun renderVib() {
        vibRow.removeAllViews()
        listOf("off" to "Off", "normal" to "Normal", "strong" to "Strong").forEach { (id, label) ->
            vibRow.addView(Ui.chip(this, label, st.alarmVibrate == id) { st.alarmVibrate = id; renderVib() }, Ui.lp(-2, -2, end = Ui.dp(this, 8)))
        }
    }

    private fun pickRingtone() {
        val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Urgent alarm sound")
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        st.alarmSound.takeIf { it.isNotBlank() }?.let { i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
        try { startActivityForResult(i, REQ_RINGTONE) } catch (_: Exception) { Ui.toast(this, "No sound picker on this phone") }
    }

    private fun pickAudio() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQ_AUDIO)
    }

    private fun renderPerms() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        val full = android.os.Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        val dnd = android.os.Build.VERSION.SDK_INT < 23 || nm.isNotificationPolicyAccessGranted
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        val batt = android.os.Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(packageName)
        permStatus.text = "${mark(full)} Full-screen alarms   ${mark(dnd)} Do Not Disturb access\n${mark(exact)} Exact timers   ${mark(batt)} No battery limits"
        permStatus.setTextColor(if (full && dnd && exact && batt) Ui.GOOD else Ui.WARN)
    }

    private fun openFullScreenPerm() {
        if (android.os.Build.VERSION.SDK_INT >= 34) try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))); return
        } catch (_: Exception) { }
        Ui.toast(this, "Already allowed on this Android version")
    }

    private fun openDndPerm() {
        try { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        catch (_: Exception) { Ui.toast(this, "Open Settings › Notifications › Do Not Disturb › Apps") }
    }

    private fun openExactPerm() {
        if (android.os.Build.VERSION.SDK_INT >= 31) try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))); return
        } catch (_: Exception) { }
        Ui.toast(this, "Already allowed on this Android version")
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun openBatteryPerm() {
        try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    private fun brandTip(): String {
        val b = android.os.Build.MANUFACTURER.lowercase()
        return "Your phone (${android.os.Build.MANUFACTURER}): " + when {
            "xiaomi" in b || "redmi" in b || "poco" in b -> "Settings › Apps › Nova Glucose › turn on Autostart, Battery saver = No restrictions, and lock the app in recent apps."
            "huawei" in b || "honor" in b -> "Settings › Battery › App launch › Nova Glucose › Manage manually (all three on)."
            "oppo" in b || "realme" in b || "oneplus" in b -> "Settings › Battery › Nova Glucose › Allow background activity and auto-launch."
            "samsung" in b -> "Settings › Battery › Background usage limits › Never sleeping apps › add Nova Glucose."
            "vivo" in b -> "i Manager › App manager › Autostart › Nova Glucose on; Battery › High background power use on."
            else -> "Allow the app to run in the background without battery limits."
        } + " Make sure Alarms are allowed in Do Not Disturb."
    }

    private fun nameRow(e: EditText, target: String): View {
        val r = Ui.row(this)
        r.addView(e, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        r.addView(Ui.smallButton(this, "📷") { penPhoto(target) }, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 44)))
        return r
    }

    /** Photo of the insulin pen / box: the AI reads the name. */
    private fun penPhoto(target: String) {
        penTarget = target
        android.app.AlertDialog.Builder(this).setTitle("Insulin name from a photo")
            .setItems(arrayOf("Take photo", "Choose from gallery")) { _, w ->
                if (w == 0) {
                    val dir = java.io.File(cacheDir, "capture").apply { mkdirs() }
                    val f = java.io.File(dir, "pen_${System.currentTimeMillis()}.jpg"); penFile = f
                    val u = androidx.core.content.FileProvider.getUriForFile(this, "com.nova.glucose.files", f)
                    try {
                        startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, u)
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_PEN_CAM)
                    } catch (_: Exception) { Ui.toast(this, "No camera app") }
                } else startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PEN_GAL)
            }.show()
    }

    private fun identifyPen(raw: ByteArray) {
        save(false)
        val p = st.current()
        if (st.key(p).isBlank()) { penStatus.setTextColor(Ui.BAD); penStatus.text = "Add an AI key first to read the photo."; return }
        penStatus.setTextColor(Ui.MUTED); penStatus.text = "Reading the insulin name with ${p.label}…"
        Thread {
            val r = try {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
                val out = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                Result.success(Providers.ask(st, p, "You read medicine labels. Reply with only the product, nothing else.",
                    "Read the insulin product on this pen, vial or box. Reply only as: Brand (generic name), e.g. \"Tresiba (insulin degludec)\". " +
                        "If you can't read it, reply: unknown", listOf(out.toByteArray())).lines().first().trim().trim('"', '.'))
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { name ->
                    if (name.equals("unknown", true) || name.isBlank()) { penStatus.setTextColor(Ui.WARN); penStatus.text = "Couldn't read the name — type it instead." }
                    else {
                        (if (penTarget == "long") longNameE else rapidNameE).setText(name)
                        save(false); penStatus.setTextColor(Ui.GOOD); penStatus.text = "✓ Read from photo: $name — please check it's right."
                    }
                }.onFailure { penStatus.setTextColor(Ui.BAD); penStatus.text = it.message }
            }
        }.start()
    }

    private fun openNotifSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
