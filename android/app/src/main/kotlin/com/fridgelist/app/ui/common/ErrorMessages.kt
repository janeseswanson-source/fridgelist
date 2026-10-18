package com.fridgelist.app.ui.common

import android.util.Log
import androidx.annotation.StringRes
import com.fridgelist.app.R
import com.fridgelist.core.data.callable.ApiError
import com.fridgelist.core.data.callable.ApiException
import com.google.firebase.FirebaseNetworkException
import java.io.IOException

/** CONTRACT §8 client behaviour: a clear message, never a crash. */
object ErrorMessages {

    /** For invite-code joins, where `not-found` and `failed-precondition` have specific meanings. */
    @StringRes
    fun forJoin(error: Throwable): Int = when ((error as? ApiException)?.error) {
        ApiError.NOT_FOUND -> R.string.error_invite_not_found
        ApiError.FAILED_PRECONDITION -> R.string.error_invite_expired
        else -> general(error)
    }

    @StringRes
    fun general(error: Throwable): Int = when {
        error is ApiException -> when (error.error) {
            ApiError.NETWORK, ApiError.UNAVAILABLE -> R.string.error_network
            ApiError.PERMISSION_DENIED -> R.string.error_permission
            ApiError.RESOURCE_EXHAUSTED -> R.string.error_rate_limited
            else -> unexpected(error)
        }
        error is FirebaseNetworkException || error is IOException -> R.string.error_network
        else -> unexpected(error)
    }

    @StringRes
    private fun unexpected(error: Throwable): Int {
        // Class names only, never messages: they can echo user data (CLAUDE.md rule 4).
        Log.w(TAG, "unexpected ${error.javaClass.name} caused by ${error.cause?.javaClass?.name}")
        return R.string.error_generic
    }

    private const val TAG = "ErrorMessages"
}
