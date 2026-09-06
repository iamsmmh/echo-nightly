package dev.brahmkshatriya.echo.player.security

/** Secrets are not settings. Platform stores must fail closed, never fall back to plaintext. */
interface SecureStorage {
    @Throws(SecureStorageException::class)
    fun get(key: String): String?
    /** Successful return means the write is durable. */
    @Throws(SecureStorageException::class)
    fun put(key: String, value: String)
    @Throws(SecureStorageException::class)
    fun remove(key: String)
}

class SecureStorageException(message: String) : IllegalStateException(message)

/** Ephemeral store for tests/previews. Production composition roots inject platform storage. */
class InMemorySecureStorage : SecureStorage {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
