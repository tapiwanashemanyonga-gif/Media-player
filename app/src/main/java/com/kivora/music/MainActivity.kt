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
import java.util.Locale

data class Song(
    val title: String,
    val artist: String,
    val uri: Uri,
    val artworkUri: Uri?
)

class MainActivity : AppCompatActivity() {

    private var player: MediaController? = null

    private var controllerFuture:
        com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

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

    override fun onCreate(savedInstanceState: Bundle