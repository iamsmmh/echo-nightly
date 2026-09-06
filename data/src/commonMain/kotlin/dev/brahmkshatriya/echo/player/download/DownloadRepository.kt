package dev.brahmkshatriya.echo.player.download

import dev.brahmkshatriya.echo.common.models.resolve
import dev.brahmkshatriya.echo.common.models.bytes
import dev.brahmkshatriya.echo.common.models.write

import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.platform.HttpClient
import dev.brahmkshatriya.echo.player.platform.KeyValueStore
import dev.brahmkshatriya.echo.player.platform.MusicStorage
import dev.brahmkshatriya.echo.player.platform.readPrefix
import dev.brahmkshatriya.echo.player.platform.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import dev.brahmkshatriya.echo.player.audio.recovery.isTransientNetworkFailure
import dev.brahmkshatriya.echo.player.core.RetryPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Lifecycle states of a download. */
enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

/**
 * Valid state transitions of the download state machine:
 *
 * ```
 * QUEUED ──▶ DOWNLOADING ──▶ COMPLETED
 *   ▲           │  │  └────────▶ FAILED ──▶ (retry) QUEUED
 *   │           │  └───────────▶ CANCELLED
 *   │           └──────────────▶ PAUSED ──▶ (resume) QUEUED
 *   └── (pause while queued) PAUSED / CANCELLED / FAILED
 * ```
 */
object DownloadStateMachine {

    private val transitions: Map<DownloadState, Set<DownloadState>> = mapOf(
        DownloadState.QUEUED to setOf(DownloadState.DOWNLOADING, DownloadState.PAUSED, DownloadState.CANCELLED, DownloadState.FAILED),
        DownloadState.DOWNLOADING to setOf(DownloadState.PAUSED, DownloadState.COMPLETED, DownloadState.CANCELLED, DownloadState.FAILED),
        DownloadState.PAUSED to setOf(DownloadState.QUEUED, DownloadState.CANCELLED, DownloadState.FAILED),
        DownloadState.FAILED to setOf(DownloadState.QUEUED, DownloadState.CANCELLED),
        DownloadState.COMPLETED to emptySet(),
        DownloadState.CANCELLED to emptySet()
    )

    fun isTransitionValid(from: DownloadState, to: DownloadState): Boolean {
        if (from == to) return true
        return transitions[from]?.contains(to) == true
    }

    fun transition(from: DownloadState, to: DownloadState): DownloadState {
        require(isTransitionValid(from, to)) { "Illegal download transition $from -> $to" }
        return to
    }
}

/** Persisted info about a download. */
@Serializable
data class DownloadEntry(
    val id: String,               // "<extensionId>::<trackId>"
    val extensionId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val fileName: String,
    val createdAtMs: Long,
    /** Added in v2; legacy entries are resolved from the lightweight fields. */
    val sourceTrack: Track? = null,
    val priority: Int = 0
)

/** Runtime status of a download. */
@Serializable
data class DownloadStatus(
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0,
    val bytesTotal: Long = -1,
    val filePath: String? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0,
    val attempts: Int = 0,
    val retryAtMs: Long? = null
)

data class DownloadWithStatus(val entry: DownloadEntry, val status: DownloadStatus)

/**
 * Produces the download request (URL/headers) for a track. Implemented by the
 * extension runtime (e.g. Subsonic stream URL with the configured bitrate).
 */
fun interface DownloadRequestProvider {
    suspend fun request(track: Track, maxBitrateKbps: Int): dev.brahmkshatriya.echo.player.platform.HttpRequest?
}

/**
 * Shared download manager: queue, concurrent execution, pause/resume/cancel/
 * retry, duplicate detection, file validation and progress reporting.
 *
 * Downloads are independent from streaming (the player never requires a
 * downloaded file; offline playback is a resolution preference).
 */
class DownloadRepository(
    private val http: HttpClient,
    private val storage: MusicStorage,
    private val store: KeyValueStore,
    private val logger: EchoLogger,
    private val scope: CoroutineScope,
    private val requestProvider: DownloadRequestProvider,
    private val quality: () -> Int = { 0 },
    private val maxConcurrent: Int = 2,
    private val retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 3, initialDelayMs = 2_000, maxDelayMs = 60_000),
    private val now: () -> Long = { dev.brahmkshatriya.echo.player.domain.nowEpochMs() },
    private val requestForEntry: (suspend (DownloadEntry, Int) -> dev.brahmkshatriya.echo.player.platform.HttpRequest?)? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val statusSerializer = MapSerializer(String.serializer(), DownloadStatus.serializer())
    private val _downloads = MutableStateFlow<Map<String, DownloadWithStatus>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadWithStatus>> = _downloads.asStateFlow()

    // The actor is the sole writer of metadata and the scheduler. HTTP callbacks
    // never mutate maps from a platform delegate / IO thread.
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private data class Running(val token: Any, val job: Job)
    private val running = mutableMapOf<String, Running>()
    private val deleteOnFinish = mutableSetOf<String>()
    private var retryWake: Job? = null
    private val actor: Job

    init {
        require(maxConcurrent in 1..8) { "maxConcurrent must be in 1..8" }
        require(!retryPolicy.infinite) { "Automatic download retries must be bounded" }
        val restored = store.getString(KEY_STATUS)?.let { raw ->
            runCatching { json.decodeFromString(statusSerializer, raw) }.getOrNull()
        }.orEmpty()
        _downloads.value = restored.mapNotNull { (id, status) ->
            val entry = decodeEntry(id) ?: return@mapNotNull null
            if (entry.id != id || entry.fileName.contains('/') || entry.fileName.contains('\\') || entry.fileName in setOf(".", "..")) {
                return@mapNotNull null
            }
            id to DownloadWithStatus(entry, if (status.state == DownloadState.DOWNLOADING) {
                status.copy(state = DownloadState.QUEUED, error = null)
            } else status)
        }.toMap()
        actor = scope.launch {
            pump()
            for (command in commands) {
                try { command() }
                catch (failure: Exception) { logger.error(TAG, "Could not update download queue", failure) }
                pump()
            }
        }
    }

    fun enqueue(track: Track, extensionId: String, priority: Int = 0) = submit {
        require(extensionId.isNotBlank() && track.id.isNotBlank())
        val id = "$extensionId::${track.id}"
        if (_downloads.value[id]?.status?.state in ACTIVE_OR_DONE) return@submit
        val entry = DownloadEntry(
            id, extensionId, track.id, track.title, track.artists.joinToString(", ") { it.name },
            (track.cover as? dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder)?.request?.url,
            track.duration, DownloadSupport.safeFileName(id, DownloadSupport.guessExtension(track)), now(), track, priority
        )
        // Persist the entry before its index, then publish the newly inserted entry.
        // The previous implementation tried updating an id that did not exist.
        store.putString(entryKey(id), json.encodeToString(DownloadEntry.serializer(), entry))
        _downloads.value = _downloads.value + (id to DownloadWithStatus(entry, DownloadStatus(updatedAtMs = now())))
        persistStatuses()
    }

    fun setPriority(id: String, priority: Int) = submit {
        val current = _downloads.value[id] ?: return@submit
        val entry = current.entry.copy(priority = priority)
        store.putString(entryKey(id), json.encodeToString(DownloadEntry.serializer(), entry))
        _downloads.value = _downloads.value + (id to current.copy(entry = entry))
    }

    fun pause(id: String) = submit {
        if (_downloads.value[id]?.status?.state !in PAUSABLE) return@submit
        update(id) { it.copy(state = DownloadState.PAUSED, retryAtMs = null, updatedAtMs = now()) }
        running[id]?.job?.cancel()
    }

    fun resume(id: String) = submit {
        val current = _downloads.value[id] ?: return@submit
        if (current.status.state !in setOf(DownloadState.PAUSED, DownloadState.FAILED)) return@submit
        update(id) { it.copy(state = DownloadState.QUEUED, attempts = 0, retryAtMs = null, error = null, updatedAtMs = now()) }
    }

    fun retry(id: String) = resume(id)

    fun cancel(id: String) = submit {
        val current = _downloads.value[id] ?: return@submit
        _downloads.value = _downloads.value - id
        persistStatuses()
        store.remove(entryKey(id))
        val active = running[id]
        if (active == null) deleteFiles(current.entry)
        else { deleteOnFinish += id; active.job.cancel() }
    }

    fun removeCompleted(id: String) = submit {
        val current = _downloads.value[id] ?: return@submit
        if (current.status.state != DownloadState.COMPLETED) return@submit
        deleteFiles(current.entry)
        _downloads.value = _downloads.value - id
        persistStatuses()
        store.remove(entryKey(id))
    }

    /** Legacy synchronous entry point; resolvers should prefer [verifiedCompletedFileFor]. */
    fun completedFileFor(extensionId: String, trackId: String): String? {
        val id = "$extensionId::$trackId"
        val current = _downloads.value[id] ?: return null
        if (current.status.state != DownloadState.COMPLETED) return null
        val file = destination(current.entry)
        if (DownloadHealthMonitor.inspect(file, current.status.bytesTotal) != DownloadHealth.HEALTHY) {
            submit {
                if (_downloads.value[id] == current) {
                    deleteFiles(current.entry)
                    update(id) { it.copy(state = DownloadState.QUEUED, bytesDownloaded = 0,
                        filePath = null, error = "Repairing damaged download", retryAtMs = null, attempts = 0) }
                }
            }
            return null
        }
        return file.absolutePath
    }

    suspend fun verifiedCompletedFileFor(extensionId: String, trackId: String): String? =
        withContext(Dispatchers.IO) { completedFileFor(extensionId, trackId) }

    /** Safe to repeat after startup; files are checked off the UI thread. */
    suspend fun checkHealth(): Map<String, DownloadHealth> = withContext(Dispatchers.IO) {
        _downloads.value.mapValues { (id, download) ->
            val health = DownloadHealthMonitor.inspect(destination(download.entry), download.status.bytesTotal)
            if (download.status.state == DownloadState.COMPLETED && health != DownloadHealth.HEALTHY) {
                completedFileFor(download.entry.extensionId, download.entry.trackId)
            }
            if (download.status.state == DownloadState.DOWNLOADING && now() - download.status.updatedAtMs > STALE_MS) {
                submit {
                    val current = _downloads.value[id] ?: return@submit
                    if (current.status.updatedAtMs == download.status.updatedAtMs) {
                        update(id) { it.copy(state = DownloadState.QUEUED, error = "Restarting stalled transfer") }
                        running[id]?.job?.cancel()
                    }
                }
                DownloadHealth.STALE
            } else health
        }
    }

    fun close() {
        commands.close()
        actor.cancel()
        retryWake?.cancel()
        running.values.forEach { it.job.cancel() }
    }

    private fun submit(action: () -> Unit) { commands.trySend(action) }

    private fun pump() {
        retryWake?.cancel()
        retryWake = null
        val eligible = _downloads.value.values.filter {
            it.status.state == DownloadState.QUEUED && it.entry.id !in running && (it.status.retryAtMs ?: 0) <= now()
        }.sortedWith(compareByDescending<DownloadWithStatus> { it.entry.priority }
            .thenBy { it.entry.createdAtMs }.thenBy { it.entry.id })
        eligible.take((maxConcurrent - running.size).coerceAtLeast(0)).forEach { download ->
            val id = download.entry.id
            val token = Any()
            update(id) { it.copy(state = DownloadState.DOWNLOADING, retryAtMs = null, error = null,
                filePath = destination(download.entry).absolutePath, updatedAtMs = now()) }
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { transfer(download, token) }
            running[id] = Running(token, job)
            job.start()
        }
        val nextRetry = _downloads.value.values.filter { it.status.state == DownloadState.QUEUED }
            .mapNotNull { it.status.retryAtMs }.filter { it > now() }.minOrNull()
        if (nextRetry != null) retryWake = scope.launch {
            delay((nextRetry - now()).coerceAtLeast(1))
            submit { }
        }
    }

    private suspend fun transfer(download: DownloadWithStatus, token: Any) {
        val entry = download.entry
        val id = entry.id
        val file = destination(entry)
        try {
            val request = if (requestForEntry != null) requestForEntry.invoke(entry, quality())
            else requestProvider.request(entry.sourceTrack ?: Track(entry.trackId, entry.title), quality())
            if (request == null) throw EchoError.Extension("No downloadable stream for this track", entry.extensionId)
            storage.downloadsDir.mkdirs()
            var lastProgressAt = Long.MIN_VALUE
            http.download(request, file, append = file.exists() && file.length() > 0) { read, total ->
                val time = now()
                if (lastProgressAt == Long.MIN_VALUE || time - lastProgressAt >= 250 || read == total) {
                    lastProgressAt = time
                    submit {
                        if (running[id]?.token === token && _downloads.value[id]?.status?.state == DownloadState.DOWNLOADING) {
                            update(id) { it.copy(bytesDownloaded = read, bytesTotal = total, filePath = file.absolutePath, updatedAtMs = time) }
                        }
                    }
                }
            }
            withContext(Dispatchers.IO) { validateFile(file) }
            submit {
                if (running[id]?.token === token && _downloads.value[id]?.status?.state == DownloadState.DOWNLOADING) {
                    update(id) { it.copy(state = DownloadState.COMPLETED, bytesDownloaded = file.length(), bytesTotal = file.length(),
                        filePath = file.absolutePath, error = null, retryAtMs = null, updatedAtMs = now()) }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The command that cancelled us already published PAUSED / QUEUED /
            // deletion. Never resurrect a removed entry or overwrite a newer job.
            throw cancelled
        } catch (failure: Exception) {
            submit {
                if (running[id]?.token === token && _downloads.value[id]?.status?.state == DownloadState.DOWNLOADING) {
                    val attempts = (_downloads.value[id]?.status?.attempts ?: 0) + 1
                    val retryable = failure.isTransientNetworkFailure() && retryPolicy.shouldRetry(attempts)
                    if (failure is EchoError.Storage) deleteFiles(entry)
                    update(id) { it.copy(state = if (retryable) DownloadState.QUEUED else DownloadState.FAILED,
                        bytesDownloaded = file.length(), attempts = attempts,
                        retryAtMs = if (retryable) now() + retryPolicy.delayFor(attempts) else null,
                        error = (failure as? EchoError)?.userMessage ?: "Download failed", updatedAtMs = now()) }
                }
            }
        } finally {
            submit {
                if (running[id]?.token === token) {
                    running.remove(id)
                    if (deleteOnFinish.remove(id)) deleteFiles(entry)
                }
            }
        }
    }

    private fun validateFile(file: EchoFile) {
        if (!file.exists() || file.length() < 12 || !DownloadSupport.looksLikeAudio(file.readPrefix(16))) {
            throw EchoError.Storage("Downloaded file failed audio validation")
        }
        DownloadSupport.recordChecksum(file)
    }

    private fun destination(entry: DownloadEntry) = storage.downloadsDir.resolve(entry.fileName)

    private fun deleteFiles(entry: DownloadEntry) {
        val file = destination(entry)
        file.delete()
        DownloadSupport.sidecarFor(file).delete()
    }

    private fun update(id: String, block: (DownloadStatus) -> DownloadStatus) {
        val current = _downloads.value[id] ?: return
        _downloads.value = _downloads.value + (id to current.copy(status = block(current.status)))
        persistStatuses()
    }

    private fun persistStatuses() = store.putString(KEY_STATUS,
        json.encodeToString(statusSerializer, _downloads.value.mapValues { it.value.status }))

    private fun entryKey(id: String) = "$KEY_ENTRY_PREFIX$id"
    private fun decodeEntry(id: String): DownloadEntry? = store.getString(entryKey(id))?.let {
        runCatching { json.decodeFromString(DownloadEntry.serializer(), it) }.getOrNull()
    }

    private companion object {
        const val TAG = "Downloads"
        const val KEY_STATUS = "echo.player.download.status"
        const val KEY_ENTRY_PREFIX = "echo.player.download.entry."
        const val STALE_MS = 120_000L
        val ACTIVE_OR_DONE = setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED, DownloadState.COMPLETED)
        val PAUSABLE = setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)
    }
}

enum class DownloadHealth { HEALTHY, MISSING, PARTIAL, CORRUPT, STALE }

object DownloadHealthMonitor {
    fun inspect(file: EchoFile, expectedBytes: Long = -1): DownloadHealth = when {
        !file.exists() -> DownloadHealth.MISSING
        file.length() == 0L || (expectedBytes > 0 && file.length() < expectedBytes) -> DownloadHealth.PARTIAL
        expectedBytes > 0 && file.length() != expectedBytes -> DownloadHealth.CORRUPT
        !DownloadSupport.looksLikeAudio(file.readPrefix(16)) -> DownloadHealth.CORRUPT
        !DownloadSupport.checksumValid(file) -> DownloadHealth.CORRUPT
        else -> DownloadHealth.HEALTHY
    }
}

/** Pure helpers of the download system, unit tested in common code. */
internal object DownloadSupport {

    /** Sidecar carrying `<sizeBytes> <sha256Hex>` next to a finished download. */
    fun sidecarFor(file: EchoFile): EchoFile =
        EchoFile(file.absolutePath + ".echo.sha256")

    /** Record the integrity sidecar after a successful download. */
    fun recordChecksum(file: EchoFile) {
        val hex = file.sha256Hex() ?: throw EchoError.Storage("Could not verify downloaded file")
        val sidecar = sidecarFor(file)
        sidecar.parent?.let { EchoFile(it).mkdirs() }
        sidecar.write("${file.length()} $hex".encodeToByteArray())
    }

    /**
     * @return true when the file matches its sidecar (or no sidecar exists -
     * legacy downloads stay valid, backward compatible).
     */
    fun checksumValid(file: EchoFile): Boolean {
        val sidecar = sidecarFor(file)
        if (!sidecar.exists()) return true
        val parsed = runCatching {
            sidecar.bytes()
                .decodeToString().trim().split(' ')
        }.getOrNull() ?: return false
        if (parsed.size != 2) return false
        val (sizeText, hash) = parsed[0] to parsed[1]
        val size = sizeText.toLongOrNull() ?: return false
        if (!Regex("[a-f0-9]{64}").matches(hash)) return false
        if (size != file.length()) return false
        val actual = runCatching { file.sha256Hex() }.getOrNull() ?: return false
        return actual == hash
    }

    fun safeFileName(id: String, ext: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80) + "-" +
            dev.brahmkshatriya.echo.player.domain.Sha256.hex(dev.brahmkshatriya.echo.player.domain.Sha256.digest(id.encodeToByteArray())).take(24) + "." + ext

    fun guessExtension(track: Track): String {
        val mime = track.extras["mimeType"]?.substringAfter('/')
        return when (mime?.lowercase()) {
            "mpeg", "mp3" -> "mp3"
            "aac", "mp4", "m4a" -> "m4a"
            "flac" -> "flac"
            "ogg", "vorbis" -> "ogg"
            "opus" -> "opus"
            "wav", "x-wav" -> "wav"
            else -> "mp3"
        }
    }

    /** Magic-bytes check for common audio containers. */
    fun looksLikeAudio(header: ByteArray): Boolean {
        if (header.size < 12) return false
        fun at(offset: Int, vararg bytes: Int): Boolean {
            if (offset + bytes.size > header.size) return false
            return bytes.indices.all { header[offset + it] == bytes[it].toByte() }
        }
        return at(0, 0x49, 0x44, 0x33) ||                    // mp3 ID3
            at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) || // mp3 frames
            at(4, 0x66, 0x74, 0x79, 0x70) ||                 // m4a ftyp
            at(0, 0x4F, 0x67, 0x67, 0x53) ||                 // ogg/opus
            at(0, 0x66, 0x4C, 0x61, 0x43) ||                 // flac
            (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x41, 0x56, 0x45)) || // RIFF/WAVE
            at(0, 0x30, 0x26, 0xB2, 0x75) ||                 // wma
            at(0, 0x23, 0x21, 0x41, 0x4D, 0x52)              // amr
    }
}
