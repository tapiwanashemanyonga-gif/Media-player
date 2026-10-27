package com.kivora.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFileTypeClassifierTest {
    @Test
    fun classifiesAudioByMimeType() {
        assertEquals(MediaFileTypeClassifier.Kind.AUDIO, MediaFileTypeClassifier.classify("audio/mpeg", "track.unknown"))
    }

    @Test
    fun classifiesAudioByCaseInsensitiveExtension() {
        assertEquals(MediaFileTypeClassifier.Kind.AUDIO, MediaFileTypeClassifier.classify(null, "VOICE.M4A"))
    }

    @Test
    fun classifiesVideoByMimeType() {
        assertEquals(MediaFileTypeClassifier.Kind.VIDEO, MediaFileTypeClassifier.classify("video/mp4", "clip.bin"))
    }

    @Test
    fun classifiesVideoByExtensionWhenMimeTypeIsMissing() {
        assertEquals(MediaFileTypeClassifier.Kind.VIDEO, MediaFileTypeClassifier.classify(null, "holiday.MKV"))
    }

    @Test
    fun videoWinsWhenMimeAndExtensionConflict() {
        assertEquals(MediaFileTypeClassifier.Kind.VIDEO, MediaFileTypeClassifier.classify("audio/mpeg", "clip.mp4"))
    }

    @Test
    fun rejectsUnsupportedFiles() {
        assertEquals(MediaFileTypeClassifier.Kind.UNSUPPORTED, MediaFileTypeClassifier.classify("application/pdf", "notes.pdf"))
    }
}
