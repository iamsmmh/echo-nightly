package dev.brahmkshatriya.echo.player.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.*
import platform.darwin.NSObject

/**
 * iOS actuals for the shared UI platform hooks: the artwork decoder and the
 * document picker used for local file import.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun platformOpenFilePicker() {
    val picker = UIDocumentPickerViewController(
        documentTypes = listOf("public.audio"),
        inMode = UIDocumentPickerMode.UIDocumentPickerModeImport
    )
    picker.allowsMultipleSelection = true
    val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(
            controller: UIDocumentPickerViewController,
            didPickDocumentsAtURLs: List<*>
        ) {
            val paths = didPickDocumentsAtURLs.mapNotNull { (it as? NSURL)?.path }
            if (paths.isNotEmpty()) FileImports.emitPicked(paths)
            activePickerDelegate = null
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            activePickerDelegate = null
        }
    }
    activePickerDelegate = delegate
    picker.delegate = delegate
    rootViewController()?.presentViewController(picker, animated = true, completion = null)
}

private fun rootViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    application.keyWindow?.let { return it.rootViewController }
    val scene = application.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull() ?: return null
    // UIWindowScene.keyWindow is available on iOS 15+
    return scene.keyWindow?.rootViewController
}

actual fun createArtworkDecoder(): ArtworkDecoder = IosArtworkDecoder()

// UIDocumentPicker holds its delegate weakly; retain it until completion.
private var activePickerDelegate: UIDocumentPickerDelegateProtocol? = null

class IosArtworkDecoder : ArtworkDecoder {
    override fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}
