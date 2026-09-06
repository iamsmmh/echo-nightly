@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.echo.player.security

import dev.brahmkshatriya.echo.common.helpers.toByteArray
import dev.brahmkshatriya.echo.common.helpers.toNSData
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

/** Device-only Keychain items remain available for background audio after first unlock. */
class IosSecureStorage(name: String) : SecureStorage {
    private val service = "dev.brahmkshatriya.echo.$name"

    private fun constant(value: CFTypeRef?): Any? = value?.let { CFBridgingRelease(CFRetain(it)) }

    private fun query(key: String): Map<Any?, Any?> = mapOf(
        constant(kSecClass) to constant(kSecClassGenericPassword),
        constant(kSecAttrService) to service, constant(kSecAttrAccount) to key
    )

    private fun <T> withDictionary(values: Map<Any?, Any?>, block: (CFDictionaryRef?) -> T): T {
        val retained = CFBridgingRetain(values)
        return try { block(retained?.reinterpret()) } finally { if (retained != null) CFRelease(retained) }
    }

    override fun get(key: String): String? = withDictionary(query(key) + mapOf(
        constant(kSecReturnData) to constant(kCFBooleanTrue), constant(kSecMatchLimit) to constant(kSecMatchLimitOne)
    )) { dictionary ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            result.value = null
            val status = SecItemCopyMatching(dictionary, result.ptr)
            when (status) {
                errSecItemNotFound -> null
                errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.decodeToString()
                    ?: throw SecureStorageException("Invalid credential data")
                else -> throw SecureStorageException("Keychain read failed ($status)")
            }
        }
    }

    override fun put(key: String, value: String) {
        val attributes = mapOf<Any?, Any?>(constant(kSecValueData) to value.encodeToByteArray().toNSData(),
            constant(kSecAttrAccessible) to constant(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly))
        val status = withDictionary(query(key)) { query ->
            withDictionary(attributes) { update -> SecItemUpdate(query, update) }
        }
        if (status == errSecItemNotFound) {
            val added = withDictionary(query(key) + attributes) { SecItemAdd(it, null) }
            if (added != errSecSuccess) throw SecureStorageException("Keychain write failed ($added)")
        } else if (status != errSecSuccess) throw SecureStorageException("Keychain update failed ($status)")
    }

    override fun remove(key: String) {
        val status = withDictionary(query(key)) { SecItemDelete(it) }
        if (status != errSecSuccess && status != errSecItemNotFound) throw SecureStorageException("Keychain deletion failed ($status)")
    }
}
