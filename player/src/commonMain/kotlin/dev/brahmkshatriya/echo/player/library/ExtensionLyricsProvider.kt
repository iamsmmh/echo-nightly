package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.player.extensions.ExtensionRuntime
import dev.brahmkshatriya.echo.player.lyrics.LyricsProvider
import dev.brahmkshatriya.echo.player.lyrics.LyricsRequest

/** Adapts the unchanged public LyricsClient contract for the shared cache/UI. */
class ExtensionLyricsProvider(private val runtime: ExtensionRuntime) : LyricsProvider {
    override suspend fun load(request: LyricsRequest): Lyrics? {
        val client = runtime.extensionFor(request.extensionId)?.instance?.value()?.getOrThrow() as? LyricsClient ?: return null
        val feed = client.searchTrackLyrics(request.extensionId, request.track)
        val candidates = feed.getPagedData(feed.notSortTabs.firstOrNull()).pagedData.loadPage(null).data
        val candidate = candidates.firstOrNull() ?: return null
        return if (candidate.lyrics != null) candidate else client.loadLyrics(candidate)
    }
}
