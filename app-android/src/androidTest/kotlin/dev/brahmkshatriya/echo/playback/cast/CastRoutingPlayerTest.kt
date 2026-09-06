package dev.brahmkshatriya.echo.playback.cast

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CastRoutingPlayerTest {
    @Test fun routeUsesOneBackendAndRejectsStaleQueueResolution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val resolving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondApplied = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            val local = ExoPlayer.Builder(context).build()
            val remote = ExoPlayer.Builder(context).build()
            var staleWasApplied = false
            val router = CastRoutingPlayer(local, scope) { item ->
                if (item.mediaId == "first") { resolving.complete(Unit); release.await() }
                item.buildUpon().setUri("https://example.invalid/${item.mediaId}.mp3").setMimeType("audio/mpeg").build()
            }
            remote.addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    if (remote.currentMediaItem?.mediaId == "first") staleWasApplied = true
                    if (remote.currentMediaItem?.mediaId == "second") secondApplied.complete(Unit)
                }
            })
            try {
                router.routeTo(remote)
                router.setMediaItem(MediaItem.Builder().setMediaId("first").build())
                withTimeout(5000) { resolving.await() }
                router.setMediaItem(MediaItem.Builder().setMediaId("second").build())
                release.complete(Unit)
                withTimeout(5000) { secondApplied.await() }
                assertFalse(staleWasApplied)
                assertEquals(0, local.mediaItemCount)
                assertEquals("second", remote.currentMediaItem?.mediaId)
                assertTrue(router.remoteActive)
                router.routeTo(local)
                assertFalse(router.remoteActive)
            } finally {
                scope.cancel()
                router.routeTo(local)
                router.release()
                remote.release()
            }
        }
    }
}
