package com.fridgelist.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinksTest {
    private val domain = "links.example.com"

    @Test
    fun inviteCodeNormalizes() {
        assertEquals("ABCD2345", InviteCode.normalize(" abcd-2345 "))
        assertEquals("ABCD2345", InviteCode.normalize("abcd ٢٣٤٥"))
    }

    @Test
    fun inviteCodeValidation() {
        assertTrue(InviteCode.isValid("ABCD2345"))
        assertFalse(InviteCode.isValid("ABCD234"), "too short")
        assertFalse(InviteCode.isValid("ABCD23450"), "too long")
        assertFalse(InviteCode.isValid("ABCD2340"), "0 is not in the alphabet")
        assertFalse(InviteCode.isValid("ABCDI234"), "I is not in the alphabet")
    }

    @Test
    fun joinCodeFromLink() {
        assertEquals("ABCD2345", Links.joinCode("https://links.example.com/join/ABCD2345", domain))
        assertEquals("ABCD2345", Links.joinCode("https://LINKS.example.com/join/abcd2345/", domain))
    }

    @Test
    fun joinCodeFromBareCode() {
        assertEquals("ABCD2345", Links.joinCode("abcd2345", domain))
    }

    @Test
    fun joinCodeRejectsOtherLinks() {
        assertNull(Links.joinCode("https://evil.example.org/join/ABCD2345", domain))
        assertNull(Links.joinCode("http://links.example.com/join/ABCD2345", domain))
        assertNull(Links.joinCode("https://links.example.com/k/ABCD2345", domain))
        assertNull(Links.joinCode("https://links.example.com/join/ABCD2345/extra", domain))
        assertNull(Links.joinCode("https://links.example.com/join/TOOSHORT1", domain))
        assertNull(Links.joinCode("hello world", domain))
    }

    @Test
    fun joinUrlRoundTrips() {
        val url = Links.joinUrl("ABCD2345", domain)
        assertEquals("https://links.example.com/join/ABCD2345", url)
        assertEquals("ABCD2345", Links.joinCode(url, domain))
    }

    @Test
    fun shopLink() {
        assertEquals("t_123", Links.shopTripId("fridgelist://shop?tripId=t_123", "fridgelist"))
        assertNull(Links.shopTripId("fridgelist://add?listId=l1", "fridgelist"))
        assertNull(Links.shopTripId("https://shop?tripId=t_1", "fridgelist"))
        assertNull(Links.shopTripId("fridgelist://shop?tripId=a/b", "fridgelist"))
        assertNull(Links.shopTripId("fridgelist://shop", "fridgelist"))
    }

    @Test
    fun callableShapesMatchContract() {
        val json = ContractJson.encodeToString(
            CreateHouseholdRequest.serializer(),
            CreateHouseholdRequest("بيتنا", "ar", "Yasser", "ar", "ar"),
        )
        assertEquals(
            """{"name":"بيتنا","defaultLanguage":"ar","displayName":"Yasser","language":"ar","uiLanguage":"ar"}""",
            json,
        )
        val invite = ContractJson.decodeFromString(
            CreateInviteResponse.serializer(),
            """{"code":"ABCD2345","url":"https://links.example.com/join/ABCD2345","expiresAt":1761384800}""",
        )
        assertEquals(1761384800L, invite.expiresAt)
        val join = ContractJson.decodeFromString(JoinHouseholdResponse.serializer(), """{"householdId":"h1","role":"helper"}""")
        assertEquals(Role.HELPER, join.roleEnum)
    }
}
