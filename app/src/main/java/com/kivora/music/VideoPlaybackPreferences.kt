package com.kivora.music

import android.content.SharedPreferences

/** Stores the user's local video playback speed and subtitle size preferences. */
internal class VideoPlaybackPreferences(
    private val preferences: SharedPreferences
) {
    fun videoSpeed(): Float =
        preferences.getFloat(KEY_VIDEO_SPEED, DEFAULT_VIDEO_SPEED).coerceIn(0.5f, 2f)

    fun subtitleTextSize(): Int =
        preferences.getInt(KEY_SUBTITLE_TEXT_SIZE, DEFAULT_SUBTITLE_TEXT_SIZE).coerceIn(16, 36)

    fun saveVideoSpeed(speed: Float) {
        preferences.edit().putFloat(KEY_VIDEO_SPEED, speed.coerceIn(0.5f, 2f)).apply()
    }

    fun saveSubtitleTextSize(sizeSp: Int) {
        preferences.edit().putInt(KEY_SUBTITLE_TEXT_SIZE, sizeSp.coerceIn(16, 36)).apply()
    }

    private companion object {
        const val KEY_VIDEO_SPEED = "video_speed"
        const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size_sp"
        const val DEFAULT_VIDEO_SPEED = 1f
        const val DEFAULT_SUBTITLE_TEXT_SIZE = 22
    }
}
