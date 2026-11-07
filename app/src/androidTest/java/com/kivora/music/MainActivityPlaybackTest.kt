package com.kivora.music

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class MainActivityPlaybackTest {

    @Test
    fun musicAndVideoPlayersPauseEachOtherWhenPlaybackStarts() {
        val launchIntent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java
        ).putExtra("a1.instrumentation.disable_ads", true)
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            val players = awaitPlayers(scenario)
            assertNotNull("Music service controller should connect", players.first)
            assertNotNull("Video player should be created", players.second)

            val audioFile = createTestWav(scenario)
            val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile))

            // Starting the second player must pause the first one.
            scenario.onActivity {
                players.second.setMediaItem(mediaItem)
                players.second.prepare()
                players.second.play()
            }
            assertTrue("Video player should start the local test media", awaitState(scenario) { it.second.isPlaying })
            scenario.onActivity { activity ->
                // Exercise the same exclusivity hook used by MainActivity.playSong().
                activity.stopVideoForMusicPlayback()
                players.first.setMediaItem(mediaItem)
                players.first.prepare()
                players.first.play()
            }
            assertTrue("Music service should start the local test media", awaitState(scenario) { it.first.isPlaying })
            assertTrue(
                "Starting music must pause the competing video player",
                awaitState(scenario) { it.first.isPlaying && !it.second.isPlaying }
            )

            // Exercise the reverse direction as well.
            scenario.onActivity { activity ->
                // Exercise the same pause hook used by MainActivity.playDeviceVideo().
                activity.pauseMusicForVideoPlayback()
                players.second.setMediaItem(mediaItem)
                players.second.prepare()
                players.second.play()
            }
            assertTrue(
                "Starting video must pause the competing music service",
                awaitState(scenario) { it.second.isPlaying && !it.first.isPlaying }
            )
        }
    }

    private fun awaitPlayers(
        scenario: ActivityScenario<MainActivity>
    ): Pair<Player, Player> {
        repeat(100) {
            var result: Pair<Player, Player>? = null
            scenario.onActivity { activity ->
                val musicField = MainActivity::class.java.getDeclaredField("player").apply {
                    isAccessible = true
                }
                val videoField = MainActivity::class.java.getDeclaredField("videoPlayer").apply {
                    isAccessible = true
                }
                val music = musicField.get(activity) as? Player
                val video = videoField.get(activity) as? Player
                if (music != null && video != null) result = music to video
            }
            result?.let { return it }
            Thread.sleep(100L)
        }
        throw AssertionError("Playback players did not become available")
    }

    private fun awaitState(
        scenario: ActivityScenario<MainActivity>,
        predicate: (Pair<Player, Player>) -> Boolean
    ): Boolean {
        repeat(100) {
            var matched = false
            scenario.onActivity { activity ->
                val music = MainActivity::class.java.getDeclaredField("player").apply {
                    isAccessible = true
                }.get(activity) as? Player
                val video = MainActivity::class.java.getDeclaredField("videoPlayer").apply {
                    isAccessible = true
                }.get(activity) as? Player
                if (music != null && video != null) matched = predicate(music to video)
            }
            if (matched) return true
            Thread.sleep(100L)
        }
        return false
    }

    private fun createTestWav(scenario: ActivityScenario<MainActivity>): File {
        var file: File? = null
        scenario.onActivity { activity ->
            file = File(activity.cacheDir, "a1-playback-regression.wav")
        }
        val output = requireNotNull(file)
        val sampleRate = 44_100
        val sampleCount = sampleRate * 8
        val dataSize = sampleCount * 2
        FileOutputStream(output).use { stream ->
            stream.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeLe32(stream, 36 + dataSize)
            stream.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            writeLe32(stream, 16)
            writeLe16(stream, 1)
            writeLe16(stream, 1)
            writeLe32(stream, sampleRate)
            writeLe32(stream, sampleRate * 2)
            writeLe16(stream, 2)
            writeLe16(stream, 16)
            stream.write("data".toByteArray(Charsets.US_ASCII))
            writeLe32(stream, dataSize)
            repeat(sampleCount) { index ->
                val sample = (sin(2.0 * PI * 440.0 * index / sampleRate) * 4_000).toInt()
                writeLe16(stream, sample)
            }
        }
        return output
    }

    private fun writeLe16(stream: FileOutputStream, value: Int) {
        stream.write(value and 0xff)
        stream.write((value shr 8) and 0xff)
    }

    private fun writeLe32(stream: FileOutputStream, value: Int) {
        writeLe16(stream, value and 0xffff)
        writeLe16(stream, (value ushr 16) and 0xffff)
    }
}
