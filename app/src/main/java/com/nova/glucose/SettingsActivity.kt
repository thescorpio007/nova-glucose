package com.nova.glucose

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
        if (msg != null) Tr.Builder(this).setTitle("✓ Restored").setMessage(msg.replace(" · ", "\n"))
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
    private lateinit var famBox: LinearLayout
    private lateinit var soundRows: LinearLayout
    private lateinit var genRows: LinearLayout
    private var soundNote = ""
    private var soundKind = "urgent_low"
    private lateinit var famMsg: TextView
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
    private lateinit var basalBox: LinearLayout

    private fun renderBasal() {
        basalBox.removeAllViews()
        val l = BasalChanges.list(this).reversed()
        if (l.isEmpty()) basalBox.addView(Ui.text(this, "No dose change recorded yet.", 12.5f, Ui.MUTED))
        l.forEach { ch ->
            val r = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.round(Ui.CARD2, Ui.dp(context, 12).toFloat())
                setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8)) }
            r.addView(Ui.text(this, BasalChanges.line(ch), 14f, Ui.TEXT, true), Ui.lp(0, weight = 1f))
            r.addView(Ui.smallButton(this, "✏️") { editBasal(ch) }, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 36)))
            basalBox.addView(r, Ui.lp(top = Ui.dp(this, 6)))
        }
    }

    private fun editBasal(ch: BasalChanges.Chg?) {
        val cal = java.util.Calendar.getInstance().apply { if (ch != null) timeInMillis = ch.ts else { set(java.util.Calendar.HOUR_OF_DAY, 20); set(java.util.Calendar.MINUTE, 30) } }
        val df = java.text.SimpleDateFormat("EEE d MMM yyyy · HH:mm", L.loc())
        val root = Ui.col(this, 18)
        lateinit var dateB: TextView
        dateB = Ui.smallButton(this, df.format(cal.time)) {
            android.app.DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d)
                android.app.TimePickerDialog(this, { _, hh, mm -> cal.set(java.util.Calendar.HOUR_OF_DAY, hh); cal.set(java.util.Calendar.MINUTE, mm); dateB.text = df.format(cal.time) },
                    cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show() },
                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).apply { datePicker.maxDate = System.currentTimeMillis() }.show() }
        root.addView(Ui.text(this, "From (date and time of the first new dose)", 13f, Ui.MUTED)); root.addView(dateB, Ui.lp(top = Ui.dp(this, 4)))
        fun num(h: String, v: Double?) = Ui.input(this, h, multi = false).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            v?.takeIf { it > 0 }?.let { setText(Lantus.fmt(it)) } }
        val from = num("Old dose (u)", ch?.from ?: st.longDose.toDoubleOrNull()); val to = num("New dose (u)", ch?.to)
        val note = Ui.input(this, "Note (optional), e.g. doctor's advice", multi = false).apply { setText(ch?.note ?: "") }
        root.addView(Ui.text(this, "Old dose (u)", 13f, Ui.MUTED), Ui.lp(top = Ui.dp(this, 10))); root.addView(from)
        root.addView(Ui.text(this, "New dose (u)", 13f, Ui.MUTED), Ui.lp(top = Ui.dp(this, 10))); root.addView(to)
        root.addView(Ui.text(this, "Note", 13f, Ui.MUTED), Ui.lp(top = Ui.dp(this, 10))); root.addView(note)
        val b = Tr.Builder(this).setTitle(if (ch == null) "+ Add dose change" else "✏️ Dose change").setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
        if (ch != null) b.setNeutralButton("Delete") { _, _ -> BasalChanges.delete(this, ch); renderBasal() }
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val f = from.text.toString().replace(',', '.').toDoubleOrNull(); val t = to.text.toString().replace(',', '.').toDoubleOrNull()
                if (f == null || f <= 0) { from.error = L.s("Enter the units"); return@setOnClickListener }
                if (t == null || t <= 0 || t == f) { to.error = L.s("Enter the new units"); return@setOnClickListener }
                if (ch != null) BasalChanges.put(this, cal.timeInMillis, f, t, note.text.toString().trim(), ch.id)
                else BasalChanges.put(this, cal.timeInMillis, f, t, note.text.toString().trim())
                if (BasalChanges.list(this).lastOrNull()?.ts == cal.timeInMillis) { st.longDose = Lantus.fmt(t); longDoseE.setText(st.longDose) }   // latest change = usual dose
                FamilySync.kick(this); renderBasal(); dlg.dismiss()
            }
        }
        dlg.show()
    }
    private lateinit var longTimeE: EditText
    private lateinit var rapidNameE: EditText
    private lateinit var penStatus: TextView
    private var penTarget = ""
    private lateinit var heightE: EditText
    private lateinit var weightE: EditText
    private lateinit var bodyInfo: TextView
    private lateinit var bodyList: LinearLayout
    private lateinit var hcStatus: TextView
    private lateinit var hcTypes: LinearLayout
    private lateinit var hcMsg: TextView
    private lateinit var roleRow: LinearLayout
    private lateinit var roleInfo: TextView
    private lateinit var guardBox: LinearLayout
    private val patientOnly = mutableListOf<View>()
    private lateinit var hcFamilyNote: TextView
    private lateinit var hcPeerBox: LinearLayout
    private lateinit var hcPeerLine: TextView
    private lateinit var hcReqLine: TextView
    private val hcTick = android.os.Handler(android.os.Looper.getMainLooper())
    private var penFile: java.io.File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        // automatic test only: lock Settings with a test PIN to show what stays changeable
        if (intent?.getBooleanExtra("demolock", false) == true && st.pinHash.isBlank() && !st.loggedIn) { st.pinHash = Parent.hash("0000"); st.pinOn = true }
        if (intent?.getBooleanExtra("demounlock", false) == true && Parent.hash("0000") == st.pinHash && !st.loggedIn) { st.pinOn = false; st.pinHash = "" }
        build()
    }

    override fun onPause() { Sounds.stop(); save(false); FamilySync.kick(this); lockTick.removeCallbacksAndMessages(null); hcTick.removeCallbacksAndMessages(null); super.onPause() }

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
        lockBanner = Ui.text(this, "🔒 Locked by parent — view only. Non-urgent alert sounds and the watch reading can still be changed.", 14f, Ui.WARN).apply {
            background = Ui.round(0x22FFB347, Ui.dp(context, 10).toFloat())
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }
        root.addView(lockBanner, Ui.lp(top = Ui.dp(this, 8)))

        // ---------------------------------------------------------- This phone (patient or family)
        root.addView(Ui.section(this, "This phone"))
        val rc = Ui.card(this)
        rc.addView(Ui.text(this, "Who uses this phone?", 14f, Ui.TEXT, true))
        roleRow = Ui.row(this); rc.addView(roleRow, Ui.lp(top = Ui.dp(this, 8)))
        roleInfo = Ui.text(this, "", 13.5f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 10), 0, 0) }
        rc.addView(roleInfo)
        guardBox = Ui.col(this).apply { setPadding(0, Ui.dp(context, 10), 0, 0) }
        rc.addView(guardBox)
        root.addView(rc)
        renderRole()

        // ---------------------------------------------------------- LibreLinkUp
        root.addView(Ui.section(this, "LibreLinkUp connection"))
        val lc = Ui.card(this)
        lc.addView(Ui.text(this, "Use the LibreLinkUp follower login (the account that sees the patient's readings).", 13f, Ui.MUTED))
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
        soundTxt = Ui.text(this, "Alert sounds (this phone only · not shared · kept after updates)", 14f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 12), 0, 0) }
        uc.addView(soundTxt)
        soundRows = Ui.col(this); uc.addView(soundRows)
        uc.addView(Ui.text(this, "Urgent alarms ring with these sounds through silent and Do Not Disturb. Normal alerts play their sound once " +
            "(lows on the alarm volume; highs and no-signal at notification volume, not when the phone is silent). " +
            "\"File…\" always opens Android's file chooser, so you can pick any folder.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 4), 0, 0) })
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
        uc.addView(Ui.button(this, "Display over other apps") { openOverlayPerm() }, Ui.lp(top = Ui.dp(this, 6)))
        uc.addView(Ui.text(this, brandTip(), 12f, Ui.WARN).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        root.addView(uc)

        // ---------------------------------------------------------- Profile and insulin
        root.addView(Ui.section(this, "Profile and insulin (as prescribed — reference only)"))
        val pc = Ui.card(this)
        pc.addView(numRow("age", "Age", st.age))
        pc.addView(Ui.text(this, "Gender", 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4)) })
        genderRow = Ui.row(this); pc.addView(genderRow); renderGender()
        heightE = decRow(pc, "Height (cm)", st.heightCm)
        weightE = decRow(pc, "Weight (kg)", st.weightKg)
        bodyInfo = Ui.text(this, "", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, 0) }
        pc.addView(bodyInfo)
        pc.addView(check("Also take height and weight from Health Connect (if the patient's Huawei app records them)", st.hcBody)
            .apply { setOnCheckedChangeListener { _, v -> st.hcBody = v } }, Ui.lp(top = Ui.dp(this, 6)))
        val gh = Ui.col(this, 12).apply { background = Ui.round(Ui.CARD2, Ui.dp(context, 12).toFloat()) }
        gh.addView(Ui.text(this, "📈 Growth history", 14f, Ui.TEXT, true))
        bodyList = Ui.col(this); gh.addView(bodyList)
        pc.addView(gh, Ui.lp(top = Ui.dp(this, 8)))
        renderBody()
        pc.addView(Ui.text(this, "About the patient (brief, used by the AI — never for doses)", 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4)) })
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
        // 💉 night Lantus reminder: full-screen pop-up on every family phone, must be confirmed
        val lanRow = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        lanRow.addView(Switch(this).apply { text = L.s("💉 Night Lantus reminder"); isChecked = st.lantusOn; setTextColor(Ui.TEXT); textSize = 14f
            setOnCheckedChangeListener { _, v -> st.lantusOn = v } }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        lateinit var lanTime: TextView
        lanTime = Ui.smallButton(this, "🕘 ${st.lantusTime}") {
            val (h, m) = st.lantusTime.split(":").map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 20 }) to (it.getOrElse(1) { 30 }) }
            android.app.TimePickerDialog(this, { _, hh, mm -> st.lantusTime = "%02d:%02d".format(hh, mm); lanTime.text = "🕘 ${st.lantusTime}"
                st.raw.edit().putLong("lantus_next", 0L).apply() }, h, m, true).show() }
        lanRow.addView(lanTime, Ui.lp(-2, Ui.dp(this, 40)))
        pc.addView(lanRow, Ui.lp(top = Ui.dp(this, 10)))
        pc.addView(Ui.text(this, "Full-screen pop-up on every family phone with the usual dose (− / + to change). Repeats every 15 min until ✓ Injected; whoever confirms first counts.",
            11.5f, Ui.MUTED))
        // Lantus dose changes: reports draw a line and compare before → after (3, 7, 14 days)
        pc.addView(Ui.text(this, "Dose changes", 14f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 14), 0, Ui.dp(context, 4)) })
        basalBox = Ui.col(this); pc.addView(basalBox); renderBasal()
        pc.addView(Ui.button(this, "+ Add dose change", true) { editBasal(null) }, Ui.lp(top = Ui.dp(this, 6)))
        pc.addView(Ui.text(this, "Added automatically when the night pop-up confirms a new dose. Reports show a line at each change and compare before → after over 3, 7 and 14 days.", 11.5f, Ui.MUTED))
        pc.addView(Ui.text(this, "Rapid insulin", 14f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
        rapidNameE = Ui.input(this, "Name, e.g. NovoRapid", multi = false).apply { setText(st.rapidName) }
        pc.addView(nameRow(rapidNameE, "rapid"), Ui.lp(top = Ui.dp(this, 6)))
        pc.addView(numRow("carb_ratio", "Prescribed ratio: 1 unit per … g carbs", st.carbRatio))
        pc.addView(numRow("eat_wait", "Wait before eating after rapid (min)", st.eatWait))
        pc.addView(numRow("target_mg", "Target glucose (mg/dL)", st.targetMg))
        pc.addView(numRow("corr_factor", "Prescribed correction: 1 unit lowers … mg/dL", st.corrFactor))
        pc.addView(Ui.text(this, "Age, gender, height and weight go to the AI full picture, AI review and AI tips as background, and into the doctor report. " +
            "The AI never calculates doses and never comments on weight or body.", 12f, Ui.WARN).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        pc.addView(Ui.text(this, "Kept as reference in the logbook and daily report, and as background for the AI. " +
            "The app never calculates or suggests a dose.", 12f, Ui.WARN).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        penStatus = Ui.text(this, "", 12f, Ui.MUTED)
        pc.addView(penStatus)
        root.addView(pc)

        // ---------------------------------------------------------- Carb plan
        root.addView(Ui.section(this, "Carb plan (grams)"))
        val cc = Ui.card(this)
        cc.addView(Ui.text(this, "Standard guidance. Replace with the care team's numbers when you have them.", 13f, Ui.MUTED))
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

        // ---------------------------------------------------------- Notifications & watch
        root.addView(Ui.section(this, "Notifications and watch (this phone only)"))
        val wc = Ui.card(this)
        wc.addView(Ui.text(this, "Smart watches (e.g. HUAWEI WATCH GT 3) show Nova Glucose notifications when Nova Glucose is switched on in " +
            "the watch app (Huawei Health › your watch › Notifications). Choose what goes to the watch from this phone:", 13f, Ui.TEXT))
        fun wcb(label: String, on: Boolean, set: (Boolean) -> Unit) = check(label, on).apply { setOnCheckedChangeListener { _, v -> set(v) } }
        wc.addView(wcb("Urgent alarms (urgent low, low, very high…)", st.watchUrgent) { st.watchUrgent = it }, Ui.lp(top = Ui.dp(this, 6)))
        wc.addView(wcb("Low / high alerts", st.watchAlerts) { st.watchAlerts = it })
        wc.addView(wcb("No-signal alerts", st.watchStale) { st.watchStale = it })
        wc.addView(wcb("Time-to-eat reminder", st.watchEat) { st.watchEat = it })
        wc.addView(Ui.text(this, "Glucose on the watch (quiet message, no sound):", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4)) })
        val wg = Ui.col(this)
        fun renderWg() {
            wg.removeAllViews()
            listOf(listOf(0 to "Off", 5 to "Every 5 min", 10 to "10 min"), listOf(15 to "15 min", 30 to "30 min", 60 to "60 min")).forEach { line ->
                val r = Ui.row(this)
                line.forEach { (m, l) -> r.addView(Ui.chip(this, l, st.watchGlucose == m) { st.watchGlucose = m; st.lastWatchPost = 0; renderWg() }, Ui.lp(-2, -2, end = Ui.dp(this, 6))) }
                wg.addView(r, Ui.lp(top = Ui.dp(this, 4)))
            }
            wg.addView(Ui.text(this, "This phone only — not shared, not changed by updates.", 11f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 4), 0, 0) })
        }
        renderWg(); wc.addView(wg)
        wc.addView(check("🔆 Keep the screen on while Nova Glucose is open in front", st.keepScreen).apply {
            setOnCheckedChangeListener { _, v -> st.keepScreen = v } }, Ui.lp(top = Ui.dp(this, 10)))
        wc.addView(Ui.text(this, "The phone doesn't lock while the app is in front, like watching a video. Uses more battery.", 11.5f, Ui.MUTED))
        wc.addView(check("✦ AI food tip a few seconds after a high/low alert", st.aiTips).apply { setOnCheckedChangeListener { _, v -> st.aiTips = v } },
            Ui.lp(top = Ui.dp(this, 10)))
        wc.addView(check("Show the time-to-eat reminder on this phone", st.notifyEat).apply { setOnCheckedChangeListener { _, v -> st.notifyEat = v } },
            Ui.lp(top = Ui.dp(this, 10)))
        wc.addView(Ui.text(this, "Unticked items stay on this phone only. Some watch apps forward everything anyway — use the test to check. " +
            "The urgent alarm's sound plays on the phone; the watch vibrates and shows it.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        wc.addView(Ui.text(this, "Sounds of the other notifications (this phone)", 14f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 12), 0, 0) })
        genRows = Ui.col(this); wc.addView(genRows)
        wc.addView(Ui.button(this, "⌚ Send a test to the watch") { Watch.test(this); Ui.toast(this, "Test sent — look at the watch") }, Ui.lp(top = Ui.dp(this, 8)))
        root.addView(wc)

        // ---------------------------------------------------------- Huawei watch data
        root.addView(Ui.section(this, "Huawei watch data (Health Connect)"))
        val hc = Ui.card(this)
        hcStatus = Ui.text(this, "", 15f, Ui.GOOD, true)
        hc.addView(hcStatus)
        hc.addView(Ui.text(this, "Reads the patient's watch data from Health Connect every 15 minutes: Huawei Health → Health Sync → Health Connect → Nova Glucose. " +
            "The data is added to the Activity & sleep page, the AI review and the doctor report.", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6))
            patientOnly += this })
        hcFamilyNote = Ui.text(this, "This is a family phone: the watch is read on the patient's phone and shared with the family. Nothing to set up here.",
            13f, Ui.MUTED).apply { setPadding(0, 0, 0, Ui.dp(context, 6)) }
        hc.addView(hcFamilyNote)
        hcPeerBox = Ui.col(this)
        hcPeerLine = Ui.text(this, "", 13f, Ui.TEXT)
        hcPeerBox.addView(hcPeerLine)
        hcPeerBox.addView(Ui.button(this, "⟳ Ask the patient's phone to read the watch now", true) {
            if (st.syncFam.isBlank()) { Ui.toast(this, "Family sync is off"); return@button }
            WatchRequest.ask(this); renderHc(); watchRequest() }.apply { tag = "free" }, Ui.lp(top = Ui.dp(this, 8)))
        hcReqLine = Ui.text(this, "", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6)) }
        hcPeerBox.addView(hcReqLine)
        hc.addView(hcPeerBox)
        hcTypes = Ui.col(this); hc.addView(hcTypes)
        hc.addView(switch("Share with the family", st.hcShare).apply { setOnCheckedChangeListener { _, v -> st.hcShare = v; if (v) Db.get(context).queueAll() }
            patientOnly += this }, Ui.lp(top = Ui.dp(this, 8)))
        hc.addView(switch("Use in AI review, full picture and tips", st.hcAi).apply { setOnCheckedChangeListener { _, v -> st.hcAi = v } })
        hc.addView(switch("Add to the doctor report", st.hcReport).apply { setOnCheckedChangeListener { _, v -> st.hcReport = v } })
        val hb = Ui.row(this)
        hb.addView(Ui.button(this, "🔐 Connect / permissions") { HealthRead.ask(this) }.apply { tag = "free" }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
        hb.addView(Ui.button(this, "⟳ Read now", true) { readWatch() }.apply { tag = "free" }, Ui.lp(0, weight = 1f))
        hc.addView(hb, Ui.lp(top = Ui.dp(this, 8))); patientOnly += hb
        hcMsg = Ui.text(this, "", 13f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 6), 0, 0) }
        hc.addView(hcMsg)
        hc.addView(Ui.button(this, "⌚ Open Activity & sleep") { startActivity(Intent(this, HealthActivity::class.java)) }.apply { tag = "free" }, Ui.lp(top = Ui.dp(this, 8)))
        hc.addView(Ui.text(this, "Needs Huawei Health and Health Sync on the phone paired with the watch (Android 14 or newer), with Health Sync set to " +
            "write to Health Connect. On the other family phones this section only shows the shared data.", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 8), 0, 0)
            patientOnly += this })
        hc.addView(Ui.smallButton(this, "Health Connect settings") { HealthRead.openSettings(this) }.apply { tag = "free"; patientOnly += this }, Ui.lp(-2, Ui.dp(this, 36), top = Ui.dp(this, 8)))
        root.addView(hc)
        renderHc()

        // ---------------------------------------------------------- Family sync
        root.addView(Ui.section(this, "Family sync (all phones)"))
        val fc = Ui.card(this)
        famBox = Ui.col(this)
        fc.addView(famBox)
        famMsg = Ui.text(this, "", 13f, Ui.MUTED)
        fc.addView(famMsg, Ui.lp(top = Ui.dp(this, 6)))
        fc.addView(Ui.button(this, "📤 Share family update file (WhatsApp…)") { shareFamilyFile() }, Ui.lp(top = Ui.dp(this, 10)))
        fc.addView(Ui.text(this, "Shared between the phones: meals, insulin doses, earlier entries, incidents, deletions, the eat timer, AI corrections, " +
            "and the settings — LibreLinkUp login, AI keys and models, profile and insulin, carb plan, food notes. " +
            "Not shared: photos and videos, alerts, urgent alarm, watch and notification choices, folder, Google Drive and the PIN — each phone keeps its own. " +
            "Everything is encrypted with the family password before it leaves the phone. No internet? Send the update file and open it on the other phone: it adds what's missing.",
            12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        root.addView(fc)
        renderFamily()

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
        ab.addView(Ui.text(this, Updater.status(this).ifBlank { "Updates: checked once a day from GitHub (signature, SHA-256 and signing key verified before installing)." },
            12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 10), 0, 0) })
        ab.addView(Ui.button(this, "⬆ Check for updates") { startActivity(Intent(this, UpdateActivity::class.java)) }.apply { tag = "free" },
            Ui.lp(top = Ui.dp(this, 6)))
        ab.addView(Ui.text(this, "🐞 Error report", 14f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 2)) })
        ab.addView(Ui.text(this, L.s("Crashes and errors of the last 30 days, with the phone and settings info, to send for fixing and improving the app. No names, passwords, keys, family code, glucose values or meals.") +
            " (" + L.s("{0} recorded").replace("{0}", "${ErrorLog.count(this)}") + ")", 12f, Ui.MUTED))
        ab.addView(Ui.button(this, "📤 Share error report") { ErrorLog.share(this) }.apply { tag = "free" }, Ui.lp(top = Ui.dp(this, 6)))
        ab.addView(Ui.text(this, "Language", 14f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 4)) })
        val lr = Ui.row(this)
        L.LANGS.forEach { (code, nm) ->
            lr.addView(Ui.chip(this, nm, L.lang == code) { if (code != L.lang) { save(false); st.lang = code; L.set(this, code); recreate() } }.apply { tag = "free" },
                Ui.lp(-2, -2, end = Ui.dp(this, 8)))
        }
        ab.addView(lr)
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
        renderSounds()
        setupFolds()
        renderLock()
        val app = applicationContext
        Thread { val note = Sounds.repair(app); if (note.isNotBlank()) runOnUiThread { soundNote = note; renderSounds(); renderLock() } }.start()
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
        saveBody()
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
                        loginStatus.text = "Signed in, but this account doesn't follow anyone. Accept the patient's invitation in LibreLinkUp first."
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
        Tr.Builder(this).setTitle("Restore everything?")
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
            st.setSound(soundKind, u?.toString() ?: "", u?.let { android.media.RingtoneManager.getRingtone(this, it)?.getTitle(this) } ?: "Default alarm")
            renderSounds(); renderLock(); return
        }
        if (req == REQ_PEN_CAM) { penFile?.let { identifyPen(it.readBytes()) }; return }
        val uri = data?.data ?: return
        try {
            when (req) {
                REQ_AUDIO -> {
                    // Copied into the app and test-played (any format the phone can play).
                    val name = try { androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)?.name } catch (_: Exception) { null } ?: "Sound file"
                    chooseSound(uri, name)
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
        if (::soundRows.isInitialized) renderSounds()
        if (::hcStatus.isInitialized) renderHc()
        if (::guardBox.isInitialized) renderGuard()
        if (::allViews.isInitialized) renderLock()
        lockTick.post(watch)
    }

    // ================================================================ family sync
    private fun famNote(s: String, col: Int = Ui.MUTED) { famMsg.setTextColor(col); famMsg.text = s }

    private fun renderFamily() {
        famBox.removeAllViews()
        if (st.syncFam.isBlank()) {
            famBox.addView(Ui.text(this, "Not set up. One phone creates the family, the others join with the code and the same password.", 14f, Ui.TEXT))
            val name = Ui.input(this, "This phone's name (e.g. Dad's phone)", multi = false).apply { setText(st.syncName) }
            val pwE = Ui.input(this, "Family password (at least ${FamilySync.MIN_PW} characters)", multi = false).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            famBox.addView(name, Ui.lp(top = Ui.dp(this, 10)))
            famBox.addView(pwE, Ui.lp(top = Ui.dp(this, 8)))
            famBox.addView(Ui.button(this, "➕ Create a new family", true) {
                val n = name.text.toString().trim(); val p = pwE.text.toString()
                if (n.isBlank()) { famNote("Give this phone a name first.", Ui.BAD); return@button }
                if (p.length < FamilySync.MIN_PW) { famNote("The family password needs at least ${FamilySync.MIN_PW} characters.", Ui.BAD); return@button }
                famWork("Creating the family…", showCode = true) { FamilySync.create(it, p, n) }
            }, Ui.lp(top = Ui.dp(this, 10)))
            famBox.addView(Ui.text(this, "— or join the family —", 12f, Ui.MUTED).apply {
                gravity = android.view.Gravity.CENTER; setPadding(0, Ui.dp(context, 12), 0, 0) })
            val code = Ui.input(this, "Family code (e.g. ABCD-EFGH-JK23)", multi = false).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
            famBox.addView(code, Ui.lp(top = Ui.dp(this, 8)))
            famBox.addView(Ui.button(this, "🔗 Join the family") {
                val n = name.text.toString().trim(); val p = pwE.text.toString(); val k = code.text.toString()
                if (n.isBlank()) { famNote("Give this phone a name first.", Ui.BAD); return@button }
                if (FamilySync.norm(k).length != 12) { famNote("Type the 12-character family code.", Ui.BAD); return@button }
                if (p.isBlank()) { famNote("Type the family password.", Ui.BAD); return@button }
                famWork("Joining…") { FamilySync.join(it, k, p, n) }
            }, Ui.lp(top = Ui.dp(this, 8)))
        } else {
            val f = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US)
            famBox.addView(Ui.text(this, "✓ Family sync on · this phone: ${st.syncName}", 14f, Ui.GOOD, true))
            famBox.addView(Ui.text(this, "Family code:  ${st.syncCode}", 16f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 8), 0, 0); setTextIsSelectable(true) })
            val cr = Ui.row(this)
            cr.addView(Ui.button(this, "Copy code") { Ui.copy(this, st.syncCode) }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
            cr.addView(Ui.button(this, "Send code") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "Nova Glucose family code: ${st.syncCode}\nOpen Nova Glucose › Settings › Family sync › Join, and type the family password (I'll tell you)."), "Send the family code"))
            }, Ui.lp(0, weight = 1f))
            famBox.addView(cr, Ui.lp(top = Ui.dp(this, 8)))
            famBox.addView(Ui.text(this, "Send the code by message, but say the password out loud — never send them together.", 12f, Ui.WARN)
                .apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
            famBox.addView(Ui.text(this, (if (st.syncLast > 0) "Last sync ${f.format(java.util.Date(st.syncLast))}  " else "") + st.syncInfo, 13f, Ui.MUTED)
                .apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
            val members = Ui.col(this).apply { setPadding(0, Ui.dp(context, 6), 0, 0) }
            members.addView(Ui.text(this, "Phones: loading…", 13f, Ui.TEXT))
            famBox.addView(members)
            renderMembers(members)
            famBox.addView(Switch(this).apply {
                text = "This phone uses its own LibreLinkUp login (don't share it)"; isChecked = st.syncOwnLibre
                setTextColor(Ui.TEXT); textSize = 14f; setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 4))
                setOnCheckedChangeListener { _, on -> st.syncOwnLibre = on }
            })
            famBox.addView(Switch(this).apply {
                text = "This phone uses its own AI key and model (don't share)"; isChecked = st.syncOwnAi
                setTextColor(Ui.TEXT); textSize = 14f; setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 4))
                setOnCheckedChangeListener { _, on -> st.syncOwnAi = on }
            })
            famBox.addView(Ui.text(this, "Tip: one LibreLinkUp login on several phones can be signed out or locked by Abbott. If that happens, " +
                "turn this on and use a separate follower invitation from the patient's LibreLink app for this phone.", 12f, Ui.MUTED))
            val sr = Ui.row(this)
            sr.addView(Ui.button(this, "🔄 Sync now", true) { famWork("Syncing…") { FamilySync.run(it) } }.apply { tag = "free" }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 8)))
            sr.addView(Ui.button(this, "Leave") {
                Tr.Builder(this).setTitle("Leave the family?")
                    .setMessage("This phone stops syncing. Everything already on it stays here, and the other phones keep their data.")
                    .setPositiveButton("Leave") { _, _ -> famWork("Leaving…") { FamilySync.leave(it); "Left the family" } }
                    .setNegativeButton("Cancel", null).show()
            }, Ui.lp(0, weight = 1f))
            famBox.addView(sr, Ui.lp(top = Ui.dp(this, 10)))
        }
        if (::allViews.isInitialized) renderLock()
    }

    /** Runs a family-sync action in the background, then refreshes the section. */
    /** Phones in the family, with ✏️ rename and 🗑 remove (shared with all phones; locked on the patient's phone until the PIN). */
    private fun renderMembers(box: LinearLayout) {
        val app = applicationContext
        Thread {
            val res = try { Result.success(FamilySync.memberDocs(app)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                box.removeAllViews()
                res.onFailure { box.addView(Ui.text(this, L.s("Phones: couldn't load") + " (${it.message})", 13f, Ui.TEXT)) }
                res.onSuccess { list ->
                    val now = System.currentTimeMillis()
                    val (rows, hidden) = Members.rows(this, list)
                    box.addView(Ui.text(this, "Phones in the family:", 13f, Ui.TEXT, true))
                    rows.forEach { r ->
                        val m = r.m
                        val ago = if (m.seen <= 0) "" else ((now - m.seen) / 60_000).let { x -> when {
                            x < 2 -> L.s(" · active now"); x < 60 -> L.s(" · seen $x min ago"); x < 48 * 60 -> L.s(" · seen ${x / 60} h ago"); else -> L.s(" · seen ${x / 1440} days ago") } }
                        val line = Ui.row(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 2)) }
                        val label = (if (r.locked) "🔒 " else "•  ") + r.name + (if (r.patient) " · " + L.s("patient's phone") else "") +
                            (if (m.me) " " + L.s("(this phone)") else "") + ago
                        line.addView(Ui.text(this, label, 13f, if (m.seen > 0 && now - m.seen > Members.INACTIVE_MS) Ui.MUTED else Ui.TEXT), Ui.lp(0, weight = 1f))
                        line.addView(Ui.smallButton(this, "✏️") { renameMember(r, box) }, Ui.lp(Ui.dp(this, 44), Ui.dp(this, 34), end = Ui.dp(this, 4)))
                        if (!m.me) line.addView(Ui.smallButton(this, "🗑") { removeMember(r, box) }.apply { alpha = if (r.canRemove) 1f else 0.4f },
                            Ui.lp(Ui.dp(this, 44), Ui.dp(this, 34), end = Ui.dp(this, 4)))
                        box.addView(line)
                    }
                    if (hidden > 0) box.addView(Ui.text(this, L.s("{0} removed phone(s) hidden — they come back if they sync again.").replace("{0}", "$hidden"), 12f, Ui.MUTED))
                    box.addView(Ui.text(this, "✏️ rename · 🗑 remove a phone not seen for 7 days (old install, formatted phone). The patient's phone 🔒 and active phones can't be removed.",
                        12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 4), 0, 0) })
                }
                if (::allViews.isInitialized && uiLocked) renderLock()
            }
        }.start()
    }

    private fun renameMember(r: Members.Row, box: LinearLayout) {
        val e = Ui.input(this, r.m.name, multi = false).apply { setText(r.name) }
        Tr.Builder(this).setTitle("✏️ Rename this phone in the list").setView(Ui.col(this, 18).apply {
            addView(e); addView(Ui.text(context, "Shown on all family phones. Leave empty to use the phone's own name.", 12f, Ui.MUTED)) })
            .setPositiveButton("Save") { _, _ -> Members.edit(this, r.m.uid, name = e.text.toString().trim().takeIf { it != r.m.name } ?: ""); renderMembers(box) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun removeMember(r: Members.Row, box: LinearLayout) {
        if (!r.canRemove) { Ui.toast(this, L.s(r.why) + if (r.why.startsWith("Active")) " — " + L.s("can be removed after 7 days without syncing") else ""); return }
        Tr.Builder(this).setTitle("🗑 Remove \"${r.name}\" from the list?")
            .setMessage("It disappears from the list on all family phones. Its data stays. If this phone syncs again, it comes back by itself.")
            .setPositiveButton("Remove") { _, _ -> Members.edit(this, r.m.uid, remove = true); renderMembers(box) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun famWork(busy: String, showCode: Boolean = false, job: (android.content.Context) -> String) {
        famNote(busy)
        val app = applicationContext
        Thread {
            val r = try { Result.success(job(app)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                renderFamily(); renderFolds()
                r.onSuccess { msg ->
                    if (!showCode) famNote(msg, if (msg.startsWith("⚠")) Ui.WARN else Ui.GOOD)
                    else {
                        famNote("✓ Family created", Ui.GOOD)
                        Tr.Builder(this).setTitle("👨‍👩‍👧 Family created")
                            .setMessage("Family code:\n\n$msg\n\nOn each other phone: Settings › Family sync › type a phone name, this code and the same family password › Join.")
                            .setPositiveButton("Copy code") { _, _ -> Ui.copy(this, msg) }.setNegativeButton("OK", null).show()
                    }
                }.onFailure { famNote(it.message ?: "Failed", Ui.BAD) }
            }
        }.start()
    }

    private fun shareFamilyFile() {
        val pwd = st.syncPw.ifBlank { st.backupPw }
        if (pwd.isBlank()) { famNote("Set up the family or a backup password first (the file is encrypted with it).", Ui.BAD); return }
        famNote("Preparing the update file…")
        val app = applicationContext
        Thread {
            val r = try {
                val bytes = FamilySync.exportFile(app, pwd)
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())
                val who = st.syncName.ifBlank { st.fileName }.replace(Regex("[^A-Za-z0-9]"), "")
                val dir = java.io.File(cacheDir, "capture").apply { mkdirs() }
                dir.listFiles()?.filter { it.name.endsWith(Vault.EXT) }?.forEach { it.delete() }
                Result.success(java.io.File(dir, "NovaGlucose-family-$who-$stamp${Vault.EXT}").apply { writeBytes(bytes) })
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                r.onSuccess { f ->
                    famNote("✓ Update file ready (encrypted with the ${if (st.syncPw.isNotBlank()) "family" else "backup"} password)", Ui.GOOD)
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.nova.glucose.files", f)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(Backup.MIME)
                        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send the family update"))
                }.onFailure { famNote(it.message ?: "Failed", Ui.BAD) }
            }
        }.start()
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
        Tr.Builder(this).setTitle("Restore from Google Drive?")
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
            val f = Fold(v, kids[k + 1], (v.contentDescription ?: v.text).toString().uppercase())
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
        sb.append(L.s((f.head.contentDescription ?: f.title).toString()).uppercase()).append(if (open) "   ▴" else "   ▾")
        sb.setSpan(android.text.style.ForegroundColorSpan(Ui.TEXT), 0, sb.length, 0)
        val sum = L.s(summary(f.title))
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
            t.startsWith("HUAWEI") -> hcSummary()
            t.startsWith("THIS PHONE") -> st.roleName() + if (st.syncName.isNotBlank() && st.syncName != st.roleName()) " · ${st.syncName}" else ""
            t.startsWith("PROFILE") -> listOfNotNull("Age ${st.age}", st.gender.ifBlank { null },
                st.heightCm.takeIf { it > 0 }?.let { "${fmtD(it)} cm" }, st.weightKg.takeIf { it > 0 }?.let { "${fmtD(it)} kg" },
                st.rapidName.ifBlank { null }, st.longName.ifBlank { null }).joinToString(" · ")
            t.startsWith("CARB") -> "Meals ${st.mealMin}–${st.mealMax} g · snacks ${st.snackMin}–${st.snackMax} g · low ${st.lowTreatG} g"
            t.startsWith("AI") -> st.current().label + " · " + st.model(st.current())
            t.startsWith("PARENTAL") -> if (Parent.enabled(st)) "PIN on" else "Off"
            t.startsWith("NOTIFICATIONS") -> listOfNotNull("urgent".takeIf { st.watchUrgent }, "alerts".takeIf { st.watchAlerts },
                "no signal".takeIf { st.watchStale }, "eat".takeIf { st.watchEat }).let { if (it.isEmpty()) "Watch: nothing" else "Watch: " + it.joinToString(", ") } +
                if (st.watchGlucose > 0) " · glucose every ${st.watchGlucose} min" else ""
            t.startsWith("FAMILY") -> if (st.syncFam.isBlank()) "Not set up" else "${st.syncName.ifBlank { "This phone" }} · " +
                (if (st.syncLast > 0) "synced ${f.format(java.util.Date(st.syncLast))}" else "waiting to sync")
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

    private fun renderSounds() {
        soundRows.removeAllViews()
        if (soundNote.isNotBlank()) soundRows.addView(Ui.text(this, soundNote, 12f, Ui.WARN).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        Sounds.KINDS.forEach { k -> soundRows.addView(soundBox(k), Ui.lp(top = Ui.dp(this, 8))) }
        if (::genRows.isInitialized) { genRows.removeAllViews()
            Sounds.volumeNote(this).takeIf { it.isNotBlank() }?.let { genRows.addView(Ui.text(this, "⚠ " + L.s(it), 12f, Ui.WARN).apply { setPadding(0, Ui.dp(context, 6), 0, 0) }) }
            Sounds.GENERAL.forEach { k -> genRows.addView(soundBox(k), Ui.lp(top = Ui.dp(this, 8))) } }
        if (::allViews.isInitialized && uiLocked) renderLock()
    }

    private fun soundBox(k: String): View {
        val box = Ui.col(this).apply {
            background = Ui.round(Ui.CARD2, Ui.dp(context, 12).toFloat())
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }
        val urgent = k in Sounds.KINDS && st.urgentFor(k)
        // non-urgent lines stay editable when Settings is locked (sound incl. Silent, vibrate, pop-up)
        if (Sounds.patientMay(st, k)) box.tag = "free"
        val h = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        h.addView(android.widget.CheckBox(this).apply { isChecked = st.onFor(k); setOnCheckedChangeListener { _, v -> st.setOn(k, v)
            if (!v) Ui.toast(this@SettingsActivity, L.s(if (k == "urgent_low") "Off on this phone — urgent low is switched back on at each update (safety)" else "Off on this phone only (not shared)")) } })
        h.addView(Ui.text(this, Sounds.label(st, k), 14f, Ui.TEXT, true), Ui.lp(0, weight = 1f))
        if (urgent) h.addView(Ui.text(this, "URGENT", 10f, 0xFFFF8A8D.toInt(), true))
        box.addView(h)
        val r1 = Ui.row(this)
        lateinit var dd: TextView
        dd = Ui.smallButton(this, "🔔 ${Sounds.displayName(st, k)}  ▾") { soundMenu(k, dd) }
        r1.addView(dd, Ui.lp(0, Ui.dp(this, 40), weight = 1f, end = Ui.dp(this, 6)))
        r1.addView(Ui.smallButton(this, "▶ Test") {
            when (Sounds.test(this, k)) {
                "default" -> Ui.toast(this, "The chosen sound can't play — playing the phone default")
                "beep" -> Ui.toast(this, "No sound file plays — built-in beep")
                "silent" -> Ui.toast(this, "This line is set to Silent")
                "chosen" -> Ui.toast(this, L.s("Playing") + " " + Sounds.displayName(st, k) + "…")
                else -> {}
            }
        }.apply { tag = "free" }, Ui.lp(Ui.dp(this, 84), Ui.dp(this, 40)))
        box.addView(r1, Ui.lp(top = Ui.dp(this, 8)))
        val r2 = Ui.row(this)
        r2.addView(Switch(this).apply {
            text = "Vibrate"; isChecked = st.vibFor(k); setTextColor(Ui.TEXT); textSize = 13f
            setOnCheckedChangeListener { _, v -> st.setVib(k, v) }
        }, Ui.lp(0, weight = 1f, end = Ui.dp(this, 12)))
        r2.addView(Switch(this).apply {
            text = "Pop up"; isChecked = st.popFor(k); setTextColor(Ui.TEXT); textSize = 13f
            setOnCheckedChangeListener { _, v -> st.setPop(k, v) }
        }, Ui.lp(0, weight = 1f))
        box.addView(r2, Ui.lp(top = Ui.dp(this, 4)))
        if (Sounds.patientMay(st, k) && Parent.enabled(st)) box.addView(Ui.text(this, "🔓 Can be changed even when Settings is locked.", 11f, Ui.MUTED))
        if (urgent) box.addView(Ui.text(this, "Urgent: full screen, rings through vibrate, silent and night mode until Stop. The family phones ring too.", 11f, Ui.MUTED))
        if (k == "eat") box.addView(Ui.text(this, "Plays once at the alarm volume, even when the phone is on silent.", 11f, Ui.MUTED))
        return box
    }

    /** Sound menu for one line: built-in sounds, the phone's sounds, a file, Silent (where allowed). */
    private fun soundMenu(k: String, anchor: View) {
        val pm = android.widget.PopupMenu(this, anchor)
        val cur = Sounds.current(st, k)
        Sounds.BUILTIN.forEachIndexed { i, (v, n) -> pm.menu.add(0, i, i, (if (v == cur) "✓ " else "    ") + n) }
        pm.menu.add(0, 10, 10, "    " + L.s("Phone sounds…"))
        pm.menu.add(0, 11, 11, "    " + L.s("File…"))
        pm.menu.add(0, 12, 12, (if (cur == Sounds.SILENT) "✓ " else "    ") + L.s(if (Sounds.silentAllowed(st, k)) "Silent" else "Silent — not allowed here")).isEnabled = Sounds.silentAllowed(st, k)
        pm.setOnMenuItemClickListener { item ->
            soundKind = k
            when (item.itemId) {
                in Sounds.BUILTIN.indices -> { Sounds.setBuiltin(this, k, Sounds.BUILTIN[item.itemId].first); renderSounds(); renderLock(); Sounds.test(this, k) }
                10 -> phoneSounds()
                11 -> pickAudio()
                12 -> { Sounds.setSilent(this, k); renderSounds(); renderLock() }
            }
            true
        }
        pm.show()
    }

    /** The phone's own sounds, listed by the app (works on every phone, nothing can lock it). */
    private fun phoneSounds() {
        val list = Sounds.phoneSounds(this)
        if (list.isEmpty()) { Ui.toast(this, "No sound picker on this phone"); return }
        val col = Ui.col(this, 12)
        var dlg: android.app.AlertDialog? = null
        var group = ""
        list.forEach { (g, title, uri) ->
            if (g != group) { group = g; col.addView(Ui.text(this, g, 12f, Ui.ACCENT, true).apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 12), 0, Ui.dp(context, 4)) }) }
            val r = Ui.row(this).apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 6)) }
            r.addView(Ui.text(this, title, 15f, Ui.TEXT).apply {
                setOnClickListener {
                    Sounds.stop()
                    dlg?.dismiss()
                    chooseSound(uri, title)
                }
            }, Ui.lp(0, Ui.dp(this, 40), weight = 1f).apply { }.also { })
            r.addView(Ui.smallButton(this, "▶") { Sounds.preview(this, uri) }, Ui.lp(Ui.dp(this, 48), Ui.dp(this, 36)))
            col.addView(r)
        }
        dlg = Tr.Builder(this).setTitle(Sounds.label(st, soundKind)).setView(ScrollView(this).apply { addView(col) })
            .setNegativeButton("Cancel") { _, _ -> Sounds.stop() }.setOnDismissListener { Sounds.stop() }.show()
    }

    private fun chooseSound(uri: Uri, name: String) {
        val k = soundKind; val app = applicationContext
        Ui.toast(this, "Checking the sound…")
        Thread {
            val err = Sounds.use(app, k, uri, name)
            runOnUiThread { if (err != null) Ui.toast(this, err) else Ui.toast(this, "Saved"); soundNote = ""; renderSounds(); renderLock() }
        }.start()
    }

    private fun pickRingtone() {
        val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_RINGTONE)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Sound: " + Sounds.label(st, soundKind))
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        st.soundFor(soundKind).takeIf { it.isNotBlank() && !it.startsWith("file:") }?.let { i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
        try { startActivityForResult(i, REQ_RINGTONE) } catch (_: Exception) { Ui.toast(this, "No sound picker on this phone") }
    }

    private fun pickAudio() {
        // A chooser every time: Android can't lock it to one app ("Always"), and every folder is reachable.
        val get = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/ogg", "audio/x-wav", "audio/wav", "audio/amr", "audio/flac",
                "audio/mp4", "video/mp4", "application/octet-stream"))
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        try {
            startActivityForResult(Intent.createChooser(get, "Choose the sound for " + Sounds.label(st, soundKind))
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(open)), REQ_AUDIO)
        } catch (_: Exception) { startActivityForResult(open, REQ_AUDIO) }
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
        val over = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)
        permStatus.text = "${mark(full)} Full-screen alarms   ${mark(dnd)} Do Not Disturb access\n${mark(exact)} Exact timers   ${mark(batt)} No battery limits\n${mark(over)} " + L.s("Display over other apps")
        permStatus.setTextColor(if (full && dnd && exact && batt && over) Ui.GOOD else Ui.WARN)
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

    private fun openOverlayPerm() {
        try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
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
        Tr.Builder(this).setTitle("Insulin name from a photo")
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

    // ================================================================ height & weight
    private fun fmtD(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(java.util.Locale.US, v)

    private fun decRow(parent: LinearLayout, label: String, v: Double): EditText {
        val r = Ui.row(this)
        r.addView(Ui.text(this, label, 14f, Ui.TEXT), Ui.lp(0, weight = 1f))
        val e = Ui.input(this, "", multi = false).apply {
            setText(if (v > 0) fmtD(v) else ""); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = android.view.Gravity.CENTER
        }
        r.addView(e, Ui.lp(Ui.dp(this, 84), -2))
        r.setPadding(0, Ui.dp(this, 8), 0, 0)
        parent.addView(r)
        return e
    }

    /** A changed height or weight becomes a new dated entry of the growth history (shared with the family). */
    private fun saveBody() {
        if (!::heightE.isInitialized) return
        val h = heightE.text.toString().replace(',', '.').trim().toDoubleOrNull()?.takeIf { it in 40.0..230.0 } ?: 0.0
        val w = weightE.text.toString().replace(',', '.').trim().toDoubleOrNull()?.takeIf { it in 5.0..250.0 } ?: 0.0
        if (h == 0.0 && w == 0.0) return
        val hNew = if (h > 0) h else st.heightCm; val wNew = if (w > 0) w else st.weightKg
        if (kotlin.math.abs(hNew - st.heightCm) < 0.05 && kotlin.math.abs(wNew - st.weightKg) < 0.05) return
        Db.get(this).addBody(System.currentTimeMillis(), hNew, wNew, "manual")
        renderBody()
    }

    private fun renderBody() {
        if (!::bodyList.isInitialized) return
        val f = java.text.SimpleDateFormat("EEE d MMM yyyy", L.loc())
        bodyInfo.text = if (st.bodyTs > 0) L.s("Last updated {0} · saved with the date each time it changes").replace("{0}", f.format(java.util.Date(st.bodyTs)))
            else L.s("Enter the patient's height and weight; each change is saved with its date.")
        bodyList.removeAllViews()
        val l = Db.get(this).bodies()
        if (l.isEmpty()) { bodyList.addView(Ui.text(this, "No entries yet.", 12f, Ui.MUTED)); return }
        val df = java.text.SimpleDateFormat("d MMM yyyy", L.loc())
        l.take(12).forEach { b ->
            val r = Ui.row(this).apply { setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6)) }
            r.addView(Ui.text(this, df.format(java.util.Date(b.ts)), 13f, Ui.TEXT), Ui.lp(0, weight = 1.3f))
            r.addView(Ui.text(this, if (b.height > 0) "${fmtD(b.height)} cm" else "–", 13f, Ui.TEXT), Ui.lp(0, weight = 1f))
            r.addView(Ui.text(this, if (b.weight > 0) "${fmtD(b.weight)} kg" else "–", 13f, Ui.TEXT), Ui.lp(0, weight = 1f))
            r.addView(Ui.text(this, if (b.src == "watch") "Health Connect" else b.who.ifBlank { "" }, 11f, Ui.MUTED), Ui.lp(0, weight = 1.2f))
            r.setOnLongClickListener {
                Tr.Builder(this).setTitle("Delete this entry?").setMessage(df.format(java.util.Date(b.ts)))
                    .setPositiveButton("Delete") { _, _ ->
                        Db.get(this).deleteBody(b.id); heightE.setText(if (st.heightCm > 0) fmtD(st.heightCm) else ""); weightE.setText(if (st.weightKg > 0) fmtD(st.weightKg) else ""); renderBody() }
                    .setNegativeButton("Cancel", null).show(); true
            }
            bodyList.addView(r)
        }
        bodyList.addView(Ui.text(this, "Touch and hold an entry to delete it.", 11f, Ui.MUTED))
    }

    // ================================================================ Huawei watch data
    private fun hcSummary(): String = when {
        st.isFamily -> if (WatchData.hasAny(this)) "Shared by the patient's phone" + if (st.hcLastData > 0) " · last data " +
            java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date(st.hcLastData)) else "" else "Family phone: nothing to set up"
        st.hcOn && HealthRead.granted(this).isNotEmpty() -> "Connected" + if (st.hcLastData > 0) " · last data " +
            java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date(st.hcLastData)) else ""
        WatchData.hasAny(this) -> "Shared by the family" + if (st.hcLastData > 0) " · last data " +
            java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(java.util.Date(st.hcLastData)) else ""
        else -> "Not connected"
    }

    private fun renderHc() {
        val g = HealthRead.granted(this)
        val f = java.text.SimpleDateFormat("d MMM HH:mm", L.loc())
        hcStatus.setTextColor(if (g.isNotEmpty() || WatchData.hasAny(this)) Ui.GOOD else Ui.MUTED)
        patientOnly.forEach { it.visibility = if (st.isFamily) View.GONE else View.VISIBLE }
        hcFamilyNote.visibility = if (st.isFamily) View.VISIBLE else View.GONE
        hcPeerBox.visibility = if (st.isFamily) View.VISIBLE else View.GONE
        hcPeerLine.text = WatchRequest.peerLine(this)
        hcReqLine.text = WatchRequest.requestLine(this)
        hcStatus.text = when {
            st.isFamily -> if (WatchData.hasAny(this)) L.s("Shared by the patient's phone") + lastData(f) else L.s("Waiting for the patient's phone to share the watch data")
            !HealthRead.supported() && WatchData.hasAny(this) -> L.s("Shared by the family") + lastData(f)
            !HealthRead.supported() -> L.s("This phone can't read the watch (needs Android 14 or newer). It shows the data shared by the family.")
            g.isEmpty() -> L.s("Not connected — tap Connect on the patient's phone")
            else -> L.s("Connected") + lastData(f) + (st.hcSources.takeIf { it.isNotBlank() }?.let { " · " + L.s("from") + " " + it } ?: "")
        }
        hcTypes.removeAllViews()
        val got = st.hcGot.split(',').toSet() + if (st.isFamily || !HealthRead.supported()) WatchData.typesSeen(this) else emptySet()
        listOf("steps" to "Steps", "ex" to "Exercise sessions", "sleep" to "Sleep and stages", "hr" to "Heart rate",
            "rhr" to "Resting heart rate", "spo2" to "SpO2 (if Health Sync sends it)").forEach { (k, l) ->
            val r = Ui.row(this).apply { setPadding(0, Ui.dp(context, 5), 0, Ui.dp(context, 5)) }
            val ok = k in got
            r.addView(Ui.text(this, if (ok) "✓" else "·", 15f, if (ok) Ui.GOOD else Ui.MUTED, true), Ui.lp(Ui.dp(this, 24), -2))
            r.addView(Ui.text(this, l, 14f, Ui.TEXT), Ui.lp(0, weight = 1f))
            r.addView(Ui.text(this, if (ok) "received" else "not yet", 12f, if (ok) Ui.GOOD else Ui.MUTED))
            hcTypes.addView(r)
        }
        val r = Ui.row(this).apply { setPadding(0, Ui.dp(context, 5), 0, Ui.dp(context, 5)); alpha = 0.6f }
        r.addView(Ui.text(this, "–", 15f, Ui.MUTED, true), Ui.lp(Ui.dp(this, 24), -2))
        r.addView(Ui.text(this, "Stress", 14f, Ui.TEXT), Ui.lp(0, weight = 1f))
        r.addView(Ui.text(this, "not in Health Connect", 12f, Ui.MUTED))
        hcTypes.addView(r)
        if (st.hcInfo.isNotBlank() && hcMsg.text.isNullOrBlank()) hcMsg.text = st.hcInfo
    }

    private fun lastData(f: java.text.SimpleDateFormat) = if (st.hcLastData > 0) " · " + L.s("last data") + " " + f.format(java.util.Date(st.hcLastData)) else ""

    private fun readWatch() {
        if (!HealthRead.supported()) { hcMsg.setTextColor(Ui.WARN); hcMsg.text = L.s("Reading the watch needs Android 14 or newer"); return }
        if (HealthRead.granted(this).isEmpty()) { HealthRead.ask(this); return }
        st.hcOn = true
        hcMsg.setTextColor(Ui.MUTED); hcMsg.text = L.s("Reading Health Connect…")
        val app = applicationContext
        Thread {
            val r = try { HealthRead.sync(app, if (st.hcLastRead == 0L) 30 else 7) } catch (e: Exception) { "⚠ " + (e.message ?: "Failed") }
            runOnUiThread { hcMsg.setTextColor(if (r.startsWith("✓")) Ui.GOOD else Ui.WARN); hcMsg.text = L.s(r); renderHc(); renderBody(); renderFolds() }
        }.start()
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req != HealthRead.REQ) return
        if (HealthRead.granted(this).isEmpty()) {
            hcMsg.setTextColor(Ui.WARN)
            hcMsg.text = L.s("No permission given. Open Health Connect settings and allow Nova Glucose to read the data.")
            renderHc(); return
        }
        st.hcOn = true
        readWatch()
    }

    // ================================================================ this phone: patient or family
    private fun renderRole() {
        roleRow.removeAllViews()
        listOf("patient" to "🧑 Patient", "family" to "👨‍👩‍👧 Family").forEach { (id, l) ->
            roleRow.addView(Ui.chip(this, l, st.role == id) { chooseRole(id) }.apply { tag = "free"; textSize = 15f },
                Ui.lp(0, -2, weight = 1f, end = if (id == "patient") Ui.dp(this, 8) else 0))
        }
        roleInfo.text = when (st.role) {
            "patient" -> L.s("✓ Reads the patient's watch through Health Connect") + "\n" + L.s("✓ Ask AI, AI tips and AI review talk to the patient directly, in simple words") +
                "\n" + L.s("✓ Settings can be locked with the parent PIN") + "\n" + L.s("• Alerts are the same on every phone") + "\n" +
                L.s("Changing this on the patient's phone needs the parent PIN.")
            "family" -> L.s("✓ Watch data comes from the patient's phone (nothing to set up here)") + "\n" +
                L.s("✓ Ask AI, AI tips and AI review talk to you about the patient") + "\n" + L.s("• Alerts are the same on every phone")
            else -> L.s("Choose who uses this phone. Everything is set up for that person.")
        }
        renderGuard()
    }

    /** Patient's phone: protection against uninstalling (device administrator). Family phones: the 2-hour warning. */
    private fun renderGuard() {
        guardBox.removeAllViews()
        if (st.isPatient) {
            val on = Guard.protected(this)
            guardBox.addView(Ui.text(this, if (on) "🛡 Protected: Nova Glucose can't be uninstalled until this protection is turned off first."
                else "🛡 Not protected: Nova Glucose can be uninstalled.", 13f, if (on) Ui.GOOD else Ui.WARN))
            guardBox.addView(Ui.button(this, if (on) "Protection settings" else "🛡 Protect from uninstall", !on) {
                if (on) Parent.ask(this, L.s("Enter the PIN to change the protection.")) { Guard.openAdminList(this) } else Guard.askProtect(this) },
                Ui.lp(top = Ui.dp(this, 6)))
        } else if (st.syncFam.isNotBlank())
            guardBox.addView(Ui.text(this, "🔔 You get a notification if the patient's phone hasn't synced for 2 hours.", 12.5f, Ui.MUTED))
    }

    private fun chooseRole(id: String) {
        if (id == st.role) return
        val apply = {
            st.role = id
            if (st.syncName.isBlank()) st.syncName = st.roleName(id)
            renderRole(); renderHc(); renderFolds()
            if (uiLocked) renderLock()
            Ui.toast(this, st.roleName(id))
        }
        if (st.isPatient && Parent.enabled(st)) Parent.ask(this, L.s("Enter the PIN to change who uses this phone.")) { apply() } else apply()
    }

    /** While a request is waiting: refresh the lines every 5 seconds (up to 10 minutes). */
    private fun watchRequest() {
        hcTick.removeCallbacksAndMessages(null)
        hcTick.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                renderHc(); renderFolds()
                if (WatchRequest.pending(this@SettingsActivity)) hcTick.postDelayed(this, 5_000)
            }
        }, 5_000)
    }
}
