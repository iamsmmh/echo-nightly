package dev.brahmkshatriya.echo.playback.cast

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.utils.CoroutineUtils.future
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One observable session player; no muted local decoder or duplicated transport state. */
@UnstableApi
class CastRoutingPlayer(
    val local: Player,
    private val scope: CoroutineScope,
    private val resolve: suspend (MediaItem) -> MediaItem
) : ForwardingSimpleBasePlayer(local) {
    private val mutations = Mutex()
    private var generation = 0L
    val remoteActive: Boolean get() = player !== local

    fun routeTo(target: Player) {
        generation++
        setPlayer(target)
    }

    private fun mutate(replace: Boolean = false, action: suspend (Player) -> Unit): ListenableFuture<*> {
        if (replace) generation++
        val version = generation
        val target = player
        return scope.future(Dispatchers.Main) {
            mutations.withLock {
                if (version != generation || target !== player) throw CancellationException("Playback route changed")
                action(target)
            }
        }
    }

    private suspend fun resolveItems(items: List<MediaItem>, target: Player): List<MediaItem> {
        val version = generation
        val resolved = items.map { resolve(it) }
        if (version != generation || target !== player) throw CancellationException("Playback route changed")
        return resolved
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (!remoteActive) return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        return mutate(replace = true) { target ->
            val items = resolveItems(mediaItems, target)
            if (startIndex == C.INDEX_UNSET) target.setMediaItems(items)
            else target.setMediaItems(items, startIndex, startPositionMs)
        }
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        if (!remoteActive) return super.handleAddMediaItems(index, mediaItems)
        return mutate { target -> target.addMediaItems(index, resolveItems(mediaItems, target)) }
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        if (!remoteActive) return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
        return mutate { target -> target.replaceMediaItems(fromIndex, toIndex, resolveItems(mediaItems, target)) }
    }
}
