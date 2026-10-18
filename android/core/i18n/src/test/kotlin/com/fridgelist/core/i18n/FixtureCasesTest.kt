package com.fridgelist.core.i18n

import com.fridgelist.core.model.ContractJson
import kotlinx.serialization.Serializable
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the shared `fixtures/parse_cases.json` (CLAUDE.md rule 6).
 *
 * Required for the local splitter: every case tagged `split` yields the right number of items.
 * Also printed for information: full matches (canonicalId, qty, unit) over all cases, using the
 * fixture's matching rules.
 */
class FixtureCasesTest {

    @Serializable
    data class Cases(val cases: List<Case>)

    @Serializable
    data class Case(
        val id: String,
        val tags: List<String>,
        val text: String,
        val localeHints: List<String>,
        val expect: List<Expected>,
    )

    @Serializable
    data class Expected(val canonicalId: String, val qty: Double? = null, val unit: String? = null)

    private val fixtures: Cases by lazy {
        val dir = System.getProperty("fixtures.dir") ?: error("fixtures.dir not set by Gradle")
        ContractJson.decodeFromString(Cases.serializer(), File(dir, "parse_cases.json").readText())
    }

    private val splitter = LocalSplitter()

    @Test
    fun fixturesAreLoaded() {
        assertEquals(28, fixtures.cases.size)
        assertTrue(fixtures.cases.count { "split" in it.tags } >= 18)
    }

    @Test
    fun everySplitCaseHasTheRightItemCount() {
        val failures = fixtures.cases.filter { "split" in it.tags }.mapNotNull { case ->
            val items = splitter.split(case.text, case.localeHints)
            if (items.size == case.expect.size) {
                null
            } else {
                "${case.id}: expected ${case.expect.size}, got ${items.size} → ${items.map { it.name }}"
            }
        }
        assertTrue(failures.isEmpty(), "Split count failures:\n" + failures.joinToString("\n"))
    }

    @Test
    fun report() {
        val lines = mutableListOf<String>()
        var full = 0
        var count = 0
        for (case in fixtures.cases) {
            val items = splitter.split(case.text, case.localeHints)
            val countOk = items.size == case.expect.size
            val fullOk = countOk && matchesAll(case.expect, items)
            if (countOk) count++
            if (fullOk) full++
            val mark = if (fullOk) "PASS" else if (countOk) "COUNT" else "FAIL"
            lines += "%-5s %-11s %s".format(mark, case.id, items.joinToString(" | ") { describe(it) })
        }
        println("\n=== core:i18n local splitter vs fixtures/parse_cases.json ===")
        lines.forEach(::println)
        println("Item count right: $count/${fixtures.cases.size}; full match: $full/${fixtures.cases.size}\n")
    }

    /** Fixture rule: match by canonicalId; qty within 0.001 when given; unit exact when given. */
    private fun matchesAll(expected: List<Expected>, items: List<LocalSplitter.Item>): Boolean {
        val remaining = items.toMutableList()
        for (e in expected) {
            val hit = remaining.firstOrNull { item ->
                item.canonicalId == e.canonicalId &&
                    (e.qty == null || (item.qty != null && abs(item.qty - e.qty) < 0.001)) &&
                    (e.unit == null || item.unit?.wire == e.unit)
            } ?: return false
            remaining.remove(hit)
        }
        return remaining.isEmpty()
    }

    private fun describe(item: LocalSplitter.Item): String =
        buildString {
            append(item.name)
            append(" [").append(item.canonicalId ?: "?")
            item.qty?.let { append(' ').append(it) }
            item.unit?.let { append(' ').append(it.wire) }
            append(']')
        }
}
