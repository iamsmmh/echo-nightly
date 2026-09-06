@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.brahmkshatriya.echo.player.audio

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private class RecordingEngine : PlayerEngine {
    override val engineState = MutableStateFlow(EngineState())
    override val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests = mutableListOf<EngineRequest>()
    var nowPlaying: NowPlayingInfo? = null
    var listener: RemoteCommandListener? = null
    var stopped = false
    override fun prepare(request: EngineRequest) {
        requests += request
        engineState.value = EngineState(positionMs = request.startPositionMs, durationMs = 120_000)
    }
    override fun play() { engineState.value = engineState.value.copy(isPlaying = true, playWhenReady = true) }
    override fun pause() { engineState.value = engineState.value.copy(isPlaying = false, playWhenReady = false) }
    override fun stop() { stopped = true; engineState.value = EngineState() }
    override fun seekTo(positionMs: Long) { engineState.value = engineState.value.copy(positionMs = positionMs) }
    override fun setVolume(volume: Float) = Unit
    override fun setPlaybackSpeed(speed: Float) { engineState.value = engineState.value.copy(speed = speed) }
    override fun setNowPlayingInfo(info: NowPlayingInfo) { nowPlaying = info }
    override fun setRemoteCommandListener(listener: RemoteCommandListener?) { this.listener = listener }
    override fun release() = Unit
}

private class MemoryPersister(var saved: PlaybackState? = null) : PlaybackPersister {
    var writes = 0
    override fun save(state: PlaybackState) { saved = state; writes++ }
    override fun restore(): PlaybackState? = saved
    override fun clear() { saved = null }
}

private val quiet = object : EchoLogger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

class PlaybackControllerTest {
    private fun TestScope.controller(
        engine: RecordingEngine,
        store: MemoryPersister = MemoryPersister(),
        resolver: StreamResolver = object : StreamResolver {
            override suspend fun resolve(item: QueueItem) = ResolvedStream(
                "https://example.invalid/${item.track.id}", source = ResolvedStream.Source.EXTENSION
            )
        },
        started: suspend (QueueItem) -> Unit = {}
    ) = PlaybackController(engine, resolver, QueueManager(), store, quiet, backgroundScope, started)

    private fun item(id: String) = QueueItem(id, Track(id, "Song $id", duration = 120_000), "ext")

    @Test fun initialEmptyQueueCannotEraseSavedSession() = runTest {
        val saved = PlaybackState(queue = listOf(item("a")), current = item("a"), currentId = "a", positionMs = 42_000)
        val store = MemoryPersister(saved)
        controller(RecordingEngine(), store)
        runCurrent()
        assertEquals(saved, store.saved)
        assertEquals(0, store.writes)
    }

    @Test fun restoreRetainsPositionAndPreparesBeforePlay() = runTest {
        val saved = PlaybackState(queue = listOf(item("a")), current = item("a"), currentId = "a", positionMs = 42_000, isPlaying = true)
        val engine = RecordingEngine()
        val controller = controller(engine, MemoryPersister(saved))
        controller.restoreSession()
        runCurrent()
        assertFalse(controller.state.value.isPlaying)
        assertTrue(engine.requests.isEmpty())
        controller.onPlay()
        runCurrent()
        assertEquals(42_000, engine.requests.single().startPositionMs)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test fun restorationIsIdempotentAndCannotReplaceNewUserQueue() = runTest {
        val engine = RecordingEngine()
        val controller = controller(engine, MemoryPersister(PlaybackState(queue = listOf(item("old")), currentId = "old")))
        controller.playQueue(listOf(Track("new", "New")), "ext", "new")
        runCurrent()
        controller.restoreSession()
        runCurrent()
        assertEquals("new", controller.state.value.current?.track?.id)
        assertEquals(1, engine.requests.size)
    }

    @Test fun shuffleAndRepeatSurviveRestartWithoutReshuffle() = runTest {
        val a = item("a"); val b = item("b"); val c = item("c")
        val saved = PlaybackState(queue = listOf(b, c, a), originalQueue = listOf(a, b, c), currentId = "c",
            shuffleEnabled = true, repeatMode = RepeatMode.ALL)
        val controller = controller(RecordingEngine(), MemoryPersister(saved))
        controller.restoreSession()
        runCurrent()
        assertEquals(listOf(b, c, a), controller.queue.items)
        assertEquals(RepeatMode.ALL, controller.queue.state.value.repeatMode)
        controller.toggleShuffle()
        runCurrent()
        assertEquals(listOf(a, b, c), controller.queue.items)
        assertEquals("c", controller.state.value.currentId)
    }

    @Test fun pausedDuringResolutionDoesNotStartWhenNetworkCompletes() = runTest {
        val result = CompletableDeferred<Unit>()
        val engine = RecordingEngine()
        val controller = controller(engine, resolver = object : StreamResolver {
            override suspend fun resolve(item: QueueItem): ResolvedStream {
                result.await()
                return ResolvedStream("https://example.invalid/audio", source = ResolvedStream.Source.EXTENSION)
            }
        })
        controller.playItems(listOf(item("a")))
        runCurrent()
        controller.onPause()
        result.complete(Unit)
        runCurrent()
        assertEquals(1, engine.requests.size)
        assertFalse(engine.engineState.value.playWhenReady)
    }

    @Test fun clearingQueueCancelsInFlightResolution() = runTest {
        val result = CompletableDeferred<Unit>()
        val engine = RecordingEngine()
        val controller = controller(engine, resolver = object : StreamResolver {
            override suspend fun resolve(item: QueueItem): ResolvedStream {
                result.await()
                return ResolvedStream("https://example.invalid/audio", source = ResolvedStream.Source.EXTENSION)
            }
        })
        controller.playItems(listOf(item("a")))
        runCurrent()
        controller.clearQueue()
        result.complete(Unit)
        runCurrent()
        assertTrue(engine.requests.isEmpty())
        assertFalse(controller.state.value.hasTrack)
    }

    @Test fun failedResolutionDoesNotRecordAListen() = runTest {
        var starts = 0
        val controller = controller(RecordingEngine(), resolver = object : StreamResolver {
            override suspend fun resolve(item: QueueItem): ResolvedStream = throw EchoError.Storage("Missing")
        }, started = { starts++ })
        controller.playItems(listOf(item("a")))
        runCurrent()
        assertEquals(0, starts)
        assertNotNull(controller.state.value.error)
    }

    @Test fun pauseResumeDoesNotDoubleCountHistory() = runTest {
        var starts = 0
        val controller = controller(RecordingEngine(), started = { starts++ })
        controller.playItems(listOf(item("a")))
        runCurrent()
        controller.onPause()
        runCurrent()
        controller.onPlay()
        runCurrent()
        assertEquals(1, starts)
    }

    @Test fun removingLastItemStopsEngineAndClearsCurrent() = runTest {
        val engine = RecordingEngine()
        val controller = controller(engine)
        controller.playItems(listOf(item("a")))
        runCurrent()
        controller.removeFromQueue("a")
        runCurrent()
        assertTrue(engine.stopped)
        assertFalse(controller.state.value.hasTrack)
    }

    @Test fun seekClampsAndPersistsImmediately() = runTest {
        val store = MemoryPersister()
        val controller = controller(RecordingEngine(), store)
        controller.playItems(listOf(item("a")))
        runCurrent()
        controller.seekTo(Long.MAX_VALUE)
        assertEquals(120_000, store.saved?.positionMs)
        controller.seekTo(-100)
        assertEquals(0, store.saved?.positionMs)
    }

    @Test fun queueMetadataUpdatesWithoutChangingTracks() = runTest {
        val engine = RecordingEngine()
        val controller = controller(engine)
        controller.playItems(listOf(item("a")))
        runCurrent()
        assertFalse(engine.nowPlaying!!.canGoNext)
        controller.addToQueue(listOf(Track("b", "B")), "ext", false)
        runCurrent()
        assertEquals(2, engine.nowPlaying?.queueCount)
        assertTrue(engine.nowPlaying!!.canGoNext)
    }

    @Test fun releaseDetachesRemoteCommands() = runTest {
        val engine = RecordingEngine()
        val controller = controller(engine)
        assertNotNull(engine.listener)
        controller.release()
        assertNull(engine.listener)
    }
}
