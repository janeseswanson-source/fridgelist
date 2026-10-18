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
