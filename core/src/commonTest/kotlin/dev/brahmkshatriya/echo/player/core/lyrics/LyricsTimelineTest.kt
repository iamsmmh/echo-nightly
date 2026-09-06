package dev.brahmkshatriya.echo.player.core.lyrics

import dev.brahmkshatriya.echo.common.models.Lyrics
import kotlin.test.*

class LyricsTimelineTest {
    @Test fun lrcSupportsMultipleTimestampsFractionsAndOffsets() {
        val lyric = LrcParser.parse("[ar:Artist]\n[offset:-100]\n[00:01.20][00:03.456]Hello\n[00:04]World", 10_000) as Lyrics.Timed
        assertEquals(listOf(1100L, 3356L, 3900L), lyric.list.map { it.startTime })
        assertEquals(listOf("Hello", "Hello", "World"), lyric.list.map { it.text })
        assertEquals(10_000L, lyric.list.last().endTime)
    }
    @Test fun unsyncedAndSectionNamesArePreserved() {
        assertEquals(Lyrics.Simple("[Intro]\nSome words"), LrcParser.parse("[ti:Title]\n[Intro]\nSome words"))
        assertEquals(-1, LyricsTimeline(Lyrics.Simple("hello")).activeLine(500))
    }
    @Test fun binarySearchHandlesBoundariesAndSeeksBackwards() {
        val timeline = LyricsTimeline(LrcParser.parse("[00:01]First\n[00:03]Second", 5_000))
        assertEquals(-1, timeline.activeLine(999))
        assertEquals(0, timeline.activeLine(1_000))
        assertEquals(1, timeline.activeLine(3_000))
        assertEquals(0, timeline.activeLine(2_000))
        assertEquals(-1, timeline.activeLine(5_000))
    }
    @Test fun gapsRespectProviderPolicy() {
        val lines = listOf(Lyrics.Item("A", 0, 100), Lyrics.Item("B", 200, 300))
        assertEquals(-1, LyricsTimeline(Lyrics.Timed(lines, false)).activeLine(150))
        assertEquals(0, LyricsTimeline(Lyrics.Timed(lines, true)).activeLine(150))
    }
    @Test fun karaokeUsesWordTiming() {
        val timeline = LyricsTimeline(Lyrics.WordByWord(listOf(listOf(Lyrics.Item("Hello", 0, 1000), Lyrics.Item("World", 1000, 2000)))))
        assertEquals("Hello World", timeline.lines.single().text)
        assertEquals(0.5f, timeline.wordProgress(0, 0, 500))
        assertEquals(0f, timeline.wordProgress(0, 1, 500))
        assertEquals(1f, timeline.wordProgress(0, 0, 2_000))
    }
    @Test fun malformedTimingAndDuplicateLinesAreSafe() {
        assertTrue(LrcParser.parse("[99:99]not a time") is Lyrics.Simple)
        val timed = LrcParser.parse("[00:01]A\n[00:01]A\n[00:01]Translation") as Lyrics.Timed
        assertEquals("A\nTranslation", timed.list.single().text)
        assertEquals(-1, LyricsTimeline(Lyrics.Timed(emptyList())).activeLine(0))
    }
}
