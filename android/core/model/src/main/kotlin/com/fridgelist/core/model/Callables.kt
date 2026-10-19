package com.fridgelist.core.model

import kotlinx.serialization.Serializable

/**
 * Request and response bodies of the callables (CONTRACT §4). Field names are the wire names.
 * Enum-typed fields stay strings on the wire; use the typed accessors. Times are Unix seconds.
 */

@Serializable
data class CreateHouseholdRequest(
    val name: String,
    val defaultLanguage: String,
    val displayName: String,
    val language: String,
    val uiLanguage: String,
)

@Serializable
data class CreateHouseholdResponse(val householdId: String, val listId: String)

@Serializable
data class CreateInviteRequest(val householdId: String, val role: String)

@Serializable
data class CreateInviteResponse(val code: String, val url: String, val expiresAt: Long)

@Serializable
data class JoinHouseholdRequest(val code: String, val displayName: String, val language: String)

@Serializable
data class JoinHouseholdResponse(val householdId: String, val role: String) {
    val roleEnum: Role get() = Role.fromWire(role)
}

@Serializable
data class ParseItemsRequest(val householdId: String, val text: String, val localeHints: List<String>)

@Serializable
data class ParseItemsResponse(
    val items: List<ParsedItem> = emptyList(),
    val detectedLang: String? = null,
    /** `catalog` | `llm` | `mixed` */
    val source: String? = null,
)

@Serializable
data class TranscribeAudioRequest(
    val audioBase64: String,
    /** `audio/ogg` | `audio/mp4` | `audio/wav` */
    val mimeType: String,
    val localeHints: List<String>,
)

@Serializable
data class TranscribeAudioResponse(val text: String, val locale: String? = null, val confidence: Double = 0.0)

@Serializable
data class StartTripRequest(
    val householdId: String,
    /** `heading` | `shopping` */
    val status: String,
    val auto: Boolean,
    val storeId: String? = null,
    val storeName: String? = null,
    /** 0 | 5 | 10 | 15 */
    val lastCallMinutes: Int? = null,
    val listIds: List<String>? = null,
    val platform: String,
    val deviceId: String,
)

@Serializable
data class StartTripResponse(val tripId: String)

@Serializable
data class UpdateTripRequest(
    val householdId: String,
    val tripId: String,
    /** `shopping` | `done` | `cancelled` */
    val status: String,
)

@Serializable
data class TripSummary(val done: Int = 0, val missing: Int = 0)

@Serializable
data class UpdateTripResponse(val ok: Boolean = true, val summary: TripSummary? = null)

@Serializable
data class NearbyStoresRequest(val lat: Double, val lng: Double, val radiusM: Int? = null, val limit: Int? = null)

@Serializable
data class NearbyStore(val storeId: String, val name: String, val chainId: String? = null, val lat: Double, val lng: Double, val distanceM: Double)

@Serializable
data class NearbyStoresResponse(val stores: List<NearbyStore> = emptyList())

/** CONTRACT §4 `addStore`: a store missing from the database; works at once for this household. */
@Serializable
data class AddStoreRequest(val name: String, val lat: Double, val lng: Double, val chainId: String? = null)

@Serializable
data class AddStoreResponse(val storeId: String)

/** Allowed `startTrip.lastCallMinutes` values (CONTRACT §4). */
val LAST_CALL_MINUTES: List<Int> = listOf(0, 5, 10, 15)

/** Limits from CONTRACT §3 that the UI validates before calling the server. */
object Limits {
    const val DISPLAY_NAME_MAX = 40
    const val HOUSEHOLD_NAME_MAX = 40
    const val LIST_NAME_MAX = 40
    const val ITEM_NAME_MAX = 80
    const val ITEM_NOTES_MAX = 120
    const val QTY_MAX = 999.0
    const val PARSE_TEXT_MAX = 500

    /** Below this `ParsedItem.confidence` the app asks which meaning was meant (prompt A3). */
    const val AMBIGUOUS_CONFIDENCE = 0.6

    /** CONTRACT §4 `transcribeAudio`: ≤ 15 s audio, `audioBase64` ≤ 1 MB. */
    const val AUDIO_MAX_SECONDS = 15
    const val AUDIO_BASE64_MAX_BYTES = 1_000_000
}

/** Roles an invite may grant (CONTRACT §3.12). */
val INVITABLE_ROLES: List<Role> = listOf(Role.ADULT, Role.HELPER, Role.KID)
