package com.jimberisolation.android.authentication

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

internal fun googleSignInRequest(serverClientId: String): GetCredentialRequest =
    GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
        .build()

internal fun googleIdToken(credential: androidx.credentials.Credential): String {
    require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        "Unexpected credential type"
    }
    return GoogleIdTokenCredential.createFrom(credential.data).idToken.also {
        require(it.isNotBlank()) { "Missing Google ID token" }
    }
}

internal fun googleSignInErrorMessage(error: Exception): String? = when (error) {
    is GetCredentialCancellationException -> null
    is NoCredentialException -> "No Google account was available. Add an account or choose another sign-in method."
    is GetCredentialProviderConfigurationException, is GetCredentialUnsupportedException ->
        "Google sign-in is unavailable on this device. Check Google Play services or choose another sign-in method."
    else -> "Google sign-in failed. Please try again."
}

suspend fun clearGoogleCredentialState(context: Context) {
    try {
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    } catch (e: ClearCredentialException) {
        // Local sign-out must still complete when no credential provider is available.
        Log.w("GoogleAuthentication", "Could not clear credential provider state (${e.javaClass.simpleName})")
    }
}
