package dev.brahmkshatriya.echo.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.player.di.AppGraph
import dev.brahmkshatriya.echo.player.domain.playlists.*

/** Built-ins and validated, user-defined JSON expressions share the existing playlist store. */
@Composable
fun SmartPlaylistsScreen(graph: AppGraph, onOpenPlayer: () -> Unit) {
    val custom by graph.playlists.smartPlaylists.collectAsState()
    val tracks by graph.library.tracks.collectAsState()
    val history by graph.history.stats.collectAsState()
    val favorites by graph.favorites.favorites.collectAsState()
    val downloads by graph.downloads.downloads.collectAsState()
    val catalog = remember(tracks, history, favorites, downloads) { graph.catalogSnapshot() }
    var editing by remember { mutableStateOf(false) }
    var ruleJson by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val json = remember { kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true } }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = {
            ruleJson = json.encodeToString(SmartPlaylist.serializer(), SmartPlaylist(
                "custom-${dev.brahmkshatriya.echo.player.domain.nowEpochMs()}", "My smart playlist",
                PlaylistRule.Flag(FlagField.FAVORITE)))
            editing = true
            error = null
        }) { Text("Create smart playlist") }
        LazyColumn {
            items(BuiltinSmartPlaylists.all + custom, key = { it.id }) { playlist ->
                val matches = remember(playlist, catalog) { graph.smartPlaylistEngine.evaluate(playlist, catalog) }
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    supportingContent = { Text("${matches.size} matching songs") },
                    modifier = Modifier.clickable(enabled = matches.isNotEmpty()) {
                        graph.playRefs(matches.map { it.ref })
                        onOpenPlayer()
                    },
                    trailingContent = {
                        if (custom.any { it.id == playlist.id }) TextButton(onClick = {
                            ruleJson = json.encodeToString(SmartPlaylist.serializer(), playlist)
                            editing = true
                            error = null
                        }) { Text("Edit rules") }
                    }
                )
            }
        }
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Smart playlist rules") },
        text = { Column {
            Text("Rules are local. Combine all, any, not, number, text and flag expressions.")
            OutlinedTextField(ruleJson, { ruleJson = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                isError = error != null, label = { Text("Playlist JSON") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching {
                require(ruleJson.length <= 32_768) { "Rules are too large" }
                val playlist = json.decodeFromString(SmartPlaylist.serializer(), ruleJson)
                require(BuiltinSmartPlaylists.all.none { it.id == playlist.id }) { "Choose a custom playlist id" }
                graph.playlists.saveSmart(playlist)
            }.onSuccess { editing = false }.onFailure { error = it.message ?: "Invalid rules" }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
    )
}
