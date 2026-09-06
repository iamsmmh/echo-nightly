package dev.brahmkshatriya.echo.player.lyrics

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.platform.InMemoryKeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LyricsRepositoryTest {
    private val request = LyricsRequest("ext", Track("a", "A"))
    private val lyric = Lyrics("l", "Lyrics", lyrics = Lyrics.Simple("Hello"))
    @Test fun cacheSurvivesRestartAndOfflineNeverCallsNetwork() = runTest {
        val store = InMemoryKeyValueStore()
        var calls = 0
        val provider = LyricsProvider { calls++; lyric }
        val repo = LyricsRepository(store, provider)
        assertNull(repo.get(request, offlineOnly = true))
        assertEquals(0, calls)
        assertEquals(lyric, repo.get(request))
        assertEquals(lyric, LyricsRepository(store, provider).get(request, offlineOnly = true))
        assertEquals(1, calls)
    }
    @Test fun cacheIsBoundedAndProviderNamespaced() = runTest {
        val repo = LyricsRepository(InMemoryKeyValueStore(), { lyric }, maxEntries = 1)
        repo.save(request, lyric)
        repo.save(request.copy(extensionId = "other"), lyric)
        assertNull(repo.get(request, offlineOnly = true))
        assertEquals(lyric, repo.get(request.copy(extensionId = "other"), offlineOnly = true))
    }
    @Test fun removalAndCancellationWork() = runTest {
        val repo = LyricsRepository(InMemoryKeyValueStore(), { throw CancellationException() })
        repo.save(request, lyric)
        repo.remove(request)
        assertNull(repo.get(request, offlineOnly = true))
        assertFailsWith<CancellationException> { repo.get(request) }
    }
    @Test fun accountsCannotReadEachOthersLyrics() = runTest {
        val repo = LyricsRepository(InMemoryKeyValueStore(), { lyric })
        repo.save(request.copy(sourceIdentity = "first-server"), lyric)
        assertNull(repo.get(request.copy(sourceIdentity = "second-server"), offlineOnly = true))
    }
    @Test fun corruptCacheIsRejectedWithoutNetworkInOfflineMode() = runTest {
        val store = InMemoryKeyValueStore()
        var called = false
        val repo = LyricsRepository(store, { called = true; lyric })
        repo.save(request, lyric)
        val key = "echo.lyrics.v1." + dev.brahmkshatriya.echo.player.domain.Sha256.digestHex("ext\u0000\u0000a")
        store.putString(key, "truncated cache")
        assertNull(repo.get(request, offlineOnly = true))
        assertFalse(called)
        assertNull(store.getString(key))
    }
}
