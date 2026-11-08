package com.kivora.music

/** Maintains the most-recent-first in-memory history for the home dashboard. */
internal class RecentlyPlayedTracker(
    private val maxEntries: Int = 12
) {
    private val recentUris = mutableListOf<String>()

    val uris: List<String>
        get() = recentUris

    fun record(uri: String) {
        if (uri.isBlank()) return
        recentUris.remove(uri)
        recentUris.add(0, uri)
        while (recentUris.size > maxEntries) {
            recentUris.removeAt(recentUris.lastIndex)
        }
    }
}
