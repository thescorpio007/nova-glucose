package com.nova.glucose

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

/**
 * A family phone asks the patient's phone to read the watch now (through family sync, end-to-end encrypted);
 * the patient's phone answers with fresh data and its status (build, last read, background permission).
 */
object WatchRequest {
    private const val HOUR = 3600_000L

    /** Family phone: send the request. */
    fun ask(c: Context) {
        val st = Store(c)
        st.hcReq = System.currentTimeMillis()
        Db.get(c).queue("hcreq", "hcreq")
        FamilySync.kick(c)
    }

    /** Family phone: ask by itself when the data is over an hour old (at most every 15 minutes). */
    fun autoAsk(c: Context): Boolean {
        val st = Store(c)
        if (!st.isFamily || st.syncFam.isBlank()) return false
        val now = System.currentTimeMillis()
        if (now - st.hcLastData < HOUR || now - st.hcReq < 15 * 60_000L) return false
        ask(c); return true
    }

    /** Patient phone: a request arrived. */
    fun received(c: Context, u: Long): String {
        val st = Store(c)
        if (st.isFamily || u <= st.hcReqDone) return ""
        if (!(st.isPatient || st.hcOn)) return ""
        st.hcReqDone = u
        val app = c.applicationContext
        Thread {
            try { HealthRead.sync(app, 3) } catch (e: Exception) { st.hcInfo = "⚠ " + (e.message ?: "Failed") }
            Db.get(app).queue("hcstatus", "hcst", kick = false)
            FamilySync.kick(app)
        }.start()
        return "hcreq"
    }

    /** Patient phone: its status for the family. */
    fun status(c: Context): JSONObject {
        val st = Store(c)
        return JSONObject().put("t", "hcst").put("g", "hcstatus").put("u", System.currentTimeMillis())
            .put("build", Updater.current(c)).put("read", st.hcLastRead).put("data", st.hcLastData)
            .put("bg", HealthRead.background(c)).put("perms", HealthRead.granted(c).size).put("info", st.hcInfo)
            .put("ans", st.hcReqDone).put("by", st.syncName)
    }

    private fun peer(st: Store): JSONObject? = try { if (st.hcPeer.isBlank()) null else JSONObject(st.hcPeer) } catch (_: Exception) { null }

    /** Family phone: one line about the patient's phone. */
    fun peerLine(c: Context): String {
        val st = Store(c)
        val p = peer(st) ?: return L.s("Patient's phone: no status received yet (it needs build 37 or newer and family sync)")
        val f = SimpleDateFormat("d MMM HH:mm", L.loc())
        return L.s("Patient's phone") + ": build %02d".format(p.optInt("build")) +
            (if (p.optLong("read") > 0) " · " + L.s("last read") + " " + f.format(Date(p.optLong("read"))) else "") +
            " · " + L.s("background reading") + " " + (if (p.optBoolean("bg")) "✓" else "✗")
    }

    /** Family phone: where the last request stands ("" when none). */
    fun requestLine(c: Context): String {
        val st = Store(c)
        if (st.hcReq == 0L) return ""
        val f = SimpleDateFormat("HH:mm", L.loc())
        val p = peer(st)
        val asked = L.s("Requested") + " " + f.format(Date(st.hcReq))
        return when {
            p != null && p.optLong("ans") >= st.hcReq ->
                asked + " · " + L.s("answered") + " " + f.format(Date(p.optLong("u"))) + " · " + L.s(p.optString("info").ifBlank { "done" })
            System.currentTimeMillis() - st.hcReq > 10 * 60_000L -> asked + " · " + L.s("no answer yet (phone off or offline?)")
            else -> asked + " · " + L.s("waiting for the patient's phone…")
        }
    }

    /** Patient phone: share its status every 30 minutes (build, last read, background permission). */
    fun maybeStatus(c: Context) {
        val st = Store(c)
        if (st.isFamily || st.syncFam.isBlank() || !(st.isPatient || st.hcOn)) return
        val sp = c.getSharedPreferences(Store.PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("last_hc_st", 0) < 30 * 60_000L) return
        sp.edit().putLong("last_hc_st", System.currentTimeMillis()).apply()
        Db.get(c).queue("hcstatus", "hcst", kick = false)
    }

    fun pending(c: Context): Boolean {
        val st = Store(c); val p = peer(st)
        return st.hcReq > 0 && System.currentTimeMillis() - st.hcReq < 10 * 60_000L && (p == null || p.optLong("ans") < st.hcReq)
    }
}
