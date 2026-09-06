package dev.brahmkshatriya.echo.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.player.di.AppGraph
import dev.brahmkshatriya.echo.player.domain.formatMs
import kotlinx.coroutines.delay
import dev.brahmkshatriya.echo.player.platform.audioCapabilities

/** Shared settings screen (platform specific options are added per platform). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph) {
    var settings by remember { mutableStateOf(graph.settings.settings) }
    LaunchedEffect(Unit) {
        graph.settings.addListener { settings = it }
    }
    val capabilities = remember { audioCapabilities() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        TopAppBar(title = { Text("Settings") })

        SettingsSection("Appearance")
        ToggleRow(
            title = "Follow system theme",
            subtitle = "Off uses the dark player theme",
            checked = settings.useDarkTheme == null,
            onChecked = { useSystem ->
                graph.settings.update { it.copy(useDarkTheme = if (useSystem) null else true) }
            }
        )
        ToggleRow(
            title = "Dark theme",
            subtitle = "Force dark colors",
            checked = settings.useDarkTheme == true,
            onChecked = { dark ->
                graph.settings.update { it.copy(useDarkTheme = if (dark) true else false) }
            },
            enabled = settings.useDarkTheme != null
        )

        SettingsSection("Playback")
        ToggleRow(
            title = "Pause when headphones disconnect",
            subtitle = "Stops playback on audio route loss",
            checked = settings.pauseOnHeadphonesDisconnected,
            onChecked = { value ->
                graph.settings.update { it.copy(pauseOnHeadphonesDisconnected = value) }
            }
        )

        SettingsSection("Downloads")
        ToggleRow(
            title = "Download only on unmetered network",
            subtitle = "Wi-Fi only",
            checked = settings.downloadOnlyOnUnmetered,
            onChecked = { value ->
                graph.settings.update { it.copy(downloadOnlyOnUnmetered = value) }
            }
        )
        ToggleRow(
            title = "Transcode downloads to MP3",
            subtitle = "Server-side transcoding (Subsonic)",
            checked = settings.transcodeFormat != "raw",
            onChecked = { value ->
                graph.settings.update { it.copy(transcodeFormat = if (value) "mp3" else "raw") }
            }
        )

        SettingsSection("Audio & Sleep")
        CrossfadeRow(graph, settings.crossfadeMs)
        ToggleRow(
            title = "Normalize volume (track)",
            subtitle = "ReplayGain track mode with limiter",
            checked = settings.replayGainMode == 1,
            onChecked = { on ->
                graph.settings.update { it.copy(replayGainMode = if (on) 1 else 0) }
            }
        )
        ToggleRow(
            title = "Normalize volume (album)",
            subtitle = "ReplayGain album mode (falls back to track)",
            checked = settings.replayGainMode == 2,
            onChecked = { on ->
                graph.settings.update { it.copy(replayGainMode = if (on) 2 else 0) }
            }
        )
        SleepTimerRow(graph, settings.sleepTimerMinutes)

        SettingsSection("Storage")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Music directory", style = MaterialTheme.typography.titleSmall)
                Text(
                    graph.storage.musicDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsSection("About")
        Text(
            "Echo — a Kotlin Multiplatform music player.\n" +
                "Supported by this device: MP3, AAC/M4A, WAV" +
                (if (capabilities.flac) ", FLAC" else "") +
                (if (capabilities.ogg) ", OGG" else "") +
                (if (capabilities.opus) ", Opus" else "") +
                ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(0.dp))
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onChecked(it) },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun CrossfadeRow(graph: AppGraph, crossfadeMs: Long) {
    var seconds by remember(crossfadeMs) { mutableStateOf(crossfadeMs / 1000f) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            if (seconds < 1f) "Crossfade: off" else "Crossfade: ${seconds.toInt()}s",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            "Dips the outgoing track while the next one starts",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = seconds,
            onValueChange = { seconds = it },
            onValueFinished = {
                val millis = (seconds.toInt() * 1000).toLong()
                graph.settings.update { current -> current.copy(crossfadeMs = millis) }
            },
            valueRange = 0f..12f,
            steps = 11,
            modifier = Modifier.semantics { contentDescription = "Crossfade seconds" }
        )
    }
}

@Composable
private fun SleepTimerRow(graph: AppGraph, activeMinutes: Int) {
    val snapshot by graph.sleepTimer.snapshot.collectAsState()
    // Re-tick once per second while active to update the countdown label.
    var nowTick by remember { mutableStateOf(0L) }
    LaunchedEffect(snapshot.isActive) {
        while (snapshot.isActive) {
            nowTick = dev.brahmkshatriya.echo.player.domain.nowEpochMs()
            delay(1_000)
        }
    }
    val remaining = dev.brahmkshatriya.echo.player.audiofx.SleepStateMachine
        .remainingMs(snapshot, nowTick)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            if (snapshot.isActive) "Sleep timer: ${formatMs(remaining)} left"
            else "Sleep timer",
            style = MaterialTheme.typography.titleSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60).forEach { minutes ->
                TextButton(
                    onClick = { graph.startSleepTimer(minutes) },
                    modifier = Modifier.semantics { contentDescription = "Sleep in $minutes minutes" }
                ) { Text("${minutes}m") }
            }
            TextButton(
                onClick = { graph.startSleepTimer(0) },
                modifier = Modifier.semantics { contentDescription = "Disable sleep timer" }
            ) { Text("Off") }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}
