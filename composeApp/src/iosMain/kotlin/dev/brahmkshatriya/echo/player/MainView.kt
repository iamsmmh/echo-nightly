package dev.brahmkshatriya.echo.player

import androidx.compose.ui.window.ComposeUIViewController
import dev.brahmkshatriya.echo.player.audio.IosAudioPlayer
import dev.brahmkshatriya.echo.player.di.AppGraph
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.platform.HttpRequest
import dev.brahmkshatriya.echo.player.ui.ArtworkDecoder
import dev.brahmkshatriya.echo.player.ui.EchoApp
import dev.brahmkshatriya.echo.player.ui.FileImports
import dev.brahmkshatriya.echo.player.audio.IosArtworkDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSLog
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerModeImport
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject

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

    /** Imports audio files picked with UIDocumentPickerViewController. */
    fun importFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        val graph = IosApplication.graph
        IosApplication.scope.launch {
            FileImports.importPickedFiles(graph.library, graph.logger, paths)
        }
    }

    fun playPause() = IosApplication.graph.player.playPause()

    fun next() = IosApplication.graph.player.next()

    fun previous() = IosApplication.graph.player.previous()

    fun seekTo(positionMs: Long) = IosApplication.graph.player.seekTo(positionMs)

    fun libraryCount(): Long = IosApplication.graph.library.tracks.value.size.toLong()

    fun activeExtensionId(): String? = IosApplication.graph.extensions.activeExtensionId

    /** The current AVAudioSession category (used by the iOS tests). */
    fun audioSessionCategory(): String =
        platform.AVFoundation.AVAudioSession.sharedInstance().category
}

/** Presents the iOS document picker (Files app) for audio import. */
actual fun platformOpenFilePicker() {
    val picker = UIDocumentPickerViewController(
        documentTypes = listOf("public.audio"),
        inMode = UIDocumentPickerModeImport
    )
    picker.allowsMultipleSelection = true
    picker.delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(
            controller: UIDocumentPickerViewController,
            didPickDocumentsAtURLs: List<*>
        ) {
            val paths = didPickDocumentsAtURLs.mapNotNull { (it as? NSURL)?.path }
            if (paths.isNotEmpty()) FileImports.emitPicked(paths)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            // no-op
        }
    }
    rootViewController()?.presentViewController(picker, animated = true, completion = null)
}

private fun rootViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    application.keyWindow?.let { return it.rootViewController }
    val scene = application.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == platform.UIKit.UIWindowSceneActivationStateForegroundActive }
        ?: return null
    return scene.windows.firstOrNull { it.isKeyWindow }?.rootViewController
}

actual fun createArtworkDecoder(): ArtworkDecoder = IosArtworkDecoder()
