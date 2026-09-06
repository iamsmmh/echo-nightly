package dev.brahmkshatriya.echo.player.domain

/**
 * Shared logging abstraction. Implementations:
 *  - Android: `android.util.Log` (Logcat)
 *  - iOS: `NSLog`
 *
 * Sensitive values (tokens, credentials, authenticated URLs) must be passed
 * through [sanitize] before being logged.
 */
interface EchoLogger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

/** Query parameter names whose values must never be logged. */
private val sensitiveQueryParams = setOf(
    "token", "t", "password", "p", "salt", "s", "apikey", "api_key", "key", "auth", "session", "sid"
)

/**
 * Redacts credentials from a URL before logging: sensitive query parameter
 * values are replaced with `***`.
 */
fun sanitizeUrl(url: String): String {
    val queryStart = url.indexOf('?')
    if (queryStart == -1) return url
    val base = url.substring(0, queryStart)
    val query = url.substring(queryStart + 1)
    val cleaned = query.split('&').joinToString("&") { pair ->
        val key = pair.substringBefore('=')
        if (key.lowercase() in sensitiveQueryParams) "$key=***" else pair
    }
    return "$base?$cleaned"
}
