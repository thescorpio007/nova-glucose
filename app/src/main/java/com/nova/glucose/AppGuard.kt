package com.nova.glucose

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 🛡 Protected apps on the patient's phone (needs device owner): the chosen apps (Libre, Huawei Health, Health Sync…)
 * can't be uninstalled, app management (force stop, disable, clear data) can be locked for all apps, and the parents are
 * told when a protected app is removed, disabled or force-stopped. Play Store updates still install normally.
 */
object AppGuard {
    val DEFAULT = listOf("com.freestylelibre.app.ae", "com.huawei.health", "nl.appyhapps.healthsync")
    private const val APPS = "guard_apps"            // this phone only (patient)
    private const val LOCK = "apps_lock"
    private const val UNTIL = "apps_unlock_until"
    private const val ST = "sync_appst"              // last status from the patient's phone {apps:[{p,n,s}], u}
    private const val NOTE = 7981

    fun chosen(c: Context): List<String> {
        val raw = Store(c).raw.getString(APPS, null) ?: return DEFAULT
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    fun setChosen(c: Context, l: List<String>) { Store(c).raw.edit().putString(APPS, l.distinct().joinToString(",")).apply(); apply(c) }
    fun lockOn(c: Context) = Store(c).raw.getBoolean(LOCK, true)
    fun setLock(c: Context, on: Boolean) { Store(c).raw.edit().putBoolean(LOCK, on).apply(); apply(c) }
    fun unlockedUntil(c: Context) = Store(c).raw.getLong(UNTIL, 0L)
    fun unlock15(c: Context) { Store(c).raw.edit().putLong(UNTIL, System.currentTimeMillis() + 15 * 60_000L).apply(); apply(c) }

    fun label(c: Context, p: String): String = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(p, 0)).toString() }
        catch (_: Exception) { when (p) { "com.freestylelibre.app.ae" -> "FreeStyle Libre"; "com.huawei.health" -> "Huawei Health"; "nl.appyhapps.healthsync" -> "Health Sync"; else -> p } }

    /** "ok", "removed", "disabled" or "stopped". */
    fun state(c: Context, p: String): String {
        val pm = c.packageManager
        val ai = try { pm.getApplicationInfo(p, 0) } catch (_: PackageManager.NameNotFoundException) { return "removed" } catch (_: Exception) { return "ok" }
        val es = try { pm.getApplicationEnabledSetting(p) } catch (_: Exception) { PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }
        if (!ai.enabled || es == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || es == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) return "disabled"
        if (ai.flags and ApplicationInfo.FLAG_STOPPED != 0) return "stopped"
        return "ok"
    }

    /** As device owner: block uninstalling the chosen apps (and Nova), lock app management unless a parent unlocked it. */
    fun apply(c: Context) {
        if (!Net.owner(c)) return
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager; val a = Guard.admin(c)
        val want = chosen(c).toSet()
        val had = Store(c).raw.getString("apps_blocked", "")!!.split(',').filter { it.isNotBlank() }.toSet()
        (had - want).forEach { try { dpm.setUninstallBlocked(a, it, false) } catch (_: Exception) { } }
        want.forEach { try { dpm.setUninstallBlocked(a, it, true) } catch (_: Exception) { } }
        try { dpm.setUninstallBlocked(a, c.packageName, true) } catch (_: Exception) { }
        Store(c).raw.edit().putString("apps_blocked", want.joinToString(",")).apply()
        val lock = lockOn(c) && System.currentTimeMillis() >= unlockedUntil(c)
        try { if (lock) dpm.addUserRestriction(a, UserManager.DISALLOW_APPS_CONTROL) else dpm.clearUserRestriction(a, UserManager.DISALLOW_APPS_CONTROL) } catch (_: Exception) { }
    }

    /** Parent's release of device owner: give everything back. */
    fun release(c: Context) {
        if (!Net.owner(c)) return
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager; val a = Guard.admin(c)
        chosen(c).forEach { try { dpm.setUninstallBlocked(a, it, false) } catch (_: Exception) { } }
        try { dpm.clearUserRestriction(a, UserManager.DISALLOW_APPS_CONTROL) } catch (_: Exception) { }
        Store(c).raw.edit().putString("apps_blocked", "").apply()
    }

    fun locked(c: Context): Boolean = try { (c.getSystemService(Context.USER_SERVICE) as UserManager).hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL) } catch (_: Exception) { false }

    // ------------------------------------------------------------ every minute (monitor), patient's phone: check every 5 min
    fun maybe(c: Context) {
        val st = Store(c)
        if (!st.isPatient) return
        val now = System.currentTimeMillis()
        val until = unlockedUntil(c)
        if (until in 1..now) { st.raw.edit().putLong(UNTIL, 0L).apply(); apply(c) }
        if (now - st.raw.getLong("apps_last_check", 0L) < 5 * 60_000L - 5_000) return
        st.raw.edit().putLong("apps_last_check", now).apply()
        apply(c)
        val arr = JSONArray(); val bad = mutableListOf<String>()
        chosen(c).forEach { p -> val s = state(c, p); arr.put(JSONObject().put("p", p).put("n", label(c, p)).put("s", s)); if (s != "ok") bad += "$p:$s" }
        val sig = bad.joinToString(",")
        val prev = st.raw.getString("apps_sig", "") ?: ""
        val o = JSONObject().put("apps", arr).put("u", now).put("lock", locked(c)).put("own", Net.owner(c))
        st.raw.edit().putString(ST, o.toString()).apply()
        if (sig != prev || now - st.raw.getLong("apps_sent", 0L) > 60 * 60_000L) {          // changed, or once an hour
            st.raw.edit().putString("apps_sig", sig).putLong("apps_sent", now).apply()
            if (sig != prev && sig.isNotEmpty()) ErrorLog.e("Apps", "protected app problem: $sig")
            Db.get(c).queue("appst-$now", "appst")
        }
    }

    fun item(c: Context, gid: String): JSONObject? {
        val o = try { JSONObject(Store(c).raw.getString(ST, "{}") ?: "{}") } catch (_: Exception) { return null }
        if (!o.has("u")) return null
        return JSONObject(o.toString()).put("t", "appst").put("g", gid)
    }

    /** Family phone: keep the status, warn when an app newly has a problem. */
    fun received(c: Context, it: JSONObject): String {
        val st = Store(c)
        if (st.isPatient) return ""
        val old = status(c)
        if (old != null && old.optLong("u") >= it.optLong("u")) return ""
        val x = JSONObject(it.toString()); x.remove("t"); x.remove("g")
        st.raw.edit().putString(ST, x.toString()).apply()
        val before = old?.optJSONArray("apps")?.let { a -> (0 until a.length()).associate { i -> a.getJSONObject(i).optString("p") to a.getJSONObject(i).optString("s") } } ?: emptyMap()
        val a = x.optJSONArray("apps") ?: return "appst"
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i); val s = e.optString("s")
            if (s != "ok" && before[e.optString("p")] != s && System.currentTimeMillis() - x.optLong("u") < 6 * 3600_000L) note(c, e.optString("n"), s)
        }
        return "appst"
    }

    fun status(c: Context): JSONObject? = try { JSONObject(Store(c).raw.getString(ST, "") ?: "").takeIf { it.has("u") } } catch (_: Exception) { null }

    fun stateText(s: String) = when (s) { "removed" -> L.s("removed"); "disabled" -> L.s("disabled"); "stopped" -> L.s("stopped"); else -> "✓" }

    private fun note(c: Context, name: String, s: String) {
        if (!Store(c).onFor("apps")) return
        val title = "🛡 " + L.s("{0} is {1} on the patient's phone").replace("{0}", name).replace("{1}", stateText(s))
        val text = L.s("Readings, alarms or watch data may stop. Open the patient's phone and turn it back on.")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, Sounds.channel(c, "apps")) else @Suppress("DEPRECATION") Notification.Builder(c)
        val open = PendingIntent.getActivity(c, 7982, Intent(c, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = b.setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE + (name.hashCode() and 0xff), n)
        Sounds.after(c, "apps")
    }
}
