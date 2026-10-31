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

/** Readings history (kept 30 days) and the meal logbook, stored only on this phone. */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, "nova_glucose.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c.applicationContext).also { inst = it } }
        private const val KEEP_MS = 30L * 24 * 3600 * 1000
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings(ts INTEGER PRIMARY KEY, mg INTEGER, trend INTEGER)")
        db.execSQL("CREATE TABLE meals(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, title TEXT, items TEXT, carbs INTEGER, " +
            "low INTEGER, high INTEGER, mg_at INTEGER, photo TEXT, note TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun addReadings(list: List<Reading>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in list) db.insertWithOnConflict("readings", null, ContentValues().apply {
                put("ts", r.ts); put("mg", r.mg); put("trend", r.trend)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("readings", "ts < ?", arrayOf((System.currentTimeMillis() - KEEP_MS).toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

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

    fun addMeal(m: Meal): Long = writableDatabase.insert("meals", null, ContentValues().apply {
        put("ts", m.ts); put("title", m.title); put("items", m.itemsJson); put("carbs", m.carbs)
        put("low", m.low); put("high", m.high); put("mg_at", m.mgAt); put("photo", m.photo); put("note", m.note)
    })

    fun deleteMeal(id: Long) { writableDatabase.delete("meals", "id = ?", arrayOf(id.toString())) }

    fun meals(limit: Int = 200): List<Meal> {
        val out = mutableListOf<Meal>()
        readableDatabase.rawQuery("SELECT id, ts, title, items, carbs, low, high, mg_at, photo, note FROM meals ORDER BY ts DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += Meal(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "[]",
                c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getString(8) ?: "", c.getString(9) ?: "")
        }
        return out
    }
}
