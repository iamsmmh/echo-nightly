@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.echo.player.audio

import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.common.helpers.toNSData
import dev.brahmkshatriya.echo.player.domain.EchoError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
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
import platform.AVFAudio.*
import dev.brahmkshatriya.echo.player.core.recovery.AudioSessionRecovery
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.setActive
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.MediaPlayer.*
import platform.UIKit.*
import platform.darwin.NSObjectProtocol

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
    private val notificationObservers = mutableListOf<NSObjectProtocol>()
    private val remoteCommandTargets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private val sessionRecovery = AudioSessionRecovery()

    private var currentRequest: EngineRequest? = null
    private var metadata: NowPlayingInfo? = null
    private var artworkImage: UIImage? = null
    private var artworkJob: Job? = null
    private var volume: Float = 1f
    private var speed: Float = 1f

    init {
        configureAudioSession()
        observeInterruptions()
        observeRouteChanges()
        observeItemEnd()
        observeMediaServices()
        setupRemoteCommands()
        scope.launch {
            while (true) {
                delay(if (sessionRecovery.wantsPlayback) 500L else 2_000L)
                publishTick()
            }
        }
    }

    // ---------------------------------------------------------- audio session

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        val categorySuccess = session.setCategory(AVAudioSessionCategoryPlayback, null)
        if (!categorySuccess) {
            logger.error(TAG, "AVAudioSession category error: failed to set playback category")
        }
    }

    private fun activateAudioSession(): Boolean {
        val session = AVAudioSession.sharedInstance()
        val success = session.setActive(true, null)
        if (!success) {
            logger.warn(TAG, "AVAudioSession activation error: failed to activate session")
            updateState { it.copy(isPlaying = false, error = "Audio output is unavailable. Try playing again.") }
        }
        return success
    }

    private fun observeInterruptions() {
        notificationObservers += NSNotificationCenter.defaultCenter().addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue()
        ) { notification ->
            val userInfo = notification?.userInfo
            // userInfo key literals are the stable public ABI constants
            val type = (userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.longValue
            when (type) {
                INTERRUPTION_TYPE_BEGAN -> {
                    applySessionAction(sessionRecovery.interruptionBegan())
                }
                INTERRUPTION_TYPE_ENDED -> {
                    val options = (userInfo?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.longValue ?: 0
                    applySessionAction(sessionRecovery.interruptionEnded(options and INTERRUPTION_OPTION_SHOULD_RESUME != 0L))
                }
            }
        }
    }

    private fun observeRouteChanges() {
        notificationObservers += NSNotificationCenter.defaultCenter().addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue()
        ) { notification ->
            val reason = (notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.longValue
            if (reason == ROUTE_REASON_OLD_DEVICE_UNAVAILABLE) {
                // Headphones/Bluetooth disconnected
                applySessionAction(sessionRecovery.routeLost(pauseOnRouteLoss()))
            }
        }
    }

    private fun observeItemEnd() {
        notificationObservers += NSNotificationCenter.defaultCenter().addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue()
        ) { notification ->
            if (notification?.`object` == player?.currentItem && player?.currentItem != null) {
                updateState { it.copy(isPlaying = false, isBuffering = false) }
                _ended.tryEmit(Unit)
            }
        }
        notificationObservers += NSNotificationCenter.defaultCenter().addObserverForName(
            AVPlayerItemFailedToPlayToEndTimeNotification, null, NSOperationQueue.mainQueue()
        ) { notification ->
            if (notification?.`object` == player?.currentItem && player?.currentItem != null) {
                updateState { it.copy(isPlaying = false, isBuffering = false, error = "Playback failed. Try playing again.") }
            }
        }
    }

    private fun observeMediaServices() {
        val center = NSNotificationCenter.defaultCenter()
        notificationObservers += center.addObserverForName(
            AVAudioSessionMediaServicesWereLostNotification, null, NSOperationQueue.mainQueue()
        ) { applySessionAction(sessionRecovery.servicesLost()) }
        notificationObservers += center.addObserverForName(
            AVAudioSessionMediaServicesWereResetNotification, null, NSOperationQueue.mainQueue()
        ) { applySessionAction(sessionRecovery.servicesReset()) }
    }

    private fun applySessionAction(action: AudioSessionRecovery.Action) {
        when (action) {
            AudioSessionRecovery.Action.PAUSE -> player?.pause()
            AudioSessionRecovery.Action.RESUME -> resumePlayer()
            AudioSessionRecovery.Action.REBUILD_PAUSED,
            AudioSessionRecovery.Action.REBUILD_AND_RESUME -> {
                val saved = currentRequest?.copy(startPositionMs = engineState.value.positionMs)
                if (saved != null) {
                    prepare(saved)
                    if (action == AudioSessionRecovery.Action.REBUILD_AND_RESUME) resumePlayer()
                } else configureAudioSession()
            }
            AudioSessionRecovery.Action.NONE -> Unit
        }
        updateState { it.copy(
            isPlaying = if (action == AudioSessionRecovery.Action.PAUSE) false else it.isPlaying,
            playWhenReady = sessionRecovery.wantsPlayback,
            suppressed = sessionRecovery.interrupted || !sessionRecovery.servicesAvailable
        ) }
        pushNowPlaying()
    }

    // ---------------------------------------------------------- engine API

    override fun prepare(request: EngineRequest) {
        // The controller calls prepare -> play synchronously on the main thread.
        // Scheduling prepare in another coroutine loses that play command.
        currentRequest = request
        configureAudioSession()
        detachPlayerInternals()
        val item = buildItem(request)
        val newPlayer = AVPlayer(playerItem = item)
        newPlayer.volume = volume
        newPlayer.actionAtItemEnd = AVPlayerActionAtItemEndPause
        newPlayer.allowsExternalPlayback = true
        player = newPlayer
        engineState.value = EngineState(positionMs = request.startPositionMs.coerceAtLeast(0), speed = speed)
        if (request.startPositionMs > 0) {
            newPlayer.seekToTime(CMTimeMakeWithSeconds(request.startPositionMs / 1000.0, 600))
        }
    }

    private fun buildItem(request: EngineRequest): AVPlayerItem {
        return if (request.isLocalFile) {
            AVPlayerItem(uRL = NSURL.fileURLWithPath(request.url))
        } else {
            val url = NSURL.URLWithString(request.url)
                ?: throw EchoError.Playback("Invalid stream URL")
            // NOTE: custom HTTP headers are not attached on iOS — Echo stream
            // URLs (Subsonic token auth) carry their credentials in the URL.
            AVPlayerItem(uRL = url)
        }
    }

    override fun play() = applySessionAction(sessionRecovery.play())

    private fun resumePlayer() {
        val current = player ?: return
        if (!activateAudioSession()) return
        current.playImmediatelyAtRate(speed)
        updateState { it.copy(playWhenReady = true, error = null) }
    }

    override fun pause() = applySessionAction(sessionRecovery.pause())

    override fun stop() {
        sessionRecovery.pause()
        detachPlayerInternals()
        currentRequest = null
        metadata = null
        artworkJob?.cancel()
        artworkImage = null
        engineState.value = EngineState(speed = speed)
        pushNowPlaying()
        updateRemoteAvailability()
    }

    override fun seekTo(positionMs: Long) {
        val current = player ?: return
        val clamped = positionMs.coerceAtLeast(0)
        val time = CMTimeMakeWithSeconds(clamped / 1000.0, 600)
        current.seekToTime(time)
        updateState { it.copy(positionMs = clamped) }
        pushNowPlaying()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        player?.volume = this.volume
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3f) ?: 1f
        if (engineState.value.isPlaying) player?.rate = this.speed
        updateState { it.copy(speed = this.speed) }
    }

    override fun setNowPlayingInfo(info: NowPlayingInfo) {
        val previous = metadata
        val changed = previous?.artworkRequestUrl != info.artworkRequestUrl || previous?.artworkHeaders != info.artworkHeaders
        metadata = info
        if (changed) {
            artworkJob?.cancel()
            artworkImage = null
            val artworkUrl = info.artworkRequestUrl
            val fetcher = artworkFetcher
            if (artworkUrl != null && fetcher != null) {
                artworkJob = scope.launch {
                    val bytes = try { fetcher(artworkUrl, info.artworkHeaders) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { null }
                    if (bytes != null && metadata?.artworkRequestUrl == artworkUrl) {
                        artworkImage = bytes.toUIImage()
                        pushNowPlaying()
                    }
                }
            }
        }
        updateRemoteAvailability()
        pushNowPlaying()
    }

    override fun setRemoteCommandListener(listener: RemoteCommandListener?) {
        remoteListener = listener
        updateRemoteAvailability()
    }

    private var remoteListener: RemoteCommandListener? = null

    override fun release() {
        stop()
        val center = NSNotificationCenter.defaultCenter()
        notificationObservers.forEach { center.removeObserver(it) }
        notificationObservers.clear()
        unregisterRemoteCommands()
        remoteListener = null
        AVAudioSession.sharedInstance().setActive(false, null)
        scope.cancel()
    }

    // ---------------------------------------------------------- internals

    private fun detachPlayerInternals() {
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        player = null
    }

    private fun publishTick() {
        val current = player ?: return
        val item = current.currentItem ?: return
        val positionSeconds = cmSeconds(current.currentTime())
        val durationSeconds = cmSeconds(item.duration)
        // NSValue.timeRangeValue is not exposed in this cinterop binding, so
        // the buffered position falls back to the current position on iOS.
        val bufferedSeconds = positionSeconds
        val isBuffering = current.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        updateState {
            it.copy(
                positionMs = secondsToMs(positionSeconds),
                durationMs = if (durationSeconds.isNaN() || durationSeconds <= 0) it.durationMs
                else secondsToMs(durationSeconds),
                bufferedMs = secondsToMs(bufferedSeconds),
                isPlaying = current.timeControlStatus == AVPlayerTimeControlStatusPlaying,
                isBuffering = isBuffering,
                playWhenReady = sessionRecovery.wantsPlayback,
                suppressed = sessionRecovery.interrupted || !sessionRecovery.servicesAvailable,
                error = if (item.status == AVPlayerItemStatusFailed) "Playback failed. Try playing again." else it.error
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
        if (!seconds.isFinite() || seconds < 0) 0 else (seconds * 1000).toLong()

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
        val map = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to info.title,
            MPMediaItemPropertyArtist to info.artist,
            MPMediaItemPropertyPlaybackDuration to ((state.durationMs.takeIf { it > 0 } ?: info.durationMs) / 1000.0),
            MPNowPlayingInfoPropertyElapsedPlaybackTime to ((if (currentRequest == null) info.positionMs else state.positionMs) / 1000.0),
            MPNowPlayingInfoPropertyPlaybackQueueIndex to info.queueIndex,
            MPNowPlayingInfoPropertyPlaybackQueueCount to info.queueCount,
            MPNowPlayingInfoPropertyPlaybackRate to (if (state.isPlaying) state.speed.toDouble() else 0.0)
        )
        info.album?.let { map[MPMediaItemPropertyAlbumTitle] = it }
        artworkImage?.let { image ->
            map[MPMediaItemPropertyArtwork] = platform.MediaPlayer.MPMediaItemArtwork(
                boundsSize = CGSizeMake(600.0, 600.0)
            ) { _ -> image }
        }
        center.nowPlayingInfo = map
    }

    // ---------------------------------------------------------- remote commands

    private fun updateRemoteAvailability() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        val available = metadata != null && remoteListener != null
        center.playCommand.enabled = available
        center.pauseCommand.enabled = available
        center.togglePlayPauseCommand.enabled = available
        center.nextTrackCommand.enabled = available && metadata?.canGoNext == true
        center.previousTrackCommand.enabled = available && metadata?.canGoPrevious == true
        center.skipForwardCommand.enabled = available
        center.skipBackwardCommand.enabled = available
        center.changePlaybackPositionCommand.enabled = available
    }

    private fun setupRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        fun add(command: MPRemoteCommand, handler: (RemoteCommandListener) -> Unit) {
            val target = command.addTargetWithHandler {
                val listener = remoteListener
                if (listener == null || metadata == null) MPRemoteCommandHandlerStatusCommandFailed
                else { handler(listener); MPRemoteCommandHandlerStatusSuccess }
            }
            remoteCommandTargets += command to target
        }
        add(center.playCommand) { it.onPlay() }
        add(center.pauseCommand) { it.onPause() }
        add(center.togglePlayPauseCommand) { it.onTogglePlayPause() }
        add(center.nextTrackCommand) { it.onNext() }
        add(center.previousTrackCommand) { it.onPrevious() }
        add(center.skipForwardCommand) { it.onSkipForward() }
        add(center.skipBackwardCommand) { it.onSkipBackward() }
        center.skipForwardCommand.preferredIntervals = listOf(30.0)
        center.skipBackwardCommand.preferredIntervals = listOf(30.0)
        val command = center.changePlaybackPositionCommand
        val target = command.addTargetWithHandler { event ->
            val position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
            val listener = remoteListener
            if (position == null || !position.isFinite() || position < 0 || listener == null || metadata == null) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                listener.onSeekTo((position * 1000).toLong())
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        remoteCommandTargets += command to target
        updateRemoteAvailability()
    }

    private fun unregisterRemoteCommands() {
        remoteCommandTargets.forEach { (command, target) -> command.removeTarget(target) }
        remoteCommandTargets.clear()
    }
}
