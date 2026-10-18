package com.fridgelist.core.data.firestore

import com.fridgelist.core.model.HouseholdDoc
import com.fridgelist.core.model.MemberDoc
import com.fridgelist.core.model.NotifyPrefs
import com.fridgelist.core.model.Plan
import com.fridgelist.core.model.Presence
import com.fridgelist.core.model.Role
import com.fridgelist.core.model.TripStatus
import com.google.firebase.Timestamp

/**
 * Firestore maps → contract types. Lenient by design (CONTRACT §1): missing or unexpected fields
 * fall back to defaults instead of failing the whole list.
 */
object Mappers {

    fun household(id: String, data: Map<String, Any?>): HouseholdDoc = HouseholdDoc(
        id = id,
        name = data.string("name").orEmpty(),
        ownerUid = data.string("ownerUid").orEmpty(),
        defaultLanguage = data.string("defaultLanguage") ?: "en",
        memberLanguages = data.stringList("memberLanguages"),
        plan = Plan.fromWire(data.string("plan")),
        createdAt = data.seconds("createdAt"),
    )

    fun member(uid: String, data: Map<String, Any?>): MemberDoc = MemberDoc(
        uid = uid,
        role = Role.fromWire(data.string("role")),
        displayName = data.string("displayName").orEmpty(),
        language = data.string("language") ?: "en",
        notify = notify(data["notify"] as? Map<*, *>),
        presence = presence(data["presence"] as? Map<*, *>),
        joinedAt = data.seconds("joinedAt"),
    )

    private fun notify(map: Map<*, *>?): NotifyPrefs {
        val defaults = NotifyPrefs()
        if (map == null) return defaults
        return NotifyPrefs(
            tripHeading = map["tripHeading"] as? Boolean ?: defaults.tripHeading,
            tripArrived = map["tripArrived"] as? Boolean ?: defaults.tripArrived,
            itemMissing = map["itemMissing"] as? Boolean ?: defaults.itemMissing,
            usuals = map["usuals"] as? Boolean ?: defaults.usuals,
        )
    }

    private fun presence(map: Map<*, *>?): Presence? {
        val tripId = map?.get("tripId") as? String ?: return null
        val status = TripStatus.fromWire(map["status"] as? String) ?: return null
        return Presence(
            tripId = tripId,
            status = status,
            storeName = map["storeName"] as? String ?: "",
            since = (map["since"] as? Timestamp)?.seconds,
        )
    }

    /** Members sorted for display: owner first, then adults, helpers, kids; then by name. */
    val memberOrder: Comparator<MemberDoc> = compareBy<MemberDoc>(
        { listOf(Role.OWNER, Role.ADULT, Role.HELPER, Role.KID).indexOf(it.role) },
        { it.displayName.lowercase() },
    )

    private fun Map<String, Any?>.string(key: String): String? = this[key] as? String

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()

    private fun Map<String, Any?>.seconds(key: String): Long? = (this[key] as? Timestamp)?.seconds
}
