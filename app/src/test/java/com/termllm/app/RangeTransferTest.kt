package com.termllm.app

import com.termllm.app.listen.RangeTransfer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Download correctness for the offline path: completeness, resume, restart on a
 * changed resource, 206 range validation, 416 verification, credential scoping,
 * body read failures and cancellation. Nothing may be reported as downloaded
 * unless the byte count matches the advertised length.
 */
class RangeTransferTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var target: File
    private val transfer = RangeTransfer(OkHttpClient.Builder().build())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = createTempDir(prefix = "transfer")
        target = File(dir, "episode.audio")
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun bytes(count: Int) = ByteArray(count) { (it % 251).toByte() }

    @Test
    fun wholeFileWithContentLengthIsComplete() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(2048))))
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertTrue(result.complete)
        assertEquals(2048L, result.bytes)
        assertEquals(2048L, target.length())
        assertNull(result.error)
    }

    @Test
    fun resumesWithRangeAndCompletesOnlyAtTheAdvertisedLength() {
        target.writeBytes(bytes(1024))
        // First attempt: server sends the remaining 1024 of a 2048-byte file.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 1024-2047/2048")
                .setBody(Buffer().write(bytes(1024)))
        )
        val first = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertEquals("bytes=1024-", server.takeRequest().getHeader("Range"))
        assertTrue(first.complete)
        assertEquals(2048L, target.length())
    }

    @Test
    fun shortBodyIsNotComplete() {
        target.writeBytes(bytes(1024))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 1000-1999/2000")
                .setBody(Buffer().write(bytes(200)))
        )
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertFalse("a truncated transfer must not be reported complete", result.complete)
        assertEquals("Wrong-range bytes must not be appended", 1024L, target.length())
    }

    @Test
    fun changedResourceRestartsInsteadOfAppending() {
        target.writeBytes(bytes(1024))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(4096))))
        val result = transfer.fetch(server.url("/a.mp3").toString(), target, etag = "\"v1\"")
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-Range"))
        assertTrue(result.complete)
        assertEquals(4096L, target.length())
        assertEquals(4096L, result.bytes)
    }

    @Test
    fun rangeNotSatisfiableWithMatchingLengthIsComplete() {
        target.writeBytes(bytes(3000))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(3000))))
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertTrue("416 with a matching remote length means the file is whole", result.complete)
        assertEquals(3000L, result.bytes)
    }

    @Test
    fun rangeNotSatisfiableWithDifferentLengthRestarts() {
        target.writeBytes(bytes(9999))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(500))))
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertFalse(result.complete)
        assertEquals(0L, target.length())
    }

    @Test
    fun cancelLeavesPartialFileAndIsNotComplete() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-999999/1000000")
                .setBody(Buffer().write(bytes(4096)))
        )
        var calls = 0
        val result = transfer.fetch(server.url("/a.mp3").toString(), target, isCancelled = { calls++ > 0 })
        assertFalse(result.complete)
        assertTrue(result.cancelled)
    }

    @Test
    fun mediaRequestsCarryNoSourceCredentials() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(64))))
        transfer.fetch(server.url("/public.mp3").toString(), target)
        val recorded = server.takeRequest()
        assertNull("media downloads must never send cookies", recorded.getHeader("Cookie"))
        assertNull("media downloads must never send Authorization", recorded.getHeader("Authorization"))
    }

    @Test
    fun httpErrorSurfacesAndDoesNotCreateAReadyFile() {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertFalse(result.complete)
        assertEquals("HTTP 500", result.error)
    }

    // ---- hardening -------------------------------------------------------

    @Test
    fun mismatched206StartIsFlaggedForRestartAndNeverComplete() {
        target.writeBytes(bytes(1024))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 1000-1999/2000")
                .setBody(Buffer().write(bytes(200)))
        )
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertFalse(result.complete)
        assertTrue("a 206 that does not start at the local length must be refetched", result.restart)
    }

    @Test
    fun resumeAfterPartialUsesTheStoredValidator() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1023/2048")
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(bytes(1024)))
        )
        val first = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertFalse("a partial transfer is never complete", first.complete)
        server.takeRequest()

        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 1024-2047/2048")
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(bytes(1024)))
        )
        val second = transfer.fetch(server.url("/a.mp3").toString(), target)
        val recorded = server.takeRequest()
        assertEquals("bytes=1024-", recorded.getHeader("Range"))
        assertEquals("the validator must survive process death", "\"v1\"", recorded.getHeader("If-Range"))
        assertTrue(second.complete)
        assertEquals(2048L, target.length())
    }

    @Test
    fun changedValidatorOnResumeDiscardsThePartialBytes() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1023/2048")
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(bytes(1024)))
        )
        transfer.fetch(server.url("/a.mp3").toString(), target)
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(2048))))
        val second = transfer.fetch(server.url("/a.mp3").toString(), target, etag = "\"v2\"")
        val recorded = server.takeRequest()
        assertNull("a changed resource must not be range-requested", recorded.getHeader("Range"))
        assertTrue(second.complete)
        assertEquals(2048L, target.length())
    }

    @Test
    fun localBytesLongerThanTheRecordedTotalAreDiscarded() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1023/4096")
                .setBody(Buffer().write(bytes(1024)))
        )
        transfer.fetch(server.url("/a.mp3").toString(), target)
        server.takeRequest()
        java.io.FileOutputStream(target, true).use { it.write(bytes(4096)) }

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(4096))))
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertNull(server.takeRequest().getHeader("Range"))
        assertTrue(result.complete)
        assertEquals(4096L, target.length())
    }

    @Test
    fun unauthorizedIsFlaggedAndNotRetried() {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertFalse(result.complete)
        assertTrue(result.unauthorized)
        assertEquals(401, result.code)
        assertEquals("HTTP 401", result.error)
    }

    @Test
    fun rangeVerificationHeadCarriesTheSameCredential() {
        target.writeBytes(bytes(3000))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(3000))))
        val result = transfer.fetch(
            server.url("/a.mp3").toString(), target,
            credential = { "Cookie" to "term_llm_hub_token=abc" },
        )
        assertTrue(result.complete)
        val transferRequest = server.takeRequest()
        val verification = server.takeRequest()
        assertEquals("GET", transferRequest.method)
        assertEquals("the range request must be signed", "term_llm_hub_token=abc", transferRequest.getHeader("Cookie"))
        assertEquals("HEAD", verification.method)
        assertEquals(
            "the length check must be signed too",
            "term_llm_hub_token=abc",
            verification.getHeader("Cookie"),
        )
    }

    @Test
    fun timeBudgetPauseKeepsThePartialAndItsValidator() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1023/2048")
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(bytes(1024)))
        )
        var checks = 0
        val first = transfer.fetch(server.url("/a.mp3").toString(), target, shouldPause = { checks++ >= 1 })
        assertFalse(first.complete)
        assertTrue("a budget stop is a pause, not a failure", first.paused)
        assertNull("a pause is not an error", first.error)
        assertEquals(1024L, target.length())
        server.takeRequest()

        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 1024-2047/2048")
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(bytes(1024)))
        )
        val second = transfer.fetch(server.url("/a.mp3").toString(), target)
        val recorded = server.takeRequest()
        assertEquals("bytes=1024-", recorded.getHeader("Range"))
        assertEquals("the validator survives a budget pause", "\"v1\"", recorded.getHeader("If-Range"))
        assertTrue(second.complete)
        assertEquals(2048L, target.length())
    }

    @Test
    fun bodyReadFailureKeepsThePartialAndIsNeverComplete() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1023/2048")
                .setBody(Buffer().write(bytes(1024)))
        )
        transfer.fetch(server.url("/a.mp3").toString(), target)
        server.takeRequest()
        assertEquals(1024L, target.length())

        // Two identical broken responses: OkHttp may retry a dropped connection,
        // and a retry must not block on an empty queue.
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 1024-2047/2048")
                    .setBody(Buffer().write(bytes(1024)))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
        }
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertFalse(result.complete)
        assertNotNull("a broken body must surface an error", result.error)
        assertTrue("the partial bytes stay resumable", target.length() >= 1024L)
    }

    @Test
    fun cancelBeforeTheFirstRequestTouchesNoNetwork() {
        val result = transfer.fetch(server.url("/a.mp3").toString(), target, isCancelled = { true })
        assertTrue(result.cancelled)
        assertFalse(result.complete)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun chunkedResponseVerifiesItsLengthWithAHeadRequest() {
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(bytes(1500)), 512))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(1500))))
        val result = transfer.fetch(server.url("/a.mp3").toString(), target)
        assertTrue("an unadvertised length must be verified, not assumed", result.complete)
        assertEquals(1500L, result.totalBytes)
        assertEquals(1500L, target.length())
    }

    @Test
    fun oversizedDownloadIsRefusedBeforeTheBodyIsWritten() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(2048))))
        val result = transfer.fetch(server.url("/a.mp3").toString(), target, maxBytes = 1024)
        assertFalse(result.complete)
        assertTrue((result.error ?: "").startsWith("file too large"))
        assertFalse(target.exists())
    }

    @Test
    fun rangeArithmeticAsksForIdentityEncoding() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(64))))
        transfer.fetch(server.url("/a.mp3").toString(), target)
        assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
    }
}
