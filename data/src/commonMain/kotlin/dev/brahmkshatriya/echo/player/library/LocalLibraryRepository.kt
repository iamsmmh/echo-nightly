package dev.brahmkshatriya.echo.player.library

import dev.brahmkshatriya.echo.common.models.resolve
import dev.brahmkshatriya.echo.common.models.write

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.platform.MetadataReader
import dev.brahmkshatriya.echo.player.platform.MusicStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One indexed local audio file in the imported library. */
@Serializable
data class LocalTrackEntry(
    val id: String,
    val path: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
    val artworkPath: String?,
    val mimeType: String?,
    val addedAtMs: Long
) {
    val trackKey: String get() = "local::$id"
}

/** A grouping of local tracks by album name. */
data class AlbumGroup(
    val title: String,
    val artist: String,
    val year: Int?,
    val tracks: List<LocalTrackEntry>
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs ?: 0 }
}

/** A grouping of local tracks by artist name. */
data class ArtistGroup(
    val name: String,
    val tracks: List<LocalTrackEntry>
)

/**
 * The imported local music library: files copied into [MusicStorage.musicDir]
 * by the platform file importer, indexed as JSON.
 *
 * All grouping and search logic lives here in common code and is unit tested.
 */
class LocalLibraryRepository(
    private val storage: MusicStorage,
    private val metadataReader: MetadataReader,
    private val logger: EchoLogger,
    private val store: dev.brahmkshatriya.echo.player.platform.KeyValueStore
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val trackListSerializer =
        kotlinx.serialization.builtins.ListSerializer(LocalTrackEntry.serializer())

    private val _tracks = MutableStateFlow<List<LocalTrackEntry>>(emptyList())
    val tracks: StateFlow<List<LocalTrackEntry>> = _tracks.asStateFlow()

    var revision: Long = 0
        private set

    init {
        load()
    }

    fun find(id: String): LocalTrackEntry? = _tracks.value.firstOrNull { it.id == id }

    fun search(query: String): List<LocalTrackEntry> {
        if (query.isBlank()) return _tracks.value
        val q = query.trim().lowercase()
        return _tracks.value.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album?.lowercase()?.contains(q) == true
        }
    }

    fun albums(): List<AlbumGroup> = groupAlbums(_tracks.value)

    fun artists(): List<ArtistGroup> = groupArtists(_tracks.value)

    /**
     * Imports a file that has already been copied into app accessible storage
     * at [sourcePath]: reads metadata, extracts artwork, moves the file into
     * the music directory and indexes it.
     */
    suspend fun import(sourcePath: String): Result<LocalTrackEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val source = EchoFile(sourcePath)
            if (!source.exists()) throw EchoError.Storage("Import file does not exist: $sourcePath")
            val meta = runCatching { metadataReader.read(sourcePath) }
                .getOrElse { e ->
                    logger.warn(TAG, "Could not read metadata of $sourcePath", e)
                    dev.brahmkshatriya.echo.player.platform.AudioMetadata()
                }
            val safeName = source.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val target = storage.copyInto(sourcePath, storage.musicDir, safeName)
            val id = stableId(target.absolutePath)

            val artworkPath = meta.artworkBytes?.let { bytes ->
                val ext = if (meta.artworkIsPng) "png" else "jpg"
                val file = storage.cacheDir.resolve("artwork").also { it.mkdirs() }.resolve("$id.$ext")
                runCatching { file.write(bytes) }
                    .onFailure { logger.warn(TAG, "Could not persist artwork for $id", it) }
                    .getOrNull()?.let { file.absolutePath }
            }

            val entry = LocalTrackEntry(
                id = id,
                path = target.absolutePath,
                fileName = target.name,
                title = meta.title ?: source.name.substringBeforeLast('.'),
                artist = meta.artist?.ifBlank { null } ?: UNKNOWN_ARTIST,
                album = meta.album?.ifBlank { null },
                albumArtist = meta.albumArtist?.ifBlank { null },
                genre = meta.genre?.ifBlank { null },
                year = meta.year,
                trackNumber = meta.trackNumber,
                durationMs = meta.durationMs,
                artworkPath = artworkPath,
                mimeType = meta.mimeType,
                addedAtMs = now()
            )
            val list = _tracks.value.toMutableList()
            list.removeAll { it.id == id || it.path == entry.path }
            list.add(entry)
            persistAndPublish(list)
            source.delete()
            logger.info(TAG, "Imported ${entry.title} (${entry.fileName})")
            entry
        }
    }

    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        val entry = find(id) ?: return@withContext false
        runCatching {
            EchoFile(entry.path).delete()
            entry.artworkPath?.let { EchoFile(it).delete() }
        }.onFailure { logger.warn(TAG, "Failed deleting files of $id", it) }
        persistAndPublish(_tracks.value.filterNot { it.id == id })
        true
    }

    fun asTrack(entry: LocalTrackEntry): Track {
        val artworkHolder = entry.artworkPath?.let {
            ImageHolder.ResourceUriImageHolder("file://${it.replace(" ", "%20")}", crop = true)
        }
        return Track(
            id = entry.id,
            title = entry.title,
            cover = artworkHolder,
            artists = listOf(Artist(id = "artist:${entry.artist}", name = entry.artist)),
            album = entry.album?.let { albumName ->
                Album(
                    id = "album:${entry.albumArtist ?: entry.artist}:$albumName",
                    title = albumName,
                    artists = listOf(Artist(id = "artist:${entry.albumArtist ?: entry.artist}", name = entry.albumArtist ?: entry.artist))
                )
            },
            duration = entry.durationMs,
            genres = listOfNotNull(entry.genre),
            releaseDate = entry.year?.let { dev.brahmkshatriya.echo.common.models.Date(it) },
            albumOrderNumber = entry.trackNumber?.toLong(),
            extras = mapOf(
                "localPath" to entry.path,
                "mimeType" to (entry.mimeType ?: ""),
                dev.brahmkshatriya.echo.player.domain.search.SearchProvenance.PROVIDER_ID to "local-offline"
            )
        )
    }

    fun albumTracks(group: AlbumGroup): List<Track> =
        sortAlbumTracks(group.tracks).map { asTrack(it) }

    // -------------------------------------------------------------- internals

    private fun load() {
        val raw = store.getString(KEY_INDEX) ?: return
        runCatching { json.decodeFromString(trackListSerializer, raw) }
            .onSuccess { _tracks.value = it }
            .onFailure {
                logger.error(TAG, "Corrupt local library index; starting fresh", it)
                store.remove(KEY_INDEX)
            }
    }

    private fun persistAndPublish(list: List<LocalTrackEntry>) {
        store.putString(KEY_INDEX, json.encodeToString(trackListSerializer, list))
        _tracks.value = list
        revision++
    }

    private companion object {
        const val TAG = "LocalLibrary"
        const val KEY_INDEX = "echo.player.local.library"
        const val UNKNOWN_ARTIST = "Unknown Artist"

        fun stableId(path: String): String =
            path.hashCode().toUInt().toString(16) + "-" + path.length.toString(16) + "-" +
                (path.substringAfterLast('/').hashCode().toUInt().toString(16))

        fun now(): Long = dev.brahmkshatriya.echo.player.domain.nowEpochMs()
    }
}

/** Groups entries into albums, ignoring case and blank albums. */
fun groupAlbums(entries: List<LocalTrackEntry>): List<AlbumGroup> {
    return entries
        .groupBy { (it.album ?: it.fileName).lowercase().trim() to (it.albumArtist ?: it.artist).lowercase().trim() }
        .map { (_, groupTracks) ->
            val sorted = sortAlbumTracks(groupTracks)
            AlbumGroup(
                title = sorted.first().album ?: sorted.first().fileName.substringBeforeLast('.'),
                artist = sorted.first().albumArtist ?: sorted.first().artist,
                year = sorted.first().year,
                tracks = sorted
            )
        }
        .sortedBy { it.title.lowercase() }
}

/** Groups entries into artists. */
fun groupArtists(entries: List<LocalTrackEntry>): List<ArtistGroup> {
    return entries
        .groupBy { it.artist.lowercase().trim() }
        .map { (name, groupTracks) ->
            ArtistGroup(name = groupTracks.first().artist, tracks = groupTracks.sortedBy { it.title.lowercase() })
        }
        .sortedBy { it.name.lowercase() }
}

/** Sorts tracks by disc/track number, falling back to title. */
internal fun sortAlbumTracks(tracks: List<LocalTrackEntry>): List<LocalTrackEntry> =
    tracks.sortedWith(compareBy({ it.trackNumber ?: Int.MAX_VALUE }, { it.title.lowercase() }))
