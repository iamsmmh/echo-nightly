package dev.brahmkshatriya.echo.player.platform

import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger

/**
 * A platform independent HTTP request.
 *
 * @param url The absolute URL. Only http/https are supported; the platform
 * implementations validate the scheme.
 * @param method The HTTP method.
 * @param headers Request headers.
 * @param body Optional request body.
 * @param connectTimeoutMs Connection timeout.
 * @param requestTimeoutMs Total request timeout.
 */
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val connectTimeoutMs: Long = 15_000,
    val requestTimeoutMs: Long = 30_000
)

/** A fully buffered HTTP response. */
data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
    val bodyText: String get() = body.decodeToString()
}

/**
 * KMP HTTP client abstraction with explicit timeouts, cancellation (via
 * coroutine cancellation), range resume support and progress reporting.
 *
 * Implementations: `HttpURLConnection` (Android) and `NSURLSession` (iOS).
 */
interface HttpClient {

    /** Performs a buffered request. */
    suspend fun send(request: HttpRequest): HttpResponse

    /**
     * Streams a response body to [destination] without loading it into memory.
     *
     * @param append When `true` and the server supports ranges, resumes from
     * the current length of [destination]; otherwise overwrites the file.
     * @param onProgress Invoked with (bytesDownloaded, totalBytes) where
     * totalBytes is `-1` when unknown.
     * @return The destination file.
     * @throws EchoError.Network on HTTP/transport failures.
     */
    suspend fun download(
        request: HttpRequest,
        destination: EchoFile,
        append: Boolean = false,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): EchoFile

    /** Frees any underlying sessions/connections. */
    fun close()
}

/** Creates the platform HTTP client. */
expect fun createHttpClient(logger: EchoLogger): HttpClient
