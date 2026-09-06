package dev.brahmkshatriya.echo.player.audio.recovery

import dev.brahmkshatriya.echo.player.domain.EchoError

/**
 * Policy controlling the automatic network retry / watchdog recovery built into
 * [dev.brahmkshatriya.echo.player.audio.PlaybackController].
 *
 * All values are intentionally conservative: retries are bounded, back off
 * exponentially and only ever apply to *transient* network failures, so they
 * can never turn into an infinite loop or mask a permanent problem.
 */
data class RetryPolicy(
    /** Total resolution attempts for one track, including the first one. */
    val maxAttempts: Int = 3,
    /** Base back-off delay before the first retry (ms). */
    val baseDelayMs: Long = 1_000L,
    /** Hard cap for any single back-off delay (ms). */
    val maxDelayMs: Long = 15_000L,
    /** Multiplier applied to the delay on every consecutive failure. */
    val backoffFactor: Double = 2.0,
    /** How long a track may be buffering without progress before the watchdog fires (ms). */
    val stallTimeoutMs: Long = 12_000L
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
        require(baseDelayMs >= 0) { "baseDelayMs must be >= 0, was $baseDelayMs" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs ($maxDelayMs) must be >= baseDelayMs ($baseDelayMs)" }
        require(backoffFactor >= 1.0) { "backoffFactor must be >= 1.0, was $backoffFactor" }
        require(stallTimeoutMs > 0) { "stallTimeoutMs must be > 0, was $stallTimeoutMs" }
    }

    /**
     * How many further attempts are permitted after [failures] consecutive
     * failures, i.e. `maxAttempts - failures`. With `maxAttempts = 3` a track
     * is tried 3 times in total (initial + up to 2 automatic retries).
     */
    fun attemptsLeftAfter(failures: Int): Int = (maxAttempts - failures).coerceAtLeast(0)

    /** True when another attempt is allowed after [failures] consecutive failures. */
    fun canRetry(failures: Int): Boolean = attemptsLeftAfter(failures) > 0
}

/**
 * Exponential back-off delay (ms) to sleep *before* the attempt indexed by
 * [attempt] (0-based, so attempt 0 returns `0` and is the first try).
 *
 * Deterministic (no jitter) so behaviour is easy to reason about and test.
 */
fun backoffDelayMs(attempt: Int, policy: RetryPolicy): Long {
    if (attempt <= 0) return 0L
    var delay = policy.baseDelayMs.toDouble()
    repeat(attempt - 1) { delay *= policy.backoffFactor }
    return delay.toLong().coerceIn(policy.baseDelayMs, policy.maxDelayMs)
}

/**
 * True when [this] throwable (or anything in its cause chain) represents a
 * *transient* network failure worth retrying automatically: a connection /
 * read problem (no HTTP status) or an HTTP 429 / 5xx. Local file, storage,
 * extension-missing and permanent HTTP 4xx failures are NOT retried.
 */
fun Throwable.isTransientNetworkFailure(): Boolean {
    var cause: Throwable? = this
    var hops = 0
    while (cause != null && hops < 8) {
        val network = cause as? EchoError.Network
        if (network != null) {
            val code = network.statusCode
            if (code == null) return true // connection / timeout without an HTTP response
            return code == 429 || code in 500..599
        }
        cause = cause.cause
        hops++
    }
    return false
}

/**
 * Pure stall tracker backing the playback watchdog.
 *
 * The engine should make forward progress while a track is playing. When it
 * keeps reporting buffering (or is otherwise not advancing position) for
 * longer than [stallTimeoutMs], the tracker fires [onTick] once. Firing is
 * edge triggered: once reported, the window resets and it will only fire again
 * after another full stall window. Not thread safe — intended for single
 * consumer coroutines.
 */
class PlaybackStallTracker(
    private val stallTimeoutMs: Long = 12_000L,
    private val nowMs: () -> Long
) {
    private var currentTrackId: String? = null
    private var stalledSinceMs: Long? = null
    private var lastProgressPositionMs: Long = -1L

    /**
     * Records the latest engine observation.
     *
     * @param id Current queue item id, or null when nothing is loaded.
     * @param isPlaying Whether the engine reports playing.
     * @param isBuffering Whether the engine reports buffering.
     * @param positionMs Current playback position in ms.
     * @return true exactly once when a stall is detected; false otherwise.
     */
    fun onTick(id: String?, isPlaying: Boolean, isBuffering: Boolean, positionMs: Long): Boolean {
        // Nothing loaded: reset accounting.
        if (id == null) {
            if (currentTrackId != null) {
                currentTrackId = null
                stalledSinceMs = null
                lastProgressPositionMs = -1L
            }
            return false
        }

        // A (re)loaded track resets the accounting, then the current
        // observation is evaluated normally below (so a stall window can open
        // on the very first buffering tick of a track).
        if (id != currentTrackId) {
            currentTrackId = id
            stalledSinceMs = null
            lastProgressPositionMs = positionMs
        }

        // Forward progress → healthy, reset any pending window.
        if (!isBuffering && positionMs > lastProgressPositionMs) {
            lastProgressPositionMs = positionMs
            stalledSinceMs = null
            return false
        }

        // The engine only counts as stalled when it is expected to be playing
        // (a prepared, non-ended track). While paused or not buffering we must
        // not fire, and any window that was building up is cancelled.
        if (!isPlaying || !isBuffering) {
            stalledSinceMs = null
            if (!isBuffering) lastProgressPositionMs = lastProgressPositionMs.coerceAtLeast(positionMs)
            return false
        }

        // Buffering without progress: start or keep the stall window.
        val now = nowMs()
        val since = stalledSinceMs ?: now.also { stalledSinceMs = it }
        if (now - since >= stallTimeoutMs) {
            stalledSinceMs = now // re-arm: only fire again after another full window
            return true
        }
        return false
    }
}
