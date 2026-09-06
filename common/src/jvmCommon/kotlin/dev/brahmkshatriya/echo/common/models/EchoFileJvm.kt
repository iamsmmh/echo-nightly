package dev.brahmkshatriya.echo.common.models

/**
 * On JVM targets [EchoFile] is a transparent alias of `java.io.File`, which
 * keeps the published extension API source compatible with existing extensions.
 */
actual typealias EchoFile = java.io.File

actual fun EchoFile.bytes(): ByteArray = this.readBytes()

actual fun EchoFile.write(bytes: ByteArray) = this.writeBytes(bytes)

actual fun EchoFile.resolve(child: String): EchoFile = EchoFile(this.path, child)
