package dev.brahmkshatriya.echo.utils

import dev.brahmkshatriya.echo.utils.CoroutineUtils.future
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import java.util.concurrent.ExecutionException
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineFutureTest {
    @Test fun successAndFailureCompleteTheFuture() = runTest {
        val success = future(coroutineContext) { "result" }
        val failure = future<String>(coroutineContext) { throw IllegalArgumentException("rejected") }
        runCurrent()
        assertEquals("result", success.get())
        assertTrue(assertFailsWith<ExecutionException> { failure.get() }.cause is IllegalArgumentException)
    }
    @Test fun futureCancellationCancelsItsCoroutine() = runTest {
        var cleanedUp = false
        val pending = future<Unit>(coroutineContext) {
            try { awaitCancellation() } finally { cleanedUp = true }
        }
        runCurrent()
        pending.cancel(true)
        runCurrent()
        assertTrue(cleanedUp)
        assertTrue(pending.isCancelled)
    }
    @Test fun cancellationBeforeLaunchDoesNotLeaveADanglingFuture() = runTest {
        val parent = Job().also { it.cancel() }
        val scope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
        val result = scope.future(EmptyCoroutineContext) { "unreachable" }
        runCurrent()
        assertTrue(result.isCancelled)
    }
}
