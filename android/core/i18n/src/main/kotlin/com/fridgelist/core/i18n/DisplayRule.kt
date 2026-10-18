package com.fridgelist.core.i18n

/**
 * Display rule (ARCHITECTURE §4.3), identical on every client:
 * ```
 * title    = item.names[viewerLang] ?? (item.lang == viewerLang ? item.name : null)
 *            ?? item.names[household.defaultLanguage] ?? item.name
 * original = item.name   // shown underneath, smaller, only when title != item.name
 * ```
 */
object DisplayRule {
    data class Display(val title: String, val original: String?)

    fun display(
        name: String,
        lang: String?,
        names: Map<String, String>?,
        viewerLang: String,
        householdDefaultLanguage: String,
    ): Display {
        val title = names?.get(viewerLang)
            ?: (if (lang == viewerLang) name else null)
            ?: names?.get(householdDefaultLanguage)
            ?: name
        return Display(title = title, original = if (title != name) name else null)
    }
}
