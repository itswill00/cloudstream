package com.lagradost.cloudstream3.ui.player.live

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.mvvm.debugWarning
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object LiveHelper {
    private val liveManagers = WeakHashMap<Player, Pair<LiveManager, Player.Listener>>()

    @OptIn(UnstableApi::class)
    fun registerPlayer(player: Player?) {
        if (player == null) {
            debugWarning { "LiveHelper registerPlayer called with null player!" }
            return
        }

        // Prevent duplicates
        synchronized(liveManagers) {
            if (liveManagers.contains(player)) {
                return
            }
        }

        val liveManager = LiveManager(player)
        /**
         * The player has to be held weakly here. The map is a WeakHashMap, so a strong
         * reference to the key from the value would keep the entry alive forever and
         * leak the player.
         */
        val playerRef = WeakReference(player)
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val currentPlayer = playerRef.get() ?: return
                val window = Timeline.Window()
                timeline.getWindow(currentPlayer.currentMediaItemIndex, window)
                if (window.isDynamic) {
                    liveManager.submitLivestreamChunk(LivestreamChunk(window.durationMs))
                }
                super.onTimelineChanged(timeline, reason)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                val currentPlayer = playerRef.get() ?: return
                val timeAheadOfLive = liveManager.getTimeAheadOfLive(newPosition.positionMs)

                // Seek back to the optimal live spot
                if (timeAheadOfLive > 100) {
                    currentPlayer.seekTo(newPosition.positionMs - timeAheadOfLive)
                }
            }
        }

        synchronized(liveManagers) {
            player.addListener(listener)
            liveManagers[player] = liveManager to listener
        }
    }

    fun unregisterPlayer(player: Player?) {
        if (player == null) {
            debugWarning { "LiveHelper unregisterPlayer called with null player!" }
            return
        }

        synchronized(liveManagers) {
            // Prevent duplicates
            if (!liveManagers.contains(player)) {
                return
            }

            liveManagers[player]?.let { (_, listener) ->
                player.removeListener(listener)
            }
            liveManagers.remove(player)
        }
    }

    fun getLiveManager(player: Player?) = liveManagers[player]?.first
}
