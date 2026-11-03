package com.nova.glucose

import android.content.Context

/** Sample day for the automatic screenshots (blank test install only — never on a phone in use). */
object Demo {
    fun fill(c: Context) {
        val db = Db.get(c); val st = Store(c)
        val now = System.currentTimeMillis()
        st.rapidName = "NovoRapid"; st.longName = "Lantus"
        db.addReadings((0..144).map { k ->
            val t = now - (144 - k) * 300_000L
            val h = k / 12.0
            Reading(t, (95 + 15 * h + 25 * kotlin.math.sin(h * 1.3) + if (h > 9) (h - 9) * 20 else 0.0).toInt().coerceIn(60, 320), 3)
        })
        val m1 = now - 5 * 3600_000L; val m2 = now - 70 * 60_000L
        val a = db.addMeal(Meal(0, m1, "Msemen with cheese and tea",
            "[{\"name\":\"Msemen\",\"portion\":\"1 piece\",\"carbs\":40},{\"name\":\"Tea with sugar\",\"portion\":\"1 cup\",\"carbs\":12}]",
            52, 45, 58, 178, "", ""), who = "Mum's phone")
        db.setAi(a, "[{\"name\":\"Msemen\",\"portion\":\"1 piece\",\"carbs\":35},{\"name\":\"Tea with sugar\",\"portion\":\"1 cup\",\"carbs\":10}]", 45)
        db.addDose(Dose(0, m1 - 13 * 60_000L, "rapid", 4.0, 52, 15, 170, "calc: 52 ÷ 12 = 4.3"), who = "Dad's phone")
        val b = db.addMeal(Meal(0, m2, "Couscous with chicken", "[{\"name\":\"Couscous with chicken\",\"portion\":\"1 plate\",\"carbs\":60}]",
            60, 55, 65, 296, "", "school lunch"), who = "Patient's phone")
        db.setAi(b, "[{\"name\":\"Couscous with chicken\",\"portion\":\"1 plate\",\"carbs\":60}]", 60)
        db.addDose(Dose(0, m2 - 8 * 60_000L, "rapid", 5.5, 60, 10, 290, "entered later"), who = "Patient's phone")
        db.addDose(Dose(0, now - 19 * 3600_000L, "long", 18.0, note = ""), who = "Dad's phone")
        // a correction only (carbs "0") and a meal whose carbs were not recorded (carbs left empty)
        db.addDose(Dose(0, now - 3 * 3600_000L, "rapid", 1.5, Carbs.CORRECTION, 0, 245, ""), who = "Mum's phone")
        val u = now - 8 * 3600_000L
        db.addDose(Dose(0, u - 10 * 60_000L, "rapid", 3.0, Carbs.UNKNOWN, 10, 150, "entered later"), who = "Patient's phone")
        db.addMeal(Meal(0, u, "Birthday cake at school", "[]", Carbs.UNKNOWN, Carbs.UNKNOWN, Carbs.UNKNOWN, 150, "", ""), who = "Patient's phone")
        db.addMeal(Meal(0, now - 20 * 3600_000L, "Rice, salad and fish", "[]", 55, 50, 60, 150, "", ""), who = "Mum's phone")
        // A sick day with a (drawn) prescription
        val bmp = android.graphics.Bitmap.createBitmap(700, 900, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp); cv.drawColor(0xFFF3EFE4.toInt())
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F3A68.toInt(); textSize = 34f }
        listOf("Dr. Sample Clinic", "1 Nov 2026", "", "Rx", "1. Paracetamol 250 mg", "   1 dose x 3 / day - 3 days", "2. Saline nasal spray", "   2 x / day - 5 days", "", "   signature")
            .forEachIndexed { k, t -> cv.drawText(t, 50f, 90f + k * 70f, p) }
        val photo = Incidents.savePhoto(c, Incidents.compress(bmp), "rx_demo")
        db.addIncident(Incident(0, now - 9 * 3600_000L, 0, "Flu / cold", "Fever 38.4 °C, no appetite, tired",
            "• Paracetamol 250 mg · 1 dose · 3 times a day · 3 days\n• Saline nasal spray · 2 times a day · 5 days\nDoctor: Sample Clinic · Date: 1 Nov 2026", photo), who = "Mum's phone")
    }

    /** 14 days of glucose with her watch data (steps, sport, sleep, heart rate), height/weight and a few saved recipes. */
    fun watch(c: Context) {
        val db = Db.get(c); val st = Store(c)
        st.rapidName = "NovoRapid"; st.longName = "Tresiba"; st.age = 14; st.gender = "girl"; st.foodNotes = "no nuts"
        val now = System.currentTimeMillis()
        val today = WatchData.dayStart(now)
        val rnd = java.util.Random(7)
        val sportDays = setOf(0, 2, 5, 9, 12); val shortNights = setOf(1, 4, 8, 11); val fever = setOf(6, 7)
        fun bump(h: Double, at: Double, top: Double, w: Double) = if (h > at) top * kotlin.math.exp(-((h - at - w) * (h - at - w)) / (w * w)) else 0.0
        val rs = ArrayList<Reading>()
        for (k in 13 downTo 0) {
            val d0 = WatchData.addDays(today, -k)
            val sport = k in sportDays; val short = k in shortNights; val sick = k in fever
            val steps = IntArray(24); val hr = IntArray(288)
            for (m in 0 until 288) {
                val h = m / 12.0; val t = d0 + m * 300_000L
                if (t > now) break
                var g = 118.0 + (if (short) 30.0 else 0.0) * (if (h in 5.0..9.0) 1.0 else 0.3) + (if (sick) 55.0 else 0.0)
                g += bump(h, 7.5, 85.0, 1.0) + bump(h, 13.0, 65.0, 1.0) + bump(h, 20.0, 75.0, 1.0) + rnd.nextGaussian() * 4
                if (sport && h in 16.5..19.0) g -= 55.0 * kotlin.math.sin((h - 16.5) / 2.5 * Math.PI)
                rs += Reading(t, g.toInt().coerceIn(48, 320), 3)
                val asleep = h < 6.6 || h > 22.3
                hr[m] = when { asleep -> 58 + rnd.nextInt(6) + if (sick) 9 else 0; sport && h in 16.5..17.6 -> 145 + rnd.nextInt(20); else -> 80 + rnd.nextInt(14) + if (sick) 8 else 0 }
            }
            // night lows after sport (the night that follows)
            if (sport && k > 0) {
                val nd = WatchData.addDays(d0, 1)
                for (m in 24 until 42) { val t = nd + m * 300_000L; rs.removeAll { it.ts == t }; rs += Reading(t, (66 - 6 * kotlin.math.sin((m - 24) / 18.0 * Math.PI)).toInt(), 2) }
            }
            for (h in 0 until 24) steps[h] = when {
                h < 7 || h > 21 -> 0
                sport && h == 16 -> 2600 + rnd.nextInt(400)
                sick -> 60 + rnd.nextInt(120)
                else -> (if (k % 3 == 1) 180 else 420) + rnd.nextInt(380)
            }
            val sleep = mutableListOf<Seg>()
            var t = d0 - (if (short) 30 else 105) * 60_000L      // asleep 22:15 (or 23:30) the day before
            val wake = d0 + (if (short) 6 * 60 + 5 else 6 * 60 + 40) * 60_000L
            var s = 0
            while (t < wake) { val len = listOf(25, 40, 20, 5)[s % 4] * 60_000L; val stg = listOf(5, 4, 6, 1)[s % 4]; sleep += Seg(t, minOf(t + len, wake), stg); t += len; s++ }
            val prev = WatchData.addDays(d0, -1)
            val ex = if (sport) listOf(Ex(d0 + 16 * 3600_000L + 30 * 60_000L, d0 + 17 * 3600_000L + 35 * 60_000L, if (k % 2 == 0) 73 else 25, "")) else emptyList()
            db.putWatch(WatchData.dayKey(d0), WatchDay(WatchData.dayKey(d0), steps, hr, sleep.filter { it.a >= d0 }, ex.filter { it.a < now }, if (sick) 70 else 60 + rnd.nextInt(3), 96.5 + rnd.nextDouble()).json(), share = false)
            if (sleep.any { it.a < d0 }) {
                val pk = WatchData.dayKey(prev)
                val pw = WatchDay.parse(pk, db.watchDay(pk)?.second)
                db.putWatch(pk, WatchDay(pk, pw.steps, pw.hr, pw.sleep + sleep.filter { it.a < d0 }, pw.ex, pw.rhr, pw.spo2).json(), share = false, force = true)
            }
        }
        db.addReadings(rs.filter { it.ts <= now }.sortedBy { it.ts })
        st.hcLastData = now - 4 * 60_000L; st.hcGot = "steps,ex,sleep,hr,rhr,spo2"; st.hcSources = "Health Sync"
        listOf(Triple(240, 153.0, 44.0), Triple(150, 155.0, 44.9), Triple(60, 157.0, 45.8), Triple(1, 158.0, 46.5)).forEach { (d, h, w) ->
            db.addBody(now - d * 86_400_000L, h, w, "manual", share = false, who = if (d > 100) "clinic" else "Dad's phone") }
        // today's meals and insulin for the marks
        listOf(Triple(7.5, "Bread, labneh and tea", 55), Triple(13.0, "School lunch: couscous", 48), Triple(20.2, "Rice, chicken and salad", 60)).forEach { (h, n, cb) ->
            val ts = today + (h * 3600_000L).toLong(); if (ts < now) { db.addDose(Dose(0, ts - 12 * 60_000L, "rapid", 4.5, cb, 12, 140), share = false); db.addMeal(Meal(0, ts, n, "[]", cb, cb, cb, 140, "", ""), share = false) } }
        db.addIncident(Incident(0, WatchData.addDays(today, -7) + 9 * 3600_000L, WatchData.addDays(today, -6) + 20 * 3600_000L, "Fever", "38.5 °C"), share = false)
        listOf(Db.Recipe(0, now - 3000, "Chakchouka", "Dinner", "Tunisian", 28, 25, 4, "Eggs, tomato, peppers · 1 slice bread",
                FoodAi.Recipe("Chakchouka", 4, 25, 28, "tomato sauce ≈ 12 g + bread ≈ 16 g",
                    listOf("8 eggs", "4 ripe tomatoes (or 400 g crushed tomatoes)", "2 green peppers, 1 red pepper", "1 onion, 2 garlic cloves",
                        "2 tbsp olive oil · 1 tsp harissa (optional)", "cumin, salt, pepper", "4 slices whole-wheat bread (40 g each)"),
                    listOf("Slice the onion and peppers; cook in olive oil 8 min on medium heat.", "Add garlic, harissa and cumin; stir 1 min.",
                        "Add the tomatoes, salt and pepper; simmer 8–10 min until thick.", "Make 8 small wells, break an egg into each; cover and cook 5–6 min.",
                        "Serve 2 eggs with sauce and 1 slice of bread each."), "Without bread it is about 12 g per portion.").json()),
            Db.Recipe(0, now - 2000, "Grilled fish, salad and couscous", "Dinner", "Tunisian", 35, 30, 0, "Sea bream · 100 g cooked couscous", ""),
            Db.Recipe(0, now - 1000, "Yogurt with berries", "Sport", "Any", 15, 2, 0, "After sport", "")).forEach { db.addRecipe(it) }
    }
}
