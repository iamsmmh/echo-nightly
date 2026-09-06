package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.player.platform.KeyValueStore
import kotlinx.serialization.Serializable

/** Player settings shared by Android and iOS. */
@Serializable
data class PlayerSettings(
    // Appearance
    val useDarkTheme: Boolean? = null,           // null = follow system
    // Playback
    val defaultPlaybackSpeed: Float = 1.0f,
    val pauseOnHeadphonesDisconnected: Boolean = true,
    // Downloads / offline
    val downloadOnlyOnUnmetered: Boolean = false,
    val downloadMaxBitrateKbps: Int = 0,         // 0 = original
    val transcodeFormat: String = "raw",         // raw | mp3
    // Extension
    val activeExtensionId: String? = null,
    val disabledExtensions: Set<String> = emptySet(),
    // Subsonic / OpenSubsonic server (user's own server)
    val subsonicServerUrl: String = "",
    val subsonicUsername: String = "",
    val subsonicPassword: String = "",
    // Diagnostics
    val hasSeenOnboarding: Boolean = false
)

/**
 * Shared settings repository backed by a [KeyValueStore]; JSON serialized so
 * new fields get default values without migration.
 */
class SettingsRepository(private val store: KeyValueStore) {

    var settings: PlayerSettings
        get() = runCatching {
            json.decodeFromString(PlayerSettings.serializer(), store.getString(KEY) ?: return@runCatching null)
        }.getOrNull() ?: PlayerSettings()
        private set(value) {
            store.putString(KEY, json.encodeToString(PlayerSettings.serializer(), value))
            listeners.forEach { it(value) }
        }

    private val listeners = mutableListOf<(PlayerSettings) -> Unit>()

    fun update(block: (PlayerSettings) -> PlayerSettings) {
        settings = block(settings)
    }

    fun addListener(listener: (PlayerSettings) -> Unit) {
        listeners.add(listener)
        listener(settings)
    }

    fun removeListener(listener: (PlayerSettings) -> Unit) {
        listeners.remove(listener)
    }

    private companion object {
        const val KEY = "echo.player.settings"
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
