package com.kabladaktari.intake

import android.content.Context

/**
 * Tiny SharedPreferences wrapper so the backend URL and shared secret can be
 * changed from the app at demo time, without a rebuild — the wifi/IP at a
 * hackathon venue is never the one you planned for.
 */
object IntakeConfig {
    private const val PREFS = "kabla_intake_prefs"
    private const val KEY_BACKEND_URL = "backend_url"
    private const val KEY_TOKEN = "shared_token"
    private const val KEY_REQUIRE_AUTH = "require_auth"

    // Points at the deployed backend by default so setup is just "set a
    // password" — override under Advanced settings only for local/USB demos
    // (e.g. http://127.0.0.1:8000 via `adb reverse tcp:8000 tcp:8000`).
    const val DEFAULT_BACKEND_URL = "https://kabla-ya-daktari-backend.onrender.com"

    fun getBackendUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL

    fun getToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "") ?: ""

    fun save(context: Context, backendUrl: String, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BACKEND_URL, backendUrl)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun getRequireAuth(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUIRE_AUTH, false)

    fun setRequireAuth(context: Context, required: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_REQUIRE_AUTH, required)
            .apply()
    }
}
