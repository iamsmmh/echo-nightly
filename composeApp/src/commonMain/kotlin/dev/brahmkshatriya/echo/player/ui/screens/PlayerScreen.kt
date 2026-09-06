package dev.brahmkshatriya.echo.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.audio.PlaybackState
import dev.brahmkshatriya.echo.player.audio.RepeatMode
import dev.brahmkshatriya.echo.player.di.AppGraph
import dev.brahmkshatriya.echo.player.ui.ArtworkImage

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * Full screen Now Playing: artwork, metadata, seek bar, transport controls,
 * shuffle/repeat, playback speed and queue/download actions.
 * Portrait stacks vertically; landscape puts artwork beside the controls.
 */
@Composable
fun PlayerScreen(
    graph: AppGraph,
    onDismiss: () -> Unit,
    onOpenQueue: () -> Unit
) {
    val playback by graph.player.state.collectAsState()
    val downloads by graph.downloads.downloads.collectAsState()
    // CMP has no LocalConfiguration; BoxWithConstraints gives us the window
    var landscape by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.foundation.layout.BoxWithConstraints {
        landscape = maxWidth > maxHeight
    }

    val current = playback.current ?: run {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }

    val background = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.background
        )
    )

    Box(
        modifier = Modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.Center
    ) {
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkBlock(graph, playback, Modifier.weight(1f))
                Column(
                    modifier = Modifier.weight(1f).padding(start = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TrackInfo(playback)
                    SeekBar(
                        playback = playback,
                        dragPosition = dragPosition,
                        onDrag = { dragPosition = it },
                        onSeek = { fraction ->
                            if (playback.durationMs > 0) {
                                graph.player.seekTo((fraction * playback.durationMs).toLong())
                            }
                        }
                    )
                    TransportControls(graph, playback, onOpenQueue, speedMenu = { speedMenu = true })
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player")
                    }
                    Text("Now Playing", style = MaterialTheme.typography.titleSmall)
                    QueueDownloadActions(graph, current.track, onOpenQueue)
                }
                Spacer(Modifier.height(16.dp))
                ArtworkBlock(graph, playback, Modifier.fillMaxWidth(0.85f))
                Spacer(Modifier.height(24.dp))
                TrackInfo(playback)
                SeekBar(playback, dragPosition, onDrag = { dragPosition = it }, onSeek = { graph.player.seekTo(it.toLong()) })
                TransportControls(graph, playback, onOpenQueue, speedMenu = { speedMenu = true })
                Spacer(Modifier.height(24.dp))
            }
        }

        if (speedMenu) {
            AlertDialog(
                onDismissRequest = { speedMenu = false },
                title = { Text("Playback speed") },
                text = {
                    Column {
                        PLAYBACK_SPEEDS.forEach { speed ->
                            TextButton(
                                onClick = {
                                    graph.player.setPlaybackSpeed(speed)
                                    speedMenu = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (speed == 1.0f) "Normal" else "${speed}x",
                                    color = if (playback.playbackSpeed == speed)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { speedMenu = false }) { Text("Close") }
                }
            )
        }
    }
}

@Composable
private fun ArtworkBlock(graph: AppGraph, playback: PlaybackState, modifier: Modifier) {
    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        ArtworkImage(
            holder = playback.current?.track?.cover,
            contentDescription = "Album artwork",
            size = 320.dp,
            modifier = Modifier.fillMaxSize(),
            loader = graph.artworkLoader
        )
        if (playback.isResolving) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun TrackInfo(playback: PlaybackState) {
    val current = playback.current ?: return
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = current.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2
        )
        Text(
            text = current.authors,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        current.track.album?.title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SeekBar(
    playback: PlaybackState,
    dragPosition: Float?,
    onDrag: (Float?) -> Unit,
    onSeek: (Float) -> Unit
) {
    val duration = playback.durationMs
    val position = playback.positionMs
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = dragPosition ?: playback.progress,
            onValueChange = { onDrag(it) },
            onValueChangeFinished = {
                dragPosition?.let(onSeek)
                onDrag(null)
            },
            enabled = duration > 0,
            modifier = Modifier.semantics { contentDescription = "Seek bar" }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatMs(position), style = MaterialTheme.typography.labelSmall)
            Text(
                text = formatMs(if (duration > 0) duration else 0),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun TransportControls(
    graph: AppGraph,
    playback: PlaybackState,
    onOpenQueue: () -> Unit,
    speedMenu: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { graph.player.toggleShuffle() },
                modifier = Modifier.semantics { contentDescription = "Shuffle" }
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = null,
                    tint = if (playback.shuffleEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { graph.player.previous() },
                modifier = Modifier.semantics { contentDescription = "Previous track" }
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null)
            }
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.material3.FilledIconButton(
                    onClick = { graph.player.playPause() },
                    modifier = Modifier.size(72.dp).semantics {
                        contentDescription = if (playback.isPlaying) "Pause" else "Play"
                    }
                ) {
                    if (playback.isResolving || playback.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
            IconButton(
                onClick = { graph.player.next() },
                modifier = Modifier.semantics { contentDescription = "Next track" }
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null)
            }
            IconButton(
                onClick = { graph.player.cycleRepeat() },
                modifier = Modifier.semantics { contentDescription = "Repeat mode" }
            ) {
                Icon(
                    imageVector = if (playback.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne
                    else Icons.Filled.Repeat,
                    contentDescription = null,
                    tint = if (playback.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = speedMenu, modifier = Modifier.semantics { contentDescription = "Playback speed" }) {
                Icon(Icons.Filled.Speed, contentDescription = null)
            }
            IconButton(onClick = onOpenQueue, modifier = Modifier.semantics { contentDescription = "Queue" }) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
            }
        }
    }
}

@Composable
private fun QueueDownloadActions(graph: AppGraph, track: Track, onOpenQueue: () -> Unit) {
    val downloads by graph.downloads.downloads.collectAsState()
    val extensionId = graph.extensions.activeExtensionId
    val key = "$extensionId::${track.id}"
    val status = downloads[key]?.status
    Row {
        IconButton(onClick = onOpenQueue, modifier = Modifier.semantics { contentDescription = "Queue" }) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
        }
        IconButton(
            onClick = {
                if (status == null) graph.downloads.enqueue(track, extensionId ?: return@IconButton)
            },
            modifier = Modifier.semantics {
                contentDescription = if (status != null) "Downloaded" else "Download for offline"
            }
        ) {
            Icon(
                imageVector = if (status?.state == dev.brahmkshatriya.echo.player.download.DownloadState.COMPLETED)
                    Icons.Filled.DownloadDone else Icons.Filled.Download,
                contentDescription = null,
                tint = if (status?.state == dev.brahmkshatriya.echo.player.download.DownloadState.COMPLETED)
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return buildString {
        if (hours > 0) {
            append(hours)
            append(':')
            append(twoDigits(minutes))
        } else {
            append(minutes)
        }
        append(':')
        append(twoDigits(seconds))
    }
}

internal fun twoDigits(value: Long): String = if (value < 10) "0$value" else value.toString()
