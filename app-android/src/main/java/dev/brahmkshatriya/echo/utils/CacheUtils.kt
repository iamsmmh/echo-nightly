package dev.brahmkshatriya.echo.utils

import android.content.Context
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Simple JSON-file cache with validation (Phase 1 - stability).
 *
 * New entries are written as a versioned envelope carrying a timestamp so
 * readers can drop stale entries; unparseable entries are deleted on read
 * instead of silently failing or crashing downstream code. Payloads written
 * by older app versions (bare JSON) remain readable and are trusted.
 */
object CacheUtils {

    fun cacheDir(context: Context, folderName: String) =
        File(context.cacheDir, "context/$folderName").apply { mkdirs() }

    const val CACHE_FOLDER_SIZE = 50 * 1024 * 1024 //50MB

    /** Default freshness window for transient data (stream urls, artwork...). */
    const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L //6h

    @PublishedApi internal const val ENVELOPE_TIME = "_t"
    @PublishedApi internal const val ENVELOPE_DATA = "_d"

    inline fun <reified T> Context.saveToCache(
        id: String, data: T?, folderName: String = T::class.java.simpleName
    ) = runCatching {
        val directory = cacheDir(this, folderName)
        val encoded = wrap(data.toJson(), System.currentTimeMillis())
        if (encoded.encodeToByteArray().size > CACHE_FOLDER_SIZE) return@runCatching
        val file = File(directory, dev.brahmkshatriya.echo.player.domain.Sha256.digestHex(id))
        val temporary = File.createTempFile("echo-cache-", ".tmp", directory)
        try {
            temporary.writeText(encoded)
            check(temporary.renameTo(file)) { "Could not publish cache entry" }
        } finally { temporary.delete() }
        val files = directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        // A read-only cache file must not trap playback in an unbounded eviction loop.
        for (candidate in files) {
            if (size <= CACHE_FOLDER_SIZE) break
            val length = candidate.length()
            if (candidate.delete()) size -= length
        }
    }

    @PublishedApi
    internal fun storedFile(context: Context, id: String, folder: String): File {
        val directory = cacheDir(context, folder)
        val current = File(directory, dev.brahmkshatriya.echo.player.domain.Sha256.digestHex(id))
        return current.takeIf { it.exists() } ?: File(directory, id.hashCode().toString())
    }

    /** Supports old raw JSON and timestamp envelopes; new writes reuse the shared validator. */
    @PublishedApi
    internal fun payload(text: String, nowMs: Long, maxAgeMillis: Long): String? {
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (parsed != null && (parsed.containsKey("writtenAtMs") && parsed.containsKey("payload")))
            return dev.brahmkshatriya.echo.player.cache.CacheValidator.unwrap(text, nowMs, maxAgeMillis)
        if (parsed == null || !parsed.containsKey(ENVELOPE_TIME)) return text
        val timestamp = (parsed[ENVELOPE_TIME] as? JsonPrimitive)?.longOrNull ?: return null
        if (timestamp < 0 || timestamp > nowMs + 60_000 || nowMs - timestamp > maxAgeMillis) return null
        return (parsed[ENVELOPE_DATA] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * @param maxAgeMillis drop (and delete) the entry when it is older than
     * this; pass [Long.MAX_VALUE] to keep entries indefinitely.
     */
    inline fun <reified T> Context.getFromCache(
        id: String, folderName: String = T::class.java.simpleName,
        maxAgeMillis: Long = DEFAULT_TTL_MS
    ): T? {
        val file = storedFile(this, id, folderName)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrElse {
            file.delete()
            return null
        }
        val payload = payload(text, System.currentTimeMillis(), maxAgeMillis) ?: run {
            file.delete()
            return null
        }
        return runCatching { payload.toData<T>().getOrThrow() }.getOrElse {
            // Corrupted / outdated format: purge so we never serve garbage.
            runCatching { file.delete() }
            null
        }
    }

    /** Read the raw cached timestamp, if any (debug/metrics use). */
    fun Context.cacheTimestamp(id: String, folderName: String): Long? = runCatching {
        val file = storedFile(this, id, folderName)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as? JsonObject
        (parsed?.get("writtenAtMs") ?: parsed?.get(ENVELOPE_TIME))?.let {
            (it as? JsonPrimitive)?.longOrNull
        }
    }.getOrNull()

    // ---- internals (public-ish because inline functions reference them) ----

    fun wrap(json: String?, timestamp: Long): String =
        dev.brahmkshatriya.echo.player.cache.CacheValidator.wrap(json ?: "null", timestamp)

    inline fun <reified T> unwrap(text: String): T? =
        runCatching { text.toData<T>().getOrNull() }.getOrNull()
            ?.takeIf { it is JsonObject && (it.containsKey(ENVELOPE_TIME) || it.containsKey("writtenAtMs")) }

    fun String?.encodeJsonString(): String {
        if (this == null) return "null"
        val sb = StringBuilder(this.length + 2)
        sb.append('"')
        for (c in this) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }
}
