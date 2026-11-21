package com.nova.glucose

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.UserManager
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 📍 Locate the patient's phone (parents only). Her phone switches GPS on (device owner, locked on), takes a fresh fix
 * (or the last known one, with its time) and sends it with accuracy and battery; then every minute for 15 min (live follow).
 * Automatic: her position goes to the parents if an urgent low is not dismissed within 5 min, or a 🔔 bell is not answered within 10 min.
 * Silent on her phone. Positions are kept 2 days, only in the family sync.
 */
object Locate {
    private const val KEY = "sync_loc"            // {gid: {id, k: ask|pos, to, by, byUid, u, lat, lon, acc, prov, fix, batt, why}}
    private const val FOLLOW_MS = 15 * 60_000L
    private const val NOTE = 7950

    private fun all(c: Context): JSONObject = try { JSONObject(Store(c).raw.getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun save(c: Context, o: JSONObject) {
        val now = System.currentTimeMillis()
        o.keys().asSequence().toList().forEach { if (now - (o.optJSONObject(it)?.optLong("u") ?: 0L) > 2 * 24 * 3600_000L) o.remove(it) }
        Store(c).raw.edit().putString(KEY, o.toString()).apply()
    }
    private fun put(c: Context, gid: String, e: JSONObject) { val o = all(c); o.put(gid, e); save(c, o); Db.get(c).queue(gid, "loc") }

    // ------------------------------------------------------------ permissions and GPS (patient's phone, device owner)
    fun ownerSetup(c: Context) {
        if (!Net.owner(c)) return
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager; val a = Guard.admin(c)
        val perms = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) perms += android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        perms.forEach { p -> if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) try {
            dpm.setPermissionGrantState(a, c.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) } catch (_: Exception) { } }
        if (Build.VERSION.SDK_INT >= 30) try { if (!gpsOn(c)) dpm.setLocationEnabled(a, true) } catch (_: Exception) { }
        try { dpm.addUserRestriction(a, UserManager.DISALLOW_CONFIG_LOCATION) } catch (_: Exception) { }      // GPS stays on
    }
    fun ownerRelease(c: Context) {
        if (!Net.owner(c)) return
        try { (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).clearUserRestriction(Guard.admin(c), UserManager.DISALLOW_CONFIG_LOCATION) } catch (_: Exception) { }
    }
    fun gpsOn(c: Context) = try { (c.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled } catch (_: Exception) { false }
    fun allowed(c: Context) = c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ parent: ask
    fun ask(c: Context, toUid: String, toName: String) {
        val st = Store(c); val now = System.currentTimeMillis(); val id = "${st.syncUid.take(6)}$now"
        put(c, "loc-$id-a", JSONObject().put("id", id).put("k", "ask").put("to", toUid).put("toName", toName).put("by", Chat.myName(st)).put("byUid", st.syncUid).put("u", now))
        FamilySync.kick(c)
    }

    /** Latest position received for each locate (newest first), for the card in 💬 Messages. */
    fun latest(c: Context): JSONObject? {
        val now = System.currentTimeMillis()
        val o = all(c)
        return o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.filter { it.optString("k") == "pos" && now - it.optLong("u") < 3 * 3600_000L }.maxByOrNull { it.optLong("u") }
    }
    fun pending(c: Context): JSONObject? {
        val me = Store(c).syncUid; val now = System.currentTimeMillis(); val o = all(c)
        val ask = o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.filter { it.optString("k") == "ask" && it.optString("byUid") == me && now - it.optLong("u") < 5 * 60_000L }
            .maxByOrNull { it.optLong("u") } ?: return null
        return if (latest(c)?.let { it.optLong("u") >= ask.optLong("u") } == true) null else ask
    }
    fun followUntil(c: Context) = Store(c).raw.getLong("loc_follow_until_seen", 0L)

    fun item(c: Context, gid: String): JSONObject? = all(c).optJSONObject(gid)?.let { JSONObject(it.toString()).put("t", "loc").put("g", gid) }

    fun received(c: Context, it: JSONObject): String {
        val st = Store(c); val gid = it.optString("g")
        val o = all(c); if (o.has(gid)) return ""
        val x = JSONObject(it.toString()).apply { remove("t"); remove("g") }
        o.put(gid, x); save(c, o)
        val now = System.currentTimeMillis()
        when (x.optString("k")) {
            "ask" -> if (x.optString("to") == st.syncUid && st.isPatient && now - x.optLong("u") < 10 * 60_000L) {
                st.raw.edit().putLong("loc_follow_until", now + FOLLOW_MS).putString("loc_follow_id", x.optString("id")).apply()
                ErrorLog.e("Locate", "asked by ${x.optString("by")}")
                st.raw.edit().putLong("loc_last_sent", 0L).apply(); busy = false
                sendAsync(c, x.optString("id"), "")
            }
            "pos" -> if (!st.isPatient && now - x.optLong("u") < 30 * 60_000L) {
                st.raw.edit().putLong("loc_follow_until_seen", x.optLong("until")).apply()
                if (x.optString("why").isNotBlank() || x.optString("id") != st.raw.getString("loc_noted_id", "")) {
                    st.raw.edit().putString("loc_noted_id", x.optString("id")).apply(); note(c, x)
                }
            }
        }
        return "loc"
    }

    // ------------------------------------------------------------ patient: fix and send
    @Suppress("MissingPermission")
    private fun fix(c: Context): Location? {
        ownerSetup(c)
        if (!allowed(c)) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        if (Build.VERSION.SDK_INT >= 30) {
            for (prov in listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER)) {
                if (!try { lm.isProviderEnabled(prov) } catch (_: Exception) { false } && prov != "fused") continue
                val latch = CountDownLatch(1); val cs = CancellationSignal(); var got: Location? = null
                try { lm.getCurrentLocation(prov, cs, c.mainExecutor) { l -> got = l; latch.countDown() } } catch (_: Exception) { continue }
                if (!latch.await(if (prov == LocationManager.GPS_PROVIDER) 60 else 20, TimeUnit.SECONDS)) cs.cancel()
                got?.let { l -> if (best == null || l.accuracy < best!!.accuracy) best = l }
                if (best != null && best!!.accuracy <= 30f) break
            }
        }
        if (best == null) best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
        return best
    }

    @Volatile private var busy = false
    /** In the background (a fix can take up to a minute); one at a time. */
    private fun sendAsync(c: Context, id: String, why: String) {
        if (busy) return; busy = true
        val app = c.applicationContext
        Thread { try { send(app, id, why) } catch (_: Exception) { } finally { busy = false } }.start()
    }

    fun send(c: Context, id: String, why: String) {
        val st = Store(c); if (!st.isPatient) return
        st.raw.edit().putLong("loc_last_sent", System.currentTimeMillis()).apply()
        val l = fix(c)
        val now = System.currentTimeMillis()
        val batt = try { (c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { -1 }
        val e = JSONObject().put("id", id).put("k", "pos").put("by", Chat.myName(st)).put("byUid", st.syncUid).put("u", now).put("batt", batt).put("why", why)
            .put("until", st.raw.getLong("loc_follow_until", 0L)).put("gps", gpsOn(c))
        if (l != null) e.put("lat", l.latitude).put("lon", l.longitude).put("acc", l.accuracy.toInt()).put("prov", l.provider ?: "").put("fix", l.time)
        st.raw.edit().putLong("loc_last_sent", now).apply()
        put(c, "loc-$id-$now", e)
        ErrorLog.e("Locate", if (l == null) "no position available" else "position sent (± ${l.accuracy.toInt()} m${if (why.isNotBlank()) ", automatic: $why" else ""})")
        FamilySync.kick(c)
    }

    /** Every minute (monitor / wake-up), patient's phone: live follow, and the automatic position. */
    fun maybe(c: Context) {
        val st = Store(c); if (!st.isPatient) return
        val now = System.currentTimeMillis()
        if (now < st.raw.getLong("loc_follow_until", 0L) && now - st.raw.getLong("loc_last_sent", 0L) >= 55_000L) sendAsync(c, st.raw.getString("loc_follow_id", "") ?: "", "")
        // urgent low not dismissed within 5 min
        val low = st.lastTs > 0 && now - st.lastTs < 15 * 60_000L && st.lastMg in 1..st.urgentLow
        if (!low) st.raw.edit().putLong("ul_since", 0L).apply()
        else {
            val since = st.raw.getLong("ul_since", 0L).takeIf { it > 0 } ?: now.also { st.raw.edit().putLong("ul_since", it).apply() }
            val dismissed = st.raw.getLong("alarm_dismissed", 0L) > since
            if (!dismissed && now - since >= 5 * 60_000L && st.raw.getLong("loc_auto_ul", 0L) != since) {
                st.raw.edit().putLong("loc_auto_ul", since).putLong("loc_follow_until", now + FOLLOW_MS).putString("loc_follow_id", "auto$since").apply()
                sendAsync(c, "auto$since", "urgent low not dismissed for 5 min")
            }
        }
        // 🔔 bell not answered within 10 min
        Bell.unanswered(c, 10 * 60_000L)?.let { b ->
            val bid = b.optString("id")
            if (st.raw.getString("loc_auto_bell", "") != bid) {
                st.raw.edit().putString("loc_auto_bell", bid).putLong("loc_follow_until", now + FOLLOW_MS).putString("loc_follow_id", "bell$bid").apply()
                sendAsync(c, "bell$bid", "🔔 bell from ${b.optString("by")} not answered for 10 min")
            }
        }
    }

    // ------------------------------------------------------------ parents: notification
    fun mapsUrl(e: JSONObject) = "https://www.google.com/maps/search/?api=1&query=${e.optDouble("lat")},${e.optDouble("lon")}"

    private fun note(c: Context, e: JSONObject) {
        try {
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
            val has = e.has("lat")
            val auto = e.optString("why").isNotBlank()
            val title = (if (auto) "🚨 " else "📍 ") + L.s("{0}'s position").replace("{0}", e.optString("by")) + " · " + hm.format(java.util.Date(e.optLong(if (has) "fix" else "u")))
            val text = if (!has) L.s("No position available (GPS / internet)") + " · 🔋 ${e.optInt("batt")}%"
                else "± ${e.optInt("acc")} m · 🔋 ${e.optInt("batt")}%" + if (auto) " · " + L.s("sent automatically") + ": " + e.optString("why") else ""
            val open = PendingIntent.getActivity(c, NOTE, if (has) Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl(e))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                else Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val kind = if (auto) "bell" else "msg"
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, Sounds.channel(c, kind)) else @Suppress("DEPRECATION") Notification.Builder(c)
            c.getSystemService(NotificationManager::class.java).notify(NOTE, b.setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
            Sounds.after(c, kind)
        } catch (_: Exception) { }
    }
}
