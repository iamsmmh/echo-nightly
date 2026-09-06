package dev.brahmkshatriya.echo.playback.cast

import android.content.Context
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.player.domain.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Transfers queues only after every source is resolved; the MediaSession retains one player. */
@UnstableApi
class ChromecastBridge(
    private val context: Context,
    private val router: CastRoutingPlayer,
    private val app: App,
    private val scope: CoroutineScope,
    private val resolve: suspend (MediaItem) -> MediaItem,
    private val restoreQueue: suspend () -> List<MediaItem>
) {
    private var castPlayer: CastPlayer? = null
    private var transfer: Job? = null
    private var started = false
    private var attaching = false
    private var converter = EchoCastMediaItemConverter()
    private var lastPosition = 0L
    private var lastIndex = 0
    private var remoteItems = emptyList<MediaItem>()

    private val remoteListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!router.remoteActive || player.currentMediaItem == null) return
            lastPosition = player.currentPosition.coerceAtLeast(0)
            lastIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            if (items.isNotEmpty()) remoteItems = items
        }
    }
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session, false)
        override fun onSessionStartFailed(session: CastSession, error: Int) = detach()
        override fun onSessionEnding(session: CastSession) { snapshotRemote() }
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session, true)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()
        // Keep the local decoder paused during transient connectivity loss.
        override fun onSessionSuspended(session: CastSession, reason: Int) { snapshotRemote() }
    }

    fun start() {
        if (started || !app.settings.getBoolean(ENABLE_CAST, true)) return
        runCatching {
            val manager = CastContext.getSharedInstance(context).sessionManager
            manager.addSessionManagerListener(sessionListener, CastSession::class.java)
            started = true
            manager.currentCastSession?.let { attach(it, true) }
        }.onFailure { warn("Cast services unavailable") }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { CastContext.getSharedInstance(context).sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java) }
        detach()
    }

    private fun attach(session: CastSession, resumed: Boolean) {
        if (router.remoteActive || attaching) return
        attaching = true
        transfer?.cancel()
        transfer = scope.launch(Dispatchers.Main) {
            try { runCatchingCancellable {
                val local = router.local
                val snapshot = (0 until local.mediaItemCount).map { local.getMediaItemAt(it) }
                val saved = if (snapshot.isEmpty() && resumed) restoreQueue() else snapshot
                converter = EchoCastMediaItemConverter().also { it.remember(saved) }
                val status = session.remoteMediaClient?.mediaStatus
                val existingId = status?.mediaInfo?.contentId
                val canResume = resumed && existingId != null && converter.knows(existingId) &&
                    status?.mediaInfo?.customData?.optInt("echoCastVersion") == 1
                if (resumed && existingId != null && !canResume) return@runCatchingCancellable
                val resolved = if (canResume) emptyList() else withTimeoutOrNull(30_000) { saved.map { resolve(it) } }
                    ?: throw IllegalStateException("Cast source resolution timed out")
                if (!started || session != CastContext.getSharedInstance(context).sessionManager.currentCastSession) return@runCatchingCancellable
                // A user queue change while sources were resolving cancels the handoff.
                if (!canResume && snapshot.map { it.mediaId } != (0 until local.mediaItemCount).map { local.getMediaItemAt(it).mediaId }) return@runCatchingCancellable
                if (!canResume && resolved.isEmpty()) return@runCatchingCancellable
                if (canResume && status?.queueItems?.any { !converter.knows(it.media?.contentId.orEmpty()) } == true) return@runCatchingCancellable
                val cast = CastPlayer(CastContext.getSharedInstance(context), converter)
                castPlayer = cast
                cast.addListener(remoteListener)
                val wantedPlayback = local.playWhenReady
                val index = local.currentMediaItemIndex.coerceAtLeast(0)
                val position = local.currentPosition.coerceAtLeast(0)
                if (!canResume) {
                    cast.setMediaItems(resolved, index.coerceIn(resolved.indices), position)
                    cast.repeatMode = local.repeatMode
                    cast.prepare()
                }
                local.pause()
                router.routeTo(cast)
                if (!canResume) cast.playWhenReady = wantedPlayback
                snapshotRemote()
            }.onFailure {
                if (castPlayer != null) detach()
                warn("Queue could not be transferred; playback remains local")
            } } finally { attaching = false }
        }
    }

    private fun snapshotRemote() {
        val cast = castPlayer ?: return
        if (!router.remoteActive || cast.currentMediaItem == null) return
        lastPosition = cast.currentPosition.coerceAtLeast(0)
        lastIndex = cast.currentMediaItemIndex.coerceAtLeast(0)
        val items = (0 until cast.mediaItemCount).map { cast.getMediaItemAt(it) }
        if (items.isNotEmpty()) remoteItems = items
    }

    private fun detach() {
        transfer?.cancel()
        transfer = null
        attaching = false
        val cast = castPlayer
        castPlayer = null
        val local = router.local
        if (router.remoteActive) {
            val restored = remoteItems.mapNotNull(converter::localItem)
            if (restored.isNotEmpty() && restored.size == remoteItems.size) {
                local.setMediaItems(restored, lastIndex.coerceIn(restored.indices), lastPosition)
                local.prepare()
            }
            local.pause() // Never unexpectedly resume through the phone speaker.
            router.routeTo(local)
        }
        remoteItems = emptyList()
        cast?.removeListener(remoteListener)
        runCatching { cast?.release() }
    }

    private fun warn(message: String) {
        Log.w("EchoCast", message)
        scope.launch { app.messageFlow.emit(dev.brahmkshatriya.echo.common.models.Message(message)) }
    }
    companion object { const val ENABLE_CAST = "enable_cast" }
}
