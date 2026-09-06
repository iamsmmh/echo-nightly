package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.player.platform.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A lightweight serializable reference to a track from any extension. */
@Serializable
data class TrackRef(
    val extensionId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null
) {
    val key: String get() = "$extensionId::$trackId"
}

/** A user created playlist stored locally (shared by both platforms). */
@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val tracks: List<TrackRef>,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/**
 * Local playlist repository: create, rename, delete, add/remove/reorder
 * tracks. Persisted as JSON in a [KeyValueStore].
 */
class PlaylistRepository(private val store: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(UserPlaylist.serializer())

    private val _playlists = kotlinx.coroutines.flow.MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: kotlinx.coroutines.flow.StateFlow<List<UserPlaylist>> = _playlists

    init {
        load()
    }

    fun create(name: String): UserPlaylist {
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
                p.copy(tracks = p.tracks + tracks.filterNot { it.key in existing }, updatedAtMs = now())
            } else p
        })
        return true
    }

    fun removeTrack(id: String, trackKey: String): Boolean {
        val list = _playlists.value
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return false
        persist(list.mapIndexed { i, p ->
            if (i == index) p.copy(tracks = p.tracks.filterNot { it.key == trackKey }, updatedAtMs = now()) else p
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

/** A history entry of a played track. */
@Serializable
data class HistoryEntry(
    val ref: TrackRef,
    val playedAtMs: Long,
    val msPlayed: Long
)

/** Recently played history, most recent first, capped to [LIMIT]. */
class HistoryRepository(private val store: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(HistoryEntry.serializer())

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

    fun record(ref: TrackRef, msPlayed: Long) {
        val entry = HistoryEntry(ref, now(), msPlayed)
        _history.value = (listOf(entry) + _history.value)
            .distinctBy { it.ref.key }
            .take(LIMIT)
        store.putString(KEY, json.encodeToString(listSerializer, _history.value))
    }

    fun clear() {
        _history.value = emptyList()
        store.remove(KEY)
    }

    private fun now(): Long = dev.brahmkshatriya.echo.player.domain.nowEpochMs()

    private companion object {
        const val KEY = "echo.player.history"
        const val LIMIT = 200
    }
}
