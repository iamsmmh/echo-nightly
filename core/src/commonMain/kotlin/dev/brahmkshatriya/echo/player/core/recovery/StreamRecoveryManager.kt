package dev.brahmkshatriya.echo.player.core.recovery

import dev.brahmkshatriya.echo.player.core.RetryPolicy
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Observable lifecycle of automatic stream recovery. */
enum class StreamRecoveryState { IDLE, BUFFERING, RECOVERING, FAILED, RESTORED }

enum class StreamFailureKind { TIMEOUT, DNS, SOCKET, HTTP, OFFLINE, NON_RETRYABLE }

data class StreamFailure(
    val kind: StreamFailureKind,
    val httpStatus: Int? = null,
    val message: String? = null
) {
    val retryable: Boolean get() = kind in setOf(
        StreamFailureKind.TIMEOUT,
        StreamFailureKind.DNS,
        StreamFailureKind.SOCKET,
        StreamFailureKind.OFFLINE
    ) || (kind == StreamFailureKind.HTTP && httpStatus in RETRYABLE_HTTP)

    companion object {
        val RETRYABLE_HTTP = setOf(429, 500, 502, 503)

        /** Platform-neutral classification; callers may provide an HTTP status explicitly. */
        fun classify(error: Throwable, httpStatus: Int? = null): StreamFailure {
            if (httpStatus != null) return StreamFailure(StreamFailureKind.HTTP, httpStatus, error.message)
            val chain = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
            val network = chain.filterIsInstance<EchoError.Network>().firstOrNull()
            if (network?.statusCode != null) return StreamFailure(StreamFailureKind.HTTP, network.statusCode, network.message)
            if (network != null) return StreamFailure(StreamFailureKind.SOCKET, message = network.message)
            val text = chain.joinToString(" ") { "${it::class.simpleName.orEmpty()} ${it.message.orEmpty()}" }.lowercase()
            val kind = when {
                "timeout" in text || "timed out" in text -> StreamFailureKind.TIMEOUT
                "unknownhost" in text || "dns" in text || "name resolution" in text -> StreamFailureKind.DNS
                "socket" in text || "connection reset" in text || "broken pipe" in text -> StreamFailureKind.SOCKET
                else -> StreamFailureKind.NON_RETRYABLE
            }
            return StreamFailure(kind, message = error.message)
        }
    }
}

/** Named strategy used by streaming recovery while retaining the shared retry policy. */
class ExponentialBackoffStrategy(private val policy: RetryPolicy = RetryPolicy.Playback) {
    fun canRetry(attempt: Int): Boolean = policy.shouldRetry(attempt)
    fun delayMs(attempt: Int): Long = policy.delayFor(attempt)
}

/**
 * Serializes stream retries and network restoration. A newer failure cancels stale recovery,
 * while loss of connectivity preserves the operation until [onNetworkAvailable].
 */
class StreamRecoveryManager(
    private val scope: CoroutineScope,
    private val backoff: ExponentialBackoffStrategy = ExponentialBackoffStrategy(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    private val _state = MutableStateFlow(StreamRecoveryState.IDLE)
    val state: StateFlow<StreamRecoveryState> = _state.asStateFlow()

    private var recoveryJob: Job? = null
    private var generation = 0L
    private var networkAvailable = true
    private var pending: (suspend () -> Unit)? = null

    fun buffering() {
        if (_state.value != StreamRecoveryState.RECOVERING) _state.value = StreamRecoveryState.BUFFERING
    }

    fun restored() {
        pending = null
        recoveryJob?.cancel()
        recoveryJob = null
        _state.value = StreamRecoveryState.RESTORED
    }

    fun idle() {
        pending = null
        recoveryJob?.cancel()
        recoveryJob = null
        _state.value = StreamRecoveryState.IDLE
    }

    fun onNetworkLost() {
        networkAvailable = false
        if (_state.value == StreamRecoveryState.BUFFERING) _state.value = StreamRecoveryState.RECOVERING
    }

    fun onNetworkAvailable() {
        networkAvailable = true
        val action = pending ?: return
        startRecovery(action)
    }

    /** Starts bounded recovery when [failure] is transient; succeeds only when [recover] returns. */
    fun recover(failure: StreamFailure, recover: suspend () -> Unit) {
        generation++
        recoveryJob?.cancel()
        recoveryJob = null
        pending = recover
        if (!failure.retryable) {
            pending = null
            _state.value = StreamRecoveryState.FAILED
            return
        }
        _state.value = StreamRecoveryState.RECOVERING
        if (networkAvailable) startRecovery(recover)
    }

    private fun startRecovery(recover: suspend () -> Unit) {
        val version = generation
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            var attempt = 1
            while (version == generation) {
                if (!networkAvailable) return@launch
                if (!backoff.canRetry(attempt)) {
                    pending = null
                    _state.value = StreamRecoveryState.FAILED
                    return@launch
                }
                sleeper(backoff.delayMs(attempt))
                if (!networkAvailable || version != generation) return@launch
                val result = runCatchingCancellable { recover() }
                if (result.isSuccess) {
                    pending = null
                    _state.value = StreamRecoveryState.RESTORED
                    return@launch
                }
                val nextFailure = StreamFailure.classify(result.exceptionOrNull()!!)
                if (!nextFailure.retryable) {
                    pending = null
                    _state.value = StreamRecoveryState.FAILED
                    return@launch
                }
                attempt++
            }
        }
    }

    fun close() {
        generation++
        pending = null
        recoveryJob?.cancel(CancellationException("Stream recovery closed"))
        recoveryJob = null
        _state.value = StreamRecoveryState.IDLE
    }
}
