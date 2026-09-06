package dev.brahmkshatriya.echo.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.player.di.AppGraph
import dev.brahmkshatriya.echo.player.ui.ArtworkImage
import kotlinx.coroutines.launch

/** Bottom sheet queue: jump to item, reorder, remove, reshuffle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(graph: AppGraph, onDismiss: () -> Unit) {
    val playback by graph.player.state.collectAsState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Up Next • ${playback.queue.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        scope.launch { graph.player.queue.reshuffle() }
                    },
                    modifier = Modifier.semantics { contentDescription = "Reshuffle queue" }
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close queue")
                }
            }
            if (playback.queue.isEmpty()) {
                Text(
                    "The queue is empty. Play something!",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn {
                itemsIndexed(playback.queue, key = { _, item -> item.id }) { index, item ->
                    val isCurrent = item.id == playback.currentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { graph.player.jumpTo(item.id) }
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(
                            holder = item.track.cover,
                            contentDescription = null,
                            size = 40.dp,
                            loader = graph.artworkLoader
                        )
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                item.authors,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { graph.player.moveInQueue(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.semantics { contentDescription = "Move up" }
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                        }
                        IconButton(
                            onClick = { graph.player.moveInQueue(index, index + 1) },
                            enabled = index < playback.queue.lastIndex,
                            modifier = Modifier.semantics { contentDescription = "Move down" }
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                        }
                        IconButton(
                            onClick = { graph.player.removeFromQueue(item.id) },
                            modifier = Modifier.semantics { contentDescription = "Remove from queue" }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}
