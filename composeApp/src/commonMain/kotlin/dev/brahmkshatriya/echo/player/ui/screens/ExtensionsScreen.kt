package dev.brahmkshatriya.echo.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.player.di.AppGraph
import kotlinx.coroutines.launch

/**
 * Extensions screen: select/enable built-in extensions and configure the
 * Subsonic server (tested with a real ping on save).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(graph: AppGraph, onBack: (() -> Unit)? = null) {
    val extensions by graph.extensions.extensions.collectAsState()
    var settings by remember { mutableStateOf(graph.settings.settings) }
    var pingResult by remember { mutableStateOf<String?>(null) }
    var pinging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        graph.settings.addListener { settings = it }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text("Extensions") },
            navigationIcon = {
                if (onBack != null) {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            }
        )

        Text(
            "Music sources",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        extensions.forEach { registered ->
            val extension = registered.extension
            val isActive = graph.extensions.activeExtensionId == extension.id
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(extension.name, style = MaterialTheme.typography.titleSmall)
                        if (isActive) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Active extension",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                    Text(
                        extension.metadata.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = registered.enabled,
                    onCheckedChange = { graph.extensions.setEnabled(extension.id, it) },
                    modifier = Modifier.semantics { contentDescription = "Enable ${extension.name}" }
                )
                TextButton(
                    onClick = { graph.extensions.setActiveExtension(extension.id) },
                    enabled = registered.enabled && !isActive
                ) { Text(if (isActive) "Active" else "Use") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Subsonic / OpenSubsonic server",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        var serverUrl by remember(settings.subsonicServerUrl) { mutableStateOf(settings.subsonicServerUrl) }
        var username by remember(settings.subsonicUsername) { mutableStateOf(settings.subsonicUsername) }
        var password by remember(settings.subsonicPassword) { mutableStateOf(settings.subsonicPassword) }

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL (https://music.example.com)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    scope.launch {
                        pinging = true
                        pingResult = null
                        graph.settings.update {
                            it.copy(
                                subsonicServerUrl = serverUrl.trim(),
                                subsonicUsername = username.trim(),
                                subsonicPassword = password
                            )
                        }
                        graph.refreshSubsonicConfig()
                        pingResult = runCatching { "Connected (server v${graph.subsonicApi.ping()})" }
                            .getOrElse { "Connection failed: ${it.message}" }
                        pinging = false
                    }
                },
                enabled = serverUrl.isNotBlank() && username.isNotBlank() && !pinging
            ) {
                Icon(Icons.Filled.Cloud, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("Test & Save")
            }
            if (pinging) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp))
            }
        }
        pingResult?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 16.dp),
                color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        Text(
            "Echo is a client for your own sources — no music is bundled. Your server credentials stay on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}
