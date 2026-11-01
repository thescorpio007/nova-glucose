package com.nova.glucose

import android.content.Context
import android.util.Log

/**
 * Backup round-trip self-test, used by the automatic build test. It only runs on a BLANK install
 * (no LibreLinkUp login and no readings), so it can never touch real data.
 */
object SelfTest {
    fun canRun(c: Context) = !Store(c).loggedIn && Store(c).email.isBlank() && Db.get(c).readings(0).isEmpty() && Db.get(c).meals(1).isEmpty()

    fun run(c: Context): String {
        if (!canRun(c)) return "SKIPPED (not a blank install)"
        val st = Store(c); val db = Db.get(c)
        val pw = "Backup#2026"
        try {
            // 1. Fill settings, secrets and data
            st.email = "test@example.com"; st.password = "LluPass!1"
            Providers.all.forEachIndexed { k, p -> st.setKey(p, "KEY-${p.id}-$k") }
            st.longName = "Lantus SoloStar"; st.longDose = "18"; st.longTime = "20:30"; st.rapidName = "NovoRapid"
            st.carbRatio = 12; st.corrFactor = 50; st.targetMg = 110; st.age = 11; st.gender = "girl"; st.aboutHer = "swims Mon/Wed"
            st.low = 72; st.high = 190; st.urgNoSignal = true; st.alarmVolume = 70; st.staleMin = 30; st.ringSec = 30
            st.mealMin = 40; st.foodNotes = "no nuts"; st.aiProvider = "claude"; st.chartRange = "24"; st.driveFreq = "3"
            st.patientName = "Test Child"; st.backupPw = pw
            val now = System.currentTimeMillis()
            db.addReadings((0 until 50).map { Reading(now - it * 300_000L, 100 + it, 3) })
            val mid = db.addMeal(Meal(0, now - 3600_000L, "Couscous", "[{\"name\":\"Couscous\",\"portion\":\"1 cup\",\"carbs\":45}]", 45, 40, 50, 140, "", ""))
            db.setAi(mid, "[{\"name\":\"Couscous\",\"portion\":\"1 cup\",\"carbs\":40}]", 40)
            db.addDose(Dose(0, now - 3500_000L, "rapid", 4.5, 45, 15, 140, "calc: 45÷12 = 3.8"))
            db.addDose(Dose(0, now - 20 * 3600_000L, "long", 18.0))

            // 2. Backup + verify
            val text = Backup.build(c, pw)
            val v = Backup.verify(text, c, pw)

            // 3. Wipe everything (fresh phone)
            st.raw.edit().clear().commit(); db.clearAll()
            check(st.longName.isBlank() && st.password.isBlank() && db.readings(0).isEmpty()) { "wipe failed" }

            // 4. Restore + compare
            val r = Backup.restore(c, text, pw)
            fun eq(name: String, a: Any?, b: Any?) = check(a == b) { "$name: expected $b, got $a" }
            eq("email", st.email, "test@example.com"); eq("password", st.password, "LluPass!1")
            Providers.all.forEachIndexed { k, p -> eq("${p.label} key", st.key(p), "KEY-${p.id}-$k") }
            eq("long name", st.longName, "Lantus SoloStar"); eq("long dose", st.longDose, "18"); eq("long time", st.longTime, "20:30")
            eq("rapid name", st.rapidName, "NovoRapid"); eq("ratio", st.carbRatio, 12); eq("correction", st.corrFactor, 50)
            eq("target", st.targetMg, 110); eq("age", st.age, 11); eq("gender", st.gender, "girl"); eq("about", st.aboutHer, "swims Mon/Wed")
            eq("low", st.low, 72); eq("high", st.high, 190); eq("no-signal alarm", st.urgNoSignal, true); eq("volume", st.alarmVolume, 70)
            eq("no signal min", st.staleMin, 30); eq("ring", st.ringSec, 30); eq("meal min", st.mealMin, 40); eq("food notes", st.foodNotes, "no nuts")
            eq("AI", st.aiProvider, "claude"); eq("chart", st.chartRange, "24"); eq("drive freq", st.driveFreq, "3")
            eq("backup password", st.backupPw, pw)
            eq("readings", db.readings(0).size, 50); eq("meals", db.meals(10).size, 1); eq("doses", db.doses().size, 2)
            eq("AI estimate", db.ai(db.meals(1).first().id)?.second, 40)
            eq("calc note", db.doses().first { it.kind == "rapid" }.note, "calc: 45÷12 = 3.8")
            return "PASS · $v · restore: $r"
        } catch (e: Throwable) {
            return "FAIL · ${e.message}"
        } finally {
            st.raw.edit().clear().commit(); db.clearAll()     // leave the install blank again
        }
    }

    fun log(c: Context) { Log.i("NOVA_SELFTEST", run(c)) }
}
