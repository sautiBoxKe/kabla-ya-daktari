package com.kabladaktari.intake

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class SessionSummary(
    val phone: String,
    val messageCount: Int,
    val hasReport: Boolean,
    val isMedical: Boolean?,
    val urgent: Boolean?,
)

data class Report(
    val chiefComplaint: String,
    val duration: String,
    val symptoms: List<String>,
    val vitalsTemperature: String,
    val vitalsOther: String,
    val medications: List<String>,
    val allergies: List<String>,
    val history: List<String>,
    val redFlags: List<String>,
    val urgent: Boolean,
    val summaryForDoctor: String,
    val disclaimer: String,
) {
    companion object {
        fun fromJson(json: JSONObject): Report {
            fun strList(key: String): List<String> {
                val arr = json.optJSONArray(key) ?: JSONArray()
                return (0 until arr.length()).map { arr.getString(it) }
            }
            val vitals = json.optJSONObject("self_reported_vitals") ?: JSONObject()
            return Report(
                chiefComplaint = json.optString("chief_complaint"),
                duration = json.optString("duration"),
                symptoms = strList("symptoms"),
                vitalsTemperature = vitals.optString("temperature"),
                vitalsOther = vitals.optString("other"),
                medications = strList("current_medications"),
                allergies = strList("allergies"),
                history = strList("relevant_history"),
                redFlags = strList("red_flags"),
                urgent = json.optBoolean("urgent", false),
                summaryForDoctor = json.optString("summary_for_doctor"),
                disclaimer = json.optString("disclaimer"),
            )
        }
    }
}

/**
 * Thin JSON/HTTP helper around the backend's REST endpoints — mirrors the
 * HttpURLConnection style already used in WhatsAppNotificationListener rather
 * than pulling in a networking library for a handful of calls.
 */
object BackendApi {
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun request(
        context: Context,
        method: String,
        path: String,
        onResult: (obj: JSONObject?, arr: JSONArray?, error: String?) -> Unit,
    ) {
        executor.execute {
            val backendUrl = IntakeConfig.getBackendUrl(context)
            val token = IntakeConfig.getToken(context)
            try {
                val url = URL("$backendUrl$path")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = method
                if (token.isNotEmpty()) conn.setRequestProperty("X-Intake-Token", token)
                conn.connectTimeout = 8000
                conn.readTimeout = 20000

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                conn.disconnect()

                if (code !in 200..299) {
                    val msg = try {
                        JSONObject(body).optString("detail", "HTTP $code")
                    } catch (e: Exception) {
                        "HTTP $code"
                    }
                    mainHandler.post { onResult(null, null, msg) }
                    return@execute
                }

                val trimmed = body.trim()
                if (trimmed.startsWith("[")) {
                    mainHandler.post { onResult(null, JSONArray(trimmed), null) }
                } else {
                    mainHandler.post { onResult(JSONObject(trimmed), null, null) }
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(null, null, e.message ?: "Network error") }
            }
        }
    }

    fun fetchSessions(context: Context, onResult: (List<SessionSummary>?, String?) -> Unit) {
        request(context, "GET", "/sessions") { _, arr, error ->
            if (error != null || arr == null) {
                onResult(null, error ?: "Unexpected response")
                return@request
            }
            fun nullableBool(o: JSONObject, key: String): Boolean? =
                if (o.isNull(key)) null else o.optBoolean(key)

            val sessions = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SessionSummary(
                    phone = o.optString("phone"),
                    messageCount = o.optInt("message_count"),
                    hasReport = o.optBoolean("has_report"),
                    isMedical = nullableBool(o, "is_medical"),
                    urgent = nullableBool(o, "urgent"),
                )
            }
            onResult(sessions, null)
        }
    }

    fun fetchReport(context: Context, phone: String, onResult: (Report?, String?) -> Unit) {
        request(context, "GET", "/report/${encodePathSegment(phone)}") { obj, _, error ->
            if (error != null || obj == null) onResult(null, error) else onResult(Report.fromJson(obj), null)
        }
    }

    fun generateReport(context: Context, phone: String, onResult: (Report?, String?) -> Unit) {
        request(context, "POST", "/report/${encodePathSegment(phone)}") { obj, _, error ->
            if (error != null || obj == null) onResult(null, error) else onResult(Report.fromJson(obj), null)
        }
    }

    // URLEncoder is form-encoding (space -> "+"), but this is a URL *path*
    // segment, not a query string — the backend won't decode "+" back to a
    // space. Swap it for a literal %20 after encoding everything else.
    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
