package dev.brahmkshatriya.echo.player.audio

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.audio.recovery.RetryPolicy
import dev.brahmkshatriya.echo.player.audio.recovery.backoffDelayMs
import dev.brahmkshatriya.echo.player.audio.recovery.isTransientNetworkFailure
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Persists/restore the playback session (queue + position). */
interface PlaybackPersister {
    fun save(state: PlaybackState)
    fun restore(): PlaybackState?
    fun clear()
}

/**
 * Resolves a queue item into a playable [ResolvedStream]:
 * downloaded file or imported local library file first (offline first),
 * then the extension's stream.
 */
interface StreamResolver {
    suspend fun resolve(item: QueueItem): ResolvedStream
}

/**
 * Orchestrates the queue ([QueueManager]) and the platform engine
 * ([PlayerEngine]) into one consistent, persisted [PlaybackState].
 *
 * All public methods are safe to call from the UI thread; heavy work is
 * dispatched inside the controller scope.
 */
class PlaybackController(
    private val engine: PlayerEngine,
    private val resolver: StreamResolver,
    val queue: QueueManager,
    private val persister: PlaybackPersister,
    private val logger: EchoLogger,
    private val scope: CoroutineScope,
    private val onTrackStarted: (suspend (item: QueueItem) -> Unit)? = null,
    /** Bounded automatic network retry / stall watchdog configuration. */
    private val retryPolicy: RetryPolicy = RetryPolicy()
) : RemoteCommandListener {

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Transient user visible messages. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var resolveJob: Job? = null
    private var volume: Float = 1f
    private var speed: Float = 1f

    /** Last prepared engine request, retained so the watchdog can re-prepare it. */
    private var lastRequest: EngineRequest? = null
    private var lastRequestWasRemote: Boolean = false

    /** Consecutive auto-recoveries performed for the current track by the watchdog. */
    private var watchdogRecoveriesForTrack: Int = 0
    private var watchdogRecoveredTrackId: String? = null

    // ------------------------------------------------------- audio FX (Phase 5)

    /** Dip-style crossfade window in ms; 0 disables crossfading. */
    private var crossfadeMs: Int = 0
    private var replayGainMode: Int = 0
    private var replayGainPreampDb: Float = 0f
    private var replayGainLimiter: Boolean = true
    /** gain from replay-gain metadata, multiplied into the engine volume */
    private var rgGain: Float = 1f
    /** volume factor pushed by the sleep timer fade (1f = no timer active) */
    private var sleepFactor: Float = 1f
    /** incoming-fade state after an auto advance */
    private var dipFadeInStartMs: Long = -1
    private var dipActive: Boolean = false

    /** Called by the settings layer whenever the audio FX prefs change. */
    fun setAudioFxPreferences(crossfadeMs: Long, replayGainMode: Int, preampDb: Float, limiter: Boolean) {
        this.crossfadeMs = crossfadeMs.coerceIn(0L, 12_000L).toInt()
        this.replayGainMode = replayGainMode.coerceIn(0, 2)
        this.replayGainPreampDb = preampDb
        this.replayGainLimiter = limiter
        applyTrackGain(_state.value.current)
        applyEngineVolume()
    }

    /** Sleep timer ramp: multiplies the master volume while fading out. */
    fun setSleepVolumeFactor(factor: Float) {
        sleepFactor = factor.coerceIn(0f, 1f)
        applyEngineVolume()
    }

    /** The sleep timer hit zero: stop playback gracefully. */
    fun onSleepExpired() {
        engine.pause()
        _state.value = _state.value.copy(isPlaying = false)
    }

    private fun applyEngineVolume() {
        engine.setVolume((volume * sleepFactor * rgGain).coerceIn(0f, 1f))
    }

    private fun applyTrackGain(item: QueueItem?) {
        if (item == null) {
            rgGain = 1f
            return
        }
        rgGain = if (replayGainMode == 0) 1f else {
            val extras = runCatching { item.track.extras }.getOrNull().orEmpty()
            dev.brahmkshatriya.echo.player.audiofx.ReplayGain.resolve(
                mode = replayGainMode,
                trackGainDb = dev.brahmkshatriya.echo.player.audiofx.ReplayGain
                    .parseDb(extras[dev.brahmkshatriya.echo.player.audiofx.ReplayGain.EXTRA_TRACK_GAIN]),
                albumGainDb = dev.brahmkshatriya.echo.player.audiofx.ReplayGain
                    .parseDb(extras[dev.brahmkshatriya.echo.player.audiofx.ReplayGain.EXTRA_ALBUM_GAIN]),
                preampDb = replayGainPreampDb,
                peakLinear = dev.brahmkshatriya.echo.player.audiofx.ReplayGain
                    .parseDb(extras[dev.brahmkshatriya.echo.player.audiofx.ReplayGain.EXTRA_PEAK]),
                limiterEnabled = replayGainLimiter
            ).linear
        }
    }

    init {
        var lastPlaying = false
        scope.launch {
            engine.engineState.collect { engineState ->
                val current = _state.value.current
                _state.value = _state.value.copy(
                    isPlaying = engineState.isPlaying,
                    isBuffering = engineState.isBuffering,
                    positionMs = engineState.positionMs,
                    durationMs = if (engineState.durationMs > 0) engineState.durationMs
                    else current?.track?.duration ?: 0,
                    bufferedMs = engineState.bufferedMs,
                    playbackSpeed = engineState.speed
                )
                if (engineState.isPlaying != lastPlaying) {
                    lastPlaying = engineState.isPlaying
                    pushNowPlaying(engineState.positionMs, engineState.isPlaying)
                }
                maybeStartCrossfadeDip(engineState.positionMs, _state.value.durationMs)
                advanceDipFadeIn()
            }
        }
        scope.launch {
            engine.ended.collect { onTrackEnded() }
        }
        scope.launch {
            queue.state.collect { snapshot ->
                _state.value = _state.value.copy(
                    queue = snapshot.items,
                    currentId = snapshot.currentId,
                    shuffleEnabled = snapshot.shuffleEnabled,
                    repeatMode = snapshot.repeatMode
                )
                persister.save(_state.value)
            }
        }
        // Periodic position persistence so playback can resume after the
        // process is killed.
        scope.launch {
            while (true) {
                delay(5_000)
                if (_state.value.current != null) persister.save(_state.value)
            }
        }
        startWatchdog()
        engine.setRemoteCommandListener(this)
    }

    /**
     * Playback watchdog: samples the engine periodically and, when a remote
     * track has been buffering without progress for longer than the stall
     * timeout, transparently re-prepares it once. If the same track stalls
     * again after that automatic recovery, playback is stopped with a clear
     * error instead of retrying forever.
     */
    private fun startWatchdog() {
        scope.launch {
            val tracker = dev.brahmkshatriya.echo.player.audio.recovery.PlaybackStallTracker(
                stallTimeoutMs = retryPolicy.stallTimeoutMs,
                nowMs = { dev.brahmkshatriya.echo.player.domain.nowEpochMs() }
            )
            while (true) {
                delay(WATCHDOG_TICK_MS)
                val es = engine.engineState.value
                val currentId = _state.value.current?.id
                if (currentId == null) continue
                if (tracker.onTick(currentId, es.isPlaying, es.isBuffering, es.positionMs)) {
                    handleStall(currentId)
                }
            }
        }
    }

    private fun handleStall(currentId: String) {
        val item = _state.value.current?.takeIf { it.id == currentId }
            ?: _state.value.queue.firstOrNull { it.id == currentId }
        // Only attempt an automatic recovery for remote streaming sources.
        val request = lastRequest ?: return
        if (!lastRequestWasRemote || item == null) {
            if (item != null) {
                surfacePlaybackFailure(item, EchoError.Playback("Playback stalled.", null))
            }
            return
        }
        if (watchdogRecoveredTrackId != currentId) {
            watchdogRecoveredTrackId = currentId
            watchdogRecoveriesForTrack = 0
        }
        watchdogRecoveriesForTrack++
        if (watchdogRecoveriesForTrack > retryPolicy.maxAttempts) {
            logger.warn(TAG, "Watchdog gave up on '$currentId' after $watchdogRecoveriesForTrack recoveries")
            surfacePlaybackFailure(item, EchoError.Playback("Playback kept stalling.", null))
            return
        }
        logger.info(TAG, "Watchdog: re-preparing stalled track '$currentId' (recovery $watchdogRecoveriesForTrack)")
        scope.launch {
            engine.prepare(request)
            applyEngineVolume()
            engine.setPlaybackSpeed(speed)
            engine.play()
        }
    }

    /** Restores the last persisted session (paused). */
    // ------------------------------------------- RemoteCommandListener
    // (lock screen / Control Centre / notification commands)

    override fun onPlay() {
        if (!_state.value.isPlaying) playPause()
    }

    override fun onPause() {
        if (_state.value.isPlaying) playPause()
    }

    override fun onTogglePlayPause() = playPause()

    override fun onNext() = next(userInitiated = true)

    override fun onPrevious() = previous()

    override fun onSeekTo(positionMs: Long) = seekTo(positionMs)

    override fun onSkipForward() {
        seekTo(_state.value.positionMs + SKIP_COMMAND_MS)
    }

    override fun onSkipBackward() {
        seekTo((_state.value.positionMs - SKIP_COMMAND_MS).coerceAtLeast(0))
    }

    fun restoreSession() {
        val restored = persister.restore() ?: return
        if (restored.queue.isEmpty()) return
        scope.launch {
            queue.replaceItems(restored.queue, restored.currentId)
            volume = restored.volume
            speed = restored.playbackSpeed
            engine.setVolume(volume)
            engine.setPlaybackSpeed(speed)
            _state.value = restored.copy(isPlaying = false, isResolving = false)
        }
    }

    /** Plays a list of tracks from [startTrackId], replacing the queue. */
    fun playQueue(tracks: List<Track>, extensionId: String, startTrackId: String?, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        scope.launch {
            val current = queue.setQueue(tracks, extensionId, startTrackId, shuffle)
            if (current != null) startCurrent(current)
        }
    }

    fun playPause() {
        val state = _state.value
        val current = state.current
        if (current == null) {
            restoreAndPlayFirst()
            return
        }
        if (state.isResolving) return
        if (state.isPlaying) engine.pause() else engine.play()
    }

    private fun restoreAndPlayFirst() {
        val first = _state.value.queue.firstOrNull() ?: return
        scope.launch { startCurrent(first) }
    }

    fun next(userInitiated: Boolean = true) {
        scope.launch {
            val next = queue.advance(userInitiated)
            if (next != null) startCurrent(next)
            else {
                engine.stop()
                _state.value = _state.value.copy(isPlaying = false, error = null)
            }
        }
    }

    fun previous() {
        scope.launch {
            // Standard music player behaviour: restart the track when more
            // than 3 seconds of it have been played.
            if (_state.value.positionMs > 3_000) {
                engine.seekTo(0)
            } else {
                val prev = queue.rewind()
                if (prev != null) startCurrent(prev)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        val clamped = positionMs.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        engine.seekTo(clamped)
        _state.value = _state.value.copy(positionMs = clamped)
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        applyEngineVolume()
        _state.value = _state.value.copy(volume = volume)
    }

    fun setPlaybackSpeed(value: Float) {
        speed = value.coerceIn(0.25f, 3f)
        engine.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(playbackSpeed = speed)
    }

    fun toggleShuffle() {
        scope.launch {
            val enabled = queue.toggleShuffle()
            _state.value = _state.value.copy(shuffleEnabled = enabled)
        }
    }

    fun cycleRepeat() {
        val mode = queue.cycleRepeatMode()
        _state.value = _state.value.copy(repeatMode = mode)
    }

    fun jumpTo(itemId: String) {
        scope.launch {
            val item = queue.jumpTo(itemId) ?: return@launch
            startCurrent(item)
        }
    }

    fun addToQueue(tracks: List<Track>, extensionId: String, playNext: Boolean) {
        if (tracks.isEmpty()) return
        scope.launch {
            val items = tracks.map { QueueItem(newQueueId(), it, extensionId) }
            if (playNext) queue.addNext(items) else queue.addLater(items)
            if (_state.value.current == null) {
                queue.jumpTo(items.first().id)?.let { startCurrent(it) }
            }
        }
    }

    fun removeFromQueue(itemId: String) {
        scope.launch {
            val becameCurrent = queue.remove(itemId)
            if (becameCurrent != null) startCurrent(becameCurrent)
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        scope.launch { queue.move(from, to) }
    }

    fun clearQueue() {
        engine.stop()
        scope.launch {
            queue.clear()
            _state.value = PlaybackState()
            persister.clear()
        }
    }

    fun stop() {
        engine.pause()
        persister.save(_state.value)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // ------------------------------------------------------------------ core

    private suspend fun startCurrent(item: QueueItem) {
        resolveJob?.cancel()
        resolveJob = scope.launch {
            resetWatchdogFor(item.id)
            _state.value = _state.value.copy(
                current = item, currentId = item.id, isResolving = true, error = null,
                positionMs = 0, durationMs = item.track.duration ?: 0
            )
            pushNowPlaying(0, false)
            onTrackStarted?.invoke(item)

            var failures = 0
            while (true) {
                if (failures > 0) {
                    val backoff = backoffDelayMs(failures, retryPolicy)
                    logger.info(TAG, "Retrying '${item.title}' in ${backoff}ms (failure #$failures)")
                    delay(backoff)
                    // The user may have moved to another track while we waited.
                    if (_state.value.current?.id != item.id) return@launch
                }
                try {
                    val stream = resolver.resolve(item)
                    if (_state.value.current?.id != item.id) return@launch // changed meanwhile
                    lastRequest = EngineRequest(
                        url = stream.url,
                        headers = stream.headers,
                        isLocalFile = stream.isLocalFile,
                        mimeType = stream.mimeType
                    )
                    lastRequestWasRemote = !stream.isLocalFile
                    engine.prepare(lastRequest!!)
                    applyTrackGain(item)
                    if (dipActive) {
                        dipFadeInStartMs = dev.brahmkshatriya.echo.player.domain.nowEpochMs()
                        engine.setVolume(0f)
                    } else {
                        applyEngineVolume()
                    }
                    engine.setPlaybackSpeed(speed)
                    engine.play()
                    _state.value = _state.value.copy(isResolving = false)
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures++
                    // Bounded automatic retry for transient network failures
                    // only; anything else surfaces immediately.
                    if (e.isTransientNetworkFailure() && retryPolicy.canRetry(failures)) {
                        logger.warn(TAG, "Transient failure resolving '${item.title}' (failure #$failures): ${e.message}")
                        continue
                    }
                    surfacePlaybackFailure(item, e.toPlaybackError())
                    return@launch
                }
            }
        }
    }

    /**
     * Unconditionally stops playback for the current item and surfaces [error]
     * to the state + transient message flow.
     */
    private fun surfacePlaybackFailure(item: QueueItem, error: EchoError) {
        logger.error(TAG, "Failed to resolve stream for '${item.title}': ${error.message}", error)
        _state.value = _state.value.copy(isResolving = false, isPlaying = false, error = error.userMessage)
        _messages.tryEmit(error.userMessage)
        engine.stop()
    }

    /** Resets the watchdog recovery budget whenever a new track is loaded. */
    private fun resetWatchdogFor(itemId: String) {
        watchdogRecoveredTrackId = itemId
        watchdogRecoveriesForTrack = 0
    }

    private fun maybeStartCrossfadeDip(positionMs: Long, durationMs: Long) {
        if (crossfadeMs <= 0 || dipActive || !dipFadeInStartMs.isIdle()) return
        val fadeStart = dev.brahmkshatriya.echo.player.audiofx.CrossfadePolicy
            .fadeStartMs(durationMs, crossfadeMs)
        if (fadeStart < 0 || positionMs < fadeStart) return
        val gain = dev.brahmkshatriya.echo.player.audiofx.CrossfadePolicy
            .outgoingGain(positionMs, fadeStart, crossfadeMs)
        dipActive = true // one-way latch: keep ramping to silence into the transition
        engine.setVolume((volume * sleepFactor * rgGain * gain).coerceIn(0f, 1f))
    }

    private fun Long.isIdle(): Boolean = this == -1L

    private fun advanceDipFadeIn() {
        val start = dipFadeInStartMs
        if (start <= 0) return
        val elapsed = dev.brahmkshatriya.echo.player.domain.nowEpochMs() - start
        val gain = dev.brahmkshatriya.echo.player.audiofx.CrossfadePolicy
            .incomingGain(elapsed, crossfadeMs)
        engine.setVolume((volume * sleepFactor * rgGain * gain).coerceIn(0f, 1f))
        if (gain >= 1f) {
            dipFadeInStartMs = -1
            dipActive = false
            applyEngineVolume()
        }
    }

    private suspend fun onTrackEnded() {
        val snapshot = queue.state.value
        if (snapshot.repeatMode == RepeatMode.ONE) {
            engine.seekTo(0)
            engine.play()
            return
        }
        val next = queue.advance(userInitiated = false)
        if (next != null) {
            if (crossfadeMs > 0) dipActive = true
            startCurrent(next)
        } else {
            dipActive = false
            dipFadeInStartMs = -1
            applyEngineVolume()
            _state.value = _state.value.copy(isPlaying = false, positionMs = 0)
            pushNowPlaying(0, false)
            persister.save(_state.value)
        }
    }

    private fun pushNowPlaying(positionMs: Long, playing: Boolean) {
        val item = _state.value.current ?: return
        val track = item.track
        engine.setNowPlayingInfo(
            NowPlayingInfo(
                id = item.trackKey,
                title = track.title,
                artist = track.artists.joinToString(", ") { it.name },
                album = track.album?.title,
                durationMs = _state.value.durationMs,
                positionMs = positionMs,
                playbackRate = if (playing) speed else 0f,
                isPlaying = playing,
                artworkRequestUrl = (track.cover as? dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder)
                    ?.request?.url,
                artworkHeaders = (track.cover as? dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder)
                    ?.request?.headers ?: emptyMap()
            )
        )
    }

    private fun Throwable.toPlaybackError(): EchoError = when (this) {
        is EchoError -> this
        else -> EchoError.Playback(message ?: "Unknown playback failure", this)
    }

    private companion object {
        const val TAG = "PlaybackController"
        const val WATCHDOG_TICK_MS = 2_000L
    }
}

/**
 * Persists the playback session as JSON inside a [dev.brahmkshatriya.echo.player.platform.KeyValueStore].
 */
class KeyValuePlaybackPersister(
    private val store: dev.brahmkshatriya.echo.player.platform.KeyValueStore,
    private val json: Json
) : PlaybackPersister {

    override fun save(state: PlaybackState) {
        runCatching {
            store.putString(KEY_QUEUE, json.encodeToString(PlaybackState.serializer(), state))
        }
    }

    override fun restore(): PlaybackState? {
        val raw = store.getString(KEY_QUEUE) ?: return null
        return runCatching { json.decodeFromString(PlaybackState.serializer(), raw) }
            .onFailure { store.remove(KEY_QUEUE) }
            .getOrNull()
    }

    override fun clear() {
        store.remove(KEY_QUEUE)
    }

    private companion object {
        const val KEY_QUEUE = "echo.playback.session"
    }
}

private const val SKIP_COMMAND_MS = 30_000L
