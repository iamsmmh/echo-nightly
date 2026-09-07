package dev.brahmkshatriya.echo.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * MediaBrowserTree for Android Auto: generates browsable tree nodes
 * for artists, albums, playlists, search results, and voice queries.
 */
object MediaBrowserTree {

    const val ROOT = "root"
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val PLAYLIST = "playlist"
    const val SEARCH = "search"
    const val RADIO = "radio"

    fun buildRoot(context: Context, extensions: List<Any>): List<MediaItem> =
        extensions.map { ext ->
            browsableItem(
                id = "$ROOT/${(ext as? Any)?.toString() ?: "ext"}",
                title = "Extension",
                browsable = true,
                type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
        }

    fun buildArtistBranch(context: Context, artistId: String, artistName: String): List<MediaItem> =
        listOf(
            browsableItem(
                id = "$ARTIST/$artistId/albums",
                title = "Albums by $artistName",
                browsable = true,
                type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            ),
            browsableItem(
                id = "$ARTIST/$artistId/tracks",
                title = "Tracks by $artistName",
                browsable = true,
                type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
        )

    fun buildAlbumBranch(context: Context, albumId: String, albumTitle: String): List<MediaItem> =
        listOf(
            browsableItem(
                id = "$ALBUM/$albumId/tracks",
                title = "Tracks in $albumTitle",
                browsable = true,
                type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
        )

    fun buildPlaylistBranch(context: Context, playlistId: String, playlistTitle: String): List<MediaItem> =
        listOf(
            browsableItem(
                id = "$PLAYLIST/$playlistId/tracks",
                title = "Tracks in $playlistTitle",
                browsable = true,
                type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
        )

    fun buildSearchBranch(context: Context, query: String): List<MediaItem> =
        listOf(
            browsableItem(
                id = "$SEARCH/$query/artists",
                title = "Artists for \"$query\"",
                subtitle = query,
                browsable = true,
                type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            ),
            browsableItem(
                id = "$SEARCH/$query/albums",
                title = "Albums for \"$query\"",
                subtitle = query,
                browsable = true,
                type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            ),
            browsableItem(
                id = "$SEARCH/$query/tracks",
                title = "Tracks for \"$query\"",
                subtitle = query,
                browsable = true,
                type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
        )

    fun buildVoiceQuery(context: Context, query: String): List<MediaItem> =
        buildSearchBranch(context, query)

    private fun browsableItem(
        id: String,
        title: String,
        subtitle: String? = null,
        browsable: Boolean = true,
        artWorkUri: android.net.Uri? = null,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    ) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsPlayable(false)
                .setIsBrowsable(browsable)
                .setMediaType(type)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artWorkUri)
                .build()
        )
        .build()
}
