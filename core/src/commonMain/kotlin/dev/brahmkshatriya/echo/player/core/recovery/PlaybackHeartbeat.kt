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
 * Playback heartbeat that samples progress and reports stalls without busy-looping.
 *
 * Time is injected so this stays Kotlin Multiplatform (no JVM wall-clock API).
 */
class PlaybackHeartbeat(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 10_000L,
    private val onHeartbeat: () -> Unit,
    private val onStall: () -> Unit,
    private val nowMs: () -> Long = { nowEpochMs() },
) {

    private val _isActive = MutableStateFlow(true)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var heartbeatJob: Job? = null
    private var lastProgressMs: Long = nowMs()

    fun start() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(intervalMs)
                val now = nowMs()
                if (now - lastProgressMs > intervalMs * 3) {
                    if (_isActive.value) {
                        _isActive.value = false
                        onStall()
                    }
                } else {
                    if (!_isActive.value) _isActive.value = true
                    onHeartbeat()
                }
            }
        }
    }

    fun progress() {
        lastProgressMs = nowMs()
        _isActive.value = true
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
