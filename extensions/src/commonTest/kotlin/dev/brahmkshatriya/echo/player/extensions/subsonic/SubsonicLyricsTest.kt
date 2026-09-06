package dev.brahmkshatriya.echo.player.extensions.subsonic

import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.library.SettingsRepository
import dev.brahmkshatriya.echo.player.platform.*
import dev.brahmkshatriya.echo.player.security.InMemorySecureStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SubsonicLyricsTest {
    private val logger = object : EchoLogger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
    private class Http(private val respond: (String) -> String) : HttpClient {
        override suspend fun send(request: HttpRequest): HttpResponse {
            assertTrue(request.url.contains("f=json"))
            return HttpResponse(200, emptyMap(), respond(request.url).encodeToByteArray())
        }
        override suspend fun download(request: HttpRequest, destination: EchoFile, append: Boolean, onProgress: (Long, Long) -> Unit) = destination
        override fun close() = Unit
    }
    private fun client(response: (String) -> String): SubsonicExtensionClient {
        val api = SubsonicApi(Http(response), logger)
        api.configure(SubsonicApi.ServerConfig("https://music.example", "name", "password"))
        return SubsonicExtensionClient(api, SettingsRepository(InMemoryKeyValueStore(), InMemorySecureStorage()))
    }
    @Test fun openSubsonicStructuredTimingIsPreserved() = runTest {
        val client = client { """{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[{"synced":true,"offset":100,"line":[{"start":1000,"value":"First"},{"start":2000,"value":"Second"}]}]}}}""" }
        val feed = client.searchTrackLyrics("subsonic", Track("1", "Song", duration = 5000))
        val lyric = feed.getPagedData(null).pagedData.loadPage(null).data.single().lyrics as Lyrics.Timed
        assertEquals(listOf(1100L, 2100L), lyric.list.map { it.startTime })
        assertEquals(5000L, lyric.list.last().endTime)
    }
    @Test fun olderSubsonicFallsBackToUnsyncedLyrics() = runTest {
        val client = client { url -> if (url.contains("getLyricsBySongId")) """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not supported"}}}"""
            else """{"subsonic-response":{"status":"ok","lyrics":{"value":"Some words"}}}""" }
        val lyric = client.searchTrackLyrics("subsonic", Track("1", "Song")).getPagedData(null).pagedData.loadPage(null).data.single().lyrics
        assertEquals(Lyrics.Simple("Some words"), lyric)
    }
}
