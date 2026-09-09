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

        val servers = loaded.servers.sortedByDescending { it.quality }
        if (servers.isEmpty()) throw EchoError.Playback("No streamable server for '${track.title}'")

        var lastError: Throwable? = null
        for (server in servers) {
            val media = runCatchingCancellable { trackClient.loadStreamableMedia(server, false) }
                .getOrElse { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    lastError = EchoError.Extension("loadStreamableMedia failed: ${error.message}", extension.id, error)
                    null
                } ?: continue
            when (media) {
                is Streamable.Media.Server -> {
                    val httpSources = media.sources.filterIsInstance<Streamable.Source.Http>()
                    val source = httpSources.firstOrNull()
                    if (source == null) {
                        lastError = EchoError.Playback("Stream server has no HTTP sources")
                        continue
                    }
                    logger.debug(TAG, "Resolving stream for '${track.title}' from ${sanitizeUrl(source.request.url)}")
                    return ResolvedStream(
                        url = source.request.url,
                        headers = source.request.headers,
                        mimeType = null,
                        source = ResolvedStream.Source.EXTENSION
                    )
                }
                else -> {
                    lastError = EchoError.Playback("Unsupported streamable media type")
                }
            }
        }
        throw lastError ?: EchoError.Playback("No playable stream for '${track.title}'")
    }

    private companion object {
        const val TAG = "StreamResolver"
    }
}
