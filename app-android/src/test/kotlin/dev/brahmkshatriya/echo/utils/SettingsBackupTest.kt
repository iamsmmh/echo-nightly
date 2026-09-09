package dev.brahmkshatriya.echo.utils

import kotlin.test.Test
import kotlin.test.assertTrue

class SettingsBackupTest {

    @Test
    fun validDocumentParses() {
        val json = """{"echo_settings":{"amoled":true,"speed":1}}"""
        val parsed = SettingsBackup.parse(json)
        assertTrue(parsed.isSuccess)
        assertTrue(parsed.getOrThrow().containsKey("echo_settings"))
    }

    @Test
    fun rejectsArrayRoot() {
        assertTrue(SettingsBackup.parse("[]").isFailure)
    }

    @Test
    fun rejectsPathTraversalPrefName() {
        val json = """{"../evil":{"a":1}}"""
        assertTrue(SettingsBackup.parse(json).isFailure)
    }

    @Test
    fun rejectsNestedObjects() {
        val json = """{"echo_settings":{"nested":{"x":1}}}"""
        assertTrue(SettingsBackup.parse(json).isFailure)
    }
}
