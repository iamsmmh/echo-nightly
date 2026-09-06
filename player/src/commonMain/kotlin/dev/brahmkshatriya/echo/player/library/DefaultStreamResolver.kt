package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.player.audio.QueueItem
import dev.brahmkshatriya.echo.player.audio.ResolvedStream
import dev.brahmkshatriya.echo.player.audio.StreamResolver
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.domain.sanitizeUrl
import dev.brahmkshatriya.echo.player.domain.runCatchingCancellable
import dev.brahmkshatriya.echo.player.download.DownloadRepository
import dev.brahmkshatriya.echo.player.extensions.ExtensionRuntime
import dev.brahmkshatriya.echo.player.extensions.local.LocalExtensionClient

/**
 * Offline-first stream resolution:
 *
 * 1. completed download file
 * 2. imported local library file
 * 3. the extension's stream (streaming is first class; never
 *    download-to-play)
 *
 * Local playback therefore works without any network access whenever the file
 * is on the device.
 */
class DefaultStreamResolver(
    private val runtime: ExtensionRuntime,
    private val downloads: DownloadRepository,
    private val library: LocalLibraryRepository,
    private val logger: EchoLogger
) : StreamResolver {

    override suspend fun resolve(item: QueueItem): ResolvedStream {
        val track = item.track

        // 1. Downloaded offline file
        downloads.verifiedCompletedFileFor(item.extensionId, track.id)?.let { path ->
            logger.debug(TAG, "Resolving downloaded file for '${track.title}'")
            return ResolvedStream(
                url = path, isLocalFile = true,
                mimeType = track.extras["contentType"]?.ifBlank { null },
                source = ResolvedStream.Source.DOWNLOAD
            )
        }

        // 2. Imported local library file
        if (item.extensionId == LocalExtensionClient.ID) {
            val entry = library.find(track.id)
                ?: track.extras["localPath"]?.let { library.tracks.value.firstOrNull { t -> t.path == it } }
            if (entry != null) {
                logger.debug(TAG, "Resolving local library file for '${track.title}'")
                return ResolvedStream(
                    url = entry.path, isLocalFile = true,
                    mimeType = entry.mimeType,
                    source = ResolvedStream.Source.LOCAL_LIBRARY
                )
            }
        }
        track.extras["localPath"]?.let { path ->
            return ResolvedStream(url = path, isLocalFile = true, source = ResolvedStream.Source.LOCAL_LIBRARY)
        }

        // 3. Extension stream
        val extension = runtime.extensionFor(item.extensionId)
            ?: throw EchoError.Extension("Extension '${item.extensionId}' is not available")

        val client = extension.instance.value().getOrNull()
            ?: throw EchoError.Extension("Could not initialize extension '${extension.name}'", extension.id)
        val trackClient = client as? TrackClient
            ?: throw EchoError.Playback("Extension '${extension.name}' cannot stream tracks")

        val loaded = if (track.streamables.isEmpty()) {
            runCatchingCancellable { trackClient.loadTrack(track, false) }
                .getOrElse { throw EchoError.Extension("loadTrack failed: ${it.message}", extension.id, it) }
        } else track

        val server = loaded.servers.maxByOrNull { it.quality }
            ?: throw EchoError.Playback("No streamable server for '${track.title}'")

        val media = runCatchingCancellable { trackClient.loadStreamableMedia(server, false) }
            .getOrElse { throw EchoError.Extension("loadStreamableMedia failed: ${it.message}", extension.id, it) }

        return when (media) {
            is Streamable.Media.Server -> {
                val source = media.sources.firstOrNull()
                    ?: throw EchoError.Playback("Stream server has no sources")
                val http = source as? Streamable.Source.Http
                    ?: throw EchoError.Playback("Raw streams are not supported on this platform")
                logger.debug(TAG, "Resolving stream for '${track.title}' from ${sanitizeUrl(http.request.url)}")
                ResolvedStream(
                    url = http.request.url,
                    headers = http.request.headers,
                    mimeType = null,
                    source = ResolvedStream.Source.EXTENSION
                )
            }
            else -> throw EchoError.Playback("Unsupported streamable media type")
        }
    }

    private companion object {
        const val TAG = "StreamResolver"
    }
}
