package dev.brahmkshatriya.echo.player.core.recovery

import dev.brahmkshatriya.echo.player.domain.nowEpochMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connection watchdog that monitors heartbeats and notifies only on transitions
 * (lost ↔ available). Time is injected so this stays Kotlin Multiplatform.
 */
class ConnectionWatchdog(
    private val scope: CoroutineScope,
    private val onLost: () -> Unit,
    private val onAvailable: () -> Unit,
    private val checkIntervalMs: Long = 5_000L,
    private val timeoutMs: Long = 60_000L,
    private val nowMs: () -> Long = { nowEpochMs() },
) {

    private val _alive = MutableStateFlow(true)
    val alive: StateFlow<Boolean> = _alive.asStateFlow()

    private var watchJob: Job? = null
    private var lastCheckMs: Long = nowMs()

    fun start() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (true) {
                delay(checkIntervalMs)
                val now = nowMs()
                val timedOut = now - lastCheckMs > timeoutMs
                if (timedOut) {
                    if (_alive.value) {
                        _alive.value = false
                        onLost()
                    }
                } else if (!_alive.value) {
                    _alive.value = true
                    onAvailable()
                }
            }
        }
    }

    fun heartbeat() {
        lastCheckMs = nowMs()
        if (!_alive.value) {
            _alive.value = true
            onAvailable()
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }

    fun close() {
        stop()
    }
}
