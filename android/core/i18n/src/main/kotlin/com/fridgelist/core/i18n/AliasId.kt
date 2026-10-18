package com.fridgelist.core.i18n

import java.security.MessageDigest

/** CONTRACT §3.16: `aliasId` = first 32 hex characters of the SHA-256 of the normalized text. */
object AliasId {
    /** The normalized text stored in the alias `text` field. */
    fun normalizedText(text: String, turkish: Boolean = false): String = Normalize.matchKey(text, turkish)

    fun of(normalizedText: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizedText.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
