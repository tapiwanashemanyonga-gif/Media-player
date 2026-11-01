package com.kivora.music

/**
 * Converts timed recognition segments into a UTF-8 SubRip subtitle document.
 * Recognition engines return floating-point seconds; time conversion stays here.
 */
internal data class TimedSubtitleSegment(
    val startSeconds: Float,
    val endSeconds: Float,
    val text: String,
)

internal object SrtSubtitleWriter {
    fun render(segments: List<TimedSubtitleSegment>): String {
        val valid = segments.mapNotNull { segment ->
            val text = segment.text
                .replace("\\r", " ")
                .replace("\\n", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isEmpty() || !segment.startSeconds.isFinite() ||
                !segment.endSeconds.isFinite() || segment.startSeconds < 0f ||
                segment.endSeconds <= segment.startSeconds
            ) null else segment.copy(text = text)
        }.sortedBy { it.startSeconds }

        return valid.mapIndexed { index, segment ->
            val start = (segment.startSeconds * 1000f).toLong().coerceAtLeast(0L)
            val end = (segment.endSeconds * 1000f).toLong().coerceAtLeast(start + 1L)
            "${index + 1}\\n${formatTimestamp(start)} --> ${formatTimestamp(end)}\\n${segment.text}"
        }.joinToString("\\n\\n").let { if (it.isEmpty()) it else "$it\\n" }
    }

    private fun formatTimestamp(milliseconds: Long): String {
        val hours = milliseconds / 3_600_000L
        val minutes = (milliseconds % 3_600_000L) / 60_000L
        val seconds = (milliseconds % 60_000L) / 1_000L
        val millis = milliseconds % 1_000L
        return "%02d:%02d:%02d,%03d".format(
            java.util.Locale.ROOT, hours, minutes, seconds, millis
        )
    }
}
