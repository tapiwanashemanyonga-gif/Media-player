package com.kivora.music

import kotlin.math.max

/** Turns Whisper's timestamped tokens into readable, short SubRip caption segments. */
internal object OfflineWhisperTokenFormatter {
    fun toCues(
        tokens: List<String>,
        timestamps: List<Float>,
        absoluteStartSeconds: Float,
        windowDurationSeconds: Float,
    ): List<OfflineSubtitleCue> {
        if (tokens.isEmpty() || timestamps.isEmpty()) return emptyList()

        val cues = mutableListOf<OfflineSubtitleCue>()
        val caption = StringBuilder()
        var captionStart = -1f
        var previousTime = 0f

        fun flush(nextTime: Float) {
            val text = caption.toString().trim()
            if (text.isNotEmpty() && captionStart >= 0f) {
                val localEnd = nextTime.coerceAtLeast(captionStart + 0.8f)
                    .coerceAtMost(max(windowDurationSeconds, captionStart + 0.8f))
                cues += OfflineSubtitleCue(
                    startSeconds = absoluteStartSeconds + captionStart,
                    durationSeconds = (localEnd - captionStart).coerceAtLeast(0.8f),
                    text = text,
                )
            }
            caption.clear()
            captionStart = -1f
        }

        val count = minOf(tokens.size, timestamps.size)
        for (index in 0 until count) {
            val token = tokens[index]
            val time = timestamps[index].takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: continue
            if (token.isBlank() || token.startsWith("<|")) continue

            if (captionStart < 0f) captionStart = time
            caption.append(token)
            previousTime = time

            val nextTime = timestamps.getOrNull(index + 1)
                ?.takeIf { it.isFinite() && it >= time }
                ?: windowDurationSeconds
            val normalized = caption.toString().trim()
            val sentenceEnded = normalized.endsWith(".") ||
                normalized.endsWith("?") || normalized.endsWith("!")
            val captionTooLong = normalized.length >= 72
            val captionTooOld = nextTime - captionStart >= 4.5f

            if (sentenceEnded || captionTooLong || captionTooOld) {
                flush(nextTime)
            }
        }
        flush(max(windowDurationSeconds, previousTime + 0.8f))
        return cues
    }
}
