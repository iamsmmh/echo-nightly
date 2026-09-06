package dev.brahmkshatriya.echo.player.audio.recovery

import dev.brahmkshatriya.echo.player.audio.recovery.PlaybackStallTracker
import dev.brahmkshatriya.echo.player.audio.recovery.RetryPolicy
import dev.brahmkshatriya.echo.player.audio.recovery.backoffDelayMs
import dev.brahmkshatriya.echo.player.audio.recovery.isTransientNetworkFailure
import dev.brahmkshatriya.echo.player.domain.EchoError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {

    @Test
    fun `default policy retries twice then stops`() {
        val policy = RetryPolicy()
        // maxAttempts = 3 => up to 2 automatic retries after the first failure
        // (a track is tried 3 times in total).
        assertTrue(policy.canRetry(0))
        assertTrue(policy.canRetry(1))
        assertTrue(policy.canRetry(2))
        assertFalse(policy.canRetry(3))
        assertEquals(3, policy.maxAttempts)
        assertEquals(3, policy.attemptsLeftAfter(0))
        assertEquals(2, policy.attemptsLeftAfter(1))
        assertEquals(1, policy.attemptsLeftAfter(2))
        assertEquals(0, policy.attemptsLeftAfter(3))
    }

    @Test
    fun `single attempt policy never retries after a failure`() {
        val policy = RetryPolicy(maxAttempts = 1)
        // The single attempt is still available before any failure…
        assertTrue(policy.canRetry(0))
        // …but after one failure there is no automatic retry left.
        assertFalse(policy.canRetry(1))
        assertEquals(0, policy.attemptsLeftAfter(1))
    }

    @Test
    fun `invalid policies are rejected`() {
        listOf(
            { RetryPolicy(maxAttempts = 0) },
            { RetryPolicy(baseDelayMs = -1) },
            { RetryPolicy(maxDelayMs = 1, baseDelayMs = 100) },
            { RetryPolicy(backoffFactor = 0.5) },
            { RetryPolicy(stallTimeoutMs = 0) }
        ).forEach { block ->
            var threw = false
            try {
                block()
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw, "expected IllegalArgumentException")
        }
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val policy = RetryPolicy(maxAttempts = 5, baseDelayMs = 100, maxDelayMs = 1_000, backoffFactor = 2.0)
        assertEquals(0, backoffDelayMs(0, policy))
        assertEquals(100, backoffDelayMs(1, policy))
        assertEquals(200, backoffDelayMs(2, policy))
        assertEquals(400, backoffDelayMs(3, policy))
        // 800 capped at maxDelayMs=1000, but further attempts stay capped.
        assertEquals(800, backoffDelayMs(4, policy))
        assertEquals(1_000, backoffDelayMs(10, policy))
    }

    @Test
    fun `backoff is monotonic non-decreasing`() {
        val policy = RetryPolicy(maxAttempts = 10, baseDelayMs = 50, maxDelayMs = 10_000, backoffFactor = 3.0)
        var previous = 0L
        for (attempt in 0..8) {
            val current = backoffDelayMs(attempt, policy)
            assertTrue(current >= previous, "attempt $attempt backoff regressed")
            assertTrue(current <= policy.maxDelayMs)
            previous = current
        }
    }
}

class TransientFailureClassificationTest {

    private fun network(code: Int?) = EchoError.Network("boom", code)

    @Test
    fun `network error without status is transient`() {
        assertTrue(network(null).isTransientNetworkFailure())
    }

    @Test
    fun `http 5xx and 429 are transient`() {
        assertTrue(network(500).isTransientNetworkFailure())
        assertTrue(network(503).isTransientNetworkFailure())
        assertTrue(network(429).isTransientNetworkFailure())
    }

    @Test
    fun `http 4xx are permanent`() {
        assertFalse(network(404).isTransientNetworkFailure())
        assertFalse(network(401).isTransientNetworkFailure())
    }

    @Test
    fun `non network errors are not transient`() {
        assertFalse(EchoError.Playback("no").isTransientNetworkFailure())
        assertFalse(EchoError.Storage("no").isTransientNetworkFailure())
        assertFalse(IllegalStateException("no").isTransientNetworkFailure())
    }

    @Test
    fun `transient wrapped deep in a cause chain is detected`() {
        val wrapped = EchoError.Playback(
            "failed",
            IllegalStateException("inner", network(502))
        )
        assertTrue(wrapped.isTransientNetworkFailure())
    }

    @Test
    fun `permanent wrapped in a cause chain stays permanent`() {
        val wrapped = EchoError.Playback(
            "failed",
            IllegalStateException("inner", EchoError.Extension("missing"))
        )
        assertFalse(wrapped.isTransientNetworkFailure())
    }
}

class PlaybackStallTrackerTest {

    private class Clock(var timeMs: Long = 0L)

    /** Builds a tracker that derives time from a shared mutable clock. */
    private fun tracker(timeoutMs: Long, clock: Clock) =
        PlaybackStallTracker(stallTimeoutMs = timeoutMs, nowMs = { clock.timeMs })

    @Test
    fun `does not stall while paused`() {
        val clock = Clock(0)
        val t = tracker(1_000, clock)
        assertFalse(t.onTick("a", isPlaying = false, isBuffering = true, positionMs = 0))
        clock.timeMs = 10_000
        assertFalse(t.onTick("a", isPlaying = false, isBuffering = true, positionMs = 0))
    }

    @Test
    fun `stall fires once the buffering timeout elapses without progress`() {
        val clock = Clock(0)
        val t = tracker(1_000, clock)

        assertFalse(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))
        clock.timeMs = 500
        assertFalse(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))
        clock.timeMs = 1_000
        assertTrue(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))
    }

    @Test
    fun `stall fires at most once per full window`() {
        val clock = Clock(0)
        val t = tracker(1_000, clock)
        assertFalse(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))

        clock.timeMs = 1_000
        assertTrue(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))

        // Immediately after firing it re-arms, so a short follow-up is quiet.
        clock.timeMs = 1_400
        assertFalse(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))

        clock.timeMs = 2_500
        assertTrue(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))
    }

    @Test
    fun `forward progress resets the stall window`() {
        val clock = Clock(0)
        val t = tracker(1_000, clock)
        assertFalse(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))
        clock.timeMs = 900

        // Buffer replenished and position advanced => healthy again.
        assertFalse(t.onTick("a", isPlaying = true, isBuffering = false, positionMs = 400))
        clock.timeMs = 1_900 // would have stalled, but progress reset the clock
        assertFalse(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 400))

        clock.timeMs = 3_000
        assertTrue(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 400))
    }

    @Test
    fun `track change resets accounting`() {
        val clock = Clock(0)
        val t = tracker(1_000, clock)
        assertFalse(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))
        clock.timeMs = 10_000 // "a" has been stalling

        assertFalse(t.onTick("b", isPlaying = true, isBuffering = true, positionMs = 0))
        clock.timeMs = 10_400
        assertFalse(t.onTick("b", isPlaying = true, isBuffering = true, positionMs = 0))
        clock.timeMs = 11_000
        assertTrue(t.onTick("b", isPlaying = true, isBuffering = true, positionMs = 0))
    }

    @Test
    fun `null track resets accounting`() {
        val clock = Clock(0)
        val t = tracker(1_000, clock)
        assertFalse(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))
        assertFalse(t.onTick(null, isPlaying = false, isBuffering = false, positionMs = 0))
        clock.timeMs = 5_000
        assertFalse(t.onTick(null, isPlaying = false, isBuffering = false, positionMs = 0))
    }

    @Test
    fun `small default construction is allowed`() {
        val clock = Clock(0)
        val t = tracker(100, clock)
        assertFalse(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))
        clock.timeMs = 100
        assertTrue(t.onTick("a", isPlaying = true, isBuffering = true, positionMs = 0))
    }
}
