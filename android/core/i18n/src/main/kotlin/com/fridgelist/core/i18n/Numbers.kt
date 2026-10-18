package com.fridgelist.core.i18n

/**
 * Number words and fractions in Arabic, Turkish and English (ARCHITECTURE §4.1). Every key here is
 * already a [Normalize.matchKey] so lookups compare like with like.
 */
object Numbers {
    sealed interface Word {
        /** A plain number: "three", "üç", "ثلاثة". */
        data class Value(val value: Double) : Word

        /** Multiplies the running quantity, or stands alone: "half", "yarım", "نص". */
        data class Fraction(val value: Double) : Word

        /** Adds to the running quantity: "buçuk", "ونص" (one and a half). */
        data class Plus(val value: Double) : Word

        /** "dozen" → × 12, unit becomes piece. */
        data object Dozen : Word

        /** "a"/"an": counts as 1 only before a unit or "dozen". */
        data object Article : Word
    }

    private val words: Map<String, Word> = buildMap {
        fun values(value: Int, vararg forms: String) =
            forms.forEach { put(Normalize.matchKey(it), Word.Value(value.toDouble())) }

        // Arabic (Modern Standard and common dialect spellings).
        values(1, "واحد", "واحدة", "وحدة")
        values(2, "اثنين", "اثنان", "اتنين", "ثنين")
        values(3, "ثلاث", "ثلاثة", "تلات", "تلاتة")
        values(4, "أربع", "أربعة")
        values(5, "خمس", "خمسة")
        values(6, "ست", "ستة")
        values(7, "سبع", "سبعة")
        values(8, "ثمان", "ثماني", "ثمانية", "تمن", "تمانية")
        values(9, "تسع", "تسعة")
        values(10, "عشر", "عشرة")
        values(11, "احدعش", "حدعش", "إحدى عشر")
        values(12, "اثنعش", "اطنعش", "اثنا عشر")
        // Turkish ("on" is handled separately: it is also an English word).
        values(1, "bir")
        values(2, "iki")
        values(3, "üç")
        values(4, "dört")
        values(5, "beş")
        values(6, "altı")
        values(7, "yedi")
        values(8, "sekiz")
        values(9, "dokuz")
        // English.
        values(1, "one")
        values(2, "two")
        values(3, "three")
        values(4, "four")
        values(5, "five")
        values(6, "six")
        values(7, "seven")
        values(8, "eight")
        values(9, "nine")
        values(10, "ten")
        values(11, "eleven")
        values(12, "twelve")

        fun word(word: Word, vararg forms: String) = forms.forEach { put(Normalize.matchKey(it), word) }
        word(Word.Fraction(0.5), "نص", "نصف", "yarım", "half")
        word(Word.Fraction(0.25), "ربع", "çeyrek", "quarter")
        word(Word.Plus(0.5), "ونص", "ونصف", "buçuk")
        word(Word.Plus(0.25), "وربع")
        word(Word.Dozen, "dozen", "دزينة", "درزن", "düzine")
        word(Word.Article, "a", "an")
    }

    private val turkishTens = mapOf("on" to 10.0)

    /**
     * Looks up one token key. Turkish "on" (10) only counts when [turkish] is true, and "on bir" /
     * "on iki" are handled by [LocalSplitter] combining two tokens.
     */
    fun word(key: String, turkish: Boolean): Word? {
        words[key]?.let { return it }
        if (turkish) turkishTens[key]?.let { return Word.Value(it) }
        return parseNumeral(key)?.let { Word.Value(it) }
    }

    /** Parses ASCII numerals after [Normalize.matchKey] ("2", "1.5"). */
    fun parseNumeral(key: String): Double? =
        if (NUMERAL.matches(key)) key.toDoubleOrNull() else null

    private val NUMERAL = Regex("\\d+(\\.\\d+)?")
}
