package com.kivora.music

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppManifestRobolectricTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val packageManager get() = context.packageManager

    @Test
    fun exposesCorrectAppIdentityAndMainActivity() {
        val appInfo = packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals("com.a1family.media", context.packageName)
        assertEquals("A1 FAMILY MEDIA", packageManager.getApplicationLabel(appInfo).toString())

        val mainActivity = packageManager.getActivityInfo(
            ComponentName(context.packageName, "com.kivora.music.MainActivity"),
            0
        )
        assertNotNull("Main activity must remain declared", mainActivity)
        assertTrue("Main activity must remain exported for launcher and file-open intents", mainActivity.exported)
    }

    @Test
    fun declaresMediaPermissionsAndPlaybackForegroundService() {
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(permissions.contains(Manifest.permission.READ_MEDIA_AUDIO))
        assertTrue(permissions.contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertTrue(permissions.contains("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
        assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))

        val playbackService = packageInfo.services.orEmpty()
            .firstOrNull { it.name == "com.kivora.music.KivoraPlaybackService" }
        assertNotNull("Playback service must remain registered", playbackService)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(
                "Playback service must declare the mediaPlayback foreground type",
                playbackService!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0
            )
        }
    }

    @Test
    fun acceptsExternalAudioAndVideoOpenIntents() {
        val audioIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://test/audio.mp3"), "audio/mpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val videoIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://test/video.mp4"), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        assertTrue(
            packageManager.queryIntentActivities(audioIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == "com.kivora.music.MainActivity" }
        )
        assertTrue(
            packageManager.queryIntentActivities(videoIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == "com.kivora.music.MainActivity" }
        )
    }
}
