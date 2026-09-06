package dev.brahmkshatriya.echo.player.security

import kotlin.test.*

class SecureRandomTest {
    @Test fun validatesAllocationBounds() {
        assertEquals(0, secureRandomBytes(0).size)
        assertFailsWith<IllegalArgumentException> { secureRandomBytes(-1) }
        assertFailsWith<IllegalArgumentException> { secureRandomBytes(1_048_577) }
    }
    @Test fun platformGeneratorReturnsIndependentBytes() {
        val first = secureRandomBytes(32)
        val second = secureRandomBytes(32)
        assertEquals(32, first.size)
        assertFalse(first.contentEquals(second))
    }
}
