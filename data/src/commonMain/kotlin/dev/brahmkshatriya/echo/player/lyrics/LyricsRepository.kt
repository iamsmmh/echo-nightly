package dev.brahmkshatriya.echo.player.lyrics

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.cache.CacheValidator
import dev.brahmkshatriya.echo.player.domain.Sha256
import dev.brahmkshatriya.echo.player.domain.nowEpochMs
import dev.brahmkshatriya.echo.player.domain.runCatchingCancellable
import dev.brahmkshatriya.echo.player.platform.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class LyricsRequest(val extensionId: String, val track: Track, val sourceIdentity: String = "")
fun interface LyricsProvider { suspend fun load(request: LyricsRequest): Lyrics? }

/** Validated offline cache; no negative caching and no network work in offline mode. */
class LyricsRepository(
    private val store: KeyValueStore,
    private val provider: LyricsProvider,
    private val now: () -> Long = ::nowEpochMs,
    private val maxEntries: Int = 128
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val keysSerializer = ListSerializer(String.serializer())
    init { require(maxEntries in 1..1024) }

    suspend fun get(request: LyricsRequest, offlineOnly: Boolean = false): Lyrics? {
        val cached = mutex.withLock { read(request) }
        if (cached != null || offlineOnly) return cached
        val loaded = runCatchingCancellable { provider.load(request) }.getOrNull() ?: return null
        if (loaded.lyrics != null) runCatchingCancellable { save(request, loaded) }
        return loaded
    }

    suspend fun save(request: LyricsRequest, lyrics: Lyrics) = mutex.withLock {
        require(lyrics.lyrics != null)
        val payload = json.encodeToString(Lyrics.serializer(), lyrics)
        require(payload.length <= 262_144) { "Lyrics exceed cache size limit" }
        val key = key(request)
        val previous = store.getString(INDEX)?.let { runCatching { json.decodeFromString(keysSerializer, it) }.getOrNull() }.orEmpty()
        store.putString(key, CacheValidator.wrap(payload, now()))
        val keys = (previous.filterNot { it == key } + key)
        keys.dropLast(maxEntries).forEach(store::remove)
        store.putString(INDEX, json.encodeToString(keysSerializer, keys.takeLast(maxEntries)))
    }

    suspend fun remove(request: LyricsRequest) = mutex.withLock { store.remove(key(request)) }

    private fun read(request: LyricsRequest): Lyrics? {
        val key = key(request)
        val raw = store.getString(key) ?: return null
        // Song lyrics do not expire offline. Corruption/version checks still apply.
        val payload = CacheValidator.unwrap(raw, now(), Long.MAX_VALUE)
        val parsed = payload?.let { runCatching { json.decodeFromString(Lyrics.serializer(), it) }.getOrNull() }
        if (parsed?.lyrics == null) store.remove(key)
        return parsed?.takeIf { it.lyrics != null }
    }

    private fun key(request: LyricsRequest) = PREFIX + Sha256.digestHex(request.extensionId + "\u0000" + request.sourceIdentity + "\u0000" + request.track.id)
    private companion object { const val PREFIX = "echo.lyrics.v1."; const val INDEX = "echo.lyrics.index.v1" }
}
