package dev.brahmkshatriya.echo.player.core.recovery

import dev.brahmkshatriya.echo.player.core.RetryPolicy

/**
 * Recovery policy configuration for stream playback failures,
 * defining retry limits, backoff behavior, and retryable HTTP statuses.
 */
class RecoveryPolicy(
    val maxRetries: Int = 10,
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 60_000,
    val retryableHttpStatuses: Set<Int> = setOf(429, 500, 502, 503, 301, 302, 307, 308),
    val retryableKinds: Set<StreamFailureKind> = setOf(
        StreamFailureKind.TIMEOUT,
        StreamFailureKind.DNS,
        StreamFailureKind.SOCKET,
        StreamFailureKind.HTTP,
        StreamFailureKind.OFFLINE
    )
) {

    fun isRetryable(failure: StreamFailure): Boolean {
        if (failure.kind !in retryableKinds) return false
        if (failure.kind == StreamFailureKind.HTTP && failure.httpStatus != null) {
            return failure.httpStatus in retryableHttpStatuses
        }
        return true
    }

    fun toRetryPolicy(): RetryPolicy = RetryPolicy(
        maxRetries = maxRetries,
        initialDelayMs = initialDelayMs,
        maxDelayMs = maxDelayMs
    )
}
