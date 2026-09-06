package dev.brahmkshatriya.echo.player.domain.playlists

import dev.brahmkshatriya.echo.player.library.LibrarySong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NumberField { PLAY_COUNT, COMPLETION_COUNT, DURATION_MS, DAYS_SINCE_ADDED, DAYS_SINCE_PLAYED }
@Serializable
enum class NumberOperator { EQUAL, LESS_THAN, AT_MOST, GREATER_THAN, AT_LEAST }
@Serializable
enum class TextField { TITLE, ARTIST, ALBUM, GENRE }
@Serializable
enum class TextOperator { EQUAL, CONTAINS }
@Serializable
enum class FlagField { FAVORITE, DOWNLOADED, OFFLINE_AVAILABLE }

/** Versioned, data-only expressions. No scripts, reflection or provider code execution. */
@Serializable
sealed class PlaylistRule {
    @Serializable @SerialName("all") data class All(val rules: List<PlaylistRule>) : PlaylistRule()
    @Serializable @SerialName("any") data class Any(val rules: List<PlaylistRule>) : PlaylistRule()
    @Serializable @SerialName("not") data class Not(val rule: PlaylistRule) : PlaylistRule()
    @Serializable @SerialName("number") data class Number(val field: NumberField, val operator: NumberOperator, val value: Long) : PlaylistRule()
    @Serializable @SerialName("text") data class Text(val field: TextField, val operator: TextOperator, val value: String) : PlaylistRule()
    @Serializable @SerialName("flag") data class Flag(val field: FlagField, val value: Boolean = true) : PlaylistRule()
}

@Serializable
enum class PlaylistSort { TITLE, ADDED_AT, PLAY_COUNT, LAST_PLAYED, ARTIST_POPULARITY }

@Serializable
data class SmartPlaylist(
    val id: String,
    val name: String,
    val rule: PlaylistRule,
    val sort: PlaylistSort = PlaylistSort.TITLE,
    val descending: Boolean = false,
    val limit: Int = 100,
    val schemaVersion: Int = 1
)

class SmartPlaylistEngine(private val now: () -> Long) {
    fun validate(playlist: SmartPlaylist) {
        require(playlist.schemaVersion == 1) { "Unsupported smart playlist version" }
        require(playlist.id.isNotBlank() && playlist.name.isNotBlank()) { "Playlist id and name are required" }
        require(playlist.limit in 1..10_000) { "Limit must be between 1 and 10000" }
        val pending = ArrayDeque<Pair<PlaylistRule, Int>>()
        pending.add(playlist.rule to 0)
        var count = 0
        while (pending.isNotEmpty()) {
            val (rule, depth) = pending.removeLast()
            require(++count <= 128 && depth <= 16) { "Rule is too complex" }
            when (rule) {
                is PlaylistRule.All -> { require(rule.rules.size <= 32); rule.rules.forEach { pending.add(it to depth + 1) } }
                is PlaylistRule.Any -> { require(rule.rules.size <= 32); rule.rules.forEach { pending.add(it to depth + 1) } }
                is PlaylistRule.Not -> pending.add(rule.rule to depth + 1)
                is PlaylistRule.Number -> require(rule.value >= 0) { "Rule values must not be negative" }
                is PlaylistRule.Text -> require(rule.value.isNotBlank() && rule.value.length <= 256) { "Invalid text rule" }
                is PlaylistRule.Flag -> Unit
            }
        }
    }

    fun evaluate(playlist: SmartPlaylist, library: List<LibrarySong>): List<LibrarySong> {
        validate(playlist)
        val timestamp = now()
        val artistCounts = library.groupBy { it.ref.artist.lowercase() }.mapValues { (_, songs) -> songs.sumOf { it.stats.playCount } }
        val candidates = library.distinctBy { it.ref.key }.filter { matches(playlist.rule, it, timestamp) }
        val order = when (playlist.sort) {
            PlaylistSort.TITLE -> compareBy<LibrarySong> { it.ref.title.lowercase() }
            PlaylistSort.ADDED_AT -> compareBy { it.addedAtMs }
            PlaylistSort.PLAY_COUNT -> compareBy { it.stats.playCount }
            PlaylistSort.LAST_PLAYED -> compareBy { it.stats.lastPlayedAtMs }
            PlaylistSort.ARTIST_POPULARITY -> compareBy { artistCounts[it.ref.artist.lowercase()] ?: 0 }
        }
        return candidates.sortedWith((if (playlist.descending) order.reversed() else order).thenBy { it.ref.key }).take(playlist.limit)
    }

    private fun matches(rule: PlaylistRule, song: LibrarySong, timestamp: Long): Boolean = when (rule) {
        is PlaylistRule.All -> rule.rules.all { matches(it, song, timestamp) }
        is PlaylistRule.Any -> rule.rules.any { matches(it, song, timestamp) }
        is PlaylistRule.Not -> !matches(rule.rule, song, timestamp)
        is PlaylistRule.Flag -> (when (rule.field) { FlagField.FAVORITE -> song.favorite; FlagField.DOWNLOADED -> song.downloaded; FlagField.OFFLINE_AVAILABLE -> song.offlineAvailable }) == rule.value
        is PlaylistRule.Number -> {
            val value = when (rule.field) {
                NumberField.PLAY_COUNT -> song.stats.playCount
                NumberField.COMPLETION_COUNT -> song.stats.completedCount
                NumberField.DURATION_MS -> song.ref.durationMs
                NumberField.DAYS_SINCE_ADDED -> (timestamp - song.addedAtMs).coerceAtLeast(0) / DAY_MS
                NumberField.DAYS_SINCE_PLAYED -> if (song.stats.playCount == 0L) null else (timestamp - song.stats.lastPlayedAtMs).coerceAtLeast(0) / DAY_MS
            }
            value != null && when (rule.operator) {
                NumberOperator.EQUAL -> value == rule.value
                NumberOperator.LESS_THAN -> value < rule.value
                NumberOperator.AT_MOST -> value <= rule.value
                NumberOperator.GREATER_THAN -> value > rule.value
                NumberOperator.AT_LEAST -> value >= rule.value
            }
        }
        is PlaylistRule.Text -> {
            val fields = when (rule.field) {
                TextField.TITLE -> listOf(song.ref.title)
                TextField.ARTIST -> listOf(song.ref.artist)
                TextField.ALBUM -> listOfNotNull(song.ref.album)
                TextField.GENRE -> song.genres.toList()
            }
            fields.any { if (rule.operator == TextOperator.EQUAL) it.equals(rule.value.trim(), true) else it.contains(rule.value.trim(), true) }
        }
    }

    private companion object { const val DAY_MS = 86_400_000L }
}

object BuiltinSmartPlaylists {
    val all: List<SmartPlaylist> = listOf(
        SmartPlaylist("recently-added", "Recently Added", PlaylistRule.Number(NumberField.DAYS_SINCE_ADDED, NumberOperator.AT_MOST, 30), PlaylistSort.ADDED_AT, true),
        SmartPlaylist("most-played", "Most Played", PlaylistRule.Number(NumberField.PLAY_COUNT, NumberOperator.GREATER_THAN, 0), PlaylistSort.PLAY_COUNT, true),
        SmartPlaylist("never-played", "Never Played", PlaylistRule.Number(NumberField.PLAY_COUNT, NumberOperator.EQUAL, 0)),
        SmartPlaylist("downloaded", "Downloaded", PlaylistRule.Flag(FlagField.DOWNLOADED)),
        SmartPlaylist("offline-favorites", "Offline Favorites", PlaylistRule.All(listOf(PlaylistRule.Flag(FlagField.FAVORITE), PlaylistRule.Flag(FlagField.OFFLINE_AVAILABLE)))),
        SmartPlaylist("top-artists", "Top Artists", PlaylistRule.Number(NumberField.PLAY_COUNT, NumberOperator.GREATER_THAN, 0), PlaylistSort.ARTIST_POPULARITY, true)
    )
}
