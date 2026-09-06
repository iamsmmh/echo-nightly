package dev.brahmkshatriya.echo.player.platform

import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.player.domain.Sha256
import java.io.RandomAccessFile

/**
 * Cheap prefix read (magic-byte sniffing) without loading a whole file.
 * Returns at most [count] bytes; empty array on I/O failure.
 */
actual fun EchoFile.readPrefix(count: Int): ByteArray = runCatching {
    RandomAccessFile(this, "r").use { raf ->
        val n = minOf(count.toLong(), raf.length()).toInt()
        val buffer = ByteArray(n)
        raf.readFully(buffer)
        buffer
    }
}.getOrElse { ByteArray(0) }

/**
 * Streams the file through SHA-256 in chunks so even huge downloads never have
 * to be held in memory. @return lowercase hex digest or `null` on I/O failure.
 */
actual fun EchoFile.sha256Hex(chunkSize: Int): String? = runCatching {
    val streaming = Sha256.Streaming()
    RandomAccessFile(this, "r").use { raf ->
        val buffer = ByteArray(chunkSize)
        var remaining = raf.length()
        while (remaining > 0) {
            val want = minOf(chunkSize.toLong(), remaining).toInt()
            val read = raf.read(buffer, 0, want)
            if (read <= 0) break
            streaming.update(buffer, 0, read)
            remaining -= read
        }
    }
    Sha256.hex(streaming.finish())
}.getOrNull()
