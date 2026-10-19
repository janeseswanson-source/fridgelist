package com.fridgelist.core.i18n

import com.fridgelist.core.model.ContractJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * `fixtures/split_cases.json`: the exact output of the local splitter and normalizer for a fixed
 * set of inputs (CLAUDE.md rule 6). Android is the reference implementation; the backend
 * (TypeScript) and FridgeKit (Swift) must produce the same output for every case.
 *
 * Regenerate after an intended behaviour change: `./gradlew :core:i18n:test -PwriteFixtures=true`,
 * then review the diff and note it in docs/PROGRESS.md.
 */
class SplitCasesFixtureTest {

    private data class Input(val text: String, val hints: List<String>, val aliases: Map<String, String> = emptyMap())

    private val inputs: List<Input> by lazy {
        val fixtureCases = ContractJson.parseToJsonElement(File(dir, "parse_cases.json").readText())
            .jsonObject.getValue("cases").jsonArray.map { case ->
                val o = case.jsonObject
                Input(o.getValue("text").jsonPrimitive.content, o.getValue("localeHints").jsonArray.map { it.jsonPrimitive.content })
            }
        fixtureCases + EXTRA
    }

    private val dir: File get() = File(System.getProperty("fixtures.dir") ?: error("fixtures.dir not set by Gradle"))

    private fun generate(): JsonObject {
        val splitter = LocalSplitter()
        return buildJsonObject {
            put("version", 1)
            put(
                "howToUse",
                "Generated from the Android reference (core:i18n SplitCasesFixtureTest). For each split case, " +
                    "LocalSplitter.split(text, localeHints, aliases) must return exactly `expect`, in order: name " +
                    "(as typed, after invisible-mark cleanup), qty (number or null), unit (contract Unit or null), " +
                    "canonicalId (or null) and ambiguity ({wordKey, candidates} or null). matchKey cases: " +
                    "Normalize.matchKey(text, turkish) == key and AliasId.of(key) == aliasId.",
            )
            put(
                "split",
                buildJsonArray {
                    for (input in inputs) {
                        add(
                            buildJsonObject {
                                put("text", input.text)
                                put("localeHints", JsonArray(input.hints.map(::JsonPrimitive)))
                                if (input.aliases.isNotEmpty()) {
                                    put("aliases", JsonObject(input.aliases.mapValues { JsonPrimitive(it.value) }))
                                }
                                put(
                                    "expect",
                                    JsonArray(
                                        splitter.split(input.text, input.hints, input.aliases).map { item ->
                                            buildJsonObject {
                                                put("name", item.name)
                                                put("qty", item.qty?.let(::JsonPrimitive) ?: JsonNull)
                                                put("unit", item.unit?.wire?.let(::JsonPrimitive) ?: JsonNull)
                                                put("canonicalId", item.canonicalId?.let(::JsonPrimitive) ?: JsonNull)
                                                put(
                                                    "ambiguity",
                                                    item.ambiguity?.let { a ->
                                                        buildJsonObject {
                                                            put("wordKey", a.wordKey)
                                                            put("candidates", JsonArray(a.candidates.map(::JsonPrimitive)))
                                                        }
                                                    } ?: JsonNull,
                                                )
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    }
                },
            )
            put(
                "matchKey",
                buildJsonArray {
                    for ((text, turkish) in MATCH_KEYS) {
                        val key = Normalize.matchKey(text, turkish)
                        add(
                            buildJsonObject {
                                put("text", text)
                                put("turkish", turkish)
                                put("key", key)
                                put("aliasId", AliasId.of(key))
                            },
                        )
                    }
                },
            )
        }
    }

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    @Test
    fun splitCasesFixtureMatchesTheReference() {
        val file = File(dir, "split_cases.json")
        val generated = generate()
        if (System.getProperty("fixtures.write") == "true") {
            file.writeText(json.encodeToString(JsonElement.serializer(), generated) + "\n", Charsets.UTF_8)
        }
        val stored = ContractJson.parseToJsonElement(file.readText(Charsets.UTF_8))
        assertEquals(stored, generated, "fixtures/split_cases.json is out of date: regenerate with -PwriteFixtures=true and review")
    }

    private companion object {
        val EXTRA = listOf(
            // Splitter unit-test inputs.
            Input("٢ كيلو بَندورة", listOf("ar")),
            Input("خبز وحليب", listOf("ar")),
            Input("ورق تواليت", listOf("ar")),
            Input("جيب ٢ كيلو بندورة وعلبة لبنة", listOf("ar-SY")),
            Input("2 ekmek, yarım kilo beyaz peynir ve süt", listOf("tr")),
            Input("domates biber patlıcan", listOf("tr")),
            Input("yumurta 30 tane", listOf("tr")),
            Input("sugar 1kg", listOf("en")),
            Input("Pampers x2", listOf("en")),
            Input("Pampers size 4", listOf("en")),
            Input("2 bags of rice", listOf("en")),
            Input("half a dozen eggs", listOf("en")),
            Input("a dozen eggs", listOf("en")),
            Input("on bir yumurta", listOf("tr")),
            Input("iki buçuk kilo un", listOf("tr")),
            Input("كيلو ونص رز", listOf("ar")),
            Input("نص كيلو لحمة", listOf("ar")),
            Input("علبة حمص", listOf("ar")),
            Input("كرتونة بيض", listOf("ar")),
            Input("2 kutu ton balığı", listOf("tr")),
            Input("لبن", listOf("ar-EG")),
            Input("لبن", listOf("ar-SY")),
            Input("لبن", listOf("ar-SA")),
            Input("عيش", listOf("ar")),
            Input("العيش", listOf("ar")),
            Input("عيش بسمتي", listOf("ar")),
            Input("بطاطا", listOf("ar-EG")),
            Input("عيش", listOf("ar"), mapOf("عيش" to "rice")),
            Input("2 عيش", listOf("ar"), mapOf("عيش" to "bread")),
            Input("we need milk and don't forget the bread", listOf("en")),
            Input("lütfen 2 süt", listOf("tr")),
            Input("بدنا ٣ حبات ليمون", listOf("ar")),
            Input("1,5 kg peynir", listOf("tr")),
            Input("1, 5 milk", listOf("en")),
            Input("süt,ekmek", listOf("tr")),
            Input("٢٫٥ كيلو رز", listOf("ar")),
            Input("۳ نان", listOf("ar")),
            Input("SÜT", listOf("tr")),
            Input("İNCİR", listOf("tr")),
            Input("ISPANAK", listOf("tr")),
            Input("Milk & Eggs + bread; tea", listOf("en")),
            Input("حليب، خبز؛ بيض", listOf("ar")),
            // QA edge cases (2026-10-19): invisible marks, no-break spaces, odd numbers, nothing to add.
            Input("\u200Fحليب\u200F", listOf("ar")),
            Input("milk\u200B", listOf("en")),
            Input("2\u00A0süt", listOf("tr")),
            Input("\u202Bخبز\u202C و\u2066بيض\u2069", listOf("ar")),
            Input("0 milk", listOf("en")),
            Input("-2 milk", listOf("en")),
            Input("1.5.2 milk", listOf("en")),
            Input("2 2 milk", listOf("en")),
            Input("99999999999 milk", listOf("en")),
            Input("dozen dozen eggs", listOf("en")),
            Input("half half milk", listOf("en")),
            Input("كيلو 2 طماطم", listOf("ar")),
            Input("Pampers x2 x3", listOf("en")),
            Input("2", listOf("en")),
            Input("2 kg", listOf("en")),
            Input("x2", listOf("en")),
            Input("we need", listOf("en")),
            Input("جيب", listOf("ar")),
            Input("   ", listOf("en")),
            Input(" , ، و and ", listOf("ar", "en")),
            Input("🍎🍎", listOf("en")),
            Input("milk " + "and ".repeat(20) + "bread", listOf("en")),
        )

        val MATCH_KEYS = listOf(
            "حليب" to false,
            "بَندورة" to false,
            "الخبز" to false,
            "إبريق" to false,
            "مستشفى" to false,
            "طماطة" to false,
            "ـحليبـ" to false,
            "٢٫٥" to false,
            "۱۲" to false,
            "SÜT" to true,
            "İNCİR" to true,
            "ISPANAK" to true,
            "ISPANAK" to false,
            "1,5 KG" to true,
            "  çok   boşluk  " to true,
            "Milk  Chocolate" to false,
            "\u200Fحليب" to false,
            "\u200Cنان" to false,
            "a\u202F\u2007b" to false,
            "عيش" to false,
        )
    }
}
