package com.kivora.music

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * Sends one privacy-minimal first-open event per app installation.
 * The app key is a public client credential; authorization is enforced by the API.
 */
class A1InstallTrackingClient(context: Context) {
    private val appContext = context.applicationContext

    fun recordFirstOpen() {
        val appKey = BuildConfig.A1_APP_TRACKING_KEY
        if (appKey.isBlank()) return

        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_SENT, false)) return

        val visitorId = preferences.getString(KEY_VISITOR_ID, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_VISITOR_ID, it).apply()
            }
        val eventId = preferences.getString(KEY_PENDING_EVENT_ID, null)
            ?: UUID.randomUUID().toString().also {
                // Persist before sending so a retry uses the same deduplication ID.
                preferences.edit().putString(KEY_PENDING_EVENT_ID, it).commit()
            }

        val payload = JSONObject()
            .put("event_id", eventId)
            .put("event_type", "install_first_open")
            .put("visitor_id", visitorId)
            .put("occurred_at", Instant.now().toString())
            .put(
                "metadata",
                JSONObject()
                    .put("app_version", BuildConfig.VERSION_NAME)
                    .put("platform", "android")
            )
            .toString()

        Thread({
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(EVENTS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-App-Key", appKey)
                }
                connection.outputStream.use { output ->
                    output.write(payload.toByteArray(StandardCharsets.UTF_8))
                }
                if (connection.responseCode in 200..299) {
                    preferences.edit()
                        .putBoolean(KEY_SENT, true)
                        .remove(KEY_PENDING_EVENT_ID)
                        .apply()
                }
            } catch (_: Exception) {
                // Keep the pending event ID. A later app launch can retry safely.
            } finally {
                connection?.disconnect()
            }
        }, "a1-install-tracking").start()
    }

    private companion object {
        const val EVENTS_URL = "https://a1-family-cloud-api.tapiwanashemanyonga.workers.dev/v1/events"
        const val PREFERENCES = "a1_cloud_tracking"
        const val KEY_SENT = "install_first_open_sent"
        const val KEY_VISITOR_ID = "visitor_id"
        const val KEY_PENDING_EVENT_ID = "pending_install_event_id"
    }
}
