package dev.brahmkshatriya.echo.utils

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Download/extension corruption detection (Phase 1 - stability).
 *
 * Every finished artifact gets a sidecar `<file>.echo.sha256` containing
 * `sizeInBytes sha256Hex`. Playback and extension loading verify the sidecar
 * (when present) before trusting a file, so truncated or corrupted downloads
 * fall back to streaming instead of crashing or playing garbage.
 *
 * The sidecar is optional by design: files created by older versions have no
 * sidecar and stay fully usable (backward compatible).
 */
object FileIntegrity {

    private const val SIDECAR_SUFFIX = ".echo.sha256"
    private const val HEADER_BYTES = 16

    fun sidecarFor(file: File): File = File(file.parentFile, file.name + SIDECAR_SUFFIX)

    /** Compute the hash of a file; returns null on I/O failure. */
    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun record(file: File) {
        if (!file.exists() || file.length() <= 0) return
        val hash = sha256(file) ?: return
        runCatching { sidecarFor(file).writeText("${file.length()} $hash") }
    }

    /**
     * @return [Result] success when the file can be trusted (valid sidecar or
     * legacy file without one); failure when corruption is detected.
     */
    fun verify(file: File): Result<Unit> {
        if (!file.exists()) return Result.failure(IntegrityError("missing file ${file.name}"))
        if (file.length() <= 0) return Result.failure(IntegrityError("empty file ${file.name}"))
        val sidecar = sidecarFor(file)
        if (!sidecar.exists()) {
            // Legacy file: no recorded hash. Fall back to a header sniff.
            return if (hasAudioHeader(file)) Result.success(Unit)
            else Result.failure(IntegrityError("unrecognised audio data ${file.name}"))
        }
        val (size, hash) = runCatching {
            val fields = sidecar.readText().trim().split(Regex("\\s+"))
            require(fields.size == 2 && fields[1].matches(Regex("[a-fA-F0-9]{64}")))
            fields[0].toLong().also { require(it > 0) } to fields[1].lowercase()
        }.getOrElse {
            return Result.failure(IntegrityError("invalid integrity record for ${file.name}"))
        }
        if (size != file.length())
            return Result.failure(IntegrityError("size mismatch for ${file.name}"))
        val actual = sha256(file)
            ?: return Result.failure(IntegrityError("could not verify ${file.name}"))
        if (actual != hash)
            return Result.failure(IntegrityError("checksum mismatch for ${file.name}"))
        return Result.success(Unit)
    }

    /** True when the file starts with a ZIP local-file header (an APK). */
    fun hasZipHeader(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(4)
            if (raf.length() < 4) return false
            raf.readFully(header)
            val isLocal = header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
                header[2] == 3.toByte() && header[3] == 4.toByte()
            val isEmptyZip = header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
                header[2] == 5.toByte() && header[3] == 6.toByte()
            isLocal || isEmptyZip
        }
    }.getOrDefault(false)

    /** True when the first bytes look like an audio container. */
    fun hasAudioHeader(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val n = minOf(HEADER_BYTES.toLong(), raf.length()).toInt()
            val header = ByteArray(n)
            raf.readFully(header)
            dev.brahmkshatriya.echo.player.domain.AudioFormat.looksLikeAudio(header)
        }
    }.getOrDefault(false)

    class IntegrityError(message: String) : Exception(message)
}
