package com.kabladaktari.intake

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlField = findViewById(R.id.backendUrlField)
        tokenField = findViewById(R.id.tokenField)
        statusText = findViewById(R.id.statusText)
        saveButton = findViewById(R.id.saveButton)
        val advancedSection = findViewById<LinearLayout>(R.id.advancedSection)
        val advancedToggle = findViewById<TextView>(R.id.advancedToggle)

        urlField.setText(IntakeConfig.getBackendUrl(this))
        tokenField.setText(IntakeConfig.getToken(this))

        // Only surface the URL field by default if it's been changed from the
        // shipped default — most doctors never need to touch it.
        if (IntakeConfig.getBackendUrl(this) != IntakeConfig.DEFAULT_BACKEND_URL) {
            advancedSection.visibility = View.VISIBLE
        }
        advancedToggle.setOnClickListener {
            advancedSection.visibility =
                if (advancedSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        saveButton.setOnClickListener { onContinue() }

        findViewById<Button>(R.id.grantAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.viewSessionsButton).setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up a just-granted notification permission without needing a restart.
        refreshStatus()
    }

    private fun onContinue() {
        val password = tokenField.text.toString().trim()
        if (password.isEmpty()) {
            Snackbar.make(saveButton, "Enter a password first", Snackbar.LENGTH_SHORT).show()
            return
        }
        IntakeConfig.save(this, urlField.text.toString().trim(), password)

        if (isNotificationAccessGranted()) {
            Snackbar.make(saveButton, "Saved — ready to go", Snackbar.LENGTH_LONG)
                .setAction("View sessions") {
                    startActivity(Intent(this, SessionsActivity::class.java))
                }
                .show()
        } else {
            Snackbar.make(saveButton, "Saved. Now enable notification access.", Snackbar.LENGTH_LONG)
                .setAction("Enable") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .show()
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = if (isNotificationAccessGranted()) {
            "Notification access: granted — this phone is listening for WhatsApp messages."
        } else {
            "Notification access: NOT granted yet. Tap 'Enable notification access' and " +
                "turn this app on for '${getString(R.string.app_name)}'."
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled != null && enabled.contains(packageName)
    }
}
