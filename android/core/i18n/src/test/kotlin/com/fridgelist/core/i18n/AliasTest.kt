package com.fridgelist.core.i18n

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AliasTest {
    private val splitter = LocalSplitter()

    @Test
    fun aliasIdIsFirst32HexOfSha256() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("ba7816bf8f01cfea414140de5dae2223", AliasId.of("abc"))
        assertEquals(32, AliasId.of(AliasId.normalizedText("عيش")).length)
    }

    @Test
    fun aliasNormalizationMatchesTheSplitter() {
        assertEquals(AliasId.normalizedText("عَيش"), AliasId.normalizedText("عيش"))
    }

    @Test
    fun ambiguousDialectWordOffersCandidates() {
        val item = splitter.split("عيش", listOf("ar-SA")).single()
        assertNull(item.canonicalId)
        assertEquals(listOf("bread", "rice"), item.ambiguity?.candidates)
        assertEquals("عيش", item.ambiguity?.wordKey)
    }

    @Test
    fun householdAliasWinsAndRemovesTheQuestion() {
        val aliases = mapOf(AliasId.normalizedText("عيش") to "rice")
        val item = splitter.split("كيلو عيش", listOf("ar-SA"), aliases).single()
        assertEquals("rice", item.canonicalId)
        assertNull(item.ambiguity)
        assertEquals("rice", splitter.split("العيش", listOf("ar"), aliases).single().canonicalId, "article stripped")
    }

    @Test
    fun aliasBeatsLocaleHint() {
        // Egypt says bread, but this household decided otherwise.
        val aliases = mapOf(AliasId.normalizedText("عيش") to "rice")
        assertEquals("rice", splitter.split("عيش", listOf("ar-EG"), aliases).single().canonicalId)
    }

    @Test
    fun contextWordResolvesWithoutAsking() {
        val item = splitter.split("عيش بسمتي", listOf("ar-SA")).single()
        assertEquals("rice_basmati", item.canonicalId)
        assertNull(item.ambiguity)
    }

    @Test
    fun displayNameForCandidates() {
        assertEquals("رز", Catalog.bundled.displayName("rice", "ar"))
        assertEquals("Ekmek", Catalog.bundled.displayName("bread", "tr"))
        assertEquals("Rice", Catalog.bundled.displayName("rice", "fil"), "falls back to English")
    }
}
