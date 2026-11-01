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
            60, 55, 65, 296, "", "school lunch"), who = "Aycha's phone")
        db.setAi(b, "[{\"name\":\"Couscous with chicken\",\"portion\":\"1 plate\",\"carbs\":60}]", 60)
        db.addDose(Dose(0, m2 - 8 * 60_000L, "rapid", 5.5, 60, 10, 290, "entered later"), who = "Aycha's phone")
        db.addDose(Dose(0, now - 19 * 3600_000L, "long", 18.0, note = ""), who = "Dad's phone")
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
}
