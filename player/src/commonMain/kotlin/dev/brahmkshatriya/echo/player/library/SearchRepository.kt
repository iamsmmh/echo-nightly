package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.domain.runCatchingCancellable
import dev.brahmkshatriya.echo.player.domain.search.*
import dev.brahmkshatriya.echo.player.extensions.ExtensionRuntime
import dev.brahmkshatriya.echo.player.extensions.local.LocalExtensionClient

/** Existing facade retained; the search algorithm now lives in the domain. */
class SearchRepository(
    private val runtime: ExtensionRuntime,
    private val library: LocalLibraryRepository,
    private val logger: EchoLogger
) {
    val universal = UniversalSearchService(::providers)
    private val _failures = kotlinx.coroutines.flow.MutableStateFlow<Map<String, SearchFailure>>(emptyMap())
    val failures: kotlinx.coroutines.flow.StateFlow<Map<String, SearchFailure>> = _failures

    private fun providers(): List<SearchProvider> = runtime.searchableExtensions().map { extension ->
        object : SearchProvider {
            override val id = extension.id
            override val name = extension.name
            override val local = id == LocalExtensionClient.ID
            override val revision = "${runtime.searchRevision}:${if (local) library.revision else 0}"
            override val priority = if (id == runtime.activeExtensionId) 10 else 0
            override suspend fun search(query: String, limit: Int): SearchPage {
                val client = extension.instance.value().getOrThrow() as? SearchFeedClient ?: return SearchPage()
                val feed = client.loadSearchFeed(query)
                val shelves = if (feed.notSortTabs.isEmpty()) feed.pagedDataOfFirst().loadPage(null).data
                else feed.notSortTabs.take(6).flatMap { feed.getPagedData(it).pagedData.loadPage(null).data }
                val items = shelves.flatMap { shelf -> when (shelf) {
                    is Shelf.Item -> listOf(shelf.media)
                    is Shelf.Lists<*> -> shelf.list.filterIsInstance<EchoMediaItem>()
                    else -> emptyList()
                } }
                return SearchPage(items.filterIsInstance<Track>().take(limit), items.filterIsInstance<Album>().take(limit),
                    items.filterIsInstance<Artist>().take(limit))
            }
        }
    }

    suspend fun search(query: String): Result<List<Shelf>> = runCatchingCancellable {
        val result = universal.search(query)
        _failures.value = result.failures
        result.failures.keys.forEach { logger.warn("Search", "Search provider unavailable: $it") }
        buildList {
            if (result.tracks.isNotEmpty()) add(Shelf.Lists.Tracks("universal-tracks", "Songs", result.tracks.map { match ->
                val preferred = match.preferred
                preferred.item.copy(extras = preferred.item.extras + (SearchProvenance.PROVIDER_ID to preferred.providerId))
            }))
            if (result.albums.isNotEmpty()) add(Shelf.Lists.Items("universal-albums", "Albums", result.albums.map { match ->
                val preferred = match.preferred
                preferred.item.copy(extras = preferred.item.extras + (SearchProvenance.PROVIDER_ID to preferred.providerId))
            }))
            if (result.artists.isNotEmpty()) add(Shelf.Lists.Items("universal-artists", "Artists", result.artists.map { match ->
                val preferred = match.preferred
                preferred.item.copy(extras = preferred.item.extras + (SearchProvenance.PROVIDER_ID to preferred.providerId))
            }))
        }
    }

    suspend fun homeFeed(): Result<List<Shelf>> = runCatchingCancellable {
        runtime.withActiveClient { client -> (client as? HomeFeedClient)?.loadHomeFeed() }.getOrThrow()?.loadAll().orEmpty()
    }
}
