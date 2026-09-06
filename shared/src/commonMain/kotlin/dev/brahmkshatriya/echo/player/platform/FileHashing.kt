package dev.brahmkshatriya.echo.player.platform

import dev.brahmkshatriya.echo.common.models.EchoFile

/**
 * Platform file helpers used by cache/download integrity checks. Kept in the
 * shared module (not the published `:common` API) so the extension contract
 * stays frozen while the app can still verify file integrity cheaply.
 */

/** Reads the first [count] bytes of the file (magic-byte sniffing). */
expect fun EchoFile.readPrefix(count: Int): ByteArray

/**
 * Streams the file through SHA-256 in chunks.
 * @return the lowercase hex digest, or `null` when the file cannot be read.
 */
expect fun EchoFile.sha256Hex(chunkSize: Int = 64 * 1024): String?
