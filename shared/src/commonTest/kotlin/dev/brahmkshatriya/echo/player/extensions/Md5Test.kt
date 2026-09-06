package dev.brahmkshatriya.echo.player.extensions

import kotlin.test.Test
import kotlin.test.assertEquals

class Md5Test {
    @Test
    fun `rfc 1321 vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.digestHex(""))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Md5.digestHex("a"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.digestHex("abc"))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", Md5.digestHex("message digest"))
        assertEquals("689de1e396ad9c089ae2b9aaffd6faf7", Md5.digestHex("1234567890".repeat(7)))
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", Md5.digestHex("1234567890".repeat(8)))
    }
}
