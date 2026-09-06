package dev.brahmkshatriya.echo.utils

import kotlinx.serialization.json.*
import kotlin.test.*

class CacheEnvelopeTest {
    @Test fun currentAndLegacyPayloadsAreReadable() {
        val payload = """{"title":"Track"}"""
        assertEquals(payload, CacheUtils.payload(CacheUtils.wrap(payload, 100), 200, 1000))
        assertEquals(payload, CacheUtils.payload(payload, 200, 1000))
        val legacy = buildJsonObject { put("_t", 100); put("_d", payload) }.toString()
        assertEquals(payload, CacheUtils.payload(legacy, 200, 1000))
    }
    @Test fun expiryAndHashFailuresAreRejected() {
        val raw = CacheUtils.wrap("original", 100)
        assertNull(CacheUtils.payload(raw, 2000, 1000))
        val edited = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        edited["payload"] = JsonPrimitive("modified")
        assertNull(CacheUtils.payload(JsonObject(edited).toString(), 200, 1000))
    }
}
