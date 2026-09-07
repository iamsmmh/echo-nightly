package dev.brahmkshatriya.echo.player.core.radio

/**
 * "Now playing" information carried by an Icecast/SHOUTcast stream.
 *
 * @property title the raw `StreamTitle` value, trimmed
 * @property artist artist part when the title uses the conventional `Artist - Title` form
 * @property track track part when the title uses the conventional `Artist - Title` form
 * @property url the `StreamUrl` value, when present
 */
data class IcyMetadata(
    val title: String? = null,
    val artist: String? = null,
    val track: String? = null,
    val url: String? = null,
) {
    val isEmpty: Boolean get() = title.isNullOrBlank() && url.isNullOrBlank()

    /** Display string preferring the split form, falling back to the raw title. */
    val display: String?
        get() = when {
            !artist.isNullOrBlank() && !track.isNullOrBlank() -> "$artist - $track"
            !title.isNullOrBlank() -> title
            else -> null
        }
}

/**
 * Parser for ICY metadata blocks and headers.
 *
 * Radio servers emit metadata as `StreamTitle='...';StreamUrl='...';` padded with NULs.
 * The values themselves may contain `;` and escaped quotes, so a naive `split(';')` (the
 * usual mistake) corrupts titles — we scan for the terminating `';` sequence instead.
 */
object IcyMetadataParser {

    /** Station-level headers, sent once when the stream is opened. */
    fun parseHeaders(headers: Map<String, String>): IcyStationInfo {
        val lower = headers.entries.associate { it.key.lowercase() to it.value.trim() }
        val interval = lower["icy-metaint"]?.toIntOrNull()?.takeIf { it > 0 }
        return IcyStationInfo(
            name = lower["icy-name"]?.takeIf { it.isNotBlank() },
            genre = lower["icy-genre"]?.takeIf { it.isNotBlank() },
            description = lower["icy-description"]?.takeIf { it.isNotBlank() },
            url = lower["icy-url"]?.takeIf { it.isNotBlank() },
            bitrateKbps = lower["icy-br"]?.toIntOrNull()?.takeIf { it > 0 },
            metadataInterval = interval,
            supportsMetadata = interval != null,
        )
    }

    /**
     * Parses an in-band metadata block.
     *
     * Empty or padding-only blocks return `null`: servers send those to mean
     * "nothing changed", and treating them as an update wipes the current title.
     */
    fun parse(block: String): IcyMetadata? {
        val cleaned = block.trimEnd('\u0000').trim()
        if (cleaned.isEmpty()) return null
        val fields = splitFields(cleaned)
        val title = fields["streamtitle"]?.trim()
        val url = fields["streamurl"]?.trim()?.takeIf { it.isNotBlank() }
        if (title.isNullOrBlank() && url == null) return null
        val (artist, track) = splitArtistTitle(title)
        return IcyMetadata(title = title?.takeIf { it.isNotBlank() }, artist = artist, track = track, url = url)
    }

    /**
     * Splits `Key='value';` pairs. A value ends at the first `';` (quote followed by
     * semicolon) or at the final quote, so semicolons inside a song title survive.
     */
    private fun splitFields(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < input.length) {
            val equals = input.indexOf('=', index)
            if (equals < 0) break
            val key = input.substring(index, equals).trim().trimStart(';').trim().lowercase()
            var cursor = equals + 1
            if (cursor >= input.length) break
            val quote = input[cursor]
            if (quote == '\'' || quote == '"') {
                cursor++
                val terminator = input.indexOf("$quote;", cursor)
                val end = when {
                    terminator >= 0 -> terminator
                    input.lastIndexOf(quote) > cursor -> input.lastIndexOf(quote)
                    else -> input.length
                }
                result[key] = input.substring(cursor, end.coerceAtMost(input.length))
                index = (end + 2).coerceAtMost(input.length)
            } else {
                val end = input.indexOf(';', cursor).takeIf { it >= 0 } ?: input.length
                result[key] = input.substring(cursor, end).trim()
                index = end + 1
            }
        }
        return result
    }

    /** Splits `Artist - Title`, ignoring hyphens that are not used as a separator. */
    fun splitArtistTitle(title: String?): Pair<String?, String?> {
        val value = title?.trim().orEmpty()
        if (value.isEmpty()) return null to null
        val separator = value.indexOf(" - ")
        if (separator <= 0) return null to null
        val artist = value.substring(0, separator).trim()
        val track = value.substring(separator + 3).trim()
        if (artist.isEmpty() || track.isEmpty()) return null to null
        return artist to track
    }
}

/** Station-level information advertised by ICY response headers. */
data class IcyStationInfo(
    val name: String? = null,
    val genre: String? = null,
    val description: String? = null,
    val url: String? = null,
    val bitrateKbps: Int? = null,
    val metadataInterval: Int? = null,
    val supportsMetadata: Boolean = false,
)
