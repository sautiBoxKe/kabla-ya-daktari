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
 * Limitation: Android truncates long notification text, and this never sees
 * the actual bytes of media (images/voice notes) — only whatever short text
 * WhatsApp puts in the notification banner (e.g. "🎤" for a voice note, or
 * "Photo" for an uncaptioned image). Rather than silently forwarding that
 * placeholder as if it were the patient's message, isUnreadableMedia() flags
 * it so the doctor sees "patient sent media we can't read" instead of a
 * confusing emoji in the transcript.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "KablaIntakeListener"
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        // WhatsApp reposts/updates the same notification (e.g. when it gets
        // folded into a conversation summary), firing onNotificationPosted more
        // than once for one message. Drop exact title+text repeats seen within
        // this window rather than forwarding the same patient message twice.
        private const val DEDUP_WINDOW_MS = 3000L
        private val recentlySeen = mutableMapOf<String, Long>()

        private val MEDIA_PLACEHOLDER_WORDS = setOf(
            "photo", "video", "voice message", "audio", "gif", "sticker", "document", "image",
            "contact card",
        )

        private fun isUnreadableMedia(text: String): Boolean {
            // An emoji-only banner (no letters/digits at all) is WhatsApp's
            // media icon, not a written message.
            if (text.none { it.isLetterOrDigit() }) return true
            return text.trim().lowercase() in MEDIA_PLACEHOLDER_WORDS
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()?.trim()
        val rawText = extras.getCharSequence("android.text")?.toString()?.trim()

        if (title.isNullOrEmpty() || rawText.isNullOrEmpty()) return
        // WhatsApp's own "message sent" / summary notifications have no useful title/text pair;
        // skip anything that looks like a group summary line with no real sender.
        if (title == "WhatsApp") return

        val text = if (isUnreadableMedia(rawText)) {
            "[Patient sent a photo, voice note, or other attachment this app can't read " +
                "— ask them to describe it in a text message.]"
        } else {
            rawText
        }

        val key = "$title|$text"
        val now = System.currentTimeMillis()
        synchronized(recentlySeen) {
            val last = recentlySeen[key]
            if (last != null && now - last < DEDUP_WINDOW_MS) return
            recentlySeen[key] = now
            if (recentlySeen.size > 200) {
                recentlySeen.entries.removeAll { now - it.value > DEDUP_WINDOW_MS }
            }
        }

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
