package com.kivora.music

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeFormatterTest {
    @Test
    fun formatsWholeMinutesAndSeconds() {
        assertEquals("0:00", PlaybackTimeFormatter.format(0L))
        assertEquals("1:05", PlaybackTimeFormatter.format(65_000L))
        assertEquals("61:01", PlaybackTimeFormatter.format(3_661_000L))
    }

    @Test
    fun clampsNegativePlaybackPositionsToZero() {
        assertEquals("0:00", PlaybackTimeFormatter.format(-1L))
    }
}

class VideoPreviewSizerTest {
    @Test
    fun usesSixteenByNineHeightForTypicalPhone() {
        assertEquals(219, VideoPreviewSizer.heightDp(390, 844))
    }

    @Test
    fun capsPreviewHeightOnLargeTablet() {
        assertEquals(380, VideoPreviewSizer.heightDp(1200, 1600))
    }

    @Test
    fun handlesInvalidDimensionsSafely() {
        assertEquals(140, VideoPreviewSizer.heightDp(0, 0))
    }
}
