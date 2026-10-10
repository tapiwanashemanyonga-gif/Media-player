
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

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

    private val songs = mutableListOf<Song>()
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateNowPlaying()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
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

        buildScreen()
        connectToPlaybackService()
        handler.post(progressUpdater)

        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) ==
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
            label("YOUR MUSIC. YOUR WORLD.", 11f, Color.rgb(148, 163, 184)).apply {
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
            setBackgroundColor(Color.rgb(30, 34, 49))
        }
        root.addView(searchBox, LinearLayout.LayoutParams(-1, dp(48)))

        val libraryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(10))
        }
        libraryHeader.addView(
            label("YOUR LIBRARY", 17f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        libraryCount = label("0 songs", 12f, Color.rgb(148, 163, 184))
        libraryHeader.addView(libraryCount)
        root.addView(libraryHeader)

        val scroll = ScrollView(this)
        songList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(songList)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            setBackgroundColor(Color.rgb(27, 30, 44))
        }
        card.addView(label("NOW PLAYING", 10f, Color.rgb(167, 139, 250), true))

        val trackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }

        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(45, 39, 72))
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = "Album artwork"
        }
        trackRow.addView(artwork, LinearLayout.LayoutParams(dp(64), dp(64)))

        val trackInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        nowTitle = label("Choose a song", 15f, Color.WHITE, true)
        nowArtist = label("Your library is ready", 12f, Color.rgb(148, 163, 184))
        trackInfo.addView(nowTitle)
        trackInfo.addView(nowArtist)
        trackRow.addView(trackInfo, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(trackRow)

        progress = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(167, 139, 250)
            )
            thumbTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(196, 181, 253)
            )

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    userSeeking = true
                }

                override fun onProgressChanged(
                    seekBar: SeekBar,
                    value: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        val d = player?.duration ?: 0L
                        elapsedText.text = formatTime(d * value / 1000L)
                    }
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val d = player?.duration ?: 0L
                    if (d > 0L && d != C.TIME_UNSET) {
                        player?.seekTo(d * seekBar.progress / 1000L)
                    }
                    userSeeking = false
                }
            })
        }
        card.addView(progress)

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        elapsedText = label("0:00", 10f, Color.rgb(148, 163, 184))
        durationText = label("0:00", 10f, Color.rgb(148, 163, 184))
        times.addView(elapsedText, LinearLayout.LayoutParams(0, -2, 1f))
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
                if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem()
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
                if (it.hasNextMediaItem()) it.seekToNextMediaItem()
            }
        }

        repeatButton = control("↻") {
            player?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
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
                topMargin = dp(10)
            }
        )

        setContentView(root)

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {
                renderSongs(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        updatePlayButton()
        updateShuffleButton()
        updateRepeatButton()
    }

    private fun control(symbol: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = if (symbol == "▶" || symbol == "Ⅱ") 22f else 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { action() }
        }

    private fun loadSongs() {
        songs.clear()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
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
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn)
                        ?.takeIf { value -> value.isNotBlank() } ?: "Unknown title"
                    val artistValue = it.getString(artistColumn)
                    val artist = artistValue?.takeIf {
                        value -> value.isNotBlank() && value != "<unknown>"
                    } ?: "Unknown artist"
                    val albumId = it.getLong(albumColumn)

                    val artUri = if (albumId > 0L) {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId
                        )
                    } else null

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

    private fun makeMediaItems(): List<MediaItem> =
        songs.map { song ->
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
            if (p.mediaItemCount > 0) p.clearMediaItems()
            updateNowPlaying()
            return
        }

        if (p.mediaItemCount == 0 || p.mediaItemCount != songs.size) {
            p.setMediaItems(makeMediaItems())
            p.prepare()
        }

        updateNowPlaying()
    }

    private fun renderSongs(query: String) {
        if (!::songList.isInitialized || !::libraryCount.isInitialized) return

        songList.removeAllViews()
        val normalized = query.trim().lowercase(Locale.getDefault())

        val matches = songs.withIndex().filter { entry ->
            normalized.isEmpty() ||
                entry.value.title.lowercase(Locale.getDefault()).contains(normalized) ||
                entry.value.artist.lowercase(Locale.getDefault()).contains(normalized)
        }

        libraryCount.text = if (normalized.isEmpty()) {
            "${songs.size} songs"
        } else {
            "${matches.size} found"
        }

        if (matches.isEmpty()) {
            val message = if (songs.isEmpty()) {
                "No music found yet.\nAdd audio files to your device and try again."
            } else {
                "No songs match your search."
            }

            songList.addView(
                label(message, 14f, Color.rgb(148, 163, 184)).apply {
                    setPadding(dp(4), dp(18), dp(4), dp(18))
                }
            )
            return
        }

        matches.forEach { entry ->
            val songIndex = entry.index
            val song = entry.value

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener { playSong(songIndex) }
            }

            row.addView(
                label("♫", 23f, Color.rgb(167, 139, 250), true).apply {
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(42), dp(46))
            )

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
            }

            info.addView(
                label(song.title, 14f, Color.WHITE, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )

            info.addView(
                label(song.artist, 12f, Color.rgb(148, 163, 184)).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                }
            )

            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("›", 24f, Color.rgb(148, 163, 184)))
            songList.addView(row)

            songList.addView(
                View(this).apply {
                    setBackgroundColor(Color.rgb(36, 40, 56))
                },
                LinearLayout.LayoutParams(-1, dp(1))
            )
        }
    }

    private fun playSong(index: Int) {
        val p = player

        if (p == null) {
            Toast.makeText(
                this,
                "Connecting to music service. Please wait.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (index !in songs.indices) return

        if (p.mediaItemCount != songs.size) {
            p.setMediaItems(makeMediaItems())
            p.prepare()
        }

        p.seekTo(index, 0L)
        p.play()
        updateNowPlaying()
        updatePlayButton()
    }

    private fun updateNowPlaying() {
        if (!::nowTitle.isInitialized) return

        val p = player
        val song = p?.let { songs.getOrNull(it.currentMediaItemIndex) }

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

        try {
            song.artworkUri?.let { uri ->
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bitmap ->
                        artwork.setImageBitmap(bitmap)
                    }
                }
            }
        } catch (_: Exception) {
            // Keep the placeholder if artwork is unavailable.
        }

        updateProgress()
    }

    private fun updatePlayButton() {
        if (!::playButton.isInitialized) return
        playButton.text = if (player?.isPlaying == true) "Ⅱ" else "▶"
        playButton.setTextColor(Color.rgb(196, 181, 253))
    }

    private fun updateShuffleButton() {
        if (!::shuffleButton.isInitialized) return

        shuffleButton.setTextColor(
            if (player?.shuffleModeEnabled == true) {
                Color.rgb(167, 139, 250)
            } else {
                Color.rgb(148, 163, 184)
            }
        )
    }

    private fun updateRepeatButton() {
        if (!::repeatButton.isInitialized) return

        repeatButton.text = when (player?.repeatMode ?: Player.REPEAT_MODE_OFF) {
            Player.REPEAT_MODE_ONE -> "↻¹"
            else -> "↻"
        }

        val enabled =
            (player?.repeatMode ?: Player.REPEAT_MODE_OFF) != Player.REPEAT_MODE_OFF

        repeatButton.setTextColor(
            if (enabled) Color.rgb(167, 139, 250)
            else Color.rgb(148, 163, 184)
        )
    }

    private fun updateProgress() {
        if (!::progress.isInitialized) return
        val p = player ?: return

        val rawDuration = p.duration
        val duration = if (rawDuration > 0L && rawDuration != C.TIME_UNSET) {
            rawDuration
        } else {
            0L
        }

        val position = p.currentPosition.coerceAtLeast(0L)

        if (!userSeeking) {
            progress.progress = if (duration > 0L) {
                ((position * 1000L) / duration).toInt()
            } else {
                0
            }
            elapsedText.text = formatTime(position)
        }

        durationText.text = formatTime(duration)
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(
            Locale.getDefault(),
            "%d:%02d",
            totalSeconds / 60,
            totalSeconds % 60
        )
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        player?.removeListener(playerListener)
        player = null

        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }
        controllerFuture = null

        super.onDestroy()
    }
}
