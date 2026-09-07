package dev.brahmkshatriya.echo.player.download

import dev.brahmkshatriya.echo.common.models.EchoFile
import dev.brahmkshatriya.echo.player.platform.sha256Hex

/**
 * Download repair manager implementing verifyChecksum(), verifySize(),
 * repairDownload(), cleanupCorruptedFiles(), and corruption detection.
 */
class DownloadRepairManager {

    data class RepairResult(
        val repaired: Boolean,
        val health: DownloadHealth,
        val error: String? = null
    )

    fun verifyChecksum(file: EchoFile, expectedSha256: String? = null): Boolean {
        if (!file.exists()) return false
        if (expectedSha256 == null) return DownloadSupport.checksumValid(file)
        val normalized = expectedSha256.trim().lowercase()
        if (!Regex("[a-f0-9]{64}").matches(normalized)) return false
        return file.sha256Hex() == normalized
    }

    fun verifySize(file: EchoFile, expectedBytes: Long): Boolean =
        expectedBytes >= 0 && file.exists() && file.length() == expectedBytes

    fun repairDownload(
        file: EchoFile,
        expected: DownloadIntegrityManager.Expected = DownloadIntegrityManager.Expected(),
        redownload: suspend (destination: EchoFile) -> Unit
    ): RepairResult {
        try {
            DownloadSupport.sidecarFor(file).delete()
            file.delete()
            val destination = EchoFile(file.absolutePath)
            val result = runCatching {
                kotlinx.coroutines.runBlocking { redownload(destination) }
            }.getOrElse { throw it }
            val sizeOk = expected.sizeBytes?.let { verifySize(file, it) } ?: file.exists()
            val hashOk = expected.sha256?.let { verifyChecksum(file, it) } ?: true
            if (!sizeOk || !hashOk || !DownloadSupport.looksLikeAudio(file.readPrefix(16))) {
                file.delete()
                return RepairResult(false, DownloadHealth.CORRUPT, "Replacement failed integrity validation")
            }
            DownloadSupport.recordChecksum(file)
            return RepairResult(true, DownloadHealth.HEALTHY)
        } catch (failure: Exception) {
            file.delete()
            DownloadSupport.sidecarFor(file).delete()
            return RepairResult(false, DownloadHealth.CORRUPT, failure.message ?: "Repair failed")
        }
    }

    suspend fun repairDownloadAsync(
        file: EchoFile,
        expected: DownloadIntegrityManager.Expected = DownloadIntegrityManager.Expected(),
        redownload: suspend (destination: EchoFile) -> Unit
    ): RepairResult = repairDownload(file, expected, redownload)

    fun cleanupCorruptedFiles(
        files: Iterable<EchoFile>,
        expectedSize: (EchoFile) -> Long = { -1 }
    ): List<String> {
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

    fun detectCorruption(file: EchoFile, expectedSize: Long = -1, expectedSha256: String? = null): Boolean {
        val health = DownloadHealthMonitor.inspect(file, expectedSize)
        return health == DownloadHealth.CORRUPT ||
            (expectedSha256 != null && !verifyChecksum(file, expectedSha256))
    }

    fun validateCache(file: EchoFile): Boolean {
        return verifyChecksum(file) && verifySize(file, file.length())
    }

    fun cleanupOrphans(filesDir: EchoFile): List<String> {
        val deleted = mutableListOf<String>()
        val files = filesDir.listFiles()?.toList() ?: emptyList()
        files.forEach { file ->
            val sidecar = DownloadSupport.sidecarFor(file)
            if (!file.exists() && sidecar.exists()) {
                sidecar.delete()
                deleted += sidecar.absolutePath
            } else if (file.exists() && file.name.contains(".tmp")) {
                file.delete()
                deleted += file.absolutePath
            }
        }
        return deleted
    }
}
