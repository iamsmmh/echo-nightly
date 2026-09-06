package dev.brahmkshatriya.echo.player.download

import dev.brahmkshatriya.echo.common.models.resolve
import dev.brahmkshatriya.echo.common.models.bytes

import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.platform.HttpClient
import dev.brahmkshatriya.echo.player.platform.KeyValueStore
import dev.brahmkshatriya.echo.player.platform.MusicStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    val createdAtMs: Long
)

/** Runtime status of a download. */
@Serializable
data class DownloadStatus(
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0,
    val bytesTotal: Long = -1,
    val filePath: String? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0
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
    private val maxConcurrent: Int = 2
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val statusSerializer = MapSerializer(String.serializer(), DownloadStatus.serializer())

    private val _downloads = MutableStateFlow<Map<String, DownloadWithStatus>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadWithStatus>> = _downloads.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val semaphore = Semaphore(maxConcurrent)

    init {
        // restore persisted statuses; anything left DOWNLOADING is a process
        // death victim -> requeue if partial, else mark failed
        val raw = store.getString(KEY_STATUS)
        if (raw != null) {
            runCatching { json.decodeFromString(statusSerializer, raw) }
                .onSuccess { persisted ->
                    val entries = persisted.mapValues { (id, status) ->
                        DownloadWithStatus(
                            entry = decodeEntry(id) ?: stubEntry(id),
                            status = when (status.state) {
                                DownloadState.DOWNLOADING ->
                                    if (status.bytesDownloaded > 0) status.copy(state = DownloadState.PAUSED)
                                    else status.copy(state = DownloadState.FAILED, error = "Interrupted")
                                else -> status
                            }
                        )
                    }
                    _downloads.value = entries
                    persistStatuses()
                }
                .onFailure { store.remove(KEY_STATUS) }
        }
    }

    /**
     * Enqueues a track for download. Duplicate detection: enqueuing an
     * already active/completed download is a no-op; use [retry] or [remove].
     */
    fun enqueue(track: Track, extensionId: String) {
        val id = "$extensionId::${track.id}"
        val current = _downloads.value[id]
        if (current != null && current.status.state in ACTIVE_OR_DONE) return

        val fileName = DownloadSupport.safeFileName("$id", DownloadSupport.guessExtension(track))
        val entry = DownloadEntry(
            id = id,
            extensionId = extensionId,
            trackId = track.id,
            title = track.title,
            artist = track.artists.joinToString(", ") { it.name },
            artworkUrl = (track.cover as? dev.brahmkshatriya.echo.common.models.ImageHolder.NetworkRequestImageHolder)?.request?.url,
            durationMs = track.duration,
            fileName = fileName,
            createdAtMs = now()
        )
        store.putString(entryKey(id), json.encodeToString(DownloadEntry.serializer(), entry))
        update(id) { DownloadStatus(state = DownloadState.QUEUED, updatedAtMs = now()) }
        schedule(id)
    }

    /** Pauses an active download (cancels the transfer; partial file is kept for resume). */
    fun pause(id: String) {
        val status = _downloads.value[id]?.status ?: return
        if (status.state !in PAUSABLE) return
        jobs.remove(id)?.cancel()
        updateState(id, DownloadState.PAUSED)
    }

    /** Resumes a paused/failed download using HTTP ranges when supported. */
    fun resume(id: String) {
        val status = _downloads.value[id]?.status ?: return
        if (status.state != DownloadState.PAUSED && status.state != DownloadState.FAILED) return
        updateState(id, DownloadState.QUEUED)
        schedule(id)
    }

    fun retry(id: String) = resume(id)

    /** Cancels and removes a download (partial files are deleted). */
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        val download = _downloads.value[id] ?: return
        download.status.filePath?.let { path ->
            runCatching { EchoFile(path).delete() }
                .onFailure { logger.warn(TAG, "Could not delete cancelled file $path", it) }
        }
        store.remove(entryKey(id))
        _downloads.value = _downloads.value - id
        persistStatuses()
    }

    fun removeCompleted(id: String) {
        val download = _downloads.value[id] ?: return
        download.status.filePath?.let { path ->
            runCatching { EchoFile(path).delete() }
        }
        store.remove(entryKey(id))
        _downloads.value = _downloads.value - id
        persistStatuses()
    }

    /** Path of a completed download for [extensionId]/[trackId], if playable offline. */
    fun completedFileFor(extensionId: String, trackId: String): String? {
        val status = _downloads.value["$extensionId::$trackId"]?.status ?: return null
        if (status.state != DownloadState.COMPLETED) return null
        return status.filePath?.takeIf { EchoFile(it).exists() }
    }

    // -------------------------------------------------------------- internals

    private fun schedule(id: String) {
        if (jobs.containsKey(id)) return
        jobs[id] = scope.launch {
            try {
                semaphore.withPermit {
                    val entry = decodeEntry(id) ?: return@withPermit
                    val existingStatus = _downloads.value[id]?.status ?: return@withPermit
                    if (existingStatus.state == DownloadState.COMPLETED) return@withPermit
                    executeDownload(id, entry, existingStatus)
                }
            } finally {
                jobs.remove(id)
            }
        }
    }

    private suspend fun executeDownload(id: String, entry: DownloadEntry, status: DownloadStatus) {
        updateState(id, DownloadState.DOWNLOADING)
        try {
            val request = requestProvider.request(
                dev.brahmkshatriya.echo.common.models.Track(id = entry.trackId, title = entry.title),
                quality()
            ) ?: throw EchoError.Extension("No downloadable stream for this track", entry.extensionId)

            val destination = storage.downloadsDir.also { it.mkdirs() }.resolve(entry.fileName)
            val partialExists = destination.exists() && destination.length() > 0
            val append = partialExists && status.state == DownloadState.PAUSED

            http.download(
                request = request,
                destination = destination,
                append = append,
                onProgress = { read, total ->
                    update(id) {
                        DownloadStatus(
                            state = DownloadState.DOWNLOADING,
                            bytesDownloaded = read,
                            bytesTotal = total,
                            updatedAtMs = now()
                        )
                    }
                }
            )

            validateFile(destination)
            update(id) {
                DownloadStatus(
                    state = DownloadState.COMPLETED,
                    bytesDownloaded = destination.length(),
                    bytesTotal = destination.length(),
                    filePath = destination.absolutePath,
                    updatedAtMs = now()
                )
            }
            logger.info(TAG, "Downloaded ${entry.title} -> ${destination.absolutePath}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // paused or cancelled; keep partial bytes for resume
            if (_downloads.value[id]?.status?.state != DownloadState.CANCELLED) {
                update(id) { it.copy(state = DownloadState.PAUSED, updatedAtMs = now()) }
            }
            throw e
        } catch (e: Throwable) {
            val error = (e as? EchoError)?.userMessage ?: e.message ?: "Download failed"
            logger.error(TAG, "Download failed for ${entry.title}: $error", e)
            update(id) {
                DownloadStatus(
                    state = DownloadState.FAILED,
                    bytesDownloaded = 0,
                    bytesTotal = it.bytesTotal,
                    filePath = it.filePath,
                    error = error,
                    updatedAtMs = now()
                )
            }
        }
    }

    /** Validates a downloaded file is a plausible audio file. */
    private fun validateFile(file: EchoFile) {
        if (!file.exists() || file.length() <= 0) {
            file.delete()
            throw EchoError.Storage("Downloaded file is empty")
        }
        val header = file.bytes().copyOf(16)
        val looksAudio = DownloadSupport.looksLikeAudio(header)
        if (!looksAudio) {
            file.delete()
            throw EchoError.Storage("Downloaded file failed validation (not an audio file)")
        }
    }

    private fun update(id: String, block: (DownloadStatus) -> DownloadStatus) {
        val current = _downloads.value[id] ?: return
        val next = current.copy(status = block(current.status))
        _downloads.value = _downloads.value + (id to next)
        persistStatuses()
    }

    private fun updateState(id: String, state: DownloadState) {
        update(id) { status ->
            if (DownloadStateMachine.isTransitionValid(status.state, state)) {
                status.copy(state = state, updatedAtMs = now())
            } else {
                status
            }
        }
    }

    private fun persistStatuses() {
        store.putString(
            KEY_STATUS,
            json.encodeToString(statusSerializer, _downloads.value.mapValues { it.value.status })
        )
    }

    private fun entryKey(id: String) = "$KEY_ENTRY_PREFIX$id"

    private fun decodeEntry(id: String): DownloadEntry? {
        val raw = store.getString(entryKey(id)) ?: return null
        return runCatching { json.decodeFromString(DownloadEntry.serializer(), raw) }.getOrNull()
    }

    private fun stubEntry(id: String): DownloadEntry {
        val parts = id.split("::", limit = 2)
        return DownloadEntry(
            id = id, extensionId = parts.getOrElse(0) { "unknown" },
            trackId = parts.getOrElse(1) { id }, title = id, artist = "",
            fileName = DownloadSupport.safeFileName(id, "mp3"), createdAtMs = 0
        )
    }

    private companion object {
        const val TAG = "Downloads"
        const val KEY_STATUS = "echo.player.download.status"
        const val KEY_ENTRY_PREFIX = "echo.player.download.entry."
        val ACTIVE_OR_DONE = setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED, DownloadState.COMPLETED)
        val PAUSABLE = setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)

        fun now(): Long = dev.brahmkshatriya.echo.player.domain.nowEpochMs()
    }
}

/** Pure helpers of the download system, unit tested in common code. */
internal object DownloadSupport {

    fun safeFileName(id: String, ext: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_") + "." + ext

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
        if (header.size < 12) return true // too small to tell; accept tiny files
        fun at(offset: Int, vararg bytes: Int): Boolean {
            if (offset + bytes.size > header.size) return false
            return bytes.indices.all { header[offset + it] == bytes[it].toByte() }
        }
        return at(0, 0x49, 0x44, 0x33) ||                    // mp3 ID3
            at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) || // mp3 frames
            at(4, 0x66, 0x74, 0x79, 0x70) ||                 // m4a ftyp
            at(0, 0x4F, 0x67, 0x67, 0x53) ||                 // ogg/opus
            at(0, 0x66, 0x4C, 0x61, 0x43) ||                 // flac
            at(0, 0x52, 0x49, 0x46, 0x46) ||                 // wav riff
            at(0, 0x30, 0x26, 0xB2, 0x75) ||                 // wma
            at(0, 0x23, 0x21, 0x41, 0x4D, 0x52)              // amr
    }
}
