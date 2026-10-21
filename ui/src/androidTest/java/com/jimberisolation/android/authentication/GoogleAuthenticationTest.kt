package com.jimberisolation.android.authentication

import android.os.Bundle
import android.util.Base64
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleAuthenticationTest {
    @Test fun buttonFlowUsesTheConfiguredServerClientId() {
        val request = googleSignInRequest("test-web-client.apps.googleusercontent.com")
        assertEquals(1, request.credentialOptions.size)
        val option = request.credentialOptions.single() as GetSignInWithGoogleOption
        assertEquals("test-web-client.apps.googleusercontent.com", option.serverClientId)
    }

    @Test fun providerCredentialReturnsIdTokenWithoutChangingIt() {
        fun encode(value: String) = Base64.encodeToString(
            value.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        // Syntactically valid JWT fixture; signature verification belongs to the API.
        val token = encode("""{"alg":"RS256","typ":"JWT"}""") + "." +
            encode("""{"sub":"test-user","email":"test@example.com","aud":"test-client","iss":"https://accounts.google.com"}""") +
            ".test-signature"
        val google = GoogleIdTokenCredential.Builder()
            .setId("test@example.com")
            .setIdToken(token)
            .build()
        val providerResponse = CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, google.data)
        assertEquals(token, googleIdToken(providerResponse))
    }

    @Test fun unrelatedCredentialCannotBecomeAnAppSession() {
        assertThrows(IllegalArgumentException::class.java) {
            googleIdToken(CustomCredential("another-provider", Bundle()))
        }
    }

    @Test fun malformedGoogleCredentialIsRejected() {
        assertThrows(Exception::class.java) {
            googleIdToken(CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle()))
        }
    }

    @Test fun cancellationIsSilentButProviderFailuresAreActionable() {
        assertNull(googleSignInErrorMessage(GetCredentialCancellationException()))
        assertTrue(googleSignInErrorMessage(NoCredentialException())!!.contains("Google account"))
        assertTrue(googleSignInErrorMessage(GetCredentialProviderConfigurationException())!!.contains("Google Play services"))
        assertFalse(googleSignInErrorMessage(IllegalArgumentException("private token"))!!.contains("private token"))
    }
}
