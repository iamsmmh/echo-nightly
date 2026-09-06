package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaSession
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.player.core.RecoveryPolicy
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.retries
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.PlayerCommands.getLikeButton
import dev.brahmkshatriya.echo.playback.PlayerCommands.getRepeatButton
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.ResumptionUtils
import dev.brahmkshatriya.echo.playback.exceptions.PlayerException
import dev.brahmkshatriya.echo.utils.Serializer.rootCause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

class PlayerEventListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: MediaSession,
    private val currentFlow: MutableStateFlow<PlayerState.Current?>,
    private val extensions: ExtensionLoader,
    private val throwableFlow: MutableSharedFlow<Throwable>
) : Player.Listener {

    private val player get() = session.player

    private fun updateCustomLayout() = scope.launch(Dispatchers.Main) {
        val item = player.currentMediaItem ?: return@launch
        val supportsLike = withContext(Dispatchers.IO) {
            extensions.music.getExtension(item.extensionId)?.isClient<LikeClient>() ?: false
        }
        val commandButtons = listOfNotNull(
            getRepeatButton(context, player.repeatMode),
            getLikeButton(context, item).takeIf { supportsLike }
        )
        session.setCustomLayout(commandButtons)
    }

    private fun updateCurrentFlow() {
        // Defensive: mediaItemCount can transiently disagree with currentMediaItem
        // during timeline edits; throwing here used to crash the whole playback
        // service (Phase 1 stability fix).
        currentFlow.value = player.currentMediaItem?.let {
            val isPlaying = player.isPlaying && player.playbackState == Player.STATE_READY
            PlayerState.Current(player.currentMediaItemIndex, it, it.isLoaded, isPlaying)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        updateCurrentFlow()
        updateCustomLayout()
        ResumptionUtils.saveIndex(context, player.currentMediaItemIndex)
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        updateCurrentFlow()
        updateCustomLayout()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        updateCurrentFlow()
        scope.launch { ResumptionUtils.saveQueue(context, player) }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateCustomLayout()
        ResumptionUtils.saveRepeat(context, repeatMode)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        ResumptionUtils.saveShuffle(context, shuffleModeEnabled)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        updateCurrentFlow()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        updateCurrentFlow()
        ResumptionUtils.saveCurrentPos(context, player.currentPosition)
        dev.brahmkshatriya.echo.utils.PlaybackRecoveryStore.save(context, player)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
    ) {
        ResumptionUtils.saveCurrentPos(context, player.currentPosition)
    }

    private val maxRetries = 3
    private var currentRetries = 0
    private var last: KClass<*>? = null

    private val recoveryPolicy = RecoveryPolicy(
        maxItemRetries = 2,
        maxConsecutiveFailures = maxRetries
    )

    /**
     * Recovery ladder (Phase 1 - stability): retry the same item, fall over to
     * the next streaming server, skip the track or stop with the surfaced
     * exception - decided by the shared, unit tested [RecoveryPolicy] so the
     * Android player and the KMP player recover identically.
     */
    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        val cause = error.cause ?: error
        val mediaItem = player.currentMediaItem
        scope.launch { throwableFlow.emit(PlayerException(mediaItem, cause)) }
        if (mediaItem == null) return

        val index = player.currentMediaItemIndex

        val old = last
        last = cause.rootCause::class
        currentRetries = if (old != null && old == last) currentRetries + 1 else 0
        if (currentRetries > maxRetries) {
            currentRetries = 0
            player.pause()
            return
        }

        val isNetwork = cause.rootCause is java.io.IOException
        val (retries, serverCount) = runCatching {
            mediaItem.retries to mediaItem.track.servers.size
        }.getOrDefault(0 to 1)

        val decision = recoveryPolicy.decide(
            itemRetries = retries,
            consecutiveFailures = currentRetries,
            serverCount = serverCount,
            isNetworkError = isNetwork,
            hasNext = index < player.mediaItemCount - 1
        ).action

        when (decision) {
            RecoveryPolicy.Action.RetrySameItem,
            RecoveryPolicy.Action.TryNextServer -> {
                val updated = runCatching {
                    when (decision) {
                        RecoveryPolicy.Action.RetrySameItem ->
                            MediaItemUtils.withRetry(mediaItem)

                        else -> {
                            val next = mediaItem.serverIndex + 1
                            MediaItemUtils.withRetry(
                                MediaItemUtils.buildServer(
                                    mediaItem,
                                    if (serverCount > 0) next % serverCount else next
                                )
                            )
                        }
                    }
                }.getOrElse {
                    player.pause()
                    return
                }
                player.replaceMediaItem(index, updated)
                player.prepare()
                player.play()
            }

            RecoveryPolicy.Action.SkipToNext -> {
                currentRetries = 0
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }

            RecoveryPolicy.Action.StopWithError -> {
                currentRetries = 0
                player.pause()
            }
        }
    }
}
