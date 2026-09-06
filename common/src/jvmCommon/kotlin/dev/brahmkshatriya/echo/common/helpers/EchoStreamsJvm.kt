package dev.brahmkshatriya.echo.common.helpers

import java.io.InputStream

/**
 * Wraps a `java.io.InputStream` into the platform independent [EchoByteStream].
 */
fun InputStream.toEchoByteStream(): EchoByteStream = object : EchoByteStream {
    override fun read(buffer: ByteArray): Int = this@toEchoByteStream.read(buffer)
    override fun close() = this@toEchoByteStream.close()
}

/**
 * Wraps an [EchoByteStream] into a `java.io.InputStream`.
 */
fun EchoByteStream.toInputStream(): InputStream = object : InputStream() {
    override fun read(): Int {
        val buffer = ByteArray(1)
        val read = this@toInputStream.read(buffer)
        return if (read <= 0) -1 else buffer[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (off == 0 && len == buffer.size) return this@toInputStream.read(buffer)
        val scratch = ByteArray(len)
        val read = this@toInputStream.read(scratch)
        if (read <= 0) return read
        System.arraycopy(scratch, 0, buffer, off, read)
        return read
    }

    override fun close() = this@toInputStream.close()
}

/**
 * Calls [InputProvider.provide] and adapts the resulting [EchoByteStream]
 * into a `java.io.InputStream` while keeping the stream's total length.
 *
 * This is the JVM friendly bridge used by the player's raw data source.
 */
suspend fun dev.brahmkshatriya.echo.common.models.Streamable.InputProvider
    .provideAsInputStream(position: Long, length: Long): Pair<InputStream, Long> {
    val (stream, total) = provide(position, length)
    return stream.toInputStream() to total
}
