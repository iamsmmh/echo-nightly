package dev.brahmkshatriya.echo.player.audiofx

import kotlin.math.pow
import kotlinx.serialization.Serializable

/**
 * Audio quality features shared by both platforms (Phase 5):
 * crossfade, replay gain / loudness normalization and equalizer state.
 * All maths are pure functions on plain numbers so they are unit testable on
 * any target; applying them to actual audio is delegated to the platform
 * engine through [dev.brahmkshatriya.echo.player.audio.PlayerEngine] hooks.
 */

/** User-configurable audio processing options, persisted in settings. */
@Serializable
data class AudioFxSettings(
    /** Crossfade window between tracks in ms; 0 disables crossfading. */
    val crossfadeMs: Int = 0,
    /** 0 = off, 1 = track gain, 2 = album gain. */
    val replayGainMode: Int = 0,
    /** Extra pre-amplitude applied on top of replay gain, in dB. */
    val replayGainPreampDb: Float = 0f,
    /** Clamp ceiling to protect against clipping after gain. */
    val limiterEnabled: Boolean = true,
    /** Equalizer state (persisted only; applied by platform engines). */
    val equalizer: EqualizerConfig = EqualizerConfig()
) {
    val crossfadeEnabled: Boolean get() = crossfadeMs >= 250
}

/** A graphic equalizer configuration: preamp + N band gains in dB. */
@Serializable
data class EqualizerConfig(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val bandsDb: List<Float> = List(DEFAULT_BANDS) { 0f }
) {
    fun clamped(): EqualizerConfig = copy(
        preampDb = preampDb.coerceIn(-15f, 15f),
        bandsDb = bandsDb.map { it.coerceIn(-15f, 15f) }
    )

    companion object {
        const val DEFAULT_BANDS = 15
    }
}

/**
 * Crossfade scheduling (Phase 5 - gapless/crossfade playback):
 *
 * AVPlayer/ExoPlayer-per-item designs cannot mix two decoders, so Echo
 * implements a *dip crossfade*: the outgoing track fades out over the final
 * window while the next track is resolved and prepared in parallel, then the
 * incoming track fades in. [CrossfadePolicy] owns all timing maths.
 */
object CrossfadePolicy {

    /** Earliest point (ms into the track) where the next track may be prepared. */
    fun prepareAtMs(durationMs: Long, crossfadeMs: Int): Long {
        if (crossfadeMs <= 0 || durationMs <= 0) return -1
        val overlap = crossfadeMs.coerceAtMost((durationMs / 4).toInt())
        return (durationMs - overlap - PREPARE_LEAD_MS).coerceAtLeast(0)
    }

    /** Start of the fade-out window on the outgoing track. */
    fun fadeStartMs(durationMs: Long, crossfadeMs: Int): Long {
        if (crossfadeMs <= 0 || durationMs <= 0) return -1
        val overlap = crossfadeMs.coerceAtMost((durationMs / 2).toInt())
        return (durationMs - overlap).coerceAtLeast(0)
    }

    /** Outgoing volume curve at absolute playback [positionMs] (1.0 before fade). */
    fun outgoingGain(positionMs: Long, fadeStartMs: Long, crossfadeMs: Int): Float {
        if (crossfadeMs <= 0 || fadeStartMs < 0 || positionMs < fadeStartMs) return 1f
        val progress = (positionMs - fadeStartMs).toFloat() / crossfadeMs
        return (1f - progress).coerceIn(0f, 1f)
    }

    /** Incoming volume curve at [elapsedMs] after the switch (0 -> 1). */
    fun incomingGain(elapsedMs: Long, crossfadeMs: Int): Float {
        if (crossfadeMs <= 0 || elapsedMs >= crossfadeMs) return 1f
        val progress = elapsedMs.toFloat() / crossfadeMs
        // smoothstep for a pleasant perceived ramp
        val t = progress.coerceIn(0f, 1f)
        return (t * t * (3 - 2 * t))
    }

    private const val PREPARE_LEAD_MS = 3_000L
}

/**
 * ReplayGain / loudness normalization (Phase 5).
 *
 * Gains come from extension-provided track extras (`replayGainTrack`,
 * `replayGainAlbum`, `peakTrack`), matching the community RG conventions.
 */
object ReplayGain {

    const val EXTRA_TRACK_GAIN = "replayGainTrack"
    const val EXTRA_ALBUM_GAIN = "replayGainAlbum"
    const val EXTRA_PEAK = "replayGainPeak"

    /** Converts a dB value to a linear multiplier with an 8 dB safety floor. */
    fun dbToLinear(db: Float): Float {
        val clamped = db.coerceIn(-12f, 9f)
        return 10.0.pow(clamped / 20.0).toFloat()
    }

    data class Gain(
        val linear: Float,
        val appliedDb: Float
    )

    /**
     * Resolves the final linear gain to apply on top of the user volume.
     *
     * @param mode 0 = off, 1 = track, 2 = album (falls back to track)
     * @param preampDb user trim applied before normalization
     * @param peakLinear optional true-peak value (0..1+); when a limiter is
     *   enabled the gain is reduced so `gain * peak <= 1`.
     */
    fun resolve(
        mode: Int,
        trackGainDb: Float?,
        albumGainDb: Float?,
        preampDb: Float,
        peakLinear: Float?,
        limiterEnabled: Boolean
    ): Gain {
        if (mode == 0) return Gain(1f, 0f)
        val baseDb = when (mode) {
            2 -> albumGainDb ?: trackGainDb
            else -> trackGainDb ?: albumGainDb
        } ?: return Gain(1f, 0f)
        val applied = baseDb + preampDb
        var linear = dbToLinear(applied)
        if (limiterEnabled && peakLinear != null && peakLinear > 0f)
            linear = minOf(linear, 1f / peakLinear)
        return Gain(linear.coerceIn(0.1f, 3.0f), applied)
    }

    /** Parses extension extras leniently (numbers may be strings). */
    fun parseDb(value: Any?): Float? = when (value) {
        is Float -> value
        is Double -> value.toFloat()
        is Int -> value.toFloat()
        is Number -> value.toFloat()
        is String -> value.trim().toFloatOrNull()
        else -> null
    }

}
