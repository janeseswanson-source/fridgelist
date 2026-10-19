package com.fridgelist.core.data

import com.fridgelist.core.data.auth.FirebaseAuthRepository
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.FirebaseCallableClient
import com.fridgelist.core.data.callable.JsonValues
import com.fridgelist.core.data.device.DeviceRegistrar
import com.fridgelist.core.data.firestore.Mappers
import com.fridgelist.core.data.session.OnboardingStep
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.model.ContractJson
import com.fridgelist.core.model.CreateHouseholdRequest
import com.fridgelist.core.model.Plan
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.TripStatus
import com.google.firebase.Timestamp
import com.google.firebase.functions.FirebaseFunctionsException.Code
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Robolectric: FirebaseFunctionsException.Code initializes android.util classes.
@RunWith(RobolectricTestRunner::class)
class DataLogicTest {

    @Test
    fun requestBecomesPlainMapForTheFunctionsSdk() {
        val body = ContractJson.encodeToJsonElement(
            CreateHouseholdRequest.serializer(),
            CreateHouseholdRequest("Ev", "tr", "Ayşe", "tr", "tr"),
        )
        assertEquals(
            mapOf("name" to "Ev", "defaultLanguage" to "tr", "displayName" to "Ayşe", "language" to "tr", "uiLanguage" to "tr"),
            JsonValues.toPlain(body),
        )
    }

    @Test
    fun plainResponseBecomesJson() {
        val json = JsonValues.fromPlain(mapOf("code" to "ABCD2345", "expiresAt" to 1761384800, "nested" to listOf(1, true, null)))
        assertEquals(1761384800L, json.jsonObject["expiresAt"]!!.jsonPrimitive.long)
        assertEquals(mapOf("code" to "ABCD2345", "expiresAt" to 1761384800L, "nested" to listOf(1L, true, null)), JsonValues.toPlain(json))
    }

    @Test
    fun functionErrorCodesMapToContractErrors() {
        assertEquals(ApiError.NOT_FOUND, FirebaseCallableClient.mapCode(Code.NOT_FOUND))
        assertEquals(ApiError.FAILED_PRECONDITION, FirebaseCallableClient.mapCode(Code.FAILED_PRECONDITION))
        assertEquals(ApiError.PERMISSION_DENIED, FirebaseCallableClient.mapCode(Code.PERMISSION_DENIED))
        assertEquals(ApiError.RESOURCE_EXHAUSTED, FirebaseCallableClient.mapCode(Code.RESOURCE_EXHAUSTED))
        assertEquals(ApiError.NETWORK, FirebaseCallableClient.mapCode(Code.UNAVAILABLE))
        assertEquals(ApiError.UNKNOWN, FirebaseCallableClient.mapCode(Code.INTERNAL))
        // Offline / connection refused: the SDK says INTERNAL but wraps an IOException.
        assertEquals(ApiError.NETWORK, FirebaseCallableClient.map(Code.INTERNAL, RuntimeException(java.net.ConnectException("refused"))))
        assertEquals(ApiError.UNKNOWN, FirebaseCallableClient.map(Code.INTERNAL, IllegalStateException("bug")))
    }

    @Test
    fun memberMappingIsLenient() {
        val member = Mappers.member(
            "u1",
            mapOf(
                "role" to "adult",
                "displayName" to "Ayşe",
                "language" to "tr",
                "notify" to mapOf("tripArrived" to true),
                "presence" to mapOf("tripId" to "t1", "status" to "shopping", "storeName" to "Migros"),
                "joinedAt" to Timestamp(1760780000, 0),
            ),
        )
        assertEquals(Role.ADULT, member.role)
        assertTrue(member.notify.tripArrived)
        assertTrue(member.notify.tripHeading, "missing keys keep CONTRACT defaults")
        assertEquals(TripStatus.SHOPPING, member.presence?.status)
        assertEquals(1760780000L, member.joinedAt)

        val odd = Mappers.member("u2", mapOf("role" to "superuser", "presence" to mapOf("status" to "flying")))
        assertEquals(Role.KID, odd.role, "unknown role gets least privilege")
        assertNull(odd.presence)
    }

    @Test
    fun householdMapping() {
        val h = Mappers.household("h1", mapOf("name" to "بيتنا", "defaultLanguage" to "ar", "memberLanguages" to listOf("ar", "tr", 3)))
        assertEquals(listOf("ar", "tr"), h.memberLanguages)
        assertEquals(Plan.FREE, h.plan)
    }

    @Test
    fun membersSortOwnerFirst() {
        val members = listOf(
            Mappers.member("k", mapOf("role" to "kid", "displayName" to "Zed")),
            Mappers.member("h", mapOf("role" to "helper", "displayName" to "Maria")),
            Mappers.member("o", mapOf("role" to "owner", "displayName" to "Yasser")),
            Mappers.member("a", mapOf("role" to "adult", "displayName" to "Ayşe")),
        ).sortedWith(Mappers.memberOrder)
        assertEquals(listOf("o", "a", "h", "k"), members.map { it.uid })
    }

    @Test
    fun deviceFieldsMatchContract() {
        assertEquals(
            mapOf("platform" to "android", "fcmToken" to "tok", "appVersion" to "0.1.0", "locale" to "ar-SA"),
            DeviceRegistrar.fields("tok", "0.1.0", "ar-SA"),
        )
    }

    @Test
    fun testUserSlugIsStable() {
        assertEquals("mama", FirebaseAuthRepository.testUserSlug(" Mama "))
        assertEquals("ali-2", FirebaseAuthRepository.testUserSlug("Ali 2"))
        val arabic = FirebaseAuthRepository.testUserSlug("أمي")
        assertEquals(arabic, FirebaseAuthRepository.testUserSlug("أمي"))
        assertFalse(arabic.isEmpty())
    }

    @Test
    fun onboardingResumesAtTheRightStep() {
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.of(false, Session(householdId = "h")))
        assertEquals(OnboardingStep.PROFILE, OnboardingStep.of(true, Session()))
        assertEquals(OnboardingStep.HOUSEHOLD, OnboardingStep.of(true, Session("Ali", "ar")))
        assertEquals(OnboardingStep.NOTIFICATIONS, OnboardingStep.of(true, Session("Ali", "ar", "h")))
        assertEquals(OnboardingStep.DONE, OnboardingStep.of(true, Session("Ali", "ar", "h", notificationsAsked = true)))
    }

    @Test
    fun nearbyStoresGetsOnlyARoundedPosition() {
        val sent = com.fridgelist.core.data.trips.LocationPrivacy.coarse(com.fridgelist.core.model.NearbyStoresRequest(40.991538, 29.031142, 10_000, 100))
        kotlin.test.assertEquals(com.fridgelist.core.model.NearbyStoresRequest(40.99, 29.03, 10_000, 100), sent)
        kotlin.test.assertEquals(-24.7, com.fridgelist.core.data.trips.LocationPrivacy.round(-24.6956))
    }
}
