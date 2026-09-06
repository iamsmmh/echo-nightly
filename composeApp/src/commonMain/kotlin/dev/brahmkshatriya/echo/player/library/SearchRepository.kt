package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.common.helpers.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.extensions.ExtensionRuntime

/**
 * Shared search: merges results from the imported local library and the active
 * extension into one shelf list.
 */
class SearchRepository(
    private val runtime: ExtensionRuntime,
    private val library: LocalLibraryRepository,
    private val logger: EchoLogger
) {

    suspend fun search(query: String): Result<List<Shelf>> {
        if (query.isBlank()) return Result.success(emptyList())
        val shelves = mutableListOf<Shelf>()

        val localResults = library.search(query)
        if (localResults.isNotEmpty()) {
            shelves.add(
                Shelf.Lists.Items(
                    id = "local-songs",
                    title = "On this device",
                    list = localResults.take(20).map { library.asTrack(it) },
                    type = Shelf.Lists.Type.Linear
                )
            )
        }

        runtime.withActiveClient { client ->
            (client as? SearchFeedClient)?.loadSearchFeed(query)
        }.onSuccess { feed ->
            if (feed != null) {
                runCatching { feed.loadAll() }
                    .onSuccess { shelves.addAll(it) }
                    .onFailure { logger.warn(TAG, "Extension search failed: ${it.message}", it) }
            }
        }.onFailure {
            logger.warn(TAG, "Extension search failed: ${it.message}", it)
        }

        return Result.success(shelves)
    }

    suspend fun homeFeed(): Result<List<Shelf>> =
        runtime.withActiveClient { client ->
            (client as? HomeFeedClient)?.loadHomeFeed()
        }.mapCatching { feed ->
            feed?.loadAll() ?: emptyList()
        }.recoverCatching { emptyList() }

    private companion object {
        const val TAG = "Search"
    }
}
