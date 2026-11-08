package com.kivora.music

import java.util.Locale

/** Formats Android audio-effect levels, which are reported in hundredths of a decibel. */
internal object AudioEffectLevelFormatter {
    fun format(millibels: Int): String =
        String.format(Locale.getDefault(), "%.1f dB", millibels / 100f)
}
