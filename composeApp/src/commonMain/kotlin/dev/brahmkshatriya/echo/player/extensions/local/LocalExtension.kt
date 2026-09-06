package dev.brahmkshatriya.echo.player.extensions.local

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.player.library.AlbumGroup
import dev.brahmkshatriya.echo.player.library.LocalLibraryRepository
import dev.brahmkshatriya.echo.player.extensions.builtinMetadata
import kotlinx.coroutines.flow.first

/**
 * Built-in offline extension exposing the imported local music library
 * through Echo's standard extension API. Works identically on Android and iOS.
 */
class LocalExtensionClient(
    private val library: LocalLibraryRepository
) : ExtensionClient, HomeFeedClient, LibraryFeedClient, SearchFeedClient,
    AlbumClient, ArtistClient, TrackClient {

    private var settings: Settings? = null

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    // ------------------------------------------------------------ feed

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tracks = library.tracks.first()
        val recent = tracks.sortedByDescending { it.addedAtMs }.take(20)
        return listOf(
            Shelf.Lists.Items(
                id = "recently_added",
                title = "Recently Added",
                list = recent.map { library.asTrack(it) },
                type = Shelf.Lists.Type.Linear
            ),
            Shelf.Lists.Items(
                id = "albums",
                title = "Albums",
                list = library.albums().map { it.toAlbumItem() },
                type = Shelf.Lists.Type.Grid
            )
        ).toShelves().toFeed()
    }

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val tracks = library.tracks.first()
        return listOf(
            Shelf.Lists.Items(
                id = "songs",
                title = "Songs",
                list = tracks.map { library.asTrack(it) },
                type = Shelf.Lists.Type.Linear
            ),
            Shelf.Lists.Items(
                id = "albums",
                title = "Albums",
                list = library.albums().map { it.toAlbumItem() },
                type = Shelf.Lists.Type.Linear
            ),
            Shelf.Lists.Items(
                id = "artists",
                title = "Artists",
                list = library.artists().map { it.toArtistItem() },
                type = Shelf.Lists.Type.Linear
            )
        ).toShelves().toFeed()
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val results = library.search(query)
        return listOf(
            Shelf.Lists.Items(
                id = "songs",
                title = "Songs",
                list = results.map { library.asTrack(it) },
                type = Shelf.Lists.Type.Linear
            ),
            Shelf.Lists.Items(
                id = "albums",
                title = "Albums",
                list = library.albums().filter { album ->
                    results.any { it.album == album.title } || album.title.contains(query, ignoreCase = true)
                }.map { it.toAlbumItem() },
                type = Shelf.Lists.Type.Linear
            )
        ).toShelves().toFeed()
    }

    // ------------------------------------------------------------ items

    override suspend fun loadAlbum(album: Album): Album {
        val group = library.albums().firstOrNull {
            it.title.equals(album.title, ignoreCase = true) || it.title == album.title
        } ?: throw dev.brahmkshatriya.echo.common.helpers.ClientException.NotSupported("Album not found")
        return group.toAlbumItem()
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val group = library.albums().firstOrNull { it.title == album.title } ?: return null
        val tracks = library.albumTracks(group)
        return Feed(listOf()) { PagedData.Single { tracks }.toFeedData() }
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    override suspend fun loadArtist(artist: Artist): Artist {
        val group = library.artists().firstOrNull { it.name == artist.name }
            ?: throw dev.brahmkshatriya.echo.common.helpers.ClientException.NotSupported("Artist not found")
        return group.toArtistItem()
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val group = library.artists().firstOrNull { it.name == artist.name }
        return if (group == null) emptyList<Shelf>().toFeed()
        else listOf(
            Shelf.Lists.Items(
                id = "tracks",
                title = "Songs",
                list = group.tracks.map { library.asTrack(it) },
                type = Shelf.Lists.Type.Linear
            )
        ).toShelves().toFeed()
    }

    // ------------------------------------------------------------ tracks

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val entry = library.find(track.id)
            ?: track.extras["localPath"]?.let { path -> library.tracks.value.firstOrNull { it.path == path } }
            ?: return track
        return library.asTrack(entry).withServers()
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val path = streamable.extras["path"]
            ?: streamable.id.takeIf { it.startsWith("/") }
            ?: throw dev.brahmkshatriya.echo.common.helpers.ClientException.NotSupported("No local path for streamable")
        val url = if (path.startsWith("/")) "file://$path" else path
        return url.toSource().let { it.toMedia() }
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // ------------------------------------------------------------ helpers

    private fun AlbumGroup.toAlbumItem(): Album {
        val artistName = artist
        return Album(
            id = "album:$artistName:$title",
            title = title,
            cover = tracks.firstNotNullOfOrNull { it.artworkPath?.let { p -> uri(p) } },
            artists = listOf(Artist(id = "artist:$artistName", name = artistName)),
            trackCount = tracks.size.toLong(),
            duration = durationMs
        )
    }

    private fun dev.brahmkshatriya.echo.player.library.ArtistGroup.toArtistItem(): Artist =
        Artist(
            id = "artist:$name",
            name = name,
            cover = tracks.firstNotNullOfOrNull { it.artworkPath?.let { p -> uri(p) } }
        )

    private fun uri(path: String) =
        dev.brahmkshatriya.echo.common.models.ImageHolder.ResourceUriImageHolder(
            "file://" + path.replace(" ", "%20"),
            crop = true
        )

    private fun Track.withServers(): Track {
        val path = extras["localPath"] ?: return this
        return copy(
            streamables = listOf(
                Streamable(
                    id = path,
                    quality = 0,
                    type = Streamable.MediaType.Server,
                    title = "Local file",
                    extras = mapOf("path" to path)
                )
            )
        )
    }

    private fun List<Shelf>.toShelves(): List<Shelf> = this

    private fun List<Shelf>.toFeed(): Feed<Shelf> =
        Feed(listOf()) { PagedData.Single { this }.toFeedData() }

    companion object {
        const val ID = "local-offline"
        fun metadata() = builtinMetadata(
            id = ID,
            name = "Offline Library",
            version = "1.0.0",
            description = "Plays audio files imported into Echo from your device."
        )
    }
}

/** Assembles the local library extension. */
fun createLocalExtension(library: LocalLibraryRepository) =
    dev.brahmkshatriya.echo.player.extensions.musicExtension(
        LocalExtensionClient.metadata()
    ) { LocalExtensionClient(library) }
