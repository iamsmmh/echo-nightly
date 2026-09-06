package dev.brahmkshatriya.echo.player.domain

import dev.brahmkshatriya.echo.player.platform.validateDownloadResponse
import kotlin.test.*

class DownloadResponseTest {
    @Test fun resumeRequiresMatchingRange() {
        val plan = validateDownloadResponse(206, mapOf("content-range" to "bytes 10-19/20", "Content-Length" to "10"), 10)
        assertTrue(plan.append)
        assertEquals(20, plan.totalBytes)
        plan.verifyBodySize(10)
    }
    @Test fun ignoredResumeRangeOverwritesRatherThanAppending() {
        val plan = validateDownloadResponse(200, mapOf("Content-Length" to "20"), 10)
        assertFalse(plan.append)
        assertEquals(0, plan.offset)
    }
    @Test fun malformedOrMismatchedRangesFailClosed() {
        listOf("bytes 0-9/20", "bytes 10-9/20", "bytes 10-20/20", "bytes 10-999999999999999999999/20", "invalid").forEach {
            assertFailsWith<EchoError.Network> { validateDownloadResponse(206, mapOf("Content-Range" to it), 10) }
        }
        assertFailsWith<EchoError.Network> { validateDownloadResponse(206, emptyMap(), 10) }
    }
    @Test fun truncatedBodyIsRejected() {
        assertFailsWith<EchoError.Network> {
            validateDownloadResponse(200, mapOf("Content-Length" to "100"), 0).verifyBodySize(99)
        }
    }
    @Test fun boundedChunksRequireExact206() {
        assertFailsWith<EchoError.Network> { validateDownloadResponse(200, emptyMap(), 0, "bytes=100-199") }
        assertFailsWith<EchoError.Network> {
            validateDownloadResponse(206, mapOf("Content-Range" to "bytes 100-149/300"), 0, "bytes=100-199")
        }
        val plan = validateDownloadResponse(206, mapOf("Content-Range" to "bytes 100-199/300"), 0, "bytes=100-199")
        assertFalse(plan.append)
        assertEquals(100, plan.expectedBodyBytes)
    }
    @Test fun conflictingLengthsAndCompressionRejected() {
        assertFailsWith<EchoError.Network> {
            validateDownloadResponse(206, mapOf("Content-Range" to "bytes 0-99/100", "Content-Length" to "1"), 0)
        }
        assertFailsWith<EchoError.Network> { validateDownloadResponse(200, mapOf("Content-Encoding" to "gzip"), 0) }
    }
    @Test fun httpErrorsNeverBecomeCompletedFiles() {
        listOf(204, 301, 401, 404, 416, 500).forEach {
            assertFailsWith<EchoError.Network> { validateDownloadResponse(it, emptyMap(), 0) }
        }
    }
}
