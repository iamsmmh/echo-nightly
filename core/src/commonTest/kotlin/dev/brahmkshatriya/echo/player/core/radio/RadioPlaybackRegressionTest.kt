package dev.brahmkshatriya.echo.player.core.radio

import dev.brahmkshatriya.echo.player.core.recovery.StreamFailure
import dev.brahmkshatriya.echo.player.core.recovery.StreamFailureKind
import kotlin.test.Test
import kotlin.test.assertTrue

class RadioPlaybackRegressionTest {

    @Test
    fun radioRedirectStatusIncludes301And307() {
        val statuses = RadioStreamPolicy.REDIRECT_STATUSES
        assertTrue(301 in statuses)
        assertTrue(307 in statuses)
    }

    @Test
    fun retryableRadioHttpIncludes429And503() {
        val retryable = RadioRecoveryManager.RETRYABLE_RADIO_HTTP
        assertTrue(429 in retryable)
        assertTrue(500 in retryable)
        assertTrue(502 in retryable)
        assertTrue(503 in retryable)
    }

    @Test
    fun streamFailureForTimeoutIsRetryable() {
        val failure = StreamFailure(StreamFailureKind.TIMEOUT, message = "timed out")
        assertTrue(failure.retryable)
    }
}
