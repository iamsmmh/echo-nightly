package dev.brahmkshatriya.echo.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import dev.brahmkshatriya.echo.common.models.Streamable
import android.util.Log
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.playback.MediaItemUtils.serverIndex
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Chromecast mirroring (Phase 7 - ecosystem).
 *
 * One-directional by design: while a cast session is active and Echo started
 * the cast, transport controls on the phone drive the receiver. The phone
 * mutes itself (pauses the local player) so there is never double audio.
 * Only direct progressive HTTP sources can be cast; DRM/raw-stream tracks
 * keep playing locally and are simply not mirrored.
 *
 * Requires the Cast framework initialized via [EchoCastOptionsProvider].
 */
@OptIn(UnstableApi::class)
class ChromecastBridge(
    private val context: Context,
    private val player: Player,
    private val app: App,
    private val scope: CoroutineScope
) {

    private var castPlayer: CastPlayer? = null
    private var castSession: CastSession? = null
    private var mirroring = false
    private var lastMirroredMediaId: String? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            castSession = session
            attach()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            castSession = session
            attach()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!mirroring) return
            val cast = castPlayer ?: return
            when {
                events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION) -> {
                    lastMirroredMediaId = null
                    mirrorCurrent()
                }

                events.containsAny(Player.EVENT_IS_PLAYING_CHANGED) ->
                    if (player.isPlaying) cast.play() else cast.pause()

                events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_SEEK_COMBINED_WITH_PREVIOUS_MEDIA_ITEM,
                    Player.EVENT_SEEK_COMBINED_WITH_NEXT_MEDIA_ITEM,
                    Player.EVENT_SEEK_COMBINED_WITH_CURRENT_MEDIA_ITEM
                ) -> cast.seekTo(player.currentPosition)
            }
        }
    }

    fun start() {
        if (!app.settings.getBoolean(ENABLE_CAST, true)) return
        runCatching {
            val manager = CastContext.getSharedInstance(context).sessionManager
            manager.addSessionManagerListener(sessionListener, CastSession::class.java)
            (manager.currentCastSession)?.let {
                castSession = it
                attach()
            }
        }
    }

    fun stop() {
        runCatching {
            CastContext.getSharedInstance(context).sessionManager
                .removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
        detach()
    }

    private fun attach() {
        if (mirroring) return
        castPlayer = runCatching { CastPlayer(context) }.getOrNull() ?: return
        mirroring = true
        player.addListener(listener)
        player.pause() // avoid double audio
        mirrorCurrent()
    }

    private fun detach() {
        if (!mirroring) return
        mirroring = false
        castSession = null
        lastMirroredMediaId = null
        runCatching { player.removeListener(listener) }
        runCatching { castPlayer?.release() }
        castPlayer = null
    }

    /** Push the current queue item to the receiver (if it is castable). */
    private fun mirrorCurrent() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (id == lastMirroredMediaId) return
        scope.launch(Dispatchers.IO) {
            val track = runCatching { item.track }.getOrNull()
            if (track == null) {
                warn("cast: item not castable")
                return@launch
            }
            val streamable = runCatching {
                val servers = track.servers
                servers.getOrNull(item.serverIndex) ?: servers.firstOrNull()
            }.getOrNull() ?: return@launch
            val media = runCatching {
                track.extension.loadStreamableMedia(streamable, false)
            }.getOrNull()
            val source = (media as? Streamable.Media.Server)
                ?.sources?.firstOrNull() as? Streamable.Source.Http
            if (source == null || source.isLive ||
                source.type != Streamable.SourceType.Progressive ||
                source.decryption != null
            ) {
                warn("cast: source not castable, keeping playback local")
                return@launch
            }
            val url = source.request.url
            lastMirroredMediaId = id
            val metadata = MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK)
                .apply {
                    putString(MediaMetadata.KEY_TITLE, track.name)
                    putString(MediaMetadata.KEY_ARTIST, track.artists.joinToString { it.name })
                    putString(MediaMetadata.KEY_ALBUM_TITLE, track.album?.name)
                }
            val mediaInfo = MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentTypeFor(url))
                .setMetadata(metadata)
                .setStreamDuration(player.duration.takeIf { it > 0 } ?: MediaInfo.UNKNOWN_DURATION)
                .build()
            runCatching {
                castPlayer?.load(mediaInfo, 0, player.currentPosition)
                if (player.isPlaying) castPlayer?.play()
            }.onFailure { warn("cast: load failed ${it.message}") }
        }
    }

    private fun contentTypeFor(url: String): String = when (url.substringAfterLast('.')
        .lowercase().substringBefore('?')) {
        "m4a", "mp4", "m4b" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        else -> "audio/mpeg"
    }

    private fun warn(message: String) = Log.w(TAG, message)

    companion object {
        const val ENABLE_CAST = "enable_cast"
        private const val TAG = "EchoCast"
    }
}
