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

/** CONTRACT §4 `createShareLink` (owner or adult): a fridge tag, QR or link for one list. */
@Serializable
data class CreateShareLinkRequest(
    val householdId: String,
    val listId: String,
    /** `view` | `add` */
    val scope: String,
    /** `nfc` | `qr` | `link` */
    val kind: String,
    val expiresInDays: Int? = null,
)

@Serializable
data class CreateShareLinkResponse(val token: String, val url: String)

/** CONTRACT §4 `resolveShareLink` (public). */
@Serializable
data class ResolveShareLinkRequest(val token: String, val lang: String? = null)

@Serializable
data class GuestItem(val id: String, val title: String, val subtitle: String? = null, val category: String = "other", val status: String = "open")

/** v5: present only when the signed-in caller is a member of the link's household. */
@Serializable
data class ShareMember(val householdId: String, val listId: String)

@Serializable
data class ResolveShareLinkResponse(
    val householdName: String,
    val listName: String,
    val scope: String,
    val items: List<GuestItem> = emptyList(),
    val member: ShareMember? = null,
) {
    val canAdd: Boolean get() = ShareScope.fromWire(scope) == ShareScope.ADD
}

/** CONTRACT §4 `guestAddItem` (public, scope `add`). */
@Serializable
data class GuestAddItemRequest(val token: String, val text: String, val guestName: String? = null)

@Serializable
data class GuestAddItemResponse(val added: Int = 0)

/** CONTRACT §4 `exportMyData`: a signed URL to a JSON file, valid 24 h. 3 per day. */
@Serializable
data class ExportMyDataResponse(val url: String)

/** CONTRACT §4 `deleteMyAccount`: an owner with other members names who takes over. */
@Serializable
data class DeleteMyAccountRequest(val transferOwnershipTo: String? = null)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
class EmptyRequest

/** CONTRACT §4 `suggestMeals` (10 calls/hour/household). */
@Serializable
data class SuggestMealsRequest(
    val householdId: String,
    val people: Int,
    /** `Occasion` wire value. */
    val occasion: String,
    val cuisines: List<String>? = null,
    val days: Int? = null,
    val exclude: List<String>? = null,
    val lang: String,
)

@Serializable
data class SuggestedMeal(
    val mealId: String,
    val title: String,
    val description: String = "",
    val servings: Int,
    val cuisine: String,
    val tags: List<String> = emptyList(),
)

@Serializable
data class SuggestMealsResponse(val meals: List<SuggestedMeal> = emptyList())

/** CONTRACT §4 `mealToItems`: no writes; the app confirms, then writes with `addedVia: meal_plan`. */
@Serializable
data class MealToItemsRequest(val householdId: String, val listId: String, val mealIds: List<String>, val people: Int, val lang: String)

/** `reason`: `on_list` | `recently_bought`. */
@Serializable
data class SkippedIngredient(val name: String, val reason: String)

@Serializable
data class MealToItemsResponse(val items: List<ParsedItem> = emptyList(), val skipped: List<SkippedIngredient> = emptyList())

/** PRODUCT_SPEC F11 / CONTRACT §4 ranges. */
object PlannerLimits {
    const val PEOPLE_MIN = 2
    const val PEOPLE_MAX = 30
    const val DAYS_MIN = 1
    const val DAYS_MAX = 7
}

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

    /** CONTRACT §4 `guestAddItem`: text ≤ 200, guestName ≤ 30. */
    const val GUEST_TEXT_MAX = 200
    const val GUEST_NAME_MAX = 30

    /** Below this `ParsedItem.confidence` the app asks which meaning was meant (prompt A3). */
    const val AMBIGUOUS_CONFIDENCE = 0.6

    /** CONTRACT §4 `transcribeAudio`: ≤ 15 s audio, `audioBase64` ≤ 1 MB. */
    const val AUDIO_MAX_SECONDS = 15
    const val AUDIO_BASE64_MAX_BYTES = 1_000_000
}

/** Roles an invite may grant (CONTRACT §3.12). */
val INVITABLE_ROLES: List<Role> = listOf(Role.ADULT, Role.HELPER, Role.KID)
