package com.nova.glucose

import android.os.Bundle
import android.widget.ScrollView

/** Shown by Health Connect: what Nova Glucose reads from the watch and what it does with it. */
class HealthPrivacyActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.col(this, 18)
        val head = Ui.row(this)
        head.addView(Ui.title(this, "⌚ Watch data in Nova Glucose"), Ui.lp(0, weight = 1f))
        head.addView(Ui.smallButton(this, "Close") { finish() })
        root.addView(head)
        val c = Ui.card(this)
        listOf(
            "Nova Glucose reads (never writes) steps, exercise sessions, sleep, heart rate, resting heart rate, SpO2, height and weight from Health Connect.",
            "It uses them only to show them next to her glucose (Activity & sleep page), for the AI review and tips, and in the doctor report.",
            "The data stays on the phone, in the encrypted backup and — if family sync is on — end-to-end encrypted on the family's phones. It is never sold or shared with anyone else.",
            "The AI provider you chose in Settings receives a short summary when you ask for a review or a tip.",
            "You can stop it at any time in Health Connect or in Settings → Huawei watch data."
        ).forEach { c.addView(Ui.text(this, "•  $it", 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4)) }) }
        root.addView(c, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.signature(this))
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Ui.BG) })
    }
}
