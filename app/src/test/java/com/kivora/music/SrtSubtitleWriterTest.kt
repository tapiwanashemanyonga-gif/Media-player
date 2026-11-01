package com.kivora.music

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtSubtitleWriterTest {
    @Test
    fun writesSubRipTimestampsAndSequentialIndexes() {
        val actual = SrtSubtitleWriter.render(
            listOf(
                TimedSubtitleSegment(1.25f, 2.5f, "Hello there"),
                TimedSubtitleSegment(61.0f, 63.125f, "Second line"),
            )
        )
        assertEquals(
            "1\\n00:00:01,250 --> 00:00:02,500\\nHello there\\n\\n" +
                "2\\n00:01:01,000 --> 00:01:03,125\\nSecond line\\n",
            actual
        )
    }

    @Test
    fun sortsSegmentsAndRemovesBlankOrInvalidEntries() {
        val actual = SrtSubtitleWriter.render(
            listOf(
                TimedSubtitleSegment(3f, 4f, " later "),
                TimedSubtitleSegment(0f, 0f, "invalid"),
                TimedSubtitleSegment(1f, 2f, "  "),
                TimedSubtitleSegment(1f, 2f, " first\\nline "),
            )
        )
        assertEquals(
            "1\\n00:00:01,000 --> 00:00:02,000\\nfirst line\\n\\n" +
                "2\\n00:00:03,000 --> 00:00:04,000\\nlater\\n",
            actual
        )
    }

    @Test
    fun returnsEmptyDocumentWhenNoValidSegmentsExist() {
        assertEquals("", SrtSubtitleWriter.render(emptyList()))
    }
}
