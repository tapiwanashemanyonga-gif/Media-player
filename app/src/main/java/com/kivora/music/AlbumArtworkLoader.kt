package com.kivora.music

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Decodes and caches album artwork away from the UI thread.
 * The caller checks whether a target still represents the current song.
 */
internal class AlbumArtworkLoader(
    private val contentResolver: ContentResolver,
    private val minimumTargetSizePx: Int,
    private val isHostActive: () -> Boolean
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 8L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun load(uri: Uri, targetSizePx: Int, onReady: (Bitmap) -> Unit) {
        val target = targetSizePx.coerceAtLeast(minimumTargetSizePx)
        val key = "$uri@$target"
        cache.get(key)?.let { cached ->
            if (isHostActive()) onReady(cached)
            return
        }

        executor.execute {
            val bitmap = cache.get(key) ?: decode(uri, target)?.also {
                cache.put(key, it)
            }
            if (bitmap != null) {
                handler.post {
                    if (isHostActive()) onReady(bitmap)
                }
            }
        }
    }

    private fun decode(uri: Uri, targetSizePx: Int): Bitmap? {
        return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(
                bounds.outWidth / (sampleSize * 2),
                bounds.outHeight / (sampleSize * 2)
            ) >= targetSizePx
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decodeStream = contentResolver.openInputStream(uri) ?: return null
        decodeStream.use { stream -> BitmapFactory.decodeStream(stream, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        executor.shutdownNow()
        cache.evictAll()
    }
}
