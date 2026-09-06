package dev.brahmkshatriya.echo.player.extensions.subsonic

import dev.brahmkshatriya.echo.player.domain.EchoError
import dev.brahmkshatriya.echo.player.domain.EchoLogger
import dev.brahmkshatriya.echo.player.platform.HttpClient
import dev.brahmkshatriya.echo.player.platform.HttpRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Minimal Subsonic / OpenSubsonic REST client (works with Navidrome, Airsonic,
 * Subsonic, Gonic, ...). The user configures their own server; Echo never
 * bundles any music content.
 *
 * Authentication uses the token scheme: `t = md5(password + salt)`.
 */
class SubsonicApi(
    private val http: HttpClient,
    private val logger: EchoLogger
) {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class ServerConfig(
        val baseUrl: String,
        val username: String,
        val password: String
    )

    fun configure(config: ServerConfig) {
        this.config = config
    }

    fun clearConfiguration() { config = null }

    private var config: ServerConfig? = null

    val isConfigured: Boolean get() = config?.let { it.baseUrl.isNotBlank() && it.username.isNotBlank() } == true

    // ------------------------------------------------------------------ auth

    private fun authQuery(): String {
        val cfg = config ?: throw EchoError.Extension("Subsonic server is not configured")
        val salt = generateSalt()
        val token = dev.brahmkshatriya.echo.player.extensions.Md5.digestHex(cfg.password + salt)
        return "u=${encode(cfg.username)}&t=$token&s=$salt&v=$API_VERSION&c=$CLIENT&f=json"
    }

    private fun generateSalt(): String {
        return dev.brahmkshatriya.echo.player.security.secureRandomBytes(12)
            .map { SALT_CHARS[(it.toInt() and 255) % SALT_CHARS.length] }.joinToString("")
    }

    private fun endpoint(path: String, params: Map<String, String> = emptyMap()): String {
        val cfg = config ?: throw EchoError.Extension("Subsonic server is not configured")
        val base = cfg.baseUrl.trimEnd('/')
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return "$base/rest/$path?${authQuery()}" + (if (query.isNotEmpty()) "&$query" else "")
    }

    // ------------------------------------------------------------------ calls

    /** Verifies connectivity + credentials; returns the server version. */
    suspend fun ping(): String {
        val response = SubsonicEnvelope.serializer().let { serializer ->
            json.decodeFromString(serializer, requestText(endpoint("ping")))
        }
        val body = response.response
            ?: throw EchoError.Network("Malformed Subsonic response", statusCode = null)
        if (body.status != "ok") {
            throw EchoError.Network(
                "Subsonic authentication failed: ${body.error?.message ?: body.status}",
                statusCode = body.error?.code
            )
        }
        return body.serverVersion ?: "unknown"
    }

    suspend fun searchThree(query: String): SearchResult3Dto {
        val url = endpoint(
            "search3",
            mapOf(
                "query" to query,
                "artistCount" to "10",
                "albumCount" to "10",
                "songCount" to "30",
                "musicFolderId" to ""
            ).filterValues { it.isNotEmpty() }
        )
        return envelope(requestText(url)) { it.searchResult3 }
    }

    suspend fun albumList(type: String): List<AlbumDto> {
        val url = endpoint("getAlbumList2", mapOf("type" to type, "size" to "24"))
        return envelope(requestText(url)) { it.albumList2?.album }.orEmpty()
    }

    suspend fun getAlbum(id: String): AlbumWithSongsDto {
        val url = endpoint("getAlbum", mapOf("id" to id))
        return envelope(requestText(url)) { it.album }
            ?: throw EchoError.Network("Album $id not found on the server")
    }

    suspend fun getArtist(id: String): ArtistWithAlbumsDto {
        val url = endpoint("getArtist", mapOf("id" to id))
        return envelope(requestText(url)) { it.artist }
            ?: throw EchoError.Network("Artist $id not found on the server")
    }

    suspend fun getArtists(): List<ArtistDto> {
        val url = endpoint("getArtists")
        return envelope(requestText(url)) { it.artists?.index?.flatMap { it.artist ?: emptyList() } }.orEmpty()
    }

    /** Direct stream URL for AVPlayer/ExoPlayer. */
    fun streamUrl(id: String, maxBitRateKbps: Int, format: String): String =
        endpoint(
            "stream",
            buildMap {
                put("id", id)
                if (maxBitRateKbps > 0) put("maxBitRate", maxBitRateKbps.toString())
                if (format != "raw") put("format", format)
            }
        )

    suspend fun structuredLyrics(songId: String): StructuredLyricsDto? {
        val url = endpoint("getLyricsBySongId", mapOf("id" to songId))
        return envelope(requestText(url)) { it.lyricsList }?.structuredLyrics?.firstOrNull { it.line.isNotEmpty() }
    }

    suspend fun lyrics(artist: String, title: String): String? {
        val url = endpoint("getLyrics", mapOf("artist" to artist, "title" to title))
        return envelope(requestText(url)) { it.lyrics }?.value?.takeIf { it.isNotBlank() }
    }

    /** Cover art URL. */
    fun coverArtUrl(coverArtId: String?, size: Int = 600): String? =
        coverArtId?.takeIf { it.isNotBlank() }?.let { endpoint("getCoverArt", mapOf("id" to it, "size" to size.toString())) }

    // ------------------------------------------------------------------ http

    private suspend fun requestText(url: String): String {
        val response = http.send(
            HttpRequest(
                url = url,
                requestTimeoutMs = 20_000,
                headers = mapOf("Accept" to "application/json")
            )
        )
        if (!response.isSuccessful) {
            throw EchoError.Network("Subsonic request failed", statusCode = response.statusCode)
        }
        return response.bodyText
    }

    private fun <T> envelope(text: String, pick: (SubsonicResponseDto) -> T?): T {
        val envelope = json.decodeFromString(SubsonicEnvelope.serializer(), text)
        val body = envelope.response ?: throw EchoError.Network("Malformed Subsonic response")
        if (body.status != "ok") {
            throw EchoError.Network(
                "Subsonic error: ${body.error?.message ?: body.status}",
                statusCode = body.error?.code
            )
        }
        return pick(body) ?: throw EchoError.Network("Unexpected Subsonic response shape")
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT = "echo"
        private const val SALT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun encode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val value = byte.toInt() and 0xff
                val ch = value.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "-_.~") append(ch)
                else { append('%'); append("0123456789ABCDEF"[value ushr 4]); append("0123456789ABCDEF"[value and 15]) }
            }
        }

    }
}

// ---------------------------------------------------------------------- DTOs

@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponseDto? = null
)

@Serializable
data class SubsonicResponseDto(
    val status: String = "failed",
    val version: String? = null,
    @SerialName("serverVersion") val serverVersion: String? = null,
    val error: SubsonicErrorDto? = null,
    val searchResult3: SearchResult3Dto? = null,
    val albumList2: AlbumList2Dto? = null,
    val album: AlbumWithSongsDto? = null,
    val artist: ArtistWithAlbumsDto? = null,
    val artists: ArtistsDto? = null,
    val lyrics: LyricsDto? = null,
    val lyricsList: LyricsListDto? = null
)

@Serializable
data class LyricsDto(val value: String? = null)

@Serializable
data class LyricsListDto(val structuredLyrics: List<StructuredLyricsDto> = emptyList())
@Serializable
data class StructuredLyricsDto(val synced: Boolean = false, val offset: Long = 0, val line: List<LyricLineDto> = emptyList())
@Serializable
data class LyricLineDto(val value: String, val start: Long? = null)

@Serializable
data class SubsonicErrorDto(val code: Int? = null, val message: String? = null)

@Serializable
data class SearchResult3Dto(
    val artist: List<ArtistDto>? = null,
    val album: List<AlbumDto>? = null,
    val song: List<SongDto>? = null
)

@Serializable
data class AlbumList2Dto(val album: List<AlbumDto>? = null)

@Serializable
data class ArtistsDto(val index: List<ArtistIndexDto>? = null)

@Serializable
data class ArtistIndexDto(val name: String? = null, val artist: List<ArtistDto>? = null)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int? = null
)

@Serializable
data class AlbumDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val created: String? = null
)

@Serializable
data class ArtistWithAlbumsDto(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val album: List<AlbumDto>? = null
)

@Serializable
data class AlbumWithSongsDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val song: List<SongDto>? = null
)

@Serializable
data class SongDto(
    val id: String,
    val parent: String? = null,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val played: String? = null,
    val playCount: Long? = null
)
