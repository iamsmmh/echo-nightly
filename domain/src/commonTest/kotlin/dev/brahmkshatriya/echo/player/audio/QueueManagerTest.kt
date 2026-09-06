package dev.brahmkshatriya.echo.player.audio

import dev.brahmkshatriya.echo.common.models.Track
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueIdGen(private val prefix: String = "q") : QueueIdGenerator {
    private var counter = 0
    override fun next(): String = "$prefix${counter++}"
}

fun track(id: String) = Track(id = id, title = "Track $id")

class QueueManagerTest {

    private fun manager(seed: Long = 42) =
        QueueManager(ids = QueueIdGen(), random = Random(seed))

    @Test
    fun `set queue sets current to requested track`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        val current = queue.setQueue(listOf(track("a"), track("b"), track("c")), "ext", "b")
        assertEquals("b", current?.track?.id)
        assertEquals("b", queue.current?.track?.id)
    }

    @Test
    fun `next advances in order`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a"), track("b"), track("c")), "ext", "a")
        assertEquals("b", queue.advance(true)?.track?.id)
        assertEquals("c", queue.advance(true)?.track?.id)
    }

    @Test
    fun `next wraps in repeat ALL`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a"), track("b")), "ext", "a")
        queue.setRepeatMode(RepeatMode.ALL)
        queue.advance(true)
        assertEquals("a", queue.advance(true)?.track?.id)
    }

    @Test
    fun `next stops at queue end in repeat OFF`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a"), track("b")), "ext", "b")
        assertNull(queue.advance(true))
    }

    @Test
    fun `natural completion repeats current in repeat ONE`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a"), track("b")), "ext", "a")
        queue.setRepeatMode(RepeatMode.ONE)
        assertEquals("a", queue.advance(userInitiated = false)?.track?.id)
        // user initiated next still moves on
        assertEquals("b", queue.advance(userInitiated = true)?.track?.id)
    }

    @Test
    fun `previous returns earlier item`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a"), track("b"), track("c")), "ext", "c")
        assertEquals("b", queue.rewind()?.track?.id)
        assertEquals("a", queue.rewind()?.track?.id)
        // stays on first item at the boundary in OFF mode
        assertEquals("a", queue.rewind()?.track?.id)
    }

    @Test
    fun `shuffle is deterministic with same seed`() = kotlinx.coroutines.test.runTest {
        val queueA = QueueManager(ids = QueueIdGen(), random = Random(7))
        val queueB = QueueManager(ids = QueueIdGen(), random = Random(7))
        val tracks = (0 until 10).map { track("t$it") }
        queueA.setQueue(tracks, "ext", "t3", shuffle = true)
        queueB.setQueue(tracks, "ext", "t3", shuffle = true)
        assertEquals(queueA.items.map { it.track.id }, queueB.items.map { it.track.id })
    }

    @Test
    fun `shuffle keeps current first and never immediately repeats it`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue((0 until 10).map { track("t$it") }, "ext", "t5")
        queue.toggleShuffle()
        assertEquals("t5", queue.items.first().track.id)
        assertEquals("t5", queue.current?.track?.id)
        assertNotEquals("t5", queue.peekNext(userInitiated = true)?.track?.id)
    }

    @Test
    fun `shuffle preserves all items`() = kotlinx.coroutines.test.runTest {
        val queue = manager(seed = 3)
        queue.setQueue((0 until 10).map { track("t$it") }, "ext", "t0")
        queue.toggleShuffle()
        assertEquals(10, queue.items.size)
        assertEquals((0 until 10).map { "t$it" }.toSet(), queue.items.map { it.track.id }.toSet())
    }

    @Test
    fun `unshuffle restores original order`() = kotlinx.coroutines.test.runTest {
        val queue = manager(seed = 3)
        val tracks = (0 until 10).map { track("t$it") }
        queue.setQueue(tracks, "ext", "t4")
        queue.toggleShuffle()
        queue.toggleShuffle()
        assertEquals(tracks.map { it.id }, queue.items.map { it.track.id })
        assertEquals("t4", queue.current?.track?.id)
    }

    @Test
    fun `reshuffle keeps current and changes order`() = kotlinx.coroutines.test.runTest {
        val queue = QueueManager(ids = QueueIdGen(), random = Random(11))
        queue.setQueue((0 until 20).map { track("t$it") }, "ext", "t9")
        queue.toggleShuffle()
        val before = queue.items.map { it.track.id }
        queue.reshuffle()
        assertEquals("t9", queue.items.first().track.id)
        assertNotEquals(before.drop(1), queue.items.map { it.track.id }.drop(1))
    }

    @Test
    fun `add next inserts right after current`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a"), track("b"), track("c")), "ext", "a")
        val newItems = listOf(dev.brahmkshatriya.echo.player.audio.QueueItem("x1", track("x"), "ext"))
        queue.addNext(newItems)
        val ids = queue.items.map { it.track.id }
        assertEquals(listOf("a", "x", "b", "c"), ids)
        assertEquals("x", queue.peekNext(userInitiated = true)?.track?.id)
    }

    @Test
    fun `add later appends at the end`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a")), "ext", "a")
        queue.addLater(listOf(dev.brahmkshatriya.echo.player.audio.QueueItem("x1", track("x"), "ext")))
        assertEquals(listOf("a", "x"), queue.items.map { it.track.id })
    }

    @Test
    fun `remove current moves to following item`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a"), track("b"), track("c")), "ext", "b")
        val next = queue.remove(queue.current!!.id)
        assertEquals("c", next?.track?.id)
        assertEquals(listOf("a", "c"), queue.items.map { it.track.id })
        assertEquals("c", queue.current?.track?.id)
    }

    @Test
    fun `move reorders the queue`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a"), track("b"), track("c")), "ext", null)
        queue.move(0, 2)
        assertEquals(listOf("b", "c", "a"), queue.items.map { it.track.id })
    }

    @Test
    fun `clear empties the queue`() = kotlinx.coroutines.test.runTest {
        val queue = manager()
        queue.setQueue(listOf(track("a")), "ext", null)
        queue.clear()
        assertTrue(queue.items.isEmpty())
        assertNull(queue.current)
    }

    @Test
    fun `cycle repeat goes OFF-ALL-ONE-OFF`() {
        val queue = manager()
        assertEquals(RepeatMode.ALL, queue.cycleRepeatMode())
        assertEquals(RepeatMode.ONE, queue.cycleRepeatMode())
        assertEquals(RepeatMode.OFF, queue.cycleRepeatMode())
        assertFalse(queue.state.value.shuffleEnabled)
    }
}
