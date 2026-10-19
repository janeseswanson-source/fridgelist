package com.fridgelist.app.fakes

import com.fridgelist.app.feature.onboarding.StepResolver
import com.fridgelist.app.feature.onboarding.UiLanguageProvider
import com.fridgelist.core.data.auth.AuthRepository
import com.fridgelist.core.data.auth.AuthUser
import com.fridgelist.core.data.device.DeviceRegistry
import com.fridgelist.core.data.household.HouseholdRepository
import com.fridgelist.core.data.session.ActiveShopping
import com.fridgelist.core.data.session.Session
import com.fridgelist.core.data.session.SessionStore
import com.fridgelist.core.data.user.UserRepository
import com.fridgelist.core.model.CreateHouseholdRequest
import com.fridgelist.core.model.CreateHouseholdResponse
import com.fridgelist.core.model.CreateInviteResponse
import com.fridgelist.core.model.HouseholdDoc
import com.fridgelist.core.model.JoinHouseholdRequest
import com.fridgelist.core.model.JoinHouseholdResponse
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeAuth(initial: AuthUser? = null) : AuthRepository {
    val current = MutableStateFlow(initial)
    var failWith: Exception? = null

    override val user: Flow<AuthUser?> = current
    override fun currentUser(): AuthUser? = current.value

    override suspend fun signInWithGoogleIdToken(idToken: String): AuthUser = signIn(AuthUser("google-uid", "Google User"))

    override suspend fun signInEmulatorTestUser(name: String): AuthUser = signIn(AuthUser("test-$name", name))

    private fun signIn(user: AuthUser): AuthUser {
        failWith?.let { throw it }
        current.value = user
        return user
    }

    override suspend fun signOut() {
        current.value = null
    }
}

class FakeSession(initial: Session = Session()) : SessionStore {
    val state = MutableStateFlow(initial)
    override val session: Flow<Session> = state
    override suspend fun saveProfile(name: String, itemLanguage: String) = state.update { it.copy(profileName = name, itemLanguage = itemLanguage) }
    override suspend fun setHousehold(householdId: String) = state.update { it.copy(householdId = householdId) }
    override suspend fun markNotificationsAsked() = state.update { it.copy(notificationsAsked = true) }
    override suspend fun startShopping(shopping: ActiveShopping) = state.update { it.copy(shopping = shopping) }
    override suspend fun endShopping() = state.update { it.copy(shopping = null) }
    override suspend fun clear() = state.update { Session() }
    override suspend fun deviceId(): String = "device-1"
}

class FakeHouseholds : HouseholdRepository {
    var failWith: Exception? = null
    val created = mutableListOf<CreateHouseholdRequest>()
    val joined = mutableListOf<JoinHouseholdRequest>()
    val invites = mutableListOf<Pair<String, Role>>()
    val members = MutableStateFlow<Map<String, List<MemberDoc>>>(emptyMap())
    val households = MutableStateFlow<Map<String, HouseholdDoc>>(emptyMap())

    override suspend fun createHousehold(request: CreateHouseholdRequest): CreateHouseholdResponse {
        failWith?.let { throw it }
        created += request
        return CreateHouseholdResponse("h-new", "l-new")
    }

    override suspend fun joinHousehold(request: JoinHouseholdRequest): JoinHouseholdResponse {
        failWith?.let { throw it }
        joined += request
        return JoinHouseholdResponse("h-joined", "adult")
    }

    override suspend fun createInvite(householdId: String, role: Role): CreateInviteResponse {
        failWith?.let { throw it }
        invites += householdId to role
        return CreateInviteResponse("ABCD2345", "https://links.example.com/join/ABCD2345", 1761384800)
    }

    override fun household(householdId: String): Flow<HouseholdDoc?> = households.map { it[householdId] }
    override fun members(householdId: String): Flow<List<MemberDoc>> = members.map { it[householdId].orEmpty() }
}

class FakeUsers(private val serverHouseholds: Map<String, List<String>> = emptyMap()) : UserRepository {
    val profiles = mutableListOf<List<String>>()
    override fun saveProfile(uid: String, displayName: String, language: String, uiLanguage: String) {
        profiles += listOf(uid, displayName, language, uiLanguage)
    }
    override suspend fun householdIdsFromServer(uid: String): List<String>? = serverHouseholds[uid]
}

class FakeDevices : DeviceRegistry {
    val registered = mutableListOf<String>()
    override suspend fun register(uid: String, token: String?): Boolean {
        registered += uid
        return true
    }
}

fun steps(auth: FakeAuth, session: FakeSession) = StepResolver(auth, session)

fun uiLanguage(tag: String) = UiLanguageProvider { tag }

/** startTrip succeeds with [tripId], or throws [failWith] (offline / no backend). */
class FakeTrips(
    private val tripId: String? = "t1",
    private val failWith: Exception? = null,
    private val tripLists: Map<String, List<String>> = emptyMap(),
) : com.fridgelist.core.data.trips.TripRepository {
    val started = mutableListOf<com.fridgelist.core.model.StartTripRequest>()
    override suspend fun startTrip(request: com.fridgelist.core.model.StartTripRequest): com.fridgelist.core.model.StartTripResponse {
        started += request
        failWith?.let { throw it }
        return com.fridgelist.core.model.StartTripResponse(checkNotNull(tripId))
    }
    override suspend fun updateTrip(request: com.fridgelist.core.model.UpdateTripRequest) = com.fridgelist.core.model.UpdateTripResponse()
    override suspend fun tripListIds(householdId: String, tripId: String): List<String>? = tripLists[tripId]
}

class FakeTripEnder : com.fridgelist.app.work.TripEnder {
    val ended = mutableListOf<Pair<String, String>>()
    override fun endTrip(householdId: String, tripId: String) {
        ended += householdId to tripId
    }
}
