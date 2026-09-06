package dev.brahmkshatriya.echo.player.domain

/**
 * Pure-Kotlin SHA-256 used for download corruption detection and cache
 * validation on every KMP target (including Kotlin/Native, where
 * `java.security.MessageDigest` is unavailable).
 *
 * The implementation follows FIPS 180-4; correctness is asserted against the
 * published test vectors in `shared/src/commonTest`. This hash is used for
 * integrity detection only, never as a security signature.
 */
object Sha256 {

    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
    )

    /** Streaming digest; feed [update] with arbitrary chunks and call [finish] once. */
    class Streaming {
        private val h = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19
        )
        private val buffer = ByteArray(64)
        private var bufferLen = 0
        private var totalBytes = 0L

        fun update(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            var i = offset
            val end = offset + length
            totalBytes += length
            while (i < end) {
                val n = minOf(64 - bufferLen, end - i)
                data.copyInto(buffer, bufferLen, i, i + n)
                bufferLen += n
                i += n
                if (bufferLen == 64) {
                    processBlock(buffer)
                    bufferLen = 0
                }
            }
        }

        fun finish(): ByteArray {
            val bitLength = totalBytes * 8
            update(ByteArray(1) { 0x80.toByte() })
            val pad = if (bufferLen <= 56) 56 - bufferLen else 120 - bufferLen
            update(ByteArray(pad))
            for (shift in 56 downTo 0 step 8)
                buffer[56 + (56 - shift) / 8] = ((bitLength ushr shift) and 0xFF).toByte()
            // bufferLen is now 56; write length into the remaining 8 bytes without
            // going through update() (which would re-enter padding bookkeeping).
            bufferLen += 8
            check(bufferLen == 64)
            processBlock(buffer)
            bufferLen = 0
            val out = ByteArray(32)
            for (i in h.indices) {
                out[i * 4] = (h[i] ushr 24).toByte()
                out[i * 4 + 1] = (h[i] ushr 16).toByte()
                out[i * 4 + 2] = (h[i] ushr 8).toByte()
                out[i * 4 + 3] = h[i].toByte()
            }
            return out
        }

        private fun processBlock(block: ByteArray) {
            val w = IntArray(64)
            for (i in 0 until 16) {
                val j = i * 4
                w[i] = ((block[j].toInt() and 0xFF) shl 24) or
                    ((block[j + 1].toInt() and 0xFF) shl 16) or
                    ((block[j + 2].toInt() and 0xFF) shl 8) or
                    (block[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                hh = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }

        private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
    }

    fun digest(bytes: ByteArray): ByteArray {
        val d = Streaming()
        d.update(bytes)
        return d.finish()
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX_CHARS[v ushr 4])
            append(HEX_CHARS[v and 0x0F])
        }
    }

    fun digestHex(text: String): String = hex(digest(text.encodeToByteArray()))

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
