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

    // CFBoolean must stay a CFBoolean. Bridging a Kotlin Map through NSNumber
    // changes its runtime type and SecItemCopyMatching rejects it with errSecParam.
    private fun <T> dictionary(block: (CFMutableDictionaryRef) -> T): T {
        val dictionary = CFDictionaryCreateMutable(null, 0,
            kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: throw SecureStorageException("Could not allocate credential query")
        return try { block(dictionary) } finally { CFRelease(dictionary) }
    }

    private fun putObject(dictionary: CFMutableDictionaryRef, key: CFTypeRef?, value: Any) {
        val retained = CFBridgingRetain(value) ?: throw SecureStorageException("Invalid credential attribute")
        try { CFDictionarySetValue(dictionary, key, retained) } finally { CFRelease(retained) }
    }

    private fun <T> query(key: String, block: (CFMutableDictionaryRef) -> T): T = dictionary { query ->
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        putObject(query, kSecAttrService, service)
        putObject(query, kSecAttrAccount, key)
        block(query)
    }

    override fun get(key: String): String? = query(key) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            result.value = null
            val status = SecItemCopyMatching(query, result.ptr)
            when (status) {
                errSecItemNotFound -> null
                errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.decodeToString()
                    ?: throw SecureStorageException("Invalid credential data")
                else -> throw SecureStorageException("Keychain read failed ($status)")
            }
        }
    }

    override fun put(key: String, value: String) = query(key) { query ->
        dictionary { attributes ->
            putObject(attributes, kSecValueData, value.encodeToByteArray().toNSData())
            CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val status = SecItemUpdate(query, attributes)
            if (status == errSecItemNotFound) {
                putObject(query, kSecValueData, value.encodeToByteArray().toNSData())
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                val added = SecItemAdd(query, null)
                if (added != errSecSuccess) throw SecureStorageException("Keychain write failed ($added)")
            } else if (status != errSecSuccess) throw SecureStorageException("Keychain update failed ($status)")
        }
    }

    override fun remove(key: String) {
        val status = query(key) { SecItemDelete(it) }
        if (status != errSecSuccess && status != errSecItemNotFound) throw SecureStorageException("Keychain deletion failed ($status)")
    }
}
