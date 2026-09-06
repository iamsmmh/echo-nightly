package dev.brahmkshatriya.echo.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.platform.HttpClient

/** Decodes image bytes into an [ImageBitmap] on the current platform. */
interface ArtworkDecoder {
    fun decode(bytes: ByteArray): ImageBitmap?
}

/** Creates the platform artwork decoder. */
expect fun createArtworkDecoder(): ArtworkDecoder

/**
 * Simple bounded in-memory LRU artwork cache shared by all screens
 * (keeps at most [MAX_ENTRIES] entries).
 */
object ArtworkCache {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun get(key: String): ImageBitmap? = synchronized(cache) { cache[key] }

    fun put(key: String, bitmap: ImageBitmap) {
        synchronized(cache) { cache[key] = bitmap }
    }
}

/**
 * Loads artwork for any [ImageHolder]: network requests go through the shared
 * [HttpClient]; `file://` resource URIs read local artwork extracted from tags.
 */
class ArtworkLoader(
    private val http: HttpClient,
    private val logger: EchoLogger
) {
    private val decoder = createArtworkDecoder()

    suspend fun load(holder: ImageHolder?): ImageBitmap? {
        if (holder == null) return null
        val key = cacheKey(holder)
        ArtworkCache.get(key)?.let { return it }
        val bytes = when (holder) {
            is ImageHolder.NetworkRequestImageHolder -> fetchBytes(holder.request)
            is ImageHolder.ResourceUriImageHolder -> {
                val path = holder.uri.removePrefix("file://")
                runCatching { EchoFile(path).bytes() }.getOrNull()
            }
            else -> null
        } ?: return null
        val bitmap = runCatching { decoder.decode(bytes) }.getOrNull()?.also {
            ArtworkCache.put(key, it)
        }
        return bitmap
    }

    private suspend fun fetchBytes(request: NetworkRequest): ByteArray? {
        if (!request.url.startsWith("http")) {
            // file:// or content:// artwork
            val path = request.url.removePrefix("file://")
            return runCatching { EchoFile(path).bytes() }.getOrNull()
        }
        return runCatching {
            http.send(
                dev.brahmkshatriya.echo.player.platform.HttpRequest(
                    url = request.url,
                    headers = request.headers,
                    requestTimeoutMs = 15_000
                )
            ).body.takeIf { it.isNotEmpty() }
        }.onFailure {
            logger.debug("Artwork", "Failed to load artwork: ${it.message}")
        }.getOrNull()
    }

    private fun cacheKey(holder: ImageHolder): String = when (holder) {
        is ImageHolder.NetworkRequestImageHolder -> holder.request.url
        is ImageHolder.ResourceUriImageHolder -> holder.uri
        is ImageHolder.ResourceIdImageHolder -> "res:${holder.resId}"
        is ImageHolder.HexColorImageHolder -> "color:${holder.hex}"
    }
}

/** Renders artwork with a placeholder while loading and on failure. */
@Composable
fun ArtworkImage(
    holder: ImageHolder?,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    loader: ArtworkLoader
) {
    var bitmap by remember(holder) { mutableStateOf(holder?.let { ArtworkCache.get(cacheKeyOf(it)) }) }

    LaunchedEffect(holder) {
        if (holder != null && bitmap == null) {
            bitmap = loader.load(holder)
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Crossfade(targetState = bitmap) { img ->
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size / 2)
                )
            }
        }
    }
}

private fun cacheKeyOf(holder: ImageHolder): String = when (holder) {
    is ImageHolder.NetworkRequestImageHolder -> holder.request.url
    is ImageHolder.ResourceUriImageHolder -> holder.uri
    is ImageHolder.ResourceIdImageHolder -> "res:${holder.resId}"
    is ImageHolder.HexColorImageHolder -> "color:${holder.hex}"
}
