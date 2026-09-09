package dev.brahmkshatriya.echo.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parse and validate an exported SharedPreferences backup before any writes.
 */
object SettingsBackup {
    private val PREF_NAME = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val KEY_NAME = Regex("^[A-Za-z0-9._:-]{1,128}$")
    private const val MAX_BYTES = 1_000_000
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(jsonString: String): Result<Map<String, JsonObject>> = runCatching {
        require(jsonString.length <= MAX_BYTES) { "Settings file is too large" }
        val root = json.parseToJsonElement(jsonString)
        val obj = root as? JsonObject ?: error("Settings file must be a JSON object")
        obj.entries.associate { (name, value) ->
            require(PREF_NAME.matches(name)) { "Invalid preference store name: $name" }
            val map = value as? JsonObject ?: error("Preference '$name' must be an object")
            map.keys.forEach { key ->
                require(KEY_NAME.matches(key)) { "Invalid preference key: $key" }
            }
            map.values.forEach { value ->
                require(isSupportedValue(value)) { "Unsupported value in preference store '$name'" }
            }
            name to map
        }
    }

    fun isSupportedValue(value: kotlinx.serialization.json.JsonElement): Boolean = when (value) {
        is JsonNull -> true
        is JsonArray -> value.all { it is JsonPrimitive }
        is JsonPrimitive -> true
        else -> false
    }
}
