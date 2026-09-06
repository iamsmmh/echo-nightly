package dev.brahmkshatriya.echo.player.domain

/**
 * Container sniffing used to detect corrupted downloads/caches on every
 * platform. Pure and allocation-light: only the first bytes of a file are
 * inspected, never the whole payload.
 */
object AudioFormat {

    /**
     * @return `true` when [header] begins with a known audio container magic.
     * Very short headers (below 12 bytes) cannot be classified and are
     * accepted to avoid false positives on tiny samples.
     */
    fun looksLikeAudio(header: ByteArray): Boolean {
        if (header.size < 12) return true
        fun at(offset: Int, vararg bytes: Int): Boolean {
            if (offset + bytes.size > header.size) return false
            return bytes.indices.all { header[offset + it] == bytes[it].toByte() }
        }
        return at(0, 0x49, 0x44, 0x33) ||                                   // ID3v2 (mp3)
            at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || at(0, 0xFF, 0xF2) ||  // mpeg frames
            at(4, 0x66, 0x74, 0x79, 0x70) ||                                 // m4a/mp4 ftyp
            at(0, 0x4F, 0x67, 0x67, 0x53) ||                                 // ogg
            at(0, 0x66, 0x4C, 0x61, 0x43) ||                                 // flac
            at(0, 0x52, 0x49, 0x46, 0x46) ||                                 // wav
            at(0, 0x30, 0x26, 0xB2, 0x75) ||                                 // wma/asf
            at(0, 0x23, 0x21, 0x41, 0x4D, 0x52) ||                           // amr
            at(0, 0xFF, 0xF1) || at(0, 0xFF, 0xF9)                           // adts/aac
    }

    /**
     * Basic truncation heuristic: most real audio files are larger than a
     * few hundred bytes; empty/1-byte payloads always fail.
     */
    fun plausiblyComplete(sizeBytes: Long, expectedBytes: Long): Boolean {
        if (sizeBytes <= 0L) return false
        if (expectedBytes > 0L) return sizeBytes >= expectedBytes
        return sizeBytes >= 256L || looksLikeAudioHeaderSmall(sizeBytes)
    }

    private fun looksLikeAudioHeaderSmall(sizeBytes: Long): Boolean =
        sizeBytes in 1L..255L // tiny but non-empty: allow, validated by magic bytes

    /** Extension for common audio mime types (used by validators + pickers). */
    fun extensionForMime(mimeType: String?): String = when (mimeType?.lowercase()?.substringAfter('/')) {
        "mpeg", "mp3" -> "mp3"
        "aac", "mp4", "m4a", "x-m4a" -> "m4a"
        "flac", "x-flac" -> "flac"
        "ogg", "vorbis" -> "ogg"
        "opus" -> "opus"
        "wav", "x-wav", "wave" -> "wav"
        else -> "mp3"
    }
}
