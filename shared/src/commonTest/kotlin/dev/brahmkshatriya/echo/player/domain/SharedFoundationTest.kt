package dev.brahmkshatriya.echo.player.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class Sha256Test {

    @Test
    fun `fips 180-4 vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.digestHex("")
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.digestHex("abc")
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digestHex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
        )
    }

    @Test
    fun `streaming matches one-shot`() {
        val message = "The quick brown fox jumps over the lazy dog"
        val bytes = message.encodeToByteArray()
        val streaming = Sha256.Streaming()
        for (b in bytes) streaming.update(byteArrayOf(b))
        assertEquals(
            Sha256.hex(streaming.finish()),
            Sha256.hex(Sha256.digest(bytes))
        )
    }

    @Test
    fun `padding boundaries`() {
        // 55, 56, 57, 63, 64, 119, 120 byte inputs exercise every pad branch
        for (n in listOf(55, 56, 57, 63, 64, 119, 120)) {
            val data = ByteArray(n) { (it % 251).toByte() }
            val streaming = Sha256.Streaming()
            streaming.update(data)
            assertEquals(
                Sha256.hex(Sha256.digest(data)),
                Sha256.hex(streaming.finish()),
                "length $n"
            )
        }
    }
}

class AudioFormatTest {

    @Test
    fun `magic byte detection`() {
        assertTrue(AudioFormat.looksLikeAudio(byteArrayOf(0x49, 0x44, 0x33, 0x04) + ByteArray(12)))
        assertTrue(AudioFormat.looksLikeAudio(byteArrayOf(0, 0, 0, 0x20) + "ftyp".encodeToByteArray() + ByteArray(8)))
        assertTrue(AudioFormat.looksLikeAudio("fLaC".encodeToByteArray() + ByteArray(12)))
        assertTrue(AudioFormat.looksLikeAudio("OggS".encodeToByteArray() + ByteArray(12)))
        assertFalse(AudioFormat.looksLikeAudio(ByteArray(16) { 0x01 }))
        assertFalse(AudioFormat.looksLikeAudio("<html><body>Blocked".encodeToByteArray() + ByteArray(8)))
    }

    @Test
    fun `plausible completeness`() {
        assertFalse(AudioFormat.plausiblyComplete(0, -1))
        assertTrue(AudioFormat.plausiblyComplete(5_000_000, -1))
        assertTrue(AudioFormat.plausiblyComplete(5_000, 5_000))
        assertFalse(AudioFormat.plausiblyComplete(4_000, 5_000))
    }

    @Test
    fun `mime to extension`() {
        assertEquals("mp3", AudioFormat.extensionForMime("audio/mpeg"))
        assertEquals("m4a", AudioFormat.extensionForMime("audio/mp4"))
        assertEquals("opus", AudioFormat.extensionForMime("audio/opus"))
        assertEquals("mp3", AudioFormat.extensionForMime(null))
    }
}

class TimeFormattingTest {

    @Test
    fun `formatMs matches for player screen`() {
        assertEquals("0:00", formatMs(0))
        assertEquals("3:05", formatMs(185_000))
        assertEquals("1:02:03", formatMs(3_723_000))
        assertEquals("0:00", formatMs(-5))
    }

    @Test
    fun `twoDigits`() {
        assertEquals("00", twoDigits(0))
        assertEquals("09", twoDigits(9))
        assertEquals("59", twoDigits(59))
    }
}

class LoggerSanitizationTest {

    @Test
    fun `sensitive params are sanitized for logging`() {
        val url = "https://example.com/rest/stream?u=admin&t=deadbeef&s=abc&id=1"
        val sanitized = sanitizeUrl(url)
        assertTrue(sanitized.contains("t=***"))
        assertTrue(sanitized.contains("s=***"))
        assertFalse(sanitized.contains("deadbeef"))
        assertTrue(sanitized.contains("id=1"))
    }

    @Test
    fun `sanitizeUrl keeps non-sensitive params and drops nothing`() {
        val url = "https://example.com/a?b=1"
        assertEquals(url, sanitizeUrl(url))
        assertEquals("no-query", sanitizeUrl("no-query"))
    }
}
