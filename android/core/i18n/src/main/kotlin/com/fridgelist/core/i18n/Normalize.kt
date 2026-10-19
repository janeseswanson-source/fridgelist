package com.fridgelist.core.i18n

import java.util.Locale

/**
 * Text normalization (ARCHITECTURE §4.1). Identical rules exist in the backend (TypeScript) and
 * FridgeKit (Swift); all three are tested against `fixtures/`.
 *
 * Two levels:
 * - [display]-safe helpers ([digitsToAscii], [arabic]) that keep the text readable;
 * - [matchKey], which is only for comparing words and is never shown to the user.
 */
object Normalize {
    private val TURKISH = Locale.forLanguageTag("tr")

    /** Arabic harakat (tashkeel), superscript alef and Quranic marks. */
    private val TASHKEEL = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private const val TATWEEL = 'ـ'
    private val WHITESPACE = Regex("\\s+")
    private val INVISIBLE = Regex("[\\u00AD\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val UNICODE_SPACE = Regex("[\\u00A0\\u2007\\u202F]")
    private val FORMAT = Regex("\\p{Cf}")

    /**
     * Display-safe cleanup for typed or dictated text: drops bidi marks, zero-width spaces, BOM and
     * soft hyphens, and turns no-break spaces into plain spaces. Keyboards and speech engines insert
     * these; left in, "‏حليب" would not match "حليب".
     */
    fun invisible(text: String): String = UNICODE_SPACE.replace(INVISIBLE.replace(text, ""), " ")

    /** Eastern Arabic (٠-٩) and Persian/Urdu (۰-۹) digits → ASCII; Arabic decimal separator ٫ → '.'. */
    fun digitsToAscii(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    '٫' -> '.'
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    /** Strips tashkeel and tatweel; unifies أ إ آ ٱ → ا and ى → ي. */
    fun arabic(text: String): String {
        val stripped = TASHKEEL.replace(text, "").replace(TATWEEL.toString(), "")
        val sb = StringBuilder(stripped.length)
        for (c in stripped) {
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    /** Lowercases with Turkish rules (İ → i, I → ı) when [turkish], otherwise locale-neutral. */
    fun lowercase(text: String, turkish: Boolean): String =
        if (turkish) text.lowercase(TURKISH) else text.lowercase(Locale.ROOT)

    /** A Turkish decimal comma between digits becomes a dot: "1,5" → "1.5". */
    fun decimalComma(text: String): String = DECIMAL_COMMA.replace(text, "$1.$2")

    private val DECIMAL_COMMA = Regex("(\\d),(\\d)")

    /**
     * Comparison key: invisible and format characters removed, digits to ASCII, Arabic folding,
     * lowercase, decimal comma, collapsed
     * whitespace, and final ة treated as ه (ARCHITECTURE §4.1: "treat final ة/ه as equal when
     * matching").
     */
    fun matchKey(text: String, turkish: Boolean = false): String {
        val base = decimalComma(lowercase(arabic(digitsToAscii(FORMAT.replace(invisible(text), ""))), turkish))
        return base.trim().split(WHITESPACE).joinToString(" ") { word ->
            if (word.endsWith('ة')) word.dropLast(1) + 'ه' else word
        }
    }

    /** True when the locale hints ask for Turkish casing rules. */
    fun isTurkish(localeHints: List<String>): Boolean =
        localeHints.any { it.lowercase(Locale.ROOT).substringBefore('-') == "tr" }
}
