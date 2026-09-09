package dev.brahmkshatriya.echo.player.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Versioned settings document: parse → validate → migrate → apply.
 *
 * Credentials are never written into the exported document. Importing a file
 * that omits the Subsonic password keeps the currently stored password.
 */
object SettingsDocuments {
    const val CURRENT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Document(
        val version: Int = CURRENT_VERSION,
        val settings: PlayerSettings
    )

    fun export(settings: PlayerSettings): String {
        val sanitized = settings.copy(subsonicPassword = "")
        return json.encodeToString(Document.serializer(), Document(CURRENT_VERSION, sanitized))
    }

    fun import(raw: String, current: PlayerSettings = PlayerSettings()): PlayerSettings {
        require(raw.length <= MAX_BYTES) { "Settings document is too large" }
        val element = runCatching { json.parseToJsonElement(raw) }.getOrElse {
            error("Settings file is not valid JSON")
        }
        val obj = element as? JsonObject ?: error("Settings file must be a JSON object")
        val document = when {
            obj.containsKey("settings") -> json.decodeFromJsonElement(Document.serializer(), obj)
            else -> Document(version = 0, settings = json.decodeFromJsonElement(PlayerSettings.serializer(), obj))
        }
        val migrated = migrate(document)
        return validate(migrated).let { imported ->
            if (imported.subsonicPassword.isEmpty() && current.subsonicPassword.isNotEmpty()) {
                imported.copy(subsonicPassword = current.subsonicPassword)
            } else imported
        }
    }

    private fun migrate(document: Document): PlayerSettings {
        require(document.version <= CURRENT_VERSION) {
            "Settings version ${document.version} is newer than this app"
        }
        return document.settings
    }

    fun validate(settings: PlayerSettings): PlayerSettings {
        require(settings.defaultPlaybackSpeed.isFinite() && settings.defaultPlaybackSpeed in 0.25f..3f) {
            "Playback speed must be between 0.25 and 3"
        }
        require(settings.downloadMaxBitrateKbps >= 0) { "Download bitrate cannot be negative" }
        require(settings.replayGainMode in 0..2) { "Unknown ReplayGain mode" }
        require(settings.crossfadeMs in 0L..12_000L) { "Crossfade must be between 0 and 12 seconds" }
        require(settings.sleepTimerMinutes >= 0) { "Sleep timer cannot be negative" }
        require(settings.transcodeFormat in setOf("raw", "mp3")) { "Unknown transcode format" }
        require(settings.replayGainPreampDb.isFinite() && settings.replayGainPreampDb in -15f..15f) {
            "ReplayGain preamp is out of range"
        }
        return settings
    }

    private const val MAX_BYTES = 256 * 1024
}
