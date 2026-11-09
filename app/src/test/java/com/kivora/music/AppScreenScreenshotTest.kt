package com.kivora.music

import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppScreenScreenshotTest {

    @Test
    fun capturesHomeScreenScreenshot() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            activity.window.decorView.captureRoboImage(
                filePath = "build/outputs/roborazzi/a1-family-media-home.png"
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
