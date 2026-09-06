package dev.brahmkshatriya.echo.player.domain

import kotlinx.coroutines.CancellationException

/** Structured concurrency must survive Result-based extension/repository boundaries. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
