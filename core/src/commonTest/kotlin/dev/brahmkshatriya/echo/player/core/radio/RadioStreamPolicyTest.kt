package dev.brahmkshatriya.echo.player.core.radio

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioStreamPolicyTest {

    @Test fun requestsIcyMetadata() {
        assertEquals("1", RadioStreamPolicy.icyRequestHeaders()["Icy-MetaData"])
    }

    @Test fun detectsPlaylistContainersByExtensionAndContentType() {
        assertTrue(RadioStreamPolicy.isPlaylist("http://h/stream.pls"))
        assertTrue(RadioStreamPolicy.isPlaylist("http://h/stream.m3u"))
        assertTrue(RadioStreamPolicy.isPlaylist("http://h/listen?x=1", "audio/x-scpls"))
        assertFalse(RadioStreamPolicy.isPlaylist("http://h/stream.mp3"))
        // A query string must not confuse extension detection.
        assertTrue(RadioStreamPolicy.isPlaylist("http://h/stream.pls?token=abc"))
    }

    @Test fun m3u8IsHlsButPlainM3uIsNot() {
        assertTrue(RadioStreamPolicy.isHls("http://h/master.m3u8"))
        assertFalse(RadioStreamPolicy.isHls("http://h/stream.m3u"))
        // .m3u serving an actual HLS manifest is detected from the body.
        assertTrue(
            RadioStreamPolicy.isHls(
                "http://h/stream.m3u", "audio/x-mpegurl",
                "#EXTM3U\n#EXT-X-TARGETDURATION:6\nseg1.ts\n"
            )
        )
        // .m3u serving a plain playlist stays a playlist.
        assertFalse(
            RadioStreamPolicy.isHls(
                "http://h/stream.m3u", "audio/x-mpegurl", "#EXTM3U\nhttp://h/live.mp3\n"
            )
        )
    }

    @Test fun hlsManifestNeedsHlsSpecificTags() {
        assertFalse(RadioStreamPolicy.looksLikeHlsManifest("#EXTM3U\nhttp://h/a.mp3"))
        assertTrue(RadioStreamPolicy.looksLikeHlsManifest("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8"))
        assertFalse(RadioStreamPolicy.looksLikeHlsManifest("http://h/a.mp3"))
    }

    @Test fun sourceTypeTrustsExplicitDeclaration() {
        assertEquals(
            Streamable.SourceType.HLS,
            RadioStreamPolicy.sourceTypeFor("http://h/x", Streamable.SourceType.HLS)
        )
        assertEquals(
            Streamable.SourceType.Progressive,
            RadioStreamPolicy.sourceTypeFor("http://h/live.mp3")
        )
        assertEquals(
            Streamable.SourceType.HLS,
            RadioStreamPolicy.sourceTypeFor("http://h/live.m3u8")
        )
    }

    @Test fun parsesPlsInFileIndexOrderNotLineOrder() {
        val body = """
            [playlist]
            NumberOfEntries=2
            File2=http://h/second.mp3
            Title2=Second
            File1=http://h/first.mp3
            Title1=First
        """.trimIndent()
        assertEquals(listOf("http://h/first.mp3", "http://h/second.mp3"), RadioStreamPolicy.parsePlaylist(body))
    }

    @Test fun parsesExtendedM3uSkippingDirectives() {
        val body = "#EXTM3U\n#EXTINF:-1,Station\nhttp://h/live.mp3\n\nhttp://h/backup.mp3\n"
        assertEquals(listOf("http://h/live.mp3", "http://h/backup.mp3"), RadioStreamPolicy.parsePlaylist(body))
    }

    @Test fun parsesXspfLocations() {
        val body = "<playlist><trackList><track><location>http://h/a.mp3</location></track></trackList></playlist>"
        assertEquals(listOf("http://h/a.mp3"), RadioStreamPolicy.parsePlaylist(body))
    }

    @Test fun resolvesRelativeAbsoluteAndProtocolRelativeUrls() {
        val base = "http://host.tld/dir/stream.pls"
        assertEquals("https://other/x.mp3", RadioStreamPolicy.resolveUrl(base, "https://other/x.mp3"))
        assertEquals("http://host.tld/root.mp3", RadioStreamPolicy.resolveUrl(base, "/root.mp3"))
        assertEquals("http://host.tld/dir/rel.mp3", RadioStreamPolicy.resolveUrl(base, "rel.mp3"))
        assertEquals("http://cdn.tld/x.mp3", RadioStreamPolicy.resolveUrl(base, "//cdn.tld/x.mp3"))
    }
}
