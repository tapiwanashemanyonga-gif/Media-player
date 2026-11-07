
package com.kivora.music

import android.Manifest
import android.content.ComponentName
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
import android.util.Size
import android.util.TypedValue
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

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

class MainActivity : AppCompatActivity() {

    private var player: MediaController? = null
    private var pendingExternalMediaIntent: Intent? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private lateinit var songList: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var artwork: ImageView
    private lateinit var artworkFrame: android.widget.FrameLayout
    private lateinit var miniArtwork: ImageView
    private lateinit var playButton: TextView
    private lateinit var shuffleButton: TextView
    private lateinit var repeatButton: TextView
    private lateinit var searchBox: EditText
    private lateinit var clearSearchButton: TextView
    private lateinit var progress: SeekBar
    private lateinit var elapsedText: TextView
    private lateinit var durationText: TextView
    private lateinit var libraryCount: TextView
    private lateinit var favoriteFilterButton: TextView
    private lateinit var playlistButton: TextView
    private lateinit var allSongsButton: TextView
    private lateinit var libraryHeader: LinearLayout
    private lateinit var libraryVisibilityToggle: TextView
    private var libraryControlsVisible = true
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
    private lateinit var customizationStore: A1CustomizationStore
    private var pendingTrackMemorySong: Song? = null
    private val trackMemoryPhotoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val song = pendingTrackMemorySong
        pendingTrackMemorySong = null
        if (uri != null && song != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val key = song.uri.toString()
            val existing = customizationStore.loadTrackMemory(key)
            customizationStore.saveTrackMemory(key, existing.copy(photoUri = uri.toString()))
            Toast.makeText(this, "Photo attached to this song's memory.", Toast.LENGTH_SHORT).show()
            openTrackMemoryDialog(song)
        }
    }
    private var customization by mutableStateOf(A1CustomizationSettings())
    private var appRoot: LinearLayout? = null
    private var videoPlayer: ExoPlayer? = null
    private var navigationVideoUri: String? = null
    private var lastFailedVideoUri: String? = null
    private val videoPlayerErrorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            reportVideoPlaybackFailure()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // If video starts after a delayed music request, stop only the competing music.
            if (isPlaying) player?.pause()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val finishedUri = selectedVideoUri ?: return
            val index = deviceVideos.indexOfFirst { it.uri.toString() == finishedUri }
            val next = deviceVideos.getOrNull(index + 1)
            if (next != null) playDeviceVideo(next)
            else videoStatus.text = "You've reached the end of your videos."
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
    private var selectedNavigationTab by mutableStateOf("home")
    private var searchQuery by mutableStateOf("")
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlayButton: TextView
    private val navigationButtons = linkedMapOf<String, TextView>()

    private val songs = mutableListOf<Song>()
    // Album grouping is reused by the Home dashboard instead of recalculated on every track change.
    private var cachedAlbumGroups: List<List<Song>>? = null
    private var activeQueue: List<Song> = emptyList()
    private val recentlyPlayedUris = mutableListOf<String>()
    private val favoriteSongUris = mutableSetOf<String>()
    private val playlists = linkedMapOf<String, MutableSet<String>>()

    private var showFavoritesOnly = false
    private var selectedPlaylist: String? = null
    private var libraryBrowseMode by mutableStateOf("songs")
    private var selectedAlbumKey: String? = null
    private var selectedArtistName: String? = null
    private var librarySortMode = "title"
    private var userSeeking = false
    private var pendingLyricsPdfTitle: String = "A1 Family Lyrics"
    private var pendingLyricsPdfText: String = ""
    private val lyricsPdfLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) writeLyricsPdf(uri, pendingLyricsPdfTitle, pendingLyricsPdfText)
    }
    private val wallpaperPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            customization = customization.copy(wallpaperUri = uri.toString())
            customizationStore.save(customization)
            applyCustomizationWallpaper()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var searchRenderRunnable: Runnable? = null

    // Keep media scans and thumbnail decoding off the UI thread, and bound each UI render batch.
    private val musicScanExecutor = Executors.newSingleThreadExecutor()
    private var songRenderLimit = 40
    private var videoRenderLimit = 24
    private val videoScanExecutor = Executors.newSingleThreadExecutor()
    private val videoThumbnailExecutor = Executors.newFixedThreadPool(2)
    private var musicScanRequestId = 0
    private var videoScanRequestId = 0
    private val videoThumbnailCache = object : LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 16L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val videoThumbnailTargets = HashMap<String, MutableList<ImageView>>()

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
            // A delayed music start must not overlap a video that began during the request.
            if (isPlaying) {
                videoPlayer?.let { video ->
                    video.playWhenReady = false
                    video.pause()
                }
            }
            updatePlayButton()
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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(13, 8, 23)
        window.navigationBarColor = Color.rgb(13, 8, 23)

        customizationStore = A1CustomizationStore(this)
        customization = customizationStore.load()

        val preferences = getSharedPreferences(
            "kivora_preferences",
            MODE_PRIVATE
        )

        libraryControlsVisible = !preferences.getBoolean("hide_songs_albums_shortcuts", false)

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
        checkForAppUpdates()
        A1InstallTrackingClient(this).recordFirstOpen()

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
        } else {
            permissionLauncher.launch(permission)
        }
        if (savedInstanceState == null) handleIncomingMediaIntent(intent)
    }


    /** Checks the official website's public version manifest without blocking playback or UI. */
    private fun checkForAppUpdates() {
        Thread {
            val update = runCatching {
                val connection = (java.net.URL("https://a1-family-media-site.onrender.com/version.json")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    org.json.JSONObject(body)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull() ?: return@Thread

            val latestCode = update.optInt("versionCode", 0)
            val latestName = update.optString("versionName", "")
            val downloadUrl = update.optString(
                "downloadUrl",
                "https://a1-family-media-site.onrender.com/#download"
            )
            val notes = update.optString("releaseNotes", "A newer version is available.")
            if (latestCode <= BuildConfig.VERSION_CODE || latestName.isBlank()) return@Thread

            handler.post {
                if (isFinishing || isDestroyed) return@post
                AlertDialog.Builder(this)
                    .setTitle("A1 FAMILY MEDIA update available")
                    .setMessage("Version $latestName is ready.\n\n$notes\n\nWould you like to download the update now?")
                    .setPositiveButton("Download update") { _, _ ->
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                        }.onFailure {
                            Toast.makeText(this, "Couldn't open the download page.", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Later", null)
                    .setCancelable(true)
                    .show()
            }
        }.start()
    }

    private fun handleIncomingMediaIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_VIEW) return
        val uri = incoming.data ?: return
        if (uri.scheme != "content" && uri.scheme != "file") return
        if (player == null) {
            pendingExternalMediaIntent = incoming
            return
        }

        val mimeType = incoming.type
            ?: runCatching { contentResolver.getType(uri) }.getOrNull()
            ?: ""
        val name = runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
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
            isVideo -> {
                val video = DeviceVideo(uri.toString().hashCode().toLong(), name, uri, 0L)
                if (deviceVideos.none { it.uri == uri }) deviceVideos.add(0, video)
                playDeviceVideo(video)
            }
            isAudio -> {
                showFavoritesOnly = false
                selectedPlaylist = null
                val song = Song(name, "Unknown artist", "Unknown album", 0L, uri, null)
                songs.removeAll { it.uri == uri }
                songs.add(0, song)
                cachedAlbumGroups = null
                normalizedSearchText[uri.toString()] =
                    (song.title + " " + song.artist + " " + song.album).lowercase(Locale.ROOT)
                renderSongs(searchBox.text?.toString().orEmpty())
                playSong(song)
            }
            else -> Toast.makeText(this, "This file type isn't supported by A1 FAMILY MEDIA.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingMediaIntent(intent)
    }

    private fun connectToPlaybackService() {
        val token = SessionToken(
            this,
            ComponentName(this, KivoraPlaybackService::class.java)
        )

        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                try {
                    val connected = future.get()

                    if (isFinishing || isDestroyed) {
                        MediaController.releaseFuture(future)
                        return@addListener
                    }

                    player = connected
                    connected.addListener(playerListener)
                    pendingExternalMediaIntent?.let { pending ->
                        pendingExternalMediaIntent = null
                        handleIncomingMediaIntent(pending)
                    }

                    syncPlaylistToPlayer()
                    recordRecentlyPlayed(connected.currentMediaItem)
                    updateNowPlaying()
                    updatePlayButton()
                    updateShuffleButton()
                    updateRepeatButton()
                    updateProgress()

                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Couldn't connect to the music service. Reopen Kivora Music Player.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    /** Instrumentation can opt out of third-party WebViews; normal app sessions always keep ads. */
    private fun shouldLoadAdBanners(): Boolean =
        !(BuildConfig.DEBUG && intent.getBooleanExtra("a1.instrumentation.disable_ads", false))

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

    private fun applyButtonFeedback(view: View) {
        if (view.isClickable || view.isFocusable) {
            val mask = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            view.foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(72, 205, 160, 255)),
                null,
                mask
            )
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) applyButtonFeedback(view.getChildAt(index))
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
        val screenWidthDp = resources.configuration.screenWidthDp
        val screenHeightDp = resources.configuration.screenHeightDp
        val compactLayout = screenWidthDp < 420
        val narrowPhoneLayout = screenWidthDp < 360
        // Short screens (including landscape phones and split-screen) need a denser player.
        val compactPlayerLayout = screenHeightDp < 700
        val compactMiniPlayer = compactLayout || compactPlayerLayout
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compactLayout) 12 else 18), dp(if (compactLayout) 10 else 14), dp(if (compactLayout) 12 else 18), dp(8))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(13, 8, 23), Color.rgb(24, 13, 39), Color.rgb(12, 10, 22))
            )
        }

        appRoot = root

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(2))
        }

        val brandMark = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "A1 Family Media logo"
        }
        header.addView(brandMark, LinearLayout.LayoutParams(dp(42), dp(42)))

        val brandCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brandCopy.addView(label("A1 FAMILY", 15f, Color.rgb(250, 247, 255), true))
        brandCopy.addView(label("MEDIA PLAYER", 9f, Color.rgb(182, 157, 213), true).apply {
            letterSpacing = 0.16f
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(brandCopy, LinearLayout.LayoutParams(0, -2, 1f))
        if (!narrowPhoneLayout) {
            header.addView(label("●  LOCAL LIBRARY", 9f, Color.rgb(210, 190, 232), true).apply {
                letterSpacing = 0.06f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBackground(Color.rgb(39, 24, 58), 18).apply {
                    setStroke(dp(1), Color.rgb(83, 56, 115))
                }
            })
        }
        header.addView(label("⚙", 22f, Color.rgb(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(44)
            contentDescription = "Personalize A1 Family Media"
            background = roundedBackground(Color.rgb(48, 31, 68), 14).apply {
                setStroke(dp(1), customization.accentArgb)
            }
            setOnClickListener { openPersonalizationSettings() }
        })

        root.addView(header)

        root.addView(
            label(
                "Your sound, your space.",
                12f,
                Color.rgb(192, 160, 232),
                true
            ).apply {
                setPadding(dp(2), dp(9), 0, dp(12))
            }
        )

        searchBox = EditText(this).apply {
            setSingleLine(true)
            visibility = View.GONE
            contentDescription = "Search query state"
        }

        clearSearchButton = label("×", 25f, Color.rgb(232, 213, 255), true).apply {
            visibility = View.GONE
            contentDescription = "Clear search"
            setOnClickListener { searchBox.text?.clear() }
        }

        root.addView(
            ComposeView(this).apply {
                setContent {
                    A1SearchField(
                        query = searchQuery,
                        onQueryChange = { value ->
                            searchQuery = value
                            if (searchBox.text?.toString() != value) {
                                searchBox.setText(value)
                                searchBox.setSelection(searchBox.text?.length ?: 0)
                            }
                        }
                    )
                }
            },
            LinearLayout.LayoutParams(-1, dp(60))
        )

        libraryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(18), dp(1), dp(8))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val libraryTitle = label("Your library", if (narrowPhoneLayout) 22f else 24f, Color.rgb(250, 247, 255), true)
        if (narrowPhoneLayout) {
            titleRow.orientation = LinearLayout.VERTICAL
            titleRow.gravity = Gravity.START
            titleRow.addView(libraryTitle, LinearLayout.LayoutParams(-1, -2))
        } else {
            titleRow.addView(libraryTitle, LinearLayout.LayoutParams(0, -2, 1f))
        }

        libraryCount = label(
            "0 tracks in your collection",
            11f,
            Color.rgb(211, 194, 232),
            // KIVORA_ROUND17_LIBRARY_COUNT_BADGE
        ).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); background = roundedBackground(Color.rgb(55, 27, 79), 14).apply { setStroke(dp(1), Color.rgb(192, 132, 252)) } }

        libraryCount.gravity = if (narrowPhoneLayout) Gravity.START else Gravity.CENTER
        if (narrowPhoneLayout) libraryCount.setPadding(0, dp(5), 0, dp(2))
        titleRow.addView(libraryCount)
        libraryHeader.addView(titleRow)

        fun collectionButton(text: String, description: String, onClick: () -> Unit) =
            label(text, if (narrowPhoneLayout) 9f else 10f, Color.rgb(232, 213, 255), true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(62, 39, 88), Color.rgb(35, 23, 52))
                ).apply {
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.rgb(151, 105, 190))
                }
                isClickable = true
                isFocusable = true
                contentDescription = description
                setOnClickListener { onClick() }
            }

        allSongsButton = collectionButton("♫  ALL SONGS", "Show all songs", ::showAllSongs)
        playlistButton = collectionButton("▤  PLAYLISTS", "Open playlists", ::showPlaylistMenu)
        favoriteFilterButton = collectionButton("☆  FAVOURITES", "Toggle favourite songs") {
            showFavoritesOnly = !showFavoritesOnly
            if (showFavoritesOnly) {
                selectedPlaylist = null
                updatePlaylistButton()
            }
            updateFavoriteFilterButton()
            renderSongs(searchBox.text?.toString().orEmpty())
        }

        val collectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        collectionActions.addView(allSongsButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(4) })
        collectionActions.addView(playlistButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
        collectionActions.addView(favoriteFilterButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(4) })
        libraryHeader.addView(collectionActions)

        libraryHeader.addView(
            ComposeView(this).apply {
                setContent {
                    A1LibraryBrowseBar(
                        selectedMode = libraryBrowseMode,
                        onModeSelected = { mode ->
                            libraryBrowseMode = mode
                            selectedAlbumKey = null
                            selectedArtistName = null
                            renderSongs(searchBox.text?.toString().orEmpty())
                        }
                    )
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12)
            }
        )

        sortButton = label("↕  SORT · TITLE A–Z", 10f, Color.rgb(224, 205, 245), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundedBackground(Color.rgb(39, 26, 54), 14).apply {
                setStroke(dp(1), Color.rgb(89, 63, 115))
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

        libraryVisibilityToggle = label(
            if (libraryControlsVisible) "Hide your library" else "Show your library",
            11f,
            Color.rgb(232, 213, 255),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.rgb(42, 25, 64), 14).apply {
                setStroke(dp(1), Color.rgb(119, 78, 159))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Hide or show library controls while keeping the song list visible"
            setOnClickListener {
                libraryControlsVisible = !libraryControlsVisible
                getSharedPreferences("kivora_preferences", MODE_PRIVATE).edit()
                    .putBoolean("hide_songs_albums_shortcuts", !libraryControlsVisible).apply()
                libraryHeader.visibility = if (libraryControlsVisible && currentTab == "library") View.VISIBLE else View.GONE
                text = if (libraryControlsVisible) "Hide your library" else "Show your library"
            }
        }
        libraryVisibilityToggle.visibility = View.GONE
        root.addView(libraryVisibilityToggle, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(6)
        })
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
            setBackgroundColor(Color.rgb(8, 6, 13))
            setShutterBackgroundColor(Color.rgb(8, 6, 13))
            contentDescription = "Video player"
            visibility = View.GONE
        }
        applyCinemaSubtitleSize()
        val videoPlayerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val fullscreenButton = label("⛶ FULL", 12f, Color.rgb(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(48)
            background = roundedBackground(Color.rgb(48, 30, 67), 15).apply {
                setStroke(dp(1), Color.rgb(131, 87, 168))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Open video in fullscreen"
            setOnClickListener { showVideoFullscreen() }
        }
        videoPlayerActions.addView(fullscreenButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        videoPlayerActions.tag = "video_player_actions"
        videoPlayerActions.visibility = View.GONE
        videoPlaceholder.addView(videoPlayerActions, LinearLayout.LayoutParams(-1, -2))
        videoPlaceholder.addView(
            videoPlayerView,
            LinearLayout.LayoutParams(-1, videoPreviewHeight())
        )

        videoStatus = label(
            "Open Videos to scan your device.",
            13f,
            Color.rgb(190, 175, 210)
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
        val minimizeVideoButton = label("↓ HIDE", 11f, Color.rgb(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minHeight = dp(48)
            background = roundedBackground(Color.rgb(48, 30, 67), 15).apply {
                setStroke(dp(1), Color.rgb(131, 87, 168))
            }
            isClickable = true
            isFocusable = true
        }
        minimizeVideoButton.setOnClickListener {
            val shouldMinimize = videoPlayerView.visibility == View.VISIBLE
            videoPlayerView.visibility = if (shouldMinimize) View.GONE else View.VISIBLE
            fullscreenButton.visibility = if (shouldMinimize) View.GONE else View.VISIBLE
            minimizeVideoButton.text = if (shouldMinimize) "↑ SHOW" else "↓ HIDE"
            minimizeVideoButton.contentDescription =
                if (shouldMinimize) "Restore video player" else "Minimize video player"
        }
        videoPlayerActions.addView(minimizeVideoButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })

        val videoListButton = label("▤ LIST", 11f, Color.rgb(232, 213, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minHeight = dp(48)
            background = roundedBackground(Color.rgb(48, 30, 67), 15).apply {
                setStroke(dp(1), Color.rgb(131, 87, 168))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Return video list to its beginning"
            setOnClickListener {
                videoScroll.smoothScrollTo(0, 0)
                videoList.requestFocus()
            }
        }
        videoPlayerActions.addView(videoListButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        val stopInlineVideoButton = label("■ STOP", 11f, Color.rgb(255, 190, 190), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            minHeight = dp(48)
            background = roundedBackground(Color.rgb(75, 27, 39), 15).apply {
                setStroke(dp(1), Color.rgb(180, 75, 94))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Stop and close the current video"
            setOnClickListener { stopInlineVideo() }
        }
        videoPlayerActions.addView(stopInlineVideoButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })

        // Keep the video list last so its weight fills the space below the player and controls.
        videoPlaceholder.removeView(videoPlayerActions)
        videoPlaceholder.addView(videoPlayerActions, 1, LinearLayout.LayoutParams(-1, -2))
        root.addView(
            videoPlaceholder,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                dp(if (narrowPhoneLayout) 12 else if (compactLayout) 16 else 20),
                dp(if (compactPlayerLayout) 10 else if (compactLayout) 14 else 20),
                dp(if (narrowPhoneLayout) 12 else if (compactLayout) 16 else 20),
                dp(if (compactPlayerLayout) 10 else 18)
            )
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(54, 30, 78),
                    Color.rgb(34, 23, 48),
                    Color.rgb(24, 18, 35)
                )
            ).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.rgb(111, 76, 145))
            }
            elevation = dp(7).toFloat()
        }

        playerCard.addView(
            label("NOW PLAYING", 11f, Color.rgb(211, 176, 255), true).apply {
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
            background = roundedBackground(Color.rgb(52, 32, 75), 28).apply {
                setStroke(dp(2), Color.rgb(159, 105, 211))
            }
            setImageResource(R.drawable.ic_audio_placeholder)
            contentDescription = "Album artwork"
            clipToOutline = true
        }

        val widthBasedArtworkSize = if (compactLayout) {
            (screenWidthDp - if (narrowPhoneLayout) 76 else 104).coerceIn(if (narrowPhoneLayout) 132 else 148, 228)
        } else {
            (screenWidthDp - 86).coerceIn(160, 460)
        }
        // Preserve a generous cover while ensuring the transport controls remain reachable
        // without excessive scrolling on short displays.
        val artworkSize = if (compactPlayerLayout) {
            minOf(widthBasedArtworkSize, (screenHeightDp - 410).coerceIn(112, 210))
        } else widthBasedArtworkSize
        artworkFrame = android.widget.FrameLayout(this).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(196, 132, 255),
                    Color.rgb(99, 53, 151),
                    Color.rgb(157, 93, 226)
                )
            ).apply {
                cornerRadius = dp(36).toFloat()
                setStroke(dp(1), Color.rgb(219, 185, 255))
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
                bottomMargin = dp(if (compactPlayerLayout) 12 else 24)
            }
        )
        applyNowPlayingSkin()

        nowTitle = label(
            "Choose a song",
            if (screenWidthDp >= 600) 32f else if (compactLayout) 24f else 28f,
            Color.rgb(255, 252, 255),
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
            if (screenWidthDp >= 600) 17f else if (compactLayout) 14f else 16f,
            Color.rgb(211, 190, 235)
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
            minimumHeight = dp(if (compactPlayerLayout) 34 else 42)
            setPadding(dp(2), 0, dp(2), 0)

            progressTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(196, 132, 255)
                )

            thumbTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(228, 202, 255)
                )

            // KIVORA_ROUND14_PROGRESS_TRACK
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(82, 58, 111)
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
            topMargin = dp(if (compactPlayerLayout) 6 else 14)
        })

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        elapsedText = label(
            "0:00",
            12f,
            Color.rgb(221, 207, 238)
        )

        durationText = label(
            "−0:00",
            12f,
            Color.rgb(221, 207, 238)
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
        val sleepTimerButton = label("☾ TIMER", 10f, Color.rgb(226, 202, 255), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Open sleep timer options"
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(Color.rgb(48, 31, 68), 14).apply {
                setStroke(dp(1), Color.rgb(192, 132, 252))
            }
            setOnClickListener { openSleepTimer() }
        }
        upNextButton = label("UP NEXT · 0", 10f, Color.rgb(226, 202, 255), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "View upcoming songs"
            isClickable = true
            isFocusable = true
            minHeight = dp(44)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(Color.rgb(48, 31, 68), 14).apply {
                setStroke(dp(1), Color.rgb(192, 132, 252))
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
        val lyricsButton = label("♫", 10f, Color.rgb(226, 202, 255), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Write or view lyrics for the current song"
            minHeight = dp(44)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(Color.rgb(48, 31, 68), 14).apply {
                setStroke(dp(1), Color.rgb(192, 132, 252))
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
            background = roundedBackground(Color.rgb(48, 31, 68), 15).apply {
                setStroke(dp(1), Color.rgb(123, 83, 158))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showTab("search") }
        }

        val songListButton = label("♫  SONG LIST", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = roundedBackground(Color.rgb(48, 31, 68), 15).apply {
                setStroke(dp(1), Color.rgb(123, 83, 158))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showTab("library") }
        }

        val equalizerButton = label("♫  EQUALIZER", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = roundedBackground(Color.rgb(48, 31, 68), 15).apply {
                setStroke(dp(1), Color.rgb(192, 132, 252))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Open audio equalizer"
            setOnClickListener { openEqualizer() }
        }

        val bassBoostButton = label("♫  BASS BOOST", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = roundedBackground(Color.rgb(48, 31, 68), 15).apply {
                setStroke(dp(1), Color.rgb(192, 132, 252))
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
            setPadding(0, dp(if (compactPlayerLayout) 7 else 14), 0, dp(4))
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
                // Stop video before resuming the retained last-played song.
                videoPlayer?.apply {
                    pause()
                    stop()
                    clearMediaItems()
                }
                selectedVideoUri = null
                navigationVideoUri = null
                p.play()
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
        // Use measured card width so compact phones, split-screen and display scaling fit.
        playerCard.addView(sessionActions, LinearLayout.LayoutParams(-1, -2))
        playerCard.addView(playerShortcuts, LinearLayout.LayoutParams(-1, -2))

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
            setPadding(
                dp(if (compactMiniPlayer) 9 else 14),
                dp(if (compactMiniPlayer) 4 else 9),
                dp(if (compactMiniPlayer) 9 else 14),
                dp(if (compactMiniPlayer) 4 else 9)
            )
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(48, 29, 69), Color.rgb(30, 23, 43))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(111, 77, 143))
            }
            elevation = dp(5).toFloat()
            setOnClickListener { showTab("nowPlaying") }
        }

        miniArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_audio_placeholder)
            background = roundedBackground(Color.rgb(57, 37, 78), 12)
            clipToOutline = true
            contentDescription = "Current song album artwork"
            setOnClickListener { showTab("nowPlaying") }
        }
        miniPlayer.addView(
            miniArtwork,
            LinearLayout.LayoutParams(
                dp(if (compactMiniPlayer) 38 else 44),
                dp(if (compactMiniPlayer) 38 else 44)
            )
        )

        val miniInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (compactMiniPlayer) 8 else 10), 0, dp(if (compactMiniPlayer) 6 else 8), 0)
        }

        miniTitle = label("Choose a song", if (compactMiniPlayer) 12f else 13f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showTab("nowPlaying") }
        }
        miniArtist = label("Your library is ready", if (compactMiniPlayer) 10f else 11f, Color.rgb(190, 175, 210)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showTab("nowPlaying") }
        }
        miniInfo.addView(miniTitle)
        miniInfo.addView(miniArtist)
        miniPlayer.addView(miniInfo, LinearLayout.LayoutParams(0, -2, 1f))

        miniPlayButton = TextView(this).apply {
            text = "▶"
            textSize = if (compactMiniPlayer) 20f else 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(if (compactMiniPlayer) 10 else 14), dp(8), dp(if (compactMiniPlayer) 10 else 14), dp(8))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(171, 99, 255), Color.rgb(111, 56, 211))
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
                        // Stop video before resuming the retained last-played song.
                        videoPlayer?.apply {
                            pause()
                            stop()
                            clearMediaItems()
                        }
                        selectedVideoUri = null
                        navigationVideoUri = null
                        p.play()
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
        miniPlayer.addView(
            miniPlayButton,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )
        root.addView(
            miniPlayer,
            LinearLayout.LayoutParams(-1, dp(if (compactMiniPlayer) 56 else 62)).apply {
                bottomMargin = dp(if (compactMiniPlayer) 4 else 8)
            }
        )

        val composeNavigation = ComposeView(this).apply {
            setContent {
                A1NavigationBar(
                    selectedTab = selectedNavigationTab,
                    accentArgb = customization.accentArgb,
                    onTabSelected = { tab -> showTab(tab) }
                )
            }
        }
        root.addView(
            composeNavigation,
            LinearLayout.LayoutParams(-1, dp(68))
        )
        setContentView(root)
        applyCustomizationWallpaper()
        applyButtonFeedback(root)

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
                    searchQuery = s?.toString().orEmpty()
                    songRenderLimit = 40
                    videoRenderLimit = 24
                    clearSearchButton.visibility =
                        if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    // Avoid rebuilding dozens of Compose rows on every keystroke.
                    // Only the most recent query is rendered after a short pause.
                    searchRenderRunnable?.let(handler::removeCallbacks)
                    val query = s?.toString().orEmpty()
                    val targetTab = currentTab
                    val pendingRender = Runnable {
                        if (!isFinishing && !isDestroyed && currentTab == targetTab) {
                            if (targetTab == "videos") renderVideos(query) else renderSongs(query)
                        }
                    }
                    searchRenderRunnable = pendingRender
                    handler.postDelayed(pendingRender, 180L)
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
                    intArrayOf(Color.rgb(190, 111, 255), Color.rgb(112, 55, 205))
                ).apply { cornerRadius = dp(36).toFloat() }
            } else {
                roundedBackground(Color.rgb(43, 30, 59), 26)
            }
            // KIVORA_ROUND13_CONTROL_BORDER
            (background as android.graphics.drawable.GradientDrawable).setStroke(
                dp(if (isPrimary) 2 else 1),
                if (isPrimary) Color.rgb(235, 216, 255) else Color.rgb(126, 88, 157)
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
                content.addView(label("PRESET", 10f, Color.rgb(194, 163, 224), true).apply {
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
                val valueLabel = label("", 12f, Color.rgb(220, 198, 241))
                levelLabels.add(valueLabel)
                content.addView(label(frequencyLabel, 13f, Color.WHITE, true).apply {
                    setPadding(0, dp(10), 0, 0)
                })
                content.addView(valueLabel)
                val slider = SeekBar(this).apply {
                    max = (maxLevel - minLevel).coerceAtLeast(1)
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(196, 132, 255))
                    thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(228, 202, 255))
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

            var latestLevels = state.getIntArray(KivoraPlaybackService.KEY_LEVELS)?.toList().orEmpty()
            var latestEnabled = state.getBoolean(KivoraPlaybackService.KEY_ENABLED)
            val profileHeading = label("MY SOUND PROFILES", 10f, Color.rgb(194, 163, 224), true).apply {
                setPadding(0, dp(16), 0, dp(6))
            }
            content.addView(profileHeading)
            content.addView(label("Save your custom band settings and reuse them whenever you like.", 11f, Color.LTGRAY).apply {
                setPadding(0, 0, 0, dp(6))
            })
            val profileSpinner = android.widget.Spinner(this)
            fun refreshEqualizerProfiles(): List<A1EqualizerProfile> {
                val profiles = customizationStore.loadEqualizerProfiles()
                profileSpinner.adapter = android.widget.ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    profiles.map { it.name }
                )
                return profiles
            }
            var savedProfiles = refreshEqualizerProfiles()
            content.addView(profileSpinner, LinearLayout.LayoutParams(-1, dp(46)))
            fun profileAction(title: String, action: () -> Unit) = label(title, 11f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBackground(Color.rgb(58, 39, 78), 12)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            val profileActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            profileActions.addView(profileAction("Save current") {
                if (latestLevels.size != frequencies.size || latestLevels.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Play a song and adjust the equalizer first.", Toast.LENGTH_SHORT).show()
                } else {
                    val nameInput = EditText(this@MainActivity).apply {
                        hint = "e.g. Deep Bass, Clear Vocals"
                        setSingleLine(true)
                        setTextColor(Color.WHITE)
                        setHintTextColor(Color.GRAY)
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Save sound profile")
                        .setView(nameInput)
                        .setPositiveButton("Save") { _, _ ->
                            val name = nameInput.text?.toString()?.trim().orEmpty()
                            runCatching {
                                customizationStore.saveEqualizerProfile(
                                    A1EqualizerProfile(name, latestLevels, latestEnabled)
                                )
                            }.onSuccess {
                                savedProfiles = refreshEqualizerProfiles()
                                val selected = savedProfiles.indexOfFirst { it.name.equals(name.take(40), true) }
                                if (selected >= 0) profileSpinner.setSelection(selected)
                                Toast.makeText(this@MainActivity, "Sound profile saved on this device.", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, it.message ?: "Couldn't save sound profile.", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
            profileActions.addView(profileAction("Apply") {
                val profile = savedProfiles.getOrNull(profileSpinner.selectedItemPosition)
                if (profile == null) {
                    Toast.makeText(this@MainActivity, "Save a sound profile first.", Toast.LENGTH_SHORT).show()
                } else if (profile.levelsMillibels.size != frequencies.size) {
                    Toast.makeText(this@MainActivity, "This profile was made for a different equalizer. Save it again on this device.", Toast.LENGTH_LONG).show()
                } else {
                    sendEqualizerCommand(
                        KivoraPlaybackService.COMMAND_SET_ENABLED,
                        Bundle().apply { putBoolean(KivoraPlaybackService.KEY_ENABLED, profile.enabled) }
                    ) { updated ->
                        updateDialogState?.invoke(updated)
                        fun applyProfileBand(index: Int) {
                            if (index >= profile.levelsMillibels.size) {
                                Toast.makeText(this@MainActivity, "${profile.name} applied.", Toast.LENGTH_SHORT).show()
                                return
                            }
                            val level = profile.levelsMillibels[index].coerceIn(minLevel, maxLevel)
                            sendEqualizerCommand(
                                KivoraPlaybackService.COMMAND_SET_BAND,
                                Bundle().apply {
                                    putInt(KivoraPlaybackService.KEY_BAND, index)
                                    putInt(KivoraPlaybackService.KEY_LEVEL, level)
                                }
                            ) { nextState ->
                                updateDialogState?.invoke(nextState)
                                applyProfileBand(index + 1)
                            }
                        }
                        applyProfileBand(0)
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
            profileActions.addView(profileAction("Delete") {
                val profile = savedProfiles.getOrNull(profileSpinner.selectedItemPosition)
                if (profile == null) {
                    Toast.makeText(this@MainActivity, "Choose a saved profile first.", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete ${profile.name}?")
                        .setMessage("This removes the saved sound profile from this device.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            customizationStore.deleteEqualizerProfile(profile.name)
                            savedProfiles = refreshEqualizerProfiles()
                        }
                        .show()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
            content.addView(profileActions)

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
                latestEnabled = updated.getBoolean(KivoraPlaybackService.KEY_ENABLED)
                val levels = updated.getIntArray(KivoraPlaybackService.KEY_LEVELS) ?: return
                latestLevels = levels.toList()
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
                content.addView(label("STRENGTH", 10f, Color.rgb(194, 163, 224), true).apply {
                    setPadding(0, dp(12), 0, dp(4))
                })
                strengthValue = label("", 12f, Color.rgb(220, 198, 241))
                content.addView(strengthValue)
                strengthSlider = SeekBar(this).apply {
                    max = (maxStrength - minStrength).coerceAtLeast(1)
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(196, 132, 255))
                    thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(228, 202, 255))
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
                content.addView(label("Strength adjustment is unsupported on this device.", 12f, Color.rgb(174, 159, 194)))
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
            sleepTimerStatus = label("", 14f, Color.rgb(226, 202, 255), true).apply {
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
                        background = roundedBackground(Color.rgb(91, 52, 132), 14).apply {
                            setStroke(dp(1), Color.rgb(192, 132, 252))
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
                setTextColor(Color.rgb(255, 220, 235))
                background = roundedBackground(Color.rgb(72, 38, 63), 14).apply {
                    setStroke(dp(1), Color.rgb(210, 105, 160))
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

        favoriteFilterButton.text = if (showFavoritesOnly) "★  FAVOURITES ON" else "☆  FAVOURITES"

        favoriteFilterButton.setTextColor(
            if (showFavoritesOnly) {
                Color.rgb(250, 204, 21)
            } else {
                Color.rgb(216, 180, 254)
            }
        )

        // KIVORA_ROUND18_FAVOURITES_BORDER
        favoriteFilterButton.background = if (showFavoritesOnly) {
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(91, 65, 12), Color.rgb(56, 42, 15), Color.rgb(39, 29, 13))
            ).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), Color.rgb(250, 204, 21))
            }
        } else {
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(62, 37, 91), Color.rgb(37, 24, 55))
            ).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(160, 112, 205))
            }
        }
        favoriteFilterButton.elevation = dp(if (showFavoritesOnly) 5 else 2).toFloat()
        favoriteFilterButton.alpha = 1f
        favoriteFilterButton.minHeight = dp(48)
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

    private fun showAllSongs() {
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

    private fun updatePlaylistButton() {
        if (!::playlistButton.isInitialized) return
        playlistButton.text = if (selectedPlaylist != null) "▤  PLAYLIST: " + selectedPlaylist else "▤  PLAYLISTS"
        playlistButton.setTextColor(
            if (selectedPlaylist != null) Color.rgb(250, 204, 21) else Color.rgb(232, 213, 255)
        )
    }

    private fun updateLibraryBrowseButtons() {
        // Compose observes libraryBrowseMode and updates the selected segment automatically.
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
        homeContent.removeAllViews()

        val albumGroups = cachedAlbumGroups ?: songs.groupBy(::albumGroupKey).values
            .map { it.toList() }
            .sortedBy { it.first().album.lowercase(Locale.ROOT) }
            .also { cachedAlbumGroups = it }
        val albumCount = albumGroups.size
        homeContent.addView(
            ComposeView(this).apply {
                setContent {
                    A1HomeHeader(
                        songCount = songs.size,
                        albumCount = albumCount,
                        playlistCount = playlists.size,
                        accentArgb = customization.accentArgb,
                        onBrowse = { openHomeShortcut("songs") },
                        onAlbums = { openHomeShortcut("albums") },
                        onArtists = { openHomeShortcut("artists") },
                        onFavourites = { openHomeShortcut("favourites") }
                    )
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
        )

        val sections = linkedMapOf<String, () -> Unit>()
        sections["recent"] = {
            addHomeSection("Recently played", "YOUR RECENT LISTENS")
            val recentSongs = recentlyPlayedUris.mapNotNull { uri ->
                songs.firstOrNull { it.uri.toString() == uri }
            }
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
        }
        sections["albums"] = {
            addHomeSection("Albums", "FROM YOUR LIBRARY")
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
        }
        sections["playlists"] = {
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
        }

        customization.homeSectionOrder.forEach { section ->
            if (section !in customization.hiddenHomeSections) sections[section]?.invoke()
        }
        homeContent.addView(label("Tap the mini-player to open Now Playing", 11f, Color.rgb(164, 145, 186)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(15), 0, dp(3))
        })

        // Keep the existing native banner placement after user-configurable home sections.
        if (shouldLoadAdBanners()) {
            homeContent.addView(
                AdsterraHomeNativeBanner.create(this),
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(8)
                }
            )
        }
    }

    private fun applyNowPlayingSkin() {
        if (!::artworkFrame.isInitialized || !::artwork.isInitialized) return
        val skin = customization.visualizerSkin
        val frameShape = android.graphics.drawable.GradientDrawable().apply {
            shape = if (skin == "vinyl") android.graphics.drawable.GradientDrawable.OVAL
                else android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(if (skin == "cassette") 12 else 36).toFloat()
            colors = when (skin) {
                "vinyl" -> intArrayOf(Color.rgb(24, 22, 30), Color.rgb(79, 62, 94), Color.rgb(20, 18, 26))
                "cassette" -> intArrayOf(Color.rgb(91, 66, 105), Color.rgb(36, 29, 47))
                else -> intArrayOf(customization.accentArgb, customization.secondaryAccentArgb, Color.rgb(67, 43, 91))
            }
            orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            setStroke(dp(if (skin == "vinyl") 3 else 2), customization.accentArgb)
        }
        val artworkShape = android.graphics.drawable.GradientDrawable().apply {
            shape = if (skin == "vinyl") android.graphics.drawable.GradientDrawable.OVAL
                else android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(if (skin == "cassette") 7 else 26).toFloat()
            setColor(Color.rgb(52, 32, 75))
            setStroke(dp(1), customization.secondaryAccentArgb)
        }
        artworkFrame.background = frameShape
        artwork.background = artworkShape
        artwork.clipToOutline = true
        artworkFrame.clipToOutline = true
        artworkFrame.setPadding(dp(if (skin == "cassette") 8 else 5), dp(if (skin == "cassette") 8 else 5),
            dp(if (skin == "cassette") 8 else 5), dp(if (skin == "cassette") 8 else 5))
        artworkFrame.contentDescription = when (skin) {
            "vinyl" -> "Vinyl-style album artwork frame"
            "cassette" -> "Cassette-style album artwork frame"
            else -> "Wave-style album artwork frame"
        }
    }

    private fun persistCustomization(updated: A1CustomizationSettings) {
        customization = updated
        customizationStore.save(updated)
        if (::homeContent.isInitialized) renderHomeDashboard()
        applyNowPlayingSkin()
    }

    private fun applyCustomizationWallpaper() {
        val root = appRoot ?: return
        val wallpaper = customization.wallpaperUri
        if (wallpaper.isNullOrBlank()) {
            root.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(13, 8, 23), Color.rgb(24, 13, 39), Color.rgb(12, 10, 22))
            )
            return
        }
        val uri = runCatching { Uri.parse(wallpaper) }.getOrNull() ?: return
        val bitmap = runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            val target = (resources.displayMetrics.widthPixels * 1.5f).toInt().coerceAtLeast(720)
            var sample = 1
            while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) {
                sample *= 2
            }
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull() ?: return
        root.background = android.graphics.drawable.BitmapDrawable(resources, bitmap).apply {
            alpha = 92
        }
    }

    private fun openPersonalizationSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        content.addView(label("THEME STUDIO", 11f, Color.rgb(190, 160, 220), true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        fun colorInput(hintValue: String, value: Int) = EditText(this).apply {
            setSingleLine(true)
            hint = hintValue
            setText(String.format("#%06X", 0xFFFFFF and value))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = roundedBackground(Color.rgb(35, 25, 48), 12).apply {
                setStroke(dp(1), customization.secondaryAccentArgb)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val hexInput = colorInput("#D0A2FF", customization.accentArgb)
        val secondaryHexInput = colorInput("#765397", customization.secondaryAccentArgb)
        val paletteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val palettes = listOf(
            Triple("Lavender", 0xFFD0A2FF.toInt(), 0xFF765397.toInt()),
            Triple("Ocean", 0xFF55C8E8.toInt(), 0xFF245B91.toInt()),
            Triple("Emerald", 0xFF65D6A0.toInt(), 0xFF205C4A.toInt()),
            Triple("Sunset", 0xFFFFA36C.toInt(), 0xFF8A3D55.toInt()),
            Triple("Rose", 0xFFFF82B2.toInt(), 0xFF74365E.toInt())
        )
        palettes.forEach { (name, primary, secondary) ->
            paletteRow.addView(label("●", 24f, primary, true).apply {
                gravity = Gravity.CENTER
                contentDescription = "$name theme palette"
                minWidth = dp(48)
                minHeight = dp(48)
                setOnClickListener {
                    hexInput.setText(String.format("#%06X", 0xFFFFFF and primary))
                    secondaryHexInput.setText(String.format("#%06X", 0xFFFFFF and secondary))
                    persistCustomization(customization.copy(accentArgb = primary, secondaryAccentArgb = secondary))
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        content.addView(paletteRow)
        val colorInputs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        colorInputs.addView(hexInput, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) })
        colorInputs.addView(secondaryHexInput, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        content.addView(colorInputs)
        content.addView(label("Primary accent                         Secondary accent", 10f, Color.LTGRAY).apply {
            setPadding(dp(2), dp(4), 0, dp(8))
        })
        val themePreview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(Color.rgb(35, 25, 48), 16).apply {
                setStroke(dp(1), customization.secondaryAccentArgb)
            }
        }
        themePreview.addView(label("A1  THEME PREVIEW", 12f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        themePreview.addView(label("PLAY", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = roundedBackground(customization.accentArgb, 22)
        })
        content.addView(themePreview)
        content.addView(label("Choose a preset or enter two 6-digit HEX colours. Your theme is saved on this device.", 11f, Color.LTGRAY).apply {
            setPadding(0, dp(6), 0, dp(8))
        })

        content.addView(label("MY SAVED THEMES", 11f, Color.rgb(190, 160, 220), true).apply {
            setPadding(0, dp(14), 0, dp(8))
        })
        val themeNameInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Name this theme (e.g. My Night Mode)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = roundedBackground(Color.rgb(35, 25, 48), 12).apply {
                setStroke(dp(1), customization.secondaryAccentArgb)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(themeNameInput, LinearLayout.LayoutParams(-1, dp(50)))
        val savedThemeSpinner = android.widget.Spinner(this)
        fun refreshSavedThemes(): List<A1ThemeProfile> {
            val profiles = customizationStore.loadThemeProfiles()
            savedThemeSpinner.adapter = android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                profiles.map { it.name }
            )
            return profiles
        }
        var savedThemes = refreshSavedThemes()
        content.addView(savedThemeSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(6)
        })
        val themeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun themeActionButton(title: String, action: () -> Unit) =
            label(title, 11f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(46)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = roundedBackground(Color.rgb(58, 39, 78), 12)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        themeActions.addView(themeActionButton("Save theme") {
            val name = themeNameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                themeNameInput.error = "Enter a theme name"
            } else {
                runCatching {
                    customizationStore.saveThemeProfile(
                        A1ThemeProfile(
                            name,
                            customization.accentArgb,
                            customization.secondaryAccentArgb,
                            customization.wallpaperUri,
                            customization.homeSectionOrder,
                            customization.hiddenHomeSections,
                            customization.visualizerSkin,
                            customization.homeDensity,
                            customization.homeCardShape
                        )
                    )
                }.onSuccess {
                    savedThemes = refreshSavedThemes()
                    val selected = savedThemes.indexOfFirst { it.name.equals(name.take(40), ignoreCase = true) }
                    if (selected >= 0) savedThemeSpinner.setSelection(selected)
                    themeNameInput.text?.clear()
                    Toast.makeText(this, "Theme saved on this device.", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Could not save theme.", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        themeActions.addView(themeActionButton("Apply") {
            val profile = savedThemes.getOrNull(savedThemeSpinner.selectedItemPosition)
            if (profile == null) {
                Toast.makeText(this, "Save a theme first.", Toast.LENGTH_SHORT).show()
            } else {
                persistCustomization(customization.copy(
                    accentArgb = profile.accentArgb,
                    secondaryAccentArgb = profile.secondaryAccentArgb,
                    wallpaperUri = profile.wallpaperUri,
                    homeSectionOrder = profile.homeSectionOrder,
                    hiddenHomeSections = profile.hiddenHomeSections,
                    visualizerSkin = profile.visualizerSkin,
                    homeDensity = profile.homeDensity,
                    homeCardShape = profile.homeCardShape
                ))
                applyCustomizationWallpaper()
                hexInput.setText(String.format("#%06X", 0xFFFFFF and profile.accentArgb))
                secondaryHexInput.setText(String.format("#%06X", 0xFFFFFF and profile.secondaryAccentArgb))
                Toast.makeText(this, "${profile.name} applied.", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        themeActions.addView(themeActionButton("Delete") {
            val profile = savedThemes.getOrNull(savedThemeSpinner.selectedItemPosition)
            if (profile == null) {
                Toast.makeText(this, "Choose a saved theme first.", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Delete ${profile.name}?")
                    .setMessage("This removes the saved theme profile from this device. It does not change your current appearance.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        customizationStore.deleteThemeProfile(profile.name)
                        savedThemes = refreshSavedThemes()
                    }
                    .show()
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        content.addView(themeActions)

        content.addView(label("HOME SCREEN SECTIONS", 11f, Color.rgb(190, 160, 220), true).apply {
            setPadding(0, dp(14), 0, dp(8))
        })
        val sectionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sectionList)
        var order = customization.homeSectionOrder.toMutableList()
        var hidden = customization.hiddenHomeSections.toMutableSet()
        fun refreshSectionOptions() {
            sectionList.removeAllViews()
            val titles = mapOf("recent" to "Recently played", "albums" to "Albums", "playlists" to "Playlists")
            order.forEachIndexed { index, id ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val toggle = android.widget.CheckBox(this).apply {
                    text = titles[id] ?: id
                    setTextColor(Color.WHITE)
                    isChecked = id !in hidden
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) hidden.remove(id) else hidden.add(id)
                        persistCustomization(customization.copy(hiddenHomeSections = hidden.toSet()))
                    }
                }
                row.addView(toggle, LinearLayout.LayoutParams(0, dp(48), 1f))
                row.addView(label("↑", 20f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(42)
                    minHeight = dp(44)
                    contentDescription = "Move ${titles[id]} up"
                    setOnClickListener {
                        if (index > 0) {
                            val item = order.removeAt(index)
                            order.add(index - 1, item)
                            persistCustomization(customization.copy(homeSectionOrder = order.toList()))
                            refreshSectionOptions()
                        }
                    }
                })
                row.addView(label("↓", 20f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(42)
                    minHeight = dp(44)
                    contentDescription = "Move ${titles[id]} down"
                    setOnClickListener {
                        if (index < order.lastIndex) {
                            val item = order.removeAt(index)
                            order.add(index + 1, item)
                            persistCustomization(customization.copy(homeSectionOrder = order.toList()))
                            refreshSectionOptions()
                        }
                    }
                })
                sectionList.addView(row)
            }
        }
        refreshSectionOptions()

        content.addView(label("HOME CARD LAYOUT", 11f, Color.rgb(190, 160, 220), true).apply {
            setPadding(0, dp(14), 0, dp(8))
        })
        content.addView(label("Choose how much space your home cards use.", 11f, Color.LTGRAY))
        val densitySpinner = android.widget.Spinner(this)
        val densityValues = listOf("Compact", "Comfortable", "Spacious")
        densitySpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, densityValues
        )
        densitySpinner.setSelection(
            listOf("compact", "comfortable", "spacious").indexOf(customization.homeDensity).coerceAtLeast(0)
        )
        content.addView(densitySpinner, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(4)
        })
        densitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = listOf("compact", "comfortable", "spacious").getOrNull(position) ?: "comfortable"
                if (customization.homeDensity != value) {
                    persistCustomization(customization.copy(homeDensity = value))
                }
            }
        }

        content.addView(label("CARD CORNERS", 11f, Color.rgb(190, 160, 220), true).apply {
            setPadding(0, dp(10), 0, dp(6))
        })
        val shapeSpinner = android.widget.Spinner(this)
        val shapeValues = listOf("Rounded", "Soft", "Square")
        shapeSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, shapeValues
        )
        shapeSpinner.setSelection(
            listOf("rounded", "soft", "square").indexOf(customization.homeCardShape).coerceAtLeast(0)
        )
        content.addView(shapeSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        shapeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = listOf("rounded", "soft", "square").getOrNull(position) ?: "rounded"
                if (customization.homeCardShape != value) {
                    persistCustomization(customization.copy(homeCardShape = value))
                }
            }
        }

        content.addView(label("NOW PLAYING ARTWORK STYLE", 11f, Color.rgb(190, 160, 220), true).apply {
            setPadding(0, dp(14), 0, dp(8))
        })
        content.addView(label("Change the frame around your current album artwork.", 11f, Color.LTGRAY))
        val skinSpinner = android.widget.Spinner(this)
        val skinLabels = listOf("Wave", "Vinyl", "Cassette")
        val skinValues = listOf("wave", "vinyl", "cassette")
        skinSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, skinLabels
        )
        skinSpinner.setSelection(skinValues.indexOf(customization.visualizerSkin).coerceAtLeast(0))
        content.addView(skinSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(4)
        })
        skinSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = skinValues.getOrNull(position) ?: "wave"
                if (customization.visualizerSkin != value) {
                    persistCustomization(customization.copy(visualizerSkin = value))
                }
            }
        }

        content.addView(label("HOME WALLPAPER", 11f, Color.rgb(190, 160, 220), true).apply {
            setPadding(0, dp(14), 0, dp(8))
        })
        content.addView(label("Choose an image stored on your device. It stays local to this app.", 11f, Color.LTGRAY))
        val wallpaperRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wallpaperRow.addView(label("Choose wallpaper", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(Color.rgb(58, 39, 78), 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { wallpaperPickerLauncher.launch(arrayOf("image/*")) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        wallpaperRow.addView(label("Reset", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                persistCustomization(customization.copy(wallpaperUri = null))
                applyCustomizationWallpaper()
            }
        })
        content.addView(wallpaperRow)
        content.addView(label("Save a complete style to reuse your colours, wallpaper, home layout, card spacing, corner shape and Now Playing artwork together.", 11f, Color.rgb(178, 160, 195)).apply {
            setPadding(0, dp(14), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Make A1 yours")
            .setView(scroll)
            .setPositiveButton("Apply color") { _, _ ->
                val raw = hexInput.text?.toString()?.trim().orEmpty()
                val normalized = if (raw.startsWith("#")) raw else "#$raw"
                val secondaryRaw = secondaryHexInput.text?.toString()?.trim().orEmpty()
                val secondaryNormalized = if (secondaryRaw.startsWith("#")) secondaryRaw else "#$secondaryRaw"
                val parsed = runCatching { Color.parseColor(normalized) }.getOrNull()
                val secondaryParsed = runCatching { Color.parseColor(secondaryNormalized) }.getOrNull()
                if (parsed == null || secondaryParsed == null) {
                    Toast.makeText(this, "Enter valid HEX colours, for example #D0A2FF and #765397.", Toast.LENGTH_LONG).show()
                } else {
                    persistCustomization(customization.copy(accentArgb = parsed, secondaryAccentArgb = secondaryParsed))
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
        heading.addView(label(title, 19f, Color.rgb(250, 246, 255), true),
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label(caption, 9f, Color.rgb(179, 150, 208), true).apply {
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

    private fun homeCardWidthDp(): Int {
        val screenWidth = resources.configuration.screenWidthDp
        return when (customization.homeDensity) {
            "compact" -> (screenWidth - 76).coerceIn(132, 170)
            "spacious" -> (screenWidth - 48).coerceIn(176, 240)
            else -> (screenWidth - 64).coerceIn(150, 210)
        }
    }

    private fun homeArtworkHeightDp(): Int = when (customization.homeDensity) {
        "compact" -> (homeCardWidthDp() - 22).coerceAtLeast(108)
        "spacious" -> (homeCardWidthDp() - 12).coerceAtLeast(150)
        else -> (homeCardWidthDp() - 20).coerceAtLeast(128)
    }

    private fun homeCardPaddingDp(): Int = when (customization.homeDensity) {
        "compact" -> 7
        "spacious" -> 13
        else -> 10
    }

    private fun homeCardCornerDp(): Int = when (customization.homeCardShape) {
        "square" -> 5
        "soft" -> 12
        else -> 18
    }

    private fun homeArtworkCornerDp(): Int = when (customization.homeCardShape) {
        "square" -> 3
        "soft" -> 9
        else -> 14
    }

    private fun homeCardParams() = LinearLayout.LayoutParams(dp(homeCardWidthDp()), -2)
        .apply { rightMargin = dp(10) }

    private fun homeSongCard(song: Song, title: String, subtitle: String, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(homeCardPaddingDp()), dp(homeCardPaddingDp()), dp(homeCardPaddingDp()), dp(homeCardPaddingDp() + 1))
            background = roundedBackground(Color.rgb(37, 25, 53), homeCardCornerDp()).apply {
                setStroke(dp(1), customization.secondaryAccentArgb)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "$title, $subtitle"
            setOnClickListener { onClick() }
        }
        val cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(Color.rgb(55, 34, 75), homeArtworkCornerDp()).apply {
                setStroke(dp(1), customization.accentArgb)
            }
            clipToOutline = true
            contentDescription = "Artwork for ${song.title}"
        }
        card.addView(cover, LinearLayout.LayoutParams(-1, dp(homeArtworkHeightDp())))
        loadArtworkInto(cover, song)
        card.addView(label(title, 14f, Color.rgb(250, 246, 255), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(1), dp(9), dp(1), 0)
        })
        card.addView(label(subtitle, 11f, Color.rgb(187, 166, 210)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(1), dp(3), dp(1), 0)
        })
        return card
    }

    private fun homePlaylistCard(name: String, count: Int, representative: Song?, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(homeCardPaddingDp()), dp(homeCardPaddingDp()), dp(homeCardPaddingDp()), dp(homeCardPaddingDp() + 1))
            background = roundedBackground(Color.rgb(37, 25, 53), homeCardCornerDp()).apply {
                setStroke(dp(1), customization.secondaryAccentArgb)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Playlist $name, $count songs"
            setOnClickListener { onClick() }
        }
        val cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(Color.rgb(55, 34, 75), homeArtworkCornerDp()).apply {
                setStroke(dp(1), customization.accentArgb)
            }
            clipToOutline = true
            contentDescription = "Playlist artwork for $name"
        }
        card.addView(cover, LinearLayout.LayoutParams(-1, dp(homeArtworkHeightDp())))
        representative?.let { loadArtworkInto(cover, it) }
        card.addView(label(name, 14f, Color.rgb(250, 246, 255), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(1), dp(9), dp(1), 0)
        })
        card.addView(label("$count ${if (count == 1) "song" else "songs"}", 11f, Color.rgb(187, 166, 210)).apply {
            setPadding(dp(1), dp(3), dp(1), 0)
        })
        return card
    }

    private fun homeEmptyCard(message: String): View = label(message, 13f, Color.rgb(183, 163, 204)).apply {
        setPadding(dp(15), dp(16), dp(15), dp(16))
        background = roundedBackground(Color.rgb(31, 22, 44), 16).apply {
            setStroke(dp(1), customization.secondaryAccentArgb)
        }
    }

    private fun loadSongs() {
        val requestId = ++musicScanRequestId
        musicScanExecutor.execute {
            val scannedSongs = mutableListOf<Song>()
            var scanError: String? = null
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
                val cursor = contentResolver.query(
                    collection, projection,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null, "${MediaStore.Audio.Media.TITLE} ASC"
                ) ?: throw IllegalStateException("MediaStore returned no music cursor")
                cursor.use {
                    val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val displayNameColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumNameColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    while (it.moveToNext()) {
                        val id = it.getLong(idColumn)
                        val rawTitle = it.getString(titleColumn)?.trim().orEmpty()
                        val displayName = it.getString(displayNameColumn)?.trim().orEmpty()
                        val fileTitle = displayName.substringBeforeLast('.', displayName)
                            .replace(Regex("[._-]+"), " ").trim().ifBlank { "Unknown title" }
                        val title = if (rawTitle.isBlank() ||
                            rawTitle.equals("unknown", true) ||
                            rawTitle.equals("unknown title", true)) fileTitle else rawTitle
                        val artist = it.getString(artistColumn)?.takeIf { value ->
                            value.isNotBlank() && !value.equals("<unknown>", true) &&
                                !value.equals("unknown", true)
                        } ?: "Unknown artist"
                        val albumId = it.getLong(albumColumn)
                        val album = it.getString(albumNameColumn)?.trim()?.takeIf { value ->
                            value.isNotBlank() && !value.equals("<unknown>", true) &&
                                !value.equals("unknown", true)
                        } ?: "Unknown album"
                        val artUri = if (albumId > 0L) ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"), albumId
                        ) else null
                        scannedSongs.add(Song(
                            title, artist, album, albumId,
                            ContentUris.withAppendedId(collection, id), artUri
                        ))
                    }
                }
            } catch (error: Exception) {
                scanError = error.message?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName
            }
            handler.post {
                if (requestId != musicScanRequestId || isFinishing || isDestroyed) return@post
                if (scanError != null) {
                    Toast.makeText(this, "Couldn't refresh music. Your existing library was kept.", Toast.LENGTH_LONG).show()
                    return@post
                }
                songs.clear()
                songs.addAll(scannedSongs)
                cachedAlbumGroups = null
                normalizedSearchText.clear()
                syncPlaylistToPlayer()
                recordRecentlyPlayed(player?.currentMediaItem)
                renderHomeDashboard()
                renderSongs(searchBox.text?.toString().orEmpty())
            }
        }
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

        // Build the lookup once: the previous nested scan became quadratic for large libraries.
        // Indexed lookup preserves the old first-match behavior for duplicate URIs.
        val songsByUri = firstItemByKey(songs) { it.uri.toString() }
        val existingQueue = (0 until p.mediaItemCount).mapNotNull { index ->
            val uri = p.getMediaItemAt(index)
                .localConfiguration?.uri?.toString()
            uri?.let(songsByUri::get)
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
                    Color.rgb(174, 159, 194)
                ).apply {
                    setPadding(dp(4), dp(18), dp(4), dp(18))
                    // KIVORA_ROUND23_EMPTY_LIBRARY_CARD
                    background = roundedBackground(Color.rgb(34, 16, 56), 18).apply { setStroke(dp(1), Color.rgb(145, 95, 190)) }
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
            val backButton = label("‹  ALL $modeTitle", 12f, Color.rgb(224, 197, 248), true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedBackground(Color.rgb(44, 29, 62), 15).apply {
                    setStroke(dp(1), Color.rgb(104, 73, 134))
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
            songList.addView(label("No songs match this selection.", 14f, Color.rgb(190, 175, 210)).apply {
                setPadding(dp(16), dp(18), dp(16), dp(18))
                background = roundedBackground(Color.rgb(34, 16, 56), 18).apply {
                    setStroke(dp(1), Color.rgb(115, 76, 151))
                }
            })
            return
        }

        visibleSongs.take(songRenderLimit).forEachIndexed { songIndex, song ->
            val uri = song.uri.toString()

            songList.addView(
                ComposeView(this).apply {
                    setContent {
                        A1SongRow(
                            song = song,
                            isFavorite = favoriteSongUris.contains(uri),
                            isInPlaylist = selectedPlaylist != null,
                            onArtworkReady = { imageView -> loadArtworkInto(imageView, song) },
                            onPlay = { playSong(song) },
                            onFavorite = { toggleFavorite(song) },
                            onAddToPlaylist = { showAddToPlaylistDialog(song) },
                            onRemoveFromPlaylist = { removeFromPlaylist(song) },
                            onMore = { showQueueActions(song) }
                        )
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                }
            )

            // Original 320x50 banner: one placement after song 4, only for libraries over 50 songs.
            if (shouldLoadAdBanners() && visibleSongs.size > 50 && songIndex == 3 && visibleSongs.size > 8) {
                songList.addView(
                    AdsterraSongListBanner.create(this),
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(2)
                        bottomMargin = dp(4)
                    }
                )
            }
        }
        if (visibleSongs.size > songRenderLimit) {
            val remaining = visibleSongs.size - songRenderLimit
            songList.addView(label("LOAD MORE SONGS  ·  $remaining REMAINING", 12f, Color.rgb(231, 210, 255), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(15), dp(18), dp(15))
                background = roundedBackground(Color.rgb(54, 33, 76), 16).apply {
                    setStroke(dp(1), Color.rgb(169, 118, 216))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    songRenderLimit += 40
                    renderSongs(searchBox.text?.toString().orEmpty())
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(12) })
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
        songList.addView(
            ComposeView(this).apply {
                setContent {
                    A1CollectionRow(
                        title = title,
                        subtitle = subtitle,
                        description = description,
                        coverSong = coverSong,
                        onArtworkReady = { imageView -> loadArtworkInto(imageView, coverSong) },
                        onClick = onClick
                    )
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(9)
            }
        )
    }

    /** Shared by the real music start path and the playback regression test. */
    internal fun stopVideoForMusicPlayback() {
        videoPlayer?.apply {
            pause()
            stop()
            clearMediaItems()
        }
        selectedVideoUri = null
        navigationVideoUri = null
        if (::videoStatus.isInitialized) {
            videoStatus.text = if (deviceVideos.isEmpty()) "No videos yet" else "Choose a video to play."
            renderVideos(searchBox.text?.toString().orEmpty())
        }
    }

    /** Shared by the real video start path and the playback regression test. */
    internal fun pauseMusicForVideoPlayback() {
        player?.pause()
        updateNowPlaying()
        updatePlayButton()
    }

    private fun playSong(song: Song) {
        val p = player

        if (p == null) {
            Toast.makeText(
                this,
                "Connecting to music service. Please wait.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

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

        // Enforce a single active playback source before starting music.
        stopVideoForMusicPlayback()
        p.setMediaItems(makeMediaItems(activeQueue), startIndex, 0L)
        p.prepare()
        p.play()

        updateNowPlaying()
        updatePlayButton()
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
                background = roundedBackground(Color.rgb(72, 42, 101), 14).apply {
                    setStroke(dp(1), Color.rgb(167, 112, 220))
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
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        content.addView(actionButton("Track memory & mood", "Add a personal note or mood for ${song.title}") {
            openTrackMemoryDialog(song)
        }, LinearLayout.LayoutParams(-1, dp(48)))

        dialog.show()
    }

    private fun openTrackMemoryDialog(song: Song) {
        val key = song.uri.toString()
        val saved = customizationStore.loadTrackMemory(key)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val moodInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Mood (e.g. Relaxed, Focused, Joyful)"
            setText(saved.mood)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = roundedBackground(Color.rgb(35, 25, 48), 12).apply {
                setStroke(dp(1), customization.secondaryAccentArgb)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val noteInput = EditText(this).apply {
            hint = "Your personal memory about this song..."
            setText(saved.note)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP
            background = roundedBackground(Color.rgb(35, 25, 48), 12).apply {
                setStroke(dp(1), customization.secondaryAccentArgb)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(label("MOOD", 10f, Color.rgb(190, 160, 220), true))
        content.addView(moodInput, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(10) })
        content.addView(label("PERSONAL NOTE", 10f, Color.rgb(190, 160, 220), true))
        content.addView(noteInput, LinearLayout.LayoutParams(-1, dp(116)).apply { bottomMargin = dp(8) })
        content.addView(
            label(
                if (saved.photoUri != null) "PHOTO ATTACHED · STORED ON THIS DEVICE" else "OPTIONAL PHOTO · STORED ON THIS DEVICE",
                10f,
                Color.rgb(190, 160, 220),
                true
            )
        )
        AlertDialog.Builder(this)
            .setTitle("Your memory · ${song.title}")
            .setView(content)
            .setPositiveButton("Save memory") { _, _ ->
                customizationStore.saveTrackMemory(
                    key,
                    A1TrackMemory(
                        note = noteInput.text?.toString().orEmpty().trim(),
                        mood = moodInput.text?.toString().orEmpty().trim(),
                        photoUri = saved.photoUri
                    )
                )
                Toast.makeText(this, "Track memory saved on this device.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (saved.photoUri != null) "Change photo" else "Attach photo") { _, _ ->
                customizationStore.saveTrackMemory(
                    key,
                    A1TrackMemory(
                        note = noteInput.text?.toString().orEmpty().trim(),
                        mood = moodInput.text?.toString().orEmpty().trim(),
                        photoUri = saved.photoUri
                    )
                )
                pendingTrackMemorySong = song
                trackMemoryPhotoPicker.launch(arrayOf("image/*"))
            }
            .setNegativeButton("Clear") { _, _ ->
                customizationStore.saveTrackMemory(key, A1TrackMemory())
                Toast.makeText(this, "Track memory cleared.", Toast.LENGTH_SHORT).show()
            }
            .show()
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
        val summary = label("", 13f, Color.rgb(205, 177, 231), true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(8), dp(12))
        }
        upNextSummary = summary
        val clearButton = label("Clear upcoming", 12f, Color.rgb(255, 166, 197), true).apply {
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
                background = roundedBackground(Color.rgb(62, 38, 83), 17).apply {
                    setStroke(dp(2), Color.rgb(177, 117, 231))
                }
                contentDescription = "Currently playing: $title"
            }
            val cover = ImageView(this).apply {
                setImageResource(R.drawable.ic_audio_placeholder)
                background = roundedBackground(Color.rgb(57, 37, 78), 11)
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
            copy.addView(label("NOW PLAYING", 10f, Color.rgb(213, 171, 255), true))
            copy.addView(label(title, 14f, Color.WHITE, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
            copy.addView(label(artist, 11f, Color.rgb(205, 187, 219)).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            currentRow.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            currentRow.addView(label("♫", 20f, Color.rgb(213, 171, 255), true).apply {
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
                Color.rgb(190, 169, 211)
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(22), dp(14), dp(22))
                background = roundedBackground(Color.rgb(35, 23, 49), 18).apply {
                    setStroke(dp(1), Color.rgb(108, 73, 139))
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
                background = roundedBackground(Color.rgb(35, 24, 48), 16).apply {
                    setStroke(dp(1), Color.rgb(77, 54, 99))
                }
                isClickable = song != null
                isFocusable = song != null
                contentDescription = if (song != null) "Play $title next" else "$title is unavailable"
                setOnClickListener {
                    if (song == null) showUnavailableSong() else playQueuedItem(mediaIndex)
                }
            }
            row.addView(label("${queuePosition + 1}", 12f, Color.rgb(196, 153, 236), true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(28)
            })
            val cover = ImageView(this).apply {
                setImageResource(R.drawable.ic_audio_placeholder)
                background = roundedBackground(Color.rgb(57, 37, 78), 11).apply {
                    setStroke(dp(1), Color.rgb(111, 75, 146))
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
            copy.addView(label(artist, 11f, Color.rgb(187, 168, 207)).apply {
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
            row.addView(label("×", 24f, Color.rgb(255, 166, 197), true).apply {
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
        val p = player ?: return showQueueUnavailable()
        if (mediaIndex !in 0 until p.mediaItemCount) {
            renderUpNextQueue()
            return
        }
        val item = p.getMediaItemAt(mediaIndex)
        if (songForMediaItem(item) == null) return showUnavailableSong()
        try {
            p.seekTo(mediaIndex, 0L)
            p.play()
            upNextDialog?.dismiss()
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
    ): TextView = label(text, 18f, Color.rgb(213, 171, 255), true).apply {
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
        playButton.background = roundedBackground(customization.accentArgb, 18)
        if (::miniPlayButton.isInitialized) {
            miniPlayButton.background = roundedBackground(customization.accentArgb, 16)
        }
    }

    private fun updateShuffleButton() {
        if (!::shuffleButton.isInitialized) return

        shuffleButton.setTextColor(
            if (player?.shuffleModeEnabled == true) {
                customization.accentArgb
            } else {
                Color.rgb(174, 159, 194)
            }
        )
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

        repeatButton.setTextColor(
            if (enabled) {
                Color.rgb(196, 145, 255)
            } else {
                Color.rgb(174, 159, 194)
            }
        )
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
        musicScanRequestId++
        videoScanRequestId++
        searchRenderRunnable?.let(handler::removeCallbacks)
        searchRenderRunnable = null
        musicScanExecutor.shutdownNow()
        videoScanExecutor.shutdownNow()
        videoThumbnailExecutor.shutdownNow()
        artworkExecutor.shutdownNow()

        handler.removeCallbacks(progressUpdater)
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

        // Invalidate pending artwork callbacks and stop the activity-owned worker.
        artworkRequestId++
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
        val requestId = ++videoScanRequestId
        videoStatus.visibility = View.VISIBLE
        videoStatus.text = "Scanning videos on this device..."
        videoStatus.setOnClickListener(null)
        videoScanExecutor.execute {
            val scannedVideos = mutableListOf<DeviceVideo>()
            var scanError: String? = null
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION
            )
            try {
                val cursor = contentResolver.query(
                    collection, projection, null, null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                ) ?: throw IllegalStateException("MediaStore returned no video cursor")
                cursor.use {
                    val idColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val titleColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                    val nameColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durationColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    while (it.moveToNext()) {
                        val id = it.getLong(idColumn)
                        val title = it.getString(titleColumn)?.takeIf { value -> value.isNotBlank() }
                            ?: it.getString(nameColumn)?.takeIf { value -> value.isNotBlank() }
                            ?: "Untitled video"
                        scannedVideos.add(DeviceVideo(
                            id, title, ContentUris.withAppendedId(collection, id),
                            it.getLong(durationColumn)
                        ))
                    }
                }
            } catch (error: Exception) {
                scanError = error.message?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName
            }
            handler.post {
                if (requestId != videoScanRequestId || isFinishing || isDestroyed) return@post
                if (scanError != null) {
                    videoStatus.text = "Couldn't refresh videos. Your previous list was kept. Tap to retry."
                    videoStatus.setOnClickListener { loadVideos() }
                    Toast.makeText(this, "Video library couldn't be refreshed.", Toast.LENGTH_LONG).show()
                    return@post
                }
                deviceVideos.clear()
                deviceVideos.addAll(scannedVideos)
                videosLoaded = true
                renderVideos()
            }
        }
    }

    private fun loadVideoThumbnail(video: DeviceVideo, imageView: ImageView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val key = video.uri.toString()
        imageView.tag = key
        val cached = videoThumbnailCache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }
        val shouldDecode = synchronized(videoThumbnailTargets) {
            val targets = videoThumbnailTargets.getOrPut(key) { mutableListOf() }
            targets.add(imageView)
            targets.size == 1
        }
        if (!shouldDecode) return
        val width = dp(112).coerceAtLeast(1)
        val height = dp(76).coerceAtLeast(1)
        videoThumbnailExecutor.execute {
            val bitmap = try {
                contentResolver.loadThumbnail(video.uri, Size(width, height), null)
            } catch (_: Exception) { null }
            handler.post {
                if (bitmap != null && !isFinishing && !isDestroyed) videoThumbnailCache.put(key, bitmap)
                val targets = synchronized(videoThumbnailTargets) {
                    videoThumbnailTargets.remove(key).orEmpty()
                }
                if (bitmap != null && !isFinishing && !isDestroyed) {
                    targets.forEach { target ->
                        if (target.tag == key) target.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun renderVideos(query: String = searchBox.text?.toString().orEmpty()) {
        videoList.removeAllViews()

        if (deviceVideos.isEmpty()) {
            videoPlayer?.apply {
                pause()
                stop()
                clearMediaItems()
            }
            selectedVideoUri = null
            videoPlaceholder.visibility = View.GONE
            videoPlayerView.visibility = View.GONE
            if (currentTab == "videos") homeScroll.visibility = View.VISIBLE
            videoStatus.text = "Your video library is ready when you are."
            videoList.addView(label("No videos yet", 18f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(28), dp(20), dp(28))
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }

        val filteredVideos = filterItemsByTitle(deviceVideos, query) { it.title }
        if (filteredVideos.isEmpty()) {
            videoStatus.visibility = View.VISIBLE
            videoStatus.text = "No videos match your search."
            return
        }
        // With no active selection, let the thumbnail list occupy the full video panel.
        videoStatus.visibility = if (selectedVideoUri == null) View.GONE else View.VISIBLE
        videoPlaceholder.visibility = if (currentTab == "videos") View.VISIBLE else View.GONE
        if (currentTab == "videos") homeScroll.visibility = View.GONE
        videoStatus.text = "${deviceVideos.count { it.title.contains(query.trim(), ignoreCase = true) }} matching videos"

        filteredVideos.take(videoRenderLimit).forEach { video ->
            videoList.addView(
                ComposeView(this).apply {
                    setContent {
                        A1VideoRow(
                            title = video.title,
                            duration = formatTime(video.durationMs),
                            isSelected = video.uri.toString() == selectedVideoUri,
                            onThumbnailReady = { imageView ->
                                loadVideoThumbnail(video, imageView)
                            },
                            onPlay = { playDeviceVideo(video) }
                        )
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                }
            )
        }
        if (filteredVideos.size > videoRenderLimit) {
            val remaining = filteredVideos.size - videoRenderLimit
            videoList.addView(label("LOAD MORE VIDEOS  ·  $remaining REMAINING", 12f, Color.rgb(231, 210, 255), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(15), dp(18), dp(15))
                background = roundedBackground(Color.rgb(54, 33, 76), 16).apply {
                    setStroke(dp(1), Color.rgb(169, 118, 216))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    videoRenderLimit += 24
                    renderVideos(query)
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(12) })
        }
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
            setTextColor(Color.rgb(245, 238, 255))
            setHintTextColor(Color.rgb(151, 132, 174))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = roundedBackground(Color.rgb(24, 16, 36), 16).apply {
                setStroke(dp(1), Color.rgb(103, 70, 132))
            }
            contentDescription = "Lyrics editor. Type or paste lyrics you have permission to use."
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(6))
            addView(label("NOW EDITING", 10f, Color.rgb(66, 224, 255), true).apply {
                letterSpacing = 0.12f
                setPadding(0, 0, 0, dp(7))
            })
            addView(label(song.title, 18f, Color.rgb(245, 238, 255), true))
            addView(label(song.artist.ifBlank { "Unknown artist" }, 12f, Color.rgb(192, 173, 214)).apply {
                setPadding(0, dp(4), 0, dp(14))
            })
            addView(label("YOUR LYRICS", 10f, Color.rgb(194, 165, 224), true).apply {
                letterSpacing = 0.1f
                setPadding(0, 0, 0, dp(7))
            })
            addView(lyrics, LinearLayout.LayoutParams(-1, dp(320)))
            addView(label("Lyrics are saved on this device for this track.", 11f, Color.rgb(160, 143, 181)).apply {
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

    private fun stopInlineVideo() {
        videoPlayer?.apply {
            playWhenReady = false
            pause()
            stop()
            clearMediaItems()
        }
        selectedVideoUri = null
        navigationVideoUri = null
        lastFailedVideoUri = null
        videoPlayerView.visibility = View.GONE
        videoPlaceholder.findViewWithTag<View>("video_player_actions")?.visibility = View.GONE
        videoStatus.text = "Video stopped. Choose another video to play."
        videoStatus.visibility = View.VISIBLE
        renderVideos(searchBox.text?.toString().orEmpty())
        Toast.makeText(this, "Video stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun playDeviceVideo(video: DeviceVideo): Boolean {
        if (!isVideoAvailable(video)) {
            Toast.makeText(this, "This video is unavailable.", Toast.LENGTH_SHORT).show()
            return false
        }
        selectedVideoUri = video.uri.toString()
        navigationVideoUri = video.uri.toString()
        videoPlayerView.visibility = View.VISIBLE
        videoPlaceholder.findViewWithTag<View>("video_player_actions")?.visibility = View.VISIBLE
        lastFailedVideoUri = null
        renderVideos(searchBox.text?.toString().orEmpty())
        videoStatus.text = "Playing: ${video.title}"
        // Pause music but retain its current item and queue for the mini player.
        pauseMusicForVideoPlayback()
        return try {
            videoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(video.uri))
                setPlaybackParameters(PlaybackParameters(savedVideoSpeed()))
                prepare()
                playWhenReady = true
            } != null
        } catch (_: RuntimeException) {
            reportVideoPlaybackFailure(video.uri.toString())
            false
        }
    }

    private fun cinemaPreferences() = getSharedPreferences("a1_cinema_preferences", MODE_PRIVATE)

    private fun savedVideoSpeed(): Float = cinemaPreferences().getFloat("video_speed", 1f).coerceIn(0.5f, 2f)

    private fun savedSubtitleTextSize(): Int = cinemaPreferences().getInt("subtitle_text_size_sp", 22).coerceIn(16, 36)

    private fun applyCinemaSubtitleSize() {
        if (::videoPlayerView.isInitialized) {
            videoPlayerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, savedSubtitleTextSize().toFloat())
        }
    }

    /** Keep the inline player close to 16:9 and scale it for phones, tablets and split-screen. */
    private fun videoPreviewHeight(): Int {
        val configuration = resources.configuration
        val screenWidthDp = configuration.screenWidthDp.coerceAtLeast(1)
        val screenHeightDp = configuration.screenHeightDp.coerceAtLeast(1)
        // Give tablets a larger preview, but never let video consume the whole short phone screen.
        val maxHeightDp = (screenHeightDp * 0.42f).toInt().coerceIn(160, 380)
        val desiredHeightDp = (screenWidthDp * 9f / 16f).toInt()
        return dp(desiredHeightDp.coerceIn(140, maxHeightDp))
    }

    /** Move the existing PlayerView into fullscreen without recreating its player. */
    @OptIn(UnstableApi::class)
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
                intArrayOf(Color.rgb(25, 15, 37), Color.TRANSPARENT)
            )
        }
        val topControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var showControls: (() -> Unit)? = null
        fun control(text: String, description: String, onClick: () -> Unit) =
            label(text, 18f, Color.rgb(245, 235, 255), true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(48)
                minHeight = dp(48)
                contentDescription = description
                background = roundedBackground(Color.rgb(54, 35, 73), 14)
                setOnClickListener {
                    showControls?.invoke()
                    onClick()
                }
            }
        val backButton = control("‹", "Back to video screen") { dialog.dismiss() }
        val closeVideoButton = control("✕", "Close video completely") {
            videoPlayer?.apply {
                stop()
                clearMediaItems()
            }
            selectedVideoUri = null
            navigationVideoUri = null
            videoStatus.text = "Video closed. Choose a video to play."
            renderVideos(searchBox.text?.toString().orEmpty())
            dialog.dismiss()
        }.apply {
            setTextColor(Color.rgb(255, 105, 105))
            background = roundedBackground(Color.rgb(75, 25, 35), 14)
        }
        val menuButton = label("☰", 18f, Color.rgb(245, 235, 255), true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "Open video list"
            background = roundedBackground(Color.rgb(54, 35, 73), 14)
        }
        val currentTitle = label("", 14f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(8), 0)
        }
        val subtitlesButton = control("CC", "Choose embedded subtitle track") {
            val activePlayer = videoPlayer
            if (activePlayer == null) {
                Toast.makeText(this, "Video player is unavailable.", Toast.LENGTH_SHORT).show()
            } else if (activePlayer.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) {
                Toast.makeText(this, "This video has no embedded subtitles.", Toast.LENGTH_SHORT).show()
            } else {
                TrackSelectionDialogBuilder(
                    this,
                    "Subtitles",
                    activePlayer,
                    C.TRACK_TYPE_TEXT
                ).setShowDisableOption(true).build().show()
            }
        }
        topControls.addView(backButton)
        topControls.addView(currentTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topControls.addView(subtitlesButton)
        topControls.addView(menuButton)
        topControls.addView(closeVideoButton)
        controls.addView(topControls)

        val menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.rgb(31, 21, 43), 16).apply {
                setStroke(dp(1), Color.rgb(112, 72, 148))
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
                intArrayOf(Color.rgb(25, 15, 37), Color.TRANSPARENT)
            )
        }
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previousButton = control("|◀", "Previous video") { navigateVideo(-1) }
        val playPauseButton = control("Ⅱ", "Pause video") {
            videoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
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
        val elapsed = label("0:00", 12f, Color.rgb(223, 207, 241))
        val duration = label("0:00", 12f, Color.rgb(223, 207, 241))
        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(184, 126, 243))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(225, 196, 255))
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
            val speed = savedVideoSpeed()
            val speedRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            speedRow.addView(label("PLAYBACK SPEED", 12f, Color.rgb(190, 160, 220), true), LinearLayout.LayoutParams(0, dp(48), 1f))
            speedRow.addView(label("${speed}×  CHANGE", 13f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(Color.rgb(67, 42, 89), 12)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val options = arrayOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "1.75×", "2.0×")
                    val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
                    val selected = values.indexOfFirst { kotlin.math.abs(it - savedVideoSpeed()) < 0.01f }.coerceAtLeast(0)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Playback speed")
                        .setSingleChoiceItems(options, selected) { dialog, which ->
                            val chosen = values[which]
                            cinemaPreferences().edit().putFloat("video_speed", chosen).apply()
                            videoPlayer?.setPlaybackParameters(PlaybackParameters(chosen))
                            dialog.dismiss()
                            refreshMenu()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            menuPanel.addView(speedRow)
            menuPanel.addView(label("SUBTITLE APPEARANCE", 12f, Color.rgb(190, 160, 220), true).apply {
                setPadding(dp(4), dp(8), dp(4), dp(4))
            })
            val subtitleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            subtitleRow.addView(label("Adjust subtitle size", 13f, Color.WHITE), LinearLayout.LayoutParams(0, dp(44), 1f))
            subtitleRow.addView(label("Aa  ADJUST", 13f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = roundedBackground(Color.rgb(67, 42, 89), 12)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val currentSize = savedSubtitleTextSize()
                    val sizeBar = SeekBar(this@MainActivity).apply {
                        max = 20
                        progress = currentSize - 16
                    }
                    val preview = label("Subtitle preview · ${currentSize}sp", currentSize.toFloat(), Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                        background = roundedBackground(Color.rgb(24, 20, 30), 10)
                    }
                    val panel = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(8), dp(20), dp(4))
                        addView(preview)
                        addView(sizeBar)
                    }
                    sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(bar: SeekBar) = Unit
                        override fun onStopTrackingTouch(bar: SeekBar) = Unit
                        override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                            preview.textSize = (value + 16).toFloat()
                            preview.text = "Subtitle preview · ${value + 16}sp"
                        }
                    })
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Subtitle text size")
                        .setView(panel)
                        .setPositiveButton("Save") { _, _ ->
                            cinemaPreferences().edit().putInt("subtitle_text_size_sp", sizeBar.progress + 16).apply()
                            applyCinemaSubtitleSize()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            menuPanel.addView(subtitleRow)
            menuPanel.addView(label("YOUR VIDEOS", 12f, Color.rgb(190, 160, 220), true).apply {
                setPadding(dp(4), dp(10), dp(4), dp(4))
            })
            if (deviceVideos.isEmpty()) {
                menuPanel.addView(label("No videos available", 14f, Color.rgb(190, 175, 210)))
                return
            }
            deviceVideos.forEach { video ->
                val selected = video.uri.toString() == selectedVideoUri
                val row = label("${if (selected) "✓  " else ""}${video.title}", 14f,
                    if (selected) Color.rgb(218, 177, 255) else Color.WHITE, selected).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isClickable = true
                    isFocusable = true
                    background = roundedBackground(if (selected) Color.rgb(67, 42, 89) else Color.TRANSPARENT, 12)
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

    private fun restoreVideoPlayerView() {
        if (!::videoPlayerView.isInitialized || !::videoPlaceholder.isInitialized) return
        (videoPlayerView.parent as? android.view.ViewGroup)?.removeView(videoPlayerView)
        if (videoPlayerView.parent == null) {
            videoPlaceholder.addView(
                videoPlayerView,
                0,
                LinearLayout.LayoutParams(-1, videoPreviewHeight())
            )
        }
    }

    private fun showTab(tab: String) {
        currentTab = tab
        selectedNavigationTab = if (tab == "nowPlaying") "home" else tab
        if (tab == "home") renderHomeDashboard()
        searchBox.visibility =
            if (tab == "search" || tab == "library" || tab == "videos") View.VISIBLE else View.GONE
        if (tab == "library") {
            // Start the Music library in its compact view. Users can expand the
            // library title, collection shortcuts, browse modes and sorting on demand.
            libraryControlsVisible = false
            getSharedPreferences("kivora_preferences", MODE_PRIVATE).edit()
                .putBoolean("hide_songs_albums_shortcuts", true).apply()
        }
        libraryHeader.visibility =
            if (tab == "library" && libraryControlsVisible) View.VISIBLE else View.GONE
        songScroll.visibility =
            if (tab == "search" || tab == "library") View.VISIBLE else View.GONE
        val showVideoLibrary = tab == "videos" && deviceVideos.isNotEmpty()
        videoPlaceholder.visibility = if (showVideoLibrary) View.VISIBLE else View.GONE
        val hasSelectedVideo = selectedVideoUri != null
        videoPlayerView.visibility = if (tab == "videos" && hasSelectedVideo) View.VISIBLE else View.GONE
        videoPlaceholder.findViewWithTag<View>("video_player_actions")?.visibility =
            if (tab == "videos" && hasSelectedVideo) View.VISIBLE else View.GONE
        if (::libraryVisibilityToggle.isInitialized) {
            libraryVisibilityToggle.visibility = if (tab == "library") View.VISIBLE else View.GONE
            libraryVisibilityToggle.text =
                if (libraryControlsVisible) "Hide your library" else "Show your library"
        }
        if (tab == "videos" && deviceVideos.isEmpty()) {
            searchBox.visibility = View.GONE
            homeScroll.visibility = View.VISIBLE
        }
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
        homeScroll.visibility = if (tab == "home" || (tab == "videos" && deviceVideos.isEmpty())) View.VISIBLE else View.GONE
        playerScroll.visibility = if (tab == "nowPlaying") View.VISIBLE else View.GONE
        miniPlayer.visibility = if (tab == "nowPlaying") View.GONE else View.VISIBLE

        if (tab == "search") {
            showFavoritesOnly = false
            selectedPlaylist = null
            updateFavoriteFilterButton()
            updatePlaylistButton()
            renderSongs(searchBox.text?.toString().orEmpty())
        } else if (tab == "library") {
            updateLibraryBrowseButtons()
            renderSongs(searchBox.text?.toString().orEmpty())
        } else if (tab == "videos" && deviceVideos.isNotEmpty()) {
            // Reapply the current query when returning from another tab.
            renderVideos(searchBox.text?.toString().orEmpty())
        }

        navigationButtons.forEach { (name, button) ->
            val selected = name == tab || (name == "home" && tab == "nowPlaying")
            button.setTextColor(
                if (selected) Color.rgb(232, 213, 255)
                else Color.rgb(174, 159, 194)
            )
            button.background = roundedBackground(
                if (selected) Color.rgb(83, 39, 126)
                else Color.TRANSPARENT,
                14
            )
        }
    }

}
