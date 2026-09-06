package dev.brahmkshatriya.echo.player.audiofx

import dev.brahmkshatriya.echo.player.domain.nowEpochMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Crash-safe sleep timer (Phase 5 - audio features).
 *
 * The timer is split into a pure part ([SleepStateMachine], fully unit tested
 * and also used for state restoration after process death) and a coroutine
 * driver that emits volume ramps and the final [onExpired] callback.
 */
@Serializable
data class SleepTimerSnapshot(
    val startedAtMs: Long = 0,
    val durationMs: Long = 0,
    val fadeMs: Long = DEFAULT_FADE_MS
) {
    val isActive: Boolean get() = durationMs > 0 && startedAtMs > 0

    companion object {
        const val DEFAULT_FADE_MS = 15_000L
    }
}

/** Pure state machine for the sleep timer; all functions are total. */
object SleepStateMachine {

    /** Remaining time in ms for [nowMs]; 0 when finished, -1 when inactive. */
    fun remainingMs(snapshot: SleepTimerSnapshot, nowMs: Long): Long {
        if (!snapshot.isActive) return -1
        return (snapshot.startedAtMs + snapshot.durationMs - nowMs).coerceAtLeast(0)
    }

    /**
     * @return the linear volume multiplier (0..1) that should be applied at
     * [nowMs] while the fade-out runs. 1.0 until the fade window starts, then
     * a linear ramp to 0 at expiry.
     */
    fun volumeAt(snapshot: SleepTimerSnapshot, nowMs: Long): Float {
        if (!snapshot.isActive) return 1f
        val fadeStart = snapshot.startedAtMs + snapshot.durationMs - snapshot.fadeMs
        if (nowMs < fadeStart) return 1f
        if (snapshot.fadeMs <= 0) return 0f
        val progress = (nowMs - fadeStart).toFloat() / snapshot.fadeMs.toFloat()
        return (1f - progress).coerceIn(0f, 1f)
    }

    fun isExpired(snapshot: SleepTimerSnapshot, nowMs: Long): Boolean =
        snapshot.isActive && remainingMs(snapshot, nowMs) == 0L
}

/**
 * Drives a [SleepTimerSnapshot]: ticks every [tickMs] and invokes
 * [onVolume] (fade curve) plus [onExpired] exactly once at the end.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val tickMs: Long = 500,
    private val clock: () -> Long = { nowEpochMs() },
    private val onVolume: (Float) -> Unit = {},
    private val onExpired: () -> Unit = {}
) {

    private val _snapshot = MutableStateFlow(SleepTimerSnapshot())
    val snapshot: StateFlow<SleepTimerSnapshot> = _snapshot.asStateFlow()

    private var job: Job? = null

    fun start(durationMs: Long, fadeMs: Long = SleepTimerSnapshot.DEFAULT_FADE_MS) {
        cancel()
        if (durationMs <= 0) return
        _snapshot.value = SleepTimerSnapshot(startedAtMs = clock(), durationMs, fadeMs.coerceAtMost(durationMs))
        job = scope.launch {
            var expired = false
            while (!expired) {
                val now = clock()
                val snap = _snapshot.value
                if (SleepStateMachine.isExpired(snap, now)) {
                    expired = true
                    break
                }
                onVolume(SleepStateMachine.volumeAt(snap, now))
                val remaining = SleepStateMachine.remainingMs(snap, now)
                delay(remaining.coerceAtMost(tickMs))
            }
            onVolume(0f)
            onExpired()
            _snapshot.value = SleepTimerSnapshot()
        }
    }

    /** Restores a persisted timer (crash recovery); starts/ignores accordingly. */
    fun restore(snapshot: SleepTimerSnapshot) {
        if (!SleepStateMachine.isExpired(snapshot, clock())) {
            cancel()
            _snapshot.value = snapshot
            job = scope.launch {
                var expired = false
                while (!expired) {
                    val now = clock()
                    if (SleepStateMachine.isExpired(_snapshot.value, now)) {
                        expired = true
                        break
                    }
                    onVolume(SleepStateMachine.volumeAt(_snapshot.value, now))
                    delay(SleepStateMachine.remainingMs(_snapshot.value, now).coerceAtMost(tickMs))
                }
                onVolume(0f)
                onExpired()
                _snapshot.value = SleepTimerSnapshot()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_snapshot.value.isActive) onVolume(1f)
        _snapshot.value = SleepTimerSnapshot()
    }

    val remainingMs: Long get() = SleepStateMachine.remainingMs(_snapshot.value, clock())
}
