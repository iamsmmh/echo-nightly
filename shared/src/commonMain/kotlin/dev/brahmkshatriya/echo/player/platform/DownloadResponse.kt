package dev.brahmkshatriya.echo.player.platform

import dev.brahmkshatriya.echo.player.domain.EchoError

fun Map<String, String>.header(name: String): String? = entries.firstOrNull { it.key.equals(name, true) }?.value

/** Validated transport framing; a 206 alone is never proof of a safe append. */
data class DownloadResponsePlan(
    val append: Boolean,
    val offset: Long,
    val expectedBodyBytes: Long,
    val totalBytes: Long
) {
    fun verifyBodySize(bytes: Long) {
        if (expectedBodyBytes >= 0 && expectedBodyBytes != bytes) {
            throw EchoError.Network("Incomplete download response")
        }
    }
}

fun validateDownloadResponse(
    status: Int,
    headers: Map<String, String>,
    resumeFrom: Long,
    requestedRange: String? = null
): DownloadResponsePlan {
    require(resumeFrom >= 0)
    if (status !in setOf(200, 206)) throw EchoError.Network("Download request failed", status)
    val encoding = headers.header("Content-Encoding")
    if (encoding != null && !encoding.equals("identity", true)) {
        throw EchoError.Network("Encoded download cannot be safely resumed")
    }
    val length = headers.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 } ?: -1
    val requested = requestedRange?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it.trim()) }
    val requestedStart = requested?.groupValues?.get(1)?.toLongOrNull() ?: resumeFrom
    val requestedEnd = requested?.groupValues?.get(2)?.toLongOrNull()
    if (status == 200) {
        if (requestedEnd != null) throw EchoError.Network("Server ignored a bounded byte range")
        return DownloadResponsePlan(false, 0, length, length)
    }
    val range = headers.header("Content-Range")?.let {
        Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).matchEntire(it.trim())
    } ?: throw EchoError.Network("Missing or invalid Content-Range")
    val start = range.groupValues[1].toLongOrNull() ?: throw EchoError.Network("Invalid range start")
    val end = range.groupValues[2].toLongOrNull() ?: throw EchoError.Network("Invalid range end")
    val total = range.groupValues[3].toLongOrNull() ?: -1
    if (start != requestedStart || end < start || end == Long.MAX_VALUE || (total >= 0 && end >= total) ||
        (requestedEnd != null && end != requestedEnd)) {
        throw EchoError.Network("Server returned an inconsistent byte range")
    }
    val bodyBytes = end - start + 1
    if (length >= 0 && length != bodyBytes) throw EchoError.Network("Conflicting response lengths")
    return DownloadResponsePlan(resumeFrom > 0, if (resumeFrom > 0) resumeFrom else 0, bodyBytes, total)
}
