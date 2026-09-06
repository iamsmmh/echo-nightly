package dev.brahmkshatriya.echo.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import dev.brahmkshatriya.echo.player.di.AppGraph
import dev.brahmkshatriya.echo.player.ui.screens.ExtensionsScreen
import dev.brahmkshatriya.echo.player.ui.screens.HomeScreen
import dev.brahmkshatriya.echo.player.ui.screens.LibraryScreen
import dev.brahmkshatriya.echo.player.ui.screens.PlayerScreen
import dev.brahmkshatriya.echo.player.ui.screens.QueueScreen
import dev.brahmkshatriya.echo.player.ui.screens.SearchScreen
import dev.brahmkshatriya.echo.player.ui.screens.SettingsScreen

/** Top level destinations of the app. */
enum class Dest(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("Library"),
    EXTENSIONS("Extensions"),
    SETTINGS("Settings")
}

/**
 * Root composable of the shared Compose Multiplatform UI. Uses a bottom
 * navigation bar on compact screens (phones in portrait) and a navigation rail
 * on expanded screens (iPad, landscape phones).
 */
@Composable
fun EchoApp(graph: AppGraph) {
    val useDark = graph.settings.settings.useDarkTheme
    var themeOverride by remember { mutableStateOf(useDark) }
    LaunchedEffect(Unit) {
        graph.settings.addListener { themeOverride = it.useDarkTheme }
    }

    EchoTheme(useDarkThemeOverride = themeOverride) {
        val playback by graph.player.state.collectAsState()
        var dest by remember { mutableStateOf(Dest.HOME) }
        var showPlayer by remember { mutableStateOf(false) }
        var showQueue by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            graph.player.messages.collect { snackbar.showSnackbar(it) }
        }
        LaunchedEffect(Unit) {
            dev.brahmkshatriya.echo.player.ui.FileImports.requestOpen.collect {
                dev.brahmkshatriya.echo.player.ui.platformOpenFilePicker()
            }
        }
        LaunchedEffect(Unit) {
            dev.brahmkshatriya.echo.player.ui.FileImports.pickedPaths.collect { paths ->
                val imported = dev.brahmkshatriya.echo.player.ui.FileImports
                    .importPickedFiles(graph.library, graph.logger, paths)
                snackbar.showSnackbar(
                    if (imported == 0) "No files imported"
                    else "Imported $imported file" + if (imported > 1) "s" else ""
                )
            }
        }
        LaunchedEffect(Unit) {
            graph.player.restoreSession()
        }

        val configuration = LocalConfiguration.current
        val compact = configuration.screenWidthDp < 600

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (compact) {
                    Column {
                        MiniPlayer(
                            graph = graph,
                            playback = playback,
                            onClick = { showPlayer = true },
                            onQueue = { showQueue = true }
                        )
                        BottomBar(dest) { dest = it }
                    }
                }
            }
        ) { padding ->
            if (compact) {
                Content(graph, dest, padding, onOpenPlayer = { showPlayer = true })
            } else {
                Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Rail(dest) { dest = it }
                    Box(modifier = Modifier.weight(1f)) {
                        Content(graph, dest, androidx.compose.foundation.layout.PaddingValues(0.dp), onOpenPlayer = { showPlayer = true })
                    }
                }
            }
        }

        if (showPlayer) {
            PlayerScreen(
                graph = graph,
                onDismiss = { showPlayer = false },
                onOpenQueue = { showQueue = true }
            )
        }
        if (showQueue) {
            QueueScreen(graph = graph, onDismiss = { showQueue = false })
        }
    }
}

@Composable
private fun Content(
    graph: AppGraph,
    dest: Dest,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onOpenPlayer: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        when (dest) {
            Dest.HOME -> HomeScreen(graph)
            Dest.SEARCH -> SearchScreen(graph, onOpenPlayer)
            Dest.LIBRARY -> LibraryScreen(graph, onOpenPlayer)
            Dest.EXTENSIONS -> ExtensionsScreen(graph)
            Dest.SETTINGS -> SettingsScreen(graph)
        }
    }
}

@Composable
private fun BottomBar(current: Dest, onSelect: (Dest) -> Unit) {
    NavigationBar {
        Dest.entries.forEach { destination ->
            NavigationBarItem(
                selected = current == destination,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            Dest.HOME -> Icons.Filled.Home
                            Dest.SEARCH -> Icons.Filled.Search
                            Dest.LIBRARY -> Icons.Filled.LibraryMusic
                            Dest.EXTENSIONS -> Icons.Filled.Extension
                            Dest.SETTINGS -> Icons.Filled.Settings
                        },
                        contentDescription = destination.label
                    )
                },
                label = { Text(destination.label) }
            )
        }
    }
}

@Composable
private fun Rail(current: Dest, onSelect: (Dest) -> Unit) {
    NavigationRail {
        Dest.entries.forEach { destination ->
            NavigationRailItem(
                selected = current == destination,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            Dest.HOME -> Icons.Filled.Home
                            Dest.SEARCH -> Icons.Filled.Search
                            Dest.LIBRARY -> Icons.Filled.LibraryMusic
                            Dest.EXTENSIONS -> Icons.Filled.Extension
                            Dest.SETTINGS -> Icons.Filled.Settings
                        },
                        contentDescription = destination.label
                    )
                },
                label = { Text(destination.label) }
            )
        }
    }
}

/** Compact player bar shown above the navigation bar. */
@Composable
fun MiniPlayer(
    graph: AppGraph,
    playback: dev.brahmkshatriya.echo.player.audio.PlaybackState,
    onClick: () -> Unit,
    onQueue: () -> Unit
) {
    val current = playback.current ?: return
    AnimatedVisibility(visible = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkImage(
                holder = current.track.cover,
                contentDescription = "Album artwork",
                size = 44.dp,
                loader = remember { graph.artworkLoader }
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    text = current.authors,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (playback.isResolving || playback.isBuffering) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { graph.player.playPause() }) {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play"
                    )
                }
            }
        }
    }
}
