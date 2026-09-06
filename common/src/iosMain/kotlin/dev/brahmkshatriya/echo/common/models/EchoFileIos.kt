package dev.brahmkshatriya.echo.common.models

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCBooleanVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableData
import platform.Foundation.NSNumber
import platform.Foundation.NSFileSize
import platform.Foundation.NSTemporaryDirectory

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

    actual fun isDirectory(): Boolean = memScoped {
        val isDir = alloc<ObjCBooleanVar>()
        val exists = manager.fileExistsAtPath(filePath, isDirectory = isDir.ptr)
        exists && isDir.value
    }
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
