package com.fridgelist.app.ui.common

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.fridgelist.app.R
import com.fridgelist.core.model.Languages
import java.util.Locale

/** A language option shown by its own name (العربية, Türkçe…), never translated. */
data class LanguageOption(val tag: String, @param:StringRes val label: Int)

object LanguageOptions {
    /** Item display languages (CONTRACT §1), Arabic first. */
    val ITEM: List<LanguageOption> = listOf(
        LanguageOption("ar", R.string.language_ar),
        LanguageOption("tr", R.string.language_tr),
        LanguageOption("en", R.string.language_en),
        LanguageOption("fil", R.string.language_fil),
        LanguageOption("hi", R.string.language_hi),
        LanguageOption("ur", R.string.language_ur),
    )

    /** UI / household default languages. */
    val UI: List<LanguageOption> = ITEM.filter { it.tag in Languages.UI }

    /**
     * The UI language as a contract tag: the per-app choice, else the system language when it is
     * one we translate, else English.
     */
    fun currentUiLanguage(): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val language = (if (appLocales.isEmpty) Locale.getDefault() else appLocales[0])?.language
        return language?.takeIf { it in Languages.UI } ?: Languages.FALLBACK_UI
    }
}
