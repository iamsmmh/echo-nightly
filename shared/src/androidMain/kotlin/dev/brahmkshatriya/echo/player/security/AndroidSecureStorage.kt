package dev.brahmkshatriya.echo.player.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Keystore-backed AES-256; excluded from backup because the key is device-bound. */
@Suppress("DEPRECATION")
class AndroidSecureStorage(context: Context, name: String) : SecureStorage {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext, "secure_$name",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    override fun get(key: String): String? = preferences.getString(key, null)
    override fun put(key: String, value: String) {
        if (!preferences.edit().putString(key, value).commit()) throw SecureStorageException("Could not persist credentials")
    }
    override fun remove(key: String) {
        if (!preferences.edit().remove(key).commit()) throw SecureStorageException("Could not remove credentials")
    }
}
