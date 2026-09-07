package dev.brahmkshatriya.echo.player.library

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineIndexManagerTest {
    private data class Song(val id: String, val title: String, val artist: String, val path: String)
    private fun index() = OfflineIndexManager<Song>({ it.id }, { listOf(it.title, it.artist) }, { it.path })

    @Test fun incrementalUpdatesAndPrefixSearchStayConsistent() {
        val index = index()
        index.rebuild(listOf(Song("1", "Night Drive", "Echo", "/1"), Song("2", "Morning", "Other", "/2")))
        assertEquals(listOf("1"), index.search("nig ech").map { it.id })
        index.upsert(Song("1", "Day Drive", "Echo", "/1"))
        assertEquals(emptyList(), index.search("night"))
        assertEquals(listOf("1"), index.search("day").map { it.id })
    }

    @Test fun orphanDetectionUpdatesIndex() {
        val index = index()
        index.rebuild(listOf(Song("1", "One", "A", "/gone"), Song("2", "Two", "B", "/kept")))
        assertEquals(listOf("1"), index.removeOrphans { it == "/kept" })
        assertEquals(1, index.size)
    }
}
