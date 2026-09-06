package dev.brahmkshatriya.echo.player.core

import kotlin.math.min
import dev.brahmkshatriya.echo.player.domain.runCatchingCancellable
import kotlin.random.Random

/**
 * Exponential backoff retry policy shared by every automatic retry in the
 * app: playback recovery, network re-resolution, download workers and
 * extension requests.
 *
 * Pure and deterministic for a given [seed] so behaviour is unit testable.
 */
data class RetryPolicy(
    /** Maximum number of retries after the first attempt. */
    val maxRetries: Int = 3,
    /** Delay before the first retry. */
    val initialDelayMs: Long = 1_000,
    /** Upper bound applied to the exponentially grown delay. */
    val maxDelayMs: Long = 30_000,
    /** Multiplier applied per attempt. */
    val factor: Double = 2.0,
    /** Random jitter fraction in 0..0.5 applied on top of the delay. */
    val jitter: Double = 0.2,
    private val seed: Long? = null
) {

    private val random get() = seed?.let { Random(it) } ?: Random.Default

    val infinite get() = maxRetries < 0

    /** @return the number of retries allowed after the initial attempt. */
    fun retriesAllowed(): Int = if (infinite) Int.MAX_VALUE else maxRetries

    /** Delay to wait before retry number [attempt] (1-based). */
    fun delayFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1" }
        val exponential = initialDelayMs * factor.pow(min(attempt - 1, 30))
        val clamped = exponential.toLong().coerceIn(0L, maxDelayMs)
        val jitterFraction = (jitter.coerceIn(0.0, 0.5))
        val noise = random.nextDouble() * 2 * jitterFraction - jitterFraction
        return (clamped * (1.0 + noise)).toLong().coerceAtLeast(0)
    }

    /** Whether another retry may be attempted for [attempt] (1-based). */
    fun shouldRetry(attempt: Int): Boolean {
        if (attempt <= 0) return false
        return infinite || attempt <= maxRetries
    }

    private fun Double.pow(other: Int): Double {
        var result = 1.0
        repeat(other) { result *= this }
        return result
    }

    companion object {
        /** Default policy for playback network retries. */
        val Playback = RetryPolicy(maxRetries = 3, initialDelayMs = 2_000, maxDelayMs = 30_000)

        /** More patient policy for background downloads. */
        val Download = RetryPolicy(maxRetries = -1, initialDelayMs = 5_000, maxDelayMs = 5 * 60_000)

        /** Quick, bounded policy for extension metadata refreshes. */
        val Extension = RetryPolicy(maxRetries = 1, initialDelayMs = 500, maxDelayMs = 2_000)
    }
}

/**
 * Runs [block] with automatic retries according to [policy].
 *
 * @param shouldRetry decides whether a failure is retryable at all (e.g. only
 *   network errors are retried while permission errors are not).
 * @param onRetry notified before each wait with the attempt number (1-based)
 *   and the delay that will be applied.
 * @return the successful result of [block].
 * @throws the last failure when the retry budget is exhausted.
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    shouldRetry: (Throwable, Int) -> Boolean = { _, _ -> true },
    onRetry: suspend (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    block: suspend (attempt: Int) -> T
): T {
    var attempt = 1
    while (true) {
        val result = runCatchingCancellable { block(attempt) }
        result.onSuccess { return it }
        val error = result.exceptionOrNull()!!
        if (!shouldRetry(error, attempt) || !policy.shouldRetry(attempt)) throw error
        val delay = policy.delayFor(attempt)
        onRetry(attempt, delay, error)
        sleeper(delay)
        attempt++
    }
}
