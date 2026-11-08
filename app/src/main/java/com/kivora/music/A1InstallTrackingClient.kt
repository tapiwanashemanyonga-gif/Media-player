package com.kivora.music

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Sends one optional first-open event per installation, only after the user opts in.
 * The app key is a public client credential; authorization is enforced by the API.
 */
class A1InstallTrackingClient(context: Context) {
    private val appContext = context.applicationContext
    private val activity = context as? Activity

    fun recordFirstOpen() {
        val appKey = BuildConfig.A1_APP_TRACKING_KEY
        if (appKey.isBlank()) return

        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_CONSENT_DECIDED, false)) {
            if (preferences.getBoolean(KEY_CONSENT_GRANTED, false)) sendFirstOpen(appKey, preferences)
            return
        }

        val hostActivity = activity?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return
        AlertDialog.Builder(hostActivity)
            .setTitle("Optional installation analytics")
            .setMessage(
                "A1 FAMILY MEDIA can send one installation event to help us understand app installs. " +
                    "If you allow it, we send a random installation ID, the event time, app version and " +
                    "Android platform to the A1 Family Media analytics service. The service provider may " +
                    "also receive your IP address and ordinary connection metadata when the request is sent. " +
                    "This is optional. Your music, videos and lyrics are not included in this event, and " +
                    "declining does not affect playback or advertising. You can change this choice later from " +
                    "Home > Privacy & data. Read the Privacy Policy for details."
            )
            .setPositiveButton("Allow optional analytics") { _, _ ->
                preferences.edit()
                    .putBoolean(KEY_CONSENT_DECIDED, true)
                    .putBoolean(KEY_CONSENT_GRANTED, true)
                    .apply()
                sendFirstOpen(appKey, preferences)
            }
            .setNegativeButton("Don't allow") { _, _ ->
                preferences.edit()
                    .putBoolean(KEY_CONSENT_DECIDED, true)
                    .putBoolean(KEY_CONSENT_GRANTED, false)
                    .apply()
            }
            .setNeutralButton("Privacy policy") { _, _ ->
                runCatching {
                    hostActivity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    )
                }
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Lets users review or change their optional installation-analytics choice at any time.
     * Revoking consent stops future sends; it cannot retract an event already transmitted.
     */
    fun openPrivacyChoices() {
        val hostActivity = activity?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return
        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val appKey = BuildConfig.A1_APP_TRACKING_KEY
        val configured = appKey.isNotBlank()
        val granted = preferences.getBoolean(KEY_CONSENT_GRANTED, false)
        val status = when {
            !configured -> "Optional installation analytics is not enabled in this app build."
            !preferences.getBoolean(KEY_CONSENT_DECIDED, false) -> "You have not made a choice about optional installation analytics."
            granted -> "You currently allow optional installation analytics."
            else -> "You currently do not allow optional installation analytics."
        }
        val details = status + "\n\n" +
            "If enabled, A1 sends a random installation ID, event time, app version and Android platform. " +
            "The service provider may receive your IP address and ordinary connection metadata. " +
            "Your music, videos and lyrics are not included. This choice does not affect playback or advertising.\n\n" +
            "Declining stops future optional analytics sends. It cannot remove information already received by the analytics service; contact the privacy email in the policy for a data request."
        val dialog = AlertDialog.Builder(hostActivity)
            .setTitle("Privacy choices")
            .setMessage(details)
            .setNeutralButton("Privacy policy") { _, _ ->
                runCatching {
                    hostActivity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
                }
            }
            .setPositiveButton(if (granted) "Keep allowed" else "Allow analytics", null)
            .setNegativeButton(if (granted) "Don't allow" else "Keep declined", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = configured
                setOnClickListener {
                    if (!configured) return@setOnClickListener
                    preferences.edit().putBoolean(KEY_CONSENT_DECIDED, true)
                        .putBoolean(KEY_CONSENT_GRANTED, true).apply()
                    sendFirstOpen(appKey, preferences)
                    android.widget.Toast.makeText(hostActivity, "Optional analytics allowed.", android.widget.Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                preferences.edit().putBoolean(KEY_CONSENT_DECIDED, true)
                    .putBoolean(KEY_CONSENT_GRANTED, false)
                    .remove(KEY_PENDING_EVENT_ID).apply()
                android.widget.Toast.makeText(hostActivity, "Optional analytics disabled.", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun sendFirstOpen(
        appKey: String,
        preferences: android.content.SharedPreferences
    ) {
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
            .put("occurred_at", utcTimestamp())
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

    private fun utcTimestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private companion object {
        const val EVENTS_URL = "https://a1-family-cloud-api.tapiwanashemanyonga.workers.dev/v1/events"
        const val PRIVACY_POLICY_URL = "https://a1-family-media-site.onrender.com/privacy.html"
        const val PREFERENCES = "a1_cloud_tracking"
        const val KEY_SENT = "install_first_open_sent"
        const val KEY_VISITOR_ID = "visitor_id"
        const val KEY_PENDING_EVENT_ID = "pending_install_event_id"
        const val KEY_CONSENT_DECIDED = "install_analytics_consent_decided"
        const val KEY_CONSENT_GRANTED = "install_analytics_consent_granted"
    }
}
