package dev.brahmkshatriya.echo.player.core.casting

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import kotlin.test.*

class CastSourcePolicyTest {
    private val http = Streamable.Source.Http(NetworkRequest("https://music.example/song.flac?token=redacted"))
    @Test fun directHttpIsSupportedButLocalAndRawSourcesAreNot() {
        assertTrue(CastSourcePolicy.supported(http))
        assertFalse(CastSourcePolicy.supported(http.copy(request = NetworkRequest("file:///music.mp3"))))
        assertFalse(CastSourcePolicy.supported(Streamable.Source.Raw(id = "raw")))
    }
    @Test fun authenticatedHeadersAndLiveContentNeverSilentlyDisappear() {
        assertFalse(CastSourcePolicy.supported(http.copy(isLive = true)))
        assertFalse(CastSourcePolicy.supported(http.copy(request = NetworkRequest("https://music.example", mapOf("Authorization" to "secret")))))
    }
    @Test fun selectionAndContentTypeAreDeterministic() {
        assertEquals(http, CastSourcePolicy.select(listOf(http.copy(isLive = true), http), 0))
        assertNull(CastSourcePolicy.select(emptyList(), 0))
        assertEquals("audio/flac", CastSourcePolicy.contentType(http.request.url))
        assertEquals("audio/mp4", CastSourcePolicy.contentType("https://host/music.M4A#art"))
    }
}
