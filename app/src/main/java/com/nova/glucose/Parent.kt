package com.nova.glucose

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import java.security.MessageDigest

/**
 * Parental control: a 4-digit parent PIN. While locked, Settings is view-only and deleting meals or doses
 * and changing the AI model need the PIN. Unlocked until leaving Settings, 5 minutes at most.
 */
object Parent {
    const val UNLOCK_MS = 5 * 60_000L
    private const val MAX_FAILS = 5
    @Volatile var unlockedUntil = 0L

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("nova-glucose:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun enabled(st: Store) = st.pinOn && st.pinHash.isNotBlank()
    fun locked(st: Store) = enabled(st) && System.currentTimeMillis() > unlockedUntil
    fun lock() { unlockedUntil = 0 }
    fun unlockFor5() { unlockedUntil = System.currentTimeMillis() + UNLOCK_MS }

    /** Runs [action] straight away when unlocked, otherwise after the parent PIN. */
    fun guard(a: Activity, what: String, action: () -> Unit) {
        val st = Store(a)
        if (!locked(st)) action() else ask(a, what) { action() }
    }

    private fun pinBox(a: Activity, hint: String) = EditText(a).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(4))
        gravity = Gravity.CENTER; textSize = 26f; letterSpacing = 0.5f
    }

    fun ask(a: Activity, what: String, onOk: () -> Unit) {
        val st = Store(a)
        val now = System.currentTimeMillis()
        if (now < st.pinBlockUntil) {
            Ui.toast(a, "Too many wrong PINs — try again in ${(st.pinBlockUntil - now) / 1000 + 1} s"); return
        }
        val box = pinBox(a, "••••")
        val d = Tr.Builder(a)
            .setTitle("🔒 Parent PIN")
            .setMessage(what)
            .setView(Ui.col(a, 20).apply { addView(box) })
            .setPositiveButton("Unlock", null)
            .setNeutralButton("Forgot PIN") { _, _ -> forgot(a) }
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (hash(box.text.toString()) == st.pinHash) {
                    st.pinFails = 0; unlockFor5(); d.dismiss(); onOk()
                } else {
                    st.pinFails = st.pinFails + 1
                    box.setText("")
                    if (st.pinFails >= MAX_FAILS) {
                        st.pinFails = 0; st.pinBlockUntil = System.currentTimeMillis() + 60_000L
                        d.dismiss(); Ui.toast(a, "Too many wrong PINs — wait 1 minute")
                    } else box.error = "Wrong PIN (${MAX_FAILS - st.pinFails} tries left)"
                }
            }
        }
        d.show()
    }

    /** Choose a new PIN (entered twice). */
    fun setPin(a: Activity, done: (Boolean) -> Unit) {
        val p1 = pinBox(a, "New PIN"); val p2 = pinBox(a, "Repeat PIN")
        val box = Ui.col(a, 20).apply { addView(p1); addView(p2, Ui.lp(top = Ui.dp(a, 8))) }
        val d = Tr.Builder(a).setTitle("Set the parent PIN (4 digits)").setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel") { _, _ -> done(false) }.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a1 = p1.text.toString(); val a2 = p2.text.toString()
                when {
                    !Regex("\\d{4}").matches(a1) -> p1.error = "Enter 4 digits"
                    a1 != a2 -> p2.error = "The two PINs don't match"
                    else -> {
                        val st = Store(a); st.pinHash = hash(a1); st.pinOn = true; unlockFor5()
                        d.dismiss(); Ui.toast(a, "Parent PIN saved"); done(true)
                    }
                }
            }
        }
        d.show()
    }

    /** Forgot PIN: the backup password removes the lock. */
    private fun forgot(a: Activity) {
        val st = Store(a)
        if (st.backupPw.isBlank()) {
            Tr.Builder(a).setTitle("Forgot PIN")
                .setMessage("No backup password is set on this phone, so the PIN can't be reset here. " +
                    "Reinstalling the app removes the lock (resume your data from the Download/NovaGlucose folder).")
                .setPositiveButton("OK", null).show(); return
        }
        val pw = EditText(a).apply {
            hint = "Backup password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        Tr.Builder(a).setTitle("Reset the parent PIN")
            .setMessage("Enter the backup password to remove the PIN. Then set a new one in Settings › Parental control.")
            .setView(Ui.col(a, 20).apply { addView(pw) })
            .setPositiveButton("Reset") { _, _ ->
                if (pw.text.toString() == st.backupPw) {
                    st.pinOn = false; st.pinHash = ""; st.pinFails = 0; unlockFor5()
                    Ui.toast(a, "PIN removed — set a new one in Parental control")
                    a.recreate()
                } else Ui.toast(a, "Wrong backup password")
            }
            .setNegativeButton("Cancel", null).show()
    }
}
