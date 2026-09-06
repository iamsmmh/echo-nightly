package dev.brahmkshatriya.echo.player.cache

import dev.brahmkshatriya.echo.player.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheValidatorTest {

    @Test
    fun `roundtrip preserves payload`() {
        val payload = """{"url":"https://example.com/a.mp3","expires":123}"""
        val raw = CacheValidator.wrap(payload, nowMs = 1_000)
        assertEquals(payload, CacheValidator.unwrap(raw, nowMs = 1_000))
    }

    @Test
    fun `entries expire after the ttl`() {
        val raw = CacheValidator.wrap("{}", nowMs = 0)
        assertNotNull(CacheValidator.unwrap(raw, nowMs = 5_999, ttlMs = 6_000))
        assertNull(CacheValidator.unwrap(raw, nowMs = 6_001, ttlMs = 6_000))
    }

    @Test
    fun `tampered payload fails validation`() {
        val raw = CacheValidator.wrap("""{"a":1}""", nowMs = 10)
        val envelope = kotlinx.serialization.json.Json.decodeFromString(CacheValidator.Envelope.serializer(), raw)
        val tampered = kotlinx.serialization.json.Json.encodeToString(CacheValidator.Envelope.serializer(), envelope.copy(payload = """{"a":2}"""))
        kotlin.test.assertNotEquals(raw, tampered)
        assertNull(CacheValidator.unwrap(tampered, nowMs = 20))
    }

    @Test
    fun `garbage input is a miss not a crash`() {
        assertNull(CacheValidator.unwrap("not json at all"))
        assertNull(CacheValidator.unwrap("{}"))          // missing fields
        assertNull(CacheValidator.unwrap("""{"version":99,"writtenAtMs":1,"sha256":"x","payload":"{}"}"""))
        assertTrue(CacheValidator.isCurrentFormat(CacheValidator.wrap("[]")))
    }

    @Test
    fun `future-dated entries are refused`() {
        val raw = CacheValidator.wrap("[]", nowMs = 60_001)
        assertNull(CacheValidator.unwrap(raw, nowMs = 0))
    }

    @Test
    fun `resolution cache purges corrupted entries`() {
        val store = InMemoryKeyValueStore()
        val cache = ResolutionCache(store)
        cache.put("k", """{"u":1}""")
        assertEquals("""{"u":1}""", cache.get("k"))
        store.putString("echo.cache.k", "{{{ broken")
        assertNull(cache.get("k"))
        assertNull(store.getString("echo.cache.k")) // entry was deleted
    }
}
