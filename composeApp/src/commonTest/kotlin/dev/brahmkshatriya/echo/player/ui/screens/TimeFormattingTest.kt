package dev.brahmkshatriya.echo.player.ui.screens

import dev.brahmkshatriya.echo.player.domain.formatMs

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormattingTest {

    @Test
    fun `formatMs renders short durations`() {
        assertEquals("0:00", formatMs(0))
        assertEquals("3:05", formatMs(185_000))
        assertEquals("0:59", formatMs(59_999))
    }

    @Test
    fun `formatMs renders hours`() {
        assertEquals("1:02:03", formatMs(3_723_000))
        assertEquals("10:00:07", formatMs(36_007_000))
    }

    @Test
    fun `formatMs clamps negative input`() {
        assertEquals("0:00", formatMs(-1_000))
    }
}
