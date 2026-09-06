package dev.brahmkshatriya.echo.player.core.casting

import dev.brahmkshatriya.echo.common.models.Streamable

/** The default Cast receiver cannot replay app-only headers, DRM or local file handles. */
object CastSourcePolicy {
    fun supported(source: Streamable.Source): Boolean = source is Streamable.Source.Http &&
        source.type == Streamable.SourceType.Progressive && !source.isLive && source.decryption == null &&
        source.request.headers.isEmpty() && source.request.method == dev.brahmkshatriya.echo.common.models.NetworkRequest.Method.GET &&
        source.request.bodyBase64 == null && (source.request.url.startsWith("https://", true) || source.request.url.startsWith("http://", true))

    fun select(sources: List<Streamable.Source>, preferred: Int): Streamable.Source.Http? =
        (sources.getOrNull(preferred)?.takeIf(::supported) ?: sources.firstOrNull(::supported)) as? Streamable.Source.Http

    fun contentType(url: String): String = when (url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase()) {
        "m4a", "mp4", "m4b" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        else -> "audio/mpeg"
    }
}
