package dev.brahmkshatriya.echo.player.security

/** OS CSPRNG. Never fall back to a predictable generator on failure. */
expect fun secureRandomBytes(size: Int): ByteArray
