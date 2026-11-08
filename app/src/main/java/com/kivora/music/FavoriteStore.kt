package com.kivora.music

import android.content.SharedPreferences

/** Persists the URI identifiers of tracks marked as favourites on this device. */
internal class FavoriteStore(
    private val preferences: SharedPreferences
) {
    fun load(): Set<String> =
        preferences.getStringSet(KEY_FAVORITE_SONG_URIS, emptySet<String>())
            ?.toSet()
            .orEmpty()

    fun save(trackUris: Set<String>) {
        preferences.edit().putStringSet(KEY_FAVORITE_SONG_URIS, trackUris.toSet()).apply()
    }

    private companion object {
        const val KEY_FAVORITE_SONG_URIS = "favorite_song_uris"
    }
}
