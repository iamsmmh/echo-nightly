package dev.brahmkshatriya.echo.player.download

import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.player.platform.readPrefix
import dev.brahmkshatriya.echo.player.platform.sha256Hex

/** SHA-256 integrity, resume verification and automatic repair for offline media. */
class DownloadIntegrityManager {
    data class Expected(val sizeBytes: Long? = null, val sha256: String? = null)
    data class RepairResult(val repaired: Boolean, val health: DownloadHealth, val error: String? = null)

    fun verifyChecksum(file: EchoFile, expectedSha256: String? = null): Boolean {
        if (!file.exists()) return false
        if (expectedSha256 == null) return DownloadSupport.checksumValid(file)
        val normalized = expectedSha256.trim().lowercase()
        if (!Regex("[a-f0-9]{64}").matches(normalized)) return false
        return file.sha256Hex() == normalized
    }

    fun verifySize(file: EchoFile, expectedBytes: Long): Boolean =
        expectedBytes >= 0 && file.exists() && file.length() == expectedBytes

    /** A partial file is resumable only when its length is strictly below the known total. */
    fun verifyResume(file: EchoFile, expectedBytes: Long): Boolean =
        file.exists() && file.length() in 1 until expectedBytes

    /**
     * [redownload] must replace the destination (never append unverified bytes). The resulting
     * file is validated before a fresh integrity sidecar is committed.
     */
    suspend fun repairDownload(
        file: EchoFile,
        expected: Expected = Expected(),
        redownload: suspend (destination: EchoFile) -> Unit
    ): RepairResult = try {
        DownloadSupport.sidecarFor(file).delete()
        file.delete()
        redownload(file)
        val sizeOk = expected.sizeBytes?.let { verifySize(file, it) } ?: file.exists()
        val hashOk = expected.sha256?.let { verifyChecksum(file, it) } ?: true
        if (!sizeOk || !hashOk || !DownloadSupport.looksLikeAudio(file.readPrefix(16))) {
            file.delete()
            RepairResult(false, DownloadHealth.CORRUPT, "Replacement failed integrity validation")
        } else {
            DownloadSupport.recordChecksum(file)
            RepairResult(true, DownloadHealth.HEALTHY)
        }
    } catch (failure: Exception) {
        file.delete()
        DownloadSupport.sidecarFor(file).delete()
        RepairResult(false, DownloadHealth.CORRUPT, failure.message ?: "Repair failed")
    }

    /** Deletes corrupt media and sidecars; healthy and legacy-valid files are retained. */
    fun cleanupCorruptedFiles(files: Iterable<EchoFile>, expectedSize: (EchoFile) -> Long = { -1 }): List<String> {
        val deleted = mutableListOf<String>()
        files.forEach { file ->
            val health = DownloadHealthMonitor.inspect(file, expectedSize(file))
            if (health == DownloadHealth.CORRUPT || health == DownloadHealth.PARTIAL) {
                file.delete()
                DownloadSupport.sidecarFor(file).delete()
                deleted += file.absolutePath
            }
        }
        return deleted
    }
}
