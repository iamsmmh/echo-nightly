package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.player.platform.InMemoryKeyValueStore
import dev.brahmkshatriya.echo.player.security.*
import kotlin.test.*

class CredentialMigrationTest {
    private val key = "echo.player.settings"
    @Test fun legacyPasswordMigratesBeforePlaintextIsRemoved() {
        val store = InMemoryKeyValueStore()
        val secrets = InMemorySecureStorage()
        store.putString(key, """{"subsonicServerUrl":"https://music.example","subsonicUsername":"u","subsonicPassword":"sensitive-value"}""")
        val repository = SettingsRepository(store, secrets)
        assertEquals("sensitive-value", repository.settings.subsonicPassword)
        assertFalse(store.getString(key)!!.contains("sensitive-value"))
        assertEquals("sensitive-value", SettingsRepository(store, secrets).settings.subsonicPassword)
    }
    @Test fun failedMigrationRetainsRecoverableLegacyDocument() {
        val store = InMemoryKeyValueStore()
        val raw = """{"subsonicPassword":"old-password"}"""
        store.putString(key, raw)
        val locked = object : SecureStorage {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String): Unit = throw SecureStorageException("Device locked")
            override fun remove(key: String) = Unit
        }
        assertFailsWith<SecureStorageException> { SettingsRepository(store, locked) }
        assertEquals(raw, store.getString(key))
    }
    @Test fun updatesNeverPutSecretsBackIntoSettingsJson() {
        val store = InMemoryKeyValueStore(); val secrets = InMemorySecureStorage()
        val repository = SettingsRepository(store, secrets)
        repository.update { it.copy(subsonicPassword = "new-password", subsonicUsername = "name") }
        assertFalse(store.getString(key)!!.contains("new-password"))
        assertEquals("new-password", SettingsRepository(store, secrets).settings.subsonicPassword)
        repository.update { it.copy(subsonicPassword = "") }
        assertEquals("", SettingsRepository(store, secrets).settings.subsonicPassword)
    }
    @Test fun listenersCanUnsubscribeDuringNotification() {
        val repository = SettingsRepository(InMemoryKeyValueStore(), InMemorySecureStorage())
        lateinit var callback: (PlayerSettings) -> Unit
        callback = { repository.removeListener(callback) }
        repository.addListener(callback)
        repository.update { it.copy(amoledMode = true) }
        assertTrue(repository.state.value.amoledMode)
    }
    private class RecordingSecrets : SecureStorage {
        val values = mutableMapOf<String, String>()
        var rejectRemoval = false
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) {
            if (rejectRemoval) throw SecureStorageException("Locked")
            values.remove(key)
        }
    }
    @Test fun failedSettingsCommitKeepsOldSecretAndCleansOrphanOnRestart() {
        val backing = InMemoryKeyValueStore()
        var rejectCommit = false
        val store = object : dev.brahmkshatriya.echo.player.platform.KeyValueStore by backing {
            override fun putStringDurably(key: String, value: String?) {
                if (rejectCommit && key == this@CredentialMigrationTest.key) throw IllegalStateException("Disk full")
                backing.putStringDurably(key, value)
            }
        }
        val secrets = RecordingSecrets()
        val repo = SettingsRepository(store, secrets)
        repo.update { it.copy(subsonicPassword = "previous") }
        rejectCommit = true
        assertFailsWith<IllegalStateException> { repo.update { it.copy(subsonicPassword = "replacement") } }
        assertEquals("previous", repo.settings.subsonicPassword)
        assertEquals(2, secrets.values.size)
        rejectCommit = false
        assertEquals("previous", SettingsRepository(store, secrets).settings.subsonicPassword)
        assertEquals(listOf("previous"), secrets.values.values.toList())
    }
    @Test fun cleanupFailureDoesNotUndoCommittedAccountChange() {
        val store = InMemoryKeyValueStore(); val secrets = RecordingSecrets()
        val repo = SettingsRepository(store, secrets)
        repo.update { it.copy(subsonicUsername = "first", subsonicPassword = "one") }
        secrets.rejectRemoval = true
        repo.update { it.copy(subsonicUsername = "second", subsonicPassword = "two") }
        assertEquals("two", repo.settings.subsonicPassword)
        secrets.rejectRemoval = false
        assertEquals("two", SettingsRepository(store, secrets).settings.subsonicPassword)
        assertEquals(listOf("two"), secrets.values.values.toList())
    }
    @Test fun failedReadbackDoesNotSanitizePlaintext() {
        val store = InMemoryKeyValueStore()
        val raw = """{"subsonicPassword":"old-password"}"""
        store.putString(key, raw)
        val faulty = object : SecureStorage {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = Unit
            override fun remove(key: String) = Unit
        }
        assertFailsWith<SecureStorageException> { SettingsRepository(store, faulty) }
        assertEquals(raw, store.getString(key))
    }
    @Test fun credentialsAreBoundToTheirServerAndAccount() {
        val store = InMemoryKeyValueStore(); val secrets = InMemorySecureStorage()
        val repo = SettingsRepository(store, secrets)
        repo.update { it.copy(subsonicServerUrl = "https://music.example", subsonicUsername = "listener", subsonicPassword = "secret") }
        store.putString(key, store.getString(key)!!.replace("https://music.example", "https://different.example"))
        assertFailsWith<SecureStorageException> { SettingsRepository(store, secrets) }
    }
}
