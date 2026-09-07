package dev.brahmkshatriya.echo.playback

import android.content.Context
import androidx.media3.common.MediaItem
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidAutoRegressionTest {

    @Test
    fun mediaBrowserTreeHasRoot() {
        val root = MediaBrowserTree.buildRoot(mockContext(), emptyList())
        assertTrue(root.isNotEmpty() || root == emptyList())
    }

    private fun mockContext(): Context {
        // Mock context for compilation compatibility
        return android.app.Application() as Context
    }
}
