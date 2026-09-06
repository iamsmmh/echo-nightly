package dev.brahmkshatriya.echo.player.platform

import android.content.Context
import android.util.Log
import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.common.models.resolve
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.domain.sanitizeUrl
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import android.media.MediaMetadataRetriever

/**
 * Android context holder for the shared module. The Android host (app or a
 * future Compose-based Echo activity) initializes this once.
 */
object EchoPlayerAndroid {
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isInitialized(): Boolean = ::appContext.isInitialized
}

/** Logcat logger. */
class AndroidEchoLogger : EchoLogger {
    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }
    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }
    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
    }
    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}

/** SharedPreferences backed key-value store. */
class AndroidKeyValueStore(name: String) : KeyValueStore {
    private val prefs = EchoPlayerAndroid.appContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    override fun getLong(key: String): Long = prefs.getLong(key, 0)
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun getBoolean(key: String): Boolean = prefs.getBoolean(key, false)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun contains(key: String): Boolean = prefs.contains(key)
}

/** App-private storage based music storage. */
class AndroidMusicStorage : MusicStorage {
    private val context get() = EchoPlayerAndroid.appContext

    override val musicDir: EchoFile =
        EchoFile(context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath)
            .resolve("music").also { it.mkdirs() }

    override val downloadsDir: EchoFile =
        EchoFile(context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath)
            .resolve("downloads").also { it.mkdirs() }

    override val cacheDir: EchoFile =
        EchoFile(context.cacheDir.absolutePath).resolve("echo").also { it.mkdirs() }

    override fun copyInto(sourcePath: String, dir: EchoFile, fileName: String): EchoFile {
        dir.mkdirs()
        val target = dir.resolve(fileName)
        if (target.exists()) target.delete()
        val source = File(sourcePath)
        if (!source.exists()) throw EchoError.Storage("Import file not found: $sourcePath")
        source.copyTo(File(target.absolutePath), overwrite = true)
        return target
    }
}

/** MediaMetadataRetriever based metadata reader. */
class AndroidMetadataReader : MetadataReader {
    override fun read(path: String): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            AudioMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull(),
                trackNumber = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
                )?.substringBefore('/')?.toIntOrNull(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                artworkBytes = retriever.embeddedPicture,
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            )
        } catch (e: Exception) {
            AudioMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** HttpURLConnection based HTTP client with timeouts, cancellation and range downloads. */
class AndroidHttpClient(private val logger: EchoLogger) : HttpClient {

    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = open(request)
        try {
            execute(request, connection)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(
        request: HttpRequest,
        destination: EchoFile,
        append: Boolean,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): EchoFile = withContext(Dispatchers.IO) {
        val file = File(destination.absolutePath)
        val base = if (append && file.exists() && file.length() > 0) file.length() else 0
        val connection = open(request)
        try {
            connection.setRequestProperty("Accept-Encoding", "identity")
            val range = if (base > 0) "bytes=$base-" else request.headers.header("Range")
            if (range != null) connection.setRequestProperty("Range", range)
            val status = connection.responseCode
            val headers = connection.headerFields.filterKeys { it != null }
                .map { (key, value) -> key!! to value.joinToString(",") }.toMap()
            val plan = validateDownloadResponse(status, headers, base, range)
            val appendMode = plan.append
            val total = plan.totalBytes

            file.parentFile?.mkdirs()
            val output = java.io.FileOutputStream(file, appendMode)
            val input = connection.inputStream
            try {
                val buffer = ByteArray(8192)
                var read: Int
                var written = if (appendMode) base else 0L
                while (input.read(buffer).also { read = it } >= 0) {
                    ensureActive()
                    output.write(buffer, 0, read)
                    written += read
                    onProgress(written, total)
                }
                output.flush()
                plan.verifyBodySize(written - plan.offset)
            } finally {
                runCatching { output.close() }
                runCatching { input.close() }
            }
            destination
        } finally {
            connection.disconnect()
        }
    }

    private fun open(request: HttpRequest): HttpURLConnection {
        val url = runCatching { URL(request.url) }.getOrNull()
            ?: throw EchoError.Network("Invalid URL: ${sanitizeUrl(request.url)}")
        if (url.protocol != "https" && url.protocol != "http") {
            throw EchoError.Network("Unsupported scheme: ${url.protocol}")
        }
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = request.connectTimeoutMs.toInt()
        connection.readTimeout = request.requestTimeoutMs.toInt()
        connection.instanceFollowRedirects = true
        request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        return connection
    }

    private fun execute(request: HttpRequest, connection: HttpURLConnection): HttpResponse {
        request.body?.let { body ->
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val headers = mutableMapOf<String, String>()
        for ((key, value) in connection.headerFields) {
            if (key != null && value != null) headers[key.lowercase()] = value.firstOrNull() ?: ""
        }
        val bytes = try {
            if (status in 200..399) connection.inputStream?.use { it.readBytes() } ?: ByteArray(0)
            else connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
        } catch (e: IOException) {
            throw EchoError.Network("Read failed: ${e.message}", cause = e)
        }
        if (status !in 200..299) {
            throw EchoError.Network("Request failed", statusCode = status)
        }
        return HttpResponse(status, headers, bytes)
    }

    override fun close() {}

    private companion object {
        private const val TAG = "AndroidHttp"
    }
}

/** Android (ExoPlayer/Media3) decoding capabilities. */
actual fun audioCapabilities(): AudioCapabilities = AudioCapabilities(
    mp3 = true,
    aacM4a = true,
    wav = true,
    flac = true,
    ogg = true,
    opus = true
)

actual fun createHttpClient(logger: EchoLogger): HttpClient = AndroidHttpClient(logger)

actual fun createKeyValueStore(name: String): KeyValueStore = AndroidKeyValueStore(name)

actual fun createMusicStorage(): MusicStorage {
    check(EchoPlayerAndroid.isInitialized()) {
        "Call EchoPlayerAndroid.init(context) before using the player on Android"
    }
    return AndroidMusicStorage()
}

actual fun createMetadataReader(): MetadataReader = AndroidMetadataReader()

