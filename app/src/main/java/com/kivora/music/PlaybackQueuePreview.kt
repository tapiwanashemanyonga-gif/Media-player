package com.kivora.music

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Resolves upcoming queue entries using the current player mode and any matching cached order. */
internal object PlaybackQueuePreview {
    fun entries(
        player: Player,
        cachedOrder: IntArray?,
        cachedItemCount: Int,
        cachedCurrentIndex: Int,
        cachedShuffleEnabled: Boolean,
        cachedRepeatMode: Int
    ): List<Pair<Int, MediaItem>> {
        if (!player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return emptyList()
        val count = player.mediaItemCount
        if (count == 0) return emptyList()
        val currentIndex = player.currentMediaItemIndex
        val matchingCachedOrder = cachedOrder?.takeIf {
            cachedItemCount == count &&
                cachedCurrentIndex == currentIndex &&
                cachedShuffleEnabled == player.shuffleModeEnabled &&
                cachedRepeatMode == player.repeatMode
        }
        if (matchingCachedOrder != null) {
            return matchingCachedOrder.filter { it in 0 until count && it != currentIndex }
                .map { it to player.getMediaItemAt(it) }
        }
        if (currentIndex !in 0 until count) {
            return (0 until count).map { it to player.getMediaItemAt(it) }
        }
        val order = mutableListOf<Int>()
        if (player.shuffleModeEnabled) {
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex in 0 until count && nextIndex != currentIndex) order.add(nextIndex)
            for (index in 0 until count) {
                if (index != currentIndex && index !in order) order.add(index)
            }
        } else {
            for (step in 1 until count) {
                val index = (currentIndex + step) % count
                if (player.repeatMode != Player.REPEAT_MODE_ALL && index < currentIndex) break
                order.add(index)
            }
        }
        return order.map { it to player.getMediaItemAt(it) }
    }
}
