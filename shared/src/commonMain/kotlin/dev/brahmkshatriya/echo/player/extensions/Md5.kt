package dev.brahmkshatriya.echo.player.extensions

/**
 * A pure Kotlin MD5 implementation (RFC 1321) so Subsonic token
 * authentication works identically on Android and iOS without platform
 * crypto dependencies. Verified against the RFC test vectors in Md5Test.
 */
object Md5 {

    private val s = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    )

    private val k = IntArray(64) { i ->
        // floor(abs(sin(i + 1)) * 2^32) as a signed 32-bit int
        val d = kotlin.math.abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0
        d.toLong().toInt()
    }

    fun digest(message: ByteArray): ByteArray {
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476

        // padding: 0x80 then zeros until length ≡ 56 mod 64, then 64-bit bit length
        val originalLengthBits = message.size.toLong() * 8
        val paddedLength = ((message.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLength)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLength - 8 + i] = ((originalLengthBits ushr (8 * i)) and 0xff).toByte()
        }

        var offset = 0
        while (offset < paddedLength) {
            val m = IntArray(16) { j ->
                val base = offset + j * 4
                ((padded[base].toInt() and 0xff)) or
                    ((padded[base + 1].toInt() and 0xff) shl 8) or
                    ((padded[base + 2].toInt() and 0xff) shl 16) or
                    ((padded[base + 3].toInt() and 0xff) shl 24)
            }
            var aa = a0
            var bb = b0
            var cc = c0
            var dd = d0
            for (i in 0 until 64) {
                var f: Int
                var g: Int
                when {
                    i < 16 -> {
                        f = (bb and cc) or (bb.inv() and dd)
                        g = i
                    }
                    i < 32 -> {
                        f = (dd and bb) or (dd.inv() and cc)
                        g = (5 * i + 1) % 16
                    }
                    i < 48 -> {
                        f = bb xor cc xor dd
                        g = (3 * i + 5) % 16
                    }
                    else -> {
                        f = cc xor (bb or dd.inv())
                        g = (7 * i) % 16
                    }
                }
                f = f + aa + k[i] + m[g]
                aa = dd
                dd = cc
                cc = bb
                bb = bb + (f shl s[i] or (f ushr (32 - s[i])))
            }
            a0 += aa
            b0 += bb
            c0 += cc
            d0 += dd
            offset += 64
        }

        val result = ByteArray(16)
        listOf(a0, b0, c0, d0).forEachIndexed { index, value ->
            for (i in 0 until 4) {
                result[index * 4 + i] = ((value ushr (8 * i)) and 0xff).toByte()
            }
        }
        return result
    }

    fun digestHex(message: String): String = toHex(digest(message.encodeToByteArray()))

    fun toHex(bytes: ByteArray): String = buildString {
        bytes.forEach { b ->
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4])
            append(HEX[v and 0x0f])
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
