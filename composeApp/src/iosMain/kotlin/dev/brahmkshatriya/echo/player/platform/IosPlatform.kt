@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.echo.player.platform

import dev.brahmkshatriya.echo.common.helpers.toByteArray
import dev.brahmkshatriya.echo.common.helpers.toNSData
import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.common.models.resolve
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.domain.sanitizeUrl
import dev.brahmkshatriya.echo.common.models.bytes
import dev.brahmkshatriya.echo.common.models.write
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*
import platform.AVFoundation.*
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "IosHttp"

/** `NSURLSession` backed HTTP client with timeouts, cancellation and range downloads. */
class IosHttpClient(
    private val logger: EchoLogger
) : HttpClient {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun send(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            try {
                val url = NSURL.URLWithString(request.url)
                    ?: throw EchoError.Network("Invalid URL: ${sanitizeUrl(request.url)}")
                val session = NSURLSession.sessionWithConfiguration(configurationFor(request))
                val urlRequest = NSMutableURLRequest(uRL = url)
                urlRequest.httpMethod = request.method
                request.headers.forEach { (key, value) ->
                    urlRequest.setValue(value, forHTTPHeaderField = key)
                }
                request.body?.let { urlRequest.httpBody = it.toNSData() }

                val task = session.dataTaskWithRequest(urlRequest) { data, response, error ->
                    if (error != null) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                EchoError.Network(error.localizedDescription, cause = null)
                            )
                        }
                        return@dataTaskWithRequest
                    }
                    val http = response as? platform.Foundation.NSHTTPURLResponse
                    val headers = mutableMapOf<String, String>()
                    http?.allHeaderFields?.forEach { (key, value) ->
                        headers[key.toString()] = value.toString()
                    }
                    val body = data?.toByteArray() ?: ByteArray(0)
                    if (continuation.isActive) {
                        continuation.resume(HttpResponse(http?.statusCode?.toInt() ?: 0, headers, body))
                    }
                }
                continuation.invokeOnCancellation { task.cancel() }
                task.resume()
            } catch (e: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        if (e is EchoError) e else EchoError.Network(e.message ?: "Request failed", cause = null)
                    )
                }
            }
        }

    override suspend fun download(
        request: HttpRequest,
        destination: EchoFile,
        append: Boolean,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): EchoFile = suspendCancellableCoroutine { continuation ->
        try {
            val url = NSURL.URLWithString(request.url)
                ?: throw EchoError.Network("Invalid URL: ${sanitizeUrl(request.url)}")
            val configuration = configurationFor(request).apply {
                timeoutIntervalForResource = 3600.0
            }
            val delegate = DownloadDelegate(destination, append, onProgress, continuation, logger)
            val session = NSURLSession.sessionWithConfiguration(
                configuration, delegate = delegate, delegateQueue = null
            )
            val urlRequest = NSMutableURLRequest(uRL = url)
            urlRequest.httpMethod = request.method
            request.headers.forEach { (key, value) ->
                urlRequest.setValue(value, forHTTPHeaderField = key)
            }
            if (append && destination.exists() && destination.length() > 0) {
                urlRequest.setValue("bytes=${destination.length()}-", forHTTPHeaderField = "Range")
            }
            val task = session.downloadTaskWithRequest(urlRequest)
            continuation.invokeOnCancellation {
                task.cancel()
                session.finishTasksAndInvalidate()
            }
            task.resume()
        } catch (e: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(
                    if (e is EchoError) e else EchoError.Network(e.message ?: "Download failed", cause = null)
                )
            }
        }
    }

    private fun configurationFor(request: HttpRequest): NSURLSessionConfiguration =
        NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
            timeoutIntervalForRequest = request.requestTimeoutMs / 1000.0
        }

    override fun close() {
        // sessions invalidate themselves via their delegates when finished
    }
}

/** Streams downloads to disk with progress; supports HTTP range resume. */
private class DownloadDelegate(
    private val destination: EchoFile,
    private val appendRequested: Boolean,
    private val onProgress: (Long, Long) -> Unit,
    private val continuation: CancellableContinuation<EchoFile>,
    private val logger: EchoLogger
) : NSObject(), NSURLSessionDownloadDelegateProtocol, NSURLSessionTaskDelegateProtocol {

    private fun baseOffset(task: platform.Foundation.NSURLSessionTask): Long {
        val status = (task.response as? platform.Foundation.NSHTTPURLResponse)?.statusCode?.toLong() ?: 200L
        return if (appendRequested && status == 206L) destination.length() else 0L
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: platform.Foundation.NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long
    ) {
        val base = baseOffset(downloadTask)
        val total = if (totalBytesExpectedToWrite > 0) totalBytesExpectedToWrite + base else -1L
        onProgress(base + totalBytesWritten, total)
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: platform.Foundation.NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL
    ) {
        val manager = NSFileManager.defaultManager()
        val rangeApplied = baseOffset(downloadTask) > 0
        try {
            val tempPath = didFinishDownloadingToURL.path
                ?: throw EchoError.Storage("Missing download temp file")
            val tempBytes = manager.contentsAtPath(tempPath)?.toByteArray()
                ?: throw EchoError.Storage("Could not read download temp file")
            if (rangeApplied && destination.exists()) {
                destination.write(destination.bytes() + tempBytes)
            } else {
                if (destination.exists()) destination.delete()
                destination.parent?.let { EchoFile(it).mkdirs() }
                destination.write(tempBytes)
            }
            manager.removeItemAtPath(tempPath, error = null)
            logger.debug(TAG, "Download finished: ${destination.absolutePath} (${destination.length()} bytes)")
            if (continuation.isActive) continuation.resume(destination)
        } catch (e: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(
                    if (e is EchoError) e else EchoError.Storage(e.message ?: "Download failed", null)
                )
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: platform.Foundation.NSURLSessionTask,
        didCompleteWithError: NSError?
    ) {
        session.finishTasksAndInvalidate()
        if (didCompleteWithError != null && continuation.isActive) {
            continuation.resumeWithException(
                EchoError.Network(didCompleteWithError.localizedDescription, cause = null)
            )
        }
    }
}

/** NSUserDefaults backed key-value store. */
class IosKeyValueStore(name: String) : KeyValueStore {
    private val defaults: NSUserDefaults =
        NSUserDefaults(suiteName = name) ?: NSUserDefaults.standardUserDefaults()

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key)
        else defaults.setObject(value, forKey = key)
    }

    override fun getLong(key: String): Long = defaults.integerForKey(key).toLong()

    override fun putLong(key: String, value: Long) = defaults.setInteger(value, forKey = key)

    override fun getBoolean(key: String): Boolean = defaults.boolForKey(key)

    override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, forKey = key)

    override fun remove(key: String) = defaults.removeObjectForKey(key)

    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null
}

/** Application Support / Caches based music storage. */
class IosMusicStorage : MusicStorage {

    private fun directory(domain: platform.Foundation.NSSearchPathDirectory, append: String): String {
        val paths = platform.Foundation.NSSearchPathForDirectoriesInDomains(
            domain, platform.Foundation.NSUserDomainMask, true
        )
        val first = (paths.firstOrNull() as? String) ?: platform.Foundation.NSTemporaryDirectory()
        return first.trimEnd('/') + "/Echo/" + append
    }

    override val musicDir: EchoFile = EchoFile(
        directory(platform.Foundation.NSApplicationSupportDirectory, "Music")
    ).also { it.mkdirs() }

    override val downloadsDir: EchoFile = EchoFile(
        directory(platform.Foundation.NSApplicationSupportDirectory, "Downloads")
    ).also { it.mkdirs() }

    override val cacheDir: EchoFile = EchoFile(
        directory(platform.Foundation.NSCachesDirectory, "Cache")
    ).also { it.mkdirs() }

    override fun copyInto(sourcePath: String, dir: EchoFile, fileName: String): EchoFile {
        dir.mkdirs()
        val target = dir.resolve(fileName)
        if (target.exists()) target.delete()
        val manager = NSFileManager.defaultManager()
        val source = NSURL.fileURLWithPath(sourcePath)
        val targetUrl = NSURL.fileURLWithPath(target.absolutePath)
        if (!manager.copyItemAtURL(source, toURL = targetUrl, error = null)) {
            val data = manager.contentsAtPath(sourcePath)
                ?: throw EchoError.Storage("Could not read imported file")
            target.write(data.toByteArray())
        }
        return target
    }
}

/** AVAsset (AVFoundation) metadata reader for local audio files. */
class IosMetadataReader : MetadataReader {

    override fun read(path: String): AudioMetadata {
        return runCatching {
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
            val metadata = asset.commonMetadata.filterIsInstance<AVMetadataItem>()

            // The AVMetadataCommonKey* constants are plain NSString values.
            fun text(key: String): String? =
                metadata.firstOrNull { it.commonKey == key }?.stringValue?.takeIf { it.isNotBlank() }

            val artworkData = metadata.firstOrNull { it.commonKey == "artwork" }?.dataValue
            // CMTime is a CValue — read value/timescale through useContents.
            val durationSeconds = asset.duration.useContents {
                if (timescale == 0) Double.NaN else value.toDouble() / timescale
            }

            val yearText = text("creationDate")

            AudioMetadata(
                title = text("title"),
                artist = text("artist"),
                album = text("album"),
                albumArtist = text("albumArtist"),
                genre = null,
                year = yearText?.take(4)?.toIntOrNull(),
                trackNumber = null,
                durationMs = if (durationSeconds.isNaN() || durationSeconds < 0) null
                else (durationSeconds * 1000).toLong(),
                artworkBytes = artworkData?.toByteArray(),
                mimeType = mimeFromExtension(path.substringAfterLast('.', ""))
            )
        }.getOrNull() ?: AudioMetadata()
    }

    private fun mimeFromExtension(ext: String): String? = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "aiff", "aif" -> "audio/aiff"
        else -> null
    }
}

/** iOS audio decoding capabilities (AVFoundation). */
actual fun audioCapabilities(): AudioCapabilities = AudioCapabilities(
    mp3 = true,
    aacM4a = true,
    wav = true,
    flac = true,
    ogg = false,   // AVPlayer does not decode Ogg Vorbis containers natively
    opus = false   // Opus is only supported inside CAF/Matroska by AVFoundation
)

actual fun createHttpClient(logger: EchoLogger): HttpClient = IosHttpClient(logger)

actual fun createKeyValueStore(name: String): KeyValueStore = IosKeyValueStore(name)

actual fun createMusicStorage(): MusicStorage = IosMusicStorage()

actual fun createMetadataReader(): MetadataReader = IosMetadataReader()
