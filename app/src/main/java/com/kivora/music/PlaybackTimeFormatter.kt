package com.kivora.music

import java.util.Locale

/** Formats playback positions and durations as minutes and seconds. */
internal object PlaybackTimeFormatter {
    fun format(milliseconds: Long): String {
        val totalSeconds =
            (milliseconds.coerceAtLeast(0L) / 1000L).toInt()

        return String.format(
            Locale.getDefault(),
            "%d:%02d",
            totalSeconds / 60,
            totalSeconds % 60
        )
    }
}
