package com.fridgelist.core.data.user

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

interface UserRepository {
    /**
     * Writes the fields a user owns on `users/{uid}` (CONTRACT §3.1: `displayName`,
     * `uiLanguage`, `language`). Offline-first: queued locally, synced later, never awaited.
     */
    fun saveProfile(uid: String, displayName: String, language: String, uiLanguage: String)

    /** `users/{uid}.householdIds` from the server, or null when it can't be reached quickly. */
    suspend fun householdIdsFromServer(uid: String): List<String>?
}

@Singleton
class FirestoreUserRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : UserRepository {

    override fun saveProfile(uid: String, displayName: String, language: String, uiLanguage: String) {
        firestore.collection("users").document(uid)
            .set(
                mapOf("displayName" to displayName, "language" to language, "uiLanguage" to uiLanguage),
                SetOptions.merge(),
            )
            // Field names only: never log the user's name (CLAUDE.md rule 4).
            .addOnFailureListener { Log.w(TAG, "users profile write rejected: ${it.javaClass.simpleName}") }
    }

    override suspend fun householdIdsFromServer(uid: String): List<String>? =
        withTimeoutOrNull(SERVER_TIMEOUT_MS) {
            runCatching {
                val snapshot = firestore.collection("users").document(uid).get(Source.SERVER).await()
                (snapshot.get("householdIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
            }.getOrNull()
        }

    private companion object {
        const val TAG = "UserRepository"
        const val SERVER_TIMEOUT_MS = 5_000L
    }
}
