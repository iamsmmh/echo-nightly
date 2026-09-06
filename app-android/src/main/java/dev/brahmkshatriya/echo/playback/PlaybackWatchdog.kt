package dev.brahmkshatriya.echo.playback

import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Playback watchdog (Phase 1 - stability).
 *
 * Samples the player periodically and escalates through recovery actions when
 * playback appears frozen (buffering forever, silent stall, dead data source).
 * All decisions come from the shared, unit tested [dev.brahmkshatriya.echo.player.core.WatchdogPolicy]
 * so Android and the KMP player behave identically.
 *
 * The watchdog never releases the player and every action is idempotent, making
 * it safe against races with normal playback transitions.
 */
@OptIn(UnstableApi::class)
class PlaybackWatchdog(
    private val player: Player,
    private val settings: SharedPreferences,
    private val scope: CoroutineScope,
    private val onReloadItem: (Player) -> Unit,
    private val onSkipTrack: (Player) -> Unit,
    private val logger: (String) -> Unit = { }
) {

    private var job: Job? = null
    private var lastPosition = -1L
    private var lastProgressAt = System.currentTimeMillis()
    private var lastStateChangeAt = System.currentTimeMillis()
    private var lastState = Player.STATE_IDLE
    private var recoveries = 0

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            lastStateChangeAt = System.currentTimeMillis()
            lastState = playbackState
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            reset()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (player.currentPosition > lastPosition + 250) {
                lastPosition = player.currentPosition
                lastProgressAt = System.currentTimeMillis()
            }
        }
    }

    val enabled: Boolean
        get() = settings.getBoolean(WATCHDOG_KEY, true)

    fun start() {
        if (job != null) return
        player.addListener(listener)
        job = scope.launch {
            while (isActive) {
                if (enabled) tick() else reset()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player.removeListener(listener)
    }

    private fun tick() {
        if (!player.isPlaying) return
        val now = System.currentTimeMillis()
        val sample = dev.brahmkshatriya.echo.player.core.StallSample(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            elapsedSinceProgressMs = now - lastProgressAt,
            elapsedSinceChangeMs = now - lastStateChangeAt,
            recoveryAttempts = recoveries
        )
        val action = policy.decide(sample)
        if (action == dev.brahmkshatriya.echo.player.core.StallRecoveryAction.None) return
        recoveries++
        logger("watchdog: $action after ${sample.elapsedSinceProgressMs}ms (state=$lastState)")
        lastStateChangeAt = now
        lastProgressAt = now
        when (action) {
            dev.brahmkshatriya.echo.player.core.StallRecoveryAction.SeekResume ->
                player.seekTo(player.currentPosition)

            dev.brahmkshatriya.echo.player.core.StallRecoveryAction.RePrepare -> {
                val pos = player.currentPosition
                player.prepare()
                player.seekTo(pos)
                player.play()
            }

            dev.brahmkshatriya.echo.player.core.StallRecoveryAction.ReloadItem -> {
                onReloadItem(player)
            }

            dev.brahmkshatriya.echo.player.core.StallRecoveryAction.SkipTrack -> {
                onSkipTrack(player)
            }

            else -> player.pause()
        }
    }

    private fun reset() {
        recoveries = 0
        lastPosition = -1
        lastProgressAt = System.currentTimeMillis()
        lastStateChangeAt = System.currentTimeMillis()
    }

    companion object {
        const val WATCHDOG_KEY = "playback_watchdog"
        private const val TICK_MS = 5_000L
        private val policy = dev.brahmkshatriya.echo.player.core.WatchdogPolicy(
            stallTimeoutMs = 20_000,
            bufferingTimeoutMs = 30_000,
            maxRecoveriesPerItem = 3
        )

        /** Rebuilds the current media item so the source pipeline reloads it. */
        fun reloadItem(player: Player, rebuild: (androidx.media3.common.MediaItem) -> androidx.media3.common.MediaItem) {
            val item = player.currentMediaItem ?: return
            val index = player.currentMediaItemIndex
            runCatching {
                player.replaceMediaItem(index, rebuild(item))
                player.prepare()
                player.playWhenReady = true
            }
        }
    }
}
