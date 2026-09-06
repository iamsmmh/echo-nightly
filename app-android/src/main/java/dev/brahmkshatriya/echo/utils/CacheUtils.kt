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

    private const val ENVELOPE_TIME = "_t"
    private const val ENVELOPE_DATA = "_d"

    inline fun <reified T> Context.saveToCache(
        id: String, data: T?, folderName: String = T::class.java.simpleName
    ) = runCatching {
        val fileName = id.hashCode().toString()
        val cacheDir = cacheDir(this, folderName)
        val file = File(cacheDir, fileName)

        var size = cacheDir.walk().sumOf { it.length().toInt() }
        while (size > CACHE_FOLDER_SIZE) {
            val files = cacheDir.listFiles()
            files?.sortBy { it.lastModified() }
            files?.firstOrNull()?.delete()
            size = cacheDir.walk().sumOf { it.length().toInt() }
        }
        file.writeText(wrap(data.toJson(), System.currentTimeMillis()))
    }

    /**
     * @param maxAgeMillis drop (and delete) the entry when it is older than
     * this; pass [Long.MAX_VALUE] to keep entries indefinitely.
     */
    inline fun <reified T> Context.getFromCache(
        id: String, folderName: String = T::class.java.simpleName,
        maxAgeMillis: Long = DEFAULT_TTL_MS
    ): T? {
        val fileName = id.hashCode().toString()
        val cacheDir = cacheDir(this, folderName)
        val file = File(cacheDir, fileName)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrElse {
            file.delete()
            return null
        }
        val payload = when (val parsed = unwrap<JsonObject>(text)) {
            // Envelope written by this version: honour the timestamp.
            null -> text
            else -> {
                val timestamp = parsed[ENVELOPE_TIME]?.let {
                    (it as? JsonPrimitive)?.longOrNull
                }
                if (timestamp == null) text
                else if (System.currentTimeMillis() - timestamp > maxAgeMillis) {
                    file.delete()
                    return null
                } else parsed[ENVELOPE_DATA]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: text
            }
        }
        return runCatching { payload.toData<T>().getOrThrow() }.getOrElse {
            // Corrupted / outdated format: purge so we never serve garbage.
            runCatching { file.delete() }
            null
        }
    }

    /** Read the raw cached timestamp, if any (debug/metrics use). */
    fun Context.cacheTimestamp(id: String, folderName: String): Long? = runCatching {
        val file = File(cacheDir(this, folderName), id.hashCode().toString())
        unwrap<JsonObject>(file.readText())?.get(ENVELOPE_TIME)?.let {
            (it as? JsonPrimitive)?.longOrNull
        }
    }.getOrNull()

    // ---- internals (public-ish because inline functions reference them) ----

    fun wrap(json: String?, timestamp: Long): String =
        "{\"$ENVELOPE_TIME\":$timestamp,\"$ENVELOPE_DATA\":${json.encodeJsonString()}}"

    inline fun <reified T> unwrap(text: String): T? =
        runCatching { text.toData<T>().getOrNull() }.getOrNull()
            ?.takeIf { it is JsonObject && (it as JsonObject).containsKey(ENVELOPE_TIME) }

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
