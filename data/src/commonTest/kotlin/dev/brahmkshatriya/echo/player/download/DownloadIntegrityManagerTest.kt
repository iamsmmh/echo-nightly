package dev.brahmkshatriya.echo.player.download

import dev.brahmkshatriya.echo.common.models.resolve
import dev.brahmkshatriya.echo.common.models.write
import dev.brahmkshatriya.echo.player.platform.createMusicStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadIntegrityManagerTest {
    @Test fun repairReplacesCorruptionAndRecordsChecksum() = runTest {
        val file = createMusicStorageForIntegrityTest()
        val audio = "ID3".encodeToByteArray() + ByteArray(32)
        file.write("corrupt data".encodeToByteArray())
        val manager = DownloadIntegrityManager()
        val result = manager.repairDownload(file, DownloadIntegrityManager.Expected(sizeBytes = audio.size.toLong())) {
            it.write(audio)
        }
        assertTrue(result.repaired)
        assertTrue(manager.verifyChecksum(file))
        file.write(audio.copyOf().also { bytes -> bytes[bytes.lastIndex] = 1 })
        assertFalse(manager.verifyChecksum(file))
        file.delete(); DownloadSupport.sidecarFor(file).delete()
    }

    private fun createMusicStorageForIntegrityTest() =
        dev.brahmkshatriya.echo.player.platform.createMusicStorage().cacheDir
            .resolve("integrity-${kotlin.random.Random.nextLong()}.mp3")
}
