package dev.brahmkshatriya.echo.player.audio

import dev.brahmkshatriya.echo.common.models.Track.Companion.toDurationString
import kotlin.test.Test
import kotlin.test.assertEquals

class DurationFormattingTest {
    @Test
    fun `formats durations like the shared Track model`() {
        assertEquals("00:00", 0L.toDurationString())
        assertEquals("00:59", 59_000L.toDurationString())
        assertEquals("05:03", 303_000L.toDurationString())
        assertEquals("01:00:00", 3_600_000L.toDurationString())
        assertEquals("02:05:09", 7_509_000L.toDurationString())
    }
}
