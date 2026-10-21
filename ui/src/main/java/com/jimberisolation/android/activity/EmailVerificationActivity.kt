package com.jimberisolation.android.activity

import com.jimberisolation.android.api.hasHttpStatus
import com.jimberisolation.android.api.fieldError
import com.jimberisolation.android.api.userFacingMessage
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jimberisolation.android.Application.Companion.getTunnelManager
import com.jimberisolation.android.R
import com.jimberisolation.android.authentication.AuthenticationWithVerificationCodeApiRequest
import com.jimberisolation.android.authentication.UserAuthentication
import com.jimberisolation.android.authentication.sendVerificationEmail
import com.jimberisolation.android.authentication.verifyEmailWithToken
import com.jimberisolation.android.daemon.getDaemonInfo
import com.jimberisolation.android.storage.SharedStorage
import com.jimberisolation.android.util.TunnelImporter.importTunnel
import getDeviceHostname
import isValidMobileHostname
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import register
import sanitizeTunnelName

class EmailVerificationActivity : AppCompatActivity() {
    private var actionBar: ActionBar? = null
    private var backPressedCallback: OnBackPressedCallback? = null

    private var daemonName: String? = getDeviceHostname();

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.email_verification_activity)

        SharedStorage.initialize(this)

        val errorTextView: TextView = findViewById(R.id.verification_error)

        actionBar = supportActionBar
        actionBar?.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM)
        actionBar?.setCustomView(R.layout.jimber_action_bar)

        getSupportActionBar()?.setDisplayHomeAsUpEnabled(true);

        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }

        val email = intent.getStringExtra("EMAIL") ?: run {
            finish()
            return
        }

        val snackView = findViewById<View>(android.R.id.content)

        val tokenInputs = listOf(
            findViewById(R.id.token_digit_1),
            findViewById(R.id.token_digit_2),
            findViewById(R.id.token_digit_3),
            findViewById(R.id.token_digit_4),
            findViewById(R.id.token_digit_5),
            findViewById<EditText>(R.id.token_digit_6)
        )

        val textView: TextView = findViewById(R.id.resend_email)

        textView.setOnClickListener {
            textView.isEnabled = false
            lifecycleScope.launch {
                try {
                    val result = sendVerificationEmail(email)
                    if (result.getOrNull() != true) {
                        Snackbar.make(snackView, result.exceptionOrNull().userFacingMessage(), Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                    Snackbar.make(snackView, "Email successfully resent", Snackbar.LENGTH_LONG).show()
                } finally {
                    textView.isEnabled = true
                }
            }
        }

        val submitButton = findViewById<View>(R.id.proceed)
        submitButton.setOnClickListener {
            val verificationCode = tokenInputs.joinToString("") { it.text.toString() }
            val token = verificationCode.toIntOrNull()
            if (verificationCode.length != 6 || token == null) {
                errorTextView.text = "Please fill in a valid verification code"
                return@setOnClickListener;
            }

            submitButton.isEnabled = false
            errorTextView.text = ""
            lifecycleScope.launch {
                try {
                    val verifyResult = verifyEmailWithToken(AuthenticationWithVerificationCodeApiRequest(email, token))
                    if (verifyResult.isFailure) {
                        val error = verifyResult.exceptionOrNull()
                        errorTextView.text = error.fieldError("token") ?: error.fieldError("email") ?: error.userFacingMessage()
                        return@launch
                    }
                    handleEmailVerification(verifyResult.getOrThrow())
                } finally {
                    submitButton.isEnabled = true
                }
            }
        }

        for (i in tokenInputs.indices) {
            val currentEditText = tokenInputs[i]

            currentEditText.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && i < tokenInputs.size - 1) {
                        tokenInputs[i + 1].requestFocus()
                    }
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            currentEditText.setOnKeyListener { v, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (currentEditText.text.isEmpty() && i > 0) {
                        val previousEditText = tokenInputs[i - 1]
                        previousEditText.requestFocus()
                        previousEditText.text?.clear()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

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

    private suspend fun handleEmailVerification(userAuthenticationResult: UserAuthentication) {
        if(daemonName == null) return;

        try {
            val userId = userAuthenticationResult.userId
            val companyName = userAuthenticationResult.companyName
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

            var wireguardConfigResult = register(userAuthenticationResult, daemonName!!)
            while (wireguardConfigResult.exceptionOrNull().fieldError("name") != null) {
                daemonName = showNameInputDialog(wireguardConfigResult.exceptionOrNull().fieldError("name")) ?: return
                wireguardConfigResult = register(userAuthenticationResult, daemonName!!)
            }

            if (wireguardConfigResult.isFailure) {
                val createDaemonException = wireguardConfigResult.exceptionOrNull()
                val view = findViewById<View>(android.R.id.content) // or some other view in your layout
                Snackbar.make(view, createDaemonException.userFacingMessage(), Snackbar.LENGTH_LONG).show()
                return
            }

            val result = wireguardConfigResult.getOrThrow()

            val wireguardConfig = result.configurationString

            importTunnelAndNavigate(wireguardConfig, result.daemonId, cleanedCompanyName)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Authentication", "An error occurred", e)
            val view = findViewById<View>(android.R.id.content) // or some other view in your layout
            Snackbar.make(view, e.userFacingMessage(), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun loadExistingDaemon() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }


    private suspend fun importTunnelAndNavigate(result: String, daemonId: Int, companyName: String) {
        val manager = getTunnelManager()

        val alreadyExistingTunnel = manager.getTunnels().find { it.getDaemonId() == daemonId }
        if(alreadyExistingTunnel == null) {
            importTunnel(result, daemonId, daemonName!!, companyName) { }
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    private fun handleBackPressed() {
        finish()
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

}
