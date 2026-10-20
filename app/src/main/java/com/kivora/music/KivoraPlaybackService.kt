package com.kivora.music

import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
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
    private val sleepTimerHandler = Handler(Looper.getMainLooper())
    private var sleepTimerDeadlineElapsedRealtime = 0L
    private var sleepTimerDurationMinutes = 0
    private var sleepTimerExpired = false
    private val appliedPlaybackRequestIds = linkedSetOf<String>()

    private val sleepTimerRunnable = object : Runnable {
        override fun run() {
            val remainingMs = sleepTimerDeadlineElapsedRealtime - SystemClock.elapsedRealtime()
            if (sleepTimerDeadlineElapsedRealtime == 0L) return
            if (remainingMs > 0L) {
                sleepTimerHandler.postDelayed(this, remainingMs)
                return
            }
            finishSleepTimer()
        }
    }

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
                commands.add(SessionCommand(COMMAND_SLEEP_TIMER_GET_STATE, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_SLEEP_TIMER_SET, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_SLEEP_TIMER_CANCEL, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_QUEUE_ADD_END, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_QUEUE_PLAY_NEXT, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_QUEUE_GET_ORDER, Bundle.EMPTY))
                commands.add(SessionCommand(COMMAND_PLAYBACK_REQUEST, Bundle.EMPTY))
            }
            return MediaSession.ConnectionResult.accept(
                commands.build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        @androidx.media3.common.util.UnstableApi
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
                    COMMAND_SLEEP_TIMER_GET_STATE -> sleepTimerState()
                    COMMAND_SLEEP_TIMER_SET -> {
                        val durationMinutes = args.getInt(KEY_DURATION_MINUTES)
                        require(durationMinutes in SLEEP_TIMER_OPTIONS_MINUTES) { "Invalid sleep timer duration." }
                        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
                        sleepTimerDurationMinutes = durationMinutes
                        sleepTimerDeadlineElapsedRealtime =
                            SystemClock.elapsedRealtime() + durationMinutes * 60_000L
                        sleepTimerExpired = false
                        sleepTimerHandler.postDelayed(sleepTimerRunnable, durationMinutes * 60_000L)
                        sleepTimerState()
                    }
                    COMMAND_SLEEP_TIMER_CANCEL -> {
                        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
                        sleepTimerDeadlineElapsedRealtime = 0L
                        sleepTimerDurationMinutes = 0
                        sleepTimerExpired = false
                        sleepTimerState()
                    }
                    COMMAND_QUEUE_ADD_END -> addQueueItem(args, playNext = false)
                    COMMAND_QUEUE_PLAY_NEXT -> addQueueItem(args, playNext = true)
                    COMMAND_QUEUE_GET_ORDER -> queueOrderState()
                    COMMAND_PLAYBACK_REQUEST -> applyPlaybackRequest(args)
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

    private fun sleepTimerState(): Bundle {
        val deadline = sleepTimerDeadlineElapsedRealtime
        val remainingMs = if (deadline > 0L) {
            (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else 0L
        if (deadline > 0L && remainingMs == 0L) finishSleepTimer()
        val wasExpired = sleepTimerExpired
        sleepTimerExpired = false
        return Bundle().apply {
            putBoolean(KEY_TIMER_ACTIVE, remainingMs > 0L)
            putBoolean(KEY_TIMER_EXPIRED, wasExpired)
            putInt(KEY_DURATION_MINUTES, sleepTimerDurationMinutes)
            putLong(KEY_REMAINING_MILLIS, remainingMs)
        }
    }

    private fun finishSleepTimer() {
        if (sleepTimerDeadlineElapsedRealtime == 0L) return
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerDeadlineElapsedRealtime = 0L
        sleepTimerDurationMinutes = 0
        sleepTimerExpired = true
        musicPlayer?.pause()
        Toast.makeText(this, "Sleep timer ended. Music paused.", Toast.LENGTH_LONG).show()
    }

    @androidx.media3.common.util.UnstableApi
    private fun applyPlaybackRequest(args: Bundle): Bundle {
        val requestId = args.getString(KEY_PLAYBACK_REQUEST_ID).orEmpty()
        require(requestId.isNotBlank()) { "Missing playback request ID." }
        if (requestId in appliedPlaybackRequestIds) {
            return Bundle().apply {
                putBoolean(KEY_SUPPORTED, true)
                putBoolean(KEY_DUPLICATE, true)
            }
        }
        val player = musicPlayer ?: error("Music player is unavailable.")
        when (args.getString(KEY_PLAYBACK_KIND)) {
            "resume_music" -> player.play()
            "play_queue" -> {
                val uris = args.getStringArrayList(KEY_PLAYBACK_URIS).orEmpty()
                val titles = args.getStringArrayList(KEY_PLAYBACK_TITLES).orEmpty()
                val artists = args.getStringArrayList(KEY_PLAYBACK_ARTISTS).orEmpty()
                val albums = args.getStringArrayList(KEY_PLAYBACK_ALBUMS).orEmpty()
                val artwork = args.getStringArrayList(KEY_PLAYBACK_ARTWORK).orEmpty()
                require(uris.isNotEmpty() && uris.size == titles.size && uris.size == artists.size &&
                    uris.size == albums.size && uris.size == artwork.size) { "Invalid playback queue." }
                val items = uris.indices.map { index ->
                    val uri = Uri.parse(uris[index])
                    require(uri.scheme == "content" || uri.scheme == "file") { "Invalid local audio URI." }
                    androidx.media3.common.MediaItem.Builder().setUri(uri).setMediaId(uri.toString())
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(titles[index]).setArtist(artists[index]).setAlbumTitle(albums[index])
                            .apply { artwork[index].takeIf(String::isNotBlank)?.let { setArtworkUri(Uri.parse(it)) } }
                            .build()).build()
                }
                val startIndex = args.getInt(KEY_PLAYBACK_INDEX)
                require(startIndex in items.indices) { "Invalid playback queue index." }
                player.setMediaItems(items, startIndex, 0L)
                player.prepare()
                player.play()
            }
            "play_queue_item" -> {
                val targetUri = args.getString(KEY_PLAYBACK_URI)
                val index = if (targetUri != null) {
                    (0 until player.mediaItemCount).firstOrNull { itemIndex ->
                        player.getMediaItemAt(itemIndex).localConfiguration?.uri?.toString() == targetUri
                    }
                } else args.getInt(KEY_PLAYBACK_INDEX).takeIf { it in 0 until player.mediaItemCount }
                if (index != null) {
                    player.seekTo(index, 0L)
                    player.play()
                } else {
                    val uri = targetUri?.let(Uri::parse)
                        ?: error("Requested queue item is unavailable.")
                    require(uri.scheme == "content" || uri.scheme == "file") {
                        "Invalid local audio URI."
                    }
                    // The queue may be gone after service/process recreation. Recover the
                    // explicitly requested item instead of leaving its durable request stuck.
                    val item = androidx.media3.common.MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(uri.toString())
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                            .setTitle("Unknown title")
                            .build())
                        .build()
                    player.setMediaItem(item)
                    player.prepare()
                    player.play()
                }
            }
            else -> error("Unsupported music playback request.")
        }
        appliedPlaybackRequestIds.add(requestId)
        while (appliedPlaybackRequestIds.size > 128) {
            appliedPlaybackRequestIds.remove(appliedPlaybackRequestIds.first())
        }
        return Bundle().apply { putBoolean(KEY_SUPPORTED, true) }
    }

    @androidx.media3.common.util.UnstableApi
    private fun addQueueItem(args: Bundle, playNext: Boolean): Bundle {
        val player = musicPlayer ?: error("Music player is unavailable.")
        val uri = Uri.parse(args.getString(KEY_QUEUE_URI).orEmpty())
        require(uri.scheme == "content" || uri.scheme == "file") { "Invalid local audio URI." }
        val title = args.getString(KEY_QUEUE_TITLE).orEmpty().ifBlank { "Unknown title" }
        val artist = args.getString(KEY_QUEUE_ARTIST).orEmpty()
        val album = args.getString(KEY_QUEUE_ALBUM).orEmpty().ifBlank { "Unknown album" }
        val artworkUri = args.getString(KEY_QUEUE_ARTWORK_URI)?.let(Uri::parse)
        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .apply { artworkUri?.let { setArtworkUri(it) } }
                    .build()
            )
            .build()

        val currentIndex = player.currentMediaItemIndex
        val oldItemCount = player.mediaItemCount
        val wasEmpty = player.mediaItemCount == 0
        val shuffleEnabled = player.shuffleModeEnabled
        val previousNextIndex = if (shuffleEnabled && currentIndex in 0 until oldItemCount) {
            player.nextMediaItemIndex
        } else {
            androidx.media3.common.C.INDEX_UNSET
        }
        val insertionIndex = if (playNext && currentIndex in 0 until oldItemCount) {
            currentIndex + 1
        } else oldItemCount
        player.addMediaItem(insertionIndex, mediaItem)

        if (!wasEmpty && shuffleEnabled) {
            val newItemIndex = player.mediaItemCount - 1
            val remapIndex: (Int) -> Int = { index ->
                if (index >= insertionIndex) index + 1 else index
            }
            val shuffleOrder = buildList {
                if (currentIndex in 0 until oldItemCount) add(currentIndex)
                if (playNext) add(insertionIndex)
                if (previousNextIndex in 0 until oldItemCount && previousNextIndex != currentIndex) {
                    add(remapIndex(previousNextIndex))
                }
                val prioritized = toSet()
                val remaining = (0 until player.mediaItemCount).filterNot { it in prioritized }
                    .filterNot { !playNext && it == newItemIndex }
                    .shuffled()
                addAll(remaining)
                if (!playNext) add(newItemIndex)
                if (isEmpty()) addAll((0 until player.mediaItemCount).shuffled())
            }
            player.setShuffleOrder(
                DefaultShuffleOrder(shuffleOrder.toIntArray(), SystemClock.elapsedRealtime())
            )
        }

        if (wasEmpty || (playNext && currentIndex !in 0 until oldItemCount)) {
            if (!wasEmpty) player.seekTo(insertionIndex, androidx.media3.common.C.TIME_UNSET)
            player.prepare()
            player.play()
        }

        return Bundle().apply { putBoolean(KEY_SUPPORTED, true) }
    }

    @androidx.media3.common.util.UnstableApi
    private fun queueOrderState(): Bundle {
        val player = musicPlayer ?: error("Music player is unavailable.")
        val count = player.mediaItemCount
        if (count == 0) return Bundle().apply { putIntArray(KEY_QUEUE_INDICES, intArrayOf()) }

        val currentIndex = player.currentMediaItemIndex
        val order = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        if (currentIndex in 0 until count) seen.add(currentIndex)

        var index = when {
            currentIndex !in 0 until count -> 0
            player.shuffleModeEnabled -> player.nextMediaItemIndex
            currentIndex + 1 < count -> currentIndex + 1
            else -> androidx.media3.common.C.INDEX_UNSET
        }
        val wrapsQueue = player.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF
        val expectedSize = count - if (currentIndex in 0 until count) 1 else 0
        while (order.size < expectedSize) {
            if (index == androidx.media3.common.C.INDEX_UNSET) {
                if (!wrapsQueue) break
                index = 0
            }
            if (index !in 0 until count || !seen.add(index)) break
            order.add(index)
            index = if (index + 1 < count) {
                index + 1
            } else {
                androidx.media3.common.C.INDEX_UNSET
            }
        }
        return Bundle().apply { putIntArray(KEY_QUEUE_INDICES, order.toIntArray()) }
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
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerDeadlineElapsedRealtime = 0L
        sleepTimerDurationMinutes = 0
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
        const val COMMAND_SLEEP_TIMER_GET_STATE = "com.kivora.music.SLEEP_TIMER_GET_STATE"
        const val COMMAND_SLEEP_TIMER_SET = "com.kivora.music.SLEEP_TIMER_SET"
        const val COMMAND_SLEEP_TIMER_CANCEL = "com.kivora.music.SLEEP_TIMER_CANCEL"
        const val COMMAND_QUEUE_ADD_END = "com.kivora.music.QUEUE_ADD_END"
        const val COMMAND_QUEUE_PLAY_NEXT = "com.kivora.music.QUEUE_PLAY_NEXT"
        const val COMMAND_QUEUE_GET_ORDER = "com.kivora.music.QUEUE_GET_ORDER"
        const val COMMAND_PLAYBACK_REQUEST = "com.kivora.music.PLAYBACK_REQUEST"

        val SLEEP_TIMER_OPTIONS_MINUTES = setOf(15, 30, 45, 60)

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
        const val KEY_DURATION_MINUTES = "duration_minutes"
        const val KEY_REMAINING_MILLIS = "remaining_millis"
        const val KEY_TIMER_ACTIVE = "timer_active"
        const val KEY_TIMER_EXPIRED = "timer_expired"
        const val KEY_QUEUE_URI = "queue_uri"
        const val KEY_QUEUE_TITLE = "queue_title"
        const val KEY_QUEUE_ARTIST = "queue_artist"
        const val KEY_QUEUE_ALBUM = "queue_album"
        const val KEY_QUEUE_ARTWORK_URI = "queue_artwork_uri"
        const val KEY_QUEUE_INDICES = "queue_indices"
        const val KEY_PLAYBACK_REQUEST_ID = "playback_request_id"
        const val KEY_PLAYBACK_KIND = "playback_kind"
        const val KEY_PLAYBACK_URIS = "playback_uris"
        const val KEY_PLAYBACK_TITLES = "playback_titles"
        const val KEY_PLAYBACK_ARTISTS = "playback_artists"
        const val KEY_PLAYBACK_ALBUMS = "playback_albums"
        const val KEY_PLAYBACK_ARTWORK = "playback_artwork"
        const val KEY_PLAYBACK_INDEX = "playback_index"
        const val KEY_PLAYBACK_URI = "playback_uri"
        const val KEY_DUPLICATE = "duplicate"
    }
}
