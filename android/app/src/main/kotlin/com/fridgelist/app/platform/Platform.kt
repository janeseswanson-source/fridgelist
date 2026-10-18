package com.fridgelist.app.platform

import android.content.Context
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Sign in with Google through Credential Manager (ARCHITECTURE §8.1). */
object GoogleIdTokens {
    sealed interface Result {
        data class Token(val idToken: String) : Result
        data object Cancelled : Result
        data object NoAccount : Result
        data class Failed(val error: Throwable) : Result
    }

    /** [activityContext] must be an Activity: the account picker is shown over it. */
    suspend fun request(activityContext: Context, webClientId: String): Result {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId = webClientId).build())
            .build()
        return try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Result.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Result.Failed(IllegalStateException("Unexpected credential type ${credential.type}"))
            }
        } catch (e: GetCredentialCancellationException) {
            Result.Cancelled
        } catch (e: NoCredentialException) {
            Result.NoAccount
        } catch (e: GetCredentialException) {
            Result.Failed(e)
        }
    }
}

/** Google code scanner: Play services shows the camera UI, so the app needs no camera permission. */
object QrScanner {
    sealed interface Result {
        data class Scanned(val text: String) : Result
        data object Cancelled : Result
        data class Failed(val error: Throwable) : Result
    }

    suspend fun scan(context: Context): Result = suspendCancellableCoroutine { cont ->
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val text = barcode.rawValue
                cont.resume(if (text.isNullOrBlank()) Result.Cancelled else Result.Scanned(text))
            }
            .addOnCanceledListener { cont.resume(Result.Cancelled) }
            .addOnFailureListener { cont.resume(Result.Failed(it)) }
    }

    /** Starts downloading the scanner module early, e.g. when the join screen opens. */
    fun prefetch(context: Context) {
        val client = GmsBarcodeScanning.getClient(context)
        ModuleInstall.getClient(context).installModules(
            com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder().addApi(client).build(),
        )
    }
}

object Share {
    fun text(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
