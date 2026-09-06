package dev.brahmkshatriya.echo.common.helpers

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.*
import platform.posix.memcpy

/**
 * Copies the contents of an [NSData] into a Kotlin [ByteArray].
 */
@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { array ->
        array.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this@toByteArray.bytes, this.length)
        }
    }
}

/**
 * Wraps a Kotlin [ByteArray] into an immutable [NSData] copy.
 */
@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSMutableData()
    val data = NSMutableData()
    usePinned { pinned ->
        data.appendBytes(pinned.addressOf(0), size.toULong())
    }
    return data
}
