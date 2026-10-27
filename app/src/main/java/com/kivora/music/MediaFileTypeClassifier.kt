package com.kivora.music

import java.util.Locale

/**
 * Classifies files opened by external apps without requiring Android framework objects.
 * Keep video precedence consistent when a provider reports conflicting MIME and filename data.
 */
internal object MediaFileTypeClassifier {
    enum class Kind { AUDIO, VIDEO, UNSUPPORTED }

    fun classify(mimeType: String?, displayName: String?): Kind {
        val normalizedMime = mimeType.orEmpty().lowercase(Locale.ROOT)
        val extension = displayName.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isVideo = normalizedMime.startsWith("video/") ||
            extension in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts")
        val isAudio = normalizedMime.startsWith("audio/") ||
            extension in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "mid", "midi")

        return when {
            isVideo -> Kind.VIDEO
            isAudio -> Kind.AUDIO
            else -> Kind.UNSUPPORTED
        }
    }
}
