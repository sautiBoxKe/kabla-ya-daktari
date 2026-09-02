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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/**
 * Doubles as onboarding and settings. Once a password is saved AND
 * notification access is granted, this screen is a dead end you'd never
 * want to land on again — refreshUiState() skips straight to the sessions
 * list instead. Pass EXTRA_FORCE_SETTINGS to actually see this screen when
 * fully set up (e.g. from a "Settings" link elsewhere).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var accessDivider: View
    private lateinit var grantAccessButton: Button
    private lateinit var viewSessionsButton: Button

    private val forceSettings: Boolean by lazy { intent.getBooleanExtra(EXTRA_FORCE_SETTINGS, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlField = findViewById(R.id.backendUrlField)
        tokenField = findViewById(R.id.tokenField)
        statusText = findViewById(R.id.statusText)
        saveButton = findViewById(R.id.saveButton)
        accessDivider = findViewById(R.id.accessDivider)
        grantAccessButton = findViewById(R.id.grantAccessButton)
        viewSessionsButton = findViewById(R.id.viewSessionsButton)
        val advancedSection = findViewById<LinearLayout>(R.id.advancedSection)
        val advancedToggle = findViewById<TextView>(R.id.advancedToggle)
        val requireAuthSwitch = findViewById<MaterialSwitch>(R.id.requireAuthSwitch)

        requireAuthSwitch.isChecked = IntakeConfig.getRequireAuth(this)
        requireAuthSwitch.setOnCheckedChangeListener { _, checked ->
            IntakeConfig.setRequireAuth(this, checked)
        }

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

        grantAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        viewSessionsButton.setOnClickListener {
            startActivity(Intent(this, LockActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers the initial load AND coming back from the system notification
        // settings screen with access just granted — no restart needed either way.
        refreshUiState()
    }

    private fun onContinue() {
        val password = tokenField.text.toString().trim()
        if (password.isEmpty()) {
            Snackbar.make(saveButton, "Enter a password first", Snackbar.LENGTH_SHORT).show()
            return
        }
        IntakeConfig.save(this, urlField.text.toString().trim(), password)

        if (!isNotificationAccessGranted()) {
            Snackbar.make(saveButton, "Saved. Now enable notification access.", Snackbar.LENGTH_LONG)
                .setAction("Enable") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .show()
        }
        // If access is already granted, refreshUiState() below logs the doctor
        // straight into the sessions list instead of stalling on a Snackbar.
        refreshUiState()
    }

    /** Single source of truth for what this screen shows, given saved state. */
    private fun refreshUiState() {
        val hasPassword = IntakeConfig.getToken(this).isNotBlank()
        val granted = isNotificationAccessGranted()

        if (hasPassword && granted && !forceSettings) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }

        grantAccessButton.visibility = if (!granted) View.VISIBLE else View.GONE
        viewSessionsButton.visibility = if (forceSettings && hasPassword && granted) View.VISIBLE else View.GONE
        accessDivider.visibility =
            if (grantAccessButton.visibility == View.VISIBLE || viewSessionsButton.visibility == View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }

        statusText.text = if (granted) {
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

    companion object {
        const val EXTRA_FORCE_SETTINGS = "force_settings"
    }
}
