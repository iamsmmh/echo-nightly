package dev.brahmkshatriya.echo.player.core.radio

import dev.brahmkshatriya.echo.player.core.RetryPolicy
import dev.brahmkshatriya.echo.player.core.recovery.StreamFailure
import dev.brahmkshatriya.echo.player.core.recovery.StreamFailureKind
import dev.brahmkshatriya.echo.player.domain.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Observable state of a live radio connection. */
enum class RadioConnectionState { IDLE, CONNECTING, PLAYING, RECONNECTING, FAILED }

/**
 * Keeps a live radio stream connected.
 *
 * Radio differs from on-demand playback in three ways that this manager encodes:
 *
 * 1. **A clean end-of-stream is a failure, not a completion.** Live streams have no end, so
 *    when the socket closes we must reconnect instead of advancing the queue.
 * 2. **The endpoint must be re-resolved.** Station URLs are load balanced, and the node we
 *    were pinned to may be the one that died, so recovery re-runs redirect/playlist resolution.
 * 3. **Retries should not be given up on quickly.** A radio listener expects the stream to come
 *    back when the network does, so the default policy retries generously with backoff.
 *
 * Decisions are pure and unit tested; the caller supplies `reconnect`, which re-resolves and
 * re-prepares the engine.
 */
class RadioRecoveryManager(
    private val scope: CoroutineScope,
    private val policy: RetryPolicy = DEFAULT_POLICY,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    private val _state = MutableStateFlow(RadioConnectionState.IDLE)
    val state: StateFlow<RadioConnectionState> = _state.asStateFlow()

    /** Number of reconnect attempts made for the current outage; resets on success. */
    var attempts: Int = 0
        private set

    private var job: Job? = null
    private var generation = 0L
    private var networkAvailable = true
    private var pending: (suspend () -> Unit)? = null

    fun connecting() {
        _state.value = RadioConnectionState.CONNECTING
    }

    /** Call when audio is actually flowing; clears the retry budget. */
    fun onPlaying() {
        attempts = 0
        pending = null
        generation++
        job?.cancel()
        job = null
        _state.value = RadioConnectionState.PLAYING
    }

    fun onNetworkLost() {
        networkAvailable = false
        if (_state.value == RadioConnectionState.PLAYING ||
            _state.value == RadioConnectionState.CONNECTING
        ) {
            _state.value = RadioConnectionState.RECONNECTING
        }
    }

    fun onNetworkAvailable() {
        networkAvailable = true
        val action = pending ?: return
        start(action)
    }

    /**
     * A live stream ended without an error. For radio this always means "reconnect".
     */
    fun onStreamEnded(reconnect: suspend () -> Unit) =
        recover(StreamFailure(StreamFailureKind.SOCKET, message = "live stream ended"), reconnect)

    /**
     * Starts bounded reconnection when [failure] is transient.
     *
     * Unlike on-demand playback, HTTP 404 on a *previously working* station is treated as
     * retryable, because load balancers routinely return it for a node that is being recycled.
     */
    fun recover(failure: StreamFailure, reconnect: suspend () -> Unit) {
        generation++
        job?.cancel()
        job = null
        if (!isRetryable(failure)) {
            pending = null
            _state.value = RadioConnectionState.FAILED
            return
        }
        pending = reconnect
        _state.value = RadioConnectionState.RECONNECTING
        if (networkAvailable) start(reconnect)
    }

    private fun start(reconnect: suspend () -> Unit) {
        val version = generation
        job?.cancel()
        job = scope.launch {
            while (version == generation) {
                if (!networkAvailable) return@launch
                val attempt = attempts + 1
                if (!policy.shouldRetry(attempt)) {
                    pending = null
                    _state.value = RadioConnectionState.FAILED
                    return@launch
                }
                attempts = attempt
                sleeper(policy.delayFor(attempt))
                if (!networkAvailable || version != generation) return@launch
                val result = runCatchingCancellable { reconnect() }
                if (result.isSuccess) {
                    pending = null
                    // Stay in RECONNECTING until the engine reports audio via onPlaying(),
                    // so the UI never claims "live" before the first bytes arrive.
                    _state.value = RadioConnectionState.CONNECTING
                    return@launch
                }
                if (!isRetryable(StreamFailure.classify(result.exceptionOrNull()!!))) {
                    pending = null
                    _state.value = RadioConnectionState.FAILED
                    return@launch
                }
            }
        }
    }

    fun close() {
        generation++
        pending = null
        attempts = 0
        job?.cancel(CancellationException("Radio recovery closed"))
        job = null
        _state.value = RadioConnectionState.IDLE
    }

    companion object {
        /** Retries for roughly a quarter hour of outage before surfacing a failure. */
        val DEFAULT_POLICY = RetryPolicy(
            maxRetries = 10,
            initialDelayMs = 1_000,
            maxDelayMs = 60_000,
        )

        /** Statuses a station may transiently return while a node is recycled. */
        val RETRYABLE_RADIO_HTTP = StreamFailure.RETRYABLE_HTTP + setOf(403, 404, 408)

        fun isRetryable(failure: StreamFailure): Boolean = when (failure.kind) {
            StreamFailureKind.HTTP -> failure.httpStatus in RETRYABLE_RADIO_HTTP
            StreamFailureKind.NON_RETRYABLE -> false
            else -> true
        }
    }
}
