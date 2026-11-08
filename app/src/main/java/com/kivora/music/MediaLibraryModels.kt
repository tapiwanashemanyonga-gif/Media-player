package com.kivora.music

import android.net.Uri

/** A local audio track discovered in the device media library. */
data class Song(
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val uri: Uri,
    val artworkUri: Uri?
)

/** A local video discovered in the device media library. */
data class DeviceVideo(
    val id: Long,
    val title: String,
    val uri: Uri,
    val durationMs: Long
)
