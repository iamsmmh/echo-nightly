package dev.brahmkshatriya.echo.player.audio

import dev.brahmkshatriya.echo.player.domain.EchoLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import dev.brahmkshatriya.echo.player.platform.EchoPlayerAndroid

/**
 * Android playback engine on Media3/ExoPlayer. The shipped Echo Android app
 * uses its own full PlayerService (media session, notification, audio focus,
 * effects); this engine is the shared-module implementation used when the
 * Compose UI is hosted on Android and mirrors the same behaviour: streaming
 * and local playback, buffering state, speed, volume and end-of-item events.
 */
@OptIn(UnstableApi::class)
class AndroidAudioPlayer(
    private val logger: EchoLogger
) : PlayerEngine {

    override val engineState = MutableStateFlow(EngineState())
    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val ended = _ended.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var exoPlayer: ExoPlayer? = null
    private var metadata: NowPlayingInfo? = null
    private var volume: Float = 1f
    private var speed: Float = 1f
    private var currentRequest: EngineRequest? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateState { it.copy(isPlaying = isPlaying, playWhenReady = exoPlayer?.playWhenReady == true) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateState { it.copy(playWhenReady = playWhenReady) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            val endedNow = playbackState == Player.STATE_ENDED
            updateState {
                it.copy(
                    isBuffering = buffering,
                    durationMs = playerDuration()
                )
            }
            if (endedNow) _ended.tryEmit(Unit)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            logger.error(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            updateState { it.copy(isPlaying = false, playWhenReady = false, isBuffering = false, error = error.errorCodeName) }
        }
    }

    private fun playerDuration(): Long =
        exoPlayer?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    private fun obtainPlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val context = EchoPlayerAndroid.appContext
        val player = ExoPlayer.Builder(context).build()
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(listener)
        exoPlayer = player
        return player
    }

    override fun prepare(request: EngineRequest) {
        currentRequest = request
        val player = obtainPlayer()
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(request.headers)
        val dataFactory = androidx.media3.datasource.DefaultDataSource.Factory(EchoPlayerAndroid.appContext, httpFactory)
        val sourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataFactory)
        val mediaItem = MediaItem.Builder().setUri(request.url).setMimeType(request.mimeType).build()
        player.setMediaSource(sourceFactory.createMediaSource(mediaItem), request.startPositionMs.coerceAtLeast(0))
        player.volume = volume
        player.setPlaybackSpeed(speed)
        player.prepare()
        engineState.value = EngineState(positionMs = request.startPositionMs.coerceAtLeast(0), speed = speed)
    }

    override fun play() {
        val player = exoPlayer ?: return
        player.play()
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun stop() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        engineState.value = EngineState(speed = speed)
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        updateState { it.copy(positionMs = positionMs) }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        exoPlayer?.volume = this.volume
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.25f, 3f)
        exoPlayer?.setPlaybackSpeed(this.speed)
        updateState { it.copy(speed = this.speed) }
    }

    override fun setNowPlayingInfo(info: NowPlayingInfo) {
        metadata = info
        // On Android, lock screen/notification metadata is owned by the
        // PlayerService media session in the shipped app; nothing to do here.
    }

    override fun setRemoteCommandListener(listener: RemoteCommandListener?) {
        // MediaSession in PlayerService handles lock screen commands on Android.
    }

    override fun release() {
        exoPlayer?.removeListener(listener)
        exoPlayer?.release()
        exoPlayer = null
        scope.cancel()
    }

    init {
        // Poll position/buffered values; ExoPlayer position events are enough
        // for the shared state cadence.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val player = exoPlayer ?: continue
                if (player.playbackState == Player.STATE_IDLE) continue
                updateState {
                    it.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        bufferedMs = player.bufferedPosition.coerceAtLeast(0),
                        durationMs = playerDuration().takeIf { d -> d > 0 } ?: it.durationMs,
                        isPlaying = player.isPlaying,
                        playWhenReady = player.playWhenReady,
                        suppressed = player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    )
                }
            }
        }
    }

    private fun updateState(block: (EngineState) -> EngineState) {
        engineState.value = block(engineState.value)
    }

    private companion object {
        const val TAG = "AndroidAudioPlayer"
    }
}
