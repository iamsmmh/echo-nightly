@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.echo.player.audio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.common.helpers.toNSData
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.ui.ArtworkDecoder
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreMedia.timeRangeValue
import platform.AVFoundation.seekToTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.MediaPlayer.*
import platform.UIKit.*

private const val TAG = "IosAudioPlayer"

// Raw values of AVAudioSession interruption/route constants (kept numeric to
// avoid depending on enum import details).
private const val INTERRUPTION_TYPE_ENDED = 0L
private const val INTERRUPTION_TYPE_BEGAN = 1L
private const val INTERRUPTION_OPTION_SHOULD_RESUME = 1L
private const val ROUTE_REASON_OLD_DEVICE_UNAVAILABLE = 2L

/**
 * iOS playback engine built on AVFoundation:
 *
 *  - [AVPlayer] for HTTP(S) streaming and local file playback
 *  - [AVAudioSession] configured for `.playback` (music, background audio),
 *    with interruption (calls, Siri) and audio route change handling
 *  - [MPNowPlayingInfoCenter] lock screen / Control Center metadata
 *  - [MPRemoteCommandCenter] play/pause/next/previous/seek remote commands
 *
 * State is continuously published as [EngineState] for the shared
 * [PlaybackController]; the controller (queue/repeat/shuffle logic) lives in
 * common code and drives this engine.
 */
class IosAudioPlayer(
    private val logger: EchoLogger,
    private val artworkFetcher: (suspend (url: String, headers: Map<String, String>) -> ByteArray?)? = null,
    private val pauseOnRouteLoss: () -> Boolean = { true }
) : PlayerEngine {

    override val engineState = MutableStateFlow(EngineState())
    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val ended = _ended.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: AVPlayer? = null
    private var timeObserverToken: Any? = null
    private val notificationObservers = mutableListOf<NSObjectProtocol>()
    private val remoteCommandTargets = mutableListOf<Any>()

    private var currentRequest: EngineRequest? = null
    private var metadata: NowPlayingInfo? = null
    private var artworkImage: UIImage? = null
    private var artworkJob: Job? = null
    private var wasPlayingBeforeInterruption = false
    private var volume: Float = 1f
    private var speed: Float = 1f

    init {
        configureAudioSession()
        observeInterruptions()
        observeRouteChanges()
        observeItemEnd()
        setupRemoteCommands()
    }

    // ---------------------------------------------------------- audio session

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        val categoryError = session.setCategory(AVAudioSessionCategoryPlayback)
        if (categoryError != null) {
            logger.error(TAG, "AVAudioSession category error: ${categoryError.localizedDescription}")
        }
    }

    private fun activateAudioSession() {
        val session = AVAudioSession.sharedInstance()
        val error = session.setActive(true)
        if (error != null) {
            logger.warn(TAG, "AVAudioSession activation error: ${error.localizedDescription}")
        }
    }

    private fun observeInterruptions() {
        notificationObservers += NSNotificationCenter.defaultCenter().addObserverForName(
            name = AVAudioSession.interruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue()
        ) { notification ->
            val userInfo = notification?.userInfo
            // userInfo key literals are the stable public ABI constants
            val type = (userInfo?.get("AVAudioSessionInterruptionType") as? NSNumber)?.longValue
            when (type) {
                INTERRUPTION_TYPE_BEGAN -> {
                    wasPlayingBeforeInterruption = engineState.value.isPlaying
                    player?.pause()
                    updateState { it.copy(isPlaying = false) }
                    pushNowPlaying()
                }
                INTERRUPTION_TYPE_ENDED -> {
                    val options = (userInfo?.get("AVAudioSessionInterruptionOption") as? NSNumber)?.longValue ?: 0
                    if (options and INTERRUPTION_OPTION_SHOULD_RESUME != 0L && wasPlayingBeforeInterruption) {
                        activateAudioSession()
                        play()
                    }
                }
            }
        }
    }

    private fun observeRouteChanges() {
        notificationObservers += NSNotificationCenter.defaultCenter().addObserverForName(
            name = AVAudioSession.routeChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue()
        ) { notification ->
            val reason = (notification?.userInfo?.get("AVAudioSessionRouteChangeReason") as? NSNumber)?.longValue
            if (reason == ROUTE_REASON_OLD_DEVICE_UNAVAILABLE) {
                // Headphones/Bluetooth disconnected
                if (pauseOnRouteLoss()) {
                    player?.pause()
                    updateState { it.copy(isPlaying = false) }
                    pushNowPlaying()
                    logger.info(TAG, "Playback paused after audio route loss")
                }
            }
        }
    }

    private fun observeItemEnd() {
        notificationObservers += NSNotificationCenter.defaultCenter().addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue()
        ) { _ ->
            _ended.tryEmit(Unit)
        }
    }

    // ---------------------------------------------------------- engine API

    override fun prepare(request: EngineRequest) {
        scope.launch {
            currentRequest = request
            configureAudioSession()
            detachPlayerInternals()
            try {
                val item = buildItem(request)
                val newPlayer = AVPlayer(playerItem = item)
                newPlayer.volume = volume
                newPlayer.actionAtItemEnd = AVPlayerActionAtItemEndPause
                player = newPlayer
                attachTimeObserver(newPlayer)
                engineState.value = EngineState(
                    positionMs = request.startPositionMs,
                    speed = speed
                )
                if (request.startPositionMs > 0) {
                    newPlayer.seekToTime(CMTimeMakeWithSeconds(request.startPositionMs / 1000.0, 600))
                }
            } catch (e: Throwable) {
                val error = EchoError.Playback("Could not prepare playback: ${e.message}")
                logger.error(TAG, error.userMessage, e)
                engineState.value = EngineState()
            }
        }
    }

    private fun buildItem(request: EngineRequest): AVPlayerItem {
        return if (request.isLocalFile) {
            AVPlayerItem(uRL = NSURL.fileURLWithPath(request.url))
        } else {
            val url = NSURL.URLWithString(request.url)
                ?: throw EchoError.Playback("Invalid stream URL")
            if (request.headers.isEmpty()) {
                AVPlayerItem(uRL = url)
            } else {
                @Suppress("UNCHECKED_CAST")
                val headerOptions = mapOf(
                    "AVURLAssetHTTPHeaderFieldsKey" to
                        request.headers.entries.map { (key, value) -> "$key: $value" }
                ) as Map<kotlin.AnyObject, *>
                AVPlayerItem(
                    asset = AVURLAsset(
                        uRL = url,
                        options = headerOptions
                    )
                )
            }
        }
    }

    override fun play() {
        val current = player ?: return
        activateAudioSession()
        current.play()
        if (speed != 1f) current.rate = speed
        updateState { it.copy(isPlaying = true) }
        pushNowPlaying()
    }

    override fun pause() {
        val current = player ?: return
        current.pause()
        updateState { it.copy(isPlaying = false) }
        pushNowPlaying()
    }

    override fun stop() {
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        detachPlayerInternals()
        engineState.value = EngineState(speed = speed)
    }

    override fun seekTo(positionMs: Long) {
        val current = player ?: return
        val time = CMTimeMakeWithSeconds(positionMs / 1000.0, 600)
        current.seekToTime(time)
        updateState { it.copy(positionMs = positionMs) }
        pushNowPlaying()
    }

    override fun setVolume(newVolume: Float) {
        volume = newVolume.coerceIn(0f, 1f)
        player?.volume = volume
    }

    override fun setPlaybackSpeed(newSpeed: Float) {
        speed = newSpeed.coerceIn(0.25f, 3f)
        if (engineState.value.isPlaying) player?.rate = speed
        updateState { it.copy(speed = speed) }
    }

    override fun setNowPlayingInfo(info: NowPlayingInfo) {
        metadata = info
        if (artworkImage != null && info.artworkRequestUrl == null) artworkImage = null
        artworkJob?.cancel()
        val artworkUrl = info.artworkRequestUrl
        val fetcher = artworkFetcher
        if (artworkUrl != null && fetcher != null) {
            artworkImage = null
            artworkJob = scope.launch {
                val bytes = runCatching { fetcher(artworkUrl, info.artworkHeaders) }.getOrNull()
                if (bytes != null && metadata?.id == info.id) {
                    artworkImage = bytes.toUIImage()
                    pushNowPlaying()
                }
            }
        }
        pushNowPlaying()
    }

    override fun setRemoteCommandListener(listener: RemoteCommandListener?) {
        remoteListener = listener
    }

    private var remoteListener: RemoteCommandListener? = null

    override fun release() {
        detachPlayerInternals()
        player?.replaceCurrentItemWithPlayerItem(null)
        player = null
        val center = NSNotificationCenter.defaultCenter()
        notificationObservers.forEach { center.removeObserver(it) }
        notificationObservers.clear()
        unregisterRemoteCommands()
        scope.cancel()
    }

    // ---------------------------------------------------------- internals

    private fun attachTimeObserver(current: AVPlayer) {
        timeObserverToken = current.addPeriodicTimeObserverForInterval(
            interval = CMTimeMake(value = 1, timescale = 2),
            queue = null
        ) { _ -> scope.launch { publishTick() } }
    }

    private fun detachPlayerInternals() {
        timeObserverToken?.let { token -> player?.removeTimeObserver(token) }
        timeObserverToken = null
    }

    private fun publishTick() {
        val current = player ?: return
        val item = current.currentItem ?: return
        val positionSeconds = cmSeconds(current.currentTime())
        val durationSeconds = cmSeconds(item.duration)
        val bufferedSeconds = run {
            val ranges = item.loadedTimeRanges
            val first = ranges.firstOrNull() as? platform.Foundation.NSValue
            if (first == null) positionSeconds
            else {
                val endSeconds = cmSeconds(CMTimeRangeGetEnd(first.timeRangeValue))
                if (endSeconds.isNaN()) positionSeconds else endSeconds
            }
        }
        val isBuffering = current.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        updateState {
            it.copy(
                positionMs = secondsToMs(positionSeconds),
                durationMs = if (durationSeconds.isNaN() || durationSeconds <= 0) it.durationMs
                else secondsToMs(durationSeconds),
                bufferedMs = secondsToMs(bufferedSeconds),
                isPlaying = current.timeControlStatus == AVPlayerTimeControlStatusPlaying,
                isBuffering = isBuffering
            )
        }
        // Keep the lock screen clock in sync while playing
        if (metadata != null && engineState.value.isPlaying) pushNowPlaying()
    }

    /** CMTime has no `.seconds` member in Kotlin — compute from value/timescale. */
    private fun cmSeconds(time: kotlinx.cinterop.CValue<platform.CoreMedia.CMTime>): Double =
        time.useContents {
            if (timescale == 0) Double.NaN else value.toDouble() / timescale
        }

    private fun secondsToMs(seconds: Double): Long =
        if (seconds.isNaN() || seconds < 0) 0 else (seconds * 1000).toLong()

    private fun updateState(block: (EngineState) -> EngineState) {
        engineState.value = block(engineState.value)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun ByteArray.toUIImage(): UIImage? = runCatching {
        UIImage(data = toNSData())
    }.getOrNull()

    // ---------------------------------------------------------- now playing

    private fun pushNowPlaying() {
        val info = metadata
        val center = MPNowPlayingInfoCenter.defaultCenter()
        if (info == null) {
            center.nowPlayingInfo = null
            return
        }
        val state = engineState.value
        val map = platform.Foundation.NSMutableDictionary()
        map.setObject(info.title, forKey = MPMediaItemPropertyTitle)
        map.setObject(info.artist, forKey = MPMediaItemPropertyArtist)
        map.setObject(state.durationMs / 1000.0, forKey = MPMediaItemPropertyPlaybackDuration)
        map.setObject(state.positionMs / 1000.0, forKey = MPNowPlayingInfoPropertyElapsedPlaybackTime)
        map.setObject(if (state.isPlaying) state.speed.toDouble() else 0.0, forKey = MPNowPlayingInfoPropertyPlaybackRate)
        info.album?.let { map.setObject(it, forKey = MPMediaItemPropertyAlbumTitle) }
        artworkImage?.let { image ->
            map.setObject(
                platform.MediaPlayer.MPMediaItemArtwork(
                    boundsSize = CGSizeMake(600.0, 600.0)
                ) { _ -> image },
                forKey = MPMediaItemPropertyArtwork
            )
        }
        center.nowPlayingInfo = map
    }

    // ---------------------------------------------------------- remote commands

    private fun setupRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()

        fun add(command: MPRemoteCommand, handler: () -> Unit) {
            remoteCommandTargets += command.addTargetWithHandler { _ ->
                handler()
                MPRemoteCommandHandlerStatusSuccess
            }
        }

        add(center.playCommand) { val listener = remoteListener; if (listener != null) listener.onPlay() else play() }
        add(center.pauseCommand) { val listener = remoteListener; if (listener != null) listener.onPause() else pause() }
        add(center.togglePlayPauseCommand) { remoteListener?.onTogglePlayPause() }
        add(center.nextTrackCommand) { remoteListener?.onNext() }
        add(center.previousTrackCommand) { remoteListener?.onPrevious() }
        add(center.skipForwardCommand) { remoteListener?.onSkipForward() }
        add(center.skipBackwardCommand) { remoteListener?.onSkipBackward() }
        center.skipForwardCommand.preferredIntervals = listOf(30.0)
        center.skipBackwardCommand.preferredIntervals = listOf(30.0)

        remoteCommandTargets += center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val positionEvent = event as? MPChangePlaybackPositionCommandEvent
            if (positionEvent != null) {
                remoteListener?.onSeekTo((positionEvent.positionTime * 1000).toLong())
                MPRemoteCommandHandlerStatusSuccess
            } else {
                MPRemoteCommandHandlerStatusCommandFailed
            }
        }
    }

    private fun unregisterRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        listOf(
            center.playCommand, center.pauseCommand, center.togglePlayPauseCommand,
            center.nextTrackCommand, center.previousTrackCommand,
            center.skipForwardCommand, center.skipBackwardCommand,
            center.changePlaybackPositionCommand
        ).zip(remoteCommandTargets) { command, target -> command.removeTarget(target) }
        remoteCommandTargets.clear()
    }
}

/** Skia based artwork decoding for iOS (compose ui ships skiko). */
class IosArtworkDecoder : ArtworkDecoder {
    override fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}