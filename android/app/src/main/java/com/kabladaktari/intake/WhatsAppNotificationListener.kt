package com.kabladaktari.intake

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Mirrors the approach in suyashm002/fetchWhatsappdata: listen for WhatsApp's
 * own notifications and read the title/text Android already shows the user,
 * rather than touching WhatsApp's encrypted database or any private API.
 *
 * Intentional scope: this device is the CLINIC'S intake phone, running its
 * own WhatsApp account that patients message. It is not meant to be
 * installed on a patient's personal phone — see README "Consent & scope".
 *
 * Limitation: Android truncates long notification text, and this never
 * sees media (images/voice notes) — only the text WhatsApp puts in the
 * notification banner. Good enough for a symptom-description chat; call
 * this out as "mocked" for anything beyond that.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "KablaIntakeListener"
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()?.trim()
        val text = extras.getCharSequence("android.text")?.toString()?.trim()

        if (title.isNullOrEmpty() || text.isNullOrEmpty()) return
        // WhatsApp's own "message sent" / summary notifications have no useful title/text pair;
        // skip anything that looks like a group summary line with no real sender.
        if (title == "WhatsApp") return

        executor.execute { postMessage(title, text) }
    }

    private fun postMessage(senderLabel: String, text: String) {
        val backendUrl = IntakeConfig.getBackendUrl(applicationContext)
        val token = IntakeConfig.getToken(applicationContext)

        try {
            val body = JSONObject().apply {
                // "phone" is really "whatever label WhatsApp shows for this chat" —
                // a saved contact name or a raw number. Good enough as a session key
                // for a hackathon demo; a real deployment should ask WhatsApp for the
                // number via the Business API instead of trusting the notification title.
                put("phone", senderLabel)
                put("text", text)
                put("from_clinic", false)
                put("ts", Instant.now().toString())
            }

            val url = URL("$backendUrl/message")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) conn.setRequestProperty("X-Intake-Token", token)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Backend rejected message: HTTP $code")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward WhatsApp message to backend", e)
        }
    }
}
