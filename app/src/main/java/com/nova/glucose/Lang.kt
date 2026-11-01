package com.nova.glucose

import android.app.Activity
import android.view.View
import android.widget.PopupMenu

/** The small 🌐 ▾ button: English / Français / العربية. */
object Lang {
    fun pick(a: Activity, anchor: View, current: String = L.lang, onPick: ((String) -> Unit)? = null) {
        val pm = PopupMenu(a, anchor)
        L.LANGS.forEachIndexed { i, (code, name) -> pm.menu.add(0, i, i, (if (code == current) "✓ " else "    ") + name) }
        pm.setOnMenuItemClickListener { item ->
            val code = L.LANGS[item.itemId].first
            if (onPick != null) onPick(code)
            else if (code != L.lang) {
                Store(a).lang = code
                L.set(a, code)
                a.recreate()
            }
            true
        }
        pm.show()
    }
}
