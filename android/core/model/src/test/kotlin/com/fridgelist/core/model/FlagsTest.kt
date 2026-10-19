package com.fridgelist.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class FlagsTest {

    @Test
    fun defaultsMatchTheBackendTemplate() {
        val path = checkNotNull(System.getProperty("remoteconfig.template")) { "run through Gradle" }
        val parameters = Json.parseToJsonElement(File(path).readText()).jsonObject.getValue("parameters").jsonObject
        val fromTemplate = parameters.mapValues { (_, p) ->
            p.jsonObject.getValue("defaultValue").jsonObject.getValue("value").jsonPrimitive.content
        }
        assertEquals(fromTemplate, Flags.DEFAULTS.mapValues { (_, v) -> v.toString() })
    }
}
