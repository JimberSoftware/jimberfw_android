package com.jimberisolation.android.authentication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jimberisolation.android.BuildConfig
import com.jimberisolation.android.R
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MicrosoftInitializationTest {
    @Test fun signedEnvironmentCanInitializeMicrosoftClient() {
        // Environment redirects are registered for release package names and signing keys.
        assumeFalse("The debug package and signing key have no registered MSAL redirect", BuildConfig.DEBUG)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val initialized = CompletableFuture<Boolean>()
        instrumentation.runOnMainSync {
            PublicClientApplication.createSingleAccountPublicClientApplication(
                instrumentation.targetContext, R.raw.msal_config,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        initialized.complete(true)
                    }

                    override fun onError(exception: MsalException) {
                        initialized.completeExceptionally(AssertionError(
                            "Microsoft initialization failed (${exception.errorCode}); check the environment's package, signing key and redirect URI"
                        ))
                    }
                }
            )
        }
        assertTrue(initialized.get(30, TimeUnit.SECONDS))
    }
}
