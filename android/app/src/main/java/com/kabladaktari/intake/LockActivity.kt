package com.kabladaktari.intake

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Gate in front of the sessions list when the doctor has turned on
 * "Require unlock" in settings. Uses the device's own BiometricPrompt —
 * whatever the phone already has set up (fingerprint/face, or PIN/pattern/
 * password as a fallback) — rather than a custom PIN screen this app would
 * have to store and validate itself.
 *
 * Unlocks once per process lifetime: AppLock.unlockedThisProcess resets when
 * the app is killed, but isn't re-checked on every return from ReportActivity
 * within the same session — that would be a prompt on every tap, not a
 * meaningful security boundary.
 */
object AppLock {
    var unlockedThisProcess = false
}

class LockActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var unlockButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!IntakeConfig.getRequireAuth(this) || AppLock.unlockedThisProcess) {
            proceedToSessions()
            return
        }

        setContentView(R.layout.activity_lock)
        statusText = findViewById(R.id.lockStatusText)
        unlockButton = findViewById(R.id.unlockButton)

        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            statusText.text = "No device PIN or biometric is set up, so this can't be enforced. " +
                "Set one up in your phone's settings, or turn this off in the app's settings."
            unlockButton.text = "Continue anyway"
            unlockButton.setOnClickListener { proceedToSessions() }
            return
        }

        unlockButton.setOnClickListener { promptUnlock(authenticators) }
        promptUnlock(authenticators)
    }

    private fun promptUnlock(authenticators: Int) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Kabla ya Daktari")
            .setSubtitle("Confirm it's you before viewing patient data")
            .setAllowedAuthenticators(authenticators)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    AppLock.unlockedThisProcess = true
                    proceedToSessions()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    statusText.text = errString.toString()
                }
            },
        )
        prompt.authenticate(promptInfo)
    }

    private fun proceedToSessions() {
        startActivity(Intent(this, SessionsActivity::class.java))
        finish()
    }
}
