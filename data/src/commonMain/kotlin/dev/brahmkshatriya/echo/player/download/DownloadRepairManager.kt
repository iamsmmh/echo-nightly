package dev.brahmkshatriya.echo.player.download

import dev.brahmkshatriya.echo.common.models.EchoFile

/**
 * Download repair facade over [DownloadIntegrityManager].
 *
 * Directory listing is not part of the frozen [EchoFile] API, so orphan cleanup
 * operates on an explicit file list supplied by the caller.
 */
class DownloadRepairManager(
    private val integrity: DownloadIntegrityManager = DownloadIntegrityManager()
) {

    data class RepairResult(
        val repaired: Boolean,
        val health: DownloadHealth,
        val error: String? = null
    )

    fun verifyChecksum(file: EchoFile, expectedSha256: String? = null): Boolean =
        integrity.verifyChecksum(file, expectedSha256)

    fun verifySize(file: EchoFile, expectedBytes: Long): Boolean =
        integrity.verifySize(file, expectedBytes)

    suspend fun repairDownload(
        file: EchoFile,
        expected: DownloadIntegrityManager.Expected = DownloadIntegrityManager.Expected(),
        redownload: suspend (destination: EchoFile) -> Unit
    ): RepairResult {
        val result = integrity.repairDownload(file, expected, redownload)
        return RepairResult(result.repaired, result.health, result.error)
    }

    fun cleanupCorruptedFiles(
        files: Iterable<EchoFile>,
        expectedSize: (EchoFile) -> Long = { -1 }
    ): List<String> = integrity.cleanupCorruptedFiles(files, expectedSize)

    fun detectCorruption(file: EchoFile, expectedSize: Long = -1, expectedSha256: String? = null): Boolean =
        integrity.detectCorruption(file, expectedSha256, expectedSize)

    fun validateCache(file: EchoFile): Boolean =
        verifyChecksum(file) && verifySize(file, file.length())

    /**
     * Removes leftover `.tmp` / `.part` / `.lock` files and orphan integrity sidecars.
     * Callers must pass the directory contents — [EchoFile] has no `listFiles()`.
     */
    fun cleanupOrphans(files: Iterable<EchoFile>): List<String> {
        val deleted = mutableListOf<String>()
        files.forEach { file ->
            val name = file.name
            val isTemp = name.endsWith(".tmp", ignoreCase = true) ||
                name.endsWith(".part", ignoreCase = true) ||
                name.endsWith(".lock", ignoreCase = true)
            if (isTemp && file.exists()) {
                file.delete()
                deleted += file.absolutePath
            }
            if (integrity.isOrphanSidecar(file)) {
                val sidecar = DownloadSupport.sidecarFor(file)
                sidecar.delete()
                deleted += sidecar.absolutePath
            }
        }
        return deleted
    }
}
