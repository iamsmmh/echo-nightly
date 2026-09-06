package dev.brahmkshatriya.echo.player.extensions.subsonic

import dev.brahmkshatriya.echo.player.extensions.Md5
import dev.brahmkshatriya.echo.player.domain.sanitizeUrl
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private class TestLogger : dev.brahmkshatriya.echo.player.domain.EchoLogger {
    override fun debug(tag: String, message: String) {}
    override fun info(tag: String, message: String) {}
    override fun warn(tag: String, message: String, throwable: Throwable?) {}
    override fun error(tag: String, message: String, throwable: Throwable?) {}
}

class SubsonicAuthTest {

    private class TestHttp : dev.brahmkshatriya.echo.player.platform.HttpClient {
        val requests = mutableListOf<String>()
        override suspend fun send(request: dev.brahmkshatriya.echo.player.platform.HttpRequest) =
            dev.brahmkshatriya.echo.player.platform.HttpResponse(200, emptyMap(), "{}".encodeToByteArray())

        override suspend fun download(
            request: dev.brahmkshatriya.echo.player.platform.HttpRequest,
            destination: dev.brahmkshatriya.echo.common.models.EchoFile,
            append: Boolean,
            onProgress: (Long, Long) -> Unit
        ) = destination

        override fun close() {}
    }

    @Test
    fun `token is md5 of password plus salt`() {
        val api = SubsonicApi(TestHttp(), TestLogger())
        api.configure(SubsonicApi.ServerConfig("https://example.com", "admin", "sesame"))
        val url = api.streamUrl("42", 0, "raw")
        assertTrue(url.startsWith("https://example.com/rest/stream?"))
        assertTrue(url.contains("u=admin"))
        assertTrue(url.contains("f=json"))
        assertTrue(url.contains("t="))
        assertTrue(url.contains("s="))
        // salt is 12 chars; token is 32 hex chars
        val salt = Regex("s=([A-Za-z0-9]{12})&").find(url)!!.groupValues[1]
        val token = Regex("t=([a-f0-9]{32})&").find(url)!!.groupValues[1]
        assertEquals(Md5.digestHex("sesame$salt"), token)
    }

    @Test
    fun `stream url carries id and bitrate`() {
        val api = SubsonicApi(TestHttp(), TestLogger())
        api.configure(SubsonicApi.ServerConfig("https://example.com", "admin", "pw"))
        val url = api.streamUrl("42", 320, "mp3")
        assertTrue(url.contains("id=42"))
        assertTrue(url.contains("maxBitRate=320"))
        assertTrue(url.contains("format=mp3"))
    }

    @Test
    fun `url encoding escapes special characters`() {
        assertEquals("a%20b", SubsonicApi.encode("a b"))
        assertEquals("%C3%A9", SubsonicApi.encode("é"))
        assertEquals("%F0%9F%8E%B5", SubsonicApi.encode("🎵"))
        assertEquals("abcXYZ09-_.~", SubsonicApi.encode("abcXYZ09-_.~"))
    }

    @Test
    fun `sensitive params are sanitized for logging`() {
        val url = "https://example.com/rest/stream?u=admin&t=deadbeef&s=abc&id=1"
        val sanitized = sanitizeUrl(url)
        assertTrue(sanitized.contains("t=***"))
        assertTrue(sanitized.contains("s=***"))
        assertFalse(sanitized.contains("deadbeef"))
        assertTrue(sanitized.contains("id=1"))
    }
    @Test fun clearingConfigurationDropsCredentialsAndDisablesRequests() {
        val api = SubsonicApi(TestHttp(), TestLogger())
        api.configure(SubsonicApi.ServerConfig("https://example.com", "admin", "pw"))
        assertTrue(api.isConfigured)
        api.clearConfiguration()
        assertFalse(api.isConfigured)
        kotlin.test.assertFails { api.streamUrl("42", 0, "raw") }
    }
}
