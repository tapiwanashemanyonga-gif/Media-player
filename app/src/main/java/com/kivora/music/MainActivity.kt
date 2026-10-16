
package com.kivora.music

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.Size
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
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private lateinit var songList: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var artwork: ImageView
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
    private var videoPlayer: ExoPlayer? = null
    private var navigationVideoUri: String? = null
    private var lastFailedVideoUri: String? = null
    private val videoPlayerErrorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            reportVideoPlaybackFailure()
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

    private val handler = Handler(Looper.getMainLooper())

    // Optimization: decode album artwork away from the UI thread.
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private var artworkRequestId = 0

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

        val preferences = getSharedPreferences(
            "kivora_preferences",
            MODE_PRIVATE
        )

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(13, 8, 23), Color.rgb(24, 13, 39), Color.rgb(12, 10, 22))
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
                intArrayOf(Color.rgb(174, 98, 255), Color.rgb(104, 48, 190))
            ).apply { cornerRadius = dp(16).toFloat() }
            elevation = dp(5).toFloat()
            contentDescription = "A1 Family Media Player"
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
        header.addView(label("●  LOCAL LIBRARY", 9f, Color.rgb(210, 190, 232), true).apply {
            letterSpacing = 0.06f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.rgb(39, 24, 58), 18).apply {
                setStroke(dp(1), Color.rgb(83, 56, 115))
            }
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
            hint = "⌕   Search songs, artists and albums"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.rgb(250, 247, 255))
            setHintTextColor(Color.rgb(171, 151, 196))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = roundedBackground(Color.rgb(31, 21, 47), 19).apply {
                setStroke(dp(1), Color.rgb(91, 66, 122))
            }
            contentDescription = "Search songs, artists and albums"
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchRow.addView(searchBox, LinearLayout.LayoutParams(0, dp(54), 1f))
        clearSearchButton = label("×", 25f, Color.rgb(232, 213, 255), true).apply {
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

        libraryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(18), dp(1), dp(8))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(
            label("Your library", 24f, Color.rgb(250, 247, 255), true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        libraryCount = label(
            "0 tracks in your collection",
            11f,
            Color.rgb(211, 194, 232),
            // KIVORA_ROUND17_LIBRARY_COUNT_BADGE
        ).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); background = roundedBackground(Color.rgb(55, 27, 79), 14).apply { setStroke(dp(1), Color.rgb(192, 132, 252)) } }

        libraryCount.gravity = Gravity.CENTER
        titleRow.addView(libraryCount)
        libraryHeader.addView(titleRow)

        playlistButton = label(
            "♫   ALL SONGS  ·  PLAYLISTS",
            13f,
            Color.rgb(216, 180, 254),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            // KIVORA_ROUND19_PLAYLIST_BUTTON
            background = roundedBackground(Color.rgb(42, 25, 64), 16).apply { setStroke(dp(1), Color.rgb(119, 78, 159)) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showPlaylistMenu()
            }
        }

        favoriteFilterButton = label(
            "☆  SHOW FAVOURITES ONLY",
            13f,
            Color.rgb(216, 180, 254),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(Color.rgb(28, 20, 40), 16).apply { setStroke(dp(1), Color.rgb(74, 54, 96)) }
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
            background = roundedBackground(Color.rgb(25, 17, 38), 18).apply {
                setStroke(dp(1), Color.rgb(76, 53, 99))
            }
        }
        listOf("songs" to "SONGS", "albums" to "ALBUMS", "artists" to "ARTISTS").forEach { (mode, title) ->
            val button = label(title, 11f, Color.rgb(190, 175, 210), true).apply {
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
        }
        val videoPlayerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val fullscreenButton = label("⛶  FULLSCREEN", 12f, Color.rgb(232, 213, 255), true).apply {
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
        videoPlaceholder.addView(videoPlayerActions, LinearLayout.LayoutParams(-1, -2))
        videoPlaceholder.addView(
            videoPlayerView,
            LinearLayout.LayoutParams(-1, dp(160))
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
        val minimizeVideoButton = label("↓  MINIMIZE VIDEO", 11f, Color.rgb(232, 213, 255), true).apply {
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
            minimizeVideoButton.text = if (shouldMinimize) "↑  SHOW VIDEO" else "↓  MINIMIZE VIDEO"
            minimizeVideoButton.contentDescription =
                if (shouldMinimize) "Restore video player" else "Minimize video player"
        }
        videoPlayerActions.addView(minimizeVideoButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })

        val videoListButton = label("▤  VIDEO LIST", 11f, Color.rgb(232, 213, 255), true).apply {
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
            setPadding(dp(20), dp(20), dp(20), dp(18))
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

        val artworkSize = (resources.configuration.screenWidthDp - 86).coerceIn(160, 460)
        val artworkFrame = android.widget.FrameLayout(this).apply {
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
                bottomMargin = dp(24)
            }
        )

        nowTitle = label(
            "Choose a song",
            if (resources.configuration.screenWidthDp >= 600) 32f else 28f,
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
            if (resources.configuration.screenWidthDp >= 600) 17f else 16f,
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
            minHeight = dp(42)
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
            topMargin = dp(14)
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
                // KIVORA_FIX_AUDIO_VIDEO_V2
                videoPlayer?.pause()
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
        miniArtist = label("Your library is ready", 11f, Color.rgb(190, 175, 210)).apply {
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
                        // KIVORA_FIX_AUDIO_VIDEO_V2
                        videoPlayer?.pause()
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
            background = roundedBackground(Color.rgb(25, 18, 37), 21).apply { setStroke(dp(1), Color.rgb(70, 50, 92)) }
        }
        listOf(
            Triple("home", "⌂", "HOME"),
            Triple("search", "⌕", "SEARCH"),
            Triple("library", "♫", "LIBRARY"),
            Triple("videos", "▣", "VIDEOS")
        ).forEach { (tab, symbol, title) ->
            val item = label("$symbol\n$title", 10f, Color.rgb(190, 175, 210), true).apply {
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
                    if (currentTab == "videos") renderVideos(s?.toString().orEmpty()) else renderSongs(s?.toString().orEmpty())
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

        favoriteFilterButton.text =
            if (showFavoritesOnly) {
                "★  SHOWING FAVOURITES — TAP TO SHOW ALL"
            } else {
                "☆  SHOW FAVOURITES ONLY"
            }

        favoriteFilterButton.setTextColor(
            if (showFavoritesOnly) {
                Color.rgb(250, 204, 21)
            } else {
                Color.rgb(216, 180, 254)
            }
        )

        // KIVORA_ROUND18_FAVOURITES_BORDER
        favoriteFilterButton.background = roundedBackground(
            if (showFavoritesOnly) Color.rgb(55, 40, 65) else Color.rgb(56, 34, 78),
            18
        ).apply {
            setStroke(dp(2), if (showFavoritesOnly) Color.rgb(250, 204, 21) else Color.rgb(192, 132, 252))
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
                Color.rgb(250, 204, 21)
            } else {
                Color.rgb(216, 180, 254)
            }
        )
    }

    private fun updateLibraryBrowseButtons() {
        libraryBrowseButtons.forEach { (mode, button) ->
            val selected = mode == libraryBrowseMode
            button.setTextColor(
                if (selected) Color.rgb(250, 239, 255) else Color.rgb(190, 175, 210)
            )
            button.background = roundedBackground(
                if (selected) Color.rgb(91, 48, 132) else Color.TRANSPARENT,
                14
            ).apply {
                if (selected) setStroke(dp(1), Color.rgb(172, 112, 224))
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
        homeContent.removeAllViews()

        homeContent.addView(label("HOME", 11f, Color.rgb(203, 162, 242), true).apply {
            letterSpacing = 0.16f
            setPadding(dp(2), dp(2), 0, dp(5))
        })
        homeContent.addView(label("Your music, ready", 27f, Color.rgb(252, 248, 255), true).apply {
            setPadding(dp(2), 0, 0, dp(4))
        })
        homeContent.addView(label("Pick up where you left off.", 13f, Color.rgb(192, 173, 214)).apply {
            setPadding(dp(2), 0, 0, dp(18))
        })

        homeContent.addView(label("QUICK ACCESS", 12f, Color.rgb(215, 194, 236), true).apply {
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
                    background = roundedBackground(Color.rgb(38, 25, 55), 17).apply {
                        setStroke(dp(1), Color.rgb(91, 63, 119))
                    }
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Browse $title"
                    setOnClickListener { openHomeShortcut(destination) }
                }
                shortcut.addView(label(symbol, 21f, Color.rgb(213, 165, 255), true))
                shortcut.addView(label(title, 13f, Color.rgb(248, 242, 255), true).apply {
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
        homeContent.addView(label("Tap the mini-player to open Now Playing", 11f, Color.rgb(164, 145, 186)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(15), 0, dp(3))
        })
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

    private fun homeCardParams() = LinearLayout.LayoutParams(
        dp((resources.configuration.screenWidthDp - 64).coerceIn(150, 210)), -2
    ).apply { rightMargin = dp(10) }

    private fun homeSongCard(song: Song, title: String, subtitle: String, onClick: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(11))
            background = roundedBackground(Color.rgb(37, 25, 53), 18).apply {
                setStroke(dp(1), Color.rgb(82, 58, 108))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "$title, $subtitle"
            setOnClickListener { onClick() }
        }
        val cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(Color.rgb(55, 34, 75), 14).apply {
                setStroke(dp(1), Color.rgb(131, 88, 164))
            }
            clipToOutline = true
            contentDescription = "Artwork for ${song.title}"
        }
        card.addView(cover, LinearLayout.LayoutParams(-1, dp((resources.configuration.screenWidthDp - 100).coerceIn(128, 188))))
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
            setPadding(dp(10), dp(10), dp(10), dp(11))
            background = roundedBackground(Color.rgb(37, 25, 53), 18).apply {
                setStroke(dp(1), Color.rgb(82, 58, 108))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Playlist $name, $count songs"
            setOnClickListener { onClick() }
        }
        val cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_placeholder)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(Color.rgb(55, 34, 75), 14).apply {
                setStroke(dp(1), Color.rgb(131, 88, 164))
            }
            clipToOutline = true
            contentDescription = "Playlist artwork for $name"
        }
        card.addView(cover, LinearLayout.LayoutParams(-1, dp((resources.configuration.screenWidthDp - 100).coerceIn(128, 188))))
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
            setStroke(dp(1), Color.rgb(71, 51, 93))
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
    }

    private fun makeMediaItems(queue: List<Song>): List<MediaItem> =
        queue.map { song ->
            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
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

    private fun loadArtworkInto(imageView: ImageView, song: Song) {
        val songKey = song.uri.toString()
        imageView.tag = songKey
        val artworkUri = song.artworkUri ?: return

        artworkExecutor.execute {
            val bitmap = try {
                contentResolver.openInputStream(artworkUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) {
                null
            }

            if (bitmap != null) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && imageView.tag == songKey) {
                        imageView.clearColorFilter()
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
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

                normalized.isNotEmpty() -> "No songs match your search. Use × to clear it."
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

        visibleSongs.forEach { song ->
            val uri = song.uri.toString()

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = roundedBackground(Color.rgb(30, 21, 43), 19).apply {
                    setStroke(dp(1), Color.rgb(65, 47, 84))
                }
                isClickable = true
                isFocusable = true
                contentDescription = "Play ${song.title} by ${song.artist}"
                elevation = dp(1).toFloat()
                setOnClickListener { playSong(song) }
            }

            val cover = ImageView(this).apply {
                setImageResource(R.drawable.ic_audio_placeholder)
                background = roundedBackground(Color.rgb(57, 37, 78), 14).apply {
                    setStroke(dp(1), Color.rgb(111, 75, 146))
                }
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Album artwork"
            }
            row.addView(cover, LinearLayout.LayoutParams(dp(58), dp(58)))
            loadArtworkInto(cover, song)

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(2), 0)
            }

            info.addView(
                label(song.title, 15f, Color.WHITE, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )

            info.addView(
                label(
                    song.artist,
                    12f,
                    Color.rgb(190, 175, 210)
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
                        Color.rgb(250, 204, 21)
                    } else {
                        Color.rgb(174, 159, 194)
                    }
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    minWidth = dp(48)
                    minHeight = dp(48)
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (isFavorite) "Remove ${song.title} from favourites" else "Add ${song.title} to favourites"
                    setOnClickListener { toggleFavorite(song) }
                }
            )

            if (selectedPlaylist == null) {
                row.addView(
                    label("+", 23f, Color.rgb(216, 180, 254), true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        minWidth = dp(44)
                        minHeight = dp(44)
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
                    label("−", 23f, Color.rgb(248, 113, 113), true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        minWidth = dp(44)
                        minHeight = dp(44)
                        contentDescription = "Remove from playlist"
                        setOnClickListener {
                            removeFromPlaylist(song)
                        }
                    }
                )
            }

            row.addView(label("⋮", 22f, Color.rgb(216, 180, 254), true).apply {
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
            background = roundedBackground(Color.rgb(34, 23, 49), 19).apply {
                setStroke(dp(1), Color.rgb(90, 62, 117))
            }
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }
        val cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_placeholder)
            background = roundedBackground(Color.rgb(57, 37, 78), 14).apply {
                setStroke(dp(1), Color.rgb(145, 96, 190))
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
        details.addView(label(title, 16f, Color.rgb(251, 247, 255), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        details.addView(label(subtitle, 12f, Color.rgb(194, 174, 216)).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("›", 28f, Color.rgb(190, 145, 224), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Open $title"
        })
        songList.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(9)
        })
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

        // KIVORA_FIX_AUDIO_VIDEO_V1
        videoPlayer?.pause()
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
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        upNextSummary = summary
        content.addView(summary)
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
        val queueSummary = when (entries.size) {
            0 -> "Nothing queued after the current song"
            1 -> "1 upcoming song"
            else -> "${entries.size} upcoming songs"
        }
        upNextSummary?.text = when {
            entries.isEmpty() -> queueSummary
            p.repeatMode == Player.REPEAT_MODE_ONE -> "$queueSummary · repeat one"
            p.shuffleModeEnabled -> "$queueSummary · shuffle on"
            else -> queueSummary
        }
        if (list == null) return
        list.removeAllViews()
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

        val artworkUri = song.artworkUri

        if (artworkUri != null) {
            artworkExecutor.execute {
                val bitmap = try {
                    contentResolver.openInputStream(artworkUri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (_: Exception) {
                    null
                }

                runOnUiThread {
                    if (
                        !isFinishing &&
                        !isDestroyed &&
                        requestId == artworkRequestId &&
                        bitmap != null
                    ) {
                        artwork.setImageBitmap(bitmap)
                    }
                }
            }
        }

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

        shuffleButton.setTextColor(
            if (player?.shuffleModeEnabled == true) {
                Color.rgb(196, 145, 255)
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

        // Invalidate any pending artwork callback and stop its worker.
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
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true
                isFocusable = true
                background = roundedBackground(Color.rgb(30, 21, 43), 18).apply {
                    setStroke(dp(1), if (video.uri.toString() == selectedVideoUri) Color.rgb(173, 115, 236) else Color.rgb(69, 48, 89))
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
                background = roundedBackground(Color.rgb(53, 35, 73), 12).apply {
                    setStroke(dp(1), Color.rgb(104, 70, 137))
                }
                clipToOutline = true
                contentDescription = "Thumbnail for ${video.title}"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    thumbnail.setImageBitmap(
                        contentResolver.loadThumbnail(
                            video.uri,
                            Size(dp(100), dp(70)),
                            null
                        )
                    )
                } catch (_: Exception) {
                    // Keep the play icon if a thumbnail cannot be loaded.
                }
            }

            row.addView(thumbnail, LinearLayout.LayoutParams(dp(100), dp(70)))

            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, 0, 0)
            }

            details.addView(label(video.title, 15f, Color.WHITE, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            details.addView(
                label(formatTime(video.durationMs), 12f, Color.rgb(190, 175, 210))
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
        selectedVideoUri = video.uri.toString()
        navigationVideoUri = video.uri.toString()
        lastFailedVideoUri = null
        renderVideos(searchBox.text?.toString().orEmpty())
        videoStatus.text = "Playing: ${video.title}"
        // KIVORA_FIX_AUDIO_VIDEO_V1
        player?.pause()
        return try {
            videoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(video.uri))
                prepare()
                playWhenReady = true
            } != null
        } catch (_: RuntimeException) {
            reportVideoPlaybackFailure(video.uri.toString())
            false
        }
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
        val exitButton = control("‹", "Exit fullscreen") { dialog.dismiss() }
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
        topControls.addView(exitButton)
        topControls.addView(currentTitle, LinearLayout.LayoutParams(0, -2, 1f))
        topControls.addView(menuButton)
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
                LinearLayout.LayoutParams(-1, dp(160))
            )
        }
    }

    private fun showTab(tab: String) {
        currentTab = tab
        if (tab == "home") renderHomeDashboard()
        searchBox.visibility =
            if (tab == "search" || tab == "library" || tab == "videos") View.VISIBLE else View.GONE
        libraryHeader.visibility =
            if (tab == "library") View.VISIBLE else View.GONE
        songScroll.visibility =
            if (tab == "search" || tab == "library") View.VISIBLE else View.GONE
        videoPlaceholder.visibility =
            if (tab == "videos") View.VISIBLE else View.GONE
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
            showFavoritesOnly = false
            selectedPlaylist = null
            updateFavoriteFilterButton()
            updatePlaylistButton()
            renderSongs(searchBox.text?.toString().orEmpty())
        } else if (tab == "library") {
            updateLibraryBrowseButtons()
            renderSongs(searchBox.text?.toString().orEmpty())
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
