package com.kivora.music

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import android.net.Uri

/** Result of a MediaStore library scan, preserving the previous error contract. */
internal data class MediaScanResult<T>(
    val items: List<T>,
    val error: String?
)

/**
 * Reads local audio and video metadata from MediaStore.
 * Threading and UI updates remain the responsibility of the calling activity.
 */
internal object MediaLibraryScanner {

    fun scanSongs(contentResolver: ContentResolver): MediaScanResult<Song> {
        val scannedSongs = mutableListOf<Song>()
        var scanError: String? = null
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID
        )
        try {
            val cursor = contentResolver.query(
                collection, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null, "${MediaStore.Audio.Media.TITLE} ASC"
            ) ?: throw IllegalStateException("MediaStore returned no music cursor")
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val displayNameColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumNameColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val rawTitle = it.getString(titleColumn)?.trim().orEmpty()
                    val displayName = it.getString(displayNameColumn)?.trim().orEmpty()
                    val fileTitle = displayName.substringBeforeLast('.', displayName)
                        .replace(Regex("[._-]+"), " ").trim().ifBlank { "Unknown title" }
                    val title = if (rawTitle.isBlank() ||
                        rawTitle.equals("unknown", true) ||
                        rawTitle.equals("unknown title", true)) fileTitle else rawTitle
                    val artist = it.getString(artistColumn)?.takeIf { value ->
                        value.isNotBlank() && !value.equals("<unknown>", true) &&
                            !value.equals("unknown", true)
                    } ?: "Unknown artist"
                    val albumId = it.getLong(albumColumn)
                    val album = it.getString(albumNameColumn)?.trim()?.takeIf { value ->
                        value.isNotBlank() && !value.equals("<unknown>", true) &&
                            !value.equals("unknown", true)
                    } ?: "Unknown album"
                    val artUri = if (albumId > 0L) ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), albumId
                    ) else null
                    scannedSongs.add(Song(
                        title, artist, album, albumId,
                        ContentUris.withAppendedId(collection, id), artUri
                    ))
                }
            }
        } catch (error: Exception) {
            scanError = error.message?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName
        }
        return MediaScanResult(scannedSongs, scanError)
    }

    fun scanVideos(contentResolver: ContentResolver): MediaScanResult<DeviceVideo> {
        val scannedVideos = mutableListOf<DeviceVideo>()
        var scanError: String? = null
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )
        try {
            val cursor = contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            ) ?: throw IllegalStateException("MediaStore returned no video cursor")
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn)?.takeIf { value -> value.isNotBlank() }
                        ?: it.getString(nameColumn)?.takeIf { value -> value.isNotBlank() }
                        ?: "Untitled video"
                    scannedVideos.add(DeviceVideo(
                        id, title, ContentUris.withAppendedId(collection, id),
                        it.getLong(durationColumn)
                    ))
                }
            }
        } catch (error: Exception) {
            scanError = error.message?.takeIf { it.isNotBlank() }
                ?: error.javaClass.simpleName
        }
        return MediaScanResult(scannedVideos, scanError)
    }
}
