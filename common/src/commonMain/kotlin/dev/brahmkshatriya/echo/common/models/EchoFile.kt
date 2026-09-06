package dev.brahmkshatriya.echo.common.models

/**
 * A platform independent file handle used by the extension and download APIs.
 *
 * On JVM based targets (Android, desktop) this is a transparent
 * `actual typealias` to `java.io.File`, so existing Echo extensions keep
 * compiling without any change. On iOS it wraps an absolute file system path.
 *
 * @param path The path of the file.
 */
expect class EchoFile(path: String) {

    /** The absolute path of this file. */
    val absolutePath: String

    /** The name of the file or directory denoted by this path. */
    val name: String

    /** The parent path, or `null` if this path has no parent. */
    val parent: String?

    /** @return `true` if the file exists. */
    fun exists(): Boolean

    /** Deletes the file. @return `true` if successful. */
    fun delete(): Boolean

    /** @return the length of the file in bytes, or `0` if unknown. */
    fun length(): Long

    /** Creates the directory including any missing parents. @return `true` if successful. */
    fun mkdirs(): Boolean

    /** @return `true` if this path denotes an existing directory. */
    fun isDirectory(): Boolean
}

/** Reads the whole file into a byte array. */
expect fun EchoFile.bytes(): ByteArray

/** Writes the byte array to this file. */
expect fun EchoFile.write(bytes: ByteArray)

/** @return a new [EchoFile] resolved against the parent of this file. */
expect fun EchoFile.resolve(child: String): EchoFile
