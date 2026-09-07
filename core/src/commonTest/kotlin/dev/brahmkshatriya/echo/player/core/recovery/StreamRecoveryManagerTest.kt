@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.brahmkshatriya.echo.player.core.recovery

import dev.brahmkshatriya.echo.player.core.RetryPolicy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SocketFailure(message: String) : Exception(message)

class StreamRecoveryManagerTest {
    @Test fun retryableStatusesAreExplicit() {
        listOf(429, 500, 502, 503).forEach { assertTrue(StreamFailure(StreamFailureKind.HTTP, it).retryable) }
        listOf(400, 401, 404, 501).forEach { assertFalse(StreamFailure(StreamFailureKind.HTTP, it).retryable) }
    }

    @Test fun retriesThenRestores() = runTest {
        var attempts = 0
        val manager = StreamRecoveryManager(this,
            ExponentialBackoffStrategy(RetryPolicy(3, 1, 4, jitter = 0.0)), sleeper = {})
        manager.recover(StreamFailure(StreamFailureKind.TIMEOUT)) {
            attempts++
            if (attempts < 2) throw SocketFailure("socket reset")
        }
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(StreamRecoveryState.RESTORED, manager.state.value)
    }

    @Test fun waitsForNetworkAndDoesNotDuplicateRecovery() = runTest {
        var attempts = 0
        val manager = StreamRecoveryManager(this,
            ExponentialBackoffStrategy(RetryPolicy(2, jitter = 0.0)), sleeper = {})
        manager.onNetworkLost()
        manager.recover(StreamFailure(StreamFailureKind.OFFLINE)) { attempts++ }
        advanceUntilIdle()
        assertEquals(0, attempts)
        assertEquals(StreamRecoveryState.RECOVERING, manager.state.value)
        manager.onNetworkAvailable()
        advanceUntilIdle()
        assertEquals(1, attempts)
        assertEquals(StreamRecoveryState.RESTORED, manager.state.value)
    }
}
