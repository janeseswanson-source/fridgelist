package com.fridgelist.core.i18n

import com.fridgelist.core.model.ContractJson
import kotlinx.serialization.Serializable

/**
 * Local copy of the item catalog, loaded from `fixtures/catalog_seed.json` (bundled into the jar
 * at build time). The splitter uses it to know which words start an item and to guess a
 * canonicalId offline. The server stays the final word on canonical item and category
 * (ARCHITECTURE §2.3).
 */
class Catalog(seed: CatalogSeed) {
    val items: List<CatalogItem> = seed.items
    val dialectRules: List<DialectRule> = seed.dialectRules

    private val byId: Map<String, CatalogItem> = items.associateBy { it.id }

    /** synonym key (space-separated match keys) → canonicalId. */
    private val synonyms: Map<String, String>

    /** Every word that appears in any synonym, as a match key. */
    private val knownWords: Set<String>

    private val dialectByWord: Map<String, DialectRule> =
        seed.dialectRules.associateBy { Normalize.matchKey(it.word) }

    val longestSynonymWords: Int

    init {
        val map = HashMap<String, String>()
        val words = HashSet<String>()
        for (item in items) {
            val all = item.synonyms.values.flatten() + item.names.values
            for (synonym in all) {
                // Turkish casing for the Turkish synonyms is already lowercase in the seed.
                val key = Normalize.matchKey(synonym)
                map.putIfAbsent(key, item.id)
                words += key.split(' ')
            }
        }
        synonyms = map
        knownWords = words + dialectByWord.keys
        longestSynonymWords = synonyms.keys.maxOf { it.count { c -> c == ' ' } + 1 }
    }

    fun item(id: String?): CatalogItem? = id?.let(byId::get)

    fun isCanned(canonicalId: String?): Boolean = item(canonicalId)?.defaultUnit == "can"

    /** True when [key] is one word of some catalog synonym or a dialect word. */
    fun isKnownWord(key: String): Boolean = key in knownWords || stripArticle(key) in knownWords

    /** Exact synonym lookup for a phrase key; also tries without the Arabic article ال. */
    fun synonym(phraseKey: String): String? =
        synonyms[phraseKey] ?: synonyms[phraseKey.split(' ').joinToString(" ", transform = ::stripArticle)]

    fun dialectRule(wordKey: String): DialectRule? =
        dialectByWord[wordKey] ?: dialectByWord[stripArticle(wordKey)]

    /**
     * Best local guess for a name: the longest synonym found inside it (ARCHITECTURE §4.1:
     * "Always prefer the longest synonym match"); else a dialect word resolved by locale hint.
     * Returns null when nothing matches or a dialect word stays ambiguous.
     */
    fun guessCanonical(
        nameKeys: List<String>,
        localeHints: List<String>,
        aliases: Map<String, String> = emptyMap(),
    ): String? {
        // 1. Household alias for the whole name (CONTRACT §3.16), then for any single word.
        aliases[nameKeys.joinToString(" ")]?.let { return it }
        nameKeys.firstNotNullOfOrNull { aliases[it] ?: aliases[stripArticle(it)] }?.let { return it }
        for (len in minOf(longestSynonymWords, nameKeys.size) downTo 1) {
            for (start in 0..nameKeys.size - len) {
                val phrase = nameKeys.subList(start, start + len).joinToString(" ")
                if (dialectRule(phrase) != null) continue
                synonym(phrase)?.let { return it }
            }
        }
        for (key in nameKeys) {
            val rule = dialectRule(key) ?: continue
            return rule.resolve(localeHints)
        }
        return null
    }

    /**
     * When a name is only a dialect word that the locale hints can't settle (e.g. "عيش" without
     * ar-EG), returns the word's match key and its candidate canonicalIds so the app can ask once.
     * Null when the name is not ambiguous (an alias or a longer synonym decides it).
     */
    fun ambiguity(nameKeys: List<String>, localeHints: List<String>, aliases: Map<String, String> = emptyMap()): Ambiguity? {
        if (guessCanonical(nameKeys, localeHints, aliases) != null) return null
        val key = nameKeys.firstOrNull { dialectRule(it) != null } ?: return null
        return Ambiguity(stripArticle(key), dialectRule(key)!!.candidates)
    }

    data class Ambiguity(val wordKey: String, val candidates: List<String>)

    /** A catalog item's name for display, in [lang] or English. */
    fun displayName(canonicalId: String, lang: String): String? =
        item(canonicalId)?.names?.let { it[lang] ?: it["en"] }

    companion object {
        const val EGGS_ID = "eggs"
        private const val RESOURCE = "/catalog_seed.json"

        /** The catalog bundled with the app (built from `fixtures/catalog_seed.json`). */
        val bundled: Catalog by lazy {
            val text = Catalog::class.java.getResourceAsStream(RESOURCE)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("catalog_seed.json missing from resources")
            fromJson(text)
        }

        fun fromJson(json: String): Catalog = Catalog(ContractJson.decodeFromString(CatalogSeed.serializer(), json))

        /** Drops a leading Arabic definite article: "الخبز" → "خبز". */
        fun stripArticle(key: String): String =
            if (key.length > 3 && key.startsWith("ال")) key.substring(2) else key
    }
}

@Serializable
data class CatalogSeed(
    val version: Int = 1,
    val dialectRules: List<DialectRule> = emptyList(),
    val items: List<CatalogItem> = emptyList(),
)

@Serializable
data class CatalogItem(
    val id: String,
    val category: String,
    val defaultUnit: String? = null,
    val names: Map<String, String> = emptyMap(),
    val synonyms: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class DialectRule(
    val word: String,
    val candidates: List<String>,
    val hintsByLocale: Map<String, String> = emptyMap(),
    val note: String? = null,
) {
    /** The candidate for the first locale hint that has an entry, or null when still ambiguous. */
    fun resolve(localeHints: List<String>): String? =
        localeHints.firstNotNullOfOrNull { hint ->
            hintsByLocale.entries.firstOrNull { it.key.equals(hint, ignoreCase = true) }?.value
        }
}
