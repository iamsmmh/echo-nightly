package dev.brahmkshatriya.echo.player.download

import dev.brahmkshatriya.echo.common.models.resolve
import dev.brahmkshatriya.echo.common.models.write
import dev.brahmkshatriya.echo.player.platform.createMusicStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DownloadRepairRegressionTest {

    @Test
    fun repairReplacesCorruptFile() = runTest {
        val manager = DownloadRepairManager()
        val file = createMusicStorageForIntegrityTest()
        val audio = "ID3".encodeToByteArray() + ByteArray(32)
        file.write("corrupt".encodeToByteArray())
        val result = manager.repairDownload(file, DownloadIntegrityManager.Expected(sizeBytes = audio.size.toLong())) {
            it.write(audio)
        }
        assertTrue(result.repaired)
        assertTrue(manager.verifyChecksum(file))
        file.delete()
        DownloadSupport.sidecarFor(file).delete()
    }

    @Test
    fun cleanupRemovesTempAndOrphanSidecars() = runTest {
        val manager = DownloadRepairManager()
        val file = createMusicStorageForIntegrityTest()
        val tmp = dev.brahmkshatriya.echo.common.models.EchoFile(file.absolutePath + ".tmp")
        tmp.write("partial".encodeToByteArray())
        val deleted = manager.cleanupOrphans(listOf(tmp))
        assertTrue(deleted.contains(tmp.absolutePath))
        assertTrue(!tmp.exists())
        file.delete()
    }

    @Test
    fun cleanupRemovesCorruptFiles() = runTest {
        val manager = DownloadRepairManager()
        val file = createMusicStorageForIntegrityTest()
        file.write("bad".encodeToByteArray())
        val deleted = manager.cleanupCorruptedFiles(listOf(file))
        assertTrue(deleted.contains(file.absolutePath))
        file.delete()
    }

    private fun createMusicStorageForIntegrityTest() =
        dev.brahmkshatriya.echo.player.platform.createMusicStorage().cacheDir
            .resolve("repair-regression-${kotlin.random.Random.nextLong()}.mp3")
}
