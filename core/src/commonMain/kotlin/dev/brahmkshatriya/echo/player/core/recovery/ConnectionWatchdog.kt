package dev.brahmkshatriya.echo.player.core.recovery

import dev.brahmkshatriya.echo.player.core.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connection watchdog that monitors network state and triggers recovery
 * for network loss, DNS failures, socket disconnects, HTTP failures,
 * and temporary server outages.
 */
class ConnectionWatchdog(
    private val scope: CoroutineScope,
    private val onLost: () -> Unit,
    private val onAvailable: () -> Unit,
    private val checkIntervalMs: Long = 5_000L,
    private val timeoutMs: Long = 60_000L,
) {

    private val _alive = MutableStateFlow(true)
    val alive: StateFlow<Boolean> = _alive.asStateFlow()

    private var watchJob: Job? = null
    private var lastCheckMs: Long = System.currentTimeMillis()

    fun start() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (true) {
                delay(checkIntervalMs)
                val now = System.currentTimeMillis()
                if (now - lastCheckMs > timeoutMs) {
                    _alive.value = false
                    onLost()
                } else {
                    _alive.value = true
                    onAvailable()
                }
            }
        }
    }

    fun heartbeat() {
        lastCheckMs = System.currentTimeMillis()
        _alive.value = true
    }

    fun stop() {
        watchJob?.cancel()
    }

    fun close() {
        stop()
    }
}
