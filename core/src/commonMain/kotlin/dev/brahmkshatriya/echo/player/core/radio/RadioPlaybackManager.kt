package dev.brahmkshatriya.echo.player.core.radio

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.player.domain.EchoError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One hop of a radio resolution attempt, as reported by the platform HTTP layer.
 *
 * @property url the URL that was fetched
 * @property status the HTTP status code
 * @property headers response headers (case-insensitive lookups are done internally)
 * @property body response body, only needed for playlist/HLS bodies
 */
data class RadioProbe(
    val url: String,
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()

    val contentType: String? get() = header("Content-Type")
    val location: String? get() = header("Location")
}

/** A radio stream resolved down to something an engine can open directly. */
data class ResolvedRadioStream(
    val url: String,
    val sourceType: Streamable.SourceType,
    val station: IcyStationInfo = IcyStationInfo(),
    /** Every URL visited, starting with the original request. Useful for diagnostics. */
    val chain: List<String> = emptyList(),
) {
    /** Headers that must be replayed when the engine opens [url]. */
    val requestHeaders: Map<String, String>
        get() = if (sourceType == Streamable.SourceType.HLS) emptyMap()
        else RadioStreamPolicy.icyRequestHeaders()

    fun toSource(): Streamable.Source.Http = Streamable.Source.Http(
        request = dev.brahmkshatriya.echo.common.models.NetworkRequest(url, requestHeaders),
        type = sourceType,
        isLive = true,
    )
}

/**
 * Resolves radio station URLs and tracks live "now playing" metadata.
 *
 * The manager is transport-agnostic: the caller supplies a `probe` function that performs one
 * HTTP request (platform code owns OkHttp/NSURLSession). This keeps redirect, playlist and
 * HLS handling identical on Android and iOS, and fully unit testable with no network.
 */
class RadioPlaybackManager(
    private val probe: suspend (url: String) -> RadioProbe,
) {

    private val _metadata = MutableStateFlow(IcyMetadata())

    /** Latest ICY "now playing" info; updated by [onMetadataBlock]. */
    val metadata: StateFlow<IcyMetadata> = _metadata.asStateFlow()

    private val _station = MutableStateFlow(IcyStationInfo())

    /** Station-level info from the ICY response headers of the resolved stream. */
    val station: StateFlow<IcyStationInfo> = _station.asStateFlow()

    /**
     * Follows redirects and unwraps playlist containers until an audio endpoint is reached.
     *
     * @param url the station URL as provided by the extension or the user
     * @param declared the source type the extension declared, if any (trusted when explicit)
     * @throws EchoError.Network when the chain cannot be resolved to a playable endpoint
     */
    suspend fun resolve(
        url: String,
        declared: Streamable.SourceType? = null,
    ): ResolvedRadioStream = resolve(url, declared, playlistDepth = 0, chain = mutableListOf())

    private suspend fun resolve(
        url: String,
        declared: Streamable.SourceType?,
        playlistDepth: Int,
        chain: MutableList<String>,
    ): ResolvedRadioStream {
        var current = url
        var redirects = 0
        while (true) {
            if (current in chain) {
                throw EchoError.Network("Radio redirect loop at $current")
            }
            chain += current

            // An extension that explicitly declares HLS/DASH is trusted without a probe:
            // some CDNs reject bodiless probes but serve the manifest fine to the player.
            if (declared == Streamable.SourceType.HLS || declared == Streamable.SourceType.DASH) {
                return ResolvedRadioStream(current, declared, _station.value, chain.toList())
            }

            val response = probe(current)

            if (response.status in RadioStreamPolicy.REDIRECT_STATUSES) {
                val location = response.location
                    ?: throw EchoError.Network("Radio redirect without Location", response.status)
                if (++redirects > RadioStreamPolicy.MAX_REDIRECTS) {
                    throw EchoError.Network("Too many radio redirects", response.status)
                }
                current = RadioStreamPolicy.resolveUrl(current, location)
                continue
            }

            if (response.status !in 200..299) {
                throw EchoError.Network("Radio stream unavailable", response.status)
            }

            val station = IcyMetadataParser.parseHeaders(response.headers)
            if (station != IcyStationInfo()) _station.value = station

            val body = response.body
            if (RadioStreamPolicy.isHls(current, response.contentType, body)) {
                return ResolvedRadioStream(
                    current, Streamable.SourceType.HLS, _station.value, chain.toList()
                )
            }

            if (RadioStreamPolicy.isPlaylist(current, response.contentType) && body != null) {
                if (playlistDepth >= RadioStreamPolicy.MAX_PLAYLIST_DEPTH) {
                    throw EchoError.Network("Radio playlist nested too deeply")
                }
                val entries = RadioStreamPolicy.parsePlaylist(body)
                    .map { RadioStreamPolicy.resolveUrl(current, it) }
                    .filterNot { it in chain }
                if (entries.isEmpty()) throw EchoError.Network("Radio playlist contained no streams")
                // Try each entry: stations commonly list several mirrors, only some of them up.
                var failure: Throwable? = null
                for (entry in entries) {
                    try {
                        return resolve(entry, declared = null, playlistDepth = playlistDepth + 1, chain = chain)
                    } catch (error: EchoError.Network) {
                        failure = error
                    }
                }
                throw failure ?: EchoError.Network("Radio playlist had no reachable stream")
            }

            return ResolvedRadioStream(
                url = current,
                sourceType = RadioStreamPolicy.sourceTypeFor(current, declared, response.contentType, body),
                station = _station.value,
                chain = chain.toList(),
            )
        }
    }

    /**
     * Feeds a raw in-band ICY metadata block.
     *
     * @return the new metadata when it actually changed, `null` for padding/no-op blocks so
     *   callers do not needlessly refresh the lock screen.
     */
    fun onMetadataBlock(block: String): IcyMetadata? {
        val parsed = IcyMetadataParser.parse(block) ?: return null
        if (parsed == _metadata.value) return null
        _metadata.value = parsed
        return parsed
    }

    /** Clears per-stream state; call when the station changes or playback stops. */
    fun reset() {
        _metadata.value = IcyMetadata()
        _station.value = IcyStationInfo()
    }
}
