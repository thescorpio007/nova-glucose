package com.nova.glucose

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Device administrator on the patient's phone: Nova Glucose can't be uninstalled until this is turned off first. */
class GuardAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(c: Context, i: Intent): CharSequence =
        L.s("Nova Glucose protects the patient's glucose alarms. If you turn this off, the app can be uninstalled.")
}

/**
 * Uninstall protection and the parent warning: on the family phones, a notification when the patient's phone
 * hasn't synced for 2 hours (phone off, no internet, or Nova Glucose stopped or removed).
 */
object Guard {
    const val SILENT_MS = 2 * 3600_000L
    private const val ID_NOTE = 7801

    fun admin(c: Context) = ComponentName(c, GuardAdmin::class.java)
    fun protected(c: Context) = try { (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(admin(c)) } catch (_: Exception) { false }

    fun askProtect(a: android.app.Activity) {
        try {
            a.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(a))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, L.s("Blocks uninstalling Nova Glucose until this protection is turned off first, so the patient's glucose alarms keep working.")))
        } catch (_: Exception) { Ui.toast(a, "Open Settings › Security › Device admin apps") }
    }

    fun openAdminList(a: android.app.Activity) {
        for (act in listOf("android.settings.DEVICE_ADMIN_SETTINGS", android.provider.Settings.ACTION_SECURITY_SETTINGS))
            try { a.startActivity(Intent(act)); return } catch (_: Exception) { }
        Ui.toast(a, "Open Settings › Security › Device admin apps")
    }

    /** Family phones: check the patient's phone every 15 minutes (from the monitor). */
    fun maybe(c: Context) {
        val st = Store(c)
        if (st.isPatient || st.syncFam.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - st.raw.getLong("last_guard_chk", 0L) < 15 * 60_000L) return
        st.raw.edit().putLong("last_guard_chk", now).apply()
        val app = c.applicationContext
        Thread { try { check(app) } catch (_: Exception) { } }.start()
    }

    private fun check(c: Context) {
        val st = Store(c)
        val list = FamilySync.memberDocs(c).filter { !it.me }
        val p = Members.patient(c, list) ?: return
        val now = System.currentTimeMillis()
        val warned = st.raw.getLong("last_guard_warn", 0L)
        if (p.seen > 0 && now - p.seen > SILENT_MS) {
            if (warned == p.seen) return
            st.raw.edit().putLong("last_guard_warn", p.seen).apply()
            val h = (now - p.seen) / 3600_000L
            note(c, L.s("Patient's phone not seen for {0} h").replace("{0}", "$h"),
                L.s("Last sync {0}. The phone may be off or offline, or Nova Glucose may have been stopped or removed.")
                    .replace("{0}", SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(p.seen))))
        } else if (warned > 0 && p.seen > warned) {
            st.raw.edit().putLong("last_guard_warn", 0L).apply()
            note(c, L.s("✓ Patient's phone is back"), L.s("It synced again at {0}.").replace("{0}", SimpleDateFormat("HH:mm", Locale.US).format(Date(p.seen))))
        }
    }

    private fun note(c: Context, title: String, text: String) {
        val ch = Sounds.channel(c, "other")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ch) else @Suppress("DEPRECATION") Notification.Builder(c)
        val open = PendingIntent.getActivity(c, 7802, Intent(c, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = b.setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_NOTE, n)
        Sounds.after(c, "other")
    }
}
