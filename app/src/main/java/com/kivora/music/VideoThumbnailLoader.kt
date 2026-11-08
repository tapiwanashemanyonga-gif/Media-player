package com.kivora.music

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Loads and caches MediaStore video thumbnails without blocking the UI thread.
 * Multiple visible rows for the same video share one in-flight decode.
 */
internal class VideoThumbnailLoader(
    private val contentResolver: ContentResolver,
    private val density: Float,
    private val isHostActive: () -> Boolean
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 16L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val pendingTargets = HashMap<String, MutableList<ImageView>>()

    fun load(video: DeviceVideo, imageView: ImageView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val key = video.uri.toString()
        imageView.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        val shouldDecode = synchronized(pendingTargets) {
            val targets = pendingTargets.getOrPut(key) { mutableListOf() }
            targets.add(imageView)
            targets.size == 1
        }
        if (!shouldDecode) return

        val width = (112f * density).toInt().coerceAtLeast(1)
        val height = (76f * density).toInt().coerceAtLeast(1)
        executor.execute {
            val bitmap = try {
                contentResolver.loadThumbnail(video.uri, Size(width, height), null)
            } catch (_: Exception) {
                null
            }
            handler.post {
                if (bitmap != null && isHostActive()) cache.put(key, bitmap)
                val targets = synchronized(pendingTargets) {
                    pendingTargets.remove(key).orEmpty()
                }
                if (bitmap != null && isHostActive()) {
                    targets.forEach { target ->
                        if (target.tag == key) target.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    fun close() {
        executor.shutdownNow()
        synchronized(pendingTargets) {
            pendingTargets.clear()
        }
    }
}
