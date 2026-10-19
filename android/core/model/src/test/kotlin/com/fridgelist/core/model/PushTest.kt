package com.fridgelist.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PushTest {

    @Test
    fun tripUpdateMatchesContractExample() {
        val msg = PushMessage.parse(
            mapOf(
                "type" to "trip_update", "householdId" to "h_1", "tripId" to "t_123", "status" to "shopping",
                "storeName" to "Migros", "remaining" to "7", "done" to "5",
                "nextItems" to "[\"حليب\",\"خبز عربي\",\"بيض\"]", "lastCallUntil" to "1760780600",
                "latestAddBy" to "أمي", "latestAddName" to "لبنة",
            ),
        ) as PushMessage.TripUpdate
        assertEquals(listOf("حليب", "خبز عربي", "بيض"), msg.nextItems)
        assertEquals(7, msg.remaining)
        assertEquals(TripStatus.SHOPPING, msg.status)
        assertEquals(1760780600L, msg.lastCallUntil)
        assertEquals("لبنة", msg.latestAddName)
    }

    @Test
    fun optionalKeysMayBeAbsent() {
        val msg = PushMessage.parse(mapOf("type" to "trip_update", "householdId" to "h", "tripId" to "t", "nextItems" to "not json")) as PushMessage.TripUpdate
        assertEquals(emptyList(), msg.nextItems)
        assertNull(msg.lastCallUntil)
        assertNull(msg.latestAddBy)
    }

    @Test
    fun tripHeadingCarriesV2TitleAndBody() {
        val msg = PushMessage.parse(
            mapOf(
                "type" to "trip_heading", "householdId" to "h", "tripId" to "t", "listId" to "l", "lastCallUntil" to "1760780600",
                "title" to "Ali is heading to Migros", "body" to "Add anything in the next 10 min",
            ),
        ) as PushMessage.TripHeading
        assertEquals("l", msg.listId)
        assertEquals("Ali is heading to Migros", msg.title)
    }

    @Test
    fun unknownAndBrokenMessagesNeverCrash() {
        assertEquals(PushMessage.Unknown("h", "usuals_suggestion"), PushMessage.parse(mapOf("type" to "usuals_suggestion", "householdId" to "h")))
        assertNull(PushMessage.parse(mapOf("type" to "trip_heading")), "no household")
        assertNull(PushMessage.parse(mapOf("type" to "trip_heading", "householdId" to "h")), "no trip")
    }
}
