package dev.brahmkshatriya.echo.player.ui

import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.library.LocalLibraryRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Cross-platform file import bridge. The UI calls [open] to launch the
 * platform document picker (iOS: UIDocumentPickerViewController for audio
 * files, Files app; Android: the shipped Echo app imports via its own offline
 * extension and MediaStore).
 *
 * Picked files are announced on [pickedPaths] and imported by
 * [importPickedFiles].
 */
object FileImports {

    private val _pickedPaths = MutableSharedFlow<List<String>>(extraBufferCapacity = 1)
    val pickedPaths: SharedFlow<List<String>> = _pickedPaths

    private val _requestOpen = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestOpen: SharedFlow<Unit> = _requestOpen

    fun open() {
        _requestOpen.tryEmit(Unit)
    }

    fun emitPicked(paths: List<String>) {
        _pickedPaths.tryEmit(paths)
    }

    /** Imports a batch of picked file paths into the local library. */
    suspend fun importPickedFiles(library: LocalLibraryRepository, logger: EchoLogger, paths: List<String>): Int {
        var imported = 0
        paths.forEach { path ->
            library.import(path)
                .onSuccess { imported++ }
                .onFailure { logger.error("FileImport", "Failed to import $path: ${it.message}", null) }
        }
        return imported
    }
}

/** Launches the platform file picker for audio files. */
expect fun platformOpenFilePicker()
