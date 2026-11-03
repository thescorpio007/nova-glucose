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
            // 0. Carbs rules: all zeros = correction, empty = meal with carbs not recorded, never 0 g
            for (z in listOf("0", "00", "0000")) check(Carbs.parse(z) == Carbs.CORRECTION) { "'$z' is not a correction" }
            check(Carbs.parse("") == Carbs.UNKNOWN && Carbs.parse(" ") == Carbs.UNKNOWN) { "empty carbs not 'unknown'" }
            check(Carbs.parse("45") == 45 && Carbs.parse("abc") == null && Carbs.parse("900") == null) { "carbs number rule" }
            val um = Meal(0, 1, "x", "[]", Carbs.UNKNOWN, -1, -1, 0, "", "")
            check(Carbs.sum(listOf(um, um.copy(carbs = 30))) == (30 to 1)) { "unknown meal counted as grams" }
            check(Carbs.ai(um) == "meal eaten, carbs not recorded" && um.carbText == "? g") { "unknown meal text" }
            check(!Sounds.patientMay(st, "urgent_low") && !Sounds.patientMay(st, "low") && !Sounds.patientMay(st, "very_high") &&
                Sounds.patientMay(st, "eat") && Sounds.patientMay(st, "tip")) { "locked notification lines" }
            // 1. Fill settings, secrets and data
            st.email = "test@example.com"; st.password = "LluPass!1"
            Providers.all.forEachIndexed { k, p -> st.setKey(p, "KEY-${p.id}-$k") }
            st.longName = "Lantus SoloStar"; st.longDose = "18"; st.longTime = "20:30"; st.rapidName = "NovoRapid"
            st.carbRatio = 12; st.corrFactor = 50; st.targetMg = 110; st.age = 11; st.gender = "girl"; st.aboutHer = "swims Mon/Wed"
            st.low = 72; st.high = 190; st.urgNoSignal = true; st.alarmVolume = 70; st.staleMin = 30; st.ringSec = 30
            st.mealMin = 40; st.foodNotes = "no nuts"; st.aiProvider = "claude"; st.chartRange = "24"; st.driveFreq = "3"
            st.patientName = "Test Child"; st.backupPw = pw
            // a chosen sound file and notification choices
            val sndDir = java.io.File(c.filesDir, "sounds").apply { mkdirs() }
            val snd = java.io.File(sndDir, "eat_test.mp3").apply { writeBytes(ByteArray(4000) { (it % 97).toByte() }) }
            st.setSound("eat", android.net.Uri.fromFile(snd).toString(), "My eat sound"); st.setSound("tip", Sounds.SILENT, "Silent")
            st.setSound("low", "builtin:medical", "Nova Medical"); st.setVib("tip", false); st.setPop("other", false)
            val now = System.currentTimeMillis()
            db.addReadings((0 until 50).map { Reading(now - it * 300_000L, 100 + it, 3) })
            val mid = db.addMeal(Meal(0, now - 3600_000L, "Couscous", "[{\"name\":\"Couscous\",\"portion\":\"1 cup\",\"carbs\":45}]", 45, 40, 50, 140, "", ""))
            db.setAi(mid, "[{\"name\":\"Couscous\",\"portion\":\"1 cup\",\"carbs\":40}]", 40)
            db.addDose(Dose(0, now - 3500_000L, "rapid", 4.5, 45, 15, 140, "calc: 45÷12 = 3.8"))
            db.addDose(Dose(0, now - 20 * 3600_000L, "long", 18.0))
            val wd = WatchDay(WatchData.dayKey(now), IntArray(24) { 100 * it }, IntArray(288) { if (it % 2 == 0) 80 else 0 }, listOf(Seg(now - 9 * 3600_000L, now - 8 * 3600_000L, 5)),
                listOf(Ex(now - 4 * 3600_000L, now - 3 * 3600_000L, 73, "Swimming")), 62, 96.5)
            db.putWatch(wd.day, wd.json(), now, share = false)
            db.addBody(now - 86_400_000L, 157.5, 45.9, "manual", share = false)
            db.addRecipe(Db.Recipe(0, now, "Chakchouka", "Dinner", "Tunisian", 28, 25, 4, "eggs, tomato", FoodAi.Recipe("Chakchouka", 4, 25, 28, "",
                listOf("8 eggs", "4 tomatoes"), listOf("Cook the sauce", "Add the eggs"), "").json()))

            // 2. Backup + verify
            val file = Backup.pack(c, pw)
            check(Backup.isEncrypted(file)) { "file not encrypted" }
            val raw = String(file, Charsets.ISO_8859_1)
            check(!raw.contains("Lantus") && !raw.contains("LluPass") && !raw.contains("test@example.com")) { "plain text visible inside the file" }
            val v = Backup.verifyFile(file, c, pw)
            val wrong = try { Backup.unpack(file, "wrong-password"); "accepted" } catch (e: Exception) { e.message ?: "refused" }
            check(wrong.contains("Wrong backup password")) { "wrong password was not refused: $wrong" }
            val corrupt = file.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
            val bad = try { Backup.unpack(corrupt, pw); "accepted" } catch (e: Exception) { "refused" }
            check(bad == "refused") { "damaged file was accepted" }

            // 3. Wipe everything (fresh phone)
            st.raw.edit().clear().commit(); db.clearAll(); snd.delete()
            check(st.longName.isBlank() && st.password.isBlank() && db.readings(0).isEmpty()) { "wipe failed" }

            // 4. Restore + compare
            val r = Backup.restoreFile(c, file, pw)
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
            eq("eat sound file", java.io.File(android.net.Uri.parse(st.soundFor("eat")).path!!).readBytes().size, 4000)
            eq("eat sound name", st.soundNameFor("eat"), "My eat sound"); eq("tip silent", st.soundFor("tip"), Sounds.SILENT)
            eq("low sound", st.soundFor("low"), "builtin:medical"); eq("tip vibrate", st.vibFor("tip"), false); eq("other pop-up", st.popFor("other"), false)
            eq("readings", db.readings(0).size, 50); eq("meals", db.meals(10).size, 1); eq("doses", db.doses().size, 2)
            eq("AI estimate", db.ai(db.meals(1).first().id)?.second, 40)
            eq("calc note", db.doses().first { it.kind == "rapid" }.note, "calc: 45÷12 = 3.8")
            val w2 = WatchData.get(c, WatchData.dayStart(now))
            eq("watch steps", w2.totalSteps, 27600); eq("watch RHR", w2.rhr, 62); eq("watch sport", w2.ex.firstOrNull()?.title, "Swimming")
            eq("height", st.heightCm, 157.5); eq("weight", st.weightKg, 45.9); eq("growth entries", db.bodies().size, 1)
            eq("recipe", db.recipes().firstOrNull()?.title, "Chakchouka"); eq("recipe steps", FoodAi.Recipe.from(db.recipes().first()).steps.size, 2)
            return "PASS · $v · carbs rules (0 = correction, empty = not recorded) ✓ · watch data, height/weight and recipe book ✓ · sounds and notification choices ✓ · wrong password refused ✓ · damaged file refused ✓ · no readable text inside ✓ · restore: $r"
        } catch (e: Throwable) {
            return "FAIL · ${e.message}"
        } finally {
            st.raw.edit().clear().commit(); db.clearAll()     // leave the install blank again
        }
    }

    fun log(c: Context) { Log.i("NOVA_SELFTEST", run(c)) }
}
