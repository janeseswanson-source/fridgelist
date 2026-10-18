package com.fridgelist.core.i18n

import com.fridgelist.core.model.ItemUnit

/**
 * On-device splitter (ARCHITECTURE §4.1): turns one line such as "جيب ٢ كيلو بندورة وعلبة لبنة"
 * or "2 ekmek, yarım kilo beyaz peynir ve süt" into items with quantity and unit, with no network.
 * It powers instant chips, offline adds and notification replies; `parseItems` refines online.
 *
 * `name` keeps the words exactly as typed (CONTRACT §1); only quantity and unit words are removed.
 */
class LocalSplitter(private val catalog: Catalog = Catalog.bundled) {

    data class Item(
        val name: String,
        val qty: Double? = null,
        val unit: ItemUnit? = null,
        /** Local best guess; the server's `canonicalId` wins. */
        val canonicalId: String? = null,
    )

    /** One word as typed ([raw]) plus its comparison key. */
    private data class Token(val raw: String, val key: String)

    fun split(text: String, localeHints: List<String> = emptyList()): List<Item> {
        val turkish = Normalize.isTurkish(localeHints)
        return chunks(text)
            .flatMap { chunk -> segments(tokens(chunk, turkish)) }
            .flatMap { coverSplit(it) }
            .mapNotNull { toItem(it, localeHints, turkish) }
    }

    // --- 1. Hard separators: commas (not decimal commas), Arabic comma, semicolons, &, +, new lines.
    private fun chunks(text: String): List<String> = HARD_SEPARATORS.split(text).filter { it.isNotBlank() }

    // --- 2. Tokens, with glued forms split: "1kg" → "1" "kg", "x2" → "×" "2".
    private fun tokens(chunk: String, turkish: Boolean): List<Token> =
        chunk.trim().split(WHITESPACE).flatMap { raw ->
            GLUED_QTY_UNIT.matchEntire(raw)?.let { m ->
                val unitKey = Normalize.matchKey(m.groupValues[2], turkish)
                if (Units.lookup(unitKey) != null) {
                    return@flatMap listOf(
                        Token(m.groupValues[1], Normalize.matchKey(m.groupValues[1], turkish)),
                        Token(m.groupValues[2], unitKey),
                    )
                }
            }
            MULTIPLIER.matchEntire(raw)?.let { m ->
                val n = m.groupValues[1].ifEmpty { m.groupValues[2] }
                return@flatMap listOf(Token(raw, TIMES), Token(n, Normalize.matchKey(n, turkish)))
            }
            listOf(Token(raw, Normalize.matchKey(raw, turkish)))
        }

    // --- 3. Word separators: and / ve / ile / و, including و glued to the next word ("وحليب").
    private fun segments(tokens: List<Token>): List<List<Token>> {
        val out = mutableListOf<List<Token>>()
        var current = mutableListOf<Token>()
        fun cut() {
            if (current.isNotEmpty()) out += current
            current = mutableListOf()
        }
        for (token in tokens) {
            when {
                token.key in WORD_SEPARATORS -> cut()
                isWawPrefixed(token) -> {
                    cut()
                    val rest = token.raw.substring(1)
                    current += Token(rest, Normalize.matchKey(rest))
                }
                else -> current += token
            }
        }
        cut()
        return out
    }

    /**
     * "و" glued to a word separates items only when the rest is something we recognise (an item
     * word, a unit, a number) and the whole word is not itself known — so "وعلبة", "وحليب" and
     * "و٥" split while "ورق تواليت", "واحد" and "ونص" do not. Unknown words are left whole: a
     * merged chip is easy to fix, a mangled word is not.
     */
    private fun isWawPrefixed(token: Token): Boolean {
        val key = token.key
        if (key.length < 2 || !key.startsWith('و')) return false
        if (catalog.isKnownWord(key) || Units.lookup(key) != null || Numbers.word(key, false) != null) return false
        val rest = key.substring(1)
        return catalog.isKnownWord(rest) || Units.lookup(rest) != null ||
            Numbers.word(rest, false) != null || rest.first().isDigit()
    }

    /**
     * Space-only lists such as "domates biber patlıcan": when every word of a segment is covered by
     * catalog synonyms (longest first) and there is no quantity, each synonym becomes its own item.
     */
    private fun coverSplit(segment: List<Token>): List<List<Token>> {
        if (segment.size < 2) return listOf(segment)
        if (segment.any { Numbers.word(it.key, false) != null || Units.lookup(it.key) != null }) return listOf(segment)
        val parts = mutableListOf<List<Token>>()
        var i = 0
        while (i < segment.size) {
            val len = (minOf(catalog.longestSynonymWords, segment.size - i) downTo 1).firstOrNull { len ->
                catalog.synonym(segment.subList(i, i + len).joinToString(" ") { it.key }) != null
            } ?: return listOf(segment)
            parts += segment.subList(i, i + len)
            i += len
        }
        return if (parts.size > 1) parts else listOf(segment)
    }

    // --- 4. Filler, quantity and unit, name.
    private fun toItem(segment: List<Token>, localeHints: List<String>, turkish: Boolean): Item? {
        val tokens = stripFiller(segment)
        if (tokens.isEmpty()) return null

        val lead = parseQuantity(tokens, turkish)
        var nameTokens = tokens.drop(lead.consumed)
        var qty = lead.qty
        var unitWord = lead.unit
        var scale12 = lead.dozen

        if (lead.consumed == 0) {
            val trail = parseTrailingQuantity(nameTokens, turkish)
            if (trail != null) {
                nameTokens = nameTokens.dropLast(trail.consumed)
                qty = trail.qty
                unitWord = trail.unit
                scale12 = trail.dozen
            }
        }
        // "2 bags of rice", "كيلو من الرز"
        if (lead.consumed > 0 && nameTokens.firstOrNull()?.key in LINKING_WORDS) nameTokens = nameTokens.drop(1)
        if (nameTokens.isEmpty()) return null

        val name = nameTokens.joinToString(" ") { it.raw }
        val canonicalId = catalog.guessCanonical(nameTokens.map { it.key }, localeHints)
        val unit = when {
            unitWord != null -> Units.resolve(unitWord, canonicalId, catalog)
            scale12 -> ItemUnit.PIECE
            else -> null
        }
        return Item(name = name, qty = qty, unit = unit, canonicalId = canonicalId)
    }

    private fun stripFiller(tokens: List<Token>): List<Token> {
        var rest = tokens
        while (true) {
            val phrase = FILLERS.firstOrNull { filler ->
                filler.size <= rest.size && filler.indices.all { rest[it].key == filler[it] }
            } ?: return rest
            rest = rest.drop(phrase.size)
        }
    }

    private data class Quantity(
        val qty: Double?,
        val unit: Units.UnitWord?,
        val dozen: Boolean,
        val consumed: Int,
    )

    /** Leading quantity: numbers, number words, fractions, "dozen", then an optional unit and "+½". */
    private fun parseQuantity(tokens: List<Token>, turkish: Boolean): Quantity {
        var qty: Double? = null
        var unit: Units.UnitWord? = null
        var dozen = false
        var i = 0
        loop@ while (i < tokens.size) {
            val key = tokens[i].key
            val next = tokens.getOrNull(i + 1)?.key
            val word = Numbers.word(key, turkish)
            when {
                // Turkish "on bir" (11), "on iki" (12)…
                turkish && key == "on" && next != null && Numbers.word(next, true) is Numbers.Word.Value -> {
                    qty = 10 + (Numbers.word(next, true) as Numbers.Word.Value).value
                    i += 2
                    continue@loop
                }
                word is Numbers.Word.Value && unit == null -> qty = if (qty == null) word.value else qty * word.value
                word is Numbers.Word.Fraction && unit == null -> qty = (qty ?: 1.0) * word.value
                word is Numbers.Word.Plus -> qty = (qty ?: 1.0) + word.value
                word is Numbers.Word.Dozen -> { qty = (qty ?: 1.0) * 12; dozen = true }
                word is Numbers.Word.Article && next != null &&
                    (Numbers.word(next, turkish) is Numbers.Word.Dozen || isUnit(next, true)) -> Unit
                unit == null && !dozen && isUnit(key, qty != null) -> {
                    unit = Units.lookup(key)
                    if (qty == null) qty = 1.0
                }
                else -> break@loop
            }
            i++
        }
        return Quantity(qty, unit, dozen, i)
    }

    /** Trailing quantity: "yumurta 30 tane", "sugar 1kg", "Pampers x2". */
    private fun parseTrailingQuantity(tokens: List<Token>, turkish: Boolean): Quantity? {
        if (tokens.size < 2) return null
        val last = tokens.last().key
        val beforeLast = tokens[tokens.size - 2].key
        Numbers.parseNumeral(last)?.let { n ->
            if (beforeLast == TIMES) return Quantity(n, null, false, 2)
            return null // "Pampers size 4": a trailing bare number is part of the name.
        }
        val unit = Units.lookup(last) ?: return null
        val n = (Numbers.word(beforeLast, turkish) as? Numbers.Word.Value)?.value ?: return null
        return Quantity(n, unit, false, 2)
    }

    private fun isUnit(key: String, afterNumber: Boolean): Boolean {
        val unit = Units.lookup(key) ?: return false
        return afterNumber || !unit.needsNumber
    }

    private companion object {
        const val TIMES = "×"

        // A comma is a separator unless it sits between two digits (Turkish decimal "1,5").
        val HARD_SEPARATORS = Regex("(?<!\\p{Nd}),|,(?!\\p{Nd})|[،؛;&+\\n]")
        val WHITESPACE = Regex("\\s+")
        val GLUED_QTY_UNIT = Regex("(\\p{Nd}+(?:[.,٫]\\p{Nd}+)?)(\\p{L}+)")
        val MULTIPLIER = Regex("[x×](\\p{Nd}+)|(\\p{Nd}+)[x×]", RegexOption.IGNORE_CASE)

        val WORD_SEPARATORS = setOf("and", "ve", "ile", "و")
        val LINKING_WORDS = setOf("of", "من")

        /** Leading filler phrases, as match keys. Longest phrases first. */
        val FILLERS: List<List<String>> = listOf(
            "we're out of", "were out of", "we are out of", "we need", "don't forget", "dont forget",
            "pick up", "out of", "need", "get", "buy", "grab", "please", "some", "also",
            "almamız lazım", "unutma", "lütfen", "biraz",
            "لا تنسوا", "لا تنسو", "لا تنسى", "لا تنسي", "جيب", "جيبي", "جيبوا", "جيبو", "هات", "هاتي",
            "نبي", "نبغى", "ابي", "ابغى", "بدنا", "بدي", "عايزين", "عايز", "عاوزين", "عاوز",
            "محتاجين", "نحتاج", "اشتري", "اشتروا",
        ).map { phrase -> Normalize.matchKey(phrase, turkish = true).split(' ') }
            .sortedByDescending { it.size }
    }
}
