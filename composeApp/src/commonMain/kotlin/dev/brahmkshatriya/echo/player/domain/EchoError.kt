package dev.brahmkshatriya.echo.player.domain

/**
 * Structured error hierarchy for every asynchronous operation in the player.
 *
 * These types are used for control flow and diagnostics; user facing messages
 * are produced by [userMessage] and rendered by the UI. [EchoError] extends
 * [RuntimeException] so errors flow naturally through `Result`, coroutine
 * `resumeWithException` and logging APIs.
 */
sealed class EchoError(
    override val message: String,
    override val cause: Throwable? = null
) : RuntimeException(message, cause) {

    open val userMessage: String
        get() = message

    data class Network(
        override val message: String,
        val statusCode: Int? = null,
        override val cause: Throwable? = null
    ) : EchoError(message, cause) {
        override val userMessage: String =
            if (statusCode != null) "Network error (HTTP $statusCode)"
            else "Network error. Check your connection and try again."
    }

    data class Playback(
        override val message: String,
        override val cause: Throwable? = null
    ) : EchoError(message, cause) {
        override val userMessage: String = "Could not play this track. $message"
    }

    data class Storage(
        override val message: String,
        override val cause: Throwable? = null
    ) : EchoError(message, cause) {
        override val userMessage: String = "Storage error. $message"
    }

    data class Database(
        override val message: String,
        override val cause: Throwable? = null
    ) : EchoError(message, cause) {
        override val userMessage: String = "A local data error occurred. $message"
    }

    data class Extension(
        override val message: String,
        val extensionId: String? = null,
        override val cause: Throwable? = null
    ) : EchoError(message, cause) {
        override val userMessage: String = "Extension error. $message"
    }

    data class Permission(
        override val message: String,
        override val cause: Throwable? = null
    ) : EchoError(message, cause) {
        override val userMessage: String = "Permission denied. $message"
    }

    data class Cancelled(
        override val message: String = "Operation cancelled"
    ) : EchoError(message)

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : EchoError(message, cause) {
        override val userMessage: String = "Something went wrong. $message"
    }
}

/** Maps any throwable into the structured [EchoError] hierarchy. */
fun Throwable.toEchoError(): EchoError = when (this) {
    is EchoError -> this
    is kotlinx.coroutines.CancellationException -> EchoError.Cancelled()
    else -> EchoError.Unknown(message ?: this::class.simpleName ?: "Unknown error", this)
}
