package com.kivora.music

/** Builds the playable song list from the current library collection selection. */
internal object MediaQueueSelector {
    fun select(
        allSongs: List<Song>,
        playlistSongs: List<Song>?,
        favoritesOnly: Boolean,
        favoriteUris: Set<String>,
        libraryBrowse: Boolean,
        selectedAlbumKey: String?,
        selectedArtistName: String?
    ): List<Song> {
        val candidates = when {
            playlistSongs != null -> playlistSongs
            favoritesOnly -> allSongs.filter { favoriteUris.contains(it.uri.toString()) }
            else -> allSongs.toList()
        }

        return candidates.filter { song ->
            (!libraryBrowse || selectedAlbumKey == null ||
                MediaLibraryOrdering.albumGroupKey(song) == selectedAlbumKey) &&
                (!libraryBrowse || selectedArtistName == null ||
                    song.artist.equals(selectedArtistName, ignoreCase = true))
        }
    }
}
