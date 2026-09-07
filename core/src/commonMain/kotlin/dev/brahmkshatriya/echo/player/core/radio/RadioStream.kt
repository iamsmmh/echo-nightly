package dev.brahmkshatriya.echo.player.core.radio

import dev.brahmkshatriya.echo.common.models.Streamable

/**
 * Pure helpers for turning a user/extension supplied radio "station URL" into something a
 * platform engine can actually open.
 *
 * Radio URLs in the wild are rarely a direct audio stream: they are frequently redirect
 * chains ending at a load-balanced node, or a playlist container (`.pls`, `.m3u`) that
 * merely *points* at the audio endpoint. Getting this wrong is the root cause of the
 * "radio does not play" reports, so the parsing rules live here, platform-neutral and
 * unit tested, instead of being duplicated in Media3 and AVFoundation code.
 */
object RadioStreamPolicy {

    /** Redirect hops allowed before a chain is treated as broken. */
    const val MAX_REDIRECTS: Int = 5

    /** Playlist containers may nest (a `.pls` pointing at an `.m3u`); allow a bounded depth. */
    const val MAX_PLAYLIST_DEPTH: Int = 2

    /** Statuses that carry a `Location` header we should follow. */
    val REDIRECT_STATUSES: Set<Int> = setOf(301, 302, 303, 307, 308)

    /**
     * Request headers that ask an Icecast/SHOUTcast server to interleave ICY metadata.
     *
     * Without `Icy-MetaData: 1` the server sends no `StreamTitle` at all, which is why
     * "now playing" text used to stay empty for radio.
     */
    fun icyRequestHeaders(): Map<String, String> = mapOf(
        "Icy-MetaData" to "1",
        "Accept" to "*/*",
    )

    private fun path(url: String): String =
        url.substringBefore('?').substringBefore('#')

    private fun extension(url: String): String =
        path(url).substringAfterLast('/', "").substringAfterLast('.', "").lowercase()

    /** Whether [url] points at a playlist container rather than at audio bytes. */
    fun isPlaylist(url: String, contentType: String? = null): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null) {
            // audio/x-mpegurl and friends are playlists; HLS is handled separately below.
            if (type in PLAYLIST_CONTENT_TYPES) return !isHls(url, contentType)
        }
        return extension(url) in setOf("pls", "m3u", "asx", "xspf")
    }

    private val PLAYLIST_CONTENT_TYPES = setOf(
        "audio/x-scpls",
        "application/pls+xml",
        "audio/x-mpegurl",
        "audio/mpegurl",
        "application/xspf+xml",
        "video/x-ms-asf",
    )

    private val HLS_CONTENT_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )

    /**
     * HLS detection. `.m3u8` is unambiguous; for `.m3u` we must look at the body, because
     * `audio/x-mpegurl` is used both for plain playlists and for HLS manifests.
     */
    fun isHls(url: String, contentType: String? = null, body: String? = null): Boolean {
        if (extension(url) == "m3u8") return true
        if (body != null && looksLikeHlsManifest(body)) return true
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type == "application/vnd.apple.mpegurl" ||
            (type in HLS_CONTENT_TYPES && body != null && looksLikeHlsManifest(body))
    }

    /** An HLS manifest always declares `#EXTM3U` plus at least one HLS-only tag. */
    fun looksLikeHlsManifest(body: String): Boolean {
        val head = body.take(8 * 1024)
        if (!head.trimStart().startsWith("#EXTM3U")) return false
        return HLS_TAGS.any { it in head }
    }

    private val HLS_TAGS = listOf(
        "#EXT-X-STREAM-INF",
        "#EXT-X-TARGETDURATION",
        "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-VERSION",
        "#EXT-X-ENDLIST",
        "#EXT-X-PLAYLIST-TYPE",
    )

    /** Chooses the source type an engine should use for [url]. */
    fun sourceTypeFor(
        url: String,
        declared: Streamable.SourceType? = null,
        contentType: String? = null,
        body: String? = null,
    ): Streamable.SourceType = when {
        // An extension that explicitly says DASH/HLS is trusted.
        declared == Streamable.SourceType.DASH -> Streamable.SourceType.DASH
        declared == Streamable.SourceType.HLS -> Streamable.SourceType.HLS
        isHls(url, contentType, body) -> Streamable.SourceType.HLS
        else -> Streamable.SourceType.Progressive
    }

    /**
     * Extracts stream URLs from a `.pls` / `.m3u` / `.xspf` playlist body, in playback order.
     *
     * PLS entries are ordered by their `FileN` index rather than by line order, because
     * servers do not guarantee the keys are emitted sorted.
     */
    fun parsePlaylist(body: String): List<String> {
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val isPls = lines.any { it.equals("[playlist]", ignoreCase = true) } ||
            lines.any { PLS_FILE.matches(it) }
        val urls = if (isPls) {
            lines.mapNotNull { line ->
                val match = PLS_FILE.matchEntire(line) ?: return@mapNotNull null
                val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                index to match.groupValues[2].trim()
            }.sortedBy { it.first }.map { it.second }
        } else if (lines.any { it.startsWith("<") }) {
            XSPF_LOCATION.findAll(body).map { it.groupValues[1].trim() }.toList()
        } else {
            // Extended M3U: skip #EXTINF/#EXTM3U directives, keep entries.
            lines.filterNot { it.startsWith("#") }
        }
        return urls.filter { it.isNotEmpty() }.distinct()
    }

    private val PLS_FILE = Regex("""(?i)^File(\d+)\s*=\s*(.+)$""")
    private val XSPF_LOCATION = Regex("""(?is)<location>\s*(.*?)\s*</location>""")

    /**
     * Resolves a possibly relative `Location` / playlist entry against the URL it came from.
     *
     * Handles absolute URLs, protocol-relative (`//host/path`), root-relative (`/path`) and
     * plain relative (`path`) forms.
     */
    fun resolveUrl(base: String, target: String): String {
        val value = target.trim()
        if (value.isEmpty()) return base
        if (SCHEME.containsMatchIn(value)) return value
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return value
        val scheme = base.substring(0, schemeEnd)
        if (value.startsWith("//")) return "$scheme:$value"
        val afterScheme = base.substring(schemeEnd + 3)
        val authority = afterScheme.substringBefore('/')
        if (value.startsWith("/")) return "$scheme://$authority$value"
        val basePath = path(base).substring(schemeEnd + 3).removePrefix(authority)
        val directory = basePath.substringBeforeLast('/', "")
        return "$scheme://$authority$directory/$value"
    }

    private val SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")
}
