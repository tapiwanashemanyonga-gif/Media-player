package com.kivora.music

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

data class Song(
    val title: String,
    val artist: String,
    val uri: Uri,
    val artworkUri: Uri?
)

class MainActivity : AppCompatActivity() {

    private var player: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private lateinit var songList: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var artwork: ImageView
    private lateinit var playButton: TextView
    private lateinit var shuffleButton: TextView
    private lateinit var repeatButton: TextView
    private lateinit var searchBox: EditText
    private lateinit var progress: SeekBar
    private lateinit var elapsedText: TextView
    private lateinit var durationText: TextView
    private lateinit var libraryCount: TextView
    private lateinit var favoriteFilterButton: TextView
    private lateinit var playlistButton: TextView

    private val songs = mutableListOf<Song>()
    private var activeQueue: List<Song> = emptyList()
    private val favoriteSongUris = mutableSetOf<String>()
    private val playlists = linkedMapOf<String, MutableSet<String>>()

    private var showFavoritesOnly = false
    private var selectedPlaylist: String? = null
    private var userSeeking = false

    private val handler = Handler(Looper.getMainLooper())

    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private var artworkRequestId = 0

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

    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int
        ) {
            updateNowPlaying()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
        }

        override fun onShuffleModeEnabledChanged(
            shuffleModeEnabled: Boolean
        ) {
            updateShuffleButton()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateRepeatButton()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateProgress()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(13, 15, 25)
        window.navigationBarColor = Color.rgb(13, 15, 25)

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
                    updateNowPlaying()
                    updatePlayButton()
                    updateShuffleButton()
                    updateRepeatButton()
                    updateProgress()

                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Couldn't connect to the music service. Reopen Kivora.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(
        color: Int,
        radius: Int = 18,
        strokeColor: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radius).toFloat()

            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

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

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(Color.rgb(13, 15, 25))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            label("KIVORA", 27f, Color.rgb(167, 139, 250), true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        header.addView(
            label("MUSIC PLAYER", 10f, Color.rgb(148, 163, 184), true)
        )

        root.addView(header)

        root.addView(
            label(
                "YOUR MUSIC. YOUR WORLD.",
                11f,
                Color.rgb(148, 163, 184)
            ).apply {
                setPadding(0, dp(3), 0, dp(16))
            }
        )

        searchBox = EditText(this).apply {
            hint = "Search songs or artists"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(148, 163, 184))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBackground(
                Color.rgb(27, 30, 44),
                16,
                Color.rgb(55, 48, 75)
            )
        }

        root.addView(
            searchBox,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        val libraryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleRow.addView(
            label("YOUR LIBRARY", 17f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        libraryCount = label(
            "0 songs",
            12f,
            Color.rgb(148, 163, 184)
        )

        titleRow.addView(libraryCount)
        libraryHeader.addView(titleRow)

        playlistButton = label(
            "♫  ALL SONGS  ·  PLAYLISTS",
            13f,
            Color.rgb(196, 181, 253),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(
                Color.rgb(35, 39, 57),
                14,
                Color.rgb(74, 63, 105)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showPlaylistMenu()
            }
        }

        libraryHeader.addView(
            playlistButton,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            }
        )

        favoriteFilterButton = label(
            "☆  SHOW FAVOURITES ONLY",
            13f,
            Color.rgb(196, 181, 253),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(
                Color.rgb(39, 33, 65),
                14,
                Color.rgb(91, 71, 140)
            )
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

        libraryHeader.addView(
            favoriteFilterButton,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
            }
        )

        root.addView(libraryHeader)

        val scroll = ScrollView(this)

        songList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(songList)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            background = roundedBackground(
                Color.rgb(27, 30, 44),
                22,
                Color.rgb(55, 48, 75)
            )
        }

        card.addView(
            label(
                "NOW PLAYING",
                10f,
                Color.rgb(167, 139, 250),
                true
            )
        )

        val trackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }

        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(
                Color.rgb(45, 39, 72),
                16
            )
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = "Album artwork"
        }

        trackRow.addView(
            artwork,
            LinearLayout.LayoutParams(dp(64), dp(64))
        )

        val trackInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        nowTitle = label("Choose a song", 15f, Color.WHITE, true)
        nowArtist = label(
            "Your library is ready",
            12f,
            Color.rgb(148, 163, 184)
        )

        trackInfo.addView(nowTitle)
        trackInfo.addView(nowArtist)

        trackRow.addView(
            trackInfo,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        card.addView(trackRow)