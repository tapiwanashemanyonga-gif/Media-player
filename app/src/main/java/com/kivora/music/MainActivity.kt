
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

        window.statusBarColor = Color.rgb(18, 12, 28)
        window.navigationBarColor = Color.rgb(18, 12, 28)

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
            setPadding(dp(22), dp(22), dp(22), dp(14))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(9, 5, 19), Color.rgb(39, 15, 64))
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            label("KIVORA", 32f, Color.rgb(216, 180, 254), true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        header.addView(
            label("YOUR MUSIC. YOUR RULES.", 10f, Color.rgb(174, 159, 194), true)
        )

        root.addView(header)

        root.addView(
            label(
                "YOUR SOUND. YOUR WORLD.  ✦",
                // KIVORA_ROUND16_TAGLINE_ACCENT
                11f,
                Color.rgb(216, 180, 254)
            ).apply {
                setPadding(0, dp(3), 0, dp(16))
            }
        )

        searchBox = EditText(this).apply {
            hint = "Search songs, artists and videos"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(174, 159, 194))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedBackground(Color.rgb(23, 12, 37), 22).apply { setStroke(dp(2), Color.rgb(192, 132, 252)) }
        }

        root.addView(
            searchBox,
            LinearLayout.LayoutParams(-1, dp(58))
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
            label("YOUR LIBRARY", 22f, Color.rgb(232, 213, 255), true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        libraryCount = label(
            "0 tracks in your collection",
            13f,
            Color.rgb(207, 187, 229)
            // KIVORA_ROUND17_LIBRARY_COUNT_BADGE
        ).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); background = roundedBackground(Color.rgb(55, 27, 79), 14).apply { setStroke(dp(1), Color.rgb(192, 132, 252)) } }

        titleRow.addView(libraryCount)
        libraryHeader.addView(titleRow)

        playlistButton = label(
            "♫  ALL SONGS  ·  PLAYLISTS",
            13f,
            Color.rgb(216, 180, 254),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            // KIVORA_ROUND19_PLAYLIST_BUTTON
            background = roundedBackground(Color.rgb(96, 43, 145), 18).apply { setStroke(dp(2), Color.rgb(216, 180, 254)) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showPlaylistMenu()
            }
        }

        libraryHeader.addView(
            playlistButton,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(16)
            }
        )

        favoriteFilterButton = label(
            "☆  SHOW FAVOURITES ONLY",
            13f,
            Color.rgb(216, 180, 254),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(Color.rgb(83, 39, 126), 18).apply { setStroke(dp(2), Color.rgb(192, 132, 252)) }
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
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = roundedBackground(Color.rgb(34, 16, 56), 26).apply { setStroke(dp(2), Color.rgb(216, 180, 254)) }
        }

        card.addView(
            label(
                "NOW PLAYING  •  KIVORA PLAYER",
                10f,
                Color.rgb(196, 145, 255),
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
            // KIVORA_ROUND15_ARTWORK_FRAME
            background = roundedBackground(Color.rgb(91, 45, 135), 16).apply { setStroke(dp(2), Color.rgb(216, 180, 254)) }
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = "Album artwork"
        }

        trackRow.addView(
            artwork,
            LinearLayout.LayoutParams(dp(116), dp(116))
        )

        val trackInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        nowTitle = label("Choose a song", 18f, Color.WHITE, true)
        nowArtist = label(
             "Your library is ready",
            12f,
            Color.rgb(174, 159, 194)
        )

        trackInfo.addView(nowTitle)
        trackInfo.addView(nowArtist)

        trackRow.addView(
            trackInfo,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        card.addView(trackRow)

        progress = SeekBar(this).apply {
            max = 1000

            progressTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(196, 145, 255)
                )

            thumbTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(216, 180, 254)
                )

            // KIVORA_ROUND14_PROGRESS_TRACK
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(83, 55, 108)
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
                            elapsedText.text = formatTime(
                                d * value / 1000L
                            )
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

        card.addView(progress)

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        elapsedText = label(
            "0:00",
            10f,
            Color.rgb(174, 159, 194)
        )

        durationText = label(
            "0:00",
            10f,
            Color.rgb(174, 159, 194)
        )

        times.addView(
            elapsedText,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        times.addView(durationText)
        card.addView(times)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
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

        controls.addView(shuffleButton)
        controls.addView(previousButton)
        controls.addView(playButton)
        controls.addView(nextButton)
        controls.addView(repeatButton)

        card.addView(controls)

        root.addView(
            card,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(16)
            }
        )

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
                    renderSongs(s?.toString().orEmpty())
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
    }

    private fun control(
        symbol: String,
        action: () -> Unit
    ): TextView =
        TextView(this).apply {
            text = symbol
            textSize =
                if (symbol == "▶" || symbol == "Ⅱ") 22f else 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(if (symbol == "▶" || symbol == "Ⅱ") 18 else 12), dp(if (symbol == "▶" || symbol == "Ⅱ") 14 else 9), dp(if (symbol == "▶" || symbol == "Ⅱ") 18 else 12), dp(if (symbol == "▶" || symbol == "Ⅱ") 14 else 9))
            background = roundedBackground(
                if (symbol == "▶" || symbol == "Ⅱ")
                    Color.rgb(124, 58, 237)
                else
                    Color.rgb(56, 34, 78),
                28
            )
            // KIVORA_ROUND13_CONTROL_BORDER
            (background as android.graphics.drawable.GradientDrawable).setStroke(dp(if (symbol == "▶" || symbol == "Ⅱ") 2 else 1), if (symbol == "▶" || symbol == "Ⅱ") Color.rgb(216, 180, 254) else Color.rgb(145, 95, 190))
            setOnClickListener { action() }
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
                val uris = mutableSetOf<String>()

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

    // ---------------- PLAYLIST MENUS ----------------

    private fun showPlaylistMenu() {
        val options = mutableListOf<String>()
        options.add("All songs")
        options.add("+ Create a new playlist")

        playlists.keys.forEach { name ->
            options.add("$name (${playlists[name]?.size ?: 0} songs)")
        }

        AlertDialog.Builder(this)
            .setTitle("Your Playlists")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        selectedPlaylist = null
                        showFavoritesOnly = false
                        updateFavoriteFilterButton()
                        updatePlaylistButton()
                        renderSongs(searchBox.text?.toString().orEmpty())
                    }

                    1 -> showCreatePlaylistDialog()

                    else -> {
                        val name = playlists.keys.elementAtOrNull(which - 2)
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
                        updateFavoriteFilterButton()
                        updatePlaylistButton()
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

    private fun showCreatePlaylistDialog(songToAdd: Song? = null) {
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
                if (songToAdd != null) {
                    "Choose a name. This song will be added to it."
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

                    playlists[name] = mutableSetOf()

                    if (songToAdd != null) {
                        playlists[name]?.add(songToAdd.uri.toString())
                    }

                    savePlaylists()
                    selectedPlaylist = name
                    showFavoritesOnly = false

                    updatePlaylistButton()
                    updateFavoriteFilterButton()
                    renderSongs(searchBox.text?.toString().orEmpty())

                    Toast.makeText(
                        this,
                        if (songToAdd != null) {
                            "Playlist created and song added"
                        } else {
                            "Playlist created"
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

    private fun loadSongs() {
        songs.clear()
        normalizedSearchText.clear()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
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
                        value.isNotBlank() && value != "<unknown>"
                    } ?: "Unknown artist"

                    val albumId = it.getLong(albumColumn)

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

        if (songs.isEmpty()) {
            activeQueue = emptyList()

            if (p.mediaItemCount > 0) {
                p.clearMediaItems()
            }

            updateNowPlaying()
            return
        }

        val existingQueue = (0 until p.mediaItemCount).mapNotNull { index ->
            val uri = p.getMediaItemAt(index)
                .localConfiguration?.uri?.toString()

            uri?.let { savedUri ->
                songs.firstOrNull { it.uri.toString() == savedUri }
            }
        }

        if (
            p.mediaItemCount > 0 &&
            existingQueue.size == p.mediaItemCount
        ) {
            activeQueue = existingQueue
        } else {
            activeQueue = songs.toList()
            p.setMediaItems(makeMediaItems(activeQueue))
            p.prepare()
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

        val matches = songs.withIndex().filter { entry ->
            val song = entry.value
            val uri = song.uri.toString()

            val searchableText = normalizedSearchText.getOrPut(uri) {
                (song.title + " " + song.artist).lowercase(Locale.ROOT)
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

        libraryCount.text = when {
            selectedPlaylist != null -> "${matches.size} in playlist"
            showFavoritesOnly -> "${matches.size} favourites"
            normalized.isEmpty() -> "${songs.size} songs"
            else -> "${matches.size} found"
        }

        if (matches.isEmpty()) {
            val message = when {
                songs.isEmpty() ->
                    "No music found yet.\nAdd audio files to your device and try again."

                selectedPlaylist != null ->
                    "This playlist is empty.\nReturn to All Songs and tap ＋ beside a song to add it."

                showFavoritesOnly ->
                    "No favourites yet.\nTap ☆ beside a song to add it."

                else -> "No songs match your search."
            }

            songList.addView(
                label(
                    message,
                    14f,
                    Color.rgb(174, 159, 194)
                ).apply {
                    setPadding(dp(4), dp(18), dp(4), dp(18))
                }
            )

            return
        }

        matches.forEach { entry ->
            val song = entry.value
            val uri = song.uri.toString()

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                background = roundedBackground(Color.rgb(37, 22, 55), 20).apply { setStroke(dp(1), Color.rgb(145, 95, 190)) }
                // KIVORA_ROUND20_SONG_ROW_DEPTH
                elevation = dp(3).toFloat()
                setOnClickListener { playSong(song) }
            }

            val cover = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_media_play)
                setColorFilter(Color.rgb(196, 145, 255))
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Album artwork"
            }
            row.addView(cover, LinearLayout.LayoutParams(dp(56), dp(56)))
            loadArtworkInto(cover, song)

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, dp(2), 0)
            }

            info.addView(
                label(song.title, 14f, Color.WHITE, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )

            info.addView(
                label(
                    song.artist,
                    12f,
                    Color.rgb(174, 159, 194)
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
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    contentDescription = "Toggle favourite"
                    setOnClickListener { toggleFavorite(song) }
                }
            )

            if (selectedPlaylist == null) {
                row.addView(
                    label("+", 23f, Color.rgb(216, 180, 254), true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        contentDescription = "Add song to playlist"
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
                        contentDescription = "Remove from playlist"
                        setOnClickListener {
                            removeFromPlaylist(song)
                        }
                    }
                )
            }

            row.addView(label("›", 24f, Color.rgb(174, 159, 194)))

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

        val queue: List<Song> = when {
            selectedPlaylist != null -> {
                val members = playlists[selectedPlaylist].orEmpty()
                songs.filter { members.contains(it.uri.toString()) }
            }

            showFavoritesOnly -> {
                songs.filter {
                    favoriteSongUris.contains(it.uri.toString())
                }
            }

            else -> songs.toList()
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

        p.setMediaItems(makeMediaItems(activeQueue), startIndex, 0L)
        p.prepare()
        p.play()

        updateNowPlaying()
        updatePlayButton()
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
            nowArtist.text = "Your library is ready"
            artwork.setImageResource(android.R.drawable.ic_media_play)
            updateProgress()
            return
        }

        nowTitle.text = song.title
        nowArtist.text = song.artist
        artwork.setImageResource(android.R.drawable.ic_media_play)

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

    private fun updatePlayButton() {
        if (!::playButton.isInitialized) return

        playButton.text = if (player?.isPlaying == true) "Ⅱ" else "▶"
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

        durationText.text = formatTime(duration)
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

        // Invalidate any pending artwork callback and stop its worker.
        artworkRequestId++
        artworkExecutor.shutdownNow()

        player?.removeListener(playerListener)
        player = null

        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }

        controllerFuture = null

        super.onDestroy()
    }
}