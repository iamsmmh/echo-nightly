package dev.brahmkshatriya.echo.player.domain.recommendations

import dev.brahmkshatriya.echo.player.library.*
import dev.brahmkshatriya.echo.player.domain.recommendations.RecommendationEngine.Kind
import kotlin.test.*

class RecommendationEngineTest {
    private val day = 86_400_000L
    private val engine = RecommendationEngine { 100 * day }
    private fun song(id: String, plays: Long = 0, lastDay: Int = 0, favorite: Boolean = false, genre: String = "rock", artist: String = "Band", order: Long = 1) =
        LibrarySong(TrackRef("ext", id, id, artist, "Album"), favorite = favorite,
            stats = ListeningStats(playCount = plays, lastPlayedAtMs = lastDay * day), genres = setOf(genre), albumOrder = order)

    @Test fun recentlyAndMostPlayedUsePersistentAggregates() {
        val songs = listOf(song("frequent", 10, 80), song("recent", 1, 99), song("never"))
        assertEquals(listOf("recent", "frequent"), engine.recommend(Kind.RECENTLY_PLAYED, songs).map { it.ref.trackId })
        assertEquals("frequent", engine.recommend(Kind.MOST_PLAYED, songs).first().ref.trackId)
    }
    @Test fun forgottenFavoritesRequireAgeOrNoListen() {
        val songs = listOf(song("old", 1, 50, true), song("new", 1, 99, true), song("never", favorite = true), song("not-favorite", 5, 10))
        assertEquals(setOf("old", "never"), engine.recommend(Kind.FORGOTTEN_FAVORITES, songs).map { it.ref.trackId }.toSet())
    }
    @Test fun moodUsesGenresNotExternalInference() {
        val songs = listOf(song("rock"), song("calm", genre = "AMBIENT"), song("unknown", genre = ""))
        assertEquals(listOf("calm"), engine.recommend(Kind.MOOD, songs).map { it.ref.trackId })
    }
    @Test fun similarArtistsExcludeSeedPerformer() {
        val songs = listOf(song("seed", 2, 99), song("same-band"), song("similar", genre = "rock", artist = "Other"), song("unrelated", genre = "classical", artist = "Third"))
        assertEquals(listOf("similar"), engine.recommend(Kind.SIMILAR_ARTISTS, songs, seedKey = "ext::seed").map { it.ref.trackId })
    }
    @Test fun albumContinuationRespectsTrackOrderAndAlbumArtist() {
        val songs = listOf(song("one", order = 1), song("two", 1, 99, order = 2), song("three", order = 3),
            song("other-album", artist = "Another", order = 4))
        assertEquals(listOf("three"), engine.recommend(Kind.ALBUM_CONTINUATION, songs).map { it.ref.trackId })
    }
    @Test fun emptyDataAndLimitsAreSafe() {
        Kind.entries.forEach { assertTrue(engine.recommend(it, emptyList()).isEmpty()) }
        assertFailsWith<IllegalArgumentException> { engine.recommend(Kind.MOOD, emptyList(), 0) }
        val songs = listOf(song("a", 2), song("b", 1), song("a", 2))
        assertEquals(1, engine.recommend(Kind.MOST_PLAYED, songs, 1).size)
    }
}
