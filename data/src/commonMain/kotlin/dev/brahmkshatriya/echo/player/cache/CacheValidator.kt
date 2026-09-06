package dev.brahmkshatriya.echo.player.cache

import dev.brahmkshatriya.echo.player.domain.Sha256
import dev.brahmkshatriya.echo.player.domain.nowEpochMs
import dev.brahmkshatriya.echo.player.platform.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cache validation (Phase 1 - stability) for every JSON entry persisted
 * through the shared [KeyValueStore] (stream resolutions, browse pages,
 * artwork colors...).
 *
 * Entries are stored inside an envelope that carries the write timestamp and a
 * SHA-256 over the payload, so the readers can (a) expire stale entries and
 * (b) refuse to decode corrupted/truncated data - which previously surfaced as
 * random `SerializationException` crashes deep inside the UI.
 */
object CacheValidator {

    /** Payload + timestamp + integrity hash. */
    @Serializable
    internal data class Envelope(
        val version: Int = CURRENT_VERSION,
        val writtenAtMs: Long,
        val sha256: String,
        val payload: String
    )

    const val CURRENT_VERSION = 2

    /** 6h freshness for transient entries (stream urls may expire). */
    const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = Envelope.serializer()

    fun wrap(payloadJson: String, nowMs: Long = nowEpochMs()): String =
        json.encodeToString(
            serializer,
            Envelope(
                writtenAtMs = nowMs,
                sha256 = Sha256.digestHex(payloadJson),
                payload = payloadJson
            )
        )

    /**
     * @return the payload when the entry is present, uncorrupted, at the
     * current envelope version and younger than [ttlMs]; `null` otherwise -
     * callers should treat that as a cache miss and re-fetch.
     */
    fun unwrap(raw: String?, nowMs: Long = nowEpochMs(), ttlMs: Long = DEFAULT_TTL_MS): String? {
        if (raw == null) return null
        val envelope = runCatching { json.decodeFromString(serializer, raw) }
            .getOrNull() ?: return null
        if (envelope.version != CURRENT_VERSION) return null
        if (envelope.sha256 != Sha256.digestHex(envelope.payload)) return null
        val age = nowMs - envelope.writtenAtMs
        if (age > ttlMs) return null
        // Clock skew on the device must not poison the cache forever.
        if (envelope.writtenAtMs > nowMs + 60_000) return null
        return envelope.payload
    }

    /** True when the raw value parses as a current envelope (migration check). */
    fun isCurrentFormat(raw: String?): Boolean = unwrap(raw, ttlMs = Long.MAX_VALUE) != null
}

/**
 * A tiny typed wrapper over [KeyValueStore] that only ever returns validated,
 * fresh JSON payloads. Used by the KMP resolution pipeline and (on Android) as
 * the reference implementation mirrored by `CacheUtils`.
 */
class ResolutionCache(
    private val store: KeyValueStore,
    private val prefix: String = "echo.cache.",
    private val ttlMs: Long = CacheValidator.DEFAULT_TTL_MS
) {

    fun put(key: String, payloadJson: String) {
        store.putString(prefix + key, CacheValidator.wrap(payloadJson))
    }

    /** @return the cached payload or `null` (miss / stale / corrupted). */
    fun get(key: String): String? {
        val raw = store.getString(prefix + key) ?: return null
        val payload = CacheValidator.unwrap(raw, ttlMs = ttlMs)
        if (payload == null) store.remove(prefix + key) // purge garbage
        return payload
    }

    fun remove(key: String) = store.remove(prefix + key)
}
