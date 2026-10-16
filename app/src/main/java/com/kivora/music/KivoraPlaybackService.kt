package com.kivora.music

import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.os.Bundle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class KivoraPlaybackService : MediaSessionService() {

    private var musicPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var equalizerAudioSessionId = 0
    private var bassBoost: BassBoost? = null
    private var bassBoostAudioSessionId = 0

    private val equalizerCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
            if (controller.packageName == packageName) {
                commands.add(SessionCommand(COMMAND_GET_STATE, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_SET_ENABLED, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_SET_BAND, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_SET_PRESET, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_BASS_BOOST_GET_STATE, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_BASS_BOOST_SET_ENABLED, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_BASS_BOOST_SET_STRENGTH, Bundle.EMPTY))
            }
            return MediaSession.ConnectionResult.accept(
                commands.build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }

            val result = runCatching {
                when (customCommand.customAction) {
                    COMMAND_GET_STATE -> equalizerState()
                    COMMAND_SET_ENABLED -> {
                        val effect = requireEqualizer()
                        effect.enabled = args.getBoolean(KEY_ENABLED)
                        equalizerState()
                    }
                    COMMAND_SET_BAND -> {
                        val effect = requireEqualizer()
                        val band = args.getInt(KEY_BAND)
                        require(band in 0 until effect.numberOfBands) { "Invalid equalizer band." }
                        effect.setBandLevel(band.toShort(), args.getInt(KEY_LEVEL).toShort())
                        equalizerState()
                    }
                    COMMAND_SET_PRESET -> {
                        val effect = requireEqualizer()
                        val preset = args.getInt(KEY_PRESET)
                        require(preset in 0 until effect.numberOfPresets) { "Invalid equalizer preset." }
                        effect.usePreset(preset.toShort())
                        equalizerState()
                    }
                    COMMAND_BASS_BOOST_GET_STATE -> bassBoostState()
                    COMMAND_BASS_BOOST_SET_ENABLED -> {
                        requireBassBoost().enabled = args.getBoolean(KEY_ENABLED)
                        bassBoostState()
                    }
                    COMMAND_BASS_BOOST_SET_STRENGTH -> {
                        val effect = requireBassBoost()
                        require(effect.strengthSupported) { "Bass Boost strength adjustment is unsupported." }
                        val strength = args.getInt(KEY_STRENGTH)
                        require(strength in 0..1000) { "Invalid Bass Boost strength." }
                        effect.setStrength(strength.toShort())
                        bassBoostState()
                    }
                    else -> Bundle().apply { putBoolean(KEY_SUPPORTED, false) }
                }
            }.getOrElse { error ->
                return Futures.immediateFuture(
                    SessionResult(
                        SessionResult.RESULT_ERROR_UNKNOWN,
                        Bundle().apply {
                            putBoolean(KEY_SUPPORTED, false)
                            putString(KEY_MESSAGE, error.message ?: "Audio effect command failed.")
                        }
                    )
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, result))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        musicPlayer = player
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(equalizerCallback)
            .build()
    }

    private fun getOrCreateEqualizer(): Equalizer? {
        val sessionId = musicPlayer?.audioSessionId ?: 0
        if (sessionId != equalizerAudioSessionId) {
            runCatching { equalizer?.release() }
            equalizer = null
            equalizerAudioSessionId = sessionId
            releaseBassBoost()
        }
        if (sessionId == 0) return null
        equalizer?.let { return it }
        return runCatching {
            Equalizer(0, sessionId).also { equalizer = it }
        }.getOrNull()
    }

    private fun getOrCreateBassBoost(): BassBoost? {
        val sessionId = musicPlayer?.audioSessionId ?: 0
        if (sessionId != bassBoostAudioSessionId) {
            releaseBassBoost()
            bassBoostAudioSessionId = sessionId
        }
        if (sessionId == 0) return null
        bassBoost?.let { return it }
        return runCatching { BassBoost(0, sessionId).also { bassBoost = it } }.getOrNull()
    }

    private fun releaseBassBoost() {
        runCatching { bassBoost?.release() }
        bassBoost = null
        bassBoostAudioSessionId = 0
    }

    private fun requireBassBoost(): BassBoost = getOrCreateBassBoost()
        ?: error("Bass Boost is unavailable for the current music audio session.")

    private fun bassBoostState(): Bundle {
        val effect = getOrCreateBassBoost()
            ?: return Bundle().apply {
                putBoolean(KEY_SUPPORTED, false)
                putString(KEY_MESSAGE, "Play a song first. Bass Boost is unavailable for the current audio session.")
            }
        return runCatching {
            Bundle().apply {
                putBoolean(KEY_SUPPORTED, true)
                putBoolean(KEY_ENABLED, effect.enabled)
                putInt(KEY_STRENGTH, effect.properties?.strength?.toInt() ?: 0)
                putBoolean(KEY_STRENGTH_SUPPORTED, effect.strengthSupported)
                putInt(KEY_MIN_STRENGTH, 0)
                putInt(KEY_MAX_STRENGTH, 1000)
            }
        }.getOrElse {
            Bundle().apply {
                putBoolean(KEY_SUPPORTED, false)
                putString(KEY_MESSAGE, "Bass Boost is unsupported or could not be initialized.")
            }
        }
    }

    private fun requireEqualizer(): Equalizer = getOrCreateEqualizer()
        ?: error("Equalizer is unavailable for the current music audio session.")

    private fun equalizerState(): Bundle {
        val effect = getOrCreateEqualizer()
            ?: return Bundle().apply {
                putBoolean(KEY_SUPPORTED, false)
                putString(KEY_MESSAGE, "Play a song first. Equalizer is unavailable for the current audio session.")
            }

        return runCatching {
            val count = effect.numberOfBands.toInt()
            val range = effect.bandLevelRange
            Bundle().apply {
                putBoolean(KEY_SUPPORTED, true)
                putBoolean(KEY_ENABLED, effect.enabled)
                putInt(KEY_CURRENT_PRESET, effect.currentPreset.toInt())
                putInt(KEY_MIN_LEVEL, range[0].toInt())
                putInt(KEY_MAX_LEVEL, range[1].toInt())
                putIntArray(KEY_FREQUENCIES, IntArray(count) { effect.getCenterFreq(it.toShort()) / 1000 })
                putIntArray(KEY_LEVELS, IntArray(count) { effect.getBandLevel(it.toShort()).toInt() })
                val presetCount = effect.numberOfPresets.toInt()
                putStringArray(KEY_PRESETS, Array(presetCount) { effect.getPresetName(it.toShort()) })
            }
        }.getOrElse {
            Bundle().apply {
                putBoolean(KEY_SUPPORTED, false)
                putString(KEY_MESSAGE, "Equalizer is unsupported or could not be initialized.")
            }
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        runCatching { equalizer?.release() }
        releaseBassBoost()
        equalizer = null
        equalizerAudioSessionId = 0
        mediaSession?.release()
        musicPlayer?.release()
        mediaSession = null
        musicPlayer = null
        super.onDestroy()
    }

    companion object {
        const val COMMAND_GET_STATE = "com.kivora.music.EQUALIZER_GET_STATE"
        const val COMMAND_SET_ENABLED = "com.kivora.music.EQUALIZER_SET_ENABLED"
        const val COMMAND_SET_BAND = "com.kivora.music.EQUALIZER_SET_BAND"
        const val COMMAND_SET_PRESET = "com.kivora.music.EQUALIZER_SET_PRESET"
        const val COMMAND_BASS_BOOST_GET_STATE = "com.kivora.music.BASS_BOOST_GET_STATE"
        const val COMMAND_BASS_BOOST_SET_ENABLED = "com.kivora.music.BASS_BOOST_SET_ENABLED"
        const val COMMAND_BASS_BOOST_SET_STRENGTH = "com.kivora.music.BASS_BOOST_SET_STRENGTH"

        const val KEY_SUPPORTED = "supported"
        const val KEY_MESSAGE = "message"
        const val KEY_ENABLED = "enabled"
        const val KEY_BAND = "band"
        const val KEY_LEVEL = "level"
        const val KEY_PRESET = "preset"
        const val KEY_MIN_LEVEL = "min_level"
        const val KEY_MAX_LEVEL = "max_level"
        const val KEY_FREQUENCIES = "frequencies"
        const val KEY_LEVELS = "levels"
        const val KEY_PRESETS = "presets"
        const val KEY_CURRENT_PRESET = "current_preset"
        const val KEY_STRENGTH = "strength"
        const val KEY_MIN_STRENGTH = "min_strength"
        const val KEY_MAX_STRENGTH = "max_strength"
        const val KEY_STRENGTH_SUPPORTED = "strength_supported"
    }
}
