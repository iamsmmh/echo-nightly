package dev.brahmkshatriya.echo.player.domain

/**
 * Structured error hierarchy for every asynchronous operation in the player.
 *
 * These types are used for control flow and diagnostics; user facing messages
 * are produced by [userMessage] and rendered by the UI.
 */
sealed interface EchoError {
    val message: String
    val cause: Throwable?
    val userMessage: String
        get() = message

    data class Network(
        override val message: String,
        val statusCode: Int? = null,
        override val cause: Throwable? = null
    ) : EchoError {
        override val userMessage =
            if (statusCode != null) "Network error (HTTP $statusCode)"
            else "Network error. Check your connection and try again."
    }

    data class Playback(override val message: String, override val cause: Throwable? = null) : EchoError {
        override val userMessage = "Could not play this track. $message"
    }

    data class Storage(override val message: String, override val cause: Throwable? = null) : EchoError {
        override val userMessage = "Storage error. $message"
    }

    data class Database(override val message: String, override val cause: Throwable? = null) : EchoError {
        override val userMessage = "A local data error occurred. $message"
    }

    data class Extension(
        override val message: String,
        val extensionId: String? = null,
        override val cause: Throwable? = null
    ) : EchoError {
        override val userMessage = "Extension error. $message"
    }

    data class Permission(override val message: String, override val cause: Throwable? = null) : EchoError {
        override val userMessage = "Permission denied. $message"
    }

    data class Cancelled(override val message: String = "Operation cancelled") : EchoError

    data class Unknown(override val message: String, override val cause: Throwable? = null) : EchoError {
        override val userMessage = "Something went wrong. $message"
    }
}

/** Maps any throwable into the structured [EchoError] hierarchy. */
fun Throwable.toEchoError(): EchoError = when (this) {
    is EchoError -> this
    is kotlinx.coroutines.CancellationException -> EchoError.Cancelled()
    else -> EchoError.Unknown(message ?: this::class.simpleName ?: "Unknown error", this)
}

/** Runs [block], converting any failure into an [EchoError] result. */
inline fun <T> runCatchingEcho(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) throw t
    Result.failure(t.toEchoError())
}

/** Runs the suspending [block], converting any failure into an [EchoError] result. */
suspend inline fun <T> runCatchingEchoS(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) throw t
    Result.failure(t.toEchoError())
}
