@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.echo.player.platform

import dev.brahmkshatriya.echo.common.helpers.toByteArray
import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.player.domain.Sha256
import platform.Foundation.*

/** Chunked reads keep hashing independent of file size. */
actual fun EchoFile.readPrefix(count: Int): ByteArray {
    require(count >= 0) { "count must not be negative" }
    val handle = NSFileHandle.fileHandleForReadingAtPath(absolutePath) ?: return ByteArray(0)
    return try {
        handle.readDataOfLength(count.toULong()).toByteArray()
    } finally {
        handle.closeFile()
    }
}

actual fun EchoFile.sha256Hex(chunkSize: Int): String? {
    require(chunkSize > 0) { "chunkSize must be positive" }
    val handle = NSFileHandle.fileHandleForReadingAtPath(absolutePath) ?: return null
    return try {
        val streaming = Sha256.Streaming()
        while (true) {
            val data = handle.readDataOfLength(chunkSize.toULong())
            if (data.length == 0uL) break
            streaming.update(data.toByteArray())
        }
        Sha256.hex(streaming.finish())
    } finally {
        handle.closeFile()
    }
}
