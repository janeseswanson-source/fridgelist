package com.fridgelist.app.feature.settings

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.fridgelist.app.R
import java.util.Locale

/**
 * Per-app UI language (ARCHITECTURE §8.8). Arabic is listed first (CLAUDE.md rule 3).
 * On Android 13+ the same choice is also shown in system settings via `locales_config.xml`.
 */
enum class AppLanguage(val tag: String?, @param:StringRes val label: Int) {
    SYSTEM(null, R.string.settings_language_system),
    ARABIC("ar", R.string.language_ar),
    TURKISH("tr", R.string.language_tr),
    ENGLISH("en", R.string.language_en),
    ;

    companion object {
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return SYSTEM
            val language = locales[0]?.language
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }

        /** Applies the language, keeping the numbers choice; AppCompat recreates the activity. */
        fun apply(language: AppLanguage) = apply(language, NumberStyle.current())

        fun apply(language: AppLanguage, numbers: NumberStyle) {
            val tag = NumberStyle.tag(language.tag, numbers, Locale.getDefault())
            val list = tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()
            AppCompatDelegate.setApplicationLocales(list)
        }
    }
}

/**
 * "Numbers as ١٢٣ or 123" (PRODUCT_SPEC F12) as the per-app locale's Unicode `nu` extension, so
 * every number the app formats (quantities, counts, plurals) follows it.
 */
enum class NumberStyle(val nu: String?, @param:StringRes val label: Int) {
    SYSTEM(null, R.string.settings_numbers_system),
    ARABIC_INDIC("arab", R.string.settings_numbers_arabic),
    WESTERN("latn", R.string.settings_numbers_western),
    ;

    companion object {
        fun current(): NumberStyle {
            val nu = AppCompatDelegate.getApplicationLocales()[0]?.getUnicodeLocaleType("nu")
            return entries.firstOrNull { it.nu != null && it.nu == nu } ?: SYSTEM
        }

        /** The per-app locale tag; null = follow the system entirely. Pure, for tests. */
        fun tag(languageTag: String?, numbers: NumberStyle, system: Locale): String? {
            if (numbers.nu == null) return languageTag
            val base = Locale.forLanguageTag(languageTag ?: system.toLanguageTag())
            return Locale.Builder().setLocale(base).setUnicodeLocaleKeyword("nu", numbers.nu).build().toLanguageTag()
        }
    }
}
