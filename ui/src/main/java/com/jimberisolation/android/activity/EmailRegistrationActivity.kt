package com.jimberisolation.android.activity

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jimberisolation.android.R
import com.jimberisolation.android.api.fieldError
import com.jimberisolation.android.api.userFacingMessage
import com.jimberisolation.android.authentication.sendVerificationEmail
import isValidEmail
import kotlinx.coroutines.launch

class EmailRegistrationActivity : AppCompatActivity() {
    private var actionBar: ActionBar? = null
    private var backPressedCallback: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.email_registration_activity)

        actionBar = supportActionBar
        actionBar?.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM)
        actionBar?.setCustomView(R.layout.jimber_action_bar)

        getSupportActionBar()?.setDisplayHomeAsUpEnabled(true);


        findViewById<View>(R.id.back)?.setOnClickListener {
            handleBackPressed()
        }

        val emailInput = findViewById<EditText>(R.id.email_input)
        val submitButton = findViewById<Button>(R.id.proceed)
        var isSubmitting = false
        submitButton.isEnabled = false;
        submitButton.setBackgroundColor(Color.GRAY)

        emailInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                emailInput.error = null
                val email = s?.toString() ?: ""
                val isValid = isValidEmail(email)

                submitButton.setBackgroundColor(
                    if (isValid) ContextCompat.getColor(this@EmailRegistrationActivity, R.color.jimber_primary) else Color.GRAY
                )


                submitButton.isEnabled = isValid && !isSubmitting
            }
        })

        submitButton.setOnClickListener {
            val emailAddress = emailInput.text.toString().trim()
            isSubmitting = true
            submitButton.isEnabled = false
            emailInput.isEnabled = false
            emailInput.error = null
            lifecycleScope.launch {
                try {
                    val result = sendVerificationEmail(emailAddress)
                    if (result.getOrNull() != true) {
                        val error = result.exceptionOrNull()
                        val fieldMessage = error.fieldError("email")
                        if (fieldMessage != null) {
                            emailInput.error = fieldMessage
                        } else {
                            Snackbar.make(emailInput, error.userFacingMessage(), Snackbar.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    startActivity(Intent(this@EmailRegistrationActivity, EmailVerificationActivity::class.java)
                        .putExtra("EMAIL", emailAddress))
                } finally {
                    isSubmitting = false
                    emailInput.isEnabled = true
                    submitButton.isEnabled = isValidEmail(emailInput.text.toString())
                }
            }
        }

        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }
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

    private fun handleBackPressed() {
        finish()
    }
}
