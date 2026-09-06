package dev.brahmkshatriya.echo.player

import androidx.compose.ui.window.ComposeUIViewController
import dev.brahmkshatriya.echo.player.audio.IosAudioPlayer
import dev.brahmkshatriya.echo.player.di.AppGraph
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.platform.HttpRequest
import dev.brahmkshatriya.echo.player.ui.EchoApp
import dev.brahmkshatriya.echo.player.ui.FileImports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.Foundation.NSLog
import platform.UIKit.UIViewController

/** NSLog based logger for iOS. Never logs credentials (see sanitizeUrl). */
class IosEchoLogger : EchoLogger {
    override fun debug(tag: String, message: String) {
        NSLog("ECHO D [%s] %s", tag, message.replace('%', '⁇'))
    }

    override fun info(tag: String, message: String) {
        NSLog("ECHO I [%s] %s", tag, message.replace('%', '⁚'))
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        NSLog("ECHO W [%s] %s %@", tag, message.replace('%', '⁚'), throwable?.toString() ?: "")
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        NSLog("ECHO E [%s] %s %@", tag, message.replace('%', '⁚'), throwable?.toString() ?: "")
    }
}

/** iOS application composition root. */
object IosApplication {

    lateinit var graph: AppGraph
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val logger = IosEchoLogger()

    val engine: IosAudioPlayer by lazy {
        IosAudioPlayer(
            logger = logger,
            artworkFetcher = { url, headers ->
                runCatching {
                    graph.http.send(HttpRequest(url = url, headers = headers, requestTimeoutMs = 15_000)).body
                }.getOrNull()
            },
            pauseOnRouteLoss = { graph.settings.settings.pauseOnHeadphonesDisconnected }
        )
    }

    fun create(): IosApplication {
        if (!::graph.isInitialized) {
            graph = AppGraph(scope = scope, logger = logger, engine = engine)
            // Select the offline extension by default on first launch
            if (graph.settings.settings.activeExtensionId == null) {
                graph.extensions.setActiveExtension(graph.defaultExtensionId())
            }
        }
        return this
    }
}

/** Compose Multiplatform entry point consumed by the SwiftUI host. */
fun MainViewController(): UIViewController {
    IosApplication.create()
    return ComposeUIViewController { EchoApp(IosApplication.graph) }
}

/**
 * Small, stable Swift-facing facade for host integration (file import, remote
 * control from widgets/shortcuts, diagnostics).
 */
object EchoIosBridge {

    /**
     * The shared graph, creating it on first use.
     *
     * The bridge is reachable from widgets, shortcuts and the XCTest host
     * before `MainViewController()` ever ran, so every entry point must not
     * assume [IosApplication.create] has already been called.
     */
    private val graph: AppGraph
        get() = IosApplication.create().graph

    /** Imports audio files picked with UIDocumentPickerViewController. */
    fun importFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        val library = graph.library
        val logger = graph.logger
        IosApplication.scope.launch {
            FileImports.importPickedFiles(library, logger, paths)
        }
    }

    fun playPause() = graph.player.playPause()

    fun next() = graph.player.next()

    fun previous() = graph.player.previous()

    fun seekTo(positionMs: Long) = graph.player.seekTo(positionMs)

    fun libraryCount(): Long = graph.library.tracks.value.size.toLong()

    fun activeExtensionId(): String? = graph.extensions.activeExtensionId

    /** The current AVAudioSession category (used by the iOS tests). */
    fun audioSessionCategory(): String {
        // Reading the graph makes sure the shared audio engine exists, which is
        // what configures the session for playback in the first place.
        IosApplication.create()
        return AVAudioSession.sharedInstance().category.toString()
    }
}

/* iOS UI actuals (document picker, artwork decoder) live in player/ui/IosUiActuals.kt */

