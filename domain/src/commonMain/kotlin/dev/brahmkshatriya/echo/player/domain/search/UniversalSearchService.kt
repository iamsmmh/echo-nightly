package dev.brahmkshatriya.echo.player.domain.search

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.domain.nowEpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Providers implement this port; the domain has no extension-runtime/data dependency. */
interface SearchProvider {
    val id: String
    val name: String
    val priority: Int get() = 0
    val local: Boolean get() = false
    /** Changes on account, configuration or library mutation; invalidates private cached results. */
    val revision: String get() = "0"
    suspend fun search(query: String, limit: Int): SearchPage
}

data class SearchPage(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList()
)

data class SearchSource<T>(val providerId: String, val providerName: String, val item: T)
data class SearchMatch<T>(val sources: List<SearchSource<T>>) {
    val preferred: SearchSource<T> get() = sources.first()
}

enum class SearchFailure { TIMED_OUT, UNAVAILABLE }

data class UniversalSearchResult(
    val tracks: List<SearchMatch<Track>> = emptyList(),
    val albums: List<SearchMatch<Album>> = emptyList(),
    val artists: List<SearchMatch<Artist>> = emptyList(),
    val failures: Map<String, SearchFailure> = emptyMap(),
    val fromCache: Boolean = false
)

object SearchProvenance {
    const val PROVIDER_ID = "echo.providerId"
}

/** Parallel, bounded, cancellation-safe aggregation with a bounded TTL/LRU cache. */
class UniversalSearchService(
    private val providers: () -> List<SearchProvider>,
    private val now: () -> Long = ::nowEpochMs,
    private val timeoutMs: Long = 3_000,
    private val ttlMs: Long = 60_000,
    private val maxCachedQueries: Int = 64,
    private val concurrency: Int = 4
) {
    private data class CacheKey(val query: String, val limit: Int, val providers: List<List<String>>)
    private data class Cached(val at: Long, val result: UniversalSearchResult)
    private val cache = linkedMapOf<CacheKey, Cached>()
    private val mutex = Mutex()

    init {
        require(timeoutMs > 0 && ttlMs >= 0 && maxCachedQueries >= 0 && concurrency in 1..16)
    }

    suspend fun clearCache() = mutex.withLock { cache.clear() }

    suspend fun search(query: String, limit: Int = 50): UniversalSearchResult {
        require(limit in 1..500)
        val normalized = normalize(query).take(256)
        if (normalized.isEmpty()) return UniversalSearchResult()
        val available = providers().distinctBy { it.id }.sortedWith(
            compareByDescending<SearchProvider> { it.local }.thenByDescending { it.priority }.thenBy { it.id }
        ).take(64)
        val key = CacheKey(normalized, limit, available.map { listOf(it.id, it.revision, it.priority.toString(), it.local.toString()) })
        mutex.withLock {
            val hit = cache.remove(key)
            if (hit != null && now() - hit.at in 0 until ttlMs) {
                cache[key] = hit
                return hit.result.copy(fromCache = true)
            }
        }
        val semaphore = Semaphore(concurrency)
        val pages = supervisorScope {
            available.map { provider -> async {
                semaphore.withPermit {
                    try {
                        val page = withTimeoutOrNull(timeoutMs) { provider.search(normalized, limit) }
                        Triple(provider, page, if (page == null) SearchFailure.TIMED_OUT else null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Triple(provider, null, SearchFailure.UNAVAILABLE)
                    }
                }
            } }.awaitAll()
        }
        val failures = pages.mapNotNull { (provider, _, error) -> error?.let { provider.id to it } }.toMap()
        val tracks = pages.flatMap { (provider, page, _) -> page?.tracks.orEmpty().take(limit).map { SearchSource(provider.id, provider.name, it) } }
        val albums = pages.flatMap { (provider, page, _) -> page?.albums.orEmpty().take(limit).map { SearchSource(provider.id, provider.name, it) } }
        val artists = pages.flatMap { (provider, page, _) -> page?.artists.orEmpty().take(limit).map { SearchSource(provider.id, provider.name, it) } }
        val result = UniversalSearchResult(
            tracks = merge(tracks, { trackIdentity(it) }) { a, b ->
                a.item.isrc?.let { normalizeIsrc(it) } != null || a.item.duration == null || b.item.duration == null ||
                    abs(a.item.duration!! - b.item.duration!!) <= 2_000
            }.sortedByDescending { relevance(it.preferred.item.title, normalized) }.take(limit),
            albums = merge(albums, { albumIdentity(it) }).sortedByDescending { relevance(it.preferred.item.title, normalized) }.take(limit),
            artists = merge(artists, { artistIdentity(it) }).sortedByDescending { relevance(it.preferred.item.name, normalized) }.take(limit),
            failures = failures
        )
        // Never cache an outage as "no results". Mutable provider snapshots are
        // part of the key; switching accounts cannot reuse the old account's data.
        if (failures.isEmpty() && maxCachedQueries > 0 && ttlMs > 0) mutex.withLock {
            cache[key] = Cached(now(), result)
            while (cache.size > maxCachedQueries) cache.remove(cache.keys.first())
        }
        return result
    }

    private fun <T> merge(
        sources: List<SearchSource<T>>,
        identity: (SearchSource<T>) -> String,
        compatible: (SearchSource<T>, SearchSource<T>) -> Boolean = { _, _ -> true }
    ): List<SearchMatch<T>> {
        val groups = linkedMapOf<String, MutableList<MutableList<SearchSource<T>>>>()
        sources.forEach { source ->
            val candidates = groups.getOrPut(identity(source)) { mutableListOf() }
            val group = candidates.firstOrNull { compatible(it.first(), source) }
            if (group == null) candidates += mutableListOf(source)
            else if (group.none { it.providerId == source.providerId && it.item == source.item }) group += source
        }
        return groups.values.flatten().map { SearchMatch(it.toList()) }
    }

    private fun trackIdentity(source: SearchSource<Track>): String {
        val track = source.item
        normalizeIsrc(track.isrc)?.let { return "isrc:$it" }
        val artists = track.artists.map { normalize(it.name) }.filter { it.isNotEmpty() }.sorted()
        if (artists.isEmpty() || track.title.isBlank()) return "id:${source.providerId}:${track.id}"
        // Preserve version/live/remaster suffixes, type and explicit editions.
        return "track:${normalize(track.title)}|${artists.joinToString("|")}|${track.type}|${track.isExplicit}"
    }

    private fun albumIdentity(source: SearchSource<Album>): String {
        val album = source.item
        val artists = album.artists.map { normalize(it.name) }.filter { it.isNotEmpty() }.sorted()
        if (artists.isEmpty() || album.title.isBlank()) return "id:${source.providerId}:${album.id}"
        return "album:${normalize(album.title)}|${artists.joinToString("|")}|${album.releaseDate?.year ?: ""}|${album.type ?: ""}"
    }

    private fun artistIdentity(source: SearchSource<Artist>): String {
        val artist = source.item
        val mbid = artist.extras["musicBrainzId"]?.takeIf { it.isNotBlank() }
        return mbid?.let { "mbid:$it" } ?: if (artist.name.isBlank()) "id:${source.providerId}:${artist.id}"
        else "artist:${normalize(artist.name)}|${normalize(artist.extras["disambiguation"].orEmpty())}"
    }

    private fun relevance(title: String, query: String): Int {
        val value = normalize(title)
        return when { value == query -> 3; value.startsWith(query) -> 2; value.contains(query) -> 1; else -> 0 }
    }

    private fun normalizeIsrc(value: String?): String? = value?.uppercase()?.replace("-", "")?.replace(" ", "")
        ?.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}").matches(it) }

    private fun normalize(value: String) = value.trim().lowercase().replace(Regex("\\s+"), " ")
}
