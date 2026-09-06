package dev.brahmkshatriya.echo.playback.cast

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem

/** Keep extension state on the phone; never transmit Bundle credentials or private file paths. */
@UnstableApi
class EchoCastMediaItemConverter : MediaItemConverter {
    private val delegate = DefaultMediaItemConverter()
    private val originals = mutableMapOf<String, MediaItem>()
    fun remember(items: List<MediaItem>) { items.forEach { originals[it.mediaId] = it } }
    fun knows(id: String): Boolean = id in originals
    fun localItem(item: MediaItem): MediaItem? = originals[item.mediaId]
    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        originals[mediaItem.mediaId] = mediaItem
        return delegate.toMediaQueueItem(mediaItem).also {
            it.media?.customData?.put("echoCastVersion", 1)
        }
    }
    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val converted = delegate.toMediaItem(mediaQueueItem)
        val original = originals[converted.mediaId] ?: return converted
        return converted.buildUpon().setMediaMetadata(original.mediaMetadata).build()
    }
}
