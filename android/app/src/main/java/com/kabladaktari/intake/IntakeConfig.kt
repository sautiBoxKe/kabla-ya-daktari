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

    // Change these defaults to match your machine's IP on the venue wifi,
    // e.g. http://192.168.1.42:8000 — "localhost" only works in an emulator
    // pointed at your own laptop.
    private const val DEFAULT_BACKEND_URL = "http://10.0.2.2:8000"

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
}
