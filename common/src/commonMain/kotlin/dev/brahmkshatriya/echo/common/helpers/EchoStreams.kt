package dev.brahmkshatriya.echo.common.helpers

/**
 * A minimal, platform independent byte stream abstraction used by
 * [dev.brahmkshatriya.echo.common.models.Streamable.InputProvider].
 *
 * On JVM based targets (Android, desktop) it can be converted to and from a
 * `java.io.InputStream` with [toEchoByteStream] / [toInputStream] which are
 * provided by the `jvmCommon` source set of this library.
 */
interface EchoByteStream {

    /**
     * Reads up to [buffer].size bytes into the buffer.
     *
     * @return the number of bytes read, or `-1` if the end of the stream has been reached.
     */
    fun read(buffer: ByteArray): Int

    /**
     * Closes the stream and releases any underlying resources.
     */
    fun close()
}

/**
 * An [EchoByteStream] backed by an in-memory byte array. Mostly useful for
 * tests and for small payloads on platforms without a file/stream API.
 */
class ByteBufferStream(
    private val data: ByteArray,
    private val startOffset: Int = 0
) : EchoByteStream {

    private var position = startOffset.coerceIn(0, data.size)
    private var closed = false

    override fun read(buffer: ByteArray): Int {
        check(!closed) { "stream is closed" }
        if (position >= data.size) return -1
        val count = minOf(buffer.size, data.size - position)
        for (i in 0 until count) {
            buffer[i] = data[position + i]
        }
        position += count
        return count
    }

    override fun close() {
        closed = true
    }
}
