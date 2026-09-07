package dev.brahmkshatriya.echo.player.core

import dev.brahmkshatriya.echo.player.core.recovery.StreamRecoveryManager
import dev.brahmkshatriya.echo.player.core.recovery.StreamFailure
import dev.brahmkshatriya.echo.player.core.recovery.StreamFailureKind
import kotlinx.coroutines.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingRecoveryRegressionTest {

    @Test
    fun networkLossTriggersRecovery() = runTest {
        val manager = StreamRecoveryManager(this)
        manager.buffering()
        manager.onNetworkLost()
        assertEquals(dev.brahmkshatriya.echo.player.core.recovery.StreamRecoveryState.RECOVERING, manager.state.value)
    }

    @Test
    fun httpFailureIsRetryable() = runTest {
        val failure = StreamFailure(StreamFailureKind.HTTP, 503)
        assertEquals(true, failure.retryable)
    }

    @Test
    fun nonRetryableHttpStatusFails() = runTest {
        val failure = StreamFailure(StreamFailureKind.HTTP, 404)
        assertEquals(false, failure.retryable)
    }

    @Test
    fun dnsFailureIsRetryable() = runTest {
        val failure = StreamFailure(StreamFailureKind.DNS, message = "unknown host")
        assertEquals(true, failure.retryable)
    }

    @Test
    fun socketFailureIsRetryable() = runTest {
        val failure = StreamFailure(StreamFailureKind.SOCKET, message = "connection reset")
        assertEquals(true, failure.retryable)
    }
}
