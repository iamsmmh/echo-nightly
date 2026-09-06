package dev.brahmkshatriya.echo.player.library

import kotlinx.serialization.Serializable

/** A lightweight serializable reference to a track from any extension. */
@Serializable
data class TrackRef(
    val extensionId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null
) {
    val key: String get() = "$extensionId::$trackId"
}

/** A user created playlist stored locally (shared by both platforms). */
@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val tracks: List<TrackRef>,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/** A history entry of a played track. */
@Serializable
data class HistoryEntry(
    val ref: TrackRef,
    val playedAtMs: Long,
    val msPlayed: Long
)


/** Aggregates are separate from the capped recent-history view. */
@Serializable
data class ListeningStats(
    val playCount: Long = 0,
    val completedCount: Long = 0,
    val lastPlayedAtMs: Long = 0,
    val totalPlayedMs: Long = 0
)

/** Domain snapshot used by recommendations and smart playlist rules. */
data class LibrarySong(
    val ref: TrackRef,
    val addedAtMs: Long = 0,
    val downloaded: Boolean = false,
    val favorite: Boolean = false,
    val stats: ListeningStats = ListeningStats(),
    val genres: Set<String> = emptySet(),
    val albumArtist: String = ref.artist,
    val albumOrder: Long? = null,
    val offlineAvailable: Boolean = downloaded
)
