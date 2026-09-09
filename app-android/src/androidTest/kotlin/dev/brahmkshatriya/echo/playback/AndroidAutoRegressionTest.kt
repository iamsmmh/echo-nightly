package dev.brahmkshatriya.echo.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAutoRegressionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun mediaBrowserTreeRootIsEmptyWithoutExtensions() {
        val root = MediaBrowserTree.buildRoot(context, emptyList())
        assertTrue(root.isEmpty())
    }

    @Test
    fun mediaBrowserTreeRootHasABrowsableNodePerExtension() {
        val root = MediaBrowserTree.buildRoot(context, listOf("ext-a", "ext-b"))
        assertEquals(2, root.size)
        assertTrue(root.all { it.mediaMetadata.isBrowsable == true })
        assertTrue(root.all { it.mediaId.startsWith("${MediaBrowserTree.ROOT}/") })
    }

    @Test
    fun mediaBrowserTreeSearchAndVoiceQueriesShareBrowsableBranches() {
        val search = MediaBrowserTree.buildSearchBranch(context, "night")
        assertEquals(3, search.size)
        assertTrue(search.all { it.mediaMetadata.isBrowsable == true })
        val ids = search.map { it.mediaId }
        assertTrue(ids.any { it.contains(MediaBrowserTree.SEARCH) && it.contains("artists") })
        assertTrue(ids.any { it.contains("albums") })
        assertTrue(ids.any { it.contains("tracks") })

        val voice = MediaBrowserTree.buildVoiceQuery(context, "night")
        assertEquals(search.map { it.mediaId }, voice.map { it.mediaId })
    }

    @Test
    fun mediaBrowserTreeArtistAlbumAndPlaylistBranchesAreBrowsable() {
        val artist = MediaBrowserTree.buildArtistBranch(context, "a1", "Nina")
        val album = MediaBrowserTree.buildAlbumBranch(context, "al1", "Blue")
        val playlist = MediaBrowserTree.buildPlaylistBranch(context, "p1", "Late")
        assertEquals(2, artist.size)
        assertEquals(1, album.size)
        assertEquals(1, playlist.size)
        assertTrue((artist + album + playlist).all { it.mediaMetadata.isBrowsable == true })
    }
}
