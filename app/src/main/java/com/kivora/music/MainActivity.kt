
package com.kivora.music

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Log
import android.util.Size
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.EditText
import com.startapp.sdk.ads.banner.Banner
import com.startapp.sdk.ads.banner.BannerListener
import com.startapp.sdk.ads.nativead.StartAppNativeAd
import com.startapp.sdk.ads.nativead.NativeAdDetails
import com.startapp.sdk.ads.nativead.NativeAdPreferences
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

// Publisher-provided Adsterra SmartLink. Confirm it is Active and configured for mainstream
// offers in the Adsterra dashboard; this app opens it only after the user taps the sponsored card.
private const val ADSTERRA_SMARTLINK_URL = "https://asiafilm.org/4/d52fd43d84340c6fa7784c326206088f"

data class Song(
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val uri: Uri,
    val artworkUri: Uri?
)

data class DeviceVideo(
    val id: Long,
    val title: String,
    val uri: Uri,
    val durationMs: Long
)

private data class SettingsAction(
    val icon: String,
    val title: String,
    val description: String,
    val action: () -> Unit
)

class MainActivity : AppCompatActivity() {

    private var player: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private lateinit var songList: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var artwork: ImageView
    private lateinit var miniArtwork: ImageView
    private var artworkMotionAnimator: android.animation.AnimatorSet? = null
    private var playerBannerContainer: LinearLayout? = null
    private var startIoNativeAdRequested = false
    private var loadedStartIoNativeAd: NativeAdDetails? = null
    private var hasSeenFirstResume = false
    private var returnedFromBackground = false
    private var suppressNextResumeAd = false
    private var musicTabAdAttempted = false
    private var notificationPromptQueued = false
    private lateinit var playButton: TextView
    private lateinit var shuffleButton: TextView
    private lateinit var repeatButton: TextView
    private lateinit var searchBox: EditText
    private lateinit var searchRow: LinearLayout
    private lateinit var searchVisibilityToggle: TextView
    private lateinit var libraryVisibilityToggle: TextView
    private var searchTabVisible = true
    private var libraryShortcutsVisible = true
    private var libraryHeaderVisible = true
    private var libraryContentVisible = true
    private lateinit var clearSearchButton: TextView
    private lateinit var progress: SeekBar
    private lateinit var elapsedText: TextView
    private lateinit var durationText: TextView
    private lateinit var libraryCount: TextView
    private lateinit var favoriteFilterButton: TextView
    private lateinit var playlistButton: TextView
    private lateinit var libraryHeader: LinearLayout
    private lateinit var sortContainer: LinearLayout
    private lateinit var sortButton: TextView
    private lateinit var songScroll: ScrollView
    private lateinit var playerCard: LinearLayout
    private lateinit var playerScroll: ScrollView
    private lateinit var homeScroll: ScrollView
    private lateinit var homeContent: LinearLayout
    private lateinit var videoPlaceholder: LinearLayout
    private lateinit var videoList: LinearLayout
    private lateinit var videoStatus: TextView
    private lateinit var videoPlayerView: PlayerView
    private var videoPlayerActions: LinearLayout? = null
    private var fullscreenVideoButton: View? = null
    private var minimizeVideoButton: View? = null
    private var videoPlayer: ExoPlayer? = null
    private var navigationVideoUri: String? = null
    private var lastFailedVideoUri: String? = null
    private val videoPlayerErrorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            reportVideoPlaybackFailure()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val uri = mediaItem?.localConfiguration?.uri?.toString() ?: return
            selectedVideoUri = uri
            navigationVideoUri = uri
            if (::videoList.isInitialized && ::searchBox.isInitialized) {
                renderVideos(searchBox.text?.toString().orEmpty())
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // If video starts despite a delayed/racing music request, stop only the music player.
            // Never call a helper here that can also pause this video player.
            if (isPlaying) player?.pause()
            tryRunPendingPlaybackAfterAd()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            tryRunPendingPlaybackAfterAd()
            if (playbackState == Player.STATE_ENDED && ::videoStatus.isInitialized) {
                videoStatus.text = "Finished the video queue. Choose a video to play again."
            }
        }
    }
    private var fullscreenVideoDialog: android.app.Dialog? = null
    private var sleepTimerDialog: AlertDialog? = null
    private var sleepTimerStatus: TextView? = null
    private var sleepTimerPollRunnable: Runnable? = null
    private var upNextButton: TextView? = null
    private var upNextDialog: AlertDialog? = null
    private var upNextList: LinearLayout? = null
    private var upNextSummary: TextView? = null
    private var upNextClearButton: TextView? = null
    private var queueOrderIndices: IntArray? = null
    private var queueOrderItemCount = -1
    private var queueOrderCurrentIndex = -1
    private var queueOrderShuffleEnabled = false
    private var queueOrderRepeatMode = Player.REPEAT_MODE_OFF
    private var queueOrderRequestId = 0
    private val deviceVideos = mutableListOf<DeviceVideo>()
    private var videosLoaded = false
    private var selectedVideoUri: String? = null
    private var currentTab = "search"
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlayButton: TextView
    private val navigationButtons = linkedMapOf<String, TextView>()
    private val libraryBrowseButtons = linkedMapOf<String, TextView>()

    private val songs = mutableListOf<Song>()
    private var activeQueue: List<Song> = emptyList()
    private val recentlyPlayedUris = mutableListOf<String>()
    private val favoriteSongUris = mutableSetOf<String>()
    private val playlists = linkedMapOf<String, MutableSet<String>>()

    private var showFavoritesOnly = false
    private var selectedPlaylist: String? = null
    private var libraryBrowseMode = "songs"
    private var selectedAlbumKey: String? = null
    private var selectedArtistName: String? = null
    private var librarySortMode = "title"
    private var userSeeking = false
    private var pendingLyricsPdfTitle: String = "A1 Family Lyrics"
    private var pendingLyricsPdfText: String = ""
    private val lyricsPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) writeLyricsPdf(uri, pendingLyricsPdfTitle, pendingLyricsPdfText)
        }
    private var activityResumed = false
    private var pendingPlaybackAfterAd: PendingPlayback? = null
    private var playbackContinuesAfterAd = false
    private var musicDispatchRetryId: String? = null
    private var musicDispatchRetryCount = 0
    private var musicDispatchRetry: Runnable? = null
    private enum class PlaybackState { STOPPED, ACTIVE, UNKNOWN }
    private var songsLoaded = false
    private var controllerRetryRunnable: Runnable? = null
    private var controllerReconnectAttempt = 0

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearchRender: Runnable? = null
    // Optimization: decode album artwork away from the UI thread.
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private var artworkRequestId = 0
    private val artworkCache = object : LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 8L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    // Optimization: cache lowercase search text for each song.
    private val normalizedSearchText = mutableMapOf<String, String>()

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                loadSongs()
            } else {
                Toast.makeText(
                    this,
                    "Allow music access to show songs from your device.",
                    Toast.LENGTH_LONG
                ).show()
            }
            requestNotificationPermissionOnFirstLaunch()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                getSharedPreferences("a1_family_notifications", MODE_PRIVATE)
                    .edit().putBoolean("weekly_reminders_enabled", true).apply()
                ListeningReminderScheduler.schedule(this)
                Toast.makeText(this, "Weekly listening reminders enabled.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Notifications are off. You can enable them in Android settings.", Toast.LENGTH_LONG).show()
            }
        }

    private val videoPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                loadVideos()
            } else {
                videoStatus.text = "Allow video access to show your videos."
                Toast.makeText(
                    this,
                    "Video access is needed to display your device videos.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int
        ) {
            recordRecentlyPlayed(mediaItem)
            refreshActiveQueue()
            updateNowPlaying()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            refreshActiveQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // If music starts despite a delayed/racing video request, stop only video.
            // Do not pause the music that just started.
            if (isPlaying) {
                videoPlayer?.let { video ->
                    video.playWhenReady = false
                    video.pause()
                }
            }
            updatePlayButton()
            updateArtworkMotion(isPlaying)
            if (!isPlaying && currentTab == "search" && !musicTabAdAttempted && videoPlayer?.isPlaying != true) {
                musicTabAdAttempted = true
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed && currentTab == "search" &&
                        player?.isPlaying != true && videoPlayer?.isPlaying != true
                    ) StartIoVideoAds.showOptionalAd(this@MainActivity) { }
                }, 500L)
            }
            tryRunPendingPlaybackAfterAd()
        }

        override fun onShuffleModeEnabledChanged(
            shuffleModeEnabled: Boolean
        ) {
            updateShuffleButton()
            requestQueueOrder()
            renderUpNextQueue()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateRepeatButton()
            requestQueueOrder()
            renderUpNextQueue()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateProgress()
            tryRunPendingPlaybackAfterAd()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = themeColor(13, 8, 23)
        window.navigationBarColor = themeColor(13, 8, 23)

        val preferences = getSharedPreferences(
            "kivora_preferences",
            MODE_PRIVATE
        )
        // The Search tab is a permanent navigation destination and must always remain visible.
        searchTabVisible = true
        preferences.edit().putBoolean("hide_search_tab", false).apply()
        libraryShortcutsVisible = !preferences.getBoolean("hide_songs_albums_shortcuts", false)
        // Keep the established A1 Family purple look; do not force the Neon theme.
        if (preferences.getString("theme", null) == null ||
            preferences.getString("theme", null) == "neon"
        ) {
            preferences.edit().putString("theme", "purple").apply()
        }

        favoriteSongUris.addAll(
            preferences.getStringSet(
                "favorite_song_uris",
                emptySet<String>()
            )?.toSet().orEmpty()
        )

        loadSavedPlaylists()
        buildScreen()
        connectToPlaybackService()
        handler.post(progressUpdater)
        if (savedInstanceState == null) scheduleOpenAdIfDue()

        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (
            ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            loadSongs()
            requestNotificationPermissionOnFirstLaunch()
        } else {
            suppressNextResumeAd = true
            permissionLauncher.launch(permission)
        }
        if (savedInstanceState == null) handleIncomingMediaIntent(intent)
    }

    /**
     * Routes files opened from Android Files or another app to the matching playback path.
     */
    private fun handleIncomingMediaIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_VIEW) return
        val uri = incoming.data ?: return
        if (uri.scheme != "content" && uri.scheme != "file") return

        val mimeType = incoming.type
            ?: runCatching { contentResolver.getType(uri) }.getOrNull()
            ?: ""
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) cursor.getString(column) else null
                    } else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Selected media"

        val normalizedMime = mimeType.lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isVideo = normalizedMime.startsWith("video/") ||
            extension in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts")
        val isAudio = normalizedMime.startsWith("audio/") ||
            extension in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "mid", "midi")

        when {
            isVideo -> continueUserPlayback(PendingPlayback("play_video", uri = uri.toString(), title = name))
            isAudio -> continueUserPlayback(PendingPlayback("play_queue_item", uri = uri.toString(), title = name))
            else -> Toast.makeText(this, "This file type isn't supported by A1 FAMILY MEDIA.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingMediaIntent(intent)
    }

    private fun requestNotificationPermissionOnFirstLaunch() {
        if (notificationPromptQueued) return
        val prefs = getSharedPreferences("a1_family_notifications", MODE_PRIVATE)
        if (prefs.getBoolean("notification_permission_prompted", false)) return
        prefs.edit().putBoolean("notification_permission_prompted", true).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPromptQueued = true
            AlertDialog.Builder(this)
                .setTitle("Stay in the loop")
                .setMessage("Allow notifications for your weekly listening reminder and useful music updates. You can change this later in Android settings.")
                .setPositiveButton("Allow notifications") { _, _ ->
                    suppressNextResumeAd = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Start.io requires saving the state of the same StartAppAd used for the interstitial.
        StartIoVideoAds.saveInstanceState(this, outState)
        pendingPlaybackAfterAd?.writeTo(outState, "activity_pending_playback_")
        outState.putBoolean("activity_playback_continues_after_ad", playbackContinuesAfterAd)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // Restore the StartAppAd instance before the Activity restores its own state, as required
        // for Start.io video interstitials.
        pendingPlaybackAfterAd = PendingPlayback.readFrom(
            savedInstanceState,
            "activity_pending_playback_"
        )
        playbackContinuesAfterAd = savedInstanceState.getBoolean("activity_playback_continues_after_ad")
        StartIoVideoAds.restoreInstanceState(this, savedInstanceState)
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (!hasSeenFirstResume) {
            hasSeenFirstResume = true
        } else if (returnedFromBackground) {
            returnedFromBackground = false
            if (suppressNextResumeAd) suppressNextResumeAd = false
            else scheduleOpenAdIfDue()
        }
        // The durable playback journal and ad callback are authoritative. Retrying a
        // local pending request merely because this Activity regained focus can start audio
        // unexpectedly or dispatch the same selection twice.
        StartIoVideoAds.attach(this)
    }

    override fun onPause() {
        activityResumed = false
        returnedFromBackground = true
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
    }

    internal fun isAdHostResumedAndFocused(): Boolean =
        activityResumed && hasWindowFocus() && !isFinishing && !isDestroyed

    /** Prevents full-screen ads from covering active audio or video playback. */
    internal fun isMediaPlaybackActive(): Boolean =
        player?.isPlaying == true || videoPlayer?.isPlaying == true

    /** Called by the ad coordinator without retaining this Activity in SDK callbacks. */
    internal fun runPlaybackAfterAd(intent: PendingPlayback?) {
        if (intent == null || isFinishing || isDestroyed) return
        pendingPlaybackAfterAd = intent
        playbackContinuesAfterAd = true
        tryRunPendingPlaybackAfterAd()
    }

    private fun retainPlaybackForUnresolvedAd(intent: PendingPlayback) {
        pendingPlaybackAfterAd = intent
        playbackContinuesAfterAd = true
        if (!StartIoVideoAds.retainPendingPlayback(intent)) {
            Toast.makeText(
                this,
                "Playback request could not be saved yet. Keep A1 Family open; playback remains paused.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun discardExpiredPlaybackRequest(intent: PendingPlayback): Boolean {
        if (!StartIoVideoAds.isPlaybackRequestExpired(intent)) return false
        if (pendingPlaybackAfterAd?.requestId == intent.requestId) {
            pendingPlaybackAfterAd = null
            playbackContinuesAfterAd = false
        }
        return true
    }

    private fun tryRunPendingPlaybackAfterAd() {
        val intent = pendingPlaybackAfterAd ?: return
        if (discardExpiredPlaybackRequest(intent)) return
        if (StartIoVideoAds.wasPlaybackAcknowledged(intent)) {
            pendingPlaybackAfterAd = null
            playbackContinuesAfterAd = false
            return
        }
        if (!activityResumed) return
        val state = playbackState()
        if (state == PlaybackState.UNKNOWN) {
            StartIoVideoAds.savePendingPlayback(intent)
            return
        }
        if (intent.kind == "play_queue" && !songsLoaded) return
        // Playback is never gated by an interstitial. Optional ads are shown separately
        if (!executePlayback(intent)) {
            pendingPlaybackAfterAd = intent
            StartIoVideoAds.savePendingPlayback(intent)
            return
        }
        pendingPlaybackAfterAd = null
        playbackContinuesAfterAd = false
        StartIoVideoAds.acknowledgePendingPlayback(intent)
    }

    private fun playbackState(): PlaybackState {
        val controller = player ?: return PlaybackState.UNKNOWN
        val future = controllerFuture ?: return PlaybackState.UNKNOWN
        if (!future.isDone || future.isCancelled || videoPlayer == null) {
            return PlaybackState.UNKNOWN
        }

        fun isActive(p: Player): Boolean = p.isPlaying ||
            p.playbackState == Player.STATE_BUFFERING ||
            (p.playWhenReady && p.playbackState != Player.STATE_IDLE &&
                p.playbackState != Player.STATE_ENDED)

        return if (isActive(controller) || isActive(videoPlayer!!)) {
            PlaybackState.ACTIVE
        } else {
            PlaybackState.STOPPED
        }
    }

    private fun continueUserPlayback(intent: PendingPlayback) {
        if (discardExpiredPlaybackRequest(intent)) return
        // Every explicit play or song-selection request is dispatched without waiting for ads.
        // Persist the request first so service reconnection can safely retry the same selection.
        pendingPlaybackAfterAd = intent
        playbackContinuesAfterAd = false
        if (!StartIoVideoAds.savePendingPlayback(intent)) {
            if (playbackState() == PlaybackState.UNKNOWN) return
        }
        if (executePlayback(intent)) {
            pendingPlaybackAfterAd = null
            StartIoVideoAds.acknowledgePendingPlayback(intent)
        }
    }

    /**
     * Stop only the competing video player before dispatching music. Keeping this separate
     * from playback callbacks avoids accidentally pausing the newly started music.
     */
    private fun stopVideoForMusic() {
        videoPlayer?.let { video ->
            video.playWhenReady = false
            video.pause()
        }
    }

    private fun executePlayback(intent: PendingPlayback): Boolean {
        if (discardExpiredPlaybackRequest(intent)) return true
        when (intent.kind) {
            "resume_music" -> {
                if (player == null) return false
                stopVideoForMusic()
                dispatchMusicPlayback(intent)
                return false
            }
            "play_queue" -> {
                if (player == null) return false
                val queue = intent.queue.orEmpty().mapNotNull { uri ->
                    songs.firstOrNull { it.uri.toString() == uri }
                }
                val selectedUri = intent.queue?.getOrNull(intent.index)
                val selectedIndex = queue.indexOfFirst { it.uri.toString() == selectedUri }
                if (queue.isNotEmpty() && selectedIndex >= 0) {
                    activeQueue = queue
                    stopVideoForMusic()
                    // Service-owned request dispatch preserves the request ID across retries.
                    dispatchMusicPlayback(intent, queue, selectedIndex)
                    updateNowPlaying()
                    updatePlayButton()
                    return false
                }
                return false
            }
            "play_queue_item" -> {
                val p = player ?: return false
                val index = if (intent.uri != null) {
                    (0 until p.mediaItemCount).firstOrNull { itemIndex ->
                        p.getMediaItemAt(itemIndex).localConfiguration?.uri?.toString() == intent.uri
                    }
                } else {
                    intent.index.takeIf { it in 0 until p.mediaItemCount }
                } ?: return false
                stopVideoForMusic()
                dispatchMusicPlayback(intent, index = index)
                upNextDialog?.dismiss()
                return false
            }
            "play_video" -> {
                val uri = intent.uri?.let(Uri::parse) ?: return false
                val video = videoPlayer ?: return false
                if (video.currentMediaItem?.localConfiguration?.uri == uri &&
                    video.playbackState != Player.STATE_IDLE &&
                    video.playbackState != Player.STATE_ENDED) {
                    player?.pause()
                    video.play()
                    return true
                }
                selectedVideoUri = uri.toString()
                navigationVideoUri = uri.toString()
                updateVideoPlayerVisibility()
                lastFailedVideoUri = null
                renderVideos(searchBox.text?.toString().orEmpty())
                videoStatus.text = "Playing: ${intent.title.orEmpty()}"
                // Stop music first; never start video while the background service is still playing.
                player?.pause()
                try {
                    // Keep the full local video library in the ExoPlayer timeline. Media3
                    // advances automatically to the next item when the current video ends.
                    val playableVideos = deviceVideos.filter { isVideoAvailable(it) }
                    val playlist = playableVideos.map { item ->
                        MediaItem.Builder()
                            .setUri(item.uri)
                            .setMediaId(item.uri.toString())
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
                            .build()
                    }
                    val selectedIndex = playlist.indexOfFirst { it.localConfiguration?.uri == uri }
                    if (playlist.isNotEmpty() && selectedIndex >= 0) {
                        video.setMediaItems(playlist, selectedIndex, 0L)
                    } else {
                        video.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build())
                    }
                    video.repeatMode = Player.REPEAT_MODE_OFF
                    video.prepare()
                    // Re-assert the exclusive rule immediately before starting video.
                    player?.pause()
                    video.playWhenReady = true
                } catch (_: RuntimeException) {
                    reportVideoPlaybackFailure(uri.toString())
                    return false
                }
                return true
            }
            "resume_video" -> {
                val video = videoPlayer ?: return false
                val uri = intent.uri?.let(Uri::parse)
                if (uri != null && video.currentMediaItem?.localConfiguration?.uri != uri) {
                    video.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(intent.requestId).build())
                    video.prepare()
                }
                player?.pause()
                video.play()
                return true
            }
        }
        return false
    }

    private fun dispatchMusicPlayback(
        intent: PendingPlayback,
        queue: List<Song>? = null,
        index: Int = intent.index
    ) {
        val controller = player ?: return
        val dispatchGeneration = StartIoVideoAds.beginPlaybackDispatch(intent) ?: return
        if (musicDispatchRetryId != intent.requestId) {
            musicDispatchRetry?.let(handler::removeCallbacks)
            musicDispatchRetry = null
            musicDispatchRetryId = intent.requestId
            musicDispatchRetryCount = 0
        }
        val args = Bundle().apply {
            putString(KivoraPlaybackService.KEY_PLAYBACK_REQUEST_ID, intent.requestId)
            putString(KivoraPlaybackService.KEY_PLAYBACK_KIND, intent.kind)
            putInt(KivoraPlaybackService.KEY_PLAYBACK_INDEX, index)
            putString(KivoraPlaybackService.KEY_PLAYBACK_URI, intent.uri)
            if (queue != null) {
                putStringArrayList(KivoraPlaybackService.KEY_PLAYBACK_URIS,
                    ArrayList(queue.map { it.uri.toString() }))
                putStringArrayList(KivoraPlaybackService.KEY_PLAYBACK_TITLES,
                    ArrayList(queue.map { it.title }))
                putStringArrayList(KivoraPlaybackService.KEY_PLAYBACK_ARTISTS,
                    ArrayList(queue.map { it.artist }))
                putStringArrayList(KivoraPlaybackService.KEY_PLAYBACK_ALBUMS,
                    ArrayList(queue.map { it.album }))
                putStringArrayList(KivoraPlaybackService.KEY_PLAYBACK_ARTWORK,
                    ArrayList(queue.map { it.artworkUri?.toString().orEmpty() }))
            }
        }
        val future = controller.sendCustomCommand(
            SessionCommand(KivoraPlaybackService.COMMAND_PLAYBACK_REQUEST, Bundle.EMPTY), args
        )
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            val succeeded = result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS &&
                result.extras.getBoolean(KivoraPlaybackService.KEY_SUPPORTED)
            if (succeeded) {
                if (!StartIoVideoAds.canAcknowledgePlayback(intent)) return@addListener
                if (pendingPlaybackAfterAd?.requestId == intent.requestId) {
                    pendingPlaybackAfterAd = null
                    playbackContinuesAfterAd = false
                }
                musicDispatchRetry?.let(handler::removeCallbacks)
                musicDispatchRetry = null
                musicDispatchRetryId = null
                musicDispatchRetryCount = 0
                StartIoVideoAds.acknowledgePendingPlayback(intent)
            } else if (StartIoVideoAds.isCurrentPlaybackDispatch(intent, dispatchGeneration) &&
                !isFinishing && !isDestroyed && pendingPlaybackAfterAd?.requestId == intent.requestId) {
                StartIoVideoAds.savePendingPlayback(intent)
                if (musicDispatchRetryCount < 3) {
                    musicDispatchRetryCount++
                    val retry = Runnable {
                        musicDispatchRetry = null
                        if (pendingPlaybackAfterAd?.requestId == intent.requestId) {
                            tryRunPendingPlaybackAfterAd()
                        }
                    }
                    musicDispatchRetry?.let(handler::removeCallbacks)
                    musicDispatchRetry = retry
                    handler.postDelayed(retry, 5_000L)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun connectToPlaybackService(isRetry: Boolean = false) {
        val token = SessionToken(
            this,
            ComponentName(this, KivoraPlaybackService::class.java)
        )

        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                try {
                    if (controllerFuture !== future || isFinishing || isDestroyed) {
                        MediaController.releaseFuture(future)
                        return@addListener
                    }
                    val connected = future.get()

                    player = connected
                    connected.addListener(playerListener)
                    controllerRetryRunnable?.let(handler::removeCallbacks)
                    controllerRetryRunnable = null
                    controllerReconnectAttempt = 0

                    syncPlaylistToPlayer()
                    recordRecentlyPlayed(connected.currentMediaItem)
                    updateNowPlaying()
                    updatePlayButton()
                    updateShuffleButton()
                    updateRepeatButton()
                    updateProgress()
                    tryRunPendingPlaybackAfterAd()

                } catch (e: Exception) {
                    if (controllerFuture === future && !isFinishing && !isDestroyed) {
                        if (!isRetry) {
                            Toast.makeText(
                                this,
                                "Couldn't connect to the music service. Retrying…",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        schedulePlaybackServiceReconnect(future)
                    }
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun schedulePlaybackServiceReconnect(
        failedFuture: ListenableFuture<MediaController>
    ) {
        if (controllerRetryRunnable != null || isFinishing || isDestroyed) return
        val delayMs = (1_000L shl controllerReconnectAttempt.coerceAtMost(5))
            .coerceAtMost(30_000L)
        controllerReconnectAttempt++
        val retry = Runnable {
            controllerRetryRunnable = null
            if (!isFinishing && !isDestroyed && player == null &&
                controllerFuture === failedFuture
            ) {
                connectToPlaybackService(isRetry = true)
            }
        }
        controllerRetryRunnable = retry
        handler.postDelayed(retry, delayMs)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun selectedTheme(): String =
        getSharedPreferences("kivora_preferences", MODE_PRIVATE).getString("theme", "neon") ?: "neon"

    /** Applies a restrained hue shift to existing surfaces and lavender accents. */
    private fun themeColor(r: Int, g: Int, b: Int): Int {
        val theme = selectedTheme()
        if (theme == "purple") return Color.rgb(r, g, b)
        val brightness = (r + g + b) / 3
        if (brightness < 105) {
            return when (theme) {
                "ocean" -> Color.rgb((r * 0.42).toInt().coerceAtMost(28), (g * 0.65 + 7).toInt().coerceAtMost(55), (b * 0.72 + 10).toInt().coerceAtMost(78))
                "forest" -> Color.rgb((r * 0.35).toInt().coerceAtMost(24), (g * 0.68 + 8).toInt().coerceAtMost(62), (b * 0.30 + 5).toInt().coerceAtMost(38))
                "rose" -> Color.rgb((r * 0.62 + 8).toInt().coerceAtMost(68), (g * 0.30 + 4).toInt().coerceAtMost(28), (b * 0.48 + 8).toInt().coerceAtMost(52))
                "neon" -> Color.rgb((r * 0.18).toInt().coerceAtMost(14), (g * 0.38 + 4).toInt().coerceAtMost(34), (b * 0.68 + 8).toInt().coerceAtMost(58))
                else -> Color.rgb(r, g, b)
            }
        }
        if (r > 225 && g > 225 && b > 225) return Color.rgb(r, g, b)
        val isLavender = b >= r && (b - g) >= 10 && brightness > 95
        if (!isLavender) return Color.rgb(r, g, b)
        return when (theme) {
            "ocean" -> Color.rgb((brightness * 0.42).toInt().coerceAtMost(180), (brightness * 0.88 + 35).toInt().coerceAtMost(245), (brightness * 0.98 + 30).toInt().coerceAtMost(255))
            "forest" -> Color.rgb((brightness * 0.40).toInt().coerceAtMost(150), (brightness * 0.92 + 35).toInt().coerceAtMost(245), (brightness * 0.48 + 25).toInt().coerceAtMost(210))
            "rose" -> Color.rgb((brightness * 0.98 + 35).toInt().coerceAtMost(255), (brightness * 0.48 + 20).toInt().coerceAtMost(205), (brightness * 0.70 + 30).toInt().coerceAtMost(245))
            "neon" -> Color.rgb((brightness * 0.18 + 5).toInt().coerceAtMost(58), (brightness * 0.86 + 30).toInt().coerceAtMost(255), (brightness * 0.98 + 35).toInt().coerceAtMost(255))
            else -> Color.rgb(r, g, b)
        }
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun buildScreen() {
        val compactLayout = resources.configuration.screenWidthDp < 420
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compactLayout) 12 else 18), dp(if (compactLayout) 10 else 14), dp(if (compactLayout) 12 else 18), dp(8))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(themeColor(13, 8, 23), themeColor(24, 13, 39), themeColor(12, 10, 22))
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(2))
        }

        val brandMark = TextView(this).apply {
            text = "A1"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(themeColor(174, 98, 255), themeColor(104, 48, 190))
            ).apply { cornerRadius = dp(16).toFloat() }
            elevation = dp(5).toFloat()
            contentDescription = "A1 Family"
        }
        header.addView(brandMark, LinearLayout.LayoutParams(dp(42), dp(42)))

        val brandCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brandCopy.addView(label("A1 FAMILY MEDIA", 15f, themeColor(250, 247, 255), true))
        brandCopy.addView(label("MUSIC & VIDEO PLAYER", 9f, themeColor(192, 160, 232), true).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(brandCopy, LinearLayout.LayoutParams(0, -2, 1f))
        val settingsButton = label("⚙", 22f, themeColor(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(44)
            contentDescription = "Settings"
            isClickable = true
            isFocusable = true
            setOnClickListener { openSettings() }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            rightMargin = dp(5)
        })
        header.addView(label("●  LOCAL LIBRARY", 9f, themeColor(210, 190, 232), true).apply {
            letterSpacing = 0.06f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(themeColor(39, 24, 58), 18).apply {
                setStroke(dp(1), themeColor(83, 56, 115))
            }
        })

        root.addView(header)

        root.addView(
            label(
                "YOUR SOUND. YOUR UNIVERSE.",
                12f,
                themeColor(192, 160, 232),
                true
            ).apply {
                setPadding(dp(2), dp(9), 0, dp(12))
            }
        )

        searchBox = EditText(this).apply {
            hint = "⌕   Search songs, artists and albums"
            setSingleLine(true)
            textSize = 15f
            setTextColor(themeColor(250, 247, 255))
            setHintTextColor(themeColor(171, 151, 196))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = roundedBackground(themeColor(31, 21, 47), 19).apply {
                setStroke(dp(1), themeColor(91, 66, 122))
            }
            contentDescription = "Search songs, artists and albums"
        }

        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchRow.addView(searchBox, LinearLayout.LayoutParams(0, dp(54), 1f))
        clearSearchButton = label("×", 25f, themeColor(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            visibility = View.GONE
            contentDescription = "Clear search"
            isClickable = true
            isFocusable = true
            setOnClickListener { searchBox.text?.clear() }
        }
        searchRow.addView(clearSearchButton, LinearLayout.LayoutParams(dp(48), dp(54)))
        root.addView(searchRow, LinearLayout.LayoutParams(-1, dp(54)))

        // These preferences hide navigation shortcuts only; the music library stays visible.
        val displayOptionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        searchVisibilityToggle = label("Hide search tab", 11f, themeColor(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(themeColor(42, 25, 64), 14).apply {
                setStroke(dp(1), themeColor(119, 78, 159))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Show or hide the Search tab in bottom navigation"
            // Search is intentionally permanent; do not allow this control to hide navigation.
            setOnClickListener {
                searchTabVisible = true
                getSharedPreferences("kivora_preferences", MODE_PRIVATE).edit()
                    .putBoolean("hide_search_tab", false).apply()
                navigationButtons["search"]?.visibility = View.VISIBLE
                text = "Search tab always visible"
                Toast.makeText(this@MainActivity, "The Search tab is always available.", Toast.LENGTH_SHORT).show()
            }
        }
        libraryVisibilityToggle = label("Hide library controls", 11f, themeColor(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(themeColor(42, 25, 64), 14).apply {
                setStroke(dp(1), themeColor(119, 78, 159))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Show or hide the library heading, count, playlist and favourites controls, Songs/Albums/Artists tabs, and sorting controls. The music list stays visible."
            setOnClickListener {
                libraryShortcutsVisible = !libraryShortcutsVisible
                getSharedPreferences("kivora_preferences", MODE_PRIVATE).edit()
                    .putBoolean("hide_songs_albums_shortcuts", !libraryShortcutsVisible).apply()
                // Hide the entire library control/header section, but never hide the track list.
                // This lets songs move upward and use the space freed by the controls.
                libraryHeader.visibility = if (libraryShortcutsVisible) View.VISIBLE else View.GONE
                text = if (libraryShortcutsVisible) "Hide library controls" else "Show library controls"
            }
        }
        // Keep Search available; only the library-control section is user-hideable.
        displayOptionsRow.addView(libraryVisibilityToggle, LinearLayout.LayoutParams(-1, -2))
        root.addView(displayOptionsRow, LinearLayout.LayoutParams(-1, -2))

        // Keep a live-capable Start.io banner visible on the main library screen,
        // rather than limiting banner inventory to the Now Playing card.
        val libraryAdContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            contentDescription = "Advertisement area"
        }
        val libraryStartIoBanner = Banner(this, object : BannerListener {
            override fun onReceiveAd(view: View) {
                Log.i("A1FamilyAds", "Library banner loaded")
                libraryAdContainer.visibility = View.VISIBLE
            }
            override fun onFailedToReceiveAd(view: View) {
                Log.w("A1FamilyAds", "Library banner failed to load; hiding empty ad slot")
                libraryAdContainer.visibility = View.GONE
            }
            override fun onImpression(view: View) {
                Log.i("A1FamilyAds", "Library banner impression")
            }
            override fun onClick(view: View) {
                Log.i("A1FamilyAds", "Library banner clicked")
            }
        }).apply {
            contentDescription = "Start.io banner advertisement"
        }
        libraryAdContainer.visibility = View.GONE
        libraryAdContainer.addView(
            libraryStartIoBanner,
            LinearLayout.LayoutParams(-1, -2)
        )
        root.addView(libraryAdContainer, LinearLayout.LayoutParams(-1, -2))

        libraryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(18), dp(1), dp(8))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(
            label("Your library", 24f, themeColor(250, 247, 255), true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        libraryCount = label(
            "0 tracks in your collection",
            11f,
            themeColor(211, 194, 232),
            // KIVORA_ROUND17_LIBRARY_COUNT_BADGE
        ).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); background = roundedBackground(themeColor(55, 27, 79), 14).apply { setStroke(dp(1), themeColor(192, 132, 252)) } }

        libraryCount.gravity = Gravity.CENTER
        titleRow.addView(libraryCount)
        libraryHeader.addView(titleRow)

        playlistButton = label(
            "♫   ALL SONGS  ·  PLAYLISTS",
            13f,
            themeColor(216, 180, 254),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            // KIVORA_ROUND19_PLAYLIST_BUTTON
            background = roundedBackground(themeColor(42, 25, 64), 16).apply { setStroke(dp(1), themeColor(119, 78, 159)) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showPlaylistMenu()
            }
        }

        favoriteFilterButton = label(
            "☆  SHOW FAVOURITES ONLY",
            13f,
            themeColor(216, 180, 254),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(themeColor(28, 20, 40), 16).apply { setStroke(dp(1), themeColor(74, 54, 96)) }
            isClickable = true
            isFocusable = true

            setOnClickListener {
                showFavoritesOnly = !showFavoritesOnly

                if (showFavoritesOnly) {
                    selectedPlaylist = null
                    updatePlaylistButton()
                }

                updateFavoriteFilterButton()
                renderSongs(searchBox.text?.toString().orEmpty())
            }
        }

        val collectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        playlistButton.textSize = 11f
        playlistButton.setPadding(dp(7), dp(13), dp(7), dp(13))
        favoriteFilterButton.textSize = 11f
        favoriteFilterButton.setPadding(dp(7), dp(13), dp(7), dp(13))
        collectionActions.addView(playlistButton, LinearLayout.LayoutParams(0, -2, 1f).apply {
            rightMargin = dp(5)
        })
        collectionActions.addView(favoriteFilterButton, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(5)
        })
        libraryHeader.addView(collectionActions)

        val libraryBrowseBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedBackground(themeColor(25, 17, 38), 18).apply {
                setStroke(dp(1), themeColor(76, 53, 99))
            }
        }
        listOf("songs" to "SONGS", "albums" to "ALBUMS", "artists" to "ARTISTS").forEach { (mode, title) ->
            val button = label(title, 11f, themeColor(190, 175, 210), true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = "Browse library by ${title.lowercase(Locale.ROOT)}"
                setOnClickListener {
                    libraryBrowseMode = mode
                    selectedAlbumKey = null
                    selectedArtistName = null
                    updateLibraryBrowseButtons()
                    renderSongs(searchBox.text?.toString().orEmpty())
                }
            }
            libraryBrowseButtons[mode] = button
            libraryBrowseBar.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            })
        }
        libraryHeader.addView(libraryBrowseBar, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })

        sortButton = label("↕  SORT · TITLE A–Z", 10f, themeColor(224, 205, 245), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundedBackground(themeColor(39, 26, 54), 14).apply {
                setStroke(dp(1), themeColor(89, 63, 115))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Sort library songs"
            setOnClickListener { showSortOptions() }
        }
        sortContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
            addView(sortButton)
        }
        libraryHeader.addView(sortContainer)

        root.addView(libraryHeader)

        songScroll = ScrollView(this)

        songList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        songScroll.addView(songList)

        root.addView(
            songScroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        videoPlaceholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(8))
        }

        videoPlayer = ExoPlayer.Builder(this).build()
        videoPlayer?.addListener(videoPlayerErrorListener)
        videoPlayerView = PlayerView(this).apply {
            player = videoPlayer
            useController = true
            setBackgroundColor(themeColor(8, 6, 13))
            setShutterBackgroundColor(themeColor(8, 6, 13))
            contentDescription = "Video player"
        }
        videoPlayerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        fullscreenVideoButton = label("⛶  FULLSCREEN", 12f, themeColor(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(48)
            background = roundedBackground(themeColor(48, 30, 67), 15).apply {
                setStroke(dp(1), themeColor(131, 87, 168))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Open video in fullscreen"
            setOnClickListener { showVideoFullscreen() }
        }
        videoPlayerActions?.addView(fullscreenVideoButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        videoPlaceholder.addView(videoPlayerActions, LinearLayout.LayoutParams(-1, -2))
        videoPlaceholder.addView(
            videoPlayerView,
            LinearLayout.LayoutParams(-1, dp(if (compactLayout) 112 else if (resources.configuration.screenWidthDp >= 600) 220 else 160))
        )

        videoStatus = label(
            "Open Videos to scan your device.",
            13f,
            themeColor(190, 175, 210)
        )
        videoStatus.setPadding(dp(4), dp(10), dp(4), dp(8))
        videoPlaceholder.addView(
            videoStatus,
            LinearLayout.LayoutParams(-1, -2)
        )

        val videoScroll = ScrollView(this)
        videoList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        videoScroll.addView(
            videoList,
            android.widget.FrameLayout.LayoutParams(-1, -2)
        )
        videoPlaceholder.addView(
            videoScroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        minimizeVideoButton = label("↓  MINIMIZE VIDEO", 11f, themeColor(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minHeight = dp(48)
            background = roundedBackground(themeColor(48, 30, 67), 15).apply {
                setStroke(dp(1), themeColor(131, 87, 168))
            }
            isClickable = true
            isFocusable = true
        }
        minimizeVideoButton?.setOnClickListener {
            val shouldMinimize = videoPlayerView.visibility == View.VISIBLE
            videoPlayerView.visibility = if (shouldMinimize) View.GONE else View.VISIBLE
            fullscreenVideoButton?.visibility = if (shouldMinimize) View.GONE else View.VISIBLE
            minimizeVideoButton?.let {
                (it as? TextView)?.text = if (shouldMinimize) "↑  SHOW VIDEO" else "↓  MINIMIZE VIDEO"
                it.contentDescription = if (shouldMinimize) "Restore video player" else "Minimize video player"
            }
        }
        videoPlayerActions?.addView(minimizeVideoButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })

        val videoListButton = label("▤  VIDEO LIST", 11f, themeColor(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minHeight = dp(48)
            background = roundedBackground(themeColor(48, 30, 67), 15).apply {
                setStroke(dp(1), themeColor(131, 87, 168))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Return video list to its beginning"
            setOnClickListener {
                videoScroll.smoothScrollTo(0, 0)
                videoList.requestFocus()
            }
        }
        videoPlayerActions?.addView(videoListButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })

        // Keep the video list last so its weight fills the space below the player and controls.
        videoPlaceholder.removeView(videoPlayerActions)
        videoPlaceholder.addView(videoPlayerActions, 1, LinearLayout.LayoutParams(-1, -2))
        updateVideoPlayerVisibility()
        root.addView(
            videoPlaceholder,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    themeColor(54, 30, 78),
                    themeColor(34, 23, 48),
                    themeColor(24, 18, 35)
                )
            ).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), themeColor(111, 76, 145))
            }
            elevation = dp(7).toFloat()
        }

        val playerAdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(4), dp(4))
            background = roundedBackground(themeColor(25, 17, 38), 14).apply {
                setStroke(dp(1), themeColor(79, 54, 103))
            }
        }
        val startIoBanner = Banner(this, object : BannerListener {
            override fun onReceiveAd(view: View) {
                Log.i("A1FamilyAds", "Now Playing banner loaded")
                playerAdRow.visibility = View.VISIBLE
            }
            override fun onFailedToReceiveAd(view: View) {
                Log.w("A1FamilyAds", "Now Playing banner failed to load; hiding empty ad slot")
                playerAdRow.visibility = View.GONE
            }
            override fun onImpression(view: View) {
                Log.i("A1FamilyAds", "Now Playing banner impression")
            }
            override fun onClick(view: View) {
                Log.i("A1FamilyAds", "Now Playing banner clicked")
            }
        }).apply {
            contentDescription = "Start.io banner advertisement"
        }
        playerAdRow.visibility = View.GONE
        playerAdRow.addView(startIoBanner, LinearLayout.LayoutParams(0, -2, 1f))
        val closeBanner = label("×", 23f, themeColor(217, 195, 239), true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(36)
            minHeight = dp(40)
            contentDescription = "Close banner advertisement"
            isClickable = true
            isFocusable = true
            setOnClickListener { playerAdRow.visibility = View.GONE }
        }
        playerAdRow.addView(closeBanner, LinearLayout.LayoutParams(dp(36), dp(40)))
        playerBannerContainer = playerAdRow
        playerCard.addView(playerAdRow, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })

        playerCard.addView(
            label("NOW PLAYING", 11f, themeColor(211, 176, 255), true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.16f
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )

        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(themeColor(52, 32, 75), 28).apply {
                setStroke(dp(2), themeColor(159, 105, 211))
            }
            setImageResource(R.drawable.ic_audio_placeholder)
            contentDescription = "Album artwork"
            clipToOutline = true
        }

        val artworkSize = if (compactLayout) {
            (resources.configuration.screenWidthDp - 104).coerceIn(148, 228)
        } else {
            (resources.configuration.screenWidthDp - 120).coerceIn(220, 340)
        }
        val artworkFrame = android.widget.FrameLayout(this).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    themeColor(196, 132, 255),
                    themeColor(99, 53, 151),
                    themeColor(157, 93, 226)
                )
            ).apply {
                cornerRadius = dp(36).toFloat()
                setStroke(dp(1), themeColor(219, 185, 255))
            }
            elevation = dp(12).toFloat()
            clipChildren = false
            clipToPadding = false
            addView(artwork, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        playerCard.addView(
            artworkFrame,
            LinearLayout.LayoutParams(dp(artworkSize + 10), dp(artworkSize + 10)).apply {
                gravity = Gravity.CENTER
                bottomMargin = dp(24)
            }
        )

        nowTitle = label(
            "Choose a song",
            if (resources.configuration.screenWidthDp >= 600) 32f else 28f,
            themeColor(255, 252, 255),
            true
        ).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        playerCard.addView(
            nowTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(7)
            }
        )

        nowArtist = label(
            "Your library is ready",
            if (resources.configuration.screenWidthDp >= 600) 17f else 16f,
            themeColor(211, 190, 235)
        ).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = 0.015f
        }

        playerCard.addView(
            nowArtist,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        progress = SeekBar(this).apply {
            max = 1000
            minHeight = dp(42)
            setPadding(dp(2), 0, dp(2), 0)

            progressTintList =
                android.content.res.ColorStateList.valueOf(
                    themeColor(196, 132, 255)
                )

            thumbTintList =
                android.content.res.ColorStateList.valueOf(
                    themeColor(228, 202, 255)
                )

            // KIVORA_ROUND14_PROGRESS_TRACK
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    themeColor(82, 58, 111)
                )

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onStartTrackingTouch(
                        seekBar: SeekBar
                    ) {
                        userSeeking = true
                    }

                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        value: Int,
                        fromUser: Boolean
                    ) {
                        if (fromUser) {
                            val d = player?.duration ?: 0L
                            val targetPosition = d * value / 1000L
                            elapsedText.text = formatTime(targetPosition)
                            durationText.text = "−${formatTime(d - targetPosition)}"
                        }
                    }

                    override fun onStopTrackingTouch(
                        seekBar: SeekBar
                    ) {
                        val d = player?.duration ?: 0L

                        if (d > 0L && d != C.TIME_UNSET) {
                            player?.seekTo(
                                d * seekBar.progress / 1000L
                            )
                        }

                        userSeeking = false
                    }
                }
            )
        }

        playerCard.addView(progress, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
        })

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        elapsedText = label(
            "0:00",
            12f,
            themeColor(221, 207, 238)
        )

        durationText = label(
            "−0:00",
            12f,
            themeColor(221, 207, 238)
        ).apply { contentDescription = "Time remaining" }

        times.addView(
            elapsedText,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        times.addView(durationText)
        playerCard.addView(times, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(1)
            bottomMargin = dp(4)
        })
        val sleepTimerButton = label("☾ TIMER", 10f, themeColor(226, 202, 255), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Open sleep timer options"
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(themeColor(48, 31, 68), 14).apply {
                setStroke(dp(1), themeColor(192, 132, 252))
            }
            setOnClickListener { openSleepTimer() }
        }
        upNextButton = label("UP NEXT · 0", 10f, themeColor(226, 202, 255), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "View upcoming songs"
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(themeColor(48, 31, 68), 14).apply {
                setStroke(dp(1), themeColor(192, 132, 252))
            }
            setOnClickListener { openUpNextQueue() }
        }
        val sessionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(3))
        }
        sessionActions.addView(sleepTimerButton, LinearLayout.LayoutParams(0, -2, 1f).apply {
            rightMargin = dp(5)
        })
        sessionActions.addView(upNextButton, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(5)
        })
        val lyricsButton = label("♫ LYRICS", 10f, themeColor(226, 202, 255), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Write or view lyrics for the current song"
            minHeight = dp(44)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(themeColor(48, 31, 68), 14).apply {
                setStroke(dp(1), themeColor(192, 132, 252))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openLyricsEditor() }
        }
        sessionActions.addView(lyricsButton, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(5)
        })

        val playerShortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }

        val minimizePlayerButton = label("↓  MINIMIZE PLAYER", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = roundedBackground(themeColor(48, 31, 68), 15).apply {
                setStroke(dp(1), themeColor(123, 83, 158))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showTab("search") }
        }

        val songListButton = label("♫  SONG LIST", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = roundedBackground(themeColor(48, 31, 68), 15).apply {
                setStroke(dp(1), themeColor(123, 83, 158))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showTab("library") }
        }

        val equalizerButton = label("♫  EQUALIZER", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = roundedBackground(themeColor(48, 31, 68), 15).apply {
                setStroke(dp(1), themeColor(192, 132, 252))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Open audio equalizer"
            setOnClickListener { openEqualizer() }
        }

        val bassBoostButton = label("♫  BASS BOOST", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = roundedBackground(themeColor(48, 31, 68), 15).apply {
                setStroke(dp(1), themeColor(192, 132, 252))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Open Bass Boost audio effect"
            setOnClickListener { openBassBoost() }
        }

        playerShortcuts.addView(minimizePlayerButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        playerShortcuts.addView(equalizerButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        playerShortcuts.addView(bassBoostButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        playerShortcuts.addView(songListButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }

        shuffleButton = control("⤨") {
            player?.let {
                it.shuffleModeEnabled = !it.shuffleModeEnabled
                updateShuffleButton()
            }
        }

        val previousButton = control("⏮") {
            player?.let {
                if (it.hasPreviousMediaItem()) {
                    it.seekToPreviousMediaItem()
                }
            }
        }

        playButton = control("▶") {
            val p = player

            if (p == null) {
                Toast.makeText(
                    this@MainActivity,
                    "Connecting to music service. Please wait.",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (p.mediaItemCount == 0) {
                Toast.makeText(
                    this@MainActivity,
                    "Choose a song first",
                    Toast.LENGTH_SHORT
                ).show()
            } else if (p.isPlaying) {
                p.pause()
            } else {
                continueUserPlayback(PendingPlayback("resume_music"))
            }

            updatePlayButton()
        }

        val nextButton = control("⏭") {
            player?.let {
                if (it.hasNextMediaItem()) {
                    it.seekToNextMediaItem()
                }
            }
        }

        repeatButton = control("↻") {
            player?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF ->
                        Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL ->
                        Player.REPEAT_MODE_ONE
                    else ->
                        Player.REPEAT_MODE_OFF
                }

                updateRepeatButton()
            }
        }

        fun transportParams(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }

        controls.addView(previousButton, transportParams())
        controls.addView(playButton, transportParams().apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        controls.addView(nextButton, transportParams())
        playerCard.addView(controls)

        val playbackModes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(2))
        }
        playbackModes.addView(shuffleButton, transportParams())
        playbackModes.addView(repeatButton, transportParams())
        playerCard.addView(playbackModes)
        val availableCardWidth = (resources.configuration.screenWidthDp - 76).coerceAtLeast(180)
        playerCard.addView(sessionActions, LinearLayout.LayoutParams(dp(availableCardWidth.coerceAtMost(480)), -2))
        playerCard.addView(playerShortcuts, LinearLayout.LayoutParams(dp(availableCardWidth.coerceAtMost(600)), -2))

        playerScroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(4))
            isFillViewport = false
            addView(playerCard, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(playerScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        homeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(14))
        }
        homeScroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            addView(homeContent, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(
            homeScroll,
            root.indexOfChild(playerScroll),
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        miniPlayer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(themeColor(48, 29, 69), themeColor(30, 23, 43))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), themeColor(111, 77, 143))
            }
            elevation = dp(5).toFloat()
            setOnClickListener { showTab("nowPlaying") }
        }

        miniArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_audio_placeholder)
            background = roundedBackground(themeColor(57, 37, 78), 12)
            clipToOutline = true
            contentDescription = "Current song album artwork"
            setOnClickListener { showTab("nowPlaying") }
        }
        miniPlayer.addView(miniArtwork, LinearLayout.LayoutParams(dp(44), dp(44)))

        val miniInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }

        miniTitle = label("Choose a song", 13f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showTab("nowPlaying") }
        }
        miniArtist = label("Your library is ready", 11f, themeColor(190, 175, 210)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showTab("nowPlaying") }
        }
        miniInfo.addView(miniTitle)
        miniInfo.addView(miniArtist)
        miniPlayer.addView(miniInfo, LinearLayout.LayoutParams(0, -2, 1f))

        miniPlayButton = TextView(this).apply {
            text = "▶"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(themeColor(171, 99, 255), themeColor(111, 56, 211))
            ).apply { cornerRadius = dp(24).toFloat() }
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "Play or pause playback"
            isFocusable = true
            setOnClickListener {
                val p = player
                if (p != null && p.mediaItemCount > 0) {
                    if (p.isPlaying) {
                        p.pause()
                    } else {
                        continueUserPlayback(PendingPlayback("resume_music"))
                    }
                    updatePlayButton()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Choose a song first",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        miniPlayer.addView(miniPlayButton, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(
            miniPlayer,
            LinearLayout.LayoutParams(-1, dp(62)).apply {
                bottomMargin = dp(8)
            }
        )

        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedBackground(themeColor(25, 18, 37), 21).apply { setStroke(dp(1), themeColor(70, 50, 92)) }
        }
        listOf(
            Triple("home", "⌂", "HOME"),
            Triple("search", "⌕", "SEARCH"),
            Triple("library", "♫", "LIBRARY"),
            Triple("videos", "▣", "VIDEOS")
        ).forEach { (tab, symbol, title) ->
            val item = label("$symbol\n$title", 10f, themeColor(190, 175, 210), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isClickable = true
                isFocusable = true
                contentDescription = when (tab) {
                    "home" -> "Open Home"
                    "search" -> "Search music"
                    "library" -> "Open music library and playlists"
                    else -> "Browse videos"
                }
                setOnClickListener { showTab(tab) }
            }
            navigationButtons[tab] = item
            if (tab == "search") item.visibility = if (searchTabVisible) View.VISIBLE else View.GONE
            navBar.addView(item, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        root.addView(navBar, LinearLayout.LayoutParams(-1, dp(60)))
        setContentView(root)

        searchBox.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    clearSearchButton.visibility =
                        if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    scheduleSearchRender(s?.toString().orEmpty())
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) = Unit
            }
        )

        updatePlayButton()
        updateShuffleButton()
        updateRepeatButton()
        updateFavoriteFilterButton()
        updatePlaylistButton()
        updateLibraryBrowseButtons()
        renderHomeDashboard()
        showTab("home")
    }

    private fun control(
        symbol: String,
        action: () -> Unit
    ): TextView =
        TextView(this).apply {
            val isPrimary = symbol == "▶" || symbol == "Ⅱ"
            text = symbol
            textSize = if (isPrimary) 25f else 19f
            gravity = Gravity.CENTER
            minWidth = dp(if (isPrimary) 72 else 52)
            minHeight = dp(if (isPrimary) 72 else 52)
            contentDescription = when (symbol) {
                "⤨" -> "Shuffle"
                "⏮" -> "Previous track"
                "⏭" -> "Next track"
                "↻" -> "Repeat mode"
                else -> "Playback control"
            }
            setTextColor(Color.WHITE)
            setPadding(if (isPrimary) 0 else dp(10), if (isPrimary) 0 else dp(8), if (isPrimary) 0 else dp(10), if (isPrimary) 0 else dp(8))
            background = if (isPrimary) {
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(themeColor(190, 111, 255), themeColor(112, 55, 205))
                ).apply { cornerRadius = dp(36).toFloat() }
            } else {
                roundedBackground(themeColor(43, 30, 59), 26)
            }
            // KIVORA_ROUND13_CONTROL_BORDER
            (background as android.graphics.drawable.GradientDrawable).setStroke(
                dp(if (isPrimary) 2 else 1),
                if (isPrimary) themeColor(235, 216, 255) else themeColor(126, 88, 157)
            )
            elevation = dp(if (isPrimary) 10 else 2).toFloat()
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun openEqualizer() {
        requestEqualizerState { state ->
            if (!state.getBoolean(KivoraPlaybackService.KEY_SUPPORTED)) {
                Toast.makeText(
                    this,
                    state.getString(KivoraPlaybackService.KEY_MESSAGE)
                        ?: "Equalizer is unavailable on this device.",
                    Toast.LENGTH_LONG
                ).show()
                return@requestEqualizerState
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            var equalizerSwitchReady = false
            var updateDialogState: ((Bundle) -> Unit)? = null
            var presetSpinner: android.widget.Spinner? = null
            var suppressedPresetPosition: Int? = null
            var presetSelectionInitialized = false
            val enabledSwitch = android.widget.Switch(this).apply {
                text = "Equalizer on"
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, checked ->
                    if (equalizerSwitchReady) {
                        sendEqualizerCommand(
                            KivoraPlaybackService.COMMAND_SET_ENABLED,
                            Bundle().apply { putBoolean(KivoraPlaybackService.KEY_ENABLED, checked) }
                        ) { updateDialogState?.invoke(it) }
                    }
                }
            }
            content.addView(enabledSwitch)

            val presetNames = state.getStringArray(KivoraPlaybackService.KEY_PRESETS).orEmpty()
            if (presetNames.isNotEmpty()) {
                content.addView(label("PRESET", 10f, themeColor(194, 163, 224), true).apply {
                    setPadding(0, dp(12), 0, dp(4))
                })
                presetSpinner = android.widget.Spinner(this).apply {
                    adapter = android.widget.ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        presetNames.toList()
                    )
                }
                val currentPreset = state.getInt(KivoraPlaybackService.KEY_CURRENT_PRESET, -1)
                presetSpinner?.setSelection(currentPreset.takeIf { it in presetNames.indices } ?: -1)
                presetSpinner?.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (!presetSelectionInitialized) {
                            presetSelectionInitialized = true
                            return
                        }
                        val restoredPosition = suppressedPresetPosition
                        if (restoredPosition != null) {
                            suppressedPresetPosition = null
                            if (position == restoredPosition) return
                        }
                        sendEqualizerCommand(
                            KivoraPlaybackService.COMMAND_SET_PRESET,
                            Bundle().apply { putInt(KivoraPlaybackService.KEY_PRESET, position) }
                        ) { updateDialogState?.invoke(it) }
                    }
                }
                content.addView(presetSpinner!!, LinearLayout.LayoutParams(-1, dp(48)))
            }

            val frequencies = state.getIntArray(KivoraPlaybackService.KEY_FREQUENCIES) ?: intArrayOf()
            val minLevel = state.getInt(KivoraPlaybackService.KEY_MIN_LEVEL)
            val maxLevel = state.getInt(KivoraPlaybackService.KEY_MAX_LEVEL)
            val levelLabels = mutableListOf<TextView>()
            val bandSliders = mutableListOf<SeekBar>()

            frequencies.indices.forEach { band ->
                val frequency = frequencies[band]
                val frequencyLabel = if (frequency >= 1000) {
                    String.format(Locale.getDefault(), "%.1f kHz", frequency / 1000f)
                } else "$frequency Hz"
                val valueLabel = label("", 12f, themeColor(220, 198, 241))
                levelLabels.add(valueLabel)
                content.addView(label(frequencyLabel, 13f, Color.WHITE, true).apply {
                    setPadding(0, dp(10), 0, 0)
                })
                content.addView(valueLabel)
                val slider = SeekBar(this).apply {
                    max = (maxLevel - minLevel).coerceAtLeast(1)
                    progressTintList = android.content.res.ColorStateList.valueOf(themeColor(196, 132, 255))
                    thumbTintList = android.content.res.ColorStateList.valueOf(themeColor(228, 202, 255))
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                            if (fromUser) valueLabel.text = formatEqualizerLevel(minLevel + value)
                        }
                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            sendEqualizerCommand(
                                KivoraPlaybackService.COMMAND_SET_BAND,
                                Bundle().apply {
                                    putInt(KivoraPlaybackService.KEY_BAND, band)
                                    putInt(KivoraPlaybackService.KEY_LEVEL, minLevel + seekBar.progress)
                                }
                            ) { updateDialogState?.invoke(it) }
                        }
                    })
                }
                bandSliders.add(slider)
                content.addView(slider)
            }

            val dialog = AlertDialog.Builder(this)
                .setTitle("Audio equalizer")
                .setView(ScrollView(this).apply { addView(content) })
                .setPositiveButton("Done", null)
                .create()

            fun applyState(updated: Bundle) {
                equalizerSwitchReady = false
                enabledSwitch.isChecked = updated.getBoolean(KivoraPlaybackService.KEY_ENABLED)
                equalizerSwitchReady = true
                val currentPreset = updated.getInt(KivoraPlaybackService.KEY_CURRENT_PRESET, -1)
                val selectedPreset = currentPreset.takeIf { it in presetNames.indices } ?: -1
                presetSpinner?.let { spinner ->
                    if (spinner.selectedItemPosition != selectedPreset) {
                        suppressedPresetPosition = selectedPreset
                        spinner.setSelection(selectedPreset)
                    }
                }
                val levels = updated.getIntArray(KivoraPlaybackService.KEY_LEVELS) ?: return
                levels.indices.filter { it < bandSliders.size }.forEach { band ->
                    bandSliders[band].progress = levels[band] - minLevel
                    levelLabels[band].text = formatEqualizerLevel(levels[band])
                }
            }
            updateDialogState = ::applyState
            applyState(state)
            dialog.show()
        }
    }

    private fun openBassBoost() {
        val controller = player
        if (controller == null) {
            Toast.makeText(this, "Connecting to music service. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }
        val command = SessionCommand(KivoraPlaybackService.COMMAND_BASS_BOOST_GET_STATE, Bundle.EMPTY)
        val future = controller.sendCustomCommand(command, Bundle.EMPTY)
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            val state = result?.extras
            if (result?.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS ||
                state == null || !state.getBoolean(KivoraPlaybackService.KEY_SUPPORTED)
            ) {
                Toast.makeText(
                    this,
                    state?.getString(KivoraPlaybackService.KEY_MESSAGE) ?: "Bass Boost is unavailable on this device.",
                    Toast.LENGTH_LONG
                ).show()
                return@addListener
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            var switchReady = false
            var strengthReady = false
            val enabledSwitch = android.widget.Switch(this).apply {
                text = "Bass Boost on"
                setTextColor(Color.WHITE)
            }
            content.addView(enabledSwitch)

            val minStrength = state.getInt(KivoraPlaybackService.KEY_MIN_STRENGTH, 0)
            val maxStrength = state.getInt(KivoraPlaybackService.KEY_MAX_STRENGTH, 1000)
            var strengthSlider: SeekBar? = null
            var strengthValue: TextView? = null
            if (state.getBoolean(KivoraPlaybackService.KEY_STRENGTH_SUPPORTED)) {
                content.addView(label("STRENGTH", 10f, themeColor(194, 163, 224), true).apply {
                    setPadding(0, dp(12), 0, dp(4))
                })
                strengthValue = label("", 12f, themeColor(220, 198, 241))
                content.addView(strengthValue)
                strengthSlider = SeekBar(this).apply {
                    max = (maxStrength - minStrength).coerceAtLeast(1)
                    progressTintList = android.content.res.ColorStateList.valueOf(themeColor(196, 132, 255))
                    thumbTintList = android.content.res.ColorStateList.valueOf(themeColor(228, 202, 255))
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                        override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                            if (fromUser) strengthValue?.text = "${minStrength + value} / $maxStrength"
                        }
                        override fun onStopTrackingTouch(seekBar: SeekBar) {
                            if (!strengthReady) return
                            sendAudioEffectCommand(
                                KivoraPlaybackService.COMMAND_BASS_BOOST_SET_STRENGTH,
                                Bundle().apply { putInt(KivoraPlaybackService.KEY_STRENGTH, minStrength + seekBar.progress) }
                            ) { updated ->
                                strengthReady = false
                                val value = updated.getInt(KivoraPlaybackService.KEY_STRENGTH, minStrength)
                                seekBar.progress = value - minStrength
                                strengthValue?.text = "$value / $maxStrength"
                                strengthReady = true
                            }
                        }
                    })
                }
                content.addView(strengthSlider)
            } else {
                content.addView(label("Strength adjustment is unsupported on this device.", 12f, themeColor(174, 159, 194)))
            }

            enabledSwitch.setOnCheckedChangeListener { _, checked ->
                if (!switchReady) return@setOnCheckedChangeListener
                sendAudioEffectCommand(
                    KivoraPlaybackService.COMMAND_BASS_BOOST_SET_ENABLED,
                    Bundle().apply { putBoolean(KivoraPlaybackService.KEY_ENABLED, checked) }
                ) { updated ->
                    switchReady = false
                    enabledSwitch.isChecked = updated.getBoolean(KivoraPlaybackService.KEY_ENABLED)
                    switchReady = true
                }
            }

            val dialog = AlertDialog.Builder(this)
                .setTitle("Bass Boost")
                .setView(ScrollView(this).apply { addView(content) })
                .setPositiveButton("Done", null)
                .create()
            switchReady = false
            enabledSwitch.isChecked = state.getBoolean(KivoraPlaybackService.KEY_ENABLED)
            switchReady = true
            strengthSlider?.let { slider ->
                strengthReady = false
                val strength = state.getInt(KivoraPlaybackService.KEY_STRENGTH, minStrength)
                slider.progress = (strength - minStrength).coerceIn(0, slider.max)
                strengthValue?.text = "$strength / $maxStrength"
                strengthReady = true
            }
            dialog.show()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openSleepTimer() {
        val controller = player
        if (controller == null) {
            Toast.makeText(this, "Connecting to music service. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }
        val future = controller.sendCustomCommand(
            SessionCommand(KivoraPlaybackService.COMMAND_SLEEP_TIMER_GET_STATE, Bundle.EMPTY),
            Bundle.EMPTY
        )
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            val state = result?.extras
            if (result?.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS || state == null || isFinishing || isDestroyed) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, "Sleep timer is unavailable while the music service is disconnected.", Toast.LENGTH_LONG).show()
                }
                return@addListener
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            sleepTimerStatus = label("", 14f, themeColor(226, 202, 255), true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(14))
            }
            content.addView(sleepTimerStatus)

            val optionRows = listOf(listOf(15, 30), listOf(45, 60))
            optionRows.forEach { options ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                options.forEach { minutes ->
                    val option = android.widget.Button(this).apply {
                        text = "$minutes minutes"
                        contentDescription = "Set sleep timer for $minutes minutes"
                        isAllCaps = false
                        setTextColor(Color.WHITE)
                        background = roundedBackground(themeColor(91, 52, 132), 14).apply {
                            setStroke(dp(1), themeColor(192, 132, 252))
                        }
                        setOnClickListener { setSleepTimer(minutes) }
                    }
                    row.addView(option, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        if (minutes == 15 || minutes == 45) rightMargin = dp(6) else leftMargin = dp(6)
                        bottomMargin = dp(8)
                    })
                }
                content.addView(row)
            }

            val cancelButton = android.widget.Button(this).apply {
                text = "Cancel Timer"
                contentDescription = "Cancel the active sleep timer"
                isAllCaps = false
                setTextColor(themeColor(255, 220, 235))
                background = roundedBackground(themeColor(72, 38, 63), 14).apply {
                    setStroke(dp(1), themeColor(210, 105, 160))
                }
                setOnClickListener { cancelSleepTimer() }
            }
            content.addView(cancelButton, LinearLayout.LayoutParams(-1, dp(48)))

            val dialog = AlertDialog.Builder(this)
                .setTitle("Sleep timer")
                .setView(content)
                .setPositiveButton("Done", null)
                .create()
            dialog.setOnDismissListener {
                sleepTimerPollRunnable?.let(handler::removeCallbacks)
                sleepTimerPollRunnable = null
                sleepTimerDialog = null
                sleepTimerStatus = null
            }
            sleepTimerDialog = dialog
            updateSleepTimerStatus(state)
            dialog.show()
            if (state.getBoolean(KivoraPlaybackService.KEY_TIMER_ACTIVE)) startSleepTimerPolling()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setSleepTimer(minutes: Int) {
        sendSleepTimerCommand(
            KivoraPlaybackService.COMMAND_SLEEP_TIMER_SET,
            Bundle().apply { putInt(KivoraPlaybackService.KEY_DURATION_MINUTES, minutes) }
        ) { state ->
            updateSleepTimerStatus(state)
            Toast.makeText(this, "Sleep timer set for $minutes minutes.", Toast.LENGTH_SHORT).show()
            startSleepTimerPolling()
        }
    }

    private fun cancelSleepTimer() {
        sendSleepTimerCommand(KivoraPlaybackService.COMMAND_SLEEP_TIMER_CANCEL, Bundle.EMPTY) { state ->
            updateSleepTimerStatus(state)
            stopSleepTimerPolling()
            Toast.makeText(this, "Sleep timer cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSleepTimerCommand(
        action: String,
        args: Bundle,
        onComplete: (Bundle) -> Unit
    ) {
        val controller = player
        if (controller == null) {
            Toast.makeText(this, "Music service is disconnected; timer setting wasn't applied.", Toast.LENGTH_LONG).show()
            return
        }
        val future = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (!isFinishing && !isDestroyed && result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                onComplete(result.extras)
            } else if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, "Sleep timer command failed. Check the music service connection.", Toast.LENGTH_LONG).show()
                stopSleepTimerPolling()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateSleepTimerStatus(state: Bundle) {
        if (state.getBoolean(KivoraPlaybackService.KEY_TIMER_ACTIVE)) {
            val minutes = state.getInt(KivoraPlaybackService.KEY_DURATION_MINUTES)
            val remaining = state.getLong(KivoraPlaybackService.KEY_REMAINING_MILLIS)
            sleepTimerStatus?.text = "$minutes-minute timer · ${formatTime(remaining)} remaining"
        } else if (state.getBoolean(KivoraPlaybackService.KEY_TIMER_EXPIRED)) {
            sleepTimerStatus?.text = "Timer finished · music paused"
        } else {
            sleepTimerStatus?.text = "No sleep timer is active"
        }
    }

    private fun startSleepTimerPolling() {
        stopSleepTimerPolling()
        val poll = object : Runnable {
            override fun run() {
                if (sleepTimerDialog == null || isFinishing || isDestroyed) return
                val controller = player ?: run {
                    sleepTimerStatus?.text = "Music service disconnected"
                    return
                }
                val future = controller.sendCustomCommand(
                    SessionCommand(KivoraPlaybackService.COMMAND_SLEEP_TIMER_GET_STATE, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                future.addListener({
                    val result = runCatching { future.get() }.getOrNull()
                    if (sleepTimerDialog == null || isFinishing || isDestroyed) return@addListener
                    val state = result?.extras
                    if (result?.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS || state == null) {
                        sleepTimerStatus?.text = "Music service disconnected"
                        sleepTimerPollRunnable = null
                    } else {
                        updateSleepTimerStatus(state)
                        if (state.getBoolean(KivoraPlaybackService.KEY_TIMER_ACTIVE)) {
                            handler.postDelayed(this, 1000L)
                        } else {
                            sleepTimerPollRunnable = null
                        }
                    }
                }, ContextCompat.getMainExecutor(this@MainActivity))
            }
        }
        sleepTimerPollRunnable = poll
        handler.post(poll)
    }

    private fun stopSleepTimerPolling() {
        sleepTimerPollRunnable?.let(handler::removeCallbacks)
        sleepTimerPollRunnable = null
    }

    private fun formatEqualizerLevel(millibels: Int): String =
        String.format(Locale.getDefault(), "%.1f dB", millibels / 100f)

    private fun requestEqualizerState(onReady: (Bundle) -> Unit) {
        val controller = player
        if (controller == null) {
            Toast.makeText(this, "Connecting to music service. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }
        val future = controller.sendCustomCommand(
            SessionCommand(KivoraPlaybackService.COMMAND_GET_STATE, Bundle.EMPTY),
            Bundle.EMPTY
        )
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                onReady(result.extras)
            } else {
                Toast.makeText(
                    this,
                    result?.extras?.getString(KivoraPlaybackService.KEY_MESSAGE)
                        ?: "Couldn't read equalizer settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendEqualizerCommand(
        action: String,
        args: Bundle,
        onComplete: (Bundle) -> Unit
    ) {
        val controller = player
        if (controller == null) {
            Toast.makeText(this, "Music service is not connected; equalizer setting wasn't sent.", Toast.LENGTH_LONG).show()
            return
        }
        val future = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS &&
                result.extras.getBoolean(KivoraPlaybackService.KEY_SUPPORTED, true)
            ) {
                onComplete(result.extras)
            } else {
                Toast.makeText(
                    this,
                    result?.extras?.getString(KivoraPlaybackService.KEY_MESSAGE)
                        ?: "Equalizer setting couldn't be applied.",
                    Toast.LENGTH_LONG
                ).show()
                requestEqualizerState(onComplete)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendAudioEffectCommand(
        action: String,
        args: Bundle,
        onComplete: (Bundle) -> Unit
    ) {
        val controller = player
        if (controller == null) {
            Toast.makeText(this, "Music service is not connected; audio setting wasn't sent.", Toast.LENGTH_LONG).show()
            return
        }
        val future = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS &&
                result.extras.getBoolean(KivoraPlaybackService.KEY_SUPPORTED, true)
            ) {
                onComplete(result.extras)
            } else {
                Toast.makeText(
                    this,
                    result?.extras?.getString(KivoraPlaybackService.KEY_MESSAGE)
                        ?: "Audio setting couldn't be applied.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- FAVOURITES ----------------

    private fun updateFavoriteFilterButton() {
        if (!::favoriteFilterButton.isInitialized) return

        favoriteFilterButton.text =
            if (showFavoritesOnly) {
                "★  SHOWING FAVOURITES — TAP TO SHOW ALL"
            } else {
                "☆  SHOW FAVOURITES ONLY"
            }

        favoriteFilterButton.setTextColor(
            if (showFavoritesOnly) {
                themeColor(250, 204, 21)
            } else {
                themeColor(216, 180, 254)
            }
        )

        // KIVORA_ROUND18_FAVOURITES_BORDER
        favoriteFilterButton.background = roundedBackground(
            if (showFavoritesOnly) themeColor(55, 40, 65) else themeColor(56, 34, 78),
            18
        ).apply {
            setStroke(dp(2), if (showFavoritesOnly) themeColor(250, 204, 21) else themeColor(192, 132, 252))
        }
    }

    private fun toggleFavorite(song: Song) {
        val uri = song.uri.toString()

        if (favoriteSongUris.contains(uri)) {
            favoriteSongUris.remove(uri)
            Toast.makeText(
                this,
                "Removed from favourites",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            favoriteSongUris.add(uri)
            Toast.makeText(
                this,
                "Added to favourites",
                Toast.LENGTH_SHORT
            ).show()
        }

        getSharedPreferences(
            "kivora_preferences",
            MODE_PRIVATE
        ).edit()
            .putStringSet(
                "favorite_song_uris",
                favoriteSongUris.toSet()
            )
            .apply()

        renderSongs(searchBox.text?.toString().orEmpty())
    }

    // ---------------- PLAYLIST STORAGE ----------------

    private fun loadSavedPlaylists() {
        val saved = getSharedPreferences(
            "kivora_preferences",
            MODE_PRIVATE
        ).getString("saved_playlists", "{}") ?: "{}"

        try {
            val json = JSONObject(saved)
            val keys = json.keys()

            while (keys.hasNext()) {
                val name = keys.next()
                val array = json.optJSONArray(name) ?: JSONArray()
                val uris = linkedSetOf<String>()

                for (i in 0 until array.length()) {
                    val uri = array.optString(i)
                    if (uri.isNotBlank()) uris.add(uri)
                }

                playlists[name] = uris
            }
        } catch (_: Exception) {
            playlists.clear()
        }
    }

    private fun savePlaylists() {
        val json = JSONObject()

        playlists.forEach { (name, uris) ->
            val array = JSONArray()
            uris.forEach { array.put(it) }
            json.put(name, array)
        }

        getSharedPreferences(
            "kivora_preferences",
            MODE_PRIVATE
        ).edit()
            .putString("saved_playlists", json.toString())
            .apply()
    }

    private fun playlistSongs(name: String): List<Song> {
        val songsByUri = songs.associateBy { it.uri.toString() }
        return playlists[name].orEmpty().mapNotNull { songsByUri[it] }
    }

    private fun updatePlaylistButton() {
        if (!::playlistButton.isInitialized) return

        playlistButton.text = selectedPlaylist?.let { name ->
            "♫  $name  ·  TAP TO CHANGE PLAYLIST"
        } ?: "♫  ALL SONGS  ·  PLAYLISTS"

        playlistButton.setTextColor(
            if (selectedPlaylist != null) {
                themeColor(250, 204, 21)
            } else {
                themeColor(216, 180, 254)
            }
        )
    }

    private fun updateLibraryBrowseButtons() {
        libraryBrowseButtons.forEach { (mode, button) ->
            val selected = mode == libraryBrowseMode
            button.setTextColor(
                if (selected) themeColor(250, 239, 255) else themeColor(190, 175, 210)
            )
            button.background = roundedBackground(
                if (selected) themeColor(91, 48, 132) else Color.TRANSPARENT,
                14
            ).apply {
                if (selected) setStroke(dp(1), themeColor(172, 112, 224))
            }
        }
    }

    // ---------------- PLAYLIST MENUS ----------------

    private fun showPlaylistMenu() {
        val options = mutableListOf<String>()
        options.add("All songs")
        options.add("+ Create a new playlist")
        options.add("＋ Add multiple songs")

        playlists.keys.forEach { name ->
            val count = playlistSongs(name).size
            options.add("$name ($count ${if (count == 1) "song" else "songs"})")
        }

        AlertDialog.Builder(this)
            .setTitle("Your Playlists")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        selectedPlaylist = null
                        showFavoritesOnly = false
                        selectedAlbumKey = null
                        selectedArtistName = null
                        libraryBrowseMode = "songs"
                        updateFavoriteFilterButton()
                        updatePlaylistButton()
                        updateLibraryBrowseButtons()
                        renderSongs(searchBox.text?.toString().orEmpty())
                    }

                    1 -> showCreatePlaylistDialog()

                    2 -> showSelectSongsForPlaylistDialog()

                    else -> {
                        val name = playlists.keys.elementAtOrNull(which - 3)
                        if (name != null) {
                            showPlaylistActionsDialog(name)
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPlaylistActionsDialog(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(
                arrayOf(
                    "Open playlist",
                    "Rename playlist",
                    "Delete playlist"
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        selectedPlaylist = name
                        showFavoritesOnly = false
                        selectedAlbumKey = null
                        selectedArtistName = null
                        libraryBrowseMode = "songs"
                        updateFavoriteFilterButton()
                        updatePlaylistButton()
                        updateLibraryBrowseButtons()
                        renderSongs(searchBox.text?.toString().orEmpty())
                    }

                    1 -> showRenamePlaylistDialog(name)
                    2 -> confirmDeletePlaylist(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenamePlaylistDialog(oldName: String) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(oldName)
            setSelection(text.length)
            setTextColor(Color.BLACK)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename playlist")
            .setMessage("Enter the new playlist name.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val newName = input.text.toString().trim()

                    if (newName.isBlank()) {
                        input.error = "Enter a name"
                        return@setOnClickListener
                    }

                    val duplicate = playlists.keys.any {
                        it != oldName &&
                            it.equals(newName, ignoreCase = true)
                    }

                    if (duplicate) {
                        input.error = "That playlist name already exists"
                        return@setOnClickListener
                    }

                    if (newName != oldName) {
                        val renamed = linkedMapOf<String, MutableSet<String>>()

                        playlists.forEach { (name, uris) ->
                            if (name == oldName) {
                                renamed[newName] = uris
                            } else {
                                renamed[name] = uris
                            }
                        }

                        playlists.clear()
                        playlists.putAll(renamed)

                        if (selectedPlaylist == oldName) {
                            selectedPlaylist = newName
                        }

                        savePlaylists()
                        updatePlaylistButton()
                        renderSongs(searchBox.text?.toString().orEmpty())

                        Toast.makeText(
                            this,
                            "Playlist renamed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    dialog.dismiss()
                }
        }

        dialog.show()
    }

    private fun confirmDeletePlaylist(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete playlist?")
            .setMessage(
                "Delete \"$name\" and its saved playlist list? " +
                    "Your music files and favourites will not be deleted."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                playlists.remove(name)

                if (selectedPlaylist == name) {
                    selectedPlaylist = null
                }

                savePlaylists()
                updatePlaylistButton()
                updateFavoriteFilterButton()
                renderSongs(searchBox.text?.toString().orEmpty())

                Toast.makeText(
                    this,
                    "Playlist deleted",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun showCreatePlaylistDialog(
        songToAdd: Song? = null,
        songsToAdd: List<Song> = emptyList()
    ) {
        val input = EditText(this).apply {
            hint = "e.g. Gospel, Amapiano, Love Songs"
            setSingleLine(true)
            setTextColor(Color.BLACK)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Create a playlist")
            .setMessage(
                if (songToAdd != null || songsToAdd.isNotEmpty()) {
                    "Choose a name. The selected songs will be added to it."
                } else {
                    "Give your new playlist a name."
                }
            )
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val name = input.text.toString().trim()

                    if (name.isBlank()) {
                        input.error = "Enter a playlist name"
                        return@setOnClickListener
                    }

                    if (playlists.keys.any {
                            it.equals(name, ignoreCase = true)
                        }) {
                        input.error = "That playlist already exists"
                        return@setOnClickListener
                    }

                    playlists[name] = linkedSetOf()

                    if (songToAdd != null) {
                        playlists[name]?.add(songToAdd.uri.toString())
                    }
                    songsToAdd.forEach { song ->
                        playlists[name]?.add(song.uri.toString())
                    }

                    savePlaylists()
                    selectedPlaylist = name
                    showFavoritesOnly = false
                    selectedAlbumKey = null
                    selectedArtistName = null
                    libraryBrowseMode = "songs"

                    updatePlaylistButton()
                    updateFavoriteFilterButton()
                    updateLibraryBrowseButtons()
                    renderSongs(searchBox.text?.toString().orEmpty())
                    renderHomeDashboard()

                    Toast.makeText(
                        this,
                        when {
                            songToAdd != null -> "Playlist created and song added"
                            songsToAdd.isNotEmpty() -> "Playlist created with ${songsToAdd.size} songs"
                            else -> "Playlist created"
                        },
                        Toast.LENGTH_SHORT
                    ).show()

                    dialog.dismiss()
                }
        }

        dialog.show()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        if (playlists.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Add to playlist")
                .setMessage("You haven't created a playlist yet.")
                .setPositiveButton("Create playlist") { _, _ ->
                    showCreatePlaylistDialog(song)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val options = mutableListOf("+ Create a new playlist")
        options.addAll(playlists.keys)

        AlertDialog.Builder(this)
            .setTitle("Add “${song.title}” to...")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreatePlaylistDialog(song)
                } else {
                    val name = playlists.keys.elementAtOrNull(which - 1)

                    if (name != null) {
                        val list = playlists[name]

                        if (
                            list != null &&
                            list.add(song.uri.toString())
                        ) {
                            savePlaylists()
                            Toast.makeText(
                                this,
                                "Added to $name",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this,
                                "Song is already in that playlist",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        renderSongs(searchBox.text?.toString().orEmpty())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSelectSongsForPlaylistDialog() {
        val query = searchBox.text?.toString().orEmpty().trim().lowercase(Locale.ROOT)
        val candidates = sortLibrarySongs(songs.filter { song ->
            val searchableText =
                "${song.title.trim()} ${song.artist.trim()} ${song.album.trim()}".lowercase(Locale.ROOT)
            query.isEmpty() || searchableText.contains(query)
        })
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No songs available")
                .setMessage("Add music to your library before selecting playlist tracks.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val checked = BooleanArray(candidates.size)
        val labels = candidates.map { "${it.title}  —  ${it.artist}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select songs")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Choose playlist") { _, _ ->
                val selectedSongs = candidates.filterIndexed { index, _ -> checked[index] }
                if (selectedSongs.isEmpty()) {
                    Toast.makeText(this, "Select at least one song.", Toast.LENGTH_SHORT).show()
                } else {
                    showBulkPlaylistTargetDialog(selectedSongs)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBulkPlaylistTargetDialog(selectedSongs: List<Song>) {
        val names = playlists.keys.toList()
        val options = listOf("+ Create a new playlist") + names
        AlertDialog.Builder(this)
            .setTitle("Add ${selectedSongs.size} selected songs to...")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    showCreatePlaylistDialog(songsToAdd = selectedSongs)
                    return@setItems
                }
                val name = names.getOrNull(which - 1) ?: return@setItems
                val target = playlists[name] ?: return@setItems
                val addedCount = selectedSongs.count { target.add(it.uri.toString()) }
                savePlaylists()
                updatePlaylistButton()
                renderSongs(searchBox.text?.toString().orEmpty())
                renderHomeDashboard()
                Toast.makeText(
                    this,
                    if (addedCount == 0) "All selected songs are already in $name."
                    else "Added $addedCount ${if (addedCount == 1) "song" else "songs"} to $name.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeFromPlaylist(song: Song) {
        val name = selectedPlaylist ?: return
        val list = playlists[name] ?: return

        list.remove(song.uri.toString())
        savePlaylists()

        Toast.makeText(
            this,
            "Removed from $name",
            Toast.LENGTH_SHORT
        ).show()

        renderSongs(searchBox.text?.toString().orEmpty())
    }

    // ---------------- MUSIC LIBRARY ----------------

    private fun recordRecentlyPlayed(mediaItem: MediaItem?) {
        val uri = mediaItem?.localConfiguration?.uri?.toString() ?: return
        if (songs.none { it.uri.toString() == uri }) return
        recentlyPlayedUris.remove(uri)
        recentlyPlayedUris.add(0, uri)
        while (recentlyPlayedUris.size > 12) recentlyPlayedUris.removeAt(recentlyPlayedUris.lastIndex)
        if (::homeContent.isInitialized && currentTab == "home") renderHomeDashboard()
    }

    private fun renderHomeDashboard() {
        if (!::homeContent.isInitialized) return
        val previousScrollY = if (::homeScroll.isInitialized) homeScroll.scrollY else 0
        homeContent.removeAllViews()

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(12, 15, 31), Color.rgb(42, 12, 66), Color.rgb(0, 69, 88))
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(0, 224, 245))
            }
            elevation = dp(3).toFloat()
        }
        hero.addView(label("A1 FAMILY MEDIA  •  YOUR LOCAL LIBRARY", 10f, Color.rgb(66, 244, 255), true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(8))
        })
        hero.addView(label("Feel every frequency.", 27f, Color.WHITE, true).apply {
            setPadding(0, 0, 0, dp(5))
        })
        hero.addView(label("Your library. Your rhythm. No limits.", 12f, Color.rgb(205, 224, 242)))
        homeContent.addView(hero, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })

        // Home is the first screen for most sessions, so place one clearly labelled banner
        // here as well as the existing Library and Now Playing banners.
        val homeAdContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
            contentDescription = "Start.io home banner advertisement area"
        }
        homeAdContainer.visibility = View.GONE
        homeAdContainer.addView(
            Banner(this, object : BannerListener {
                override fun onReceiveAd(view: View) {
                    Log.i("A1FamilyAds", "Home banner loaded")
                    homeAdContainer.visibility = View.VISIBLE
                }
                override fun onFailedToReceiveAd(view: View) {
                    Log.w("A1FamilyAds", "Home banner failed to load; hiding empty ad slot")
                    homeAdContainer.visibility = View.GONE
                }
                override fun onImpression(view: View) {
                    Log.i("A1FamilyAds", "Home banner impression")
                }
                override fun onClick(view: View) {
                    Log.i("A1FamilyAds", "Home banner clicked")
                }
            }).apply {
                contentDescription = "Start.io home banner advertisement"
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        homeContent.addView(homeAdContainer, LinearLayout.LayoutParams(-1, -2))

        homeContent.addView(label("QUICK ACCESS", 12f, themeColor(215, 194, 236), true).apply {
            letterSpacing = 0.08f
            setPadding(dp(2), dp(2), 0, dp(10))
        })
        val shortcuts = listOf(
            Triple("♫", "Songs", "songs"),
            Triple("▤", "Albums", "albums"),
            Triple("♙", "Artists", "artists"),
            Triple("☆", "Favourites", "favourites")
        )
        shortcuts.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (symbol, title, destination) ->
                val shortcut = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(13), dp(12), dp(10), dp(12))
                    background = roundedBackground(themeColor(38, 25, 55), 17).apply {
                        setStroke(dp(1), themeColor(91, 63, 119))
                    }
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Browse $title"
                    setOnClickListener { openHomeShortcut(destination) }
                }
                shortcut.addView(label(symbol, 21f, themeColor(213, 165, 255), true))
                shortcut.addView(label(title, 13f, themeColor(248, 242, 255), true).apply {
                    setPadding(dp(9), 0, 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(shortcut, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    if (row.childCount == 0) rightMargin = dp(6) else leftMargin = dp(6)
                    bottomMargin = dp(8)
                })
            }
            homeContent.addView(row)
        }

        val sponsoredCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(themeColor(20, 39, 70), themeColor(27, 19, 50), themeColor(16, 16, 34))
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), themeColor(40, 224, 255))
            }
        }
        sponsoredCard.addView(label("SPONSORED  •  OPTIONAL", 10f, themeColor(40, 224, 255), true).apply { letterSpacing = 0.12f })
        sponsoredCard.addView(label("Explore something new", 17f, themeColor(250, 250, 255), true).apply { setPadding(0, dp(6), 0, dp(3)) })
        sponsoredCard.addView(label("Optional offers from our advertising partner. Your music stays uninterrupted.", 12f, themeColor(185, 198, 224)).apply { setPadding(0, 0, 0, dp(10)) })
        if (ADSTERRA_SMARTLINK_URL.isBlank()) {
            sponsoredCard.addView(label(
                "Sponsored offers are coming soon.",
                12f,
                themeColor(185, 198, 224)
            ).apply {
                setPadding(0, dp(4), 0, 0)
                contentDescription = "Sponsored offers are not enabled yet"
            })
        } else {
            sponsoredCard.addView(label("Explore sponsored offers  ↗", 12f, themeColor(40, 224, 255), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(themeColor(19, 42, 68), 13).apply { setStroke(dp(1), themeColor(40, 224, 255)) }
                isClickable = true
                isFocusable = true
                setOnClickListener { openSponsoredOffers() }
            })
        }
        homeContent.addView(sponsoredCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(14) })
        sponsoredCard.tag = "adsterra_smartlink_card"
        ensureStartIoNativeAd(sponsoredCard)

        addHomeSection("Recently played", "YOUR RECENT LISTENS")
        val recentSongs = recentlyPlayedUris.mapNotNull { uri -> songs.firstOrNull { it.uri.toString() == uri } }
        if (recentSongs.isEmpty()) {
            homeContent.addView(homeEmptyCard("Tracks you play will appear here."))
        } else {
            val row = horizontalHomeRow()
            recentSongs.take(8).forEach { song ->
                row.addView(homeSongCard(song, song.title, song.artist) {
                    playSong(song)
                    showTab("nowPlaying")
                }, homeCardParams())
            }
            homeContent.addView(horizontalHomeScroller(row))
        }

        addHomeSection("Albums", "FROM YOUR LIBRARY")
        val albumGroups = songs.groupBy(::albumGroupKey).values
            .sortedBy { it.first().album.lowercase(Locale.ROOT) }
        if (albumGroups.isEmpty()) {
            homeContent.addView(homeEmptyCard("Albums will appear when music is available."))
        } else {
            val row = horizontalHomeRow()
            albumGroups.take(10).forEach { group ->
                val representative = group.firstOrNull { it.artworkUri != null } ?: group.first()
                row.addView(homeSongCard(
                    representative,
                    group.first().album,
                    "${group.first().artist} · ${group.size} ${if (group.size == 1) "song" else "songs"}"
                ) {
                    selectedPlaylist = null
                    showFavoritesOnly = false
                    libraryBrowseMode = "albums"
                    selectedAlbumKey = albumGroupKey(group.first())
                    selectedArtistName = null
                    updateFavoriteFilterButton()
                    updatePlaylistButton()
                    showTab("library")
                }, homeCardParams())
            }
            homeContent.addView(horizontalHomeScroller(row))
        }

        addHomeSection("Playlists", "YOUR COLLECTION")
        val playlistRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val availablePlaylists = playlists.entries.toList().take(8)
        if (availablePlaylists.isEmpty()) {
            homeContent.addView(homeEmptyCard("Create a playlist from the Library to see it here."))
        } else {
            availablePlaylists.forEach { (name, _) ->
                val tracks = playlistSongs(name)
                val representative = tracks.firstOrNull { it.artworkUri != null } ?: tracks.firstOrNull()
                playlistRow.addView(homePlaylistCard(name, tracks.size, representative) {
                    selectedAlbumKey = null
                    selectedArtistName = null
                    selectedPlaylist = null
                    showFavoritesOnly = false
                    libraryBrowseMode = "songs"
                    showTab("library")
                    updateLibraryBrowseButtons()
                    showPlaylistActionsDialog(name)
                }, homeCardParams())
            }
            homeContent.addView(horizontalHomeScroller(playlistRow))
        }
        homeContent.addView(label("Tap the mini-player to open Now Playing", 11f, themeColor(164, 145, 186)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(15), 0, dp(3))
        })
        homeScroll.post { homeScroll.scrollTo(0, previousScrollY) }
    }

    private fun openHomeShortcut(destination: String) {
        selectedAlbumKey = null
        selectedArtistName = null
        selectedPlaylist = null
        showFavoritesOnly = destination == "favourites"
        libraryBrowseMode = if (destination == "favourites") "songs" else destination
        showTab("library")
        updateFavoriteFilterButton()
        updatePlaylistButton()
        updateLibraryBrowseButtons()
        renderSongs(searchBox.text?.toString().orEmpty())
    }

    private fun addHomeSection(title: String, caption: String) {
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(17), dp(2), dp(9))
        }
        heading.addView(label(title, 19f, themeColor(250, 246, 255), true),
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label(caption, 9f, themeColor(179, 150, 208), true).apply {
            letterSpacing = 0.07f
            gravity = Gravity.CENTER_VERTICAL
        })
        homeContent.addView(heading)
    }

    private fun horizontalHomeRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun horizontalHomeScroller(row: LinearLayout): View =
        android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row, android.widget.FrameLayout.LayoutParams(-2, -2))
        }

    private fun homeCardParams() = LinearLayout.LayoutParams(
        dp((resources.configuration.screenWidthDp - 64).coerceIn(150, 210)), -2
    ).apply { rightMargin = dp(10) }

    private fun homeSongCard(song: Song, title: String, subtitle: String, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(11))
            background = roundedBackground(themeColor(37, 25, 53), 18).apply {
                setStroke(dp(1), themeColor(82, 58, 108))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "$title, $subtitle"
            setOnClickListener { onClick() }
        }
        val cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(themeColor(55, 34, 75), 14).apply {
                setStroke(dp(1), themeColor(131, 88, 164))
            }
            clipToOutline = true
            contentDescription = "Artwork for ${song.title}"
        }
        card.addView(cover, LinearLayout.LayoutParams(-1, dp((resources.configuration.screenWidthDp - 100).coerceIn(128, 188))))
        loadArtworkInto(cover, song)
        card.addView(label(title, 14f, themeColor(250, 246, 255), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(1), dp(9), dp(1), 0)
        })
        card.addView(label(subtitle, 11f, themeColor(187, 166, 210)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(1), dp(3), dp(1), 0)
        })
        return card
    }

    private fun homePlaylistCard(name: String, count: Int, representative: Song?, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(11))
            background = roundedBackground(themeColor(37, 25, 53), 18).apply {
                setStroke(dp(1), themeColor(82, 58, 108))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Playlist $name, $count songs"
            setOnClickListener { onClick() }
        }
        val cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(themeColor(55, 34, 75), 14).apply {
                setStroke(dp(1), themeColor(131, 88, 164))
            }
            clipToOutline = true
            contentDescription = "Playlist artwork for $name"
        }
        card.addView(cover, LinearLayout.LayoutParams(-1, dp((resources.configuration.screenWidthDp - 100).coerceIn(128, 188))))
        representative?.let { loadArtworkInto(cover, it) }
        card.addView(label(name, 14f, themeColor(250, 246, 255), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(1), dp(9), dp(1), 0)
        })
        card.addView(label("$count ${if (count == 1) "song" else "songs"}", 11f, themeColor(187, 166, 210)).apply {
            setPadding(dp(1), dp(3), dp(1), 0)
        })
        return card
    }

    private fun homeEmptyCard(message: String): View = label(message, 13f, themeColor(183, 163, 204)).apply {
        setPadding(dp(15), dp(16), dp(15), dp(16))
        background = roundedBackground(themeColor(31, 22, 44), 16).apply {
            setStroke(dp(1), themeColor(71, 51, 93))
        }
    }

    private fun loadSongs() {
        songs.clear()
        normalizedSearchText.clear()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID
        )

        try {
            val cursor: Cursor? = contentResolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media._ID
                )
                val titleColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.TITLE
                )
                val displayNameColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.DISPLAY_NAME
                )
                val artistColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ARTIST
                )
                val albumNameColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ALBUM
                )
                val albumColumn = it.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ALBUM_ID
                )

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)

                    val rawTitle = it.getString(titleColumn)?.trim().orEmpty()
                    val displayName = it.getString(displayNameColumn)?.trim().orEmpty()
                    val fileTitle = displayName
                        .substringBeforeLast('.', displayName)
                        .replace(Regex("[._-]+"), " ")
                        .trim()
                        .ifBlank { "Unknown title" }

                    val title = if (
                        rawTitle.isBlank() ||
                        rawTitle.equals("unknown", ignoreCase = true) ||
                        rawTitle.equals("unknown title", ignoreCase = true)
                    ) fileTitle else rawTitle

                    val artistValue = it.getString(artistColumn)

                    val artist = artistValue?.takeIf { value ->
                        value.isNotBlank() &&
                            !value.equals("<unknown>", ignoreCase = true) &&
                            !value.equals("unknown", ignoreCase = true)
                    } ?: "Unknown artist"

                    val albumId = it.getLong(albumColumn)
                    val rawAlbum = it.getString(albumNameColumn)?.trim().orEmpty()
                    val album = rawAlbum.takeIf { value ->
                        value.isNotBlank() &&
                            !value.equals("<unknown>", ignoreCase = true) &&
                            !value.equals("unknown", ignoreCase = true)
                    } ?: "Unknown album"

                    val artUri = if (albumId > 0L) {
                        ContentUris.withAppendedId(
                            Uri.parse(
                                "content://media/external/audio/albumart"
                            ),
                            albumId
                        )
                    } else {
                        null
                    }

                    songs.add(
                        Song(
                            title,
                            artist,
                            album,
                            albumId,
                            ContentUris.withAppendedId(collection, id),
                            artUri
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Couldn't scan music: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }

        syncPlaylistToPlayer()
        recordRecentlyPlayed(player?.currentMediaItem)
        renderHomeDashboard()
        renderSongs(searchBox.text?.toString().orEmpty())
        songsLoaded = true
        tryRunPendingPlaybackAfterAd()
    }

    private fun makeMediaItems(queue: List<Song>): List<MediaItem> =
        queue.map { song ->
            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .apply {
                    song.artworkUri?.let { setArtworkUri(it) }
                }
                .build()

            MediaItem.Builder()
                .setUri(song.uri)
                .setMediaMetadata(metadata)
                .build()
        }

    private fun syncPlaylistToPlayer() {
        val p = player ?: return

        val existingQueue = (0 until p.mediaItemCount).mapNotNull { index ->
            val uri = p.getMediaItemAt(index)
                .localConfiguration?.uri?.toString()

            uri?.let { savedUri ->
                songs.firstOrNull { it.uri.toString() == savedUri }
            }
        }

        if (p.mediaItemCount > 0) {
            activeQueue = existingQueue
        } else if (songs.isNotEmpty()) {
            activeQueue = songs.toList()
            p.setMediaItems(makeMediaItems(activeQueue))
            p.prepare()
        } else {
            activeQueue = emptyList()
        }

        updateNowPlaying()
    }

    private fun loadArtworkInto(
        imageView: ImageView,
        song: Song,
        targetSizePx: Int? = null,
        requestId: Int? = null
    ) {
        val songKey = song.uri.toString()
        imageView.tag = songKey
        val artworkUri = song.artworkUri ?: return
        val params = imageView.layoutParams
        val targetSize = targetSizePx ?: maxOf(
            imageView.width,
            imageView.height,
            params?.width?.takeIf { it > 0 } ?: 0,
            params?.height?.takeIf { it > 0 } ?: 0
        ).takeIf { it > 0 } ?: dp(192)
        val cacheKey = "$artworkUri@$targetSize"

        fun applyIfCurrent(bitmap: android.graphics.Bitmap) {
            if (
                !isFinishing && !isDestroyed && imageView.tag == songKey &&
                (requestId == null || requestId == artworkRequestId)
            ) {
                imageView.clearColorFilter()
                imageView.setImageBitmap(bitmap)
            }
        }

        artworkCache.get(cacheKey)?.let { cachedBitmap ->
            applyIfCurrent(cachedBitmap)
            return
        }

        artworkExecutor.execute {
            val bitmap = artworkCache.get(cacheKey) ?: decodeArtwork(artworkUri, targetSize)
                ?.also { artworkCache.put(cacheKey, it) }

            if (bitmap != null) {
                runOnUiThread {
                    applyIfCurrent(bitmap)
                }
            }
        }
    }

    private fun decodeArtwork(artworkUri: Uri, targetSizePx: Int): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = contentResolver.openInputStream(artworkUri) ?: return null
            boundsStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val target = targetSizePx.coerceAtLeast(dp(32))
            var sampleSize = 1
            while (maxOf(
                    bounds.outWidth / (sampleSize * 2),
                    bounds.outHeight / (sampleSize * 2)
                ) >= target
            ) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decodeStream = contentResolver.openInputStream(artworkUri) ?: return null
            decodeStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Coalesces rapid keystrokes so large local libraries are filtered and their rows
     * rebuilt only after the user pauses typing. Rendering stays on the main thread.
     */
    private fun scheduleSearchRender(query: String) {
        pendingSearchRender?.let(handler::removeCallbacks)
        val task = Runnable {
            pendingSearchRender = null
            if (isFinishing || isDestroyed) return@Runnable
            when (currentTab) {
                "videos" -> renderVideos(query)
                "search", "library" -> renderSongs(query)
            }
        }
        pendingSearchRender = task
        handler.postDelayed(task, 140L)
    }

    private fun renderSongs(query: String) {
        if (
            !::songList.isInitialized ||
            !::libraryCount.isInitialized
        ) return

        songList.removeAllViews()

        val normalized = query.trim().lowercase(Locale.ROOT)

        val matches = songs.filter { song ->
            val uri = song.uri.toString()

            val searchableText = normalizedSearchText.getOrPut(uri) {
                (song.title.trim() + " " + song.artist.trim() + " " + song.album.trim())
                    .lowercase(Locale.ROOT)
            }

            val matchesQuery =
                normalized.isEmpty() || searchableText.contains(normalized)

            val matchesFavorites =
                !showFavoritesOnly || favoriteSongUris.contains(uri)

            val matchesPlaylist =
                selectedPlaylist == null ||
                    playlists[selectedPlaylist]?.contains(uri) == true

            matchesQuery && matchesFavorites && matchesPlaylist
        }

        val isLibraryBrowse = currentTab == "library"
        val isLibraryDetail = isLibraryBrowse &&
            (selectedAlbumKey != null || selectedArtistName != null)
        val filteredSongs = matches.filter { song ->
            if (!isLibraryBrowse) return@filter true
            when {
                selectedAlbumKey != null -> albumGroupKey(song) == selectedAlbumKey
                selectedArtistName != null -> song.artist.equals(selectedArtistName, ignoreCase = true)
                else -> true
            }
        }
        val visibleSongs = if (selectedPlaylist != null) {
            val playlistOrder = playlists[selectedPlaylist].orEmpty()
                .withIndex()
                .associate { it.value to it.index }
            filteredSongs.sortedBy { playlistOrder[it.uri.toString()] ?: Int.MAX_VALUE }
        } else {
            sortLibrarySongs(filteredSongs)
        }
        val showingCollections = isLibraryBrowse &&
            selectedAlbumKey == null && selectedArtistName == null &&
            libraryBrowseMode != "songs"
        if (::sortContainer.isInitialized) {
            sortContainer.visibility =
                if (showingCollections || selectedPlaylist != null) View.GONE else View.VISIBLE
        }
        val albumCount = visibleSongs.map(::albumGroupKey).distinct().size
        val artistCount = visibleSongs.map { it.artist.lowercase(Locale.ROOT) }.distinct().size

        libraryCount.text = when {
            isLibraryBrowse && selectedAlbumKey != null -> "${visibleSongs.size} tracks · ${songs.firstOrNull { albumGroupKey(it) == selectedAlbumKey }?.album ?: "Album"}"
            isLibraryBrowse && selectedArtistName != null -> "${visibleSongs.size} tracks · $selectedArtistName"
            showingCollections && libraryBrowseMode == "albums" -> "$albumCount albums"
            showingCollections && libraryBrowseMode == "artists" -> "$artistCount artists"
            selectedPlaylist != null -> "${visibleSongs.size} in playlist"
            showFavoritesOnly -> "${visibleSongs.size} favourites"
            normalized.isEmpty() -> "${visibleSongs.size} songs"
            else -> "${visibleSongs.size} found"
        }

        if (visibleSongs.isEmpty() && !isLibraryDetail) {
            val message = when {
                songs.isEmpty() ->
                    "No music found yet.\nAdd audio files to your device and try again."

                selectedPlaylist != null ->
                    "This playlist has no available tracks.\nAdd songs from a song row or the Playlists menu."

                showFavoritesOnly ->
                    "No favourites yet.\nTap ☆ beside a song to add it."

                normalized.isNotEmpty() && showingCollections && libraryBrowseMode == "albums" ->
                    "No albums match your search. Use × to clear it."
                normalized.isNotEmpty() && showingCollections && libraryBrowseMode == "artists" ->
                    "No artists match your search. Use × to clear it."
                normalized.isNotEmpty() -> "No songs match your search. Use × to clear it."
                showingCollections && libraryBrowseMode == "albums" -> "No albums available."
                showingCollections && libraryBrowseMode == "artists" -> "No artists available."
                else -> "No songs match your filters."
            }

            songList.addView(
                label(
                    message,
                    14f,
                    themeColor(174, 159, 194)
                ).apply {
                    setPadding(dp(4), dp(18), dp(4), dp(18))
                    // KIVORA_ROUND23_EMPTY_LIBRARY_CARD
                    background = roundedBackground(themeColor(34, 16, 56), 18).apply { setStroke(dp(1), themeColor(145, 95, 190)) }
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                }
            )

            return
        }

        if (showingCollections) {
            if (libraryBrowseMode == "albums") renderAlbumGroups(visibleSongs)
            else renderArtistGroups(visibleSongs)
            return
        }

        if (isLibraryDetail) {
            val modeTitle = if (selectedAlbumKey != null) "Albums" else "Artists"
            val detailTitle = selectedAlbumKey?.let { albumKey ->
                songs.firstOrNull { albumGroupKey(it) == albumKey }?.album ?: "Album"
            } ?: selectedArtistName.orEmpty()
            val backButton = label("‹  ALL $modeTitle", 12f, themeColor(224, 197, 248), true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedBackground(themeColor(44, 29, 62), 15).apply {
                    setStroke(dp(1), themeColor(104, 73, 134))
                }
                isClickable = true
                isFocusable = true
                contentDescription = "Back to $modeTitle"
                setOnClickListener {
                    selectedAlbumKey = null
                    selectedArtistName = null
                    renderSongs(searchBox.text?.toString().orEmpty())
                }
            }
            songList.addView(backButton, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
            songList.addView(label(detailTitle, 18f, Color.WHITE, true).apply {
                setPadding(dp(4), 0, dp(4), dp(10))
                contentDescription = "${visibleSongs.size} songs in $detailTitle"
            })
        }

        if (visibleSongs.isEmpty()) {
            songList.addView(label("No songs match this selection.", 14f, themeColor(190, 175, 210)).apply {
                setPadding(dp(16), dp(18), dp(16), dp(18))
                background = roundedBackground(themeColor(34, 16, 56), 18).apply {
                    setStroke(dp(1), themeColor(115, 76, 151))
                }
            })
            return
        }

        visibleSongs.forEach { song ->
            val uri = song.uri.toString()

            val compactSongRow = resources.configuration.screenWidthDp < 420
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (compactSongRow) 8 else 12), dp(if (compactSongRow) 6 else 10), dp(8), dp(if (compactSongRow) 6 else 10))
                background = roundedBackground(themeColor(30, 21, 43), if (compactSongRow) 15 else 19).apply {
                    setStroke(dp(1), themeColor(65, 47, 84))
                }
                isClickable = true
                isFocusable = true
                contentDescription = "Play ${song.title} by ${song.artist}, from ${song.album}"
                elevation = dp(1).toFloat()
                setOnClickListener { playSong(song) }
            }

            val cover = ImageView(this).apply {
                setImageResource(R.drawable.ic_audio_placeholder)
                background = roundedBackground(themeColor(57, 37, 78), 14).apply {
                    setStroke(dp(1), themeColor(111, 75, 146))
                }
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Album artwork"
            }
            row.addView(cover, LinearLayout.LayoutParams(dp(if (compactSongRow) 46 else 58), dp(if (compactSongRow) 46 else 58)))
            loadArtworkInto(cover, song)

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(if (compactSongRow) 8 else 12), 0, dp(2), 0)
            }

            info.addView(
                label(song.title, if (compactSongRow) 13f else 15f, Color.WHITE, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )

            info.addView(
                label(
                    "${song.artist} · ${song.album}",
                    12f,
                    themeColor(190, 175, 210)
                ).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                }
            )

            row.addView(
                info,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            val isFavorite = favoriteSongUris.contains(uri)

            row.addView(
                label(
                    if (isFavorite) "★" else "☆",
                    21f,
                    if (isFavorite) {
                        themeColor(250, 204, 21)
                    } else {
                        themeColor(174, 159, 194)
                    }
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    minWidth = dp(if (compactSongRow) 36 else 44)
                    minHeight = dp(if (compactSongRow) 38 else 44)
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (isFavorite) "Remove ${song.title} from favourites" else "Add ${song.title} to favourites"
                    setOnClickListener { toggleFavorite(song) }
                }
            )

            if (selectedPlaylist == null) {
                row.addView(
                    label("+", 23f, themeColor(216, 180, 254), true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        minWidth = dp(if (compactSongRow) 32 else 44)
                        minHeight = dp(if (compactSongRow) 38 else 44)
                        isClickable = true
                        isFocusable = true
                        contentDescription = "Add ${song.title} to a playlist"
                        setOnClickListener {
                            showAddToPlaylistDialog(song)
                        }
                    }
                )
            } else {
                row.addView(
                    label("−", 23f, themeColor(248, 113, 113), true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        minWidth = dp(if (compactSongRow) 32 else 44)
                        minHeight = dp(if (compactSongRow) 38 else 44)
                        contentDescription = "Remove from playlist"
                        setOnClickListener {
                            removeFromPlaylist(song)
                        }
                    }
                )
            }

            row.addView(label("⋮", 22f, themeColor(216, 180, 254), true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(36)
                minHeight = dp(44)
                isClickable = true
                isFocusable = true
                contentDescription = "Queue actions for ${song.title}"
                setOnClickListener { showQueueActions(song) }
            })

            songList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            )
        }
    }

    private fun albumGroupKey(song: Song): String =
        if (song.albumId > 0L) {
            "id:${song.albumId}"
        } else {
            "name:${song.album.lowercase(Locale.ROOT)}|artist:${song.artist.lowercase(Locale.ROOT)}"
        }

    private fun renderAlbumGroups(items: List<Song>) {
        items.groupBy(::albumGroupKey).values
            .sortedBy { group -> group.first().album.lowercase(Locale.ROOT) }
            .forEach { group ->
                val representative = group.firstOrNull { it.artworkUri != null } ?: group.first()
                val artistNames = group.map { it.artist }.distinct().joinToString(", ")
                addLibraryCollectionRow(
                    title = group.first().album,
                    subtitle = "$artistNames · ${group.size} ${if (group.size == 1) "song" else "songs"}",
                    coverSong = representative,
                    description = "Open album ${group.first().album}, $artistNames, ${group.size} songs"
                ) {
                    selectedAlbumKey = albumGroupKey(group.first())
                    selectedArtistName = null
                    renderSongs(searchBox.text?.toString().orEmpty())
                }
            }
    }

    private fun renderArtistGroups(items: List<Song>) {
        items.groupBy { it.artist.lowercase(Locale.ROOT) }.values
            .sortedBy { group -> group.first().artist.lowercase(Locale.ROOT) }
            .forEach { group ->
                val artistName = group.first().artist
                val representative = group.firstOrNull { it.artworkUri != null } ?: group.first()
                addLibraryCollectionRow(
                    title = artistName,
                    subtitle = "${group.size} ${if (group.size == 1) "song" else "songs"}",
                    coverSong = representative,
                    description = "Open artist $artistName, ${group.size} songs"
                ) {
                    selectedAlbumKey = null
                    selectedArtistName = artistName
                    renderSongs(searchBox.text?.toString().orEmpty())
                }
            }
    }

    private fun addLibraryCollectionRow(
        title: String,
        subtitle: String,
        coverSong: Song,
        description: String,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(14), dp(11))
            background = roundedBackground(themeColor(34, 23, 49), 19).apply {
                setStroke(dp(1), themeColor(90, 62, 117))
            }
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }
        val cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_placeholder)
            background = roundedBackground(themeColor(57, 37, 78), 14).apply {
                setStroke(dp(1), themeColor(145, 96, 190))
            }
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "$title artwork"
        }
        row.addView(cover, LinearLayout.LayoutParams(dp(68), dp(68)))
        loadArtworkInto(cover, coverSong)

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        details.addView(label(title, 16f, themeColor(251, 247, 255), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        details.addView(label(subtitle, 12f, themeColor(194, 174, 216)).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("›", 28f, themeColor(190, 145, 224), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Open $title"
        })
        songList.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(9)
        })
    }

    private fun playSong(song: Song) {
        val p = player

        val queueCandidates = when {
            selectedPlaylist != null -> playlistSongs(selectedPlaylist.orEmpty())
            showFavoritesOnly -> songs.filter { favoriteSongUris.contains(it.uri.toString()) }
            else -> songs.toList()
        }
        val queue = queueCandidates.filter { queuedSong ->
            (currentTab != "library" || selectedAlbumKey == null ||
                albumGroupKey(queuedSong) == selectedAlbumKey) &&
                (currentTab != "library" || selectedArtistName == null ||
                    queuedSong.artist.equals(selectedArtistName, ignoreCase = true))
        }

        val startIndex = queue.indexOfFirst { it.uri == song.uri }

        if (queue.isEmpty() || startIndex < 0) {
            Toast.makeText(
                this,
                "This song is not in the selected list.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        activeQueue = queue

        val request = PendingPlayback(
            kind = "play_queue",
            queue = ArrayList(activeQueue.map { it.uri.toString() }),
            index = startIndex
        )
        if (p == null) {
            pendingPlaybackAfterAd = request
            StartIoVideoAds.savePendingPlayback(request)
            Toast.makeText(
                this,
                "Connecting to music service. Please wait.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        continueUserPlayback(request)
    }

    private fun showQueueActions(song: Song) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(song.title)
            .setView(content)
            .setNegativeButton("Close", null)
            .create()
        fun actionButton(text: String, description: String, action: () -> Unit) =
            android.widget.Button(this).apply {
                this.text = text
                contentDescription = description
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = roundedBackground(themeColor(72, 42, 101), 14).apply {
                    setStroke(dp(1), themeColor(167, 112, 220))
                }
                setOnClickListener {
                    action()
                    dialog.dismiss()
                }
            }

        content.addView(actionButton("Add to end of queue", "Add ${song.title} to the end of the queue") {
            enqueueSong(song)
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        content.addView(actionButton("Play next", "Play ${song.title} after the current song") {
            playSongNext(song)
        }, LinearLayout.LayoutParams(-1, dp(48)))

        dialog.show()
    }

    private fun enqueueSong(song: Song) {
        if (player == null) return showQueueUnavailable()
        if (!isAvailableLibrarySong(song)) return showUnavailableSong()
        sendQueueSongCommand(song, KivoraPlaybackService.COMMAND_QUEUE_ADD_END, "Added to the end of Up Next.")
    }

    private fun playSongNext(song: Song) {
        if (player == null) return showQueueUnavailable()
        if (!isAvailableLibrarySong(song)) return showUnavailableSong()
        sendQueueSongCommand(song, KivoraPlaybackService.COMMAND_QUEUE_PLAY_NEXT, "Added to play next.")
    }

    private fun sendQueueSongCommand(song: Song, command: String, successMessage: String) {
        val controller = player ?: return showQueueUnavailable()
        val args = Bundle().apply {
            putString(KivoraPlaybackService.KEY_QUEUE_URI, song.uri.toString())
            putString(KivoraPlaybackService.KEY_QUEUE_TITLE, song.title)
            putString(KivoraPlaybackService.KEY_QUEUE_ARTIST, song.artist)
            putString(KivoraPlaybackService.KEY_QUEUE_ALBUM, song.album)
            putString(KivoraPlaybackService.KEY_QUEUE_ARTWORK_URI, song.artworkUri?.toString())
        }
        val future = controller.sendCustomCommand(
            SessionCommand(command, Bundle.EMPTY),
            args
        )
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (isFinishing || isDestroyed) return@addListener
            if (result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS &&
                result.extras.getBoolean(KivoraPlaybackService.KEY_SUPPORTED)
            ) {
                Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
                refreshActiveQueue()
            } else {
                showQueueUnavailable()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun isAvailableLibrarySong(song: Song): Boolean =
        songs.any { it.uri == song.uri } && song.uri.scheme in setOf("content", "file")

    private fun showUnavailableSong() {
        Toast.makeText(this, "This song is no longer available in your library.", Toast.LENGTH_LONG).show()
    }

    private fun showQueueUnavailable() {
        Toast.makeText(this, "The music queue is unavailable right now.", Toast.LENGTH_LONG).show()
    }

    private fun openUpNextQueue() {
        if (player == null) {
            showQueueUnavailable()
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        val summary = label("", 13f, themeColor(205, 177, 231), true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(8), dp(12))
        }
        upNextSummary = summary
        val clearButton = label("Clear upcoming", 12f, themeColor(255, 166, 197), true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(8), 0, 0, dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "Clear upcoming tracks"
            setOnClickListener { confirmClearUpcomingItems() }
        }
        upNextClearButton = clearButton
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clearButton, LinearLayout.LayoutParams(-2, -2))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        upNextList = list
        content.addView(ScrollView(this).apply {
            isFillViewport = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, dp(360)))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Up next")
            .setView(content)
            .setPositiveButton("Done", null)
            .create()
        dialog.setOnDismissListener {
            upNextDialog = null
            upNextList = null
            upNextSummary = null
            upNextClearButton = null
        }
        upNextDialog = dialog
        dialog.show()
        requestQueueOrder()
        renderUpNextQueue()
    }

    private fun renderUpNextQueue() {
        val p = player ?: return
        val list = upNextList
        val entries = upcomingQueueEntries()
        updateUpNextButton(entries.size)
        val currentIndex = p.currentMediaItemIndex
        val canClear = currentIndex in 0 until p.mediaItemCount &&
            currentIndex < p.mediaItemCount - 1 &&
            p.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)
        val queueSummary = when (entries.size) {
            0 -> "Nothing queued after the current song"
            1 -> "1 upcoming song"
            else -> "${entries.size} upcoming songs"
        }
        upNextSummary?.text = when {
            p.shuffleModeEnabled -> "$queueSummary · reorder unavailable in shuffle"
            p.repeatMode == Player.REPEAT_MODE_ONE -> "$queueSummary · reorder unavailable in repeat one"
            p.repeatMode == Player.REPEAT_MODE_ALL -> "$queueSummary · reorder stays within current-track boundary"
            else -> queueSummary
        }
        upNextClearButton?.apply {
            isEnabled = canClear
            alpha = if (canClear) 1f else 0.45f
        }
        if (list == null) return
        list.removeAllViews()

        if (currentIndex in 0 until p.mediaItemCount) {
            val currentItem = p.getMediaItemAt(currentIndex)
            val metadata = currentItem.mediaMetadata
            val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: currentItem.localConfiguration?.uri?.lastPathSegment
                ?: "Unavailable track"
            val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Local audio"
            val currentSong = songForMediaItem(currentItem)
            val currentRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(themeColor(62, 38, 83), 17).apply {
                    setStroke(dp(2), themeColor(177, 117, 231))
                }
                contentDescription = "Currently playing: $title"
            }
            val cover = ImageView(this).apply {
                setImageResource(R.drawable.ic_audio_placeholder)
                background = roundedBackground(themeColor(57, 37, 78), 11)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "$title album artwork"
            }
            currentRow.addView(cover, LinearLayout.LayoutParams(dp(46), dp(46)))
            currentSong?.let { loadArtworkInto(cover, it) }
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            copy.addView(label("NOW PLAYING", 10f, themeColor(213, 171, 255), true))
            copy.addView(label(title, 14f, Color.WHITE, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            copy.addView(label(artist, 11f, themeColor(205, 187, 219)).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            currentRow.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            currentRow.addView(label("♫", 20f, themeColor(213, 171, 255), true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(34)
                contentDescription = "Current track"
            })
            list.addView(currentRow, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }

        if (entries.isEmpty()) {
            list.addView(label(
                if (p.mediaItemCount == 0) "Your queue is empty. Add songs from the library to start listening." else "No upcoming songs. Add a track from your library to keep the music going.",
                14f,
                themeColor(190, 169, 211)
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(22), dp(14), dp(22))
                background = roundedBackground(themeColor(35, 23, 49), 18).apply {
                    setStroke(dp(1), themeColor(108, 73, 139))
                }
            })
            return
        }

        entries.forEachIndexed { queuePosition, (mediaIndex, mediaItem) ->
            val metadata = mediaItem.mediaMetadata
            val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: mediaItem.localConfiguration?.uri?.lastPathSegment
                ?: "Unavailable track"
            val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
                ?: "Local audio"
            val song = songForMediaItem(mediaItem)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = roundedBackground(themeColor(35, 24, 48), 16).apply {
                    setStroke(dp(1), themeColor(77, 54, 99))
                }
                isClickable = song != null
                isFocusable = song != null
                contentDescription = if (song != null) "Play $title next" else "$title is unavailable"
                setOnClickListener {
                    if (song == null) showUnavailableSong() else playQueuedItem(mediaIndex)
                }
            }
            row.addView(label("${queuePosition + 1}", 12f, themeColor(196, 153, 236), true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(28)
            })
            val cover = ImageView(this).apply {
                setImageResource(R.drawable.ic_audio_placeholder)
                background = roundedBackground(themeColor(57, 37, 78), 11).apply {
                    setStroke(dp(1), themeColor(111, 75, 146))
                }
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "$title album artwork"
            }
            row.addView(cover, LinearLayout.LayoutParams(dp(42), dp(42)))
            song?.let { loadArtworkInto(cover, it) }
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, dp(4), 0)
            }
            copy.addView(label(title, 14f, Color.WHITE, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            copy.addView(label(artist, 11f, themeColor(187, 168, 207)).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            val canReorder = !p.shuffleModeEnabled &&
                p.repeatMode != Player.REPEAT_MODE_ONE &&
                p.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            controls.addView(queueMoveButton("↑", "Move $title up", canReorder &&
                canMoveQueueEntry(entries, queuePosition, -1, p.currentMediaItemIndex)) {
                moveQueuedItem(mediaIndex, -1)
            })
            controls.addView(queueMoveButton("↓", "Move $title down", canReorder &&
                canMoveQueueEntry(entries, queuePosition, 1, p.currentMediaItemIndex)) {
                moveQueuedItem(mediaIndex, 1)
            })
            row.addView(controls)
            row.addView(label("×", 24f, themeColor(255, 166, 197), true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(44)
                minHeight = dp(44)
                isClickable = true
                isFocusable = true
                contentDescription = "Remove $title from queue"
                setOnClickListener { removeQueuedItem(mediaIndex) }
            })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(7)
            })
        }
    }

    private fun upcomingQueueEntries(): List<Pair<Int, MediaItem>> {
        val p = player ?: return emptyList()
        if (!p.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return emptyList()
        val count = p.mediaItemCount
        if (count == 0) return emptyList()
        val currentIndex = p.currentMediaItemIndex
        val cachedOrder = queueOrderIndices?.takeIf {
            queueOrderItemCount == count &&
                queueOrderCurrentIndex == currentIndex &&
                queueOrderShuffleEnabled == p.shuffleModeEnabled &&
                queueOrderRepeatMode == p.repeatMode
        }
        if (cachedOrder != null) {
            return cachedOrder.filter { it in 0 until count && it != currentIndex }
                .map { it to p.getMediaItemAt(it) }
        }
        if (currentIndex !in 0 until count) {
            return (0 until count).map { it to p.getMediaItemAt(it) }
        }
        val order = mutableListOf<Int>()
        if (p.shuffleModeEnabled) {
            val nextIndex = p.nextMediaItemIndex
            if (nextIndex in 0 until count && nextIndex != currentIndex) order.add(nextIndex)
            for (index in 0 until count) {
                if (index != currentIndex && index !in order) order.add(index)
            }
        } else {
            for (step in 1 until count) {
                val index = (currentIndex + step) % count
                if (p.repeatMode != Player.REPEAT_MODE_ALL && index < currentIndex) break
                order.add(index)
            }
        }
        return order.map { it to p.getMediaItemAt(it) }
    }

    private fun playQueuedItem(mediaIndex: Int) {
        val p = player ?: run {
            val request = PendingPlayback(
                kind = "play_queue_item",
                index = mediaIndex
            )
            pendingPlaybackAfterAd = request
            StartIoVideoAds.savePendingPlayback(request)
            showQueueUnavailable()
            return
        }
        if (mediaIndex !in 0 until p.mediaItemCount) {
            renderUpNextQueue()
            return
        }
        val item = p.getMediaItemAt(mediaIndex)
        if (songForMediaItem(item) == null) return showUnavailableSong()
        try {
            continueUserPlayback(
                PendingPlayback(
                    kind = "play_queue_item",
                    uri = item.localConfiguration?.uri?.toString()
                )
            )
        } catch (_: Exception) {
            showUnavailableSong()
        }
    }

    private fun removeQueuedItem(mediaIndex: Int) {
        val p = player ?: return showQueueUnavailable()
        if (mediaIndex !in 0 until p.mediaItemCount || mediaIndex == p.currentMediaItemIndex) {
            renderUpNextQueue()
            return
        }
        if (!p.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return showQueueUnavailable()
        try {
            p.removeMediaItem(mediaIndex)
            renderUpNextQueue()
        } catch (_: Exception) {
            showQueueUnavailable()
        }
    }

    private fun queueMoveButton(
        text: String,
        description: String,
        enabled: Boolean,
        onClick: () -> Unit
    ): TextView = label(text, 18f, themeColor(213, 171, 255), true).apply {
        gravity = Gravity.CENTER
        minWidth = dp(38)
        minHeight = dp(30)
        isEnabled = enabled
        isClickable = enabled
        isFocusable = true
        alpha = if (enabled) 1f else 0.35f
        contentDescription = description
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun canMoveQueueEntry(
        entries: List<Pair<Int, MediaItem>>,
        position: Int,
        direction: Int,
        currentIndex: Int
    ): Boolean {
        val targetPosition = position + direction
        if (position !in entries.indices || targetPosition !in entries.indices) return false
        val fromIndex = entries[position].first
        val toIndex = entries[targetPosition].first
        return fromIndex != currentIndex && toIndex != currentIndex &&
            kotlin.math.abs(fromIndex - toIndex) == 1
    }

    private fun moveQueuedItem(mediaIndex: Int, direction: Int) {
        val p = player ?: return showQueueUnavailable()
        val entries = upcomingQueueEntries()
        val position = entries.indexOfFirst { it.first == mediaIndex }
        if (position < 0 || p.shuffleModeEnabled || p.repeatMode == Player.REPEAT_MODE_ONE ||
            !p.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
            !canMoveQueueEntry(entries, position, direction, p.currentMediaItemIndex)
        ) {
            renderUpNextQueue()
            return
        }
        val targetIndex = entries[position + direction].first
        try {
            p.moveMediaItem(mediaIndex, targetIndex)
            queueOrderIndices = null
            requestQueueOrder()
            renderUpNextQueue()
        } catch (_: Exception) {
            showQueueUnavailable()
        }
    }

    private fun clearUpcomingItems() {
        val p = player ?: return showQueueUnavailable()
        val itemCount = p.mediaItemCount
        val currentIndex = p.currentMediaItemIndex
        if (itemCount == 0 || currentIndex !in 0 until itemCount || currentIndex >= itemCount - 1) {
            renderUpNextQueue()
            return
        }
        if (!p.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return showQueueUnavailable()
        val currentItem = p.getMediaItemAt(currentIndex)
        try {
            p.removeMediaItems(currentIndex + 1, itemCount)
            if (p.currentMediaItem != currentItem) {
                showQueueUnavailable()
            }
            queueOrderIndices = null
            requestQueueOrder()
            renderUpNextQueue()
        } catch (_: Exception) {
            showQueueUnavailable()
        }
    }

    private fun confirmClearUpcomingItems() {
        AlertDialog.Builder(this)
            .setTitle("Clear upcoming tracks?")
            .setMessage("The current song will keep playing. Previous playlist tracks will stay in the queue.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> clearUpcomingItems() }
            .show()
    }

    private fun songForMediaItem(mediaItem: MediaItem): Song? {
        val uri = mediaItem.localConfiguration?.uri ?: return null
        return songs.firstOrNull { it.uri == uri }
            ?.takeIf { isAvailableLibrarySong(it) }
    }

    private fun refreshActiveQueue() {
        val p = player ?: return
        if (!p.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return
        activeQueue = (0 until p.mediaItemCount).mapNotNull { index ->
            songForMediaItem(p.getMediaItemAt(index))
        }
        requestQueueOrder()
        renderUpNextQueue()
    }

    private fun requestQueueOrder() {
        val controller = player ?: return
        if (!controller.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return
        val requestId = ++queueOrderRequestId
        queueOrderIndices = null
        val itemCount = controller.mediaItemCount
        val currentIndex = controller.currentMediaItemIndex
        val shuffleEnabled = controller.shuffleModeEnabled
        val repeatMode = controller.repeatMode
        val future = controller.sendCustomCommand(
            SessionCommand(KivoraPlaybackService.COMMAND_QUEUE_GET_ORDER, Bundle.EMPTY),
            Bundle.EMPTY
        )
        future.addListener({
            val result = runCatching { future.get() }.getOrNull()
            if (requestId != queueOrderRequestId || isFinishing || isDestroyed) return@addListener
            val indices = result?.extras?.getIntArray(KivoraPlaybackService.KEY_QUEUE_INDICES)
            if (result?.resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS && indices != null) {
                queueOrderIndices = indices
                queueOrderItemCount = itemCount
                queueOrderCurrentIndex = currentIndex
                queueOrderShuffleEnabled = shuffleEnabled
                queueOrderRepeatMode = repeatMode
                renderUpNextQueue()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateUpNextButton(count: Int? = null) {
        val button = upNextButton ?: return
        val upcomingCount = count ?: upcomingQueueEntries().size
        button.text = "UP NEXT · $upcomingCount"
        button.contentDescription = "View $upcomingCount upcoming songs"
    }

    private fun updateArtworkMotion(isPlaying: Boolean) {
        if (!::artwork.isInitialized) return
        if (isPlaying) {
            if (artworkMotionAnimator?.isStarted == true) return
            artworkMotionAnimator?.cancel()
            val x = android.animation.ObjectAnimator.ofFloat(artwork, View.SCALE_X, 1f, 1.025f).apply {
                duration = 1_150L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            }
            val y = android.animation.ObjectAnimator.ofFloat(artwork, View.SCALE_Y, 1f, 1.025f).apply {
                duration = 1_150L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
            }
            artworkMotionAnimator = android.animation.AnimatorSet().apply {
                playTogether(x, y)
                start()
            }
        } else {
            artworkMotionAnimator?.cancel()
            artworkMotionAnimator = null
            artwork.scaleX = 1f
            artwork.scaleY = 1f
        }
    }

    private fun updateNowPlaying() {
        if (!::nowTitle.isInitialized) return

        // Increment for every track change, including when no song is selected.
        // This prevents a slow artwork request from replacing newer artwork.
        val requestId = ++artworkRequestId

        val p = player
        val currentUri = p?.currentMediaItem?.localConfiguration?.uri

        val song = if (currentUri != null) {
            activeQueue.firstOrNull { it.uri == currentUri }
                ?: songs.firstOrNull { it.uri == currentUri }
        } else {
            null
        }

        if (song == null) {
            nowTitle.text = "Choose a song"
            if (::miniTitle.isInitialized) miniTitle.text = nowTitle.text
            nowArtist.text = "Your library is ready"
            if (::miniArtist.isInitialized) miniArtist.text = nowArtist.text
            artwork.setImageResource(R.drawable.ic_audio_placeholder)
            artwork.tag = null
            if (::miniArtwork.isInitialized) miniArtwork.setImageResource(R.drawable.ic_audio_placeholder)
            if (::miniArtwork.isInitialized) miniArtwork.clearColorFilter()
            if (::miniArtwork.isInitialized) miniArtwork.tag = null
            updateProgress()
            return
        }

        nowTitle.text = song.title
        nowArtist.text = song.artist
        if (::miniTitle.isInitialized) miniTitle.text = song.title
        if (::miniArtist.isInitialized) miniArtist.text = song.artist
        artwork.setImageResource(R.drawable.ic_audio_placeholder)
        if (::miniArtwork.isInitialized) miniArtwork.setImageResource(R.drawable.ic_audio_placeholder)
        if (::miniArtwork.isInitialized) miniArtwork.clearColorFilter()
        if (::miniArtwork.isInitialized) loadArtworkInto(miniArtwork, song)
        loadArtworkInto(
            artwork,
            song,
            targetSizePx = dp((resources.configuration.screenWidthDp - 86).coerceIn(160, 460)),
            requestId = requestId
        )

        updateProgress()
    }

    private fun sortLibrarySongs(items: List<Song>): List<Song> = when (librarySortMode) {
        "artist" -> items.sortedWith(
            compareBy<Song> { it.artist.trim().lowercase(Locale.ROOT) }
                .thenBy { it.title.trim().lowercase(Locale.ROOT) }
        )
        "recent" -> items.sortedWith(
            compareBy<Song> {
                recentlyPlayedUris.indexOf(it.uri.toString()).let { index ->
                    if (index < 0) Int.MAX_VALUE else index
                }
            }.thenBy { it.title.trim().lowercase(Locale.ROOT) }
        )
        else -> items.sortedWith(
            compareBy<Song> { it.title.trim().lowercase(Locale.ROOT) }
                .thenBy { it.artist.trim().lowercase(Locale.ROOT) }
        )
    }

    private fun updateSortButton() {
        if (!::sortButton.isInitialized) return
        val label = when (librarySortMode) {
            "artist" -> "ARTIST A–Z"
            "recent" -> "RECENTLY PLAYED"
            else -> "TITLE A–Z"
        }
        sortButton.text = "↕  SORT · $label"
        sortButton.contentDescription = "Sort library by $label"
    }

    private fun showSortOptions() {
        val modes = mutableListOf("title", "artist")
        val labels = mutableListOf("Title A–Z", "Artist A–Z")
        if (recentlyPlayedUris.isNotEmpty()) {
            modes.add("recent")
            labels.add("Recently played")
        }
        val selected = modes.indexOf(librarySortMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Sort songs")
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                librarySortMode = modes[which]
                updateSortButton()
                renderSongs(searchBox.text?.toString().orEmpty())
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePlayButton() {
        if (!::playButton.isInitialized) return

        playButton.text = if (player?.isPlaying == true) "Ⅱ" else "▶"
        playButton.contentDescription = if (player?.isPlaying == true) "Pause" else "Play"
        if (::miniPlayButton.isInitialized) {
            miniPlayButton.text = if (player?.isPlaying == true) "Ⅱ" else "▶"
            miniPlayButton.contentDescription = if (player?.isPlaying == true) "Pause playback" else "Start playback"
        }
        playButton.setTextColor(Color.WHITE)
    }

    private fun updateShuffleButton() {
        if (!::shuffleButton.isInitialized) return

        val enabled = player?.shuffleModeEnabled == true
        shuffleButton.isSelected = enabled
        shuffleButton.contentDescription = if (enabled) "Shuffle on" else "Shuffle off"
        shuffleButton.setTextColor(if (enabled) Color.WHITE else themeColor(174, 159, 194))
        shuffleButton.background = roundedBackground(
            if (enabled) themeColor(106, 52, 175) else themeColor(43, 30, 59), 26
        ).apply {
            setStroke(dp(if (enabled) 2 else 1), if (enabled) themeColor(220, 184, 255) else themeColor(126, 88, 157))
        }
        shuffleButton.elevation = dp(if (enabled) 7 else 2).toFloat()
    }

    private fun updateRepeatButton() {
        if (!::repeatButton.isInitialized) return

        repeatButton.text =
            when (player?.repeatMode ?: Player.REPEAT_MODE_OFF) {
                Player.REPEAT_MODE_ONE -> "↻¹"
                else -> "↻"
            }

        val enabled =
            (player?.repeatMode ?: Player.REPEAT_MODE_OFF) !=
                Player.REPEAT_MODE_OFF

        repeatButton.isSelected = enabled
        repeatButton.contentDescription = when (player?.repeatMode ?: Player.REPEAT_MODE_OFF) {
            Player.REPEAT_MODE_ONE -> "Repeat current song"
            Player.REPEAT_MODE_ALL -> "Repeat all songs"
            else -> "Repeat off"
        }
        repeatButton.setTextColor(if (enabled) Color.WHITE else themeColor(174, 159, 194))
        repeatButton.background = roundedBackground(
            if (enabled) themeColor(106, 52, 175) else themeColor(43, 30, 59), 26
        ).apply {
            setStroke(dp(if (enabled) 2 else 1), if (enabled) themeColor(220, 184, 255) else themeColor(126, 88, 157))
        }
        repeatButton.elevation = dp(if (enabled) 7 else 2).toFloat()
    }

    private fun updateProgress() {
        if (!::progress.isInitialized) return

        val p = player ?: return
        val rawDuration = p.duration

        val duration =
            if (rawDuration > 0L && rawDuration != C.TIME_UNSET) {
                rawDuration
            } else {
                0L
            }

        val position = p.currentPosition.coerceAtLeast(0L)

        if (!userSeeking) {
            progress.progress =
                if (duration > 0L) {
                    ((position * 1000L) / duration).toInt()
                } else {
                    0
                }

            elapsedText.text = formatTime(position)
        }

        durationText.text = "−${formatTime(duration - position)}"
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds =
            (milliseconds.coerceAtLeast(0L) / 1000L).toInt()

        return String.format(
            Locale.getDefault(),
            "%d:%02d",
            totalSeconds / 60,
            totalSeconds % 60
        )
    }

    override fun onDestroy() {
        pendingPlaybackAfterAd = null
        musicDispatchRetry?.let(handler::removeCallbacks)
        musicDispatchRetry = null
        StartIoVideoAds.detach(this)
        controllerRetryRunnable?.let(handler::removeCallbacks)
        controllerRetryRunnable = null
        handler.removeCallbacks(progressUpdater)
        pendingSearchRender?.let(handler::removeCallbacks)
        pendingSearchRender = null
        stopSleepTimerPolling()
        sleepTimerDialog?.dismiss()
        sleepTimerDialog = null
        sleepTimerStatus = null
        upNextDialog?.dismiss()
        upNextDialog = null
        upNextList = null
        upNextSummary = null

        fullscreenVideoDialog?.dismiss()
        fullscreenVideoDialog = null
        restoreVideoPlayerView()

        // Invalidate any pending artwork callback and stop its worker.
        artworkRequestId++
        artworkMotionAnimator?.cancel()
        artworkMotionAnimator = null
        artworkExecutor.shutdownNow()

        player?.removeListener(playerListener)
        player = null

        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }

        controllerFuture = null

        if (::videoPlayerView.isInitialized) {
            videoPlayerView.player = null
        }
        videoPlayer?.removeListener(videoPlayerErrorListener)
        videoPlayer?.release()
        videoPlayer = null

        super.onDestroy()
    }
    private fun loadVideos() {
        videoStatus.text = "Scanning videos on this device..."
        videoStatus.setOnClickListener(null)
        deviceVideos.clear()
        videoList.removeAllViews()

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )

        try {
            contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() }
                        ?: cursor.getString(nameColumn)?.takeIf { it.isNotBlank() }
                        ?: "Untitled video"

                    deviceVideos.add(
                        DeviceVideo(
                            id,
                            title,
                            ContentUris.withAppendedId(collection, id),
                            cursor.getLong(durationColumn)
                        )
                    )
                }
            }
            videosLoaded = true
            renderVideos()
        } catch (error: Exception) {
            videoStatus.text = "Couldn't read videos. Tap here to retry."
            videoStatus.setOnClickListener { loadVideos() }
            Toast.makeText(this, "Video library error", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderVideos(query: String = searchBox.text?.toString().orEmpty()) {
        videoList.removeAllViews()

        if (deviceVideos.isEmpty()) {
            videoStatus.text = "No videos found. Add videos to your device and retry."
            return
        }

        val filteredVideos = deviceVideos.filter { it.title.contains(query.trim(), ignoreCase = true) }
        if (filteredVideos.isEmpty()) {
            videoStatus.text = "No videos match your search."
            return
        }
        videoStatus.text = "${deviceVideos.count { it.title.contains(query.trim(), ignoreCase = true) }} matching videos"

        filteredVideos.forEach { video ->
            val compactVideoRow = resources.configuration.screenWidthDp < 420
            val videoThumbWidth = if (compactVideoRow) 68 else if (resources.configuration.screenWidthDp >= 600) 116 else 88
            val videoThumbHeight = if (compactVideoRow) 48 else if (resources.configuration.screenWidthDp >= 600) 82 else 62
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (compactVideoRow) 8 else 10), dp(if (compactVideoRow) 6 else 10), dp(8), dp(if (compactVideoRow) 6 else 10))
                isClickable = true
                isFocusable = true
                background = roundedBackground(themeColor(30, 21, 43), 18).apply {
                    setStroke(dp(1), if (video.uri.toString() == selectedVideoUri) themeColor(173, 115, 236) else themeColor(69, 48, 89))
                }
                isFocusable = true
                contentDescription = "Play video ${video.title}"
                if (video.uri.toString() == selectedVideoUri) {
                    elevation = dp(2).toFloat()
                }
            }

            val thumbnail = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_media_play)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBackground(themeColor(53, 35, 73), 12).apply {
                    setStroke(dp(1), themeColor(104, 70, 137))
                }
                clipToOutline = true
                contentDescription = "Thumbnail for ${video.title}"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    thumbnail.setImageBitmap(
                        contentResolver.loadThumbnail(
                            video.uri,
                            Size(dp(videoThumbWidth), dp(videoThumbHeight)),
                            null
                        )
                    )
                } catch (_: Exception) {
                    // Keep the play icon if a thumbnail cannot be loaded.
                }
            }

            row.addView(thumbnail, LinearLayout.LayoutParams(dp(videoThumbWidth), dp(videoThumbHeight)))

            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (compactVideoRow) 9 else 13), 0, 0, 0)
            }

            details.addView(label(video.title, if (compactVideoRow) 13f else 15f, Color.WHITE, true).apply {
                maxLines = if (compactVideoRow) 1 else 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            details.addView(
                label(formatTime(video.durationMs), 12f, themeColor(190, 175, 210))
            )
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))

            row.setOnClickListener { playDeviceVideo(video) }

            videoList.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            )
        }
    }

    private fun playDeviceVideo(video: DeviceVideo): Boolean {
        if (!isVideoAvailable(video)) {
            Toast.makeText(this, "This video is unavailable.", Toast.LENGTH_SHORT).show()
            return false
        }
        continueUserPlayback(
            PendingPlayback("play_video", uri = video.uri.toString(), title = video.title)
        )
        return videoPlayer != null
    }

    /** Move the existing PlayerView into fullscreen without recreating its player. */
    private fun showVideoFullscreen() {
        if (videoPlayer?.currentMediaItem == null) return
        if (fullscreenVideoDialog?.isShowing == true) return

        val parent = videoPlayerView.parent as? android.view.ViewGroup ?: return
        videoPlayerView.useController = false
        parent.removeView(videoPlayerView)

        val dialog = android.app.Dialog(
            this,
            android.R.style.Theme_Black_NoTitleBar_Fullscreen
        )
        val fullscreenRoot = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        fullscreenRoot.addView(
            videoPlayerView,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(themeColor(25, 15, 37), Color.TRANSPARENT)
            )
        }
        val topControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var showControls: (() -> Unit)? = null
        fun control(text: String, description: String, onClick: () -> Unit) =
            label(text, 18f, themeColor(245, 235, 255), true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                contentDescription = description
                background = roundedBackground(themeColor(54, 35, 73), 14)
                setOnClickListener {
                    showControls?.invoke()
                    onClick()
                }
            }
        val exitButton = control("‹", "Exit fullscreen") { dialog.dismiss() }
        val menuButton = label("☰", 18f, themeColor(245, 235, 255), true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "Open video list"
            background = roundedBackground(themeColor(54, 35, 73), 14)
        }
        val currentTitle = label("", 14f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(8), 0)
        }
        topControls.addView(exitButton)
        topControls.addView(currentTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topControls.addView(menuButton)
        controls.addView(topControls)

        val menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(themeColor(31, 21, 43), 16).apply {
                setStroke(dp(1), themeColor(112, 72, 148))
            }
            visibility = View.GONE
        }
        val menuScroll = ScrollView(this)
        menuScroll.addView(menuPanel)
        controls.addView(menuScroll, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(8) })

        val bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(themeColor(25, 15, 37), Color.TRANSPARENT)
            )
        }
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previousButton = control("|◀", "Previous video") { navigateVideo(-1) }
        val playPauseButton = control("Ⅱ", "Pause video") {
            videoPlayer?.let {
                if (it.isPlaying) it.pause() else continueUserPlayback(
                    PendingPlayback("resume_video", uri = it.currentMediaItem?.localConfiguration?.uri?.toString())
                )
            }
        }
        val nextButton = control("▶|", "Next video") { navigateVideo(1) }
        transport.addView(previousButton)
        transport.addView(playPauseButton, LinearLayout.LayoutParams(dp(64), dp(52)).apply {
            leftMargin = dp(18); rightMargin = dp(18)
        })
        transport.addView(nextButton)
        bottomControls.addView(transport)

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val elapsed = label("0:00", 12f, themeColor(223, 207, 241))
        val duration = label("0:00", 12f, themeColor(223, 207, 241))
        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(themeColor(184, 126, 243))
            thumbTintList = android.content.res.ColorStateList.valueOf(themeColor(225, 196, 255))
        }
        var seeking = false
        var scheduleHide: (() -> Unit)? = null
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
                showControls?.invoke()
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                val length = videoPlayer?.duration ?: C.TIME_UNSET
                if (length > 0 && length != C.TIME_UNSET) videoPlayer?.seekTo(length * bar.progress / 1000L)
                seeking = false
                if (videoPlayer?.isPlaying == true) scheduleHide?.invoke()
            }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = formatTime((videoPlayer?.duration?.coerceAtLeast(0L) ?: 0L) * value / 1000L)
            }
        })
        seekRow.addView(elapsed)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        seekRow.addView(duration)
        bottomControls.addView(seekRow)
        fullscreenRoot.addView(controls, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        fullscreenRoot.addView(bottomControls, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        fun refreshMenu() {
            menuPanel.removeAllViews()
            if (deviceVideos.isEmpty()) {
                menuPanel.addView(label("No videos available", 14f, themeColor(190, 175, 210)))
                return
            }
            deviceVideos.forEach { video ->
                val selected = video.uri.toString() == selectedVideoUri
                val row = label("${if (selected) "✓  " else ""}${video.title}", 14f,
                    if (selected) themeColor(218, 177, 255) else Color.WHITE, selected).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isClickable = true
                    isFocusable = true
                    background = roundedBackground(if (selected) themeColor(67, 42, 89) else Color.TRANSPARENT, 12)
                    contentDescription = "Play ${video.title}${if (selected) ", current video" else ""}"
                    setOnClickListener {
                        val started = playDeviceVideo(video)
                        refreshMenu()
                        if (started) {
                            menuPanel.visibility = View.GONE
                            showControls?.invoke()
                        }
                    }
                }
                menuPanel.addView(row)
            }
        }

        var controlsVisible = true
        val hideControls = Runnable {
            if (videoPlayer?.isPlaying == true && !seeking && menuPanel.visibility != View.VISIBLE) {
                controls.visibility = View.GONE
                bottomControls.visibility = View.GONE
                controlsVisible = false
            }
        }
        scheduleHide = {
            handler.removeCallbacks(hideControls)
            handler.postDelayed(hideControls, 3500)
        }
        fun updateControls() {
            val p = videoPlayer ?: return
            val position = p.currentPosition.coerceAtLeast(0L)
            val length = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            if (!seeking) seek.progress = if (length > 0) (position * 1000L / length).toInt().coerceIn(0, 1000) else 0
            elapsed.text = formatTime(position)
            duration.text = formatTime(length)
            currentTitle.text = deviceVideos.firstOrNull { it.uri.toString() == selectedVideoUri }?.title
                ?: if (lastFailedVideoUri != null) "Playback error" else "Video"
            val playing = p.isPlaying
            playPauseButton.text = if (playing) "Ⅱ" else "▶"
            playPauseButton.contentDescription = if (playing) "Pause video" else "Play video"
            if (!playing) {
                handler.removeCallbacks(hideControls)
                controls.visibility = View.VISIBLE
                bottomControls.visibility = View.VISIBLE
                controlsVisible = true
            }
        }
        showControls = {
            controls.visibility = View.VISIBLE
            bottomControls.visibility = View.VISIBLE
            controlsVisible = true
            updateControls()
            if (videoPlayer?.isPlaying == true) scheduleHide?.invoke()
            else handler.removeCallbacks(hideControls)
        }
        menuButton.setOnClickListener {
            val open = menuPanel.visibility != View.VISIBLE
            if (open) refreshMenu()
            menuPanel.visibility = if (open) View.VISIBLE else View.GONE
            showControls?.invoke()
        }
        videoPlayerView.setOnClickListener {
            if (controlsVisible && videoPlayer?.isPlaying == true && !seeking) {
                handler.removeCallbacks(hideControls)
                controls.visibility = View.GONE
                bottomControls.visibility = View.GONE
                controlsVisible = false
            } else showControls?.invoke()
        }
        val progressTick = object : Runnable {
            override fun run() {
                if (fullscreenVideoDialog === dialog && dialog.isShowing) {
                    updateControls()
                    handler.postDelayed(this, 500)
                }
            }
        }
        val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateControls()
                if (isPlaying && controlsVisible && !seeking) scheduleHide?.invoke()
            }
            override fun onPlayerError(error: PlaybackException) {
                refreshMenu()
                updateControls()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshMenu()
                updateControls()
            }
        }
        videoPlayer?.addListener(playerListener)
        dialog.setOnDismissListener {
            handler.removeCallbacks(hideControls)
            handler.removeCallbacks(progressTick)
            videoPlayer?.removeListener(playerListener)
            videoPlayerView.setOnClickListener(null)
            videoPlayerView.useController = true
            restoreVideoPlayerView()
            if (fullscreenVideoDialog === dialog) fullscreenVideoDialog = null
        }
        dialog.setContentView(fullscreenRoot)
        fullscreenVideoDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        refreshMenu()
        updateControls()
        handler.post(progressTick)
        if (videoPlayer?.isPlaying == true) scheduleHide?.invoke()
    }

    private fun isVideoAvailable(video: DeviceVideo): Boolean = try {
        contentResolver.openFileDescriptor(video.uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    private fun navigateVideo(direction: Int) {
        if (deviceVideos.isEmpty()) {
            Toast.makeText(this, "No videos are available.", Toast.LENGTH_SHORT).show()
            return
        }
        val currentUri = selectedVideoUri ?: navigationVideoUri
        val current = deviceVideos.indexOfFirst { it.uri.toString() == currentUri }
        val start = if (current < 0) if (direction > 0) -1 else 0 else current
        var index = start
        repeat(deviceVideos.size) {
            index = (index + direction + deviceVideos.size) % deviceVideos.size
            val candidate = deviceVideos[index]
            if (isVideoAvailable(candidate)) {
                playDeviceVideo(candidate)
                return
            }
        }
        Toast.makeText(this, "No playable videos are available.", Toast.LENGTH_SHORT).show()
    }

    private fun reportVideoPlaybackFailure(failedVideoUri: String? = null) {
        if (isFinishing || isDestroyed) return
        val currentUri = videoPlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        if (failedVideoUri == null && currentUri != null && navigationVideoUri != null &&
            currentUri != navigationVideoUri
        ) return
        val failedUri = failedVideoUri ?: navigationVideoUri ?: selectedVideoUri
        if (failedUri != null && failedUri == lastFailedVideoUri) return
        lastFailedVideoUri = failedUri
        val failedTitle = deviceVideos.firstOrNull { it.uri.toString() == failedUri }?.title ?: "video"
        selectedVideoUri = null
        updateVideoPlayerVisibility()
        if (::videoStatus.isInitialized) {
            renderVideos(searchBox.text?.toString().orEmpty())
            videoStatus.text = "Couldn't play $failedTitle. Choose another video or use previous/next."
        }
        Toast.makeText(
            this,
            "Couldn't play $failedTitle. Choose another video.",
            Toast.LENGTH_LONG
        ).show()
    }

    /** Collapse the playback surface and its dedicated controls until a video is selected/loaded. */
    private fun updateVideoPlayerVisibility() {
        if (!::videoPlayerView.isInitialized) return
        val currentUri = videoPlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
        val hasSelectedVideo = !selectedVideoUri.isNullOrBlank() || !currentUri.isNullOrBlank()
        // The video library stays available; only the playback surface and its controls collapse.
        videoPlayerView.visibility = if (hasSelectedVideo) View.VISIBLE else View.GONE
        fullscreenVideoButton?.visibility = if (hasSelectedVideo) View.VISIBLE else View.GONE
        minimizeVideoButton?.visibility = if (hasSelectedVideo) View.VISIBLE else View.GONE
    }

    private fun restoreVideoPlayerView() {
        if (!::videoPlayerView.isInitialized || !::videoPlaceholder.isInitialized) return
        (videoPlayerView.parent as? android.view.ViewGroup)?.removeView(videoPlayerView)
        if (videoPlayerView.parent == null) {
            videoPlaceholder.addView(
                videoPlayerView,
                0,
                LinearLayout.LayoutParams(-1, dp(if (resources.configuration.screenWidthDp < 420) 112 else if (resources.configuration.screenWidthDp >= 600) 220 else 160))
            )
        }
    }

    private fun openSettings() {
        val options = listOf(
            SettingsAction("🎨", "Appearance & themes", "Choose the look that suits you") { openThemePicker() },
            SettingsAction("↻", "Rescan music", "Refresh songs and album artwork") { songsLoaded = false; loadSongs() },
            SettingsAction("▣", "Rescan videos", "Refresh videos stored on this device") { videosLoaded = false; loadVideos() },
            SettingsAction("♧", "Weekly reminders", "Turn listening reminders on or off") { configureListeningReminders() },
            SettingsAction("ⓘ", "About A1 Family", "Offline-first music and video player with optional sponsored discovery") {
                AlertDialog.Builder(this).setTitle("A1 Family")
                    .setMessage("Enjoy local music and videos, playlists, favourites, and uninterrupted background playback. Optional sponsored content is clearly labelled and never interrupts playback.")
                    .setPositiveButton("Got it", null).show()
            }
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBackground(themeColor(20, 13, 31), 22)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        options.forEach { option ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(themeColor(35, 23, 51), 16).apply {
                    setStroke(dp(1), themeColor(77, 54, 101))
                }
                isClickable = true
                isFocusable = true
                contentDescription = option.title
                setOnClickListener { option.action.invoke() }
            }
            item.addView(label(option.icon, 21f, themeColor(218, 180, 255), true).apply {
                gravity = Gravity.CENTER
                background = roundedBackground(themeColor(56, 35, 78), 12)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            copy.addView(label(option.title, 14f, Color.WHITE, true))
            copy.addView(label(option.description, 11f, themeColor(187, 168, 207)).apply {
                setPadding(0, dp(3), 0, 0)
            })
            item.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            item.addView(label("›", 24f, themeColor(190, 145, 237), true))
            panel.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }
        scroll.addView(panel)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            minOf(dp(620), (resources.displayMetrics.heightPixels * 0.78f).toInt())
        )
    }

    private fun openThemePicker() {
        val names = arrayOf("A1 Family Media", "Midnight Purple", "Ocean Blue", "Forest Green", "Rose Pink")
        val ids = arrayOf("purple", "purple", "ocean", "forest", "rose")
        val current = ids.indexOf(selectedTheme()).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Choose a theme")
            .setSingleChoiceItems(names, current) { dialog, which ->
                getSharedPreferences("kivora_preferences", MODE_PRIVATE)
                    .edit().putString("theme", ids[which]).apply()
                dialog.dismiss()
                recreate()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openLyricsEditor() {
        val currentUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
        val song = songs.firstOrNull { it.uri.toString() == currentUri }
        if (song == null) {
            Toast.makeText(this, "Play a song from your library before adding lyrics.", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getSharedPreferences("a1_family_lyrics", MODE_PRIVATE)
        val lyrics = EditText(this).apply {
            hint = "Paste or type your own lyrics here…"
            setText(prefs.getString(song.uri.toString(), ""))
            setSingleLine(false)
            minLines = 10
            maxLines = 22
            gravity = Gravity.TOP or Gravity.START
            textSize = 15f
            setTextColor(themeColor(245, 238, 255))
            setHintTextColor(themeColor(151, 132, 174))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = roundedBackground(themeColor(24, 16, 36), 16).apply {
                setStroke(dp(1), themeColor(103, 70, 132))
            }
            contentDescription = "Lyrics editor. Type or paste lyrics you have permission to use."
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(6))
            addView(label("NOW EDITING", 10f, themeColor(66, 224, 255), true).apply {
                letterSpacing = 0.12f
                setPadding(0, 0, 0, dp(7))
            })
            addView(label(song.title, 18f, themeColor(245, 238, 255), true))
            addView(label(song.artist.ifBlank { "Unknown artist" }, 12f, themeColor(192, 173, 214)).apply {
                setPadding(0, dp(4), 0, dp(14))
            })
            addView(label("YOUR LYRICS", 10f, themeColor(194, 165, 224), true).apply {
                letterSpacing = 0.1f
                setPadding(0, 0, 0, dp(7))
            })
            addView(lyrics, LinearLayout.LayoutParams(-1, dp(320)))
            addView(label("Lyrics are saved on this device for this track.", 11f, themeColor(160, 143, 181)).apply {
                setPadding(dp(2), dp(9), dp(2), 0)
            })
        }
        val dialog = AlertDialog.Builder(this).setTitle("Song lyrics").setView(content)
            .setPositiveButton("Save lyrics", null)
            .setNeutralButton("Export PDF", null)
            .setNegativeButton("Close", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                prefs.edit().putString(song.uri.toString(), lyrics.text.toString()).apply()
                Toast.makeText(this, "Lyrics saved for ${song.title}.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val text = lyrics.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "Write or paste lyrics before exporting.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString(song.uri.toString(), text).apply()
                pendingLyricsPdfTitle = song.title
                pendingLyricsPdfText = text
                val safeTitle = song.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(60).ifBlank { "Lyrics" }
                lyricsPdfLauncher.launch("$safeTitle-lyrics.pdf")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun writeLyricsPdf(uri: Uri, title: String, text: String) {
        val document = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
        }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: android.graphics.Canvas? = null
        var y = 0f
        fun startPage() {
            page?.let { document.finishPage(it) }
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            canvas = page?.canvas
            canvas?.drawText(title.take(52), 48f, 48f, titlePaint)
            canvas?.drawText("A1 Family · Lyrics", 48f, 68f, bodyPaint)
            y = 100f
        }
        try {
            startPage()
            val maxWidth = 499f
            text.split("\n").forEach { rawLine ->
                val words = rawLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) {
                    y += 18f
                    if (y > 790f) startPage()
                } else {
                    var line = ""
                    words.forEach { word ->
                        val candidate = if (line.isEmpty()) word else "$line $word"
                        if (bodyPaint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                            if (y > 790f) startPage()
                            canvas?.drawText(line, 48f, y, bodyPaint)
                            y += 18f
                            line = word
                        } else line = candidate
                    }
                    if (y > 790f) startPage()
                    canvas?.drawText(line, 48f, y, bodyPaint)
                    y += 18f
                }
            }
            page?.let { document.finishPage(it) }
            contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
                ?: throw IllegalStateException("Could not open the selected PDF destination.")
            Toast.makeText(this, "Lyrics PDF saved.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not create the lyrics PDF.", Toast.LENGTH_LONG).show()
        } finally {
            document.close()
        }
    }

    private fun scheduleOpenAdIfDue() {
        val prefs = getSharedPreferences("a1_family_ad_policy", MODE_PRIVATE)
        val opens = prefs.getInt("app_open_count", 0) + 1
        prefs.edit().putInt("app_open_count", opens).apply()
        handler.postDelayed({ tryShowOpenAdWhenReady() }, 1_800L)
    }

    private fun tryShowOpenAdWhenReady(attempt: Int = 0) {
        if (isFinishing || isDestroyed || player?.isPlaying == true || videoPlayer?.isPlaying == true) return
        if (hasWindowFocus()) {
            StartIoVideoAds.showOptionalAd(this) { }
        } else if (attempt < 15) {
            // Wait through permission prompts and startup window-focus transitions.
            handler.postDelayed({ tryShowOpenAdWhenReady(attempt + 1) }, 900L)
        }
    }


    private fun openMoreMusicCatalogues(query: String) {
        val q = Uri.encode(query.trim())
        val catalogues = listOf(
            "Jamendo Music" to "https://www.jamendo.com/search?q=$q",
            "Free Music Archive" to "https://freemusicarchive.org/search/?quicksearch=$q",
            "Internet Archive" to "https://archive.org/search?query=$q",
            "SoundCloud" to "https://soundcloud.com/search/sounds?q=$q",
            "Audiomack" to "https://audiomack.com/search?q=$q",
            "DatPiff mixtape archive" to "https://datpiff.com/",
            "SoundClick" to "https://www.soundclick.com/search/default.cfm?searchterm=$q",
            "Musopen" to "https://musopen.org/music/?q=$q",
            "Bandcamp" to "https://bandcamp.com/search?q=$q"
        )
        AlertDialog.Builder(this)
            .setTitle("More music catalogues")
            .setItems(catalogues.map { it.first }.toTypedArray()) { _, which ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(catalogues[which].second)))
                }.onFailure {
                    Toast.makeText(this, "No browser is available to open that catalogue.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }










    private fun configureListeningReminders() {
        val prefs = getSharedPreferences("a1_family_notifications", MODE_PRIVATE)
        val enabled = prefs.getBoolean("weekly_reminders_enabled", false)
        if (enabled) {
            prefs.edit().putBoolean("weekly_reminders_enabled", false).apply()
            ListeningReminderScheduler.cancel(this)
            Toast.makeText(this, "Weekly listening reminders disabled.", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        prefs.edit().putBoolean("weekly_reminders_enabled", true).apply()
        ListeningReminderScheduler.schedule(this)
        Toast.makeText(this, "Weekly listening reminders enabled when internet is available.", Toast.LENGTH_LONG).show()
    }


    /**
     * Loads one Start.io native ad per Activity instance and places it beside the clearly
     * labelled Adsterra SmartLink card. Native ads are optional; a no-fill response is silent.
     */
    private fun ensureStartIoNativeAd(sponsoredCard: View) {
        if (loadedStartIoNativeAd != null) {
            attachStartIoNativeAd(sponsoredCard, loadedStartIoNativeAd!!)
            return
        }
        if (startIoNativeAdRequested) return
        startIoNativeAdRequested = true
        val nativeRequest = try {
            StartAppNativeAd(this)
        } catch (error: RuntimeException) {
            android.util.Log.w("A1FamilyAds", "Could not create Start.io native ad", error)
            return
        }
        try {
            nativeRequest.loadAd(
                NativeAdPreferences().setAutoBitmapDownload(true),
                object : AdEventListener {
                    override fun onReceiveAd(ad: com.startapp.sdk.adsbase.Ad) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            loadedStartIoNativeAd = nativeRequest.getNativeAds()?.firstOrNull()
                            loadedStartIoNativeAd?.let { attachStartIoNativeAd(sponsoredCard, it) }
                        }
                    }

                    override fun onFailedToReceiveAd(ad: com.startapp.sdk.adsbase.Ad?) {
                        android.util.Log.i("A1FamilyAds", "Start.io native ad unavailable; continuing without it")
                    }
                }
            )
        } catch (error: RuntimeException) {
            android.util.Log.w("A1FamilyAds", "Start.io native ad request failed", error)
        }
    }

    private fun attachStartIoNativeAd(sponsoredCard: View, details: NativeAdDetails) {
        // Home content is rebuilt after library/playback updates; resolve the current SmartLink
        // card instead of attaching the native ad to an obsolete view from an earlier render.
        val currentSponsoredCard = homeContent.findViewWithTag<View>("adsterra_smartlink_card") ?: sponsoredCard
        val existing = homeContent.findViewWithTag<View>("startio_native_ad_card")
        if (existing != null) return
        val nativeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = roundedBackground(themeColor(25, 17, 38), 18).apply {
                setStroke(dp(1), themeColor(79, 54, 103))
            }
            tag = "startio_native_ad_card"
            contentDescription = "Sponsored Start.io native advertisement"
        }
        nativeCard.addView(label("ADVERTISEMENT  •  START.IO", 9f, themeColor(40, 224, 255), true).apply {
            letterSpacing = 0.12f
        })
        val title = details.getTitle().orEmpty().trim()
        if (title.isNotEmpty()) {
            nativeCard.addView(label(title, 16f, themeColor(250, 247, 255), true).apply {
                setPadding(0, dp(7), 0, dp(4))
            })
        }
        val description = details.getDescription().orEmpty().trim()
        if (description.isNotEmpty()) {
            nativeCard.addView(label(description, 12f, themeColor(211, 194, 232)).apply {
                setPadding(0, 0, 0, dp(4))
            })
        }
        details.getImageBitmap()?.let { bitmap ->
            nativeCard.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Sponsored image"
            }, LinearLayout.LayoutParams(-1, dp(132)).apply {
                topMargin = dp(7)
                bottomMargin = dp(7)
            })
        }
        details.registerViewForInteraction(nativeCard)
        val position = homeContent.indexOfChild(currentSponsoredCard)
        if (position >= 0) {
            homeContent.addView(nativeCard, position + 1, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(14)
            })
        }
    }

    private fun openSponsoredOffers() {
        val rawUrl = ADSTERRA_SMARTLINK_URL.trim()
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Sponsored offers are not enabled yet. An approved Adsterra SmartLink must be configured first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure {
            Toast.makeText(this, "No browser is available to open sponsored offers.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTab(tab: String) {
        val previousTab = currentTab
        currentTab = tab
        if (tab == "home") renderHomeDashboard()
        searchRow.visibility =
            if (tab == "search" || tab == "library" || tab == "videos") View.VISIBLE else View.GONE
        libraryHeader.visibility =
            if (tab == "library" && libraryContentVisible && libraryShortcutsVisible) View.VISIBLE else View.GONE
        songScroll.visibility =
            if ((tab == "search" || tab == "library") && libraryContentVisible) View.VISIBLE else View.GONE
        videoPlaceholder.visibility =
            if (tab == "videos") View.VISIBLE else View.GONE
        updateVideoPlayerVisibility()
        if (tab == "videos" && !videosLoaded) {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                loadVideos()
            } else {
                videoPermissionLauncher.launch(permission)
            }
        }
        homeScroll.visibility = if (tab == "home") View.VISIBLE else View.GONE
        playerScroll.visibility = if (tab == "nowPlaying") View.VISIBLE else View.GONE
        miniPlayer.visibility = if (tab == "nowPlaying") View.GONE else View.VISIBLE

        if (tab == "search") {
            if (previousTab != "search") {
                musicTabAdAttempted = false
                if (player?.isPlaying != true && videoPlayer?.isPlaying != true) {
                    musicTabAdAttempted = true
                    handler.postDelayed({
                        if (!isFinishing && !isDestroyed && currentTab == "search" &&
                            player?.isPlaying != true && videoPlayer?.isPlaying != true
                        ) StartIoVideoAds.showOptionalAd(this) { }
                    }, 650L)
                }
            }
            showFavoritesOnly = false
            selectedPlaylist = null
            updateFavoriteFilterButton()
            updatePlaylistButton()
            renderSongs(searchBox.text?.toString().orEmpty())
        } else if (tab == "library") {
            updateLibraryBrowseButtons()
            renderSongs(searchBox.text?.toString().orEmpty())
        }

        val activeContent: View? = when (tab) {
            "home" -> homeScroll
            "search", "library" -> songScroll
            "videos" -> videoPlaceholder
            "nowPlaying" -> playerScroll
            else -> null
        }
        activeContent?.let { content ->
            content.animate().cancel()
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(180L).start()
        }

        navigationButtons.forEach { (name, button) ->
            val selected = name == tab || (name == "home" && tab == "nowPlaying")
            button.setTextColor(
                if (selected) themeColor(232, 213, 255)
                else themeColor(174, 159, 194)
            )
            button.background = roundedBackground(
                if (selected) themeColor(83, 39, 126)
                else Color.TRANSPARENT,
                14
            )
        }
    }

}
