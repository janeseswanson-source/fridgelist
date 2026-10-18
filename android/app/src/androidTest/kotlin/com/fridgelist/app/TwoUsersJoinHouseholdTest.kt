package com.fridgelist.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.JsonValues
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.data.firebase.EmulatorConfig
import com.fridgelist.core.data.firebase.FUNCTIONS_REGION
import com.fridgelist.core.data.firestore.Mappers
import com.fridgelist.core.model.CreateHouseholdRequest
import com.fridgelist.core.model.CreateHouseholdResponse
import com.fridgelist.core.model.CreateInviteRequest
import com.fridgelist.core.model.CreateInviteResponse
import com.fridgelist.core.model.JoinHouseholdRequest
import com.fridgelist.core.model.JoinHouseholdResponse
import com.fridgelist.core.model.Role
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonElement
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.assertEquals

/**
 * A2 acceptance (prompt 02): two users create and join one household and see each other with
 * the right roles. Runs against the Emulator Suite with the backend's B3 callables:
 *
 *   cd backend && firebase emulators:start --project demo-fridgelist
 *   cd android && ./gradlew connectedDevDebugAndroidTest
 *
 * Skipped (not failed) while the Functions emulator is not reachable.
 */
@RunWith(AndroidJUnit4::class)
class TwoUsersJoinHouseholdTest {
    private val host = EmulatorConfig.DEFAULT_HOST
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun emulatorsRunning() {
        assumeTrue("Functions emulator not running on $host:${EmulatorConfig.FUNCTIONS_PORT} (needs backend B3)", reachable(EmulatorConfig.FUNCTIONS_PORT))
        assumeTrue("Firestore emulator not running", reachable(EmulatorConfig.FIRESTORE_PORT))
        assumeTrue("Auth emulator not running", reachable(EmulatorConfig.AUTH_PORT))
    }

    /** One independent Firebase app per user, so both can be signed in at once. */
    private inner class User(val name: String) {
        val app: FirebaseApp = FirebaseApp.getApps(context).firstOrNull { it.name == name } ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(EmulatorConfig.DEMO_PROJECT_ID)
                .setApplicationId(EmulatorConfig.DEMO_APP_ID)
                .setApiKey(EmulatorConfig.DEMO_API_KEY)
                .build(),
            name,
        ).also { app ->
            FirebaseAuth.getInstance(app).useEmulator(host, EmulatorConfig.AUTH_PORT)
            FirebaseFirestore.getInstance(app).useEmulator(host, EmulatorConfig.FIRESTORE_PORT)
            FirebaseFunctions.getInstance(app, FUNCTIONS_REGION).useEmulator(host, EmulatorConfig.FUNCTIONS_PORT)
        }
        val auth: FirebaseAuth = FirebaseAuth.getInstance(app)
        val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(app)
        val callables = object : CallableClient {
            override suspend fun call(name: String, body: JsonElement): JsonElement {
                val result = FirebaseFunctions.getInstance(app, FUNCTIONS_REGION).getHttpsCallable(name)
                    .call(JsonValues.toPlain(body)).await()
                return JsonValues.fromPlain(result.data)
            }
        }

        suspend fun signIn(): String {
            val token = """{"sub":"e2e-$name-${System.nanoTime()}","email":"$name@test.fridgelist.invalid","email_verified":true}"""
            return auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await().user!!.uid
        }
    }

    @Test
    fun ownerInvitesAdultAndBothSeeEachOther() = runBlocking {
        val owner = User("owner")
        val adult = User("adult")
        val ownerUid = owner.signIn()
        val adultUid = adult.signIn()

        val household = owner.callables.call(
            "createHousehold",
            CreateHouseholdRequest("بيت الاختبار", "ar", "Owner", "ar", "ar"),
            CreateHouseholdRequest.serializer(),
            CreateHouseholdResponse.serializer(),
        )
        val invite = owner.callables.call(
            "createInvite",
            CreateInviteRequest(household.householdId, Role.ADULT.wire),
            CreateInviteRequest.serializer(),
            CreateInviteResponse.serializer(),
        )
        val joined = adult.callables.call(
            "joinHousehold",
            JoinHouseholdRequest(invite.code, "Adult", "tr"),
            JoinHouseholdRequest.serializer(),
            JoinHouseholdResponse.serializer(),
        )
        assertEquals(household.householdId, joined.householdId)
        assertEquals(Role.ADULT, joined.roleEnum)

        for (user in listOf(owner, adult)) {
            val members = user.firestore.collection("households").document(household.householdId)
                .collection("members").get().await().documents
                .map { Mappers.member(it.id, it.data.orEmpty()) }
                .associate { it.uid to it.role }
            assertEquals(mapOf(ownerUid to Role.OWNER, adultUid to Role.ADULT), members, "as seen by ${user.name}")
        }
    }

    private fun reachable(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 1_000) }
    }.isSuccess
}
