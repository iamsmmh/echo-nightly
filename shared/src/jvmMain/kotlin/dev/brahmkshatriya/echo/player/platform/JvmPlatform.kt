package dev.brahmkshatriya.echo.player.platform

import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.common.models.bytes
import dev.brahmkshatriya.echo.common.models.resolve
import dev.brahmkshatriya.echo.common.models.write
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.domain.sanitizeUrl
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * JVM (desktop) actuals for the shared platform services.
 *
 * Persistence uses plain properties files under `~/.echo-shared/<name>.properties`
 * so the desktop build works without any framework; HTTP uses
 * [HttpURLConnection]. The metadata reader is intentionally minimal (JVM has no
 * MediaMetadataRetriever); duration/title are only available when the container
 * exposes them, otherwise defaults are returned.
 */
class JvmKeyValueStore(name: String) : KeyValueStore {

    private val file = File(
        System.getProperty("user.home"), ".echo-shared/$name.properties"
    ).apply { parentFile?.mkdirs() }

    private val props = java.util.Properties().apply {
        runCatching { if (file.exists()) file.inputStream().use { load(it) } }
    }

    private fun persist() {
        runCatching {
            file.outputStream().use { props.store(it, "echo shared store") }
        }
    }

    @Synchronized
    override fun getString(key: String): String? = props.getProperty(key)

    @Synchronized
    override fun putString(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        persist()
    }

    @Synchronized
    override fun putStringDurably(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        val temporary = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(temporary).use { output ->
            props.store(output, "echo shared store")
            output.fd.sync()
        }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun getLong(key: String): Long = props.getProperty(key)?.toLongOrNull() ?: 0L

    @Synchronized
    override fun putLong(key: String, value: Long) {
        props.setProperty(key, value.toString())
        persist()
    }

    @Synchronized
    override fun getBoolean(key: String): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: false

    @Synchronized
    override fun putBoolean(key: String, value: Boolean) {
        props.setProperty(key, value.toString())
        persist()
    }

    @Synchronized
    override fun remove(key: String) {
        props.remove(key)
        persist()
    }

    @Synchronized
    override fun contains(key: String): Boolean = props.containsKey(key)
}

class JvmMusicStorage : MusicStorage {

    private val root = File(System.getProperty("user.home"), "Echo").apply { mkdirs() }

    override val musicDir = EchoFile(File(root, "music").apply { mkdirs() }.absolutePath)
    override val downloadsDir = EchoFile(File(root, "downloads").apply { mkdirs() }.absolutePath)
    override val cacheDir = EchoFile(File(root, "cache").apply { mkdirs() }.absolutePath)

    override fun copyInto(sourcePath: String, dir: EchoFile, fileName: String): EchoFile {
        dir.mkdirs()
        val source = File(sourcePath)
        if (!source.exists()) throw EchoError.Storage("Import file not found: $sourcePath")
        val target = dir.resolve(fileName)
        if (target.exists()) target.delete()
        source.copyTo(File(target.absolutePath), overwrite = true)
        return target
    }
}

class JvmMetadataReader : MetadataReader {
    override fun read(path: String): AudioMetadata {
        val file = EchoFile(path)
        val mime = when (file.name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav" -> "audio/wav"
            else -> null
        }
        return AudioMetadata(title = file.name.substringBeforeLast('.'), mimeType = mime)
    }
}

class JvmEchoLogger : EchoLogger {
    private fun emit(level: String, tag: String, message: String, throwable: Throwable?) {
        println("ECHO $level [$tag] $message")
        throwable?.printStackTrace()
    }

    override fun debug(tag: String, message: String) = emit("D", tag, message, null)
    override fun info(tag: String, message: String) = emit("I", tag, message, null)
    override fun warn(tag: String, message: String, throwable: Throwable?) =
        emit("W", tag, message, throwable)

    override fun error(tag: String, message: String, throwable: Throwable?) =
        emit("E", tag, message, throwable)
}

/** java.net.http/HttpURLConnection based client for the JVM target. */
class JvmHttpClient(private val logger: EchoLogger) : HttpClient {

    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val url = URL(request.url)
        if (url.protocol !in SUPPORTED_PROTOCOLS)
            throw EchoError.Network("Unsupported protocol: ${url.protocol}")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = request.connectTimeoutMs.toInt()
            conn.readTimeout = request.requestTimeoutMs.toInt()
            conn.instanceFollowRedirects = true
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            request.body?.let { body ->
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val headers = conn.headerFields.entries
                .filter { it.key != null }
                .associate { (k, v) -> k to v.joinToString(",") }
            val bytes = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.readBytes()
            }.getOrNull() ?: ByteArray(0)
            HttpResponse(code, headers, bytes)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(TAG, "Request failed: ${sanitizeUrl(request.url)}", e)
            throw EchoError.Network(e.message ?: "Request failed", cause = e)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun download(
        request: HttpRequest,
        destination: EchoFile,
        append: Boolean,
        onProgress: (Long, Long) -> Unit
    ): EchoFile = withContext(Dispatchers.IO) {
        val target = File(destination.absolutePath)
        target.parentFile?.mkdirs()
        val resumeFrom = if (append && target.exists()) target.length() else 0L
        val url = URL(request.url)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = request.connectTimeoutMs.toInt()
            conn.readTimeout = request.requestTimeoutMs.toInt()
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept-Encoding", "identity")
            val range = if (resumeFrom > 0) "bytes=$resumeFrom-" else request.headers.header("Range")
            if (range != null) conn.setRequestProperty("Range", range)
            val code = conn.responseCode
            val headers = conn.headerFields.filterKeys { it != null }
                .map { (key, values) -> key!! to values.joinToString(",") }.toMap()
            val plan = validateDownloadResponse(code, headers, resumeFrom, range)
            val total = plan.totalBytes
            var written = plan.offset
            conn.inputStream.use { input ->
                java.io.FileOutputStream(target, plan.append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        ensureActive()
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            plan.verifyBodySize(written - plan.offset)
            destination
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(TAG, "Download failed: ${sanitizeUrl(request.url)}", e)
            throw e as? EchoError ?: EchoError.Network(e.message ?: "Download failed", cause = e)
        } finally {
            conn.disconnect()
        }
    }

    override fun close() {}

    private companion object {
        const val TAG = "JvmHttpClient"
        val SUPPORTED_PROTOCOLS = setOf("http", "https")
    }
}

actual fun createKeyValueStore(name: String): KeyValueStore = JvmKeyValueStore(name)
actual fun createMusicStorage(): MusicStorage = JvmMusicStorage()
actual fun createMetadataReader(): MetadataReader = JvmMetadataReader()
actual fun createHttpClient(logger: EchoLogger): HttpClient = JvmHttpClient(logger)
actual fun audioCapabilities(): AudioCapabilities = AudioCapabilities(
    mp3 = true, aacM4a = true, wav = true, flac = true, ogg = true, opus = true
)
