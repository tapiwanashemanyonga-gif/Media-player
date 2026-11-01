package com.kivora.music

import java.util.Locale
import kotlin.math.roundToLong

/** One timed caption segment returned by the offline speech recognizer. */
internal data class OfflineSubtitleCue(
    val startSeconds: Float,
    val durationSeconds: Float,
    val text: String,
)

/** Converts recognizer segments into the SubRip format understood by Media3. */
internal object OfflineSubtitleSrtWriter {
    fun format(cues: List<OfflineSubtitleCue>): String {
        val output = StringBuilder()
        var cueNumber = 1

        cues.forEach { cue ->
            val caption = cue.text.trim()
            if (caption.isEmpty()) return@forEach

            val startMs = (cue.startSeconds.coerceAtLeast(0f) * 1000f).roundToLong()
            val durationMs = (cue.durationSeconds.coerceAtLeast(0f) * 1000f).roundToLong()
            val endMs = (startMs + durationMs).coerceAtLeast(startMs + 1L)

            output.append(cueNumber++).append('\n')
            output.append(formatTimestamp(startMs))
                .append(" --> ")
                .append(formatTimestamp(endMs))
                .append('\n')
            output.append(caption.replace("\r\n", "\n").replace('\r', '\n'))
                .append("\n\n")
        }

        return output.toString()
    }

    private fun formatTimestamp(milliseconds: Long): String {
        val safeMs = milliseconds.coerceAtLeast(0L)
        val hours = safeMs / 3_600_000L
        val minutes = (safeMs / 60_000L) % 60L
        val seconds = (safeMs / 1_000L) % 60L
        val millis = safeMs % 1_000L
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d,%03d",
            hours,
            minutes,
            seconds,
            millis,
        )
    }
}
