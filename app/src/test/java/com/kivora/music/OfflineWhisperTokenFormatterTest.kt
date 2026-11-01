package com.kivora.music

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineWhisperTokenFormatterTest {
    @Test
    fun groupsTimestampedTokensIntoReadableCaptions() {
        val cues = OfflineWhisperTokenFormatter.toCues(
            tokens = listOf(" Hello", " there.", " How", " are", " you?"),
            timestamps = listOf(0.2f, 0.8f, 1.4f, 1.8f, 2.2f),
            absoluteStartSeconds = 30f,
            windowDurationSeconds = 4f,
        )

        assertEquals(2, cues.size)
        assertEquals("Hello there.", cues[0].text)
        assertEquals(30.2f, cues[0].startSeconds, 0.001f)
        assertEquals("How are you?", cues[1].text)
        assertEquals(31.4f, cues[1].startSeconds, 0.001f)
    }

    @Test
    fun ignoresWhisperControlTokensAndHandlesMissingTimestamps() {
        val cues = OfflineWhisperTokenFormatter.toCues(
            tokens = listOf("<|startoftranscript|>", " Good", " morning."),
            timestamps = listOf(0f, 0.5f, 1.1f),
            absoluteStartSeconds = 0f,
            windowDurationSeconds = 3f,
        )

        assertEquals(1, cues.size)
        assertEquals("Good morning.", cues.single().text)
    }

    @Test
    fun returnsNoCaptionsWhenNoTimedTokensAreAvailable() {
        assertEquals(
            emptyList<OfflineSubtitleCue>(),
            OfflineWhisperTokenFormatter.toCues(emptyList(), emptyList(), 0f, 30f),
        )
    }
}
