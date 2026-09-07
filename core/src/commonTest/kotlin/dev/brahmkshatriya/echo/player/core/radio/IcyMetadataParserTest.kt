package dev.brahmkshatriya.echo.player.core.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcyMetadataParserTest {

    @Test fun parsesStreamTitleAndUrl() {
        val parsed = IcyMetadataParser.parse("StreamTitle='Daft Punk - One More Time';StreamUrl='http://h/np';")
        assertEquals("Daft Punk - One More Time", parsed?.title)
        assertEquals("Daft Punk", parsed?.artist)
        assertEquals("One More Time", parsed?.track)
        assertEquals("http://h/np", parsed?.url)
    }

    @Test fun keepsSemicolonsInsideTitles() {
        // The classic split(';') bug corrupts this title.
        val parsed = IcyMetadataParser.parse("StreamTitle='AC/DC - Rock; Roll';StreamUrl='';")
        assertEquals("AC/DC - Rock; Roll", parsed?.title)
        assertEquals("Rock; Roll", parsed?.track)
        assertNull(parsed?.url)
    }

    @Test fun paddingOnlyBlocksAreNoOps() {
        assertNull(IcyMetadataParser.parse("\u0000\u0000\u0000"))
        assertNull(IcyMetadataParser.parse(""))
        assertNull(IcyMetadataParser.parse("StreamTitle='';"))
    }

    @Test fun stripsTrailingNulPadding() {
        val parsed = IcyMetadataParser.parse("StreamTitle='Song';\u0000\u0000\u0000")
        assertEquals("Song", parsed?.title)
    }

    @Test fun titleWithoutSeparatorHasNoArtistSplit() {
        val parsed = IcyMetadataParser.parse("StreamTitle='News at Ten';")
        assertEquals("News at Ten", parsed?.title)
        assertNull(parsed?.artist)
        assertEquals("News at Ten", parsed?.display)
    }

    @Test fun hyphenWithoutSpacesIsNotASeparator() {
        val (artist, track) = IcyMetadataParser.splitArtistTitle("Jean-Michel Jarre")
        assertNull(artist)
        assertNull(track)
    }

    @Test fun parsesStationHeadersCaseInsensitively() {
        val info = IcyMetadataParser.parseHeaders(
            mapOf(
                "icy-name" to "Test FM",
                "ICY-GENRE" to "Jazz",
                "icy-br" to "128",
                "Icy-MetaInt" to "16000",
            )
        )
        assertEquals("Test FM", info.name)
        assertEquals("Jazz", info.genre)
        assertEquals(128, info.bitrateKbps)
        assertEquals(16000, info.metadataInterval)
        assertTrue(info.supportsMetadata)
    }

    @Test fun absentMetaIntMeansNoMetadataSupport() {
        val info = IcyMetadataParser.parseHeaders(mapOf("icy-name" to "X"))
        assertEquals(false, info.supportsMetadata)
        assertNull(info.metadataInterval)
    }
}
