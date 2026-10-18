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

/** Limits from CONTRACT §3 that the UI validates before calling the server. */
object Limits {
    const val DISPLAY_NAME_MAX = 40
    const val HOUSEHOLD_NAME_MAX = 40
    const val LIST_NAME_MAX = 40
}

/** Roles an invite may grant (CONTRACT §3.12). */
val INVITABLE_ROLES: List<Role> = listOf(Role.ADULT, Role.HELPER, Role.KID)
