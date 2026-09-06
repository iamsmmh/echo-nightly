package dev.brahmkshatriya.echo.player

import dev.brahmkshatriya.echo.player.download.DownloadState
import dev.brahmkshatriya.echo.player.download.DownloadStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadStateMachineTest {

    @Test
    fun `happy path queued to completed`() {
        val states = listOf(
            DownloadState.QUEUED to DownloadState.DOWNLOADING,
            DownloadState.DOWNLOADING to DownloadState.COMPLETED
        )
        states.forEach { (from, to) ->
            assertTrue(DownloadStateMachine.isTransitionValid(from, to), "expected $from -> $to valid")
        }
    }

    @Test
    fun `pause and resume`() {
        assertTrue(DownloadStateMachine.isTransitionValid(DownloadState.DOWNLOADING, DownloadState.PAUSED))
        assertTrue(DownloadStateMachine.isTransitionValid(DownloadState.PAUSED, DownloadState.QUEUED))
        assertEquals(DownloadState.QUEUED, DownloadStateMachine.transition(DownloadState.PAUSED, DownloadState.QUEUED))
    }

    @Test
    fun `retry from failed`() {
        assertTrue(DownloadStateMachine.isTransitionValid(DownloadState.FAILED, DownloadState.QUEUED))
        assertFalse(DownloadStateMachine.isTransitionValid(DownloadState.COMPLETED, DownloadState.QUEUED))
    }

    @Test
    fun `cancel from active states`() {
        listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED, DownloadState.FAILED)
            .forEach { from ->
                assertTrue(DownloadStateMachine.isTransitionValid(from, DownloadState.CANCELLED), "cancel from $from")
            }
    }

    @Test
    fun `terminal states are final`() {
        assertTrue(DownloadState.COMPLETED.let { DownloadStateMachine.isTransitionValid(it, it) })
        assertFailsWith<IllegalArgumentException> {
            DownloadStateMachine.transition(DownloadState.COMPLETED, DownloadState.DOWNLOADING)
        }
        assertFailsWith<IllegalArgumentException> {
            DownloadStateMachine.transition(DownloadState.CANCELLED, DownloadState.QUEUED)
        }
    }

    @Test
    fun `illegal transitions rejected`() {
        assertFalse(DownloadStateMachine.isTransitionValid(DownloadState.QUEUED, DownloadState.COMPLETED))
        assertFalse(DownloadStateMachine.isTransitionValid(DownloadState.PAUSED, DownloadState.COMPLETED))
    }

    @Test
    fun `magic byte validation detects audio containers`() {
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04) + ByteArray(12)
        val m4a = byteArrayOf(0, 0, 0, 0x20) + "ftyp".encodeToByteArray() + ByteArray(8)
        val flac = "fLaC".encodeToByteArray() + ByteArray(12)
        val ogg = "OggS".encodeToByteArray() + ByteArray(12)
        val junk = ByteArray(16) { 0x01 }
        assertTrue(dev.brahmkshatriya.echo.player.download.DownloadSupport.looksLikeAudio(mp3))
        assertTrue(dev.brahmkshatriya.echo.player.download.DownloadSupport.looksLikeAudio(m4a))
        assertTrue(dev.brahmkshatriya.echo.player.download.DownloadSupport.looksLikeAudio(flac))
        assertTrue(dev.brahmkshatriya.echo.player.download.DownloadSupport.looksLikeAudio(ogg))
        assertFalse(dev.brahmkshatriya.echo.player.download.DownloadSupport.looksLikeAudio(junk))
    }
}
