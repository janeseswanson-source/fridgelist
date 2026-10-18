package com.fridgelist.core.data.auth

import com.fridgelist.core.data.firebase.AppEnvironment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class AuthUser(val uid: String, val displayName: String?)

interface AuthRepository {
    /** The signed-in user, or null. Emits on every sign-in and sign-out. */
    val user: Flow<AuthUser?>

    fun currentUser(): AuthUser?

    /** Exchanges a Google ID token (from Credential Manager) for a Firebase session. */
    suspend fun signInWithGoogleIdToken(idToken: String): AuthUser

    /**
     * dev flavor only: signs a named test user into the Auth emulator with an unsigned Google
     * credential (the emulator accepts these; production Auth never does).
     */
    suspend fun signInEmulatorTestUser(name: String): AuthUser

    suspend fun signOut()
}

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val environment: AppEnvironment,
) : AuthRepository {

    override val user: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAuthUser()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    override fun currentUser(): AuthUser? = auth.currentUser?.toAuthUser()

    override suspend fun signInWithGoogleIdToken(idToken: String): AuthUser {
        val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        return checkNotNull(result.user).toAuthUser()
    }

    override suspend fun signInEmulatorTestUser(name: String): AuthUser {
        check(environment.useEmulators) { "Test users exist only on the Auth emulator" }
        val slug = testUserSlug(name)
        val fakeIdToken = buildJsonObject {
            put("sub", "test-$slug")
            put("email", "$slug@test.fridgelist.invalid")
            put("email_verified", true)
            put("name", name.trim())
        }.toString()
        return signInWithGoogleIdToken(fakeIdToken)
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    private fun com.google.firebase.auth.FirebaseUser.toAuthUser() = AuthUser(uid, displayName)

    companion object {
        /** Stable per name, so "Mama" signs in as the same emulator user every time. */
        fun testUserSlug(name: String): String {
            val ascii = name.trim().lowercase(Locale.ROOT).map { c ->
                if (c in 'a'..'z' || c in '0'..'9') c else '-'
            }.joinToString("").trim('-')
            return ascii.ifEmpty { "u" + name.trim().hashCode().toUInt().toString(16) }
        }
    }
}
