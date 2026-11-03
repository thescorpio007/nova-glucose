package com.nova.glucose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads her Huawei watch data from Android Health Connect (Huawei Health → Health Sync → Health Connect)
 * on the phone paired with the watch (Android 14 or newer). The days are stored and shared with the family.
 */
object HealthRead {
    const val REQ = 4711
    val PERMS = arrayOf(
        "android.permission.health.READ_STEPS", "android.permission.health.READ_EXERCISE", "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_HEART_RATE", "android.permission.health.READ_RESTING_HEART_RATE",
        "android.permission.health.READ_OXYGEN_SATURATION", "android.permission.health.READ_HEIGHT", "android.permission.health.READ_WEIGHT")
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /** Health Connect is part of Android 14+. */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= 34

    fun granted(c: Context): List<String> = if (!supported()) emptyList() else
        PERMS.filter { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun background(c: Context) = supported() && c.checkSelfPermission(BACKGROUND) == PackageManager.PERMISSION_GRANTED

    /** Asks for the permissions (Health Connect's own screen). */
    fun ask(a: Activity) {
        if (!supported()) { Ui.toast(a, "Reading the watch needs Android 14 or newer"); return }
        a.requestPermissions(PERMS + BACKGROUND, REQ)
    }

    /** Health Connect's settings (to change what is shared). */
    fun openSettings(a: Activity) {
        for (act in listOf("android.health.connect.action.HEALTH_HOME_SETTINGS", "android.settings.HEALTH_CONNECT_SETTINGS",
            "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")) {
            try { a.startActivity(Intent(act)); return } catch (_: Exception) { }
        }
        Ui.toast(a, "Open Settings → Health Connect on the phone")
    }

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Read now if due (every 15 minutes) — from the monitor and when the app opens. */
    fun maybe(c: Context, force: Boolean = false) {
        val st = Store(c)
        if (!supported() || st.isFamily || !st.hcOn || granted(c).isEmpty()) return
        if (!force && System.currentTimeMillis() - st.hcLastRead < 15 * 60_000L) return
        val app = c.applicationContext
        Thread { try { sync(app, if (st.hcLastRead == 0L) 30 else 2) } catch (_: Exception) { } }.start()
    }

    /** Blocking: reads the last [days] days. Returns a short status. */
    fun sync(c: Context, days: Int): String {
        if (!supported()) return "Needs Android 14 or newer"
        if (!running.compareAndSet(false, true)) return "Reading…"
        try { return Api34.sync(c, days) } finally { running.set(false) }
    }

    @RequiresApi(34)
    private object Api34 {
        private val exec = Executors.newSingleThreadExecutor()

        private fun <T : android.health.connect.datatypes.Record> read(m: android.health.connect.HealthConnectManager, cls: Class<T>, from: Instant, to: Instant): List<T> {
            val out = ArrayList<T>()
            var token = -1L
            var pages = 0
            do {
                val b = android.health.connect.ReadRecordsRequestUsingFilters.Builder(cls)
                    .setTimeRangeFilter(android.health.connect.TimeInstantRangeFilter.Builder().setStartTime(from).setEndTime(to).build())
                    .setPageSize(2000)
                if (token != -1L) b.setPageToken(token)
                val latch = CountDownLatch(1)
                var res: android.health.connect.ReadRecordsResponse<T>? = null
                var err: Exception? = null
                m.readRecords(b.build(), exec, object : OutcomeReceiver<android.health.connect.ReadRecordsResponse<T>, android.health.connect.HealthConnectException> {
                    override fun onResult(r: android.health.connect.ReadRecordsResponse<T>) { res = r; latch.countDown() }
                    override fun onError(e: android.health.connect.HealthConnectException) { err = e; latch.countDown() }
                })
                if (!latch.await(40, TimeUnit.SECONDS)) throw Exception("Health Connect did not answer")
                err?.let { throw it }
                val r = res ?: break
                out.addAll(r.records)
                token = r.nextPageToken
                pages++
            } while (token != -1L && pages < 30)
            return out
        }

        private fun origin(r: android.health.connect.datatypes.Record) = try { r.metadata.dataOrigin.packageName ?: "" } catch (_: Exception) { "" }

        /** The watch's app first (Health Sync / Huawei), so steps counted by the phone too are not added twice. */
        private fun <T : android.health.connect.datatypes.Record> best(l: List<T>): List<T> {
            if (l.isEmpty()) return l
            val by = l.groupBy { origin(it) }
            val pick = by.keys.firstOrNull { it.contains("healthsync", true) } ?: by.keys.firstOrNull { it.contains("huawei", true) }
                ?: by.maxByOrNull { it.value.size }!!.key
            return by[pick] ?: l
        }

        fun sync(c: Context, days: Int): String {
            val st = Store(c); val db = Db.get(c)
            val m = c.getSystemService(android.health.connect.HealthConnectManager::class.java) ?: return "Health Connect not found"
            val ok = granted(c).toSet()
            if (ok.isEmpty()) return "No permission"
            val now = System.currentTimeMillis()
            val first = WatchData.addDays(WatchData.dayStart(now), -(days - 1))
            val from = Instant.ofEpochMilli(first - 24 * 3600_000L); val to = Instant.ofEpochMilli(now)
            val got = mutableSetOf<String>(); val src = mutableSetOf<String>()
            fun <T : android.health.connect.datatypes.Record> rd(p: String, cls: Class<T>): List<T> =
                if ("android.permission.health.$p" !in ok) emptyList() else try { best(read(m, cls, from, to)).also { l -> l.forEach { src += origin(it) } } } catch (_: Exception) { emptyList() }

            val steps = rd("READ_STEPS", android.health.connect.datatypes.StepsRecord::class.java)
            val hr = rd("READ_HEART_RATE", android.health.connect.datatypes.HeartRateRecord::class.java)
            val sleep = rd("READ_SLEEP", android.health.connect.datatypes.SleepSessionRecord::class.java)
            val ex = rd("READ_EXERCISE", android.health.connect.datatypes.ExerciseSessionRecord::class.java)
            val rhr = rd("READ_RESTING_HEART_RATE", android.health.connect.datatypes.RestingHeartRateRecord::class.java)
            val spo2 = rd("READ_OXYGEN_SATURATION", android.health.connect.datatypes.OxygenSaturationRecord::class.java)
            if (steps.isNotEmpty()) got += "steps"; if (hr.isNotEmpty()) got += "hr"; if (sleep.isNotEmpty()) got += "sleep"
            if (ex.isNotEmpty()) got += "ex"; if (rhr.isNotEmpty()) got += "rhr"; if (spo2.isNotEmpty()) got += "spo2"

            // sleep pieces (a session without stages counts as "asleep")
            val segs = sleep.flatMap { s ->
                val stg = try { s.stages } catch (_: Exception) { emptyList() }
                if (stg.isNullOrEmpty()) listOf(Seg(s.startTime.toEpochMilli(), s.endTime.toEpochMilli(), 2))
                else stg.map { Seg(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.type) }
            }
            var newest = 0L
            var changed = 0
            for (k in 0 until days) {
                val a = WatchData.addDays(first, k); val b = WatchData.addDays(a, 1)
                val stepArr = IntArray(24)
                for (r in steps) {
                    val s0 = r.startTime.toEpochMilli(); val s1 = maxOf(r.endTime.toEpochMilli(), s0 + 1)
                    if (s1 <= a || s0 >= b) continue
                    val n = r.count.toDouble(); val len = (s1 - s0).toDouble()
                    var t = maxOf(s0, a)
                    while (t < minOf(s1, b)) {
                        val h = ((t - a) / 3600_000L).toInt().coerceIn(0, 23)
                        val hEnd = minOf(a + (h + 1) * 3600_000L, s1, b)
                        stepArr[h] += Math.round(n * (hEnd - t) / len).toInt()
                        t = hEnd
                    }
                    newest = maxOf(newest, s1)
                }
                val sums = IntArray(288); val cnt = IntArray(288)
                for (r in hr) for (smp in r.samples) {
                    val t = smp.time.toEpochMilli(); if (t < a || t >= b) continue
                    val i = ((t - a) / 300_000L).toInt().coerceIn(0, 287); sums[i] += smp.beatsPerMinute.toInt(); cnt[i]++
                    newest = maxOf(newest, t)
                }
                val hrArr = IntArray(288) { if (cnt[it] > 0) sums[it] / cnt[it] else 0 }
                val daySegs = segs.filter { it.a in a until b }.distinct().sortedBy { it.a }
                val dayEx = ex.filter { it.startTime.toEpochMilli() in a until b }.map {
                    Ex(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.exerciseType, (it.title ?: "").toString()) }
                val r = rhr.filter { it.time.toEpochMilli() in a until b }.maxByOrNull { it.time }?.beatsPerMinute?.toInt() ?: 0
                val o = spo2.filter { it.time.toEpochMilli() in a until b }.map { it.percentage.value }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
                val w = WatchDay(WatchData.dayKey(a), stepArr, hrArr, daySegs, dayEx, r, o)
                if (w.isEmpty() && db.watchDay(w.day) == null) continue
                if (db.putWatch(w.day, w.json(), System.currentTimeMillis(), share = true, force = true)) changed++
            }
            segs.maxOfOrNull { it.b }?.let { newest = maxOf(newest, it) }

            // height and weight into the growth history
            var nb = 0
            if (st.hcBody) {
                val hs = rd("READ_HEIGHT", android.health.connect.datatypes.HeightRecord::class.java)
                val ws = rd("READ_WEIGHT", android.health.connect.datatypes.WeightRecord::class.java)
                val byTs = HashMap<Long, Pair<Double, Double>>()
                hs.forEach { val t = it.time.toEpochMilli(); byTs[t] = (it.height.inMeters * 100) to (byTs[t]?.second ?: 0.0) }
                ws.forEach { val t = it.time.toEpochMilli(); byTs[t] = (byTs[t]?.first ?: 0.0) to (it.weight.inGrams / 1000.0) }
                byTs.forEach { (t, v) ->
                    if (db.findBody(t) == null) { db.addBody(t, Math.round(v.first * 10) / 10.0, Math.round(v.second * 10) / 10.0, "watch"); nb++ }
                }
            }
            if (got.isNotEmpty()) st.hcGot = got.joinToString(",")
            st.hcSources = src.filter { it.isNotBlank() }.joinToString(", ")
            if (newest > st.hcLastData) st.hcLastData = newest
            st.hcLastRead = System.currentTimeMillis()
            st.hcInfo = if (got.isEmpty()) "No watch data found in Health Connect yet" else "✓ $changed day(s) updated" + if (nb > 0) " · $nb height/weight" else ""
            if (changed > 0 || nb > 0) FamilySync.kick(c)
            return st.hcInfo
        }
    }
}
