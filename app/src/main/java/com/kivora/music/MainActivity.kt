package com.kivora.music

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

data class Song(val title: String, val artist: String, val uri: Uri)

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var songList: LinearLayout
    private lateinit var nowPlaying: TextView
    private lateinit var playButton: Button
    private val songs = mutableListOf<Song>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadSongs()
            else Toast.makeText(this, "Music permission is needed to scan your library.", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ExoPlayer.Builder(this).build()
        buildScreen()

        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadSongs()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 20)
            setBackgroundColor(android.graphics.Color.rgb(16, 16, 24))
        }

        val heading = TextView(this).apply {
            text = "KIVORA"
            textSize = 30f
            setTextColor(android.graphics.Color.rgb(124, 77, 255))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(heading)

        val subtitle = TextView(this).apply {
            text = "YOUR MUSIC. YOUR WORLD."
            textSize = 12f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 4, 0, 24)
        }
        root.addView(subtitle)

        nowPlaying = TextView(this).apply {
            text = "Choose a song to begin"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 16, 0, 16)
        }
        root.addView(nowPlaying)

        playButton = Button(this).apply {
            text = "▶  Play / Pause"
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (p.mediaItemCount == 0) {
                    Toast.makeText(this@MainActivity, "Choose a song first", Toast.LENGTH_SHORT).show()
                } else if (p.isPlaying) {
                    p.pause()
                    text = "▶  Play"
                } else {
                    p.play()
                    text = "Ⅱ  Pause"
                }
            }
        }
        root.addView(playButton)

        val listHeading = TextView(this).apply {
            text = "YOUR LIBRARY"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 24, 0, 12)
        }
        root.addView(listHeading)

        val scroll = android.widget.ScrollView(this)
        songList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(songList)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    private fun loadSongs() {
        songs.clear()
        songList.removeAllViews()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            val cursor: Cursor? = contentResolver.query(
                collection, projection, selection, null, sortOrder
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn) ?: "Unknown title"
                    val artist = it.getString(artistColumn) ?: "Unknown artist"
                    val uri = ContentUris.withAppendedId(collection, id)
                    songs.add(Song(title, artist, uri))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't scan music: ${e.message}", Toast.LENGTH_LONG).show()
        }

        if (songs.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No music found yet. Add audio files to your device and try again."
                setTextColor(android.graphics.Color.LTGRAY)
                setPadding(0, 20, 0, 20)
            }
            songList.addView(empty)
            return
        }

        songs.forEachIndexed { index, song ->
            val row = TextView(this).apply {
                text = "♫  ${song.title}\n     ${song.artist}"
                textSize = 15f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(12, 18, 12, 18)
                setOnClickListener { playSong(index) }
            }
            songList.addView(row)
            val divider = android.view.View(this).apply {
                setBackgroundColor(android.graphics.Color.DKGRAY)
            }
            songList.addView(divider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ))
        }
    }

    private fun playSong(index: Int) {
        val p = player ?: return
        val song = songs.getOrNull(index) ?: return
        p.setMediaItem(MediaItem.fromUri(song.uri))
        p.prepare()
        p.play()
        nowPlaying.text = "Now playing: ${song.title}"
        playButton.text = "Ⅱ  Pause"
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
