package dev.brahmkshatriya.echo.player.core.recovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Playback heartbeat that ensures playback continues and detects stalls.
 */
class PlaybackHeartbeat(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 10_000L,
    private val onHeartbeat: () -> Unit,
    private val onStall: () -> Unit,
) {

    private val _isActive = MutableStateFlow(true)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var lastProgressMs: Long = System.currentTimeMillis()

    fun start() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(intervalMs)
                val now = System.currentTimeMillis()
                if (now - lastProgressMs > intervalMs * 3) {
                    _isActive.value = false
                    onStall()
                } else {
                    _isActive.value = true
                    onHeartbeat()
                }
            }
        }
    }

    fun progress() {
        lastProgressMs = System.currentTimeMillis()
        _isActive.value = true
    }

    fun stop() {
        heartbeatJob?.cancel()
    }
}
