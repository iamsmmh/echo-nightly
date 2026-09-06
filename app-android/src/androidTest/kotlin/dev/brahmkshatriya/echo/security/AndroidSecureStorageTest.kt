package dev.brahmkshatriya.echo.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brahmkshatriya.echo.player.library.SettingsRepository
import dev.brahmkshatriya.echo.player.platform.AndroidKeyValueStore
import dev.brahmkshatriya.echo.player.platform.EchoPlayerAndroid
import dev.brahmkshatriya.echo.player.security.AndroidSecureStorage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidSecureStorageTest {
    @Test fun encryptedPreferencesRoundtripAndMigrationUseRealKeystore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        EchoPlayerAndroid.init(context)
        val namespace = "credential-test-${UUID.randomUUID()}"
        try {
            val secrets = AndroidSecureStorage(context, namespace)
            secrets.put("first", "sensitive-credential")
            assertEquals("sensitive-credential", AndroidSecureStorage(context, namespace).get("first"))
            val raw = context.getSharedPreferences("secure_$namespace", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(raw.contains("sensitive-credential"))
            secrets.put("first", "unicode — é 🎵")
            assertEquals("unicode — é 🎵", secrets.get("first"))
            secrets.remove("first")
            assertNull(secrets.get("first"))
            val settings = AndroidKeyValueStore(namespace)
            settings.putStringDurably("echo.player.settings", """{"subsonicPassword":"legacy-credential"}""")
            assertEquals("legacy-credential", SettingsRepository(settings, secrets).settings.subsonicPassword)
            assertFalse(settings.getString("echo.player.settings")!!.contains("legacy-credential"))
            assertEquals("legacy-credential", SettingsRepository(AndroidKeyValueStore(namespace), AndroidSecureStorage(context, namespace)).settings.subsonicPassword)
        } finally {
            context.deleteSharedPreferences(namespace)
            context.deleteSharedPreferences("secure_$namespace")
        }
    }
}
