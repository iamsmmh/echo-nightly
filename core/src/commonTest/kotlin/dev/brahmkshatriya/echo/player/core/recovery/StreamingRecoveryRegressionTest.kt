package dev.brahmkshatriya.echo.player.core.recovery

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingRecoveryRegressionTest {

    @Test
    fun networkLossTriggersRecovery() = runTest {
        val manager = StreamRecoveryManager(this)
        manager.buffering()
        manager.onNetworkLost()
        assertEquals(StreamRecoveryState.RECOVERING, manager.state.value)
    }

    @Test
    fun httpFailureIsRetryable() {
        val failure = StreamFailure(StreamFailureKind.HTTP, 503)
        assertTrue(failure.retryable)
    }

    @Test
    fun nonRetryableHttpStatusFails() {
        val failure = StreamFailure(StreamFailureKind.HTTP, 404)
        assertFalse(failure.retryable)
    }

    @Test
    fun dnsFailureIsRetryable() {
        val failure = StreamFailure(StreamFailureKind.DNS, message = "unknown host")
        assertTrue(failure.retryable)
    }

    @Test
    fun socketFailureIsRetryable() {
        val failure = StreamFailure(StreamFailureKind.SOCKET, message = "connection reset")
        assertTrue(failure.retryable)
    }

    @Test
    fun cancelledAndUnauthorizedAreNotRetryable() {
        assertFalse(StreamFailure(StreamFailureKind.NON_RETRYABLE, 401).retryable)
        assertFalse(StreamFailure(StreamFailureKind.HTTP, 401).retryable)
        assertFalse(StreamFailure(StreamFailureKind.HTTP, 404).retryable)
    }
}
