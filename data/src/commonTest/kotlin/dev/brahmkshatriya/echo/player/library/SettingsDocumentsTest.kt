package dev.brahmkshatriya.echo.player.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SettingsDocumentsTest {

    @Test
    fun exportOmitsPasswordAndRoundTrips() {
        val original = PlayerSettings(
            amoledMode = true,
            defaultPlaybackSpeed = 1.25f,
            subsonicServerUrl = "https://music.example",
            subsonicUsername = "ada",
            subsonicPassword = "secret",
            crossfadeMs = 4000,
            replayGainMode = 1
        )
        val json = SettingsDocuments.export(original)
        assertTrue("secret" !in json)
        val imported = SettingsDocuments.import(json, current = original)
        assertEquals(true, imported.amoledMode)
        assertEquals(1.25f, imported.defaultPlaybackSpeed)
        assertEquals("https://music.example", imported.subsonicServerUrl)
        assertEquals("secret", imported.subsonicPassword)
        assertEquals(4000, imported.crossfadeMs)
    }

    @Test
    fun invalidJsonIsRejected() {
        assertFails { SettingsDocuments.import("not-json") }
        assertFails { SettingsDocuments.import("[]") }
    }

    @Test
    fun outOfRangeValuesAreRejected() {
        val bad = PlayerSettings(defaultPlaybackSpeed = 99f)
        val json = SettingsDocuments.export(bad)
        assertFails { SettingsDocuments.import(json) }
    }

    @Test
    fun futureVersionIsRejected() {
        val json = """{"version":99,"settings":{"amoledMode":false}}"""
        assertFails { SettingsDocuments.import(json) }
    }

    @Test
    fun legacyPlayerSettingsObjectIsAccepted() {
        val json = """{"amoledMode":true,"defaultPlaybackSpeed":1.0,"transcodeFormat":"raw"}"""
        val imported = SettingsDocuments.import(json)
        assertEquals(true, imported.amoledMode)
    }
}
