package dev.brahmkshatriya.echo.player.extensions.subsonic

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.player.core.lyrics.LrcParser
import dev.brahmkshatriya.echo.player.domain.runCatchingCancellable
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.player.library.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Echo extension for a user-configured Subsonic / OpenSubsonic server.
 * Streaming is direct (the player never requires a download first);
 * downloads use the same `stream` endpoint with the configured bitrate.
 */
class SubsonicExtensionClient(
    private val api: SubsonicApi,
    private val settingsRepository: SettingsRepository
) : ExtensionClient, HomeFeedClient, SearchFeedClient, AlbumClient, ArtistClient, TrackClient, LyricsClient {

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override fun setSettings(settings: Settings) {
        // no-op: configuration comes from the shared SettingsRepository
    }

    override suspend fun onExtensionSelected() {
        if (!api.isConfigured) {
            throw ClientException.NotSupported("Subsonic server is not configured")
        }
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val structured = runCatchingCancellable { api.structuredLyrics(track.id) }.getOrNull()
        val lyric = if (structured != null) {
            val lines = structured.line
            if (structured.synced && lines.all { it.start != null && it.start >= 0 && it.start <= 86_400_000 }) {
                val offset = structured.offset.coerceIn(-86_400_000, 86_400_000)
                val ordered = lines.sortedBy { it.start }
                Lyrics.Timed(ordered.mapIndexed { index, line ->
                    val start = (line.start!! + offset).coerceAtLeast(0)
                    val end = ordered.getOrNull(index + 1)?.start?.let { (it + offset).coerceAtLeast(start) }
                        ?: track.duration?.takeIf { it > start } ?: Long.MAX_VALUE
                    Lyrics.Item(line.value, start, end)
                })
            } else Lyrics.Simple(lines.joinToString("\n") { it.value })
        } else api.lyrics(track.artists.firstOrNull()?.name.orEmpty(), track.title)?.let { LrcParser.parse(it, track.duration) }
        return listOfNotNull(lyric?.let { Lyrics(track.id, track.title, lyrics = it) }).toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics

    // ------------------------------------------------------------ mappers

    private fun cover(coverArtId: String?): ImageHolder? =
        api.coverArtUrl(coverArtId)?.let { ImageHolder.NetworkRequestImageHolder(
            dev.brahmkshatriya.echo.common.models.NetworkRequest(it), crop = true
        ) }

    private fun ArtistDto.toItem() = Artist(
        id = id, name = name, cover = cover(coverArt)
    )

    private fun AlbumDto.toItem() = Album(
        id = id,
        title = name,
        cover = cover(coverArt),
        artists = artist?.let { listOf(Artist(id = artistId ?: it, name = it)) } ?: emptyList(),
        trackCount = songCount?.toLong(),
        duration = duration?.let { it * 1000L },
        releaseDate = year?.let { dev.brahmkshatriya.echo.common.models.Date(it) }
    )

    private fun AlbumWithSongsDto.toItem() = Album(
        id = id,
        title = name,
        cover = cover(coverArt),
        artists = artist?.let { listOf(Artist(id = artistId ?: it, name = it)) } ?: emptyList(),
        trackCount = song?.size?.toLong(),
        duration = song?.sumOf { it.duration ?: 0 }?.let { it * 1000L },
        releaseDate = year?.let { dev.brahmkshatriya.echo.common.models.Date(it) }
    )

    private fun ArtistWithAlbumsDto.toItem() = Artist(
        id = id, name = name, cover = cover(coverArt)
    )

    private fun SongDto.toTrack() = Track(
        id = id,
        title = title,
        cover = cover(coverArt ?: albumId),
        artists = listOfNotNull(artist?.let { Artist(id = artistId ?: it, name = it) }),
        album = album?.let { Album(id = albumId ?: it, title = it) },
        duration = duration?.let { it * 1000L },
        playedDuration = null,
        releaseDate = year?.let { dev.brahmkshatriya.echo.common.models.Date(it) },
        genres = listOfNotNull(genre),
        albumOrderNumber = track?.toLong(),
        extras = mapOf(
            "contentType" to (contentType ?: ""),
            "suffix" to (suffix ?: ""),
            "bitRate" to (bitRate?.toString() ?: "")
        )
    )

    // ------------------------------------------------------------ feeds

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val recent = api.albumList("recent")
        val frequent = api.albumList("frequent")
        val newest = api.albumList("newest")
        return listOfNotNull(
            shelf("Recently Played", recent.map { it.toItem() }),
            shelf("Newest Releases", newest.map { it.toItem() }),
            shelf("Most Played", frequent.map { it.toItem() })
        ).toFeed()
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) return emptyList<Shelf>().toFeed()
        val result = api.searchThree(query)
        return listOfNotNull(
            result.song?.takeIf { it.isNotEmpty() }?.let { shelf("Songs", it.map { s -> s.toTrack() }) },
            result.album?.takeIf { it.isNotEmpty() }?.let { shelf("Albums", it.map { a -> a.toItem() }) },
            result.artist?.takeIf { it.isNotEmpty() }?.let { shelf("Artists", it.map { a -> a.toItem() }) }
        ).toFeed()
    }

    // ------------------------------------------------------------ albums

    override suspend fun loadAlbum(album: Album): Album =
        api.getAlbum(album.id).toItem()

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val full = api.getAlbum(album.id)
        val tracks = (full.song ?: emptyList())
            .sortedWith(compareBy({ it.track ?: Int.MAX_VALUE }, { it.title.lowercase() }))
            .map { it.toTrack() }
        return Feed(listOf()) { PagedData.Single { tracks }.toFeedData() }
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // ------------------------------------------------------------ artists

    override suspend fun loadArtist(artist: Artist): Artist =
        api.getArtist(artist.id).toItem()

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val full = api.getArtist(artist.id)
        val albums = (full.album ?: emptyList()).map { it.toItem() }
        return listOf(shelf("Albums", albums)).toFeed()
    }

    // ------------------------------------------------------------ tracks

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        // Subsonic streams directly by id; expose one server streamable.
        return track.copy(
            streamables = listOf(
                Streamable(
                    id = track.id,
                    quality = track.extras["bitRate"]?.toIntOrNull() ?: 0,
                    type = Streamable.MediaType.Server,
                    title = "Subsonic stream"
                )
            )
        )
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val settings = settingsRepository.settings
        val bitrate = if (isDownload) settings.downloadMaxBitrateKbps else 0
        val format = if (isDownload) settings.transcodeFormat else "raw"
        val url = api.streamUrl(streamable.id, bitrate, format)
        return Streamable.Media.Server(
            sources = listOf(
                Streamable.Source.Http(
                    request = dev.brahmkshatriya.echo.common.models.NetworkRequest(url),
                    type = Streamable.SourceType.Progressive
                )
            ),
            merged = false
        )
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // ------------------------------------------------------------ helpers

    private fun shelf(title: String, items: List<EchoMediaItem>, grid: Boolean = false) =
        Shelf.Lists.Items(
            id = title.lowercase().replace(' ', '-'),
            title = title,
            list = items,
            type = if (grid) Shelf.Lists.Type.Grid else Shelf.Lists.Type.Linear
        )

    private fun List<Shelf>.toFeed(): Feed<Shelf> =
        Feed(listOf()) { PagedData.Single { this }.toFeedData() }

    companion object {
        const val ID = "subsonic"
        fun metadata() = dev.brahmkshatriya.echo.player.extensions.builtinMetadata(
            id = ID,
            name = "Subsonic Server",
            version = "1.0.0",
            description = "Stream from your own Subsonic/OpenSubsonic compatible server (Navidrome, Airsonic, ...)."
        )
    }
}

/** Builds the Subsonic extension wired to the shared settings. */
fun createSubsonicExtension(
    api: SubsonicApi,
    settingsRepository: SettingsRepository
) = dev.brahmkshatriya.echo.player.extensions.musicExtension(
    SubsonicExtensionClient.metadata()
) { SubsonicExtensionClient(api, settingsRepository) }
