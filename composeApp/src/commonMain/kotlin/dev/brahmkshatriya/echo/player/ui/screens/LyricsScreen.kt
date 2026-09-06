package dev.brahmkshatriya.echo.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.player.core.lyrics.LrcParser
import dev.brahmkshatriya.echo.player.core.lyrics.LyricsTimeline
import dev.brahmkshatriya.echo.player.di.AppGraph
import kotlinx.coroutines.launch

/** Shared synced/unsynced lyrics with seeking, word highlights and accessible font scaling. */
@Composable
fun LyricsScreen(graph: AppGraph, onDismiss: () -> Unit) {
    val playback by graph.player.state.collectAsState()
    val current = playback.current ?: return
    val request = remember(current.trackKey) { graph.lyricsRequest(current) }
    var lyrics by remember(current.trackKey) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(current.trackKey) { mutableStateOf(true) }
    var karaoke by remember { mutableStateOf(true) }
    var follow by remember { mutableStateOf(true) }
    var fontScale by remember { mutableStateOf(1f) }
    var editing by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(request) {
        try {
            dev.brahmkshatriya.echo.player.domain.runCatchingCancellable { graph.lyrics.get(request) }
                .onSuccess { lyrics = it }.onFailure { error = "Could not load lyrics" }
        } finally { loading = false }
    }
    val timeline = remember(lyrics) { lyrics?.lyrics?.let(::LyricsTimeline) }
    val list = rememberLazyListState()
    val activeLine = timeline?.activeLine(playback.positionMs) ?: -1
    LaunchedEffect(activeLine, follow) {
        if (follow && activeLine >= 0 && !list.isScrollInProgress) list.animateScrollToItem(activeLine)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lyrics", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text(current.title, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { karaoke = !karaoke }) { Text(if (karaoke) "Karaoke on" else "Karaoke off") }
                    TextButton(onClick = { follow = !follow }) { Text(if (follow) "Following playback" else "Scroll freely") }
                }
                Text("Text size ${(fontScale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(fontScale, { fontScale = it }, valueRange = 0.75f..2f)
                when {
                    loading -> CircularProgressIndicator()
                    timeline == null -> Text("No lyrics available. You can save your own text or LRC lyrics for offline use.")
                    else -> LazyColumn(Modifier.weight(1f), state = list) {
                        itemsIndexed(timeline.lines) { index, line ->
                            val color = if (index == activeLine && (!karaoke || line.words.size == 1)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            val words = buildAnnotatedString {
                                line.words.forEachIndexed { wordIndex, word ->
                                    val active = karaoke && timeline.wordProgress(index, wordIndex, playback.positionMs) > 0f && index == activeLine
                                    withStyle(SpanStyle(color = if (active) MaterialTheme.colorScheme.primary else color)) {
                                        if (wordIndex > 0) append(" ")
                                        append(word.text)
                                    }
                                }
                            }
                            Text(words, fontSize = (22 * fontScale).sp, lineHeight = (32 * fontScale).sp,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = timeline.synced) {
                                    graph.player.seekTo(line.startMs)
                                }.padding(vertical = 12.dp))
                        }
                    }
                }
                TextButton(onClick = { editing = true; error = null }) { Text("Add or replace lyrics") }
            }
        }
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false }, title = { Text("Save lyrics offline") },
        text = { Column {
            OutlinedTextField(pasted, { if (it.length <= 60_000) pasted = it }, label = { Text("Text or LRC") },
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { scope.launch {
            dev.brahmkshatriya.echo.player.domain.runCatchingCancellable {
                require(pasted.isNotBlank()) { "Enter lyrics first" }
                val saved = Lyrics("user:${current.trackKey}", current.title, lyrics = LrcParser.parse(pasted, current.track.duration))
                graph.lyrics.save(request, saved)
                lyrics = saved
                editing = false
            }.onFailure { error = it.message ?: "Could not save lyrics" }
        } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
    )
}
