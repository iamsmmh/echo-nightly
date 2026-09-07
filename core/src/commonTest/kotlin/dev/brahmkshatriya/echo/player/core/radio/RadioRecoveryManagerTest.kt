@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.brahmkshatriya.echo.player.core.radio

import dev.brahmkshatriya.echo.player.core.RetryPolicy
import dev.brahmkshatriya.echo.player.core.recovery.StreamFailure
import dev.brahmkshatriya.echo.player.core.recovery.StreamFailureKind
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class StreamDropped : Exception("connection reset by peer")

private fun policy(retries: Int = 5) =
    RetryPolicy(maxRetries = retries, initialDelayMs = 1, maxDelayMs = 4, jitter = 0.0)

class RadioRecoveryManagerTest {

    @Test fun liveStreamEndIsTreatedAsReconnect() = runTest {
        var reconnects = 0
        val mgr = RadioRecoveryManager(this, policy(), sleeper = {})
        mgr.onStreamEnded { reconnects++ }
        advanceUntilIdle()
        assertEquals(1, reconnects)
        // Not "playing" until the engine confirms audio.
        assertEquals(RadioConnectionState.CONNECTING, mgr.state.value)
    }

    @Test fun retriesUntilReconnectSucceeds() = runTest {
        var attempts = 0
        val mgr = RadioRecoveryManager(this, policy(), sleeper = {})
        mgr.recover(StreamFailure(StreamFailureKind.SOCKET)) {
            attempts++
            if (attempts < 3) throw StreamDropped()
        }
        advanceUntilIdle()
        assertEquals(3, attempts)
        assertEquals(RadioConnectionState.CONNECTING, mgr.state.value)
    }

    @Test fun failsAfterBudgetExhausted() = runTest {
        var attempts = 0
        val mgr = RadioRecoveryManager(this, policy(retries = 2), sleeper = {})
        mgr.recover(StreamFailure(StreamFailureKind.TIMEOUT)) {
            attempts++
            throw StreamDropped()
        }
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(RadioConnectionState.FAILED, mgr.state.value)
    }

    @Test fun waitsForNetworkInsteadOfBurningRetries() = runTest {
        var attempts = 0
        val mgr = RadioRecoveryManager(this, policy(), sleeper = {})
        mgr.onNetworkLost()
        mgr.recover(StreamFailure(StreamFailureKind.OFFLINE)) { attempts++ }
        advanceUntilIdle()
        assertEquals(0, attempts)
        assertEquals(RadioConnectionState.RECONNECTING, mgr.state.value)
        mgr.onNetworkAvailable()
        advanceUntilIdle()
        assertEquals(1, attempts)
    }

    @Test fun playingResetsRetryBudget() = runTest {
        var attempts = 0
        val mgr = RadioRecoveryManager(this, policy(retries = 2), sleeper = {})
        mgr.recover(StreamFailure(StreamFailureKind.SOCKET)) {
            attempts++
            if (attempts == 1) throw StreamDropped()
        }
        advanceUntilIdle()
        assertEquals(2, attempts)
        mgr.onPlaying()
        assertEquals(0, mgr.attempts)
        assertEquals(RadioConnectionState.PLAYING, mgr.state.value)

        // A later outage gets a full budget again.
        mgr.recover(StreamFailure(StreamFailureKind.SOCKET)) { attempts++ }
        advanceUntilIdle()
        assertEquals(3, attempts)
    }

    @Test fun stationRecyclingStatusesAreRetryableForRadio() {
        listOf(403, 404, 408, 429, 500, 502, 503).forEach {
            assertTrue(
                RadioRecoveryManager.isRetryable(StreamFailure(StreamFailureKind.HTTP, it)),
                "expected $it retryable for radio"
            )
        }
        listOf(400, 401, 410).forEach {
            assertFalse(RadioRecoveryManager.isRetryable(StreamFailure(StreamFailureKind.HTTP, it)))
        }
        assertFalse(RadioRecoveryManager.isRetryable(StreamFailure(StreamFailureKind.NON_RETRYABLE)))
    }

    @Test fun nonRetryableFailureStopsImmediately() = runTest {
        var attempts = 0
        val mgr = RadioRecoveryManager(this, policy(), sleeper = {})
        mgr.recover(StreamFailure(StreamFailureKind.HTTP, 401)) { attempts++ }
        advanceUntilIdle()
        assertEquals(0, attempts)
        assertEquals(RadioConnectionState.FAILED, mgr.state.value)
    }

    @Test fun newFailureCancelsStaleRecovery() = runTest {
        val mgr = RadioRecoveryManager(this, policy(), sleeper = {})
        var first = 0
        var second = 0
        mgr.recover(StreamFailure(StreamFailureKind.SOCKET)) { first++; throw StreamDropped() }
        mgr.recover(StreamFailure(StreamFailureKind.SOCKET)) { second++ }
        advanceUntilIdle()
        assertEquals(1, second)
        assertTrue(first <= 1)
    }

    @Test fun closeReturnsToIdle() = runTest {
        val mgr = RadioRecoveryManager(this, policy(), sleeper = {})
        mgr.recover(StreamFailure(StreamFailureKind.SOCKET)) { throw StreamDropped() }
        mgr.close()
        advanceUntilIdle()
        assertEquals(RadioConnectionState.IDLE, mgr.state.value)
        assertEquals(0, mgr.attempts)
    }
}
