package dev.brahmkshatriya.echo.player.platform

import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.player.domain.Sha256
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle

/**
 * iOS implementations of the file sniffing/hashing helpers built on
 * [NSFileHandle] so large downloads are read in chunks.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun EchoFile.readPrefix(count: Int): ByteArray {
    val handle = NSFileHandle.fileHandleForReadingAtPath(absolutePath) ?: return ByteArray(0)
    return try {
        toByteArray(handle.readDataOfLength(count.toULong()))
    } catch (e: Throwable) {
        ByteArray(0)
    } finally {
        runCatching { handle.closeFile() }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun EchoFile.sha256Hex(chunkSize: Int): String? {
    val handle = NSFileHandle.fileHandleForReadingAtPath(absolutePath) ?: return null
    return try {
        val streaming = Sha256.Streaming()
        while (true) {
            val data = handle.readDataOfLength(chunkSize.toULong())
            if (data.length.toLong() == 0L) break
            streaming.update(toByteArray(data))
        }
        Sha256.hex(streaming.finish())
    } catch (e: Throwable) {
        null
    } finally {
        runCatching { handle.closeFile() }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun toByteArray(data: NSData): ByteArray {
    val length = data.length.toInt()
    if (length == 0) return ByteArray(0)
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        data.getBytes(pinned.addressOf(0), data.length)
    }
    return bytes
}
