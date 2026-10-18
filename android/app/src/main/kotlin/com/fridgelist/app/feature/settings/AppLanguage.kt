package com.fridgelist.app.feature.settings

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.fridgelist.app.R

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

        /** Applies the language; AppCompat recreates the activity. */
        fun apply(language: AppLanguage) {
            val list = language.tag?.let { LocaleListCompat.forLanguageTags(it) }
                ?: LocaleListCompat.getEmptyLocaleList()
            AppCompatDelegate.setApplicationLocales(list)
        }
    }
}
