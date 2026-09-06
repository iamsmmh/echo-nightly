package dev.brahmkshatriya.echo.player.audio

import kotlinx.serialization.Serializable

/**
 * Aggregated playback state consumed by the UI on both platforms. This is the
 * single source of truth rendered by the Now Playing screen, the mini player,
 * the Android notification (via the service) and the iOS lock screen.
 */
@Serializable
data class PlaybackState(
    val current: QueueItem? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isResolving: Boolean = false,
    val volume: Float = 1.0f,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val queue: List<QueueItem> = emptyList(),
    val currentId: String? = null,
    /** A short, user facing error message; null when there is no error. */
    val error: String? = null
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val hasTrack: Boolean get() = current != null
}

/** A fully resolved, playable stream handed to the platform player. */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isLocalFile: Boolean = false,
    val mimeType: String? = null,
    /** Where the stream came from; used for reporting. */
    val source: Source
) {
    enum class Source { LOCAL_LIBRARY, DOWNLOAD, EXTENSION, URL }
}

/**
 * Metadata pushed to system surfaces (Android media session, iOS
 * MPNowPlayingInfoCenter) whenever the track or playback state changes.
 */
data class NowPlayingInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val positionMs: Long,
    val playbackRate: Float,
    val isPlaying: Boolean,
    val artworkRequestUrl: String? = null,
    val artworkHeaders: Map<String, String> = emptyMap()
)

/**
 * Remote command callbacks (lock screen / Control Center / headset buttons)
 * implemented by [PlaybackController] and consumed by the iOS audio engine.
 */
interface RemoteCommandListener {
    fun onPlay()
    fun onPause()
    fun onTogglePlayPause()
    fun onNext()
    fun onPrevious()
    fun onSeekTo(positionMs: Long)
    fun onSkipForward()
    fun onSkipBackward()
}
