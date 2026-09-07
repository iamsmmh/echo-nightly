package dev.brahmkshatriya.echo.player.audiofx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pure crossfade scheduler shared by Media3 and AVFoundation adapters. */
class CrossfadeEngine(
    durationMs: Long = 0,
    private val minimumFadeMs: Long = 250,
    private val maximumFadeMs: Long = 12_000
) {
    var durationMs: Long = durationMs.coerceIn(0, maximumFadeMs)
        private set

    enum class Phase { IDLE, PREPARING, FADING, COMPLETE }

    data class Frame(
        val phase: Phase,
        val outgoingGain: Float,
        val incomingGain: Float,
        val prepareNext: Boolean,
        val completeTransition: Boolean
    )

    fun configure(durationMs: Long) {
        this.durationMs = durationMs.coerceIn(0, maximumFadeMs)
    }

    /**
     * Equal-power gains avoid the centre-volume dip and reach exact zero/one endpoints,
     * preventing a residual sample from clicking when the outgoing decoder is released.
     */
    fun frame(remainingMs: Long, nextPrepared: Boolean): Frame {
        if (durationMs < minimumFadeMs) return Frame(Phase.IDLE, 1f, 0f, false, false)
        val remaining = remainingMs.coerceAtLeast(0)
        val prepare = remaining <= durationMs + PREPARE_LEAD_MS
        if (!nextPrepared || remaining > durationMs) return Frame(Phase.PREPARING, 1f, 0f, prepare, false)
        val progress = (1.0 - remaining.toDouble() / durationMs).coerceIn(0.0, 1.0)
        val angle = progress * PI / 2.0
        val outgoing = cos(angle).toFloat().snapEndpoint()
        val incoming = sin(angle).toFloat().snapEndpoint()
        val complete = remaining == 0L
        return Frame(if (complete) Phase.COMPLETE else Phase.FADING, outgoing, incoming, prepare, complete)
    }

    /** During seeks no fading is applied until the new decoder position is stable. */
    fun seekFrame(): Frame = Frame(Phase.IDLE, 1f, 0f, false, false)

    private fun Float.snapEndpoint() = when {
        this < 0.0001f -> 0f
        this > 0.9999f -> 1f
        else -> this.coerceIn(0f, 1f)
    }

    private companion object { const val PREPARE_LEAD_MS = 3_000L }
}
