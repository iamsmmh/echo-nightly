package dev.brahmkshatriya.echo.wear

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wire protocol between the phone and [dev.brahmkshatriya.echo.wear.EchoWearListenerService]
 * / the Wear OS `wearApp` module (Phase 7 - ecosystem).
 *
 *  - watch -> phone, path [PATH_COMMAND]: one of [Command]'s names.
 *  - phone -> watch, path [PATH_STATE] : `title\nartist\n{0|1}`.
 */
object WearProtocol {
    const val PATH_COMMAND = "/echo/command"
    const val PATH_STATE = "/echo/state"

    enum class Command { PlayPause, Next, Previous, RequestState }

    data class State(val title: String, val artist: String, val playing: Boolean) {
        fun encode(): String = "$title\n$artist\n${if (playing) 1 else 0}"
        companion object {
            fun decode(raw: String): State {
                val parts = raw.split('\n')
                return State(
                    title = parts.getOrElse(0) { "" },
                    artist = parts.getOrElse(1) { "" },
                    playing = parts.getOrNull(2) == "1"
                )
            }
        }
    }
}

/**
 * Pushes playback state to connected Wear OS watches and lets the watch drive
 * transport controls. State pushes are debounced so a seek storm doesn't spam
 * the message bus.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WearBridge(
    private val context: Context,
    private val player: Player,
    private val scope: CoroutineScope
) {

    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    @Volatile
    private var watchNodeId: String? = null
    private var debounceJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED
                )
            ) schedulePush()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = schedulePush()
    }

    fun start() {
        active = this
        player.addListener(listener)
    }

    fun stop() {
        if (active === this) active = null
        player.removeListener(listener)
        debounceJob?.cancel()
    }

    /** Called when the watch (re)requests sync after connecting. */
    fun onWatchConnected(nodeId: String) {
        watchNodeId = nodeId
        pushState()
    }

    fun handleCommand(command: WearProtocol.Command) {
        when (command) {
            WearProtocol.Command.PlayPause ->
                if (player.isPlaying) player.pause() else player.playWhenReady = true

            WearProtocol.Command.Next -> player.seekToNext()
            WearProtocol.Command.Previous ->
                if (player.currentPosition > 3_000) player.seekTo(0) else player.seekToPrevious()

            WearProtocol.Command.RequestState -> Unit // handled via pushState below
        }
        pushState()
    }

    private fun schedulePush() {
        debounceJob?.cancel()
        debounceJob = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            delay(PUSH_DEBOUNCE_MS)
            pushState()
        }
    }

    private fun pushState() {
        val node = watchNodeId ?: return
        val metadata = player.mediaMetadata
        val state = WearProtocol.State(
            title = metadata.title?.toString()
                ?: player.currentMediaItem?.let { runCatching { it.track.title }.getOrNull() }
                ?: "",
            artist = metadata.artist?.toString().orEmpty(),
            playing = player.isPlaying
        )
        scope.launch {
            runCatching {
                messageClient.sendMessage(
                    node, WearProtocol.PATH_STATE, state.encode().toByteArray()
                )
            }
        }
    }

    companion object {
        private const val PUSH_DEBOUNCE_MS = 400L
        @Volatile
        var active: WearBridge? = null
            private set
    }
}
