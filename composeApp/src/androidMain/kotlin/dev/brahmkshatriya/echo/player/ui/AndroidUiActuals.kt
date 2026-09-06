package dev.brahmkshatriya.echo.player.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory

/**
 * Android file import entry.
 *
 * On Android, the shipped Echo app already imports the device library through
 * its own Offline extension (MediaStore based, no file picker needed) — that
 * flow keeps working unchanged. When the shared Compose UI is hosted on
 * Android, a host activity can push picked SAF paths directly with
 * [FileImports.emitPicked]; no system picker is launched from library code.
 */
actual fun platformOpenFilePicker() {
    // Deliberately no-op on Android: SAF pickers require a host Activity.
    // Hosts call FileImports.emitPicked(paths) with the picked URIs instead.
    // The library-import flow on Android is the Offline extension.
}

/** BitmapFactory based artwork decoder. */
class AndroidArtworkDecoder : ArtworkDecoder {
    override fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()?.asImageBitmap()
}

actual fun createArtworkDecoder(): ArtworkDecoder = AndroidArtworkDecoder()
