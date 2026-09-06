package dev.brahmkshatriya.echo.player.domain.recommendations

import dev.brahmkshatriya.echo.player.library.LibrarySong

/** Explainable, on-device recommendations. Nothing is uploaded or inferred externally. */
class RecommendationEngine(private val now: () -> Long) {
    enum class Kind { RECENTLY_PLAYED, MOST_PLAYED, FORGOTTEN_FAVORITES, MOOD, SIMILAR_ARTISTS, ALBUM_CONTINUATION }
    enum class Mood(val genres: Set<String>) {
        CALM(setOf("ambient", "acoustic", "chill", "downtempo")),
        ENERGETIC(setOf("rock", "dance", "electronic", "metal", "punk")),
        FOCUS(setOf("classical", "instrumental", "ambient", "jazz")),
        UPLIFTING(setOf("pop", "soul", "funk", "disco"))
    }

    fun recommend(
        kind: Kind,
        library: List<LibrarySong>,
        limit: Int = 20,
        seedKey: String? = null,
        mood: Mood = Mood.CALM
    ): List<LibrarySong> {
        require(limit in 1..500)
        val songs = library.distinctBy { it.ref.key }.sortedBy { it.ref.key }
        val seed = songs.firstOrNull { it.ref.key == seedKey }
            ?: songs.filter { it.stats.playCount > 0 }.maxByOrNull { it.stats.lastPlayedAtMs }
        val ranked = when (kind) {
            Kind.RECENTLY_PLAYED -> songs.filter { it.stats.playCount > 0 }.sortedByDescending { it.stats.lastPlayedAtMs }
            Kind.MOST_PLAYED -> songs.filter { it.stats.playCount > 0 }
                .sortedWith(compareByDescending<LibrarySong> { it.stats.playCount }.thenByDescending { it.stats.lastPlayedAtMs })
            Kind.FORGOTTEN_FAVORITES -> songs.filter {
                it.favorite && (it.stats.playCount == 0L || now() - it.stats.lastPlayedAtMs >= 30 * DAY_MS)
            }.sortedBy { it.stats.lastPlayedAtMs }
            Kind.MOOD -> songs.map { it to genres(it).intersect(mood.genres).size }.filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<LibrarySong, Int>> { it.second }.thenBy { it.first.stats.playCount })
                .map { it.first }
            Kind.SIMILAR_ARTISTS -> if (seed == null) emptyList() else {
                val seedGenres = genres(seed)
                songs.filter { !it.ref.artist.equals(seed.ref.artist, true) }.map { song ->
                    val candidate = genres(song)
                    val union = seedGenres.union(candidate).size
                    song to if (union == 0) 0.0 else seedGenres.intersect(candidate).size.toDouble() / union
                }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
            }
            Kind.ALBUM_CONTINUATION -> if (seed?.ref?.album.isNullOrBlank()) emptyList() else songs.filter {
                it.ref.key != seed!!.ref.key && it.ref.album.equals(seed.ref.album, true) &&
                    it.albumArtist.equals(seed.albumArtist, true) &&
                    if (seed.albumOrder != null) (it.albumOrder ?: Long.MAX_VALUE) > seed.albumOrder else it.stats.playCount == 0L
            }.sortedWith(compareBy<LibrarySong> { it.albumOrder ?: Long.MAX_VALUE }.thenBy { it.ref.title })
        }
        return ranked.take(limit)
    }

    private fun genres(song: LibrarySong) = song.genres.map { it.trim().lowercase() }.toSet()
    private companion object { const val DAY_MS = 86_400_000L }
}
