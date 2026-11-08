package com.kivora.music

import java.util.Locale

/** Caches the normalized searchable text for each media content URI. */
internal class MediaSearchTextCache {
    private val values = mutableMapOf<String, String>()

    fun get(song: Song): String {
        val key = song.uri.toString()
        return values.getOrPut(key) {
            (song.title.trim() + " " + song.artist.trim() + " " + song.album.trim())
                .lowercase(Locale.ROOT)
        }
    }

    fun put(uri: String, searchableText: String) {
        values[uri] = searchableText
    }

    fun clear() {
        values.clear()
    }
}
