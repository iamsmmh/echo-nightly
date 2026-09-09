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
    scope: CoroutineScope,
    private val onTrackStarted: (suspend (item: QueueItem) -> Unit)? = null,
    /** Bounded automatic network retry / stall watchdog configuration. */
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val onTrackCompleted: (suspend (QueueItem, Long) -> Unit)? = null
) : RemoteCommandListener {

    private val controllerJob = kotlinx.coroutines.SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + controllerJob)
    // Capture before the initial empty QueueSnapshot can overwrite durable state.
    private var savedSession: PlaybackState? = persister.restore()
    private var sessionClaimed = false
    private var restoring = false
    private var playRequested = false
    private var preparedItemId: String? = null
    private var startedItemId: String? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Transient user visible messages. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var resolveJob: Job? = null
    private var recoveryJob: Job? = null
    private var consecutiveAutoSkips: Int = 0
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
        onPause()
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
                if (current == null || preparedItemId != current.id || _state.value.isResolving) return@collect
                _state.value = _state.value.copy(
                    isPlaying = engineState.isPlaying,
                    isBuffering = engineState.isBuffering,
                    positionMs = engineState.positionMs,
                    durationMs = if (engineState.durationMs > 0) engineState.durationMs
                    else current?.track?.duration ?: 0,
                    bufferedMs = engineState.bufferedMs,
                    playbackSpeed = engineState.speed,
                    error = engineState.error ?: _state.value.error
                )
                if (engineState.isPlaying && startedItemId != current.id) {
                    startedItemId = current.id
                    consecutiveAutoSkips = 0
                    onTrackStarted?.invoke(current)
                }
                if (!engineState.suppressed) playRequested = engineState.playWhenReady
                if (engineState.error != null) playRequested = false
                if (engineState.isPlaying != lastPlaying) {
                    lastPlaying = engineState.isPlaying
                    pushNowPlaying(engineState.positionMs, engineState.isPlaying)
                    persistSession()
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
                    current = snapshot.items.firstOrNull { it.id == snapshot.currentId },
                    originalQueue = snapshot.originalItems,
                    shuffleEnabled = snapshot.shuffleEnabled,
                    repeatMode = snapshot.repeatMode
                )
                if (sessionClaimed && !restoring) {
                    persistSession()
                    pushNowPlaying(_state.value.positionMs, _state.value.isPlaying)
                }
            }
        }
        // Periodic position persistence so playback can resume after the
        // process is killed.
        scope.launch {
            while (true) {
                delay(5_000)
                if (_state.value.current != null && !restoring) persistSession()
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
                if (tracker.onTick(currentId, playRequested && !es.suppressed && !_state.value.isResolving, es.isBuffering, es.positionMs)) {
                    if (currentId != null) handleStall(currentId)
                }
            }
        }
    }

    private fun handleStall(currentId: String) {
        val item = _state.value.current?.takeIf { it.id == currentId }
            ?: _state.value.queue.firstOrNull { it.id == currentId }
        val request = lastRequest?.takeIf { preparedItemId == currentId }
        if (item == null) return
        // Local files that stall are a permanent media problem, not a stream expiry.
        if (!lastRequestWasRemote) {
            surfacePlaybackFailure(item, EchoError.Playback("Playback stalled.", null))
            return
        }
        if (watchdogRecoveredTrackId != currentId) {
            watchdogRecoveredTrackId = currentId
            watchdogRecoveriesForTrack = 0
        }
        watchdogRecoveriesForTrack++
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            if (!playRequested || _state.value.current?.id != currentId) return@launch
            try {
                when (watchdogRecoveriesForTrack) {
                    1 -> {
                        val existing = request ?: run {
                            startCurrent(item, _state.value.positionMs)
                            return@launch
                        }
                        logger.info(TAG, "Watchdog: re-preparing stalled track '$currentId'")
                        val resumed = existing.copy(startPositionMs = _state.value.positionMs)
                        engine.prepare(resumed)
                        lastRequest = resumed
                        applyEngineVolume()
                        engine.setPlaybackSpeed(speed)
                        engine.play()
                    }
                    2 -> {
                        // Drop the cached stream URL and re-resolve (URLs expire).
                        logger.info(TAG, "Watchdog: refreshing stream for '$currentId'")
                        lastRequest = null
                        preparedItemId = null
                        startCurrent(item, _state.value.positionMs)
                    }
                    else -> {
                        if (consecutiveAutoSkips >= MAX_CONSECUTIVE_AUTO_SKIPS) {
                            logger.warn(TAG, "Watchdog: too many consecutive skips, stopping")
                            surfacePlaybackFailure(item, EchoError.Playback("Playback kept stalling.", null))
                            return@launch
                        }
                        val nextItem = queue.advance(userInitiated = false)
                        if (nextItem == null) {
                            surfacePlaybackFailure(item, EchoError.Playback("Playback kept stalling.", null))
                        } else {
                            consecutiveAutoSkips++
                            logger.warn(TAG, "Watchdog: skipping stalled track '$currentId'")
                            startCurrent(nextItem)
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                surfacePlaybackFailure(item, failure.toPlaybackError())
            }
        }
    }

    /** Restores the last persisted session (paused). */
    // ------------------------------------------- RemoteCommandListener
    // (lock screen / Control Centre / notification commands)

    override fun onPlay() {
        if (!sessionClaimed) restoreSession()
        playRequested = true
        scope.launch {
            val current = _state.value.current ?: return@launch
            if (_state.value.isResolving) return@launch
            if (preparedItemId != current.id || lastRequest == null || _state.value.error != null) {
                startCurrent(current, _state.value.positionMs)
            } else engine.play()
        }
    }

    override fun onPause() {
        playRequested = false
        engine.pause()
        _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
        persistSession()
        pushNowPlaying(_state.value.positionMs, false)
    }

    override fun onTogglePlayPause() = playPause()
    override fun onNext() = next(userInitiated = true)
    override fun onPrevious() = previous()
    override fun onSeekTo(positionMs: Long) = seekTo(positionMs)
    override fun onSkipForward() = seekTo((_state.value.positionMs + SKIP_COMMAND_MS).coerceAtLeast(0))
    override fun onSkipBackward() = seekTo((_state.value.positionMs - SKIP_COMMAND_MS).coerceAtLeast(0))

    /** Idempotent, paused restoration; pressing Play resolves at the saved position. */
    fun restoreSession() {
        if (sessionClaimed) return
        sessionClaimed = true
        val restored = savedSession ?: return
        savedSession = null
        if (restored.queue.isEmpty() || restored.schemaVersion > 2) return
        restoring = true
        scope.launch {
            try {
                val current = queue.restore(QueueSnapshot(
                    items = restored.queue, currentId = restored.currentId ?: restored.current?.id,
                    shuffleEnabled = restored.shuffleEnabled, repeatMode = restored.repeatMode,
                    originalItems = restored.originalQueue
                ))
                volume = restored.volume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
                speed = restored.playbackSpeed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3f) ?: 1f
                _state.value = restored.copy(
                    current = current, currentId = current?.id, queue = queue.items,
                    originalQueue = queue.state.value.originalItems,
                    volume = volume, playbackSpeed = speed,
                    positionMs = restored.positionMs.coerceIn(0, restored.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE),
                    isPlaying = false, isBuffering = false, isResolving = false, error = null
                )
                applyTrackGain(current)
                applyEngineVolume()
                engine.setPlaybackSpeed(speed)
                pushNowPlaying(_state.value.positionMs, false)
            } finally {
                restoring = false
            }
        }
    }

    /** Plays a list of tracks from [startTrackId], replacing the queue. */
    fun playQueue(tracks: List<Track>, extensionId: String, startTrackId: String?, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        sessionClaimed = true
        savedSession = null
        playRequested = true
        scope.launch {
            val current = queue.setQueue(tracks, extensionId, startTrackId, shuffle)
            if (current != null) startCurrent(current)
        }
    }

    fun playPause() {
        if (playRequested || _state.value.isPlaying) onPause() else onPlay()
    }

    /** Mixed-provider queues retain each item's origin (universal search / playlists). */
    fun playItems(items: List<QueueItem>, startId: String? = items.firstOrNull()?.id) {
        if (items.isEmpty()) return
        sessionClaimed = true
        savedSession = null
        playRequested = true
        scope.launch {
            queue.replaceItems(items, startId)?.let { startCurrent(it) }
        }
    }

    fun next(userInitiated: Boolean = true) {
        resolveJob?.cancel()
        recoveryJob?.cancel()
        playRequested = true
        scope.launch {
            val next = queue.advance(userInitiated)
            if (next != null) startCurrent(next)
            else {
                playRequested = false
                engine.pause()
                _state.value = _state.value.copy(isPlaying = false, error = null)
            }
        }
    }

    fun previous() {
        playRequested = true
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
        lastRequest = lastRequest?.copy(startPositionMs = clamped)
        persistSession()
        pushNowPlaying(clamped, _state.value.isPlaying)
    }

    fun setVolume(value: Float) {
        volume = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        applyEngineVolume()
        _state.value = _state.value.copy(volume = volume)
    }

    fun setPlaybackSpeed(value: Float) {
        speed = value.takeIf { it.isFinite() }?.coerceIn(0.25f, 3f) ?: 1f
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
        scope.launch {
            val mode = queue.cycleRepeatMode()
            _state.value = _state.value.copy(repeatMode = mode)
        }
    }

    fun jumpTo(itemId: String) {
        playRequested = true
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
                playRequested = true
                sessionClaimed = true
                queue.jumpTo(items.first().id)?.let { startCurrent(it) }
            }
        }
    }

    fun removeFromQueue(itemId: String) {
        scope.launch {
            val becameCurrent = queue.remove(itemId)
            if (becameCurrent != null) startCurrent(becameCurrent)
            else if (queue.items.isEmpty()) clearQueue()
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        scope.launch { queue.move(from, to) }
    }

    fun clearQueue() {
        resolveJob?.cancel()
        resolveJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        sessionClaimed = true
        savedSession = null
        playRequested = false
        preparedItemId = null
        lastRequest = null
        engine.stop()
        scope.launch {
            queue.clear()
            _state.value = PlaybackState()
            persister.clear()
        }
    }

    fun stop() = onPause()

    /** Stop observers before releasing the platform engine. */
    fun release() {
        stop()
        engine.setRemoteCommandListener(null)
        controllerJob.cancel()
    }

    private fun persistSession() {
        if (sessionClaimed && !restoring) persister.save(_state.value)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // ------------------------------------------------------------------ core

    private suspend fun startCurrent(item: QueueItem, startPositionMs: Long = 0) {
        resolveJob?.cancel()
        val caller = kotlinx.coroutines.coroutineContext[Job]
        if (recoveryJob != null && recoveryJob != caller) {
            recoveryJob?.cancel()
            recoveryJob = null
        }
        engine.pause()
        preparedItemId = null
        lastRequest = null
        startedItemId = null
        resolveJob = scope.launch {
            resetWatchdogFor(item.id)
            _state.value = _state.value.copy(
                current = item, currentId = item.id, isResolving = true, error = null,
                positionMs = startPositionMs, durationMs = item.track.duration ?: 0,
                isPlaying = false, isBuffering = false
            )
            pushNowPlaying(startPositionMs, false)

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
                        mimeType = stream.mimeType,
                        startPositionMs = _state.value.positionMs
                    )
                    lastRequestWasRemote = !stream.isLocalFile
                    engine.prepare(lastRequest!!)
                    preparedItemId = item.id
                    applyTrackGain(item)
                    if (dipActive) {
                        dipFadeInStartMs = dev.brahmkshatriya.echo.player.domain.nowEpochMs()
                        engine.setVolume(0f)
                    } else {
                        applyEngineVolume()
                    }
                    engine.setPlaybackSpeed(speed)
                    _state.value = _state.value.copy(isResolving = false)
                    if (playRequested) engine.play()
                    persistSession()
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
        playRequested = false
        preparedItemId = null
        lastRequest = null
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
        if (crossfadeMs <= 0 || !playRequested || !dipFadeInStartMs.isIdle()) return
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
        val endedItem = _state.value.current ?: return
        if (preparedItemId != endedItem.id) return
        onTrackCompleted?.invoke(endedItem, _state.value.durationMs)
        val snapshot = queue.state.value
        if (snapshot.repeatMode == RepeatMode.ONE) {
            startedItemId = null
            engine.seekTo(0)
            engine.play()
            return
        }
        val next = queue.advance(userInitiated = false)
        if (next != null) {
            if (crossfadeMs > 0) dipActive = true
            startCurrent(next)
        } else {
            playRequested = false
            engine.pause()
            engine.seekTo(0)
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
                    ?.request?.headers ?: emptyMap(),
                queueIndex = queue.items.indexOfFirst { it.id == item.id }.coerceAtLeast(0),
                queueCount = queue.size,
                canGoNext = queue.state.value.repeatMode == RepeatMode.ALL || queue.items.lastOrNull()?.id != item.id,
                canGoPrevious = queue.size > 0
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
        const val MAX_CONSECUTIVE_AUTO_SKIPS = 3
    }
}

private const val SKIP_COMMAND_MS = 30_000L
