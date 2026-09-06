package dev.brahmkshatriya.echo.player.domain.search

import dev.brahmkshatriya.echo.common.models.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private class Provider(
    override val id: String, var page: SearchPage,
    override val local: Boolean = false, override val priority: Int = 0,
    var waitMs: Long = 0, var failure: Exception? = null
) : SearchProvider {
    override val name = id
    override var revision = "0"
    var calls = 0
    override suspend fun search(query: String, limit: Int): SearchPage {
        calls++
        delay(waitMs)
        failure?.let { throw it }
        return page
    }
}

class UniversalSearchServiceTest {
    private fun track(id: String, title: String = "Song", duration: Long = 100_000) =
        Track(id, title, artists = listOf(Artist("artist", "Artist")), duration = duration)

    @Test fun aggregatesAndPreservesOriginsWithLocalPreferred() = runTest {
        val remote = Provider("server", SearchPage(tracks = listOf(track("remote"))), priority = 999)
        val local = Provider("local", SearchPage(tracks = listOf(track("local"))), local = true)
        val result = UniversalSearchService({ listOf(remote, local) }).search(" song ")
        assertEquals(1, result.tracks.size)
        assertEquals(listOf("local", "server"), result.tracks.single().sources.map { it.providerId })
    }
    @Test fun versionsAndDifferentPerformersDoNotCollapse() = runTest {
        val provider = Provider("p", SearchPage(tracks = listOf(track("a"), track("live", "Song (Live)"),
            track("cover").copy(artists = listOf(Artist("other", "Other"))), track("long", duration = 150_000))))
        assertEquals(4, UniversalSearchService({ listOf(provider) }).search("song").tracks.size)
    }
    @Test fun isrcMergesDifferentProviderMetadata() = runTest {
        val a = Provider("a", SearchPage(tracks = listOf(track("a").copy(isrc = "US-AAA-26-00001"))))
        val b = Provider("b", SearchPage(tracks = listOf(track("b", "Song Remastered").copy(isrc = "USAAA2600001"))))
        assertEquals(1, UniversalSearchService({ listOf(a, b) }).search("song").tracks.size)
    }
    @Test fun albumsAndArtistsMergeButAlbumArtistMatters() = runTest {
        val artist = Artist("a", "Band")
        val a = Provider("a", SearchPage(albums = listOf(Album("a", "Album", artists = listOf(artist))), artists = listOf(artist)))
        val b = Provider("b", SearchPage(albums = listOf(Album("b", "album", artists = listOf(artist)),
            Album("other", "Album", artists = listOf(Artist("o", "Another Band")))), artists = listOf(artist.copy(id = "b"))))
        val result = UniversalSearchService({ listOf(a, b) }).search("album")
        assertEquals(2, result.albums.size)
        assertEquals(2, result.albums.first().sources.size)
        assertEquals(1, result.artists.size)
    }
    @Test fun failedAndTimedOutProvidersDoNotDiscardWorkingResults() = runTest {
        val healthy = Provider("ok", SearchPage(tracks = listOf(track("a"))))
        val slow = Provider("slow", SearchPage(), waitMs = 5_000)
        val broken = Provider("broken", SearchPage(), failure = IllegalStateException())
        val service = UniversalSearchService({ listOf(healthy, slow, broken) }, timeoutMs = 100)
        val result = service.search("song")
        assertEquals(1, result.tracks.size)
        assertEquals(SearchFailure.TIMED_OUT, result.failures["slow"])
        assertEquals(SearchFailure.UNAVAILABLE, result.failures["broken"])
        assertFalse(service.search("song").fromCache)
    }
    @Test fun cancellationIsNotConvertedIntoEmptySuccess() = runTest {
        val provider = Provider("p", SearchPage(), failure = CancellationException())
        assertFailsWith<CancellationException> { UniversalSearchService({ listOf(provider) }).search("x") }
    }
    @Test fun cacheIsNormalizedBoundedExpiresAndInvalidatesOnAccountChange() = runTest {
        var time = 0L
        val provider = Provider("p", SearchPage(tracks = listOf(track("a"))))
        val service = UniversalSearchService({ listOf(provider) }, now = { time }, ttlMs = 100, maxCachedQueries = 1)
        assertFalse(service.search(" Song ").fromCache)
        assertTrue(service.search("song").fromCache)
        provider.revision = "new-account"
        assertFalse(service.search("song").fromCache)
        time = 101
        assertFalse(service.search("song").fromCache)
        service.search("other")
        assertFalse(service.search("song").fromCache)
        time = 0
        assertFalse(service.search("song").fromCache)
        service.clearCache()
        assertFalse(service.search("song").fromCache)
    }
    @Test fun blankQueryNeverContactsProviders() = runTest {
        val p = Provider("p", SearchPage())
        assertTrue(UniversalSearchService({ listOf(p) }).search(" \n ").tracks.isEmpty())
        assertEquals(0, p.calls)
    }
    @Test fun resultLimitAndStableRanking() = runTest {
        val p = Provider("p", SearchPage(tracks = listOf(track("prefix", "Song two"), track("exact", "Song"))))
        val service = UniversalSearchService({ listOf(p) })
        assertEquals("exact", service.search("song", 2).tracks.first().preferred.item.id)
        assertEquals(1, service.search("song", 1).tracks.size)
        assertFailsWith<IllegalArgumentException> { service.search("song", 0) }
    }
    @Test fun missingMetadataCannotMergeUnrelatedTracks() = runTest {
        val a = Provider("a", SearchPage(tracks = listOf(Track("x", "Untitled"))))
        val b = Provider("b", SearchPage(tracks = listOf(Track("x", "Untitled"))))
        assertEquals(2, UniversalSearchService({ listOf(a, b) }).search("untitled").tracks.size)
    }
}
