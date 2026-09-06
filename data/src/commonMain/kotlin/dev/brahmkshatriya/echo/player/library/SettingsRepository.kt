package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.player.platform.KeyValueStore
import kotlinx.serialization.Serializable
import dev.brahmkshatriya.echo.player.security.SecureStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import dev.brahmkshatriya.echo.player.security.SecureStorageException
import dev.brahmkshatriya.echo.player.security.secureRandomBytes
import dev.brahmkshatriya.echo.player.domain.Sha256

/** Player settings shared by Android and iOS. */
@Serializable
data class PlayerSettings(
    // Appearance
    val amoledMode: Boolean = false,
    val dynamicTheme: Boolean = true,
    val telemetryEnabled: Boolean = false,
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
    // Audio FX (Phase 5) - additive with safe defaults, old payloads stay valid
    val crossfadeMs: Long = 0L,                   // 0 = off, dip-style crossfade on auto advance
    val replayGainMode: Int = 0,                  // 0 off | 1 track | 2 album
    val replayGainPreampDb: Float = 0f,
    val replayGainLimiter: Boolean = true,
    // Sleep timer: minutes of the last active timer (0 = off); the live state
    // is persisted separately by SleepTimer so it survives crashes.
    val sleepTimerMinutes: Int = 0,
    // Diagnostics
    val hasSeenOnboarding: Boolean = false
)

/**
 * Existing settings document, with an opaque reference to a device-protected secret.
 * Explicit injection prevents accidental migration into an ephemeral default store.
 * Immutable credential generations preserve the old password if a settings commit fails.
 */
class SettingsRepository(
    private val store: KeyValueStore,
    private val secrets: SecureStorage
) {
    private val listeners = mutableListOf<(PlayerSettings) -> Unit>()
    private val writeLock = Mutex()
    private var credentialRef: String? = null
    private var garbage = emptySet<String>()
    private var pending = emptySet<String>()
    private val _state: MutableStateFlow<PlayerSettings>
    val state: StateFlow<PlayerSettings> get() = _state
    val settings: PlayerSettings get() = _state.value

    init {
        val raw = store.getString(KEY)
        val document = raw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val stored = document?.let { runCatching { json.decodeFromJsonElement(PlayerSettings.serializer(), it) }.getOrNull() }
            ?: PlayerSettings()
        pending = store.getString(PENDING)?.let { raw ->
            runCatching { json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrNull()
        }.orEmpty()
        credentialRef = (document?.get(REF) as? JsonPrimitive)?.contentOrNull
        garbage = (document?.get(GARBAGE) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        if (stored.subsonicPassword.isNotEmpty()) {
            // A locked Keychain/failed encryption must leave the legacy document recoverable.
            val ref = writeSecret(stored.subsonicPassword)
            val obsolete = garbage + listOfNotNull(credentialRef)
            persist(stored, ref, obsolete)
            garbage = obsolete
            credentialRef = ref
        } else if (pending.isNotEmpty() || garbage.isNotEmpty()) {
            // Re-establish durability before garbage collection after an interrupted commit.
            persist(stored, credentialRef, garbage)
        }
        _state = MutableStateFlow(stored.copy(subsonicPassword = credentialRef?.let(secrets::get).orEmpty()))
        cleanObsoleteSecrets()
    }

    fun update(block: (PlayerSettings) -> PlayerSettings) {
        check(writeLock.tryLock()) { "A settings update is already in progress" }
        val value: PlayerSettings
        try {
            val previous = settings
            value = block(previous)
            require(value.defaultPlaybackSpeed.isFinite() && value.defaultPlaybackSpeed in 0.25f..3f)
            val changed = value.subsonicPassword != previous.subsonicPassword ||
                value.subsonicServerUrl != previous.subsonicServerUrl || value.subsonicUsername != previous.subsonicUsername
            val nextRef = when {
                value.subsonicPassword.isEmpty() -> null
                !changed && credentialRef != null -> credentialRef
                else -> writeSecret(value.subsonicPassword)
            }
            val obsolete = garbage + listOfNotNull(credentialRef?.takeIf { it != nextRef })
            // Barrier BEFORE deletion: a crash can see either document, and its secret exists.
            persist(value, nextRef, obsolete)
            credentialRef = nextRef
            garbage = obsolete
            _state.value = value
            cleanObsoleteSecrets()
        } finally { writeLock.unlock() }
        listeners.toList().forEach { it(value) }
    }

    private fun writeSecret(password: String): String {
        val ref = "subsonic." + Sha256.hex(secureRandomBytes(16))
        // Journal intent before creating a secret, including the failure/crash window.
        val intentions = pending + ref
        store.putStringDurably(PENDING, JsonArray(intentions.map(::JsonPrimitive)).toString())
        pending = intentions
        secrets.put(ref, password)
        if (secrets.get(ref) != password) throw SecureStorageException("Credential write could not be verified")
        return ref
    }

    private fun persist(value: PlayerSettings, ref: String?, obsolete: Set<String>) {
        val fields = json.encodeToJsonElement(PlayerSettings.serializer(), value.copy(subsonicPassword = "")).jsonObject.toMutableMap()
        if (ref != null) fields[REF] = JsonPrimitive(ref)
        fields[GARBAGE] = JsonArray(obsolete.filterNot { it == ref }.map(::JsonPrimitive))
        store.putStringDurably(KEY, JsonObject(fields).toString())
    }

    private fun cleanObsoleteSecrets() {
        // Failed cleanup is retried on restart; it never rolls back a committed setting.
        garbage = garbage.filterNot { ref ->
            ref != credentialRef && runCatching { secrets.remove(ref) }.isSuccess
        }.toSet()
        if (pending.isEmpty()) return
        val remaining = pending.filter { ref ->
            ref != credentialRef && runCatching { secrets.remove(ref) }.isFailure
        }.toSet()
        // A failed journal cleanup is safe and idempotent on the next launch.
        if (runCatching {
            store.putStringDurably(PENDING, JsonArray(remaining.map(::JsonPrimitive)).toString())
        }.isSuccess) pending = remaining
    }

    fun addListener(listener: (PlayerSettings) -> Unit) { listeners.add(listener); listener(settings) }
    fun removeListener(listener: (PlayerSettings) -> Unit) { listeners.remove(listener) }

    private companion object {
        const val KEY = "echo.player.settings"
        const val REF = "_credentialRef"
        const val GARBAGE = "_obsoleteCredentialRefs"
        const val PENDING = "echo.player.credentials.pending"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
