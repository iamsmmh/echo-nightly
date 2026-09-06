package dev.brahmkshatriya.echo.player

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Track.Companion.toDurationString
import dev.brahmkshatriya.echo.player.extensions.Md5
import dev.brahmkshatriya.echo.player.extensions.subsonic.SubsonicApi
import dev.brahmkshatriya.echo.player.library.FavoritesRepository
import dev.brahmkshatriya.echo.player.library.HistoryRepository
import dev.brahmkshatriya.echo.player.library.PlaylistRepository
import dev.brahmkshatriya.echo.player.library.PlayerSettings
import dev.brahmkshatriya.echo.player.library.SettingsRepository
import dev.brahmkshatriya.echo.player.library.TrackRef
import dev.brahmkshatriya.echo.player.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Md5Test {
    @Test
    fun `rfc 1321 vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.digestHex(""))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Md5.digestHex("a"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.digestHex("abc"))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", Md5.digestHex("message digest"))
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", Md5.digestHex("12345678901234567890123456789012345678901234567890123456789012345678901234567890"))
    }
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
        assertEquals("abcXYZ09-_.~", SubsonicApi.encode("abcXYZ09-_.~"))
    }

    @Test
    fun `sensitive params are sanitized for logging`() {
        val url = "https://example.com/rest/stream?u=admin&t=deadbeef&s=abc&id=1"
        val sanitized = dev.brahmkshatriya.echo.player.domain.sanitizeUrl(url)
        assertTrue(sanitized.contains("t=***"))
        assertTrue(sanitized.contains("s=***"))
        assertFalse(sanitized.contains("deadbeef"))
        assertTrue(sanitized.contains("id=1"))
    }
}

class TestLogger : dev.brahmkshatriya.echo.player.domain.EchoLogger {
    override fun debug(tag: String, message: String) {}
    override fun info(tag: String, message: String) {}
    override fun warn(tag: String, message: String, throwable: Throwable?) {}
    override fun error(tag: String, message: String, throwable: Throwable?) {}
}

class SettingsRepositoryTest {

    @Test
    fun `settings roundtrip and defaults`() {
        val store = InMemoryKeyValueStore()
        val repo = SettingsRepository(store)
        assertEquals(PlayerSettings(), repo.settings)
        repo.update { it.copy(activeExtensionId = "subsonic", downloadMaxBitrateKbps = 192) }
        val reloaded = SettingsRepository(store).settings
        assertEquals("subsonic", reloaded.activeExtensionId)
        assertEquals(192, reloaded.downloadMaxBitrateKbps)
    }

    @Test
    fun `unknown future fields fall back to defaults`() {
        val store = InMemoryKeyValueStore()
        store.putString("echo.player.settings", """{"activeExtensionId":"x","totallyNewField":1}""")
        val settings = SettingsRepository(store).settings
        assertEquals("x", settings.activeExtensionId)
        assertEquals(1.0f, settings.defaultPlaybackSpeed)
    }

    @Test
    fun `listeners are notified`() {
        val store = InMemoryKeyValueStore()
        val repo = SettingsRepository(store)
        var seen = repo.settings
        repo.addListener { seen = it }
        repo.update { it.copy(transcodeFormat = "mp3") }
        assertEquals("mp3", seen.transcodeFormat)
        repo.removeListener({})
    }
}

class PlaylistRepositoryTest {

    private fun ref(id: String) = TrackRef("local-offline", id, "Title $id", "Artist")

    @Test
    fun `create add remove reorder persist`() {
        val store = InMemoryKeyValueStore()
        val repo = PlaylistRepository(store)
        val playlist = repo.create("Road trip")
        assertTrue(repo.addTracks(playlist.id, listOf(ref("a"), ref("b"), ref("c"))))
        assertEquals(3, repo.get(playlist.id)!!.tracks.size)

        // duplicate rejected
        repo.addTracks(playlist.id, listOf(ref("a")))
        assertEquals(3, repo.get(playlist.id)!!.tracks.size)

        // reorder
        assertTrue(repo.moveTrack(playlist.id, 0, 2))
        assertEquals("c", repo.get(playlist.id)!!.tracks[0].trackId)

        // remove
        assertTrue(repo.removeTrack(playlist.id, "c"))
        assertEquals(listOf("b", "a"), repo.get(playlist.id)!!.tracks.map { it.trackId })

        // persistence: a fresh repository on the same store reloads state
        val reloaded = PlaylistRepository(store)
        assertEquals(2, reloaded.get(playlist.id)!!.tracks.size)

        // rename + delete
        assertTrue(repo.rename(playlist.id, "Night drive"))
        assertTrue(repo.delete(playlist.id))
        assertNull(repo.get(playlist.id))
    }
}

class FavoritesRepositoryTest {

    @Test
    fun `toggle favorite persists`() {
        val store = InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)
        val ref = TrackRef("subsonic", "42", "Song", "Artist")
        assertTrue(repo.toggle(ref))
        assertTrue(repo.isFavorite(ref.key))
        assertFalse(repo.toggle(ref))
        assertFalse(repo.isFavorite(ref.key))
        assertEquals(0, FavoritesRepository(store).favorites.value.size)
    }
}

class HistoryRepositoryTest {

    @Test
    fun `history records distinct and caps size`() {
        val store = InMemoryKeyValueStore()
        val repo = HistoryRepository(store)
        repo.record(TrackRef("ext", "a", "A", "X"), 100)
        repo.record(TrackRef("ext", "b", "B", "X"), 200)
        repo.record(TrackRef("ext", "a", "A", "X"), 300)
        assertEquals(listOf("a", "b"), repo.history.value.map { it.ref.trackId })
        repeat(300) { repo.record(TrackRef("ext", "t$it", "T", "X"), 1) }
        assertTrue(repo.history.value.size <= 200)
    }
}

class DurationFormattingTest {
    @Test
    fun `formats durations like the shared Track model`() {
        assertEquals("00:00", 0L.toDurationString())
        assertEquals("00:59", 59_000L.toDurationString())
        assertEquals("05:03", 303_000L.toDurationString())
        assertEquals("01:00:00", 3_600_000L.toDurationString())
        assertEquals("02:05:09", 7_509_000L.toDurationString())
    }

    @Test
    fun `formatMs matches for player screen`() {
        assertEquals("0:00", dev.brahmkshatriya.echo.player.ui.screens.formatMs(0))
        assertEquals("3:05", dev.brahmkshatriya.echo.player.ui.screens.formatMs(185_000))
        assertEquals("1:02:03", dev.brahmkshatriya.echo.player.ui.screens.formatMs(3_723_000))
    }
}
