package com.nova.glucose

import android.content.Context
import org.json.JSONObject

/**
 * "Phones in the family": rename or remove a phone in the list, shared with all phones (end-to-end encrypted items).
 * Safety: the patient's phone (the latest one) and any phone active in the last 7 days can't be removed; this phone
 * leaves only with "Leave". A removed phone that syncs again shows up again by itself.
 */
object Members {
    const val INACTIVE_MS = 7L * 24 * 3600_000
    private const val EDITS = "sync_members"      // {uid: {n, rm, u}}
    private const val ROLES = "sync_roles"        // {uid: {r, u}}
    private const val SENT = "sync_role_sent"

    private fun json(c: Context, k: String): JSONObject = try { JSONObject(Store(c).raw.getString(k, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun save(c: Context, k: String, o: JSONObject) { Store(c).raw.edit().putString(k, o.toString()).apply() }

    /** Rename (blank = back to the phone's own name) or remove a phone, for all phones. */
    fun edit(c: Context, uid: String, name: String? = null, remove: Boolean = false) {
        val e = json(c, EDITS); val o = e.optJSONObject(uid) ?: JSONObject()
        if (name != null) { if (name.isBlank()) o.remove("n") else o.put("n", name.trim()) }
        if (remove) o.put("rm", System.currentTimeMillis())
        o.put("u", System.currentTimeMillis()); e.put(uid, o); save(c, EDITS, e)
        Db.get(c).queue("mem-$uid", "mem")
    }

    fun item(c: Context, gid: String): JSONObject? {
        val uid = gid.removePrefix("mem-"); val o = json(c, EDITS).optJSONObject(uid) ?: return null
        return JSONObject().put("t", "mem").put("g", gid).put("u", o.optLong("u")).put("uid", uid)
            .put("n", o.optString("n")).put("rm", o.optLong("rm"))
    }

    fun received(c: Context, item: JSONObject): String {
        val uid = item.optString("uid"); val u = item.optLong("u"); if (uid.isBlank()) return ""
        val e = json(c, EDITS); val o = e.optJSONObject(uid) ?: JSONObject()
        if (u <= o.optLong("u")) return ""
        val n = item.optString("n"); if (n.isBlank()) o.remove("n") else o.put("n", n)
        o.put("rm", item.optLong("rm")); o.put("u", u); e.put(uid, o); save(c, EDITS, e)
        return "mem"
    }

    /** Each phone tells the others whether it is the patient's phone (sent when it changes). */
    fun announce(c: Context) {
        val st = Store(c); if (st.syncUid.isBlank()) return
        val now = "${st.syncUid}|${st.role}"
        if (st.raw.getString(SENT, "") == now) return
        st.raw.edit().putString(SENT, now).apply()
        Db.get(c).queue("who-${st.syncUid}", "who", kick = false)
    }

    fun whoItem(c: Context): JSONObject? {
        val st = Store(c); if (st.syncUid.isBlank()) return null
        return JSONObject().put("t", "who").put("g", "who-${st.syncUid}").put("u", System.currentTimeMillis()).put("uid", st.syncUid).put("r", st.role)
    }

    fun receivedWho(c: Context, item: JSONObject): String {
        val uid = item.optString("uid"); val u = item.optLong("u"); if (uid.isBlank()) return ""
        val e = json(c, ROLES); val o = e.optJSONObject(uid) ?: JSONObject()
        if (u <= o.optLong("u")) return ""
        o.put("r", item.optString("r")); o.put("u", u); e.put(uid, o); save(c, ROLES, e)
        return "who"
    }

    /** The patient's phone (the most recently seen phone that says it is the patient's). */
    fun patient(c: Context, list: List<FamilySync.Member>): FamilySync.Member? {
        val roles = json(c, ROLES)
        return list.filter { (roles.optJSONObject(it.uid)?.optString("r") ?: "") == "patient" }.maxByOrNull { it.seen }
    }

    /** One line of the list. */
    class Row(val m: FamilySync.Member, val name: String, val patient: Boolean, val locked: Boolean, val canRemove: Boolean, val why: String)

    /** The list as shown: removed phones hidden (until they sync again), the latest patient's phone protected. */
    fun rows(c: Context, list: List<FamilySync.Member>): Pair<List<Row>, Int> {
        val st = Store(c); val e = json(c, EDITS); val roles = json(c, ROLES); val now = System.currentTimeMillis()
        fun role(m: FamilySync.Member) = if (m.me) st.role else roles.optJSONObject(m.uid)?.optString("r") ?: ""
        val patient = list.filter { role(it) == "patient" }.maxByOrNull { it.seen }
        var hidden = 0
        val out = list.mapNotNull { m ->
            val o = e.optJSONObject(m.uid)
            val rm = o?.optLong("rm") ?: 0L
            if (rm > 0 && m.seen in 1..rm && !m.me) { hidden++; return@mapNotNull null }
            val isP = role(m) == "patient"
            val prot = m === patient
            val active = m.seen > 0 && now - m.seen < INACTIVE_MS
            val why = when { m.me -> "This phone: use Leave"; prot -> "The patient's phone is protected"; active -> "Active in the last 7 days"; else -> "" }
            Row(m, o?.optString("n")?.takeIf { it.isNotBlank() } ?: m.name, isP, prot, why.isEmpty(), why)
        }.sortedWith(compareByDescending<Row> { it.m.me }.thenByDescending { it.locked }.thenByDescending { it.m.seen })
        return out to hidden
    }
}
