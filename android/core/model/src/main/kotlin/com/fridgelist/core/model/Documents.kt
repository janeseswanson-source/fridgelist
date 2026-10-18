package com.fridgelist.core.model

/**
 * Firestore documents (CONTRACT §3) as read by the app. Enum fields are typed with lenient parsing
 * done by the mapper in core:data; timestamps are Unix seconds here (the mapper converts
 * Firestore `Timestamp`). Optional contract fields are nullable.
 */

data class UserDoc(
    val uid: String,
    val displayName: String,
    val uiLanguage: String,
    val language: String,
    val householdIds: List<String> = emptyList(),
    val createdAt: Long? = null,
)

data class DeviceDoc(
    val deviceId: String,
    val platform: Platform,
    val fcmToken: String,
    val apnsPushToStartToken: String? = null,
    val appVersion: String,
    val locale: String,
    val updatedAt: Long? = null,
)

data class HouseholdDoc(
    val id: String,
    val name: String,
    val ownerUid: String,
    val defaultLanguage: String,
    val memberLanguages: List<String> = emptyList(),
    val plan: Plan = Plan.FREE,
    val createdAt: Long? = null,
)

data class NotifyPrefs(
    val tripHeading: Boolean = true,
    val tripArrived: Boolean = false,
    val itemMissing: Boolean = true,
    val usuals: Boolean = false,
)

data class Presence(
    val tripId: String,
    val status: TripStatus,
    val storeName: String,
    val since: Long? = null,
)

data class MemberDoc(
    val uid: String,
    val role: Role,
    val displayName: String,
    val language: String,
    val notify: NotifyPrefs = NotifyPrefs(),
    val presence: Presence? = null,
    val joinedAt: Long? = null,
)

data class ListDoc(
    val id: String,
    val name: String,
    val type: ListType,
    val emoji: String? = null,
    val order: Double = 0.0,
    val archived: Boolean = false,
    val createdAt: Long? = null,
)

data class ItemDoc(
    val id: String,
    val name: String,
    val lang: String? = null,
    val names: Map<String, String>? = null,
    val canonicalId: String? = null,
    /** Null until the server classifies the item (onItemCreated) or a member sets it. */
    val category: Category? = null,
    val categorySource: CategorySource = CategorySource.AUTO,
    val qty: Double? = null,
    val unit: ItemUnit? = null,
    val notes: String? = null,
    val photoUrl: String? = null,
    val urgent: Boolean = false,
    val status: ItemStatus = ItemStatus.OPEN,
    val addedBy: String,
    val addedVia: AddedVia? = null,
    val requestedBy: List<String> = emptyList(),
    val claimedBy: String? = null,
    val doneBy: String? = null,
    val doneAt: Long? = null,
    val doneTripId: String? = null,
    val sortKey: Double? = null,
    val recipeRef: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

data class TripCounts(val total: Int = 0, val done: Int = 0, val missing: Int = 0)

data class TripDoc(
    val id: String,
    val shopperUid: String,
    val shopperName: String,
    val shopperPlatform: Platform?,
    val shopperDeviceId: String,
    val status: TripStatus?,
    val storeId: String? = null,
    val storeName: String,
    val chainId: String? = null,
    val auto: Boolean = false,
    val listIds: List<String> = emptyList(),
    val lastCallUntil: Long? = null,
    val startedAt: Long? = null,
    val arrivedAt: Long? = null,
    val endedAt: Long? = null,
    val counts: TripCounts = TripCounts(),
)

data class AliasDoc(
    val id: String,
    val text: String,
    val canonicalId: String,
    val createdBy: String,
    val createdAt: Long? = null,
)
