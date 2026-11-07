package com.kivora.music

import android.content.Intent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityCompatibilityTest {

    @Test
    fun activityLaunchesAndSurvivesRecreation() {
        val launchIntent = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java
        ).putExtra("a1.instrumentation.disable_ads", true)
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("MainActivity should remain active after launch", activity.isFinishing)
                assertNotNull("Activity window should be available", activity.window)
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertNotNull("Activity content container should exist", content)
                assertNotNull("The app should attach its root view", content.getChildAt(0))
            }

            // Exercises the lifecycle path used by rotation, theme changes and some OEM
            // configuration updates without relying on a particular screen size.
            scenario.recreate()

            scenario.onActivity { activity ->
                assertFalse("MainActivity should remain active after recreation", activity.isFinishing)
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertNotNull("Recreated activity should attach its root view", content.getChildAt(0))
            }
        }
    }
}
