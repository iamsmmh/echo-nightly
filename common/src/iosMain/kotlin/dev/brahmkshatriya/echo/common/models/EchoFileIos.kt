package dev.brahmkshatriya.echo.common.models

import dev.brahmkshatriya.echo.common.helpers.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
actual class EchoFile actual constructor(path: String) {
    private val filePath: String = path

    private val manager: NSFileManager
        get() = NSFileManager.defaultManager

    actual val absolutePath: String
        get() = if (filePath.startsWith("/")) filePath else NSTemporaryDirectory() + filePath

    actual val name: String
        get() = filePath.substringAfterLast('/')

    actual val parent: String?
        get() = filePath.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { null }

    actual fun exists(): Boolean = manager.fileExistsAtPath(filePath)

    actual fun delete(): Boolean = manager.removeItemAtPath(filePath, error = null)

    actual fun length(): Long {
        val attributes = manager.attributesOfItemAtPath(filePath, error = null) ?: return 0L
        val size = attributes[NSFileSize] as? NSNumber ?: return 0L
        return size.longValue
    }

    actual fun mkdirs(): Boolean = manager.createDirectoryAtPath(
        filePath,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )

    actual fun isDirectory(): Boolean = runCatching {
        // Avoids the BOOL* out-param (ObjCBooleanVar mapping differences)
        val attributes = manager.attributesOfItemAtPath(filePath, error = null)
        val type = attributes?.get(NSFileType) as? String
        type == NSFileTypeDirectory
    }.getOrDefault(false)
}

@OptIn(ExperimentalForeignApi::class)
actual fun EchoFile.bytes(): ByteArray {
    val data = NSFileManager.defaultManager.contentsAtPath(absolutePath) ?: return ByteArray(0)
    return data.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
actual fun EchoFile.write(bytes: ByteArray) {
    val data = NSMutableData()
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            data.appendBytes(pinned.addressOf(0), bytes.size.toULong())
        }
    }
    data.writeToFile(absolutePath, atomically = true)
}

actual fun EchoFile.resolve(child: String): EchoFile = EchoFile("$absolutePath/$child")
