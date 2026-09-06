package dev.brahmkshatriya.echo.player.audio

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single item playback request handed to the platform engine.
 *
 * @param url A remote https/http URL or an absolute local file path.
 * @param headers Optional HTTP headers required to fetch the stream.
 * @param isLocalFile `true` when [url] points at a local file.
 * @param mimeType Optional content type hint.
 * @param startPositionMs Resume position.
 */
data class EngineRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isLocalFile: Boolean = false,
    val mimeType: String? = null,
    val startPositionMs: Long = 0
)

/** Low level, per item engine state. */
data class EngineState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val speed: Float = 1.0f
)

/**
 * The platform audio engine contract.
 *
 *  - Android implementation: `AndroidAudioPlayer` (Media3/ExoPlayer)
 *  - iOS implementation: `IosAudioPlayer` (AVPlayer + AVFoundation)
 *
 * Implementations must never block and must emit [engineState] updates on a
 * regular cadence while playing.
 */
interface PlayerEngine {

    /** Playback state of the currently prepared item. */
    val engineState: StateFlow<EngineState>

    /** Emits when the current item finished playing naturally. */
    val ended: SharedFlow<Unit>

    /** Prepares (and buffers) the given request; does not start playback. */
    fun prepare(request: EngineRequest)

    fun play()

    fun pause()

    fun stop()

    fun seekTo(positionMs: Long)

    /** @param volume linear gain in 0..1 */
    fun setVolume(volume: Float)

    /** @param speed 0.25..3.0 */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Optional metadata sync for system UI (iOS lock screen). Implementations
     * that do not integrate with system surfaces treat this as a no-op.
     */
    fun setNowPlayingInfo(info: NowPlayingInfo) {}

    /**
     * Sets the listener for remote (lock screen / headset) commands. Only used
     * by engines that integrate with system remote command surfaces.
     */
    fun setRemoteCommandListener(listener: RemoteCommandListener?) {}

    fun release()
}
