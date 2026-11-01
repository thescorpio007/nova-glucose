package com.nova.glucose

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import java.util.Locale

/**
 * App language: English, Français, العربية (per phone). Every text on screen is translated as it is shown
 * (screens, dialogs, notifications, chart), using assets/i18n.json. Arabic also turns the layout right-to-left.
 */
object L {
    val LANGS = listOf("en" to "English", "fr" to "Français", "ar" to "العربية")
    @Volatile var lang = "en"; private set
    private var loaded = ""
    private var exact: Map<String, String> = emptyMap()
    private var tpls: List<Tpl> = emptyList()
    private var chunks: List<Pair<String, String>> = emptyList()

    private class Tpl(val re: Regex, val out: String, val order: List<String>)

    fun short(l: String = lang) = when (l) { "fr" -> "FR"; "ar" -> "ع"; else -> "EN" }
    fun name(l: String) = LANGS.firstOrNull { it.first == l }?.second ?: "English"
    /** For the AI: "Answer in French." */
    fun aiLine(l: String = lang) = when (l) { "fr" -> "Answer in French."; "ar" -> "Answer in Arabic (Modern Standard Arabic, simple words)."; else -> "" }

    fun init(c: Context) { set(c, Store(c).lang) }

    fun set(c: Context, l: String) {
        lang = if (l in setOf("fr", "ar")) l else "en"
        if (lang == "en") { exact = emptyMap(); tpls = emptyList(); chunks = emptyList(); loaded = "en"; return }
        if (loaded == lang) return
        val a = JSONArray(c.assets.open("i18n.json").use { it.readBytes().toString(Charsets.UTF_8) })
        val ex = HashMap<String, String>(a.length() * 2); val tl = ArrayList<Tpl>(); val ch = ArrayList<Pair<String, String>>()
        val ph = Regex("""\{(\d+)\}|%[-0-9.]*[dfs]""")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val en = o.getString("en"); val tr = o.optString(lang)
            if (tr.isBlank()) continue
            if (!ph.containsMatchIn(en)) {
                ex[en] = tr; ex[en.trim()] = tr.trim()
                if (en.trim().length >= 8) ch += en.trim() to tr.trim()
                continue
            }
            // template: literal parts must match exactly, placeholders take any text
            val sb = StringBuilder("^"); var last = 0; val order = ArrayList<String>()
            for (m in ph.findAll(en)) {
                sb.append(Regex.escape(en.substring(last, m.range.first))); sb.append("([\\s\\S]*?)")
                order += m.value; last = m.range.last + 1
            }
            sb.append(Regex.escape(en.substring(last))).append("$")
            if (en.length - order.sumOf { it.length } < 3) continue        // too generic, e.g. "{0} {1}"
            tl += Tpl(Regex(sb.toString()), tr, order)
        }
        exact = ex; tpls = tl.sortedByDescending { it.re.pattern.length }; chunks = ch.sortedByDescending { it.first.length }; loaded = lang
    }

    /** Translate one text (unchanged in English or when unknown). */
    fun t(s: CharSequence?): CharSequence? {
        if (s == null || lang == "en" || s.isEmpty() || s is Spanned && s.getSpans(0, s.length, Any::class.java).isNotEmpty()) return s
        return tr(s.toString(), 0)
    }

    fun s(s: String): String = t(s)?.toString() ?: s

    private fun tr(s: String, depth: Int): String {
        exact[s]?.let { return it }
        val trimmed = s.trim()
        if (trimmed != s) exact[trimmed]?.let { return s.replace(trimmed, it) }
        if (!s.any { it.isLetter() }) return s
        for (tp in tpls) {
            val m = tp.re.matchEntire(s) ?: continue
            var out = tp.out
            // {n} placeholders by number; %-specs in order
            val pct = tp.order.withIndex().filter { it.value.startsWith("%") }.map { it.index }
            tp.order.forEachIndexed { k, p ->
                val v = m.groupValues[k + 1].let { if (depth < 1 && it.any { ch -> ch.isLetter() }) tr(it, depth + 1) else it }
                if (!p.startsWith("%")) out = out.replace(p, v)
            }
            var pi = 0
            out = Regex("%[-0-9.]*[dfs]").replace(out) { mm -> pct.getOrNull(pi++)?.let { m.groupValues[it + 1] } ?: mm.value }
            return out
        }
        // several sentences or pieces glued together: replace the known pieces
        var out = s
        for ((e, t) in chunks) if (out.contains(e)) out = out.replace(e, t)
        return out
    }

    // ---------------------------------------------------------------- applying it to views
    private const val KEY = 0x4E4F5641          // tag key: last text we set (so it isn't translated twice)

    fun walk(v: View) {
        if (lang == "en") return
        if (v is TextView) {
            if (v !is EditText) {
                val cur = v.text
                if (cur.isNotEmpty() && v.getTag(KEY) != cur.toString()) {
                    val n = t(cur)
                    if (n != null && n.toString() != cur.toString()) { v.setTag(KEY, n.toString()); v.text = n } else v.setTag(KEY, cur.toString())
                }
            }
            v.hint?.let { h -> if (v.getTag(KEY + 1) != h.toString()) { val n = t(h); v.setTag(KEY + 1, n.toString()); if (n.toString() != h.toString()) v.hint = n } }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    /** Translate a window's texts now and whenever it changes. */
    fun watch(root: View) {
        if (lang == "en") return
        walk(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { walk(root) }
    }

    /** Context in the chosen language (also sets right-to-left for Arabic). */
    fun wrap(base: Context): Context {
        val l = Store(base).lang
        if (l != "fr" && l != "ar") return base
        val loc = Locale(l)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc); cfg.setLayoutDirection(loc)
        return base.createConfigurationContext(cfg)
    }
}

/** AlertDialog.Builder that shows its texts in the app language. */
class Tr {
    class Builder(c: Context) : AlertDialog.Builder(c) {
        override fun create(): AlertDialog {
            val d = super.create()
            if (L.lang != "en") d.window?.decorView?.let { L.watch(it) }
            return d
        }
    }
}
