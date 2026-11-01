package com.nova.glucose

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Meal(
    val id: Long = 0,
    val ts: Long,
    val title: String,
    val itemsJson: String,
    val carbs: Int,
    val low: Int,
    val high: Int,
    val mgAt: Int,
    val photo: String,
    val note: String
)

/** Incident: kind (e.g. "Flu / cold"), start, end (0 = still ongoing), details, medicines (prescription text), photo path. */
data class Incident(
    val id: Long = 0, val ts: Long, val end: Long = 0, val kind: String, val details: String = "", val meds: String = "", val photo: String = ""
) {
    val ongoing get() = end == 0L
}

/** A logged insulin dose (what the family gave). kind = "rapid" or "long". */
data class Dose(
    val id: Long = 0, val ts: Long, val kind: String, val units: Double,
    val carbs: Int = 0, val waitMin: Int = 0, val mgAt: Int = 0, val note: String = ""
)

/** Readings history (kept 400 days) and the meal logbook, stored only on this phone. */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, "nova_glucose.db", null, 6) {
    private val app: Context = c.applicationContext

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c.applicationContext).also { inst = it } }
        private const val KEEP_MS = 400L * 24 * 3600 * 1000
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings(ts INTEGER PRIMARY KEY, mg INTEGER, trend INTEGER)")
        db.execSQL("CREATE TABLE meals(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, title TEXT, items TEXT, carbs INTEGER, " +
            "low INTEGER, high INTEGER, mg_at INTEGER, photo TEXT, note TEXT)")
        db.execSQL("CREATE TABLE unexported(ts INTEGER PRIMARY KEY)")
        v3(db); v4(db); v5(db); v6(db)
    }

    /** Incidents: illness, hypo treated, sport, sensor issue, doctor visit… with an optional prescription. */
    private fun v6(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS incidents(id INTEGER PRIMARY KEY AUTOINCREMENT, gid TEXT, upd INTEGER, who TEXT, " +
            "ts INTEGER, ts_end INTEGER, kind TEXT, details TEXT, meds TEXT, photo TEXT)")
    }

    fun addIncident(i: Incident, gid: String? = null, upd: Long? = null, share: Boolean = true, who: String? = null): Long {
        val g = gid ?: newGid()
        val id = writableDatabase.insert("incidents", null, incValues(i).apply {
            put("gid", g); put("upd", upd ?: System.currentTimeMillis()); put("who", who ?: Store(app).syncName); put("photo", i.photo) })
        if (share) queue(g, "inc")
        return id
    }

    private fun incValues(i: Incident) = ContentValues().apply {
        put("ts", i.ts); put("ts_end", i.end); put("kind", i.kind); put("details", i.details); put("meds", i.meds)
    }

    /** Edit here (share) or from the family (photo given only when it changed). */
    fun updateIncident(id: Long, i: Incident, upd: Long = System.currentTimeMillis(), share: Boolean = true, photo: String? = i.photo) {
        writableDatabase.update("incidents", incValues(i).apply { put("upd", upd); if (photo != null) put("photo", photo) }, "id = ?", arrayOf(id.toString()))
        if (share) gidOf("incidents", id)?.let { queue(it, "inc") }
    }

    fun deleteIncident(id: Long, share: Boolean = true) {
        val g = gidOf("incidents", id)
        incident(id)?.photo?.takeIf { it.isNotBlank() }?.let { java.io.File(it).delete() }
        writableDatabase.delete("incidents", "id = ?", arrayOf(id.toString()))
        if (g != null) { tombstone(g, System.currentTimeMillis()); if (share) queue(g, "del") }
    }

    private fun incRows(where: String, args: Array<String>): List<Incident> {
        val out = mutableListOf<Incident>()
        readableDatabase.rawQuery("SELECT id, ts, ts_end, kind, details, meds, photo FROM incidents WHERE $where ORDER BY ts DESC", args).use { c ->
            while (c.moveToNext()) out += Incident(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4) ?: "",
                c.getString(5) ?: "", c.getString(6) ?: "")
        }
        return out
    }

    /** Incidents overlapping [from, to] (ongoing ones count up to now). */
    fun incidents(from: Long = 0, to: Long = Long.MAX_VALUE): List<Incident> =
        incRows("ts <= ? AND (ts_end = 0 OR ts_end >= ?)", arrayOf(to.toString(), from.toString()))

    fun incident(id: Long): Incident? = incRows("id = ?", arrayOf(id.toString())).firstOrNull()

    /** Which phone added the entry (family sync). */
    private fun v5(db: SQLiteDatabase) {
        for (t in listOf("meals", "insulin")) try { db.execSQL("ALTER TABLE $t ADD COLUMN who TEXT") } catch (_: Exception) { }
    }

    fun who(t: String, id: Long): String = readableDatabase.rawQuery("SELECT who FROM $t WHERE id = ?", arrayOf(id.toString()))
        .use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }

    /** Family sync: every meal/dose gets a global id (gid) and an edit time (upd); deletions are remembered. */
    private fun v4(db: SQLiteDatabase) {
        for (t in listOf("meals", "insulin")) {
            try { db.execSQL("ALTER TABLE $t ADD COLUMN gid TEXT") } catch (_: Exception) { }
            try { db.execSQL("ALTER TABLE $t ADD COLUMN upd INTEGER") } catch (_: Exception) { }
            db.execSQL("UPDATE $t SET gid = lower(hex(randomblob(16))) WHERE gid IS NULL OR gid = ''")
            db.execSQL("UPDATE $t SET upd = ts WHERE upd IS NULL")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_out(gid TEXT PRIMARY KEY, kind TEXT, q INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_del(gid TEXT PRIMARY KEY, ts INTEGER)")
    }

    private fun newGid() = java.util.UUID.randomUUID().toString().replace("-", "")
    private fun syncing() = Store(app).syncFam.isNotBlank()

    /** Marks an item to be sent to the family (when sync is on) and wakes the sync. */
    fun queue(gid: String, kind: String, kick: Boolean = true) {
        if (!syncing()) return
        writableDatabase.insertWithOnConflict("sync_out", null, ContentValues().apply {
            put("gid", gid); put("kind", kind); put("q", System.nanoTime())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (kick) FamilySync.kick(app)
    }

    /** Pending items: (gid, kind, q). */
    fun outbox(limit: Int = 200): List<Triple<String, String, Long>> {
        val out = mutableListOf<Triple<String, String, Long>>()
        readableDatabase.rawQuery("SELECT gid, kind, q FROM sync_out ORDER BY q LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getLong(2))
        }
        return out
    }

    fun outboxCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_out", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Removes sent items (only if not changed again meanwhile). */
    fun sent(items: List<Triple<String, String, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try { items.forEach { db.delete("sync_out", "gid = ? AND q = ?", arrayOf(it.first, it.third.toString())) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    fun clearOutbox() { writableDatabase.delete("sync_out", null, null) }

    /** Everything on this phone goes to the family (first join). */
    fun queueAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var q = System.nanoTime()
            for ((t, k) in listOf("meals" to "meal", "insulin" to "dose", "incidents" to "inc"))
                db.rawQuery("SELECT gid FROM $t", null).use { c -> while (c.moveToNext()) db.insertWithOnConflict("sync_out", null,
                    ContentValues().apply { put("gid", c.getString(0)); put("kind", k); put("q", q++) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.rawQuery("SELECT gid FROM sync_del", null).use { c -> while (c.moveToNext()) db.insertWithOnConflict("sync_out", null,
                ContentValues().apply { put("gid", c.getString(0)); put("kind", "del"); put("q", q++) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deletedAt(gid: String): Long = readableDatabase.rawQuery("SELECT ts FROM sync_del WHERE gid = ?", arrayOf(gid)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    fun markDeleted(gid: String, ts: Long) = tombstone(gid, ts)
    fun deletedGids(): List<Pair<String, Long>> = readableDatabase.rawQuery("SELECT gid, ts FROM sync_del", null).use { c ->
        val o = mutableListOf<Pair<String, Long>>(); while (c.moveToNext()) o += c.getString(0) to c.getLong(1); o }
    private fun tombstone(gid: String, ts: Long) {
        if (gid.isBlank()) return
        writableDatabase.insertWithOnConflict("sync_del", null, ContentValues().apply { put("gid", gid); put("ts", ts) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun deletedCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_del", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun v3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS insulin(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, units REAL, " +
            "carbs INTEGER, wait_min INTEGER, mg_at INTEGER, note TEXT)")
        try { db.execSQL("ALTER TABLE meals ADD COLUMN ai_items TEXT") } catch (_: Exception) { }
        try { db.execSQL("ALTER TABLE meals ADD COLUMN ai_carbs INTEGER") } catch (_: Exception) { }
    }

    fun addDose(d: Dose, gid: String? = null, upd: Long? = null, share: Boolean = true, who: String? = null): Long {
        val g = gid ?: newGid()
        val id = writableDatabase.insert("insulin", null, doseValues(d).apply { put("gid", g); put("upd", upd ?: System.currentTimeMillis())
            put("who", who ?: Store(app).syncName) })
        if (share) queue(g, "dose")
        return id
    }

    private fun doseValues(d: Dose) = ContentValues().apply {
        put("ts", d.ts); put("kind", d.kind); put("units", d.units); put("carbs", d.carbs)
        put("wait_min", d.waitMin); put("mg_at", d.mgAt); put("note", d.note)
    }

    fun updateDose(id: Long, d: Dose, upd: Long, share: Boolean = false) {
        writableDatabase.update("insulin", doseValues(d).apply { put("upd", upd) }, "id = ?", arrayOf(id.toString()))
        if (share) gidOf("insulin", id)?.let { queue(it, "dose") }
    }

    fun deleteDose(id: Long, share: Boolean = true) {
        val g = gidOf("insulin", id)
        writableDatabase.delete("insulin", "id = ?", arrayOf(id.toString()))
        if (g != null) { tombstone(g, System.currentTimeMillis()); if (share) queue(g, "del") }
    }

    private fun gidOf(t: String, id: Long): String? =
        readableDatabase.rawQuery("SELECT gid FROM $t WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }

    /** Local id, edit time for a gid ("meals" or "insulin"). */
    fun byGid(t: String, gid: String): Pair<Long, Long>? =
        readableDatabase.rawQuery("SELECT id, upd FROM $t WHERE gid = ?", arrayOf(gid)).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }

    fun gid(t: String, id: Long): String = gidOf(t, id) ?: ""

    /** Gives a row a gid received from the family (same entry already here, e.g. after a restore). */
    fun adoptGid(t: String, id: Long, gid: String) { writableDatabase.update(t, ContentValues().apply { put("gid", gid) }, "id = ?", arrayOf(id.toString())) }

    fun findDose(ts: Long, kind: String, units: Double): Long? =
        readableDatabase.rawQuery("SELECT id FROM insulin WHERE ts = ? AND kind = ? AND abs(units - ?) < 0.001", arrayOf(ts.toString(), kind, units.toString()))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    fun findMeal(ts: Long, title: String): Long? =
        readableDatabase.rawQuery("SELECT id FROM meals WHERE ts = ? AND title = ?", arrayOf(ts.toString(), title)).use { if (it.moveToFirst()) it.getLong(0) else null }

    fun dose(id: Long): Dose? = readableDatabase.rawQuery("SELECT id, ts, kind, units, carbs, wait_min, mg_at, note FROM insulin WHERE id = ?",
        arrayOf(id.toString())).use { c -> if (c.moveToFirst()) Dose(c.getLong(0), c.getLong(1), c.getString(2) ?: "rapid", c.getDouble(3),
            c.getInt(4), c.getInt(5), c.getInt(6), c.getString(7) ?: "") else null }

    fun meal(id: Long): Meal? = readableDatabase.rawQuery("SELECT id, ts, title, items, carbs, low, high, mg_at, photo, note FROM meals WHERE id = ?",
        arrayOf(id.toString())).use { c -> if (c.moveToFirst()) Meal(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "[]",
            c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getString(8) ?: "", c.getString(9) ?: "") else null }

    fun upd(t: String, id: Long): Long = readableDatabase.rawQuery("SELECT upd FROM $t WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun doses(from: Long = 0, to: Long = Long.MAX_VALUE): List<Dose> {
        val out = mutableListOf<Dose>()
        readableDatabase.rawQuery("SELECT id, ts, kind, units, carbs, wait_min, mg_at, note FROM insulin WHERE ts >= ? AND ts <= ? ORDER BY ts DESC",
            arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += Dose(c.getLong(0), c.getLong(1), c.getString(2) ?: "rapid", c.getDouble(3),
                c.getInt(4), c.getInt(5), c.getInt(6), c.getString(7) ?: "")
        }
        return out
    }

    fun hasDoseAt(ts: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM insulin WHERE ts = ?", arrayOf(ts.toString())).use { it.moveToFirst() }

    /** AI's original estimate for a meal, saved for the family's corrections (training). */
    fun setAi(id: Long, itemsJson: String, carbs: Int, share: Boolean = true, upd: Long? = null) {
        writableDatabase.update("meals", ContentValues().apply {
            put("ai_items", itemsJson); put("ai_carbs", carbs); put("upd", upd ?: System.currentTimeMillis())
        }, "id = ?", arrayOf(id.toString()))
        if (share) gidOf("meals", id)?.let { queue(it, "meal") }
    }

    fun ai(id: Long): Pair<String, Int>? =
        readableDatabase.rawQuery("SELECT ai_items, ai_carbs FROM meals WHERE id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) to it.getInt(1) else null
        }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS unexported(ts INTEGER PRIMARY KEY)")
            db.execSQL("INSERT OR IGNORE INTO unexported SELECT ts FROM readings")
        }
        if (o < 3) v3(db)
        if (o < 4) v4(db)
        if (o < 5) v5(db)
        if (o < 6) v6(db)
    }

    /** New readings not yet written to the Download folder (also catches late, back-filled ones). */
    fun unexported(): List<Reading> {
        val out = mutableListOf<Reading>()
        readableDatabase.rawQuery("SELECT r.ts, r.mg, r.trend FROM readings r JOIN unexported u ON u.ts = r.ts ORDER BY r.ts", null).use { c ->
            while (c.moveToNext()) out += Reading(c.getLong(0), c.getInt(1), c.getInt(2))
        }
        return out
    }

    fun markExported(list: List<Reading>) {
        val db = writableDatabase
        db.beginTransaction()
        try { list.forEach { db.delete("unexported", "ts = ?", arrayOf(it.ts.toString())) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    fun addReadings(list: List<Reading>, markNew: Boolean = true) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in list) {
                val id = db.insertWithOnConflict("readings", null, ContentValues().apply {
                    put("ts", r.ts); put("mg", r.mg); put("trend", r.trend)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (id != -1L && markNew) db.insertWithOnConflict("unexported", null,
                    ContentValues().apply { put("ts", r.ts) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.delete("readings", "ts < ?", arrayOf((System.currentTimeMillis() - KEEP_MS).toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Self-test only (blank install). */
    fun clearAll() { writableDatabase.apply { delete("readings", null, null); delete("meals", null, null); delete("insulin", null, null); delete("unexported", null, null)
        delete("sync_out", null, null); delete("sync_del", null, null); delete("incidents", null, null) } }

    fun resetExport() { writableDatabase.execSQL("INSERT OR IGNORE INTO unexported SELECT ts FROM readings") }

    fun readings(from: Long, to: Long = Long.MAX_VALUE): List<Reading> {
        val out = mutableListOf<Reading>()
        readableDatabase.rawQuery("SELECT ts, mg, trend FROM readings WHERE ts >= ? AND ts <= ? ORDER BY ts",
            arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += Reading(c.getLong(0), c.getInt(1), c.getInt(2))
        }
        return out
    }

    /** The reading closest to [t] (within 20 minutes), or null. */
    fun near(t: Long): Reading? = readings(t - 20 * 60_000, t + 20 * 60_000).minByOrNull { kotlin.math.abs(it.ts - t) }

    fun addMeal(m: Meal, gid: String? = null, upd: Long? = null, share: Boolean = true, who: String? = null): Long {
        val g = gid ?: newGid()
        val id = writableDatabase.insert("meals", null, mealValues(m).apply { put("photo", m.photo); put("gid", g); put("upd", upd ?: System.currentTimeMillis())
            put("who", who ?: Store(app).syncName) })
        if (share) queue(g, "meal")
        return id
    }

    private fun mealValues(m: Meal) = ContentValues().apply {
        put("ts", m.ts); put("title", m.title); put("items", m.itemsJson); put("carbs", m.carbs)
        put("low", m.low); put("high", m.high); put("mg_at", m.mgAt); put("note", m.note)
    }

    /** Update from the family (the local photo is kept). */
    fun updateMeal(id: Long, m: Meal, upd: Long, share: Boolean = false) {
        writableDatabase.update("meals", mealValues(m).apply { put("upd", upd) }, "id = ?", arrayOf(id.toString()))
        if (share) gidOf("meals", id)?.let { queue(it, "meal") }
    }

    fun clearAi(id: Long) { writableDatabase.update("meals", ContentValues().apply { putNull("ai_items"); putNull("ai_carbs") }, "id = ?", arrayOf(id.toString())) }

    fun hasMealAt(ts: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM meals WHERE ts = ?", arrayOf(ts.toString())).use { it.moveToFirst() }

    fun deleteMeal(id: Long, share: Boolean = true) {
        val g = gidOf("meals", id)
        writableDatabase.delete("meals", "id = ?", arrayOf(id.toString()))
        if (g != null) { tombstone(g, System.currentTimeMillis()); if (share) queue(g, "del") }
    }

    /** Applies a deletion received from the family; returns the photo path of a deleted meal (to remove it). */
    fun applyDelete(gid: String, ts: Long): Boolean {
        var hit = false
        for (t in listOf("meals", "insulin", "incidents")) byGid(t, gid)?.let { (id, _) ->
            if (t == "incidents") incident(id)?.photo?.takeIf { it.isNotBlank() }?.let { java.io.File(it).delete() }
            writableDatabase.delete(t, "id = ?", arrayOf(id.toString())); hit = true }
        tombstone(gid, ts)
        return hit
    }

    fun meals(limit: Int = 200): List<Meal> {
        val out = mutableListOf<Meal>()
        readableDatabase.rawQuery("SELECT id, ts, title, items, carbs, low, high, mg_at, photo, note FROM meals ORDER BY ts DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += Meal(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "[]",
                c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getString(8) ?: "", c.getString(9) ?: "")
        }
        return out
    }
}
