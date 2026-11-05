package com.kivora.music

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Sends one pseudonymous first-launch event per app installation.
 * Configure ENDPOINT with the private Apps Script deployment URL before release.
 */
object InstallAnalytics {
    private const val TAG = "A1InstallAnalytics"
    private const val PREFS = "a1_install_analytics"
    private const val INSTALL_ID = "installation_id"
    private const val SENT = "first_launch_sent"
    private const val ENDPOINT = ""

    private val executor = Executors.newSingleThreadExecutor()

    fun recordFirstLaunch(context: Context) {
        if (ENDPOINT.isBlank()) return

        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installationId = preferences.getString(INSTALL_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(INSTALL_ID, it).apply()
            }

        if (preferences.getBoolean(SENT, false)) return

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val payload = JSONObject()
                    .put("action", "install")
                    .put("id", installationId)
                    .toString()
                connection.outputStream.use { stream ->
                    stream.write(payload.toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                if (status in 200..299) {
                    preferences.edit().putBoolean(SENT, true).apply()
                } else {
                    Log.w(TAG, "Analytics endpoint returned HTTP $status")
                }
            } catch (error: Exception) {
                // Analytics must never interfere with media playback or app startup.
                Log.w(TAG, "First-launch analytics unavailable", error)
            } finally {
                connection?.disconnect()
            }
        }
    }
}
