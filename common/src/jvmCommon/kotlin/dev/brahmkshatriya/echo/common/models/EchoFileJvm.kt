package dev.brahmkshatriya.echo.common.models

import java.io.File

/**
 * On JVM targets [EchoFile] wraps a [java.io.File].
 *
 * (An `actual typealias` cannot satisfy open-val expects, and extending `File`
 * cannot `actual override` its synthetic Java properties — so this is a plain
 * wrapper. Extension code can reach the underlying file via [file].)
 */
actual class EchoFile actual constructor(path: String) {

    /** The wrapped [java.io.File]. */
    val file: File = File(path)

    actual val absolutePath: String get() = file.absolutePath

    actual val name: String get() = file.name

    actual val parent: String? get() = file.parent

    actual fun exists(): Boolean = file.exists()

    actual fun delete(): Boolean = file.delete()

    actual fun length(): Long = file.length()

    actual fun mkdirs(): Boolean = file.mkdirs()

    actual fun isDirectory(): Boolean = file.isDirectory

    override fun toString(): String = file.path
}

actual fun EchoFile.bytes(): ByteArray = file.readBytes()

actual fun EchoFile.write(bytes: ByteArray) = file.writeBytes(bytes)

actual fun EchoFile.resolve(child: String): EchoFile = EchoFile(File(file.path, child).path)
