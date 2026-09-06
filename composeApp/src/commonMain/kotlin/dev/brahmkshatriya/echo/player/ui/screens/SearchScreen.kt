package dev.brahmkshatriya.echo.player.ui.screens
import dev.brahmkshatriya.echo.player.ui.ArtworkImage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.di.AppGraph
import kotlinx.coroutines.delay

/** Search across the active extension and the imported local library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(graph: AppGraph, onOpenPlayer: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var shelves by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            shelves = emptyList()
            return@LaunchedEffect
        }
        loading = true
        delay(250) // debounce
        graph.search.search(query)
            .onSuccess { shelves = it }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Search") })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Songs, albums, artists…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        Spacer(Modifier.height(8.dp))
        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (shelves.isEmpty() && query.isNotBlank()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No results for \"$query\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            shelves.forEach { shelf ->
                when (shelf) {
                    is Shelf.Lists<*> -> {
                        item(key = "header-${shelf.id}") {
                            Text(
                                shelf.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            shelf.list,
                            key = { item -> "${shelf.id}::${(item as? EchoMediaItem)?.id ?: item.hashCode()}" }
                        ) { item ->
                            when (item) {
                                is Track -> TrackRow(graph, item, shelf.list.filterIsInstance<Track>(), onOpenPlayer)
                                is Album -> MediaRow(graph, item)
                                is Artist -> MediaRow(graph, item)
                                else -> {}
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun TrackRow(graph: AppGraph, track: Track, playContext: List<Track>, onOpenPlayer: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val extensionId = graph.extensions.activeExtensionId
                    ?: dev.brahmkshatriya.echo.player.extensions.local.LocalExtensionClient.ID
                graph.player.playQueue(playContext, extensionId, track.id)
                onOpenPlayer()
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(holder = track.cover, contentDescription = null, size = 48.dp, loader = graph.artworkLoader)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.subtitleWithOutE ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MediaRow(graph: AppGraph, item: EchoMediaItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { playMediaItem(graph, item) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(holder = item.cover, contentDescription = null, size = 56.dp, loader = graph.artworkLoader)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitleWithOutE ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
