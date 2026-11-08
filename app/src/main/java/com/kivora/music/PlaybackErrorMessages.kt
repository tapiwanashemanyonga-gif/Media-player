package com.kivora.music

import androidx.media3.common.PlaybackException
import java.util.Locale

/**
 * Converts Media3 playback error codes into short, user-facing explanations.
 *
 * Kept separate from MainActivity so playback diagnostics can evolve without
 * increasing the activity's responsibilities.
 */
internal object PlaybackErrorMessages {
    fun describe(error: PlaybackException, mediaType: String): String {
        val code = error.errorCodeName.uppercase(Locale.ROOT)
        return when {
            "DECODING_FORMAT_UNSUPPORTED" in code ||
                "PARSING_CONTAINER_UNSUPPORTED" in code ||
                "DECODING_FAILED" in code ->
                "This $mediaType uses a format or codec this device cannot play."
            "IO_FILE_NOT_FOUND" in code ||
                "IO_NO_PERMISSION" in code ||
                "CONTENT_UNSUPPORTED" in code ->
                "This $mediaType file is unavailable or A1 FAMILY MEDIA cannot access it."
            "PARSING" in code ->
                "This $mediaType file may be damaged or use an unsupported format."
            else -> "Couldn't play this $mediaType. Check that the file is available and try another one."
        }
    }
}
