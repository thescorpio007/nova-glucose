package com.nova.glucose

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** 💬 Ask Nova AI (a supportive coach for the family) and the 📈 weekly progress summary every Sunday evening. */
object Coach {
    val SYSTEM = """
You are Nova, a warm and supportive diabetes coach talking with the family of a child or teenager with type 1 diabetes (see the profile).
You have all the recorded data: the daily log of every recorded day (glucose, rapid and long-acting insulin units, carbs, with exact averages),
the details of the last 14 days (hourly glucose, meals, every dose, incidents, watch data) and what is happening right now (live).
Help the family follow her progress and feel supported:
• answer every question directly with the exact numbers, times, totals and averages from the data;
• explain patterns (time of day, meals, nights, sport, sleep, school days, sick days) and compare periods (this week vs last week, before → after a Lantus change);
• notice and celebrate what is improving; say clearly what to watch (e.g. tonight, after sport, before school);
• give practical non-dose ideas: food choices and timing, pre-bolus timing questions for the team, activity, sleep, routines, school, sick days;
• help prepare questions for the diabetes care team;
• when the parents sound tired or worried, acknowledge it kindly and keep them going.
Plain text for a phone screen, short paragraphs, "•" bullets only when listing.
Rules: ${AiData.OPEN_DATA} Do not diagnose. Do not invent data; if the data does not show it, say so. If the live data shows a low or a very high now,
say first to follow the family's care plan for it. No markdown other than "•".
""".trimIndent()

    /** Photos of products / labels / screenshots attached. */
    val PRODUCTS = """
Photos are attached (food products, nutrition labels, menus or screenshots). Read them carefully. If they show food products, answer in this order:
1) One line per product: name — carbs per serving and per 100 g · sugars · fibre · sweeteners / sugar alcohols (e.g. maltitol: raises glucose more slowly, but still counts) · calories. If a value can't be read, say so.
2) Ranking: 🥇 better choice, 🥈, 🥉 — one short reason each. Be honest: say "OK in a small portion, not ideal" when even the best one is not a good everyday choice. "0% added sugar" is not "no carbs".
3) Portion tip: how many grams / pieces fit her usual snack carbs (see the profile), and the carbs of that portion.
Never a dose. Plain text, no tables, "•" bullets only.
""".trimIndent()

    /** The patient herself, on her phone: short and friendly (read aloud). */
    val SIMPLE = "She is asking herself, on her phone, and the answer is read aloud: 3 to 6 short, friendly sentences, simple words, the key number first, no long lists."

    private val WEEKLY = """
Write this week's progress summary for the family (plain text for a phone screen), with exactly these headings:
This week in one line
This week vs last week
(time in range, average, lows and highs, nights, rapid and long-acting insulin per day, carbs per day — exact numbers from the data, before → after)
What went well
What to watch
One idea to try this week
(food, timing, activity, sleep or routine — never a dose)
Question for the doctor
(only if useful)
Warm and encouraging, honest about problems. "•" bullets under headings.
""".trimIndent()

    private const val NOTE = 7990

    /** Every minute (monitor): on Sunday from 19:00, once a week, on each phone that has an AI key. */
    fun maybeWeekly(c: Context) {
        val st = Store(c)
        if (!st.onFor("weekly")) return
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || cal.get(Calendar.HOUR_OF_DAY) < 19) return
        val week = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.WEEK_OF_YEAR)}"
        if (st.raw.getString("weekly_done", "") == week) return
        val p = st.current()
        if (st.key(p).isBlank()) return
        if (System.currentTimeMillis() - st.raw.getLong("weekly_try", 0L) < 30 * 60_000L) return
        st.raw.edit().putLong("weekly_try", System.currentTimeMillis()).apply()
        val app = c.applicationContext
        Thread {
            try {
                val lg = st.aiReviewLang.ifBlank { L.lang }
                val user = AiData.dailyLog(app, 60) + "\nDetails of the last 14 days:\n" + AiData.period(app, 14) + "\n" + AiData.live(app)
                val a = Providers.ask(st, p, SYSTEM + "\n" + WEEKLY + "\n" + AiData.audience(st) + (if (lg != "en") "\nWrite everything in ${L.name(lg)}, including the headings." else ""), user)
                val txt = a.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?m)^#{1,6}\\s*"), "").replace(Regex("(?m)^\\s*[*-]\\s+"), "• ").trim()
                val conv = AiChats.Conv(java.util.UUID.randomUUID().toString().take(10), System.currentTimeMillis(), 0, mutableListOf(false to "📈 " + L.s("Weekly progress summary") + "\n\n" + txt))
                AiChats.save(app, conv)
                st.raw.edit().putString("weekly_done", week).apply()
                notify(app, conv.id, txt.lines().firstOrNull { it.isNotBlank() && !it.startsWith("This week in one line") }?.take(140) ?: "")
            } catch (e: Exception) { ErrorLog.e("AI", "weekly summary: ${e.message ?: e.javaClass.simpleName}") }
        }.start()
    }

    private fun notify(c: Context, id: String, line: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val i = Intent(c, AiReviewActivity::class.java).putExtra("coach", true).putExtra("conv", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(c, NOTE, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, Sounds.channel(c, "weekly")) else @Suppress("DEPRECATION") Notification.Builder(c)
            nm.notify(NOTE, b.setSmallIcon(c.applicationInfo.icon).setContentTitle("📈 " + L.s("Weekly progress summary"))
                .setContentText(line.ifBlank { L.s("Tap to read it and talk with Nova AI.") }).setStyle(Notification.BigTextStyle().bigText(line))
                .setContentIntent(pi).setAutoCancel(true).build())
            Sounds.after(c, "weekly")
        } catch (_: Exception) { }
    }
}
