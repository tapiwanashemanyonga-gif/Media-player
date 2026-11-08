package com.kivora.music

/** Calculates a responsive 16:9 inline video preview height in density-independent pixels. */
internal object VideoPreviewSizer {
    fun heightDp(screenWidthDp: Int, screenHeightDp: Int): Int {
        val width = screenWidthDp.coerceAtLeast(1)
        val height = screenHeightDp.coerceAtLeast(1)
        // Give tablets a larger preview, but never let video consume the whole short phone screen.
        val maxHeightDp = (height * 0.42f).toInt().coerceIn(160, 380)
        val desiredHeightDp = (width * 9f / 16f).toInt()
        return desiredHeightDp.coerceIn(140, maxHeightDp)
    }
}
