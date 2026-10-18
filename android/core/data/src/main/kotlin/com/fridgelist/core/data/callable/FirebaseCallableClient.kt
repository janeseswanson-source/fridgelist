package com.fridgelist.core.data.callable

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.FirebaseFunctionsException.Code
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseCallableClient @Inject constructor(
    private val functions: FirebaseFunctions,
) : CallableClient {

    override suspend fun call(name: String, body: JsonElement): JsonElement {
        try {
            val result = functions.getHttpsCallable(name)
                .apply { setTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .call(JsonValues.toPlain(body))
                .await()
            return JsonValues.fromPlain(result.getData())
        } catch (e: FirebaseFunctionsException) {
            val error = map(e.code, e.cause)
            // Function name and codes only; never request data (CLAUDE.md rule 4).
            Log.w(TAG, "$name failed: ${e.code} → $error (${e.cause?.javaClass?.simpleName})")
            throw ApiException(error, e.message, e)
        } catch (e: FirebaseNetworkException) {
            throw ApiException(ApiError.NETWORK, e.message, e)
        } catch (e: IOException) {
            throw ApiException(ApiError.NETWORK, e.message, e)
        }
    }

    companion object {
        private const val TAG = "Callable"
        private const val TIMEOUT_SECONDS = 20L

        /**
         * The SDK reports "never reached the server" (offline, connection refused) as INTERNAL
         * wrapping an IOException; that is a network problem, not a server bug.
         */
        fun map(code: Code, cause: Throwable?): ApiError {
            var c = cause
            while (c != null) {
                if (c is IOException) return ApiError.NETWORK
                c = c.cause
            }
            return mapCode(code)
        }

        /** CONTRACT §8. A callable that never reached a server surfaces as UNAVAILABLE/DEADLINE → NETWORK. */
        fun mapCode(code: Code): ApiError = when (code) {
            Code.UNAUTHENTICATED -> ApiError.UNAUTHENTICATED
            Code.PERMISSION_DENIED -> ApiError.PERMISSION_DENIED
            Code.INVALID_ARGUMENT, Code.OUT_OF_RANGE -> ApiError.INVALID_ARGUMENT
            Code.NOT_FOUND -> ApiError.NOT_FOUND
            Code.FAILED_PRECONDITION, Code.ALREADY_EXISTS, Code.ABORTED -> ApiError.FAILED_PRECONDITION
            Code.RESOURCE_EXHAUSTED -> ApiError.RESOURCE_EXHAUSTED
            Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED -> ApiError.NETWORK
            else -> ApiError.UNKNOWN
        }
    }
}
