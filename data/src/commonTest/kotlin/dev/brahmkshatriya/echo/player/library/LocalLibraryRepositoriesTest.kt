package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.player.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals(listOf("b", "c", "a"), repo.get(playlist.id)!!.tracks.map { it.trackId })

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
