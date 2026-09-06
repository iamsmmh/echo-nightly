package dev.brahmkshatriya.echo.player.extensions

import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.helpers.Injectable
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.player.audio.QueueItem
import dev.brahmkshatriya.echo.player.audio.ResolvedStream
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.library.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A built-in extension factory registered per platform. */
fun interface BuiltinExtensionFactory {
    fun create(settings: SettingsRepository): MusicExtension
}

/** Builds the standard extension metadata for a built-in extension. */
fun builtinMetadata(
    id: String,
    name: String,
    version: String,
    description: String,
    type: ExtensionType = ExtensionType.MUSIC
) = Metadata(
    className = "builtin",
    path = "builtin",
    importType = ImportType.BuiltIn,
    type = type,
    id = id,
    name = name,
    version = version,
    description = description,
    author = "Echo",
    repoUrl = "https://github.com/iamsmmh/echo-nightly",
    isEnabled = true
)

/** Creates a [MusicExtension] with a lazily constructed client. */
fun musicExtension(
    metadata: Metadata,
    clientProvider: () -> ExtensionClient
): MusicExtension = MusicExtension(metadata, Injectable(clientProvider, emptyList()))

/**
 * The cross-platform extension runtime.
 *
 * Echo's extension architecture is preserved: the player core never talks to a
 * hard-coded music provider, it talks to the [Extension] API from `:common`.
 *
 *  - On Android, the shipped Echo app additionally loads DEX/APK extensions at
 *    runtime (see `app/.../extensions/repo/DexLoader.kt`); this module hosts
 *    the same API for the shared Compose UI.
 *  - On iOS, dynamic code loading is not permitted by the platform, so the
 *    runtime registers built-in extensions: the local/offline extension and
 *    the Subsonic extension (user's own server).
 *
 * Extension management: enable/disable and selection are persisted through
 * [SettingsRepository]; every call is wrapped with structured error mapping.
 */
class ExtensionRuntime(
    factories: List<BuiltinExtensionFactory>,
    private val settings: SettingsRepository,
    private val logger: EchoLogger
) {

    data class RegisteredExtension(
        val extension: MusicExtension,
        val enabled: Boolean
    )

    private val registry: List<MusicExtension> =
        factories.map { it.create(settings) }

    private val _extensions = MutableStateFlow<List<RegisteredExtension>>(emptyList())
    val extensions: StateFlow<List<RegisteredExtension>> = _extensions.asStateFlow()

    private val disabledIds = mutableSetOf<String>()

    init {
        settings.settings.disabledExtensions.forEach { disabledIds.add(it) }
        publish()
    }

    val activeExtensionId: String?
        get() = settings.settings.activeExtensionId ?: firstEnabled()?.extension?.id

    fun activeExtension(): MusicExtension? {
        val wanted = activeExtensionId ?: return null
        return _extensions.value.firstOrNull { it.extension.id == wanted && it.enabled }?.extension
    }

    fun setActiveExtension(id: String) {
        val known = _extensions.value.any { it.extension.id == id }
        if (!known) {
            logger.warn(TAG, "Tried to select unknown extension $id")
            return
        }
        settings.update { it.copy(activeExtensionId = id) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val current = disabledIds.toMutableSet()
        if (enabled) current.remove(id) else current.add(id)
        clearDisabled(current)
        settings.update { it.copy(disabledExtensions = current) }
        publish()
    }

    fun isEnabled(id: String): Boolean = id !in disabledIds

    private fun clearDisabled(set: MutableSet<String>) {
        disabledIds.clear()
        disabledIds.addAll(set)
    }

    private fun firstEnabled() = _extensions.value.firstOrNull { it.enabled }

    private fun publish() {
        _extensions.value = registry.map { ext ->
            RegisteredExtension(ext, ext.id !in disabledIds)
        }
    }

    /**
     * Runs [block] against the active extension's client, mapping every
     * failure into a structured [EchoError.Extension].
     */
    suspend fun <T> withActiveClient(block: suspend (ExtensionClient) -> T): Result<T> {
        val extension = activeExtension()
            ?: return Result.failure(EchoError.Extension("No music extension selected"))
        return runCatching {
            val client = extension.instance.value().getOrNull()
                ?: throw EchoError.Extension("Could not initialize ${extension.name}", extension.id)
            try {
                block(client)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: EchoError) {
                throw EchoError.Extension(e.message, extension.id, e)
            } catch (e: Throwable) {
                throw EchoError.Extension(e.message ?: "Extension call failed", extension.id, e)
            }
        }
    }

    fun extensionFor(id: String): MusicExtension? =
        _extensions.value.firstOrNull { it.extension.id == id }?.extension

    /** Lists extensions supporting searching (for the search aggregator). */
    fun searchableExtensions(): List<MusicExtension> =
        _extensions.value.filter { it.enabled }.map { it.extension }

    companion object {
        private const val TAG = "ExtensionRuntime"

        /**
         * Converts a media item's id into the extension id that produced it.
         * Queue items carry the extension id explicitly.
         */
        fun extensionIdOf(item: QueueItem): String = item.extensionId
    }
}

/** Marker type used by [ResolvedStream.Source.EXTENSION] producers. */
val EchoMediaItem.isPlayableItem: Boolean
    get() = this is dev.brahmkshatriya.echo.common.models.Track
