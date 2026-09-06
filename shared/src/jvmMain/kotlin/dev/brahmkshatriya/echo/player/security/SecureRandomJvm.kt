package dev.brahmkshatriya.echo.player.security

private val random = java.security.SecureRandom()
actual fun secureRandomBytes(size: Int): ByteArray {
    require(size in 0..1_048_576)
    return ByteArray(size).also(random::nextBytes)
}
