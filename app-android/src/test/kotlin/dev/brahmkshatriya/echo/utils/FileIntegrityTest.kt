package dev.brahmkshatriya.echo.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class FileIntegrityTest {
    private fun withAudio(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("echo-integrity").toFile()
        try {
            val file = File(directory, "track.mp3")
            file.writeBytes("ID3".toByteArray() + ByteArray(100))
            block(file)
        } finally { directory.deleteRecursively() }
    }
    @Test fun legacyAudioAndValidRecordedHashesRemainReadable() = withAudio { file ->
        assertTrue(FileIntegrity.verify(file).isSuccess)
        FileIntegrity.record(file)
        assertTrue(FileIntegrity.verify(file).isSuccess)
    }
    @Test fun malformedPresentRecordNeverDowngradesToLegacyTrust() = withAudio { file ->
        FileIntegrity.sidecarFor(file).writeText("broken")
        assertTrue(FileIntegrity.verify(file).isFailure)
        FileIntegrity.sidecarFor(file).writeText("${file.length()} abc")
        assertTrue(FileIntegrity.verify(file).isFailure)
    }
    @Test fun sameLengthTamperingIsRejected() = withAudio { file ->
        FileIntegrity.record(file)
        val bytes = file.readBytes()
        bytes[50] = 42
        file.writeBytes(bytes)
        assertTrue(FileIntegrity.verify(file).isFailure)
    }
}
