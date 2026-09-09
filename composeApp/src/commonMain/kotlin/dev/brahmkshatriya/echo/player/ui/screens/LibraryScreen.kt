package dev.brahmkshatriya.echo.player.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.player.di.AppGraph
import dev.brahmkshatriya.echo.player.download.DownloadState
import dev.brahmkshatriya.echo.player.extensions.local.LocalExtensionClient
import dev.brahmkshatriya.echo.player.library.AlbumGroup
import dev.brahmkshatriya.echo.player.library.ArtistGroup
import dev.brahmkshatriya.echo.player.ui.ArtworkImage
import dev.brahmkshatriya.echo.player.ui.FileImports
import kotlinx.coroutines.flow.first

private val TABS = listOf("Songs", "Albums", "Artists", "Playlists", "Downloads", "Smart")

/** Library screen: local songs, albums, artists, playlists and downloads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(graph: AppGraph, onOpenPlayer: () -> Unit) {
    var tab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title) }
                )
            }
        }
        when (tab) {
            0 -> SongsTab(graph, onOpenPlayer)
            1 -> AlbumsTab(graph)
            2 -> ArtistsTab(graph)
            3 -> PlaylistsTab(graph, onOpenPlayer)
            4 -> DownloadsTab(graph)
            5 -> SmartPlaylistsScreen(graph, onOpenPlayer)
        }
    }
}

@Composable
private fun SongsTab(graph: AppGraph, onOpenPlayer: () -> Unit) {
    val tracks by graph.library.tracks.collectAsState()
    if (tracks.isEmpty()) {
        EmptyLibraryHint()
        return
    }
    val asTracks = remember(tracks) { tracks.map { graph.library.asTrack(it) } }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${tracks.size} songs", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                graph.player.playQueue(asTracks, LocalExtensionClient.ID, null, shuffle = false)
                onOpenPlayer()
            }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play all")
            }
            IconButton(onClick = {
                graph.player.playQueue(asTracks, LocalExtensionClient.ID, null, shuffle = true)
                onOpenPlayer()
            }) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle all")
            }
        }
        LazyColumn {
            items(asTracks, key = { it.id }) { track ->
                TrackRow(graph, track, asTracks, onOpenPlayer)
            }
        }
    }
}

@Composable
private fun EmptyLibraryHint() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "No local music yet.\nUse the folder icon to import audio files from your device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AlbumsTab(graph: AppGraph) {
    val tracks by graph.library.tracks.collectAsState()
    val albums = remember(tracks) { groupAlbumsPublic(graph, tracks) }
    if (albums.isEmpty()) {
        EmptyLibraryHint()
        return
    }
    LazyColumn {
        items(albums, key = { "${it.artist}::${it.title}" }) { album ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { graph.player.playQueue(graph.library.albumTracks(album), LocalExtensionClient.ID, null) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkImage(
                    holder = album.tracks.firstOrNull()?.artworkPath?.let { path ->
                        dev.brahmkshatriya.echo.common.models.ImageHolder.ResourceUriImageHolder(
                            "file://" + path.replace(" ", "%20"), crop = true
                        )
                    },
                    contentDescription = null,
                    size = 56.dp,
                    loader = graph.artworkLoader
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${album.artist} • ${album.tracks.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(onClick = {
                    val albumTracks = graph.library.albumTracks(album)
                    graph.player.playQueue(albumTracks, LocalExtensionClient.ID, null)
                }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play album")
                }
            }
        }
    }
}

private fun groupAlbumsPublic(graph: AppGraph, tracks: List<dev.brahmkshatriya.echo.player.library.LocalTrackEntry>): List<AlbumGroup> =
    dev.brahmkshatriya.echo.player.library.groupAlbums(tracks)

@Composable
private fun ArtistsTab(graph: AppGraph) {
    val tracks by graph.library.tracks.collectAsState()
    val artists = remember(tracks) { dev.brahmkshatriya.echo.player.library.groupArtists(tracks) }
    if (artists.isEmpty()) {
        EmptyLibraryHint()
        return
    }
    LazyColumn {
        items(artists, key = { it.name }) { artist: ArtistGroup ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(artist.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${artist.tracks.size} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    val tracksForArtist = artist.tracks.map { graph.library.asTrack(it) }
                    graph.player.playQueue(tracksForArtist, LocalExtensionClient.ID, null)
                }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play artist")
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(graph: AppGraph, onOpenPlayer: () -> Unit) {
    val playlists by graph.playlists.playlists.collectAsState()
    var createDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var openPlaylist by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            TextButton(onClick = { createDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("New playlist")
            }
        }
        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No playlists yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn {
            items(playlists, key = { it.id }) { playlist ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { openPlaylist = playlist.id }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${playlist.tracks.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Playlist options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    graph.playlists.delete(playlist.id)
                                    menu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (createDialog) {
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) graph.playlists.create(newName)
                        newName = ""
                        createDialog = false
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { createDialog = false }) { Text("Cancel") }
            }
        )
    }

    openPlaylist?.let { id ->
        val playlist = graph.playlists.get(id)
        if (playlist == null) {
            openPlaylist = null
            return
        }
        AlertDialog(
            onDismissRequest = { openPlaylist = null },
            title = { Text(playlist.name) },
            text = {
                Column {
                    if (playlist.tracks.isEmpty()) Text("This playlist is empty.")
                    playlist.tracks.forEach { ref -> Text(ref.title, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    graph.playRefs(playlist.tracks)
                    if (playlist.tracks.isNotEmpty()) onOpenPlayer()
                    openPlaylist = null
                }) { Text("Play") }
            },
            dismissButton = {
                TextButton(onClick = { openPlaylist = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun DownloadsTab(graph: AppGraph) {
    val downloads by graph.downloads.downloads.collectAsState()
    val list = downloads.values.sortedByDescending { it.entry.createdAtMs }
    if (list.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No downloads yet. Use the download button on a track to keep it offline.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn {
        items(list, key = { it.entry.id }) { download ->
            val status = download.status
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (status.state == DownloadState.COMPLETED) Icons.Filled.DownloadDone
                    else Icons.Filled.Download,
                    contentDescription = null,
                    tint = when (status.state) {
                        DownloadState.COMPLETED -> MaterialTheme.colorScheme.primary
                        DownloadState.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(download.entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        when (status.state) {
                            DownloadState.COMPLETED -> "Downloaded"
                            DownloadState.DOWNLOADING ->
                                if (status.bytesTotal > 0)
                                    "Downloading ${(status.bytesDownloaded * 100 / status.bytesTotal)}%"
                                else "Downloading…"
                            DownloadState.PAUSED -> "Paused"
                            DownloadState.QUEUED -> "Queued"
                            DownloadState.FAILED -> "Failed: ${status.error ?: ""}"
                            DownloadState.CANCELLED -> "Cancelled"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (status.state == DownloadState.DOWNLOADING && status.bytesTotal > 0) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (status.bytesDownloaded.toFloat() / status.bytesTotal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                }
                when (status.state) {
                    DownloadState.PAUSED, DownloadState.FAILED -> TextButton(onClick = {
                        graph.downloads.resume(download.entry.id)
                    }) { Text("Resume") }
                    DownloadState.DOWNLOADING, DownloadState.QUEUED -> TextButton(onClick = {
                        graph.downloads.pause(download.entry.id)
                    }) { Text("Pause") }
                    else -> {}
                }
                TextButton(onClick = { graph.downloads.cancel(download.entry.id) }) { Text("Remove") }
            }
        }
    }
}
