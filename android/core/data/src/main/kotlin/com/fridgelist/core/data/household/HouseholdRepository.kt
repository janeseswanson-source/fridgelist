package com.fridgelist.core.data.household

import com.fridgelist.core.data.callable.CallableClient
import com.fridgelist.core.data.callable.call
import com.fridgelist.core.data.firestore.Mappers
import com.fridgelist.core.model.CreateHouseholdRequest
import com.fridgelist.core.model.CreateHouseholdResponse
import com.fridgelist.core.model.CreateInviteRequest
import com.fridgelist.core.model.CreateInviteResponse
import com.fridgelist.core.model.HouseholdDoc
import com.fridgelist.core.model.JoinHouseholdRequest
import com.fridgelist.core.model.JoinHouseholdResponse
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.Role
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

interface HouseholdRepository {
    /** CONTRACT §4 `createHousehold`. Needs the network. */
    suspend fun createHousehold(request: CreateHouseholdRequest): CreateHouseholdResponse

    /** CONTRACT §4 `joinHousehold`. Needs the network. */
    suspend fun joinHousehold(request: JoinHouseholdRequest): JoinHouseholdResponse

    /** CONTRACT §4 `createInvite` (owner or adult). Needs the network. */
    suspend fun createInvite(householdId: String, role: Role): CreateInviteResponse

    /** Live household document; served from the offline cache when offline. */
    fun household(householdId: String): Flow<HouseholdDoc?>

    /** Live members, in display order; served from the offline cache when offline. */
    fun members(householdId: String): Flow<List<MemberDoc>>
}

@Singleton
class FirebaseHouseholdRepository @Inject constructor(
    private val callables: CallableClient,
    private val firestore: FirebaseFirestore,
) : HouseholdRepository {

    override suspend fun createHousehold(request: CreateHouseholdRequest): CreateHouseholdResponse =
        callables.call("createHousehold", request, CreateHouseholdRequest.serializer(), CreateHouseholdResponse.serializer())

    override suspend fun joinHousehold(request: JoinHouseholdRequest): JoinHouseholdResponse =
        callables.call("joinHousehold", request, JoinHouseholdRequest.serializer(), JoinHouseholdResponse.serializer())

    override suspend fun createInvite(householdId: String, role: Role): CreateInviteResponse =
        callables.call(
            "createInvite",
            CreateInviteRequest(householdId, role.wire),
            CreateInviteRequest.serializer(),
            CreateInviteResponse.serializer(),
        )

    override fun household(householdId: String): Flow<HouseholdDoc?> = callbackFlow {
        val registration = firestore.collection("households").document(householdId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.data?.let { Mappers.household(snapshot.id, it) })
            }
        awaitClose { registration.remove() }
    }

    override fun members(householdId: String): Flow<List<MemberDoc>> = callbackFlow {
        val registration = firestore.collection("households").document(householdId).collection("members")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val members = snapshot?.documents.orEmpty()
                    .mapNotNull { doc -> doc.data?.let { Mappers.member(doc.id, it) } }
                    .sortedWith(Mappers.memberOrder)
                trySend(members)
            }
        awaitClose { registration.remove() }
    }
}
