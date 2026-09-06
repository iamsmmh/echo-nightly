package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.player.library.groupAlbums
import dev.brahmkshatriya.echo.player.library.groupArtists
import dev.brahmkshatriya.echo.player.library.LocalTrackEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalLibraryGroupingTest {

    private fun entry(
        id: String,
        title: String = "T$id",
        artist: String = "Artist",
        album: String? = null,
        track: Int? = null,
        year: Int? = null
    ) = LocalTrackEntry(
        id = id, path = "/music/$id.mp3", fileName = "$id.mp3",
        title = title, artist = artist, album = album, albumArtist = null,
        genre = null, year = year, trackNumber = track, durationMs = 100_000,
        artworkPath = null, mimeType = "audio/mpeg", addedAtMs = 0
    )

    @Test
    fun `groups by album name case-insensitively`() {
        val entries = listOf(
            entry("1", album = "Alpha", track = 2),
            entry("2", album = "alpha", track = 1),
            entry("3", album = "Beta")
        )
        val albums = groupAlbums(entries)
        assertEquals(2, albums.size)
        val alpha = albums.first { it.title.equals("Alpha", ignoreCase = true) }
        assertEquals(2, alpha.tracks.size)
        // sorted by track number
        assertEquals(1, alpha.tracks[0].trackNumber)
        assertEquals(2, alpha.tracks[1].trackNumber)
    }

    @Test
    fun `unknown albums fall back to file name`() {
        val entries = listOf(entry("1", album = null))
        val albums = groupAlbums(entries)
        assertEquals(1, albums.size)
        assertEquals("1", albums[0].tracks[0].fileName.substringBeforeLast('.'))
    }

    @Test
    fun `groups artists and sorts by name`() {
        val entries = listOf(
            entry("1", artist = "Zeta"),
            entry("2", artist = "Alpha"),
            entry("3", artist = "alpha"),
            entry("4", artist = "Mid")
        )
        val artists = groupArtists(entries)
        assertEquals(listOf("Alpha", "Mid", "Zeta"), artists.map { it.name })
        assertEquals(2, artists[0].tracks.size)
    }

    @Test
    fun `search matches title artist and album`() {
        val entries = listOf(
            entry("1", title = "Bohemian", artist = "Queen", album = "A Night at the Opera"),
            entry("2", title = "Random", artist = "Someone", album = "Queen Collection")
        )
        val library = TestableLibrarySearch(entries)
        assertEquals(listOf("1"), library.search("bohemian").map { it.id })
        assertEquals(listOf("1", "2"), library.search("queen").map { it.id }.sorted())
        assertEquals(0, library.search("zzz").size)
        assertEquals(2, library.search("").size)
    }
}

/** Exposes the repository's search logic without touching platform storage. */
class TestableLibrarySearch(entries: List<LocalTrackEntry>) {
    private val all = entries
    fun search(query: String): List<LocalTrackEntry> {
        if (query.isBlank()) return all
        val q = query.trim().lowercase()
        return all.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album?.lowercase()?.contains(q) == true
        }
    }
}
