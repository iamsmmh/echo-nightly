@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.brahmkshatriya.echo.player.security

import kotlinx.cinterop.*
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.Security.errSecSuccess

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size in 0..1_048_576)
    if (size == 0) return byteArrayOf()
    return ByteArray(size).also { bytes ->
        bytes.usePinned {
            if (SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) != errSecSuccess)
                throw SecureStorageException("Secure random generation failed")
        }
    }
}
