package dev.brahmkshatriya.echo.player.domain.playlists

import dev.brahmkshatriya.echo.player.library.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class SmartPlaylistEngineTest {
    private val day = 86_400_000L
    private val engine = SmartPlaylistEngine { 100 * day }
    private val songs = listOf(
        LibrarySong(TrackRef("ext", "old", "Z", "Band", "Album", durationMs = 180_000), addedAtMs = day,
            stats = ListeningStats(5, 3, 50 * day), favorite = true, downloaded = true, genres = setOf("Rock")),
        LibrarySong(TrackRef("ext", "new", "A", "Other", durationMs = null), addedAtMs = 99 * day)
    )
    private fun playlist(rule: PlaylistRule) = SmartPlaylist("custom", "Custom", rule)

    @Test fun builtinPlaylistsEvaluateRealData() {
        val result = BuiltinSmartPlaylists.all.associate { it.id to engine.evaluate(it, songs).map { song -> song.ref.trackId } }
        assertEquals(listOf("new"), result["recently-added"])
        assertEquals(listOf("old"), result["most-played"])
        assertEquals(listOf("new"), result["never-played"])
        assertEquals(listOf("old"), result["downloaded"])
        assertEquals(listOf("old"), result["offline-favorites"])
    }
    @Test fun userRulesRoundTripAndCombine() {
        val original = playlist(PlaylistRule.All(listOf(PlaylistRule.Flag(FlagField.FAVORITE),
            PlaylistRule.Text(TextField.GENRE, TextOperator.EQUAL, "rock"),
            PlaylistRule.Not(PlaylistRule.Number(NumberField.PLAY_COUNT, NumberOperator.LESS_THAN, 2)))))
        val decoded = Json.decodeFromString(SmartPlaylist.serializer(), Json.encodeToString(SmartPlaylist.serializer(), original))
        assertEquals(original, decoded)
        assertEquals(listOf("old"), engine.evaluate(decoded, songs).map { it.ref.trackId })
    }
    @Test fun unknownDurationAndNeverPlayedAreNotFalselyZero() {
        assertTrue(engine.evaluate(playlist(PlaylistRule.Number(NumberField.DURATION_MS, NumberOperator.EQUAL, 0)), songs).isEmpty())
        assertEquals(1, engine.evaluate(playlist(PlaylistRule.Number(NumberField.DAYS_SINCE_PLAYED, NumberOperator.GREATER_THAN, 20)), songs).size)
    }
    @Test fun allComparatorsAndAnyRules() {
        NumberOperator.entries.forEach { operator ->
            val list = engine.evaluate(playlist(PlaylistRule.Number(NumberField.PLAY_COUNT, operator, 5)), songs)
            val expected = when (operator) {
                NumberOperator.EQUAL, NumberOperator.AT_LEAST -> 1
                NumberOperator.LESS_THAN -> 1
                NumberOperator.AT_MOST -> 2
                NumberOperator.GREATER_THAN -> 0
            }
            assertEquals(expected, list.size)
        }
        assertEquals(2, engine.evaluate(playlist(PlaylistRule.Any(listOf(PlaylistRule.Flag(FlagField.FAVORITE),
            PlaylistRule.Text(TextField.TITLE, TextOperator.CONTAINS, "a")))), songs).size)
    }
    @Test fun pathologicalRulesAndFutureSchemasAreRejected() {
        var rule: PlaylistRule = PlaylistRule.All(emptyList())
        repeat(20) { rule = PlaylistRule.Not(rule) }
        assertFailsWith<IllegalArgumentException> { engine.validate(playlist(rule)) }
        assertFailsWith<IllegalArgumentException> { engine.validate(playlist(PlaylistRule.All(emptyList())).copy(schemaVersion = 99)) }
        assertFailsWith<IllegalArgumentException> { engine.validate(playlist(PlaylistRule.Text(TextField.TITLE, TextOperator.EQUAL, ""))) }
        assertFailsWith<IllegalArgumentException> { engine.validate(playlist(PlaylistRule.Number(NumberField.PLAY_COUNT, NumberOperator.EQUAL, -1))) }
    }
    @Test fun sortLimitAndDuplicatesAreDeterministic() {
        val p = playlist(PlaylistRule.All(emptyList())).copy(limit = 1)
        assertEquals("new", engine.evaluate(p, songs + songs).single().ref.trackId)
        assertEquals("old", engine.evaluate(p.copy(descending = true), songs).single().ref.trackId)
    }
    @Test fun importedFavoritesAreOfflineButNotDownloads() {
        val local = LibrarySong(TrackRef("local-offline", "import", "Imported", "Artist"), favorite = true, offlineAvailable = true)
        assertEquals(listOf(local), engine.evaluate(BuiltinSmartPlaylists.all.first { it.id == "offline-favorites" }, listOf(local)))
        assertTrue(engine.evaluate(BuiltinSmartPlaylists.all.first { it.id == "downloaded" }, listOf(local)).isEmpty())
    }
}
