package dev.brahmkshatriya.echo.player.audiofx

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioProcessingTest {
    @Test fun equalPowerCrossfadeHasExactClickFreeEndpoints() {
        val engine = CrossfadeEngine(4_000)
        val start = engine.frame(4_000, nextPrepared = true)
        val middle = engine.frame(2_000, nextPrepared = true)
        val end = engine.frame(0, nextPrepared = true)
        assertEquals(1f, start.outgoingGain); assertEquals(0f, start.incomingGain)
        assertTrue(abs(middle.outgoingGain - middle.incomingGain) < 0.001f)
        assertEquals(0f, end.outgoingGain); assertEquals(1f, end.incomingGain)
        assertTrue(end.completeTransition)
    }

    @Test fun replayGainAnalysisNormalizesAndPreventsClipping() {
        val processor = ReplayGainProcessor()
        val quiet = processor.analyze(FloatArray(1_000) { 0.05f })
        assertTrue(quiet.gainDb > 0)
        val clippedRisk = quiet.copy(peak = 0.9f, gainDb = 9f)
        val result = processor.gain(ReplayGainProcessor.Mode.TRACK, clippedRisk, null)
        assertTrue(result.linear * clippedRisk.peak <= 1.0001f)
    }

    @Test fun silentAndInvalidSamplesRemainSafe() {
        val analysis = ReplayGainProcessor().analyze(floatArrayOf(Float.NaN, 0f, Float.POSITIVE_INFINITY))
        assertEquals(0f, analysis.peak)
        assertEquals(0f, analysis.gainDb)
    }
}
