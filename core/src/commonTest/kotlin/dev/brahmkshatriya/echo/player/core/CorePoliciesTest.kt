package dev.brahmkshatriya.echo.player.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryPolicyTest {

    private val deterministic = RetryPolicy(
        maxRetries = 4, initialDelayMs = 1_000, maxDelayMs = 60_000,
        factor = 2.0, jitter = 0.0, seed = 1
    )

    @Test
    fun `delays grow exponentially and honour the cap`() {
        assertEquals(1_000, deterministic.delayFor(1))
        assertEquals(2_000, deterministic.delayFor(2))
        assertEquals(4_000, deterministic.delayFor(3))
        assertEquals(8_000, deterministic.delayFor(4))
        val capped = RetryPolicy(3, 1_000, 2_500, 2.0, 0.0, seed = 2)
        assertEquals(1_000, capped.delayFor(1))
        assertEquals(2_000, capped.delayFor(2))
        assertEquals(2_500, capped.delayFor(3))
        assertEquals(2_500, capped.delayFor(9))
    }

    @Test
    fun `jitter stays within bounds`() {
        val jittery = RetryPolicy(3, 1_000, 60_000, 2.0, 0.5, seed = 3)
        repeat(200) { i ->
            val d = jittery.delayFor((i % 5) + 1)
            val base = 1_000L shl (i % 5)
            assertTrue(d >= 0 && d <= base + base / 2 + 1, "delay $d out of bounds for base $base")
        }
    }

    @Test
    fun `budgets and infinite policy`() {
        val finite = RetryPolicy(maxRetries = 2)
        assertTrue(finite.shouldRetry(1))
        assertTrue(finite.shouldRetry(2))
        assertFalse(finite.shouldRetry(3))
        val infinite = RetryPolicy(maxRetries = -1)
        assertTrue(infinite.infinite)
        assertTrue(infinite.shouldRetry(9999))
    }

    @Test
    fun `withRetry succeeds after transient failures`() = runTest {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val value = withRetry(
            policy = deterministic,
            shouldRetry = { error, _ -> error is IllegalStateException },
            sleeper = { sleeps += it }
        ) {
            calls++
            if (calls < 3) throw IllegalStateException("transient") else "ok"
        }
        assertEquals("ok", value)
        assertEquals(3, calls)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test
    fun `withRetry gives up on non-retryable failures`() = runTest {
        var calls = 0
        val result = runCatching {
            withRetry(
                policy = deterministic,
                shouldRetry = { _, _ -> false },
                sleeper = {}
            ) {
                calls++
                throw SecurityException("no permission")
            }
        }
        assertEquals(1, calls)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `withRetry exhausts the budget`() = runTest {
        var calls = 0
        val result = runCatching {
            withRetry(
                policy = deterministic.copy(maxRetries = 2),
                sleeper = {}
            ) {
                calls++
                throw IllegalStateException("always fails")
            }
        }
        assertEquals(3, calls) // initial + 2 retries
        assertTrue(result.isFailure)
    }
}

class WatchdogPolicyTest {

    private val policy = WatchdogPolicy(
        stallTimeoutMs = 10_000, bufferingTimeoutMs = 15_000, maxRecoveriesPerItem = 3
    )

    private fun sample(
        playing: Boolean = true,
        buffering: Boolean = false,
        sinceProgress: Long = 0,
        sinceChange: Long = 0,
        attempts: Int = 0
    ) = StallSample(playing, buffering, 42_000, sinceProgress, sinceChange, attempts)

    @Test
    fun `healthy playback needs no action`() {
        assertEquals(StallRecoveryAction.None, policy.decide(sample()))
        assertEquals(StallRecoveryAction.None, policy.decide(sample(playing = false, sinceProgress = 999_999)))
    }

    @Test
    fun `stalled playback escalates`() {
        assertEquals(
            StallRecoveryAction.SeekResume,
            policy.decide(sample(sinceProgress = 10_001))
        )
        assertEquals(
            StallRecoveryAction.RePrepare,
            policy.decide(sample(sinceProgress = 10_001, attempts = 1))
        )
        assertEquals(
            StallRecoveryAction.ReloadItem,
            policy.decide(sample(sinceProgress = 10_001, attempts = 2))
        )
        assertEquals(
            StallRecoveryAction.SkipTrack,
            policy.decide(sample(sinceProgress = 10_001, attempts = 3))
        )
    }

    @Test
    fun `buffering waits for its own timeout then re-prepares`() {
        assertEquals(
            StallRecoveryAction.None,
            policy.decide(sample(buffering = true, sinceChange = 14_000, sinceProgress = 14_000))
        )
        assertEquals(
            StallRecoveryAction.RePrepare,
            policy.decide(sample(buffering = true, sinceChange = 16_000))
        )
    }
}

class RecoveryPolicyTest {

    private val policy = RecoveryPolicy(maxItemRetries = 1, maxConsecutiveFailures = 3)

    @Test
    fun `network failure with multiple servers falls back to next server`() {
        val d = policy.decide(
            itemRetries = 0, consecutiveFailures = 0, serverCount = 3,
            isNetworkError = true, hasNext = true
        )
        assertEquals(RecoveryPolicy.Action.TryNextServer, d.action)
        assertTrue(d.shouldReconnectNetwork)
    }

    @Test
    fun `non network failure retries same item`() {
        val d = policy.decide(0, 0, 3, false, true)
        assertEquals(RecoveryPolicy.Action.RetrySameItem, d.action)
    }

    @Test
    fun `exhausted item retries skips`() {
        val d = policy.decide(itemRetries = 1, consecutiveFailures = 1, serverCount = 1, isNetworkError = false, hasNext = true)
        assertEquals(RecoveryPolicy.Action.SkipToNext, d.action)
    }

    @Test
    fun `repeated identical failures stop with error when queue is exhausted`() {
        val d = policy.decide(itemRetries = 1, consecutiveFailures = 3, serverCount = 1, isNetworkError = true, hasNext = false)
        assertEquals(RecoveryPolicy.Action.StopWithError, d.action)
    }

    @Test
    fun `server fallback can be disabled`() {
        val disabled = RecoveryPolicy(allowServerFallback = false)
        val d = disabled.decide(0, 0, 5, true, true)
        assertEquals(RecoveryPolicy.Action.RetrySameItem, d.action)
    }
}
