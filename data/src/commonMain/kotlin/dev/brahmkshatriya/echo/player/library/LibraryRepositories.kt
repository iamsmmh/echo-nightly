package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.player.platform.KeyValueStore
import dev.brahmkshatriya.echo.player.domain.playlists.SmartPlaylist
import dev.brahmkshatriya.echo.player.domain.playlists.SmartPlaylistEngine
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Local playlist repository: create, rename, delete, add/remove/reorder
 * tracks. Persisted as JSON in a [KeyValueStore].
 */
class PlaylistRepository(private val store: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(UserPlaylist.serializer())

    private val _playlists = kotlinx.coroutines.flow.MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: kotlinx.coroutines.flow.StateFlow<List<UserPlaylist>> = _playlists

    private val smartSerializer = ListSerializer(SmartPlaylist.serializer())
    private val _smartPlaylists = kotlinx.coroutines.flow.MutableStateFlow<List<SmartPlaylist>>(emptyList())
    val smartPlaylists: kotlinx.coroutines.flow.StateFlow<List<SmartPlaylist>> = _smartPlaylists

    fun saveSmart(playlist: SmartPlaylist) {
        SmartPlaylistEngine(::now).validate(playlist)
        val next = _smartPlaylists.value.filterNot { it.id == playlist.id } + playlist
        store.putString(SMART_KEY, json.encodeToString(smartSerializer, next))
        _smartPlaylists.value = next
    }

    fun deleteSmart(id: String) {
        val next = _smartPlaylists.value.filterNot { it.id == id }
        store.putString(SMART_KEY, json.encodeToString(smartSerializer, next))
        _smartPlaylists.value = next
    }

    init {
        load()
        _smartPlaylists.value = store.getString(SMART_KEY)?.let {
            runCatching { json.decodeFromString(smartSerializer, it) }.getOrNull()
        }.orEmpty().filter { runCatching { SmartPlaylistEngine(::now).validate(it) }.isSuccess }
    }

    fun create(name: String): UserPlaylist {
        require(name.isNotBlank()) { "Playlist name must not be empty" }
        val playlist = UserPlaylist(
            id = newId(),
            name = name.trim(),
            tracks = emptyList(),
            createdAtMs = now(),
            updatedAtMs = now()
        )
        persist(_playlists.value + playlist)
        return playlist
    }

    fun rename(id: String, name: String): Boolean {
        if (name.isBlank()) return false
        val list = _playlists.value
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return false
        persist(list.mapIndexed { i, p -> if (i == index) p.copy(name = name.trim(), updatedAtMs = now()) else p })
        return true
    }

    fun delete(id: String): Boolean {
        val list = _playlists.value
        if (list.none { it.id == id }) return false
        persist(list.filterNot { it.id == id })
        return true
    }

    fun addTracks(id: String, tracks: List<TrackRef>): Boolean {
        val list = _playlists.value
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return false
        persist(list.mapIndexed { i, p ->
            if (i == index) {
                // append while avoiding duplicates
                val existing = p.tracks.map { it.key }.toSet()
                p.copy(tracks = p.tracks + tracks.filterNot { it.key in existing }.distinctBy { it.key }, updatedAtMs = now())
            } else p
        })
        return true
    }

    fun removeTrack(id: String, trackKey: String): Boolean {
        val list = _playlists.value
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return false
        persist(list.mapIndexed { i, p ->
            if (i == index) {
                // Accept either the composite `extensionId::trackId` key or the
                // bare track id, so callers do not need to know the extension.
                val matches = p.tracks.filterNot { it.key == trackKey || it.trackId == trackKey }
                p.copy(tracks = matches, updatedAtMs = now())
            } else p
        })
        return true
    }

    fun moveTrack(id: String, from: Int, to: Int): Boolean {
        val list = _playlists.value
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return false
        persist(list.mapIndexed { i, p ->
            if (i == index) {
                val tracks = p.tracks.toMutableList()
                if (from in tracks.indices && to in tracks.indices && from != to) {
                    tracks.add(to, tracks.removeAt(from))
                    p.copy(tracks = tracks, updatedAtMs = now())
                } else p
            } else p
        })
        return true
    }

    fun get(id: String): UserPlaylist? = _playlists.value.firstOrNull { it.id == id }

    // -------------------------------------------------------------- internals

    private fun load() {
        val raw = store.getString(KEY) ?: return
        runCatching { json.decodeFromString(listSerializer, raw) }
            .onSuccess { _playlists.value = it.sortedBy { it.name.lowercase() } }
            .onFailure { store.remove(KEY) }
    }

    private fun persist(list: List<UserPlaylist>) {
        _playlists.value = list.sortedBy { it.name.lowercase() }
        store.putString(KEY, json.encodeToString(listSerializer, _playlists.value))
    }

    private fun newId(): String =
        dev.brahmkshatriya.echo.player.domain.nowEpochMs().toString(16) +
            "-" + kotlin.random.Random.nextInt(0xFFFF).toString(16)

    private fun now(): Long = dev.brahmkshatriya.echo.player.domain.nowEpochMs()

    private companion object {
        const val KEY = "echo.player.playlists"
        const val SMART_KEY = "echo.player.smart-playlists.v1"
    }
}

/** Favorite tracks (starred), shared by both platforms. */
class FavoritesRepository(private val store: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(TrackRef.serializer())

    private val _favorites = kotlinx.coroutines.flow.MutableStateFlow<List<TrackRef>>(emptyList())
    val favorites: kotlinx.coroutines.flow.StateFlow<List<TrackRef>> = _favorites

    init {
        val raw = store.getString(KEY)
        if (raw != null) {
            runCatching { json.decodeFromString(listSerializer, raw) }
                .onSuccess { _favorites.value = it }
                .onFailure { store.remove(KEY) }
        }
    }

    fun isFavorite(trackKey: String): Boolean = _favorites.value.any { it.key == trackKey }

    fun toggle(ref: TrackRef): Boolean {
        val list = _favorites.value
        val next = if (isFavorite(ref.key)) list.filterNot { it.key == ref.key } else list + ref
        _favorites.value = next
        store.putString(KEY, json.encodeToString(listSerializer, next))
        return next.any { it.key == ref.key }
    }

    private companion object {
        const val KEY = "echo.player.favorites"
    }
}

/** Recently played history, most recent first, capped to [LIMIT]. */
class HistoryRepository(
    private val store: KeyValueStore,
    private val now: () -> Long = { dev.brahmkshatriya.echo.player.domain.nowEpochMs() }
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(HistoryEntry.serializer())

    private val statsSerializer = kotlinx.serialization.builtins.MapSerializer(
        String.serializer(), ListeningStats.serializer()
    )
    private val _stats = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ListeningStats>>(emptyMap())
    val stats: kotlinx.coroutines.flow.StateFlow<Map<String, ListeningStats>> = _stats

    private val _history = kotlinx.coroutines.flow.MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: kotlinx.coroutines.flow.StateFlow<List<HistoryEntry>> = _history

    init {
        val raw = store.getString(KEY)
        if (raw != null) {
            runCatching { json.decodeFromString(listSerializer, raw) }
                .onSuccess { _history.value = it }
                .onFailure { store.remove(KEY) }
        }
    }

    init {
        val persisted = store.getString(STATS_KEY)?.let { runCatching { json.decodeFromString(statsSerializer, it) }.getOrNull() }
        // Old recent history has one retained entry per track. Counts before this
        // migration are unknowable; seed one known listen, not invented totals.
        _stats.value = persisted ?: _history.value.associate {
            it.ref.key to ListeningStats(1, 0, it.playedAtMs, it.msPlayed.coerceAtLeast(0))
        }
    }

    fun record(ref: TrackRef, msPlayed: Long) {
        val timestamp = now()
        val old = _stats.value[ref.key] ?: ListeningStats()
        _stats.value = _stats.value + (ref.key to old.copy(
            playCount = (old.playCount + 1).coerceAtLeast(old.playCount),
            lastPlayedAtMs = timestamp,
            totalPlayedMs = old.totalPlayedMs + msPlayed.coerceAtLeast(0)
        ))
        store.putString(STATS_KEY, json.encodeToString(statsSerializer, _stats.value))
        val entry = HistoryEntry(ref, timestamp, msPlayed.coerceAtLeast(0))
        _history.value = (listOf(entry) + _history.value)
            .distinctBy { it.ref.key }
            .take(LIMIT)
        store.putString(KEY, json.encodeToString(listSerializer, _history.value))
    }

    fun recordCompletion(ref: TrackRef, msPlayed: Long) {
        val old = _stats.value[ref.key] ?: ListeningStats()
        _stats.value = _stats.value + (ref.key to old.copy(
            completedCount = old.completedCount + 1,
            totalPlayedMs = old.totalPlayedMs + msPlayed.coerceAtLeast(0)
        ))
        store.putString(STATS_KEY, json.encodeToString(statsSerializer, _stats.value))
    }

    fun clear() {
        _history.value = emptyList()
        store.remove(KEY)
        _stats.value = emptyMap()
        store.remove(STATS_KEY)
    }

    private companion object {
        const val STATS_KEY = "echo.player.listening.stats.v1"
        const val KEY = "echo.player.history"
        const val LIMIT = 200
    }
}
