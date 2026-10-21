package com.jimberisolation.android.activity

import Config
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import authenticateUser
import com.google.android.material.snackbar.Snackbar
import com.jimberisolation.android.Application.Companion.getTunnelManager
import com.jimberisolation.android.R
import com.jimberisolation.android.api.fieldError
import com.jimberisolation.android.api.hasHttpStatus
import com.jimberisolation.android.api.userFacingMessage
import com.jimberisolation.android.authentication.AuthenticationType
import com.jimberisolation.android.authentication.UserAuthentication
import com.jimberisolation.android.authentication.googleIdToken
import com.jimberisolation.android.authentication.googleSignInErrorMessage
import com.jimberisolation.android.authentication.googleSignInRequest
import com.jimberisolation.android.daemon.getDaemonInfo
import com.jimberisolation.android.storage.SharedStorage
import com.jimberisolation.android.util.TunnelImporter.importTunnel
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import getDeviceHostname
import isValidMobileHostname
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import register
import sanitizeTunnelName

class SignInActivity : AppCompatActivity() {
    private var actionBar: ActionBar? = null
    private var daemonName: String? = getDeviceHostname()
    private var microsoftApp: ISingleAccountPublicClientApplication? = null
    private var microsoftReady = false
    private var signingIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.sign_in_activity)
        SharedStorage.initialize(this)

        actionBar = supportActionBar
        actionBar?.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM)
        actionBar?.setCustomView(R.layout.jimber_action_bar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        onBackPressedDispatcher.addCallback(this) { handleBackPressed() }

        findViewById<View>(R.id.google_sign_in).setOnClickListener { signInWithGoogle() }
        findViewById<View>(R.id.microsoft_sign_in).setOnClickListener { signInWithMicrosoft() }
        findViewById<View>(R.id.email_sign_in).setOnClickListener {
            startActivity(Intent(this, EmailRegistrationActivity::class.java))
        }
        updateSignInButtons()
        initializeMicrosoft()
    }

    private fun updateSignInButtons() {
        findViewById<View>(R.id.google_sign_in).isEnabled = !signingIn
        findViewById<View>(R.id.microsoft_sign_in).isEnabled = !signingIn && microsoftReady
        findViewById<View>(R.id.email_sign_in).isEnabled = !signingIn
    }

    private fun showSignInError(message: String) {
        if (!isFinishing && !isDestroyed) {
            Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun signInWithGoogle() {
        if (signingIn) return
        signingIn = true
        updateSignInButtons()
        lifecycleScope.launch {
            try {
                val token = try {
                    val response = CredentialManager.create(this@SignInActivity).getCredential(
                        context = this@SignInActivity,
                        request = googleSignInRequest(Config.GOOGLE_AUTH_ID)
                    )
                    googleIdToken(response.credential)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    googleSignInErrorMessage(e)?.let(::showSignInError)
                    return@launch
                }
                // The existing API verifies the Google ID token before creating the app session.
                completeSignIn(authenticateUser(token, AuthenticationType.Google).getOrThrow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showSignInError(e.userFacingMessage())
            } finally {
                signingIn = false
                updateSignInButtons()
            }
        }
    }

    private fun initializeMicrosoft() {
        PublicClientApplication.createSingleAccountPublicClientApplication(
            this, R.raw.msal_config,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    microsoftApp = application
                    clearCachedMicrosoftAccount(application, onCleared = {
                        microsoftReady = true
                        updateSignInButtons()
                    }, onError = { microsoftInitializationFailed() })
                }
                override fun onError(exception: MsalException) = microsoftInitializationFailed()
            }
        )
    }

    private fun clearCachedMicrosoftAccount(
        app: ISingleAccountPublicClientApplication,
        onCleared: () -> Unit,
        onError: (MsalException) -> Unit
    ) {
        app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                if (activeAccount == null) {
                    onCleared()
                } else {
                    app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                        override fun onSignOut() = onCleared()
                        override fun onError(exception: MsalException) = onError(exception)
                    })
                }
            }
            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) = Unit
            override fun onError(exception: MsalException) = onError(exception)
        })
    }

    private fun microsoftInitializationFailed() {
        microsoftReady = false
        updateSignInButtons()
        showSignInError("Microsoft sign-in could not initialize. Reopen this screen to try again.")
    }

    private fun signInWithMicrosoft() {
        val app = microsoftApp ?: return
        if (signingIn || !microsoftReady) return
        signingIn = true
        updateSignInButtons()
        // A previous attempt can have reached Microsoft but failed during app registration.
        clearCachedMicrosoftAccount(app, onCleared = { launchMicrosoftSignIn(app) }, onError = { exception ->
            Log.w("MicrosoftAuthentication", "Account reset failed (${exception.errorCode})")
            signingIn = false
            updateSignInButtons()
            showSignInError("Microsoft sign-in failed. Please try again.")
        })
    }

    private fun launchMicrosoftSignIn(app: ISingleAccountPublicClientApplication) {
        if (isFinishing || isDestroyed) return
        try {
            app.signIn(SignInParameters.builder()
                .withActivity(this)
                // MSAL adds the reserved openid/profile/offline_access scopes itself.
                .withScopes(listOf("User.Read", "email"))
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        lifecycleScope.launch {
                            try {
                                val token = authenticationResult.account.idToken
                                if (token.isNullOrBlank()) {
                                    showSignInError("Microsoft did not return an ID token. Please sign in again.")
                                    return@launch
                                }
                                val result = authenticateUser(token, AuthenticationType.Microsoft)
                                completeSignIn(result.getOrThrow())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                showSignInError(e.userFacingMessage())
                            } finally {
                                signingIn = false
                                updateSignInButtons()
                            }
                        }
                    }
                    override fun onError(exception: MsalException) {
                        // Log only the SDK error code, never tokens or full authentication results.
                        Log.w("MicrosoftAuthentication", "Sign-in failed (${exception.errorCode})")
                        signingIn = false
                        updateSignInButtons()
                        showSignInError("Microsoft sign-in failed. Please try again.")
                    }
                    override fun onCancel() {
                        signingIn = false
                        updateSignInButtons()
                    }
                }).build())
        } catch (e: Exception) {
            Log.w("MicrosoftAuthentication", "Could not start sign-in (${e.javaClass.simpleName})")
            signingIn = false
            updateSignInButtons()
            showSignInError("Microsoft sign-in failed. Please try again.")
        }
    }

    private suspend fun completeSignIn(userAuthentication: UserAuthentication) {
        val userId = userAuthentication.userId
        val companyName = userAuthentication.companyName
        val cleanedCompanyName = sanitizeTunnelName(companyName)

        var daemonAlreadyInStorage = SharedStorage.getInstance().getDaemonKeyPairByUserId(userId)
        if(daemonAlreadyInStorage != null) {
            val result = getDaemonInfo(daemonAlreadyInStorage.daemonId, daemonAlreadyInStorage.companyName,  daemonAlreadyInStorage.baseEncodedSkEd25519);
            if(result.isFailure && result.exceptionOrNull().hasHttpStatus(403, 404)) {
                Log.w("LOGIN_WARNING", "TUNNEL IN KEYSTORE BUT NOT IN SIGNAL (probably removed in signal UI), will remove status code 403")
                Log.w("LOGIN_WARNING", "REMOVING IS NOT AN ISSUE, JUST RE LOGIN AND REGENERATE")

                val tunnelManager = getTunnelManager();
                val existingTunnel = tunnelManager.getTunnels().firstOrNull { it.getDaemonId() == daemonAlreadyInStorage?.daemonId }
                existingTunnel?.let { tunnelManager.deleteState(it) }

                SharedStorage.getInstance().clearDaemonKeys(daemonAlreadyInStorage.daemonId)
                daemonAlreadyInStorage = null;
            }

            else {
                if (result.isFailure) {
                    Snackbar.make(findViewById(android.R.id.content), result.exceptionOrNull().userFacingMessage(), Snackbar.LENGTH_LONG).show()
                    return
                }
                loadExistingDaemon();
                return
            }
        }

        daemonName = daemonAlreadyInStorage?.daemonName ?: run {
            showNameInputDialog() ?: return
        }

        // Proceed with the WireGuard config
        var wireguardConfigResult = register(userAuthentication, daemonName!!)
        while (wireguardConfigResult.exceptionOrNull().fieldError("name") != null) {
            daemonName = showNameInputDialog(wireguardConfigResult.exceptionOrNull().fieldError("name")) ?: return
            wireguardConfigResult = register(userAuthentication, daemonName!!)
        }

        if (wireguardConfigResult.isFailure) {
            val createDaemonException = wireguardConfigResult.exceptionOrNull()
            val view = findViewById<View>(android.R.id.content) // or some other view in your layout
            Snackbar.make(view, createDaemonException.userFacingMessage(), Snackbar.LENGTH_LONG).show()
            return
        }

        val wireguardConfig = wireguardConfigResult.getOrThrow().configurationString
        val daemonId = wireguardConfigResult.getOrThrow().daemonId

        importTunnelAndNavigate(wireguardConfig, daemonId, cleanedCompanyName)
    }

    private fun loadExistingDaemon() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    private suspend fun importTunnelAndNavigate(configResult: String, daemonId: Int, companyName: String) {
        val manager = getTunnelManager()

        val alreadyExistingTunnel = manager.getTunnels().find { it.getDaemonId() == daemonId }

        if(alreadyExistingTunnel == null) {
            importTunnel(configResult, daemonId, daemonName!!, companyName) { }
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                // The back arrow in the action bar should act the same as the back button.
                onBackPressedDispatcher.onBackPressed()
                true
            }

            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private suspend fun showNameInputDialog(serverError: String? = null): String? {
        // Create a deferred result to await the input
        val result = CompletableDeferred<String?>()

        // Inflate the dialog's custom view
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_input_name, null)

        // Build the dialog
        val dialogBuilder = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)

        // Get reference to EditText and buttons in the dialog
        val nameInput = dialogView.findViewById<EditText>(R.id.nameInput)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSubmit = dialogView.findViewById<Button>(R.id.btnSubmit)

        nameInput.setText(daemonName)
        nameInput.error = serverError

        // Create the dialog
        val dialog = dialogBuilder.create()

        // Handle the Cancel button
        btnCancel.setOnClickListener {
            result.complete(null)  // Return null if canceled
            dialog.dismiss()
        }

        // Handle the Submit button
        btnSubmit.setOnClickListener {
            val name = nameInput.text.toString().trim()

            val (isValid, errorMessage) = isValidMobileHostname(name)

            if (isValid) {
                result.complete(name)  // Pass the entered name to the result
                dialog.dismiss()
            } else {
                Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
            }
        }

        // Show the dialog
        dialog.show()

        // Await the result before proceeding
        return try {
            result.await()
        } finally {
            dialog.dismiss()
        }
    }

    private fun handleBackPressed() {
        finish()
    }
}
