package com.kabladaktari.intake

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val urlField = findViewById<EditText>(R.id.backendUrlField)
        val tokenField = findViewById<EditText>(R.id.tokenField)
        val statusText = findViewById<TextView>(R.id.statusText)

        urlField.setText(IntakeConfig.getBackendUrl(this))
        tokenField.setText(IntakeConfig.getToken(this))

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            IntakeConfig.save(this, urlField.text.toString().trim(), tokenField.text.toString().trim())
            Toast.makeText(this, "Saved. Backend: ${urlField.text}", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.grantAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

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
