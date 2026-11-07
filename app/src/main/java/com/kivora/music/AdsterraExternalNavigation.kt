package com.kivora.music

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

/**
 * Opens an advert destination in the device browser only after a genuine
 * user gesture. Background redirects and ad resources remain in the WebView.
 */
internal object AdsterraExternalNavigation {
    fun shouldOpenExternally(
        context: Context,
        uri: Uri,
        hasGesture: Boolean
    ): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return true
        if (!hasGesture) return false

        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No browser is available to open this advert.", Toast.LENGTH_LONG).show()
            true
        } catch (_: SecurityException) {
            Toast.makeText(context, "Couldn't open this advert in a browser.", Toast.LENGTH_LONG).show()
            true
        }
    }
}
