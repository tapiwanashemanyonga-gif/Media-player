package com.kivora.music

import java.util.Locale

/** Shared grouping and ordering rules for the local music library. */
internal object MediaLibraryOrdering {
    fun albumGroupKey(song: Song): String =
        if (song.albumId > 0L) {
            "id:${song.albumId}"
        } else {
            "name:${song.album.lowercase(Locale.ROOT)}|artist:${song.artist.lowercase(Locale.ROOT)}"
        }

    fun sortSongs(
        items: List<Song>,
        sortMode: String,
        recentlyPlayedUris: List<String>
    ): List<Song> = when (sortMode) {
        "artist" -> items.sortedWith(
            compareBy<Song> { it.artist.trim().lowercase(Locale.ROOT) }
                .thenBy { it.title.trim().lowercase(Locale.ROOT) }
        )
        "recent" -> items.sortedWith(
            compareBy<Song> {
                recentlyPlayedUris.indexOf(it.uri.toString()).let { index ->
                    if (index < 0) Int.MAX_VALUE else index
                }
            }.thenBy { it.title.trim().lowercase(Locale.ROOT) }
        )
        else -> items.sortedWith(
            compareBy<Song> { it.title.trim().lowercase(Locale.ROOT) }
                .thenBy { it.artist.trim().lowercase(Locale.ROOT) }
        )
    }
}
