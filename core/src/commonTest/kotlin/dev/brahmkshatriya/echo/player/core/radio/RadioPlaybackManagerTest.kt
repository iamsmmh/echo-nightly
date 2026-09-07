@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.brahmkshatriya.echo.player.core.radio

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.player.domain.EchoError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun manager(vararg responses: Pair<String, RadioProbe>): Pair<RadioPlaybackManager, MutableList<String>> {
    val map = responses.toMap()
    val visited = mutableListOf<String>()
    val mgr = RadioPlaybackManager { url ->
        visited += url
        map[url] ?: RadioProbe(url, 404)
    }
    return mgr to visited
}

class RadioPlaybackManagerTest {

    @Test fun followsRedirectChainToAudioEndpoint() = runTest {
        val (mgr, visited) = manager(
            "http://h/listen" to RadioProbe("http://h/listen", 302, mapOf("Location" to "/node7/live.mp3")),
            "http://h/node7/live.mp3" to RadioProbe("http://h/node7/live.mp3", 200, mapOf("Content-Type" to "audio/mpeg")),
        )
        val resolved = mgr.resolve("http://h/listen")
        assertEquals("http://h/node7/live.mp3", resolved.url)
        assertEquals(Streamable.SourceType.Progressive, resolved.sourceType)
        assertEquals(listOf("http://h/listen", "http://h/node7/live.mp3"), visited)
        assertEquals("1", resolved.requestHeaders["Icy-MetaData"])
    }

    @Test fun boundsRedirectsAndDetectsLoops() = runTest {
        val (loop, _) = manager(
            "http://h/a" to RadioProbe("http://h/a", 302, mapOf("Location" to "http://h/b")),
            "http://h/b" to RadioProbe("http://h/b", 302, mapOf("Location" to "http://h/a")),
        )
        assertFailsWith<EchoError.Network> { loop.resolve("http://h/a") }
    }

    @Test fun unwrapsPlsPlaylist() = runTest {
        val (mgr, _) = manager(
            "http://h/s.pls" to RadioProbe(
                "http://h/s.pls", 200, mapOf("Content-Type" to "audio/x-scpls"),
                "[playlist]\nFile1=http://h/live.mp3\n"
            ),
            "http://h/live.mp3" to RadioProbe("http://h/live.mp3", 200, mapOf("Content-Type" to "audio/mpeg")),
        )
        assertEquals("http://h/live.mp3", mgr.resolve("http://h/s.pls").url)
    }

    @Test fun fallsBackToNextPlaylistMirrorWhenFirstIsDown() = runTest {
        val (mgr, _) = manager(
            "http://h/s.pls" to RadioProbe(
                "http://h/s.pls", 200, mapOf("Content-Type" to "audio/x-scpls"),
                "[playlist]\nFile1=http://dead/live.mp3\nFile2=http://up/live.mp3\n"
            ),
            "http://dead/live.mp3" to RadioProbe("http://dead/live.mp3", 503),
            "http://up/live.mp3" to RadioProbe("http://up/live.mp3", 200, mapOf("Content-Type" to "audio/mpeg")),
        )
        assertEquals("http://up/live.mp3", mgr.resolve("http://h/s.pls").url)
    }

    @Test fun detectsHlsAndDropsIcyHeaders() = runTest {
        val (mgr, _) = manager(
            "http://h/master.m3u8" to RadioProbe(
                "http://h/master.m3u8", 200,
                mapOf("Content-Type" to "application/vnd.apple.mpegurl"),
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\nlow.m3u8\n"
            ),
        )
        val resolved = mgr.resolve("http://h/master.m3u8")
        assertEquals(Streamable.SourceType.HLS, resolved.sourceType)
        // ICY headers on an HLS request confuse some CDNs; they must not be sent.
        assertTrue(resolved.requestHeaders.isEmpty())
    }

    @Test fun trustsExplicitHlsDeclarationWithoutProbing() = runTest {
        val (mgr, visited) = manager()
        val resolved = mgr.resolve("http://h/opaque", Streamable.SourceType.HLS)
        assertEquals(Streamable.SourceType.HLS, resolved.sourceType)
        assertTrue(visited.isEmpty())
    }

    @Test fun capturesStationHeaders() = runTest {
        val (mgr, _) = manager(
            "http://h/live" to RadioProbe(
                "http://h/live", 200,
                mapOf("Content-Type" to "audio/mpeg", "icy-name" to "Test FM", "icy-metaint" to "16000")
            ),
        )
        val resolved = mgr.resolve("http://h/live")
        assertEquals("Test FM", resolved.station.name)
        assertTrue(mgr.station.value.supportsMetadata)
    }

    @Test fun errorStatusSurfacesAsNetworkError() = runTest {
        val (mgr, _) = manager("http://h/live" to RadioProbe("http://h/live", 500))
        assertFailsWith<EchoError.Network> { mgr.resolve("http://h/live") }
    }

    @Test fun metadataUpdatesOnlyOnChange() {
        val mgr = RadioPlaybackManager { RadioProbe(it, 200) }
        assertEquals("A - B", mgr.onMetadataBlock("StreamTitle='A - B';")?.display)
        // Repeated identical block is a no-op so the lock screen is not churned.
        assertNull(mgr.onMetadataBlock("StreamTitle='A - B';"))
        assertEquals("C", mgr.onMetadataBlock("StreamTitle='C';")?.display)
        assertEquals("C", mgr.metadata.value.title)
        mgr.reset()
        assertTrue(mgr.metadata.value.isEmpty)
    }

    @Test fun resolvedStreamBuildsLiveSource() = runTest {
        val (mgr, _) = manager(
            "http://h/live.mp3" to RadioProbe("http://h/live.mp3", 200, mapOf("Content-Type" to "audio/mpeg")),
        )
        val source = mgr.resolve("http://h/live.mp3").toSource()
        assertTrue(source.isLive)
        assertEquals("http://h/live.mp3", source.request.url)
    }
}
