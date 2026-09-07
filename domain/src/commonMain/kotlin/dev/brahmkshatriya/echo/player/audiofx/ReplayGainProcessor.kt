package dev.brahmkshatriya.echo.player.audiofx

import kotlin.math.log10
import kotlin.math.sqrt

/** ReplayGain analysis and clipping-safe gain selection for decoded PCM and tagged tracks. */
class ReplayGainProcessor(
    private val targetLoudnessDb: Float = -18f,
    private val maximumPositiveGainDb: Float = 9f
) {
    enum class Mode { OFF, TRACK, ALBUM }

    data class Analysis(val loudnessDb: Float, val peak: Float, val gainDb: Float)

    /** Analyzes normalized floating-point PCM. Empty/silent buffers produce zero gain. */
    fun analyze(samples: FloatArray): Analysis {
        if (samples.isEmpty()) return Analysis(Float.NEGATIVE_INFINITY, 0f, 0f)
        var energy = 0.0
        var peak = 0f
        samples.forEach { raw ->
            val sample = if (raw.isFinite()) raw.coerceIn(-1f, 1f) else 0f
            energy += sample * sample
            peak = maxOf(peak, kotlin.math.abs(sample))
        }
        val rms = sqrt(energy / samples.size)
        if (rms <= 1e-9) return Analysis(Float.NEGATIVE_INFINITY, peak, 0f)
        val loudness = (20.0 * log10(rms)).toFloat()
        return Analysis(loudness, peak, (targetLoudnessDb - loudness).coerceIn(-12f, maximumPositiveGainDb))
    }

    fun gain(
        mode: Mode,
        track: Analysis?,
        album: Analysis?,
        preampDb: Float = 0f,
        preventClipping: Boolean = true
    ): ReplayGain.Gain {
        if (mode == Mode.OFF) return ReplayGain.Gain(1f, 0f)
        val selected = (if (mode == Mode.ALBUM) album ?: track else track ?: album)
            ?: return ReplayGain.Gain(1f, 0f)
        return ReplayGain.resolve(
            mode = 1,
            trackGainDb = selected.gainDb,
            albumGainDb = null,
            preampDb = preampDb,
            peakLinear = selected.peak,
            limiterEnabled = preventClipping
        )
    }

    /** Album analysis is energy-weighted without allocating a concatenated PCM buffer. */
    fun analyzeAlbum(tracks: List<FloatArray>): Analysis {
        var sampleCount = 0L
        var energy = 0.0
        var peak = 0f
        tracks.forEach { samples -> samples.forEach { raw ->
            val sample = if (raw.isFinite()) raw.coerceIn(-1f, 1f) else 0f
            energy += sample * sample
            peak = maxOf(peak, kotlin.math.abs(sample))
            sampleCount++
        } }
        if (sampleCount == 0L || energy <= 1e-18) return Analysis(Float.NEGATIVE_INFINITY, peak, 0f)
        val loudness = (20.0 * log10(sqrt(energy / sampleCount))).toFloat()
        return Analysis(loudness, peak, (targetLoudnessDb - loudness).coerceIn(-12f, maximumPositiveGainDb))
    }
}
