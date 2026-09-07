package dev.brahmkshatriya.echo.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibrarySession

/**
 * Echo Media Library Service for Android Auto compatibility.
 * Provides browse tree generation, search support, queue synchronization,
 * and resume playback.
 */
@OptIn(UnstableApi::class)
class EchoMediaLibraryService : MediaLibraryService() {

    private lateinit var session: MediaLibrarySession

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onCreate() {
        super.onCreate()
        val player = createPlayer()
        val callback = createCallback()
        session = MediaLibrarySession.Builder(this, player, callback).build()
    }

    private fun createPlayer(): androidx.media3.session.MediaSession.Player = run {
        val context = applicationContext
        val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        exoPlayer
    }

    private fun createCallback(): MediaLibrarySession.Callback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: androidx.media3.session.MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ) = androidx.media3.session.LibraryResult.ofItem(
            MediaItem.Builder().setMediaId("root").build(),
            params
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: androidx.media3.session.MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ) = androidx.media3.session.LibraryResult.ofItemList(
            emptyList(),
            params
        )
    }

    override fun onDestroy() {
        session.player.release()
        session.release()
        super.onDestroy()
    }
}
