package com.nova.glucose

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.view.Gravity
import android.view.WindowManager
import org.json.JSONObject

/**
 * 📶 Network on the patient's phone: checks the internet every few minutes, pop-up + sound when it is gone (repeated),
 * and safe Wi-Fi networks shared by the family. As device owner (set once with ADB) Nova also switches Wi-Fi back on,
 * locks the mobile-data switch and airplane mode, and blocks uninstalling.
 */
object Net {
    private const val NOTE = 7971
    private const val WIFI = "sync_wifi"        // {id: {id, ssid, pass, sec, by, del, u}}

    // ------------------------------------------------------------ status
    class Status(val wifiOn: Boolean, val wifiNet: Boolean, val cell: Boolean, val internet: Boolean)

    fun status(c: Context): Status {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        var wifi = false; var cell = false; var ok = false
        try {
            cm.allNetworks.forEach { n -> val cap = cm.getNetworkCapabilities(n) ?: return@forEach
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
                if (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) ok = true }
        } catch (_: Exception) { }
        return Status(try { wm.isWifiEnabled } catch (_: Exception) { false }, wifi, cell, ok)
    }

    fun line(c: Context): String {
        val s = status(c); val st = Store(c)
        var t = "Wi-Fi ${if (s.wifiNet) "✓" else if (s.wifiOn) L.s("on, not connected") else "✗ " + L.s("off")} · " +
            "${L.s("Mobile data")} ${if (s.cell) "✓" else "✗"} · ${L.s("Internet")} ${if (s.internet) "✓" else "✗"}"
        if (st.netBt) bt(c)?.let { t += " · Bluetooth ${if (it) "✓" else "✗"}" }
        if (st.netNfc) nfc(c)?.let { t += " · NFC ${if (it) "✓" else "✗"}" }
        return t
    }

    // ------------------------------------------------------------ Bluetooth and NFC (null = this phone has none)
    fun bt(c: Context): Boolean? = try { (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled } catch (_: Exception) { null }
    fun nfc(c: Context): Boolean? = try { NfcAdapter.getDefaultAdapter(c)?.isEnabled } catch (_: Exception) { null }
    fun btAllowed(c: Context) = Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    /** NFC can only be switched by apps given WRITE_SECURE_SETTINGS with ADB (experimental, not every phone). */
    fun nfcAllowed(c: Context) = c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    const val ADB_NFC = "adb shell pm grant com.nova.glucose android.permission.WRITE_SECURE_SETTINGS"

    @Suppress("DEPRECATION", "MissingPermission")
    private fun btOn(c: Context): Boolean = try { btAllowed(c) && (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.enable() } catch (_: Exception) { false }
    private fun nfcOn(c: Context): Boolean = try {
        val a = NfcAdapter.getDefaultAdapter(c) ?: return false
        nfcAllowed(c) && (NfcAdapter::class.java.getMethod("enable").invoke(a) as? Boolean ?: false)
    } catch (e: Exception) { if (!nfcLogged) { nfcLogged = true; ErrorLog.e("Network", "NFC switch not possible on this phone: ${e.javaClass.simpleName}") }; false }
    private var nfcLogged = false

    /** What is off right now (after trying to switch it back on): "internet", "bt", "nfc". */
    fun problems(c: Context): List<String> {
        val st = Store(c); val o = mutableListOf<String>()
        if (!status(c).internet) o += "internet"
        if (st.netBt && bt(c) == false) o += "bt"
        if (st.netNfc && nfc(c) == false) o += "nfc"
        return o
    }

    // ------------------------------------------------------------ device owner
    fun owner(c: Context) = try { (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(c.packageName) } catch (_: Exception) { false }
    const val ADB = "adb shell dpm set-device-owner com.nova.glucose/.GuardAdmin"

    /** As device owner: lock the mobile-data switch and airplane mode, block uninstalling. */
    fun applyOwner(c: Context) {
        if (!owner(c)) return
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager; val a = Guard.admin(c)
        try { dpm.addUserRestriction(a, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS) } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 28) try { dpm.addUserRestriction(a, UserManager.DISALLOW_AIRPLANE_MODE) } catch (_: Exception) { }
        try { dpm.setUninstallBlocked(a, c.packageName, true) } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 31 && !btAllowed(c)) try {      // device owner gives itself "Nearby devices" (Bluetooth)
            dpm.setPermissionGrantState(a, c.packageName, android.Manifest.permission.BLUETOOTH_CONNECT, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } catch (_: Exception) { }
    }

    /** Parent switch (PIN): give everything back and stop being device owner. */
    fun releaseOwner(c: Context) {
        if (!owner(c)) return
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager; val a = Guard.admin(c)
        try { dpm.clearUserRestriction(a, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS) } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT >= 28) try { dpm.clearUserRestriction(a, UserManager.DISALLOW_AIRPLANE_MODE) } catch (_: Exception) { }
        try { dpm.setUninstallBlocked(a, c.packageName, false) } catch (_: Exception) { }
        @Suppress("DEPRECATION") try { dpm.clearDeviceOwnerApp(c.packageName) } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun wifiOn(c: Context): Boolean = try { (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).setWifiEnabled(true) } catch (_: Exception) { false }

    // ------------------------------------------------------------ every minute (monitor), patient's phone
    fun maybe(c: Context) {
        val st = Store(c)
        if (!st.isPatient) return
        val now = System.currentTimeMillis()
        if (now - st.raw.getLong("net_last_check", 0L) < st.netEvery * 60_000L - 5_000) return
        st.raw.edit().putLong("net_last_check", now).apply()
        val o = owner(c)
        if (o) { applyOwner(c); if (!status(c).wifiOn && wifiOn(c)) ErrorLog.e("Network", "Wi-Fi was off — switched back on (device owner)") }
        if (st.netBt && bt(c) == false && (o || Build.VERSION.SDK_INT < 33) && btOn(c)) ErrorLog.e("Network", "Bluetooth was off — switched back on")
        if (st.netNfc && nfc(c) == false && nfcAllowed(c) && nfcOn(c)) ErrorLog.e("Network", "NFC was off — switched back on")
        applySafe(c)
        val p = problems(c)
        if (p.isEmpty()) {
            if (st.raw.getLong("net_down", 0L) > 0) { ErrorLog.e("Network", "connections back"); st.raw.edit().putLong("net_down", 0L).putLong("net_pop", 0L).apply()
                (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE) }
            return
        }
        val down = st.raw.getLong("net_down", 0L).takeIf { it > 0 } ?: now.also { st.raw.edit().putLong("net_down", it).apply()
            ErrorLog.e("Network", "off: ${p.joinToString()} · " + line(c)) }
        if (!st.onFor("net")) return
        if (now - st.raw.getLong("net_pop", 0L) < st.netRemind * 60_000L) return
        st.raw.edit().putLong("net_pop", now).apply()
        popup(c, (now - down) / 60_000L, p)
    }

    fun names(p: List<String>) = p.joinToString(" · ") { when (it) { "internet" -> L.s("Internet"); "bt" -> "Bluetooth"; else -> "NFC" } }

    private fun popup(c: Context, minutes: Long, p: List<String>) {
        val i = Intent(c, NetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(c, 7972, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, Sounds.channel(c, "net")) else @Suppress("DEPRECATION") Notification.Builder(c)
        val n = b.setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(L.s("📵 Connection off") + ": " + names(p))
            .setContentText((if ("internet" in p) L.s("Nova Glucose can't read the glucose or send it to the family.") else L.s("The sensor or the watch may stop sending readings.")) + if (minutes > 0) " ($minutes min)" else "")
            .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE, n)
        if (Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c)) try { c.startActivity(i) } catch (_: Exception) { }
        Sounds.after(c, "net")
    }

    fun openWifi(a: Activity) = open(a, if (Build.VERSION.SDK_INT >= 29) android.provider.Settings.Panel.ACTION_WIFI else android.provider.Settings.ACTION_WIFI_SETTINGS)
    fun openBt(a: Activity) = open(a, if (Build.VERSION.SDK_INT >= 33) android.provider.Settings.ACTION_BLUETOOTH_SETTINGS else "android.bluetooth.adapter.action.REQUEST_ENABLE")
    fun openNfc(a: Activity) = open(a, android.provider.Settings.ACTION_NFC_SETTINGS)
    fun openData(a: Activity) = open(a, if (Build.VERSION.SDK_INT >= 29) android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY else android.provider.Settings.ACTION_WIRELESS_SETTINGS)
    private fun open(a: Activity, act: String) { try { a.startActivity(Intent(act)) } catch (_: Exception) { try { a.startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) { } } }

    // ------------------------------------------------------------ safe Wi-Fi (shared with the family)
    class Wifi(val id: String, val ssid: String, val pass: String, val sec: String, val by: String)

    private fun all(c: Context) = try { JSONObject(Store(c).raw.getString(WIFI, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    fun list(c: Context): List<Wifi> { val o = all(c)
        return o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.filter { !it.optBoolean("del") }
            .map { Wifi(it.optString("id"), it.optString("ssid"), it.optString("pass"), it.optString("sec", "WPA2"), it.optString("by")) }.sortedBy { it.ssid.lowercase() }.toList() }

    fun put(c: Context, ssid: String, pass: String, sec: String, id: String = java.util.UUID.randomUUID().toString().take(10), del: Boolean = false) {
        val o = all(c)
        o.put(id, JSONObject().put("id", id).put("ssid", ssid).put("pass", pass).put("sec", sec).put("by", Chat.myName(Store(c))).put("del", del).put("u", System.currentTimeMillis()))
        Store(c).raw.edit().putString(WIFI, o.toString()).putBoolean("wifi_dirty", true).apply()
        Db.get(c).queue("wifi-$id", "wifi")
    }

    fun item(c: Context, gid: String): JSONObject? = all(c).optJSONObject(gid.removePrefix("wifi-"))?.let { JSONObject(it.toString()).put("t", "wifi").put("g", gid) }

    fun received(c: Context, it: JSONObject): String {
        val id = it.optString("id"); if (id.isBlank()) return ""
        val o = all(c); val cur = o.optJSONObject(id)
        if (cur != null && cur.optLong("u") >= it.optLong("u")) return ""
        val x = JSONObject(it.toString()); x.remove("t"); x.remove("g"); o.put(id, x)
        Store(c).raw.edit().putString(WIFI, o.toString()).putBoolean("wifi_dirty", true).apply()
        return "wifi"
    }

    /** Patient's phone: give the safe networks to Android (device owner: saved silently; otherwise as suggestions, approved once). */
    fun applySafe(c: Context) {
        val st = Store(c)
        if (!st.isPatient || !st.raw.getBoolean("wifi_dirty", false)) return
        st.raw.edit().putBoolean("wifi_dirty", false).apply()
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val l = list(c)
        try {
            if (owner(c)) {
                @Suppress("DEPRECATION")
                l.forEach { w -> val cfg = android.net.wifi.WifiConfiguration().apply { SSID = "\"${w.ssid}\""
                    if (w.sec == "Open" || w.pass.isBlank()) allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE) else preSharedKey = "\"${w.pass}\"" }
                    val nid = wm.addNetwork(cfg); if (nid >= 0) wm.enableNetwork(nid, false) }
            } else if (Build.VERSION.SDK_INT >= 29) {
                val sugg = l.map { w -> android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(w.ssid).apply {
                    if (w.sec == "WPA3" && w.pass.isNotBlank()) setWpa3Passphrase(w.pass) else if (w.pass.isNotBlank() && w.sec != "Open") setWpa2Passphrase(w.pass) }.build() }
                try { wm.removeNetworkSuggestions(emptyList()) } catch (_: Exception) { }
                if (sugg.isNotEmpty()) wm.addNetworkSuggestions(sugg)
            }
            ErrorLog.e("Network", "safe Wi-Fi given to the phone: ${l.size} network(s)" + if (owner(c)) " (saved)" else " (suggested)")
        } catch (e: Exception) { ErrorLog.e("Network", "safe Wi-Fi: ${e.message}") }
    }
}

/** Full-screen "No internet" pop-up on the patient's phone. */
class NetActivity : BaseActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val st = Store(this)
        val p = Net.problems(this)
        val root = Ui.col(this, 24).apply { gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Ui.BG) }
        root.addView(Ui.text(this, "📵", 64f, Ui.TEXT).apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 30)))
        root.addView(Ui.text(this, "Connection off", 28f, Ui.TEXT, true).apply { gravity = Gravity.CENTER })
        root.addView(Ui.text(this, Net.names(p), 18f, Ui.WARN, true).apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 4)))
        root.addView(Ui.text(this, if ("internet" in p || p.isEmpty()) "Nova Glucose can't read the glucose or send it to the family." else "The sensor or the watch may stop sending readings.",
            15f, Ui.MUTED).apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 8)))
        val stt = Ui.text(this, Net.line(this), 14f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        root.addView(stt, Ui.lp(top = Ui.dp(this, 12)))
        if ("internet" in p || p.isEmpty()) {
            root.addView(Ui.button(this, "Turn on Wi-Fi", true) { Net.openWifi(this) }, Ui.lp(top = Ui.dp(this, 20)))
            root.addView(Ui.button(this, "Turn on mobile data") { Net.openData(this) }, Ui.lp(top = Ui.dp(this, 10)))
        }
        if ("bt" in p) root.addView(Ui.button(this, "Turn on Bluetooth", true) { Net.openBt(this) }, Ui.lp(top = Ui.dp(this, 10)))
        if ("nfc" in p) root.addView(Ui.button(this, "Turn on NFC", true) { Net.openNfc(this) }, Ui.lp(top = Ui.dp(this, 10)))
        root.addView(Ui.text(this, L.s("Next reminder in {0} min if still offline.").replace("{0}", "${st.netRemind}"), 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }, Ui.lp(top = Ui.dp(this, 16)))
        root.addView(Ui.button(this, "Close") { Sounds.stop(); finish() }, Ui.lp(top = Ui.dp(this, 10)))
        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
        val h = android.os.Handler(mainLooper)
        h.post(object : Runnable { override fun run() {
            if (isFinishing) return
            stt.text = Net.line(this@NetActivity)
            if (Net.problems(this@NetActivity).isEmpty()) { Sounds.stop(); Ui.toast(this@NetActivity, "✓ All connections are back"); finish(); return }
            h.postDelayed(this, 3000) } })
    }
}
