@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.brahmkshatriya.echo.player.download

import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.player.core.RetryPolicy
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.platform.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private val audio = "ID3".encodeToByteArray() + ByteArray(1021) { (it % 255).toByte() }
private val logger = object : EchoLogger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

private class TestStorage : MusicStorage {
    private val root = createMusicStorage().cacheDir.resolve("download-test-${kotlin.random.Random.nextLong()}").also { it.mkdirs() }
    override val downloadsDir = root.resolve("downloads").also { it.mkdirs() }
    override val musicDir = root.resolve("music").also { it.mkdirs() }
    override val cacheDir = root.resolve("cache").also { it.mkdirs() }
    override fun copyInto(sourcePath: String, dir: EchoFile, fileName: String): EchoFile =
        dir.resolve(fileName).also { it.write(EchoFile(sourcePath).bytes()) }
}

private class FakeHttp : HttpClient {
    val appends = mutableListOf<Boolean>()
    val requests = mutableListOf<String>()
    var barrier: CompletableDeferred<Unit>? = null
    var payload = audio
    override suspend fun send(request: HttpRequest) = HttpResponse(200, emptyMap(), ByteArray(0))
    override suspend fun download(request: HttpRequest, destination: EchoFile, append: Boolean, onProgress: (Long, Long) -> Unit): EchoFile {
        requests += request.url
        appends += append
        destination.write(payload.copyOfRange(0, 32))
        onProgress(32, payload.size.toLong())
        barrier?.await()
        destination.write(payload)
        onProgress(payload.size.toLong(), payload.size.toLong())
        return destination
    }
    override fun close() = Unit
}

class DownloadRepositoryTest {
    @Test fun enqueueActuallyInsertsAndCompletesAndPersists() = runTest {
        val store = InMemoryKeyValueStore(); val storage = TestStorage(); val http = FakeHttp()
        val repository = DownloadRepository(http, storage, store, logger, backgroundScope,
            { track, _ -> HttpRequest("https://example.invalid/${track.id}") })
        repository.enqueue(Track("a", "A", extras = mapOf("mimeType" to "audio/mpeg")), "ext")
        val result = repository.downloads.first { it["ext::a"]?.status?.state == DownloadState.COMPLETED }.getValue("ext::a")
        assertEquals(audio.size.toLong(), result.status.bytesDownloaded)
        assertNotNull(repository.completedFileFor("ext", "a"))
        repository.close()
        val restored = DownloadRepository(http, storage, store, logger, backgroundScope, { _, _ -> null })
        assertEquals(DownloadState.COMPLETED, restored.downloads.value["ext::a"]?.status?.state)
        restored.removeCompleted("ext::a")
        runCurrent()
        restored.close()
    }

    @Test fun pauseThenResumeUsesExistingBytesAndDoesNotResurrectCancelledEntry() = runTest {
        val http = FakeHttp().apply { barrier = CompletableDeferred() }
        val repository = DownloadRepository(http, TestStorage(), InMemoryKeyValueStore(), logger, backgroundScope,
            { _, _ -> HttpRequest("https://example.invalid/audio") })
        repository.enqueue(Track("a", "A"), "ext")
        runCurrent()
        repository.pause("ext::a")
        runCurrent()
        assertEquals(DownloadState.PAUSED, repository.downloads.value["ext::a"]?.status?.state)
        http.barrier = null
        repository.resume("ext::a")
        repository.downloads.first { it["ext::a"]?.status?.state == DownloadState.COMPLETED }
        assertEquals(listOf(false, true), http.appends)
        repository.cancel("ext::a")
        runCurrent()
        assertTrue(repository.downloads.value.isEmpty())
        repository.close()
    }

    @Test fun cancelDuringTransferRemovesPartialAndDoesNotReinsertMetadata() = runTest {
        val http = FakeHttp().apply { barrier = CompletableDeferred() }
        val storage = TestStorage()
        val repository = DownloadRepository(http, storage, InMemoryKeyValueStore(), logger, backgroundScope,
            { _, _ -> HttpRequest("https://example.invalid/audio") })
        repository.enqueue(Track("a", "A"), "ext")
        runCurrent()
        val file = storage.downloadsDir.resolve(repository.downloads.value.getValue("ext::a").entry.fileName)
        assertTrue(file.exists())
        repository.cancel("ext::a")
        runCurrent()
        assertTrue(repository.downloads.value.isEmpty())
        assertFalse(file.exists())
        repository.close()
    }

    @Test fun prioritiesDetermineNextAvailableSlot() = runTest {
        val http = FakeHttp().apply { barrier = CompletableDeferred() }
        val repository = DownloadRepository(http, TestStorage(), InMemoryKeyValueStore(), logger, backgroundScope,
            { track, _ -> HttpRequest("https://example.invalid/${track.id}") }, maxConcurrent = 1)
        repository.enqueue(Track("active", "Active"), "ext")
        runCurrent()
        repository.enqueue(Track("low", "Low"), "ext", priority = -1)
        repository.enqueue(Track("high", "High"), "ext", priority = 10)
        runCurrent()
        http.barrier!!.complete(Unit)
        http.barrier = null
        repository.downloads.first { it.size == 3 && it.values.all { d -> d.status.state == DownloadState.COMPLETED } }
        assertEquals(listOf("active", "high", "low"), http.requests.map { it.substringAfterLast('/') })
        repository.downloads.value.keys.forEach { repository.cancel(it) }
        runCurrent()
        repository.close()
    }

    @Test fun invalidAudioNeverCompletes() = runTest {
        val http = FakeHttp().apply { payload = "<html>not audio</html>".encodeToByteArray() + ByteArray(20) }
        val repository = DownloadRepository(http, TestStorage(), InMemoryKeyValueStore(), logger, backgroundScope,
            { _, _ -> HttpRequest("https://example.invalid/audio") }, retryPolicy = RetryPolicy(maxRetries = 0))
        repository.enqueue(Track("a", "A"), "ext")
        repository.downloads.first { it["ext::a"]?.status?.state == DownloadState.FAILED }
        assertNull(repository.completedFileFor("ext", "a"))
        repository.close()
    }

    @Test fun integritySidecarFailsClosedButLegacyFilesStayReadable() {
        val storage = TestStorage()
        val file = storage.downloadsDir.resolve("legacy.mp3")
        try {
            file.write(audio)
            assertTrue(DownloadSupport.checksumValid(file))
            DownloadSupport.recordChecksum(file)
            assertTrue(DownloadSupport.checksumValid(file))
            DownloadSupport.sidecarFor(file).write("broken".encodeToByteArray())
            assertFalse(DownloadSupport.checksumValid(file))
            DownloadSupport.recordChecksum(file)
            file.write(audio.copyOf().also { it[it.lastIndex] = 1 })
            assertEquals(DownloadHealth.CORRUPT, DownloadHealthMonitor.inspect(file, audio.size.toLong()))
        } finally { file.delete(); DownloadSupport.sidecarFor(file).delete() }
    }

    @Test fun filenamesCannotCollideAfterSanitizationOrEscapeDirectory() {
        assertNotEquals(DownloadSupport.safeFileName("a:b", "mp3"), DownloadSupport.safeFileName("a_b", "mp3"))
        assertFalse(DownloadSupport.safeFileName("../../a", "mp3").contains('/'))
        assertFalse(DownloadSupport.looksLikeAudio(ByteArray(5)))
    }
}
