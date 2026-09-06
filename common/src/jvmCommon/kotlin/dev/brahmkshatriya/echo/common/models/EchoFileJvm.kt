package dev.brahmkshatriya.echo.common.models

/**
 * On JVM targets [EchoFile] is a transparent alias of `java.io.File`, which
 * keeps the published extension API source compatible with existing extensions.
 */
actual class EchoFile actual constructor(path: String) : java.io.File(path) {
    // Inherited Java synthetic properties do not satisfy expect members —
    // they must be declared explicitly as actual overrides.
    actual override val absolutePath: String get() = super.getAbsolutePath()
    actual override val name: String get() = super.getName()
    actual override val parent: String? get() = super.getParent()
}

actual fun EchoFile.bytes(): ByteArray = this.readBytes()

actual fun EchoFile.write(bytes: ByteArray) = this.writeBytes(bytes)

actual fun EchoFile.resolve(child: String): EchoFile = EchoFile(java.io.File(path, child).path)
