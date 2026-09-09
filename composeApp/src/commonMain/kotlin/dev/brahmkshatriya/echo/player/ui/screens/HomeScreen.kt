package dev.brahmkshatriya.echo.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import dev.brahmkshatriya.echo.player.domain.recommendations.RecommendationEngine
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.di.AppGraph
import dev.brahmkshatriya.echo.player.ui.ArtworkImage
import kotlinx.coroutines.flow.first

/** Home screen: shelves from the active extension, or onboarding when none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(graph: AppGraph) {
    var shelves by remember { mutableStateOf<List<Shelf>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val localTracks by graph.library.tracks.collectAsState()
    val stats by graph.history.stats.collectAsState()
    val favorites by graph.favorites.favorites.collectAsState()
    val downloads by graph.downloads.downloads.collectAsState()
    val catalog = remember(localTracks, stats, favorites, downloads) { graph.catalogSnapshot() }
    var mood by remember { mutableStateOf(RecommendationEngine.Mood.CALM) }
    val recommended = remember(catalog, mood) { RecommendationEngine.Kind.entries.mapNotNull { kind ->
        val matches = graph.recommendations.recommend(kind, catalog, mood = mood)
        if (matches.isEmpty()) null else kind to matches
    } }
    val preferences by graph.settings.state.collectAsState()
    val registered by graph.extensions.extensions.collectAsState()
    val activeId = remember(preferences.activeExtensionId, registered) { graph.extensions.activeExtensionId }
    LaunchedEffect(activeId) {
        shelves = null
        error = null
        graph.search.homeFeed()
            .onSuccess { shelves = it }
            .onFailure { error = it.message }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            shelves == null && error == null && recommended.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            error != null && recommended.isEmpty() -> HomeMessage(graph, "Could not load the feed.\n$error")
            shelves?.isEmpty() == true && recommended.isEmpty() -> HomeMessage(
                graph,
                if (activeId == dev.brahmkshatriya.echo.player.extensions.local.LocalExtensionClient.ID)
                    "Your library is empty. Import audio files from the Library tab, or connect a Subsonic server in Extensions."
                else "Nothing here yet."
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (error != null) item(key = "feed-error") {
                    Text("Remote feed unavailable. Your local recommendations are still available.", modifier = Modifier.padding(16.dp))
                }
                if (recommended.isNotEmpty()) {
                    item(key = "recommendation-mood") {
                        TextButton(onClick = { mood = RecommendationEngine.Mood.entries[(mood.ordinal + 1) % RecommendationEngine.Mood.entries.size] }) {
                            Text("Mood: ${mood.name.lowercase().replaceFirstChar { it.uppercase() }} · change")
                        }
                    }
                }
                recommended.forEach { (kind, matches) ->
                    item(key = "recommendation-${kind.name}") {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                            matches.take(5).forEach { song ->
                                Text(song.ref.title + " · " + song.ref.artist,
                                    modifier = Modifier.fillMaxWidth().clickable { graph.playRefs(listOf(song.ref)) }.padding(vertical = 10.dp))
                            }
                        }
                    }
                }
                shelves.orEmpty().forEach { shelf ->
                    item(key = shelf.id) {
                        ShelfSection(graph, shelf)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeMessage(graph: AppGraph, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        dev.brahmkshatriya.echo.player.ui.EchoLogo(size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { graph.extensions.setActiveExtension(graph.defaultExtensionId()) }) {
            Text("Use Offline Library")
        }
    }
}

@Composable
fun activeExtensionName(graph: AppGraph): String =
    graph.extensions.activeExtension()?.name ?: "Echo"

/** Renders one shelf: a horizontal list (Linear) or a grid-ish flow (Grid). */
@Composable
fun ShelfSection(graph: AppGraph, shelf: Shelf) {
    when (shelf) {
        is Shelf.Lists<*> -> when (shelf) {
            is Shelf.Lists.Items -> ShelfItems(graph, shelf)
            is Shelf.Lists.Tracks -> ShelfTracks(graph, shelf)
            is Shelf.Lists.Categories -> {}
        }
        else -> {}
    }
}

@Composable
private fun ShelfTracks(graph: AppGraph, shelf: Shelf.Lists.Tracks) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            shelf.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(shelf.list) { track ->
                Column(
                    modifier = Modifier.clickable {
                        val extensionId = graph.extensions.activeExtensionId ?: return@clickable
                        graph.player.playQueue(shelf.list, extensionId, track.id)
                    }.padding(vertical = 4.dp)
                ) {
                    ArtworkImage(
                        holder = track.cover,
                        contentDescription = "${track.title} artwork",
                        size = 110.dp,
                        loader = graph.artworkLoader
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ShelfItems(graph: AppGraph, shelf: Shelf.Lists.Items) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            shelf.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        val grid = shelf.type == Shelf.Lists.Type.Grid
        if (grid) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shelf.list) { item ->
                    ShelfItem(graph, item, wide = true)
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(shelf.list) { item ->
                    ShelfItem(graph, item, wide = false)
                }
            }
        }
    }
}

@Composable
fun ShelfItem(graph: AppGraph, item: EchoMediaItem, wide: Boolean) {
    Column(
        modifier = Modifier
            .then(if (wide) Modifier else Modifier)
            .clickable { playMediaItem(graph, item) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArtworkImage(
            holder = item.cover,
            contentDescription = "${item.title} artwork",
            size = if (wide) 140.dp else 110.dp,
            loader = graph.artworkLoader
        )
        Spacer(Modifier.height(4.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        item.subtitleWithE?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** Selection always uses the provider that produced the result, not the active tab. */
fun playMediaItem(graph: AppGraph, item: EchoMediaItem) {
    val extensionId = graph.providerIdFor(item)
    if (item is Track) {
        graph.player.playQueue(listOf(item), extensionId, item.id)
        return
    }
    graph.scope.launch {
        dev.brahmkshatriya.echo.player.domain.runCatchingCancellable {
            val extension = graph.extensions.extensionFor(extensionId) ?: return@runCatchingCancellable
            val client = extension.instance.value().getOrThrow()
            val tracks = when {
                item is dev.brahmkshatriya.echo.common.models.Album && client is dev.brahmkshatriya.echo.common.clients.AlbumClient -> {
                    val feed = client.loadTracks(client.loadAlbum(item))
                    feed?.let { it.getPagedData(it.notSortTabs.firstOrNull()).pagedData.loadAll() }.orEmpty()
                }
                item is dev.brahmkshatriya.echo.common.models.Artist && client is dev.brahmkshatriya.echo.common.clients.ArtistClient -> {
                    val feed = client.loadFeed(client.loadArtist(item))
                    feed.getPagedData(feed.notSortTabs.firstOrNull()).pagedData.loadPage(null).data.flatMap { shelf ->
                        if (shelf is Shelf.Lists<*>) shelf.list.filterIsInstance<Track>() else emptyList()
                    }
                }
                else -> emptyList()
            }
            graph.player.playQueue(tracks, extensionId, null)
        }.onFailure { graph.logger.warn("Browse", "Could not load selected media", it) }
    }
}
