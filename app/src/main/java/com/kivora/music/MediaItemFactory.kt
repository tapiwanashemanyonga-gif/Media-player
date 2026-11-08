package com.kivora.music

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Converts local library songs into Media3 queue items with their display metadata. */
internal object MediaItemFactory {
    fun fromSongs(songs: List<Song>): List<MediaItem> =
        songs.map { song ->
            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .apply {
                    song.artworkUri?.let { setArtworkUri(it) }
                }
                .build()

            MediaItem.Builder()
                .setUri(song.uri)
                .setMediaMetadata(metadata)
                .build()
        }
}
