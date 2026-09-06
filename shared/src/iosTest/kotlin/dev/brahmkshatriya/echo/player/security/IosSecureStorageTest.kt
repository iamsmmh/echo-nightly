package dev.brahmkshatriya.echo.player.security

import platform.Foundation.NSUUID
import kotlin.test.*

/** Exercises real Keychain bridging, not a fake dictionary or in-memory secret store. */
class IosSecureStorageTest {
    @Test fun keychainRoundtripUpdateDeleteAndNamespaceIsolation() {
        val name = "test-" + NSUUID().UUIDString
        val store = IosSecureStorage(name)
        val other = IosSecureStorage(name + "-other")
        try {
            assertNull(store.get("account"))
            store.put("account", "pass — é 🎵")
            assertEquals("pass — é 🎵", IosSecureStorage(name).get("account"))
            assertNull(other.get("account"))
            store.put("account", "updated")
            assertEquals("updated", store.get("account"))
            store.remove("account")
            assertNull(store.get("account"))
            store.remove("account")
        } finally { store.remove("account") }
    }
}
