package com.kivora.music

import android.content.SharedPreferences

/**
 * Stores user-entered lyrics locally, keyed by the track's content URI.
 * Lyrics remain on the device and are not sent to a remote service.
 */
internal class LyricsStore(
    private val preferences: SharedPreferences
) {
    fun get(trackUri: String): String =
        preferences.getString(trackUri, "").orEmpty()

    fun save(trackUri: String, lyrics: String) {
        preferences.edit().putString(trackUri, lyrics).apply()
    }
}
