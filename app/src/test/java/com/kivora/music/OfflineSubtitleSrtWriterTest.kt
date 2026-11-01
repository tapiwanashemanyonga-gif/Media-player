package com.kivora.music

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineSubtitleSrtWriterTest {
    @Test
    fun formatsTimedSegmentsAsSubRip() {
        val result = OfflineSubtitleSrtWriter.format(
            listOf(
                OfflineSubtitleCue(1.25f, 2.5f, "Hello everyone."),
                OfflineSubtitleCue(3.75f, 1.0f, "Welcome to the video."),
            ),
        )

        assertEquals(
            "1\n00:00:01,250 --> 00:00:03,750\nHello everyone.\n\n" +
                "2\n00:00:03,750 --> 00:00:04,750\nWelcome to the video.\n\n",
            result,
        )
    }

    @Test
    fun skipsBlankSegmentsAndNormalizesCaptionLineEndings() {
        val result = OfflineSubtitleSrtWriter.format(
            listOf(
                OfflineSubtitleCue(0f, 1f, "   "),
                OfflineSubtitleCue(-2f, 0f, "First line.\r\nSecond line."),
            ),
        )

        assertEquals(
            "1\n00:00:00,000 --> 00:00:00,001\nFirst line.\nSecond line.\n\n",
            result,
        )
    }

    @Test
    fun supportsTimestampsLongerThanOneDay() {
        val result = OfflineSubtitleSrtWriter.format(
            listOf(OfflineSubtitleCue(90_061f, 1f, "Long recording.")),
        )

        assertEquals(
            "1\n25:01:01,000 --> 25:01:02,000\nLong recording.\n\n",
            result,
        )
    }
}
