package com.fridgelist.core.model

import java.net.URI
import java.util.Locale

/** Invite codes (CONTRACT §3.12): 8 characters from an alphabet without look-alikes (no 0/O, 1/I). */
object InviteCode {
    const val LENGTH = 8
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Uppercases, drops spaces and dashes, and turns Eastern Arabic digits into ASCII. */
    fun normalize(input: String): String =
        buildString {
            for (c in input.trim().uppercase(Locale.ROOT)) {
                when (c) {
                    in '٠'..'٩' -> append('0' + (c - '٠'))
                    in '۰'..'۹' -> append('0' + (c - '۰'))
                    ' ', '-', '\u00A0' -> Unit
                    else -> append(c)
                }
            }
        }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}

/** Parses the links in CONTRACT §6. */
object Links {
    /**
     * Returns the invite code from `https://{linkDomain}/join/{code}`, or from a bare code (as a
     * scanned QR may hold either). Null when the text is neither.
     */
    fun joinCode(text: String, linkDomain: String): String? {
        val trimmed = text.trim()
        val fromUrl = runCatching { URI(trimmed) }.getOrNull()?.takeIf { it.scheme != null }?.let { uri ->
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (!uri.host.equals(linkDomain, ignoreCase = true)) return null
            val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            if (segments.size != 2 || segments[0] != "join") return null
            segments[1]
        }
        val code = InviteCode.normalize(fromUrl ?: trimmed)
        return code.takeIf(InviteCode::isValid)
    }

    fun joinUrl(code: String, linkDomain: String): String = "https://$linkDomain/join/$code"

    /** CONTRACT §3.11: share tokens are 24 URL-safe characters. */
    const val SHARE_TOKEN_LENGTH = 24

    /** `https://{LINK_DOMAIN}/k/{token}` (fridge tag, QR) → the token. */
    fun shareToken(text: String, linkDomain: String): String? =
        pathSegments(text, linkDomain)?.takeIf { it.size == 2 && it[0] == "k" }?.get(1)
            ?.takeIf { it.length == SHARE_TOKEN_LENGTH && it.all { c -> c.isAsciiLetterOrDigit() || c == '_' || c == '-' } }

    fun shareUrl(linkDomain: String, token: String): String = "https://$linkDomain/k/$token"

    /** `https://{LINK_DOMAIN}/l/{householdId}/{listId}` → that list (members only). */
    data class ListLink(val householdId: String, val listId: String)

    fun listLink(text: String, linkDomain: String): ListLink? =
        pathSegments(text, linkDomain)?.takeIf { it.size == 3 && it[0] == "l" && isId(it[1]) && isId(it[2]) }
            ?.let { ListLink(it[1], it[2]) }

    /** Path segments of an https link on our domain; null for anything else. */
    private fun pathSegments(text: String, linkDomain: String): List<String>? {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals(linkDomain, ignoreCase = true)) return null
        return uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    }

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** `{URL_SCHEME}://add?listId={lid}&mode=voice|text` (CONTRACT §6): widgets, tile, shortcuts. */
    data class AddLink(val listId: String?, val voice: Boolean)

    fun add(text: String, urlScheme: String): AddLink? {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(urlScheme, ignoreCase = true) || uri.host != "add") return null
        val query = query(uri)
        val listId = query["listId"]?.takeIf(::isId)
        return AddLink(listId, voice = query["mode"] != "text")
    }

    fun addUrl(urlScheme: String, listId: String?, voice: Boolean): String =
        "$urlScheme://add?" + listOfNotNull(listId?.let { "listId=$it" }, "mode=" + if (voice) "voice" else "text").joinToString("&")

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&')
        .filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to it.substringAfter('=', "") }

    /** Firestore auto-IDs and our own IDs: letters, digits, _ and -. */
    private fun isId(value: String) = value.isNotEmpty() && value.length <= 128 && value.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' }

    /** `{URL_SCHEME}://shop?tripId={tid}` (CONTRACT §6): the trip to open in Shopping Mode. */
    fun shopTripId(text: String, urlScheme: String): String? {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(urlScheme, ignoreCase = true) || uri.host != "shop") return null
        return uri.rawQuery.orEmpty().split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first == "tripId" }?.second
            ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
    }
}
