package com.termllm.app

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.listen.DownloadAuth
import com.termllm.app.listen.DownloadEngine
import com.termllm.app.listen.DownloadLedger
import com.termllm.app.listen.DownloadOutcome
import com.termllm.app.listen.DownloadPreferences
import com.termllm.app.listen.DownloadStatus
import com.termllm.app.listen.DownloadStore
import com.termllm.app.listen.ListenItem
import com.termllm.app.listen.MediaAuth
import com.termllm.app.listen.RangeTransfer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The download engine end to end: durable state, exact-size completion, resume
 * after process death, credential scoping, space refusal and retry classification.
 * Runs on the JVM against MockWebServer — no device, no WorkManager.
 */
class DownloadEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = createTempDir(prefix = "download-engine")
        dir = File(root, "listen-downloads").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        DownloadStore.open(dir).flushBlocking()
        DownloadStore.forgetForTest(dir)
        server.shutdown()
        root.deleteRecursively()
    }

    private fun bytes(count: Int) = ByteArray(count) { (it % 251).toByte() }

    private fun item(sourceId: String? = null, etag: String? = null) = ListenItem(
        id = "listen-1",
        url = server.url("/ep.mp3").toString(),
        title = "Episode",
        sourceId = sourceId,
        etag = etag,
    )

    private fun engine(
        auth: DownloadAuth? = null,
        preferences: DownloadPreferences = DownloadPreferences(),
        freeBytes: Long? = null,
    ): DownloadEngine = DownloadEngine(
        transfer = RangeTransfer(OkHttpClient.Builder().build()),
        dir = dir,
        downloads = DownloadStore.open(dir),
        ledger = DownloadLedger(File(root, "listen-network.json")),
        auth = auth,
        preferences = { preferences },
        freeBytes = { freeBytes },
    )

    private fun record() = DownloadStore.open(dir).record("listen-1")

    @Test
    fun completeTransferMarksTheRecordAndTheLedger() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(bytes(2048)))
        )
        val outcome = runBlocking { engine().run(item()) }
        assertTrue(outcome is DownloadOutcome.Complete)
        assertEquals(2048L, outcome.bytes)

        val record = record()!!
        assertEquals(DownloadStatus.COMPLETE, record.status)
        assertEquals(2048L, record.bytes)
        assertEquals(2048L, record.totalBytes)
        assertEquals("\"v1\"", record.etag)
        assertEquals(2048L, File(dir, "listen-1.audio").length())
        assertEquals(2048L, DownloadLedger(File(root, "listen-network.json")).bytesToday())
    }

    @Test
    fun anAlreadyCompleteFileIsNotFetchedAgain() {
        File(dir, "listen-1.audio").writeBytes(bytes(1024))
        val store = DownloadStore.open(dir)
        store.markQueued(item())
        store.markComplete("listen-1", 1024, 1024, "\"v1\"")

        val outcome = runBlocking { engine().run(item()) }
        assertTrue(outcome is DownloadOutcome.Complete)
        assertEquals("nothing to fetch", 0, server.requestCount)
    }

    @Test
    fun resumeAfterProcessDeathUsesTheStoredValidatorAndRange() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1023/2048")
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(bytes(1024)))
        )
        val first = runBlocking { engine().run(item()) }
        assertTrue("a partial transfer is retryable, not complete", first is DownloadOutcome.Retry)
        assertEquals(1024L, File(dir, "listen-1.audio").length())
        assertEquals(DownloadStatus.FAILED, record()!!.status)

        // Process death: the state and the partial file are all that survive.
        DownloadStore.open(dir).flushBlocking()
        DownloadStore.forgetForTest(dir)
        server.takeRequest()

        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 1024-2047/2048")
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(bytes(1024)))
        )
        val second = runBlocking { engine().run(item()) }
        val request = server.takeRequest()
        assertEquals("bytes=1024-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
        assertTrue(second is DownloadOutcome.Complete)
        assertEquals(2048L, second.bytes)
        assertEquals(2048L, File(dir, "listen-1.audio").length())
        assertEquals(DownloadStatus.COMPLETE, record()!!.status)
    }

    @Test
    fun anUntrustedRangeRestartsOnceFromZero() {
        File(dir, "listen-1.audio").writeBytes(bytes(512))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-511/1024")
                .setBody(Buffer().write(bytes(512)))
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(1024))))

        val outcome = runBlocking { engine().run(item()) }
        assertTrue(outcome is DownloadOutcome.Complete)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("bytes=512-", first.getHeader("Range"))
        assertNull("the untrusted partial is refetched from zero", second.getHeader("Range"))
        assertEquals(1024L, File(dir, "listen-1.audio").length())
    }

    @Test
    fun anUnauthorizedTransferFailsWithoutRetrying() {
        server.enqueue(MockResponse().setResponseCode(401))
        val outcome = runBlocking { engine().run(item(sourceId = "hub-1")) }
        assertTrue(outcome is DownloadOutcome.Failed)
        assertTrue((outcome as DownloadOutcome.Failed).unauthorized)
        assertEquals(DownloadStatus.FAILED, record()!!.status)
        assertEquals("a rejected credential is retried by nobody", 1, server.requestCount)
    }

    @Test
    fun aSourceScopedDownloadCarriesTheOwningCredential() {
        val connection = Connection(
            id = "hub-1", name = "Hub", kind = ServerKind.HUB, url = server.url("/").toString(),
        )
        val mediaAuth = MediaAuth({ listOf(connection) }, { "tok-123" })
        val auth = DownloadAuth(mediaAuth, { listOf(connection) })

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(64))))
        runBlocking { engine(auth = auth).run(item(sourceId = "hub-1")) }
        val cookie = server.takeRequest().getHeader("Cookie")
        assertNotNull(cookie)
        assertTrue(cookie!!.startsWith("term_llm_hub_token=tok-123"))
    }

    @Test
    fun aPublicItemNeverReceivesTheSourcesCookie() {
        val connection = Connection(
            id = "hub-1", name = "Hub", kind = ServerKind.HUB, url = server.url("/").toString(),
        )
        val mediaAuth = MediaAuth({ listOf(connection) }, { "tok-123" })
        val auth = DownloadAuth(mediaAuth, { listOf(connection) })

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(64))))
        runBlocking { engine(auth = auth).run(item(sourceId = null)) }
        val request = server.takeRequest()
        assertNull("an item without a source must download anonymously", request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun cancellationBeforeTheRequestTouchesNoNetwork() {
        val outcome = runBlocking { engine().run(item()) { true } }
        assertTrue(outcome is DownloadOutcome.Cancelled)
        assertEquals(0, server.requestCount)
        assertEquals(DownloadStatus.CANCELLED, record()!!.status)
    }

    @Test
    fun insufficientSpaceRefusesTheDownloadBeforeTheNetwork() {
        val outcome = runBlocking {
            engine(preferences = DownloadPreferences(reserveBytes = 100L * 1024 * 1024), freeBytes = 1024L).run(item())
        }
        assertTrue(outcome is DownloadOutcome.Retry)
        assertTrue((outcome.error ?: "").startsWith("Not enough free space"))
        assertEquals("no bytes are wasted on a download that cannot finish", 0, server.requestCount)
        assertEquals(DownloadStatus.FAILED, record()!!.status)
    }

    @Test
    fun aTruncatedBodyIsRetryableAndNeverComplete() {
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1023/4096")
                .setBody(Buffer().write(bytes(1024)))
        )
        val outcome = runBlocking { engine().run(item()) }
        assertTrue(outcome is DownloadOutcome.Retry)
        assertEquals(1024L, File(dir, "listen-1.audio").length())
        assertTrue(DownloadStatus.COMPLETE != record()!!.status)
    }

    @Test
    fun anOversizedEpisodeIsRefusedAndLeavesNoBytes() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(2048))))
        val outcome = runBlocking { engine(preferences = DownloadPreferences(maxBytes = 1024L)).run(item()) }
        assertTrue(outcome is DownloadOutcome.Failed)
        assertTrue((outcome.error ?: "").startsWith("file too large"))
        assertEquals(DownloadStatus.FAILED, record()!!.status)
        assertFalse(File(dir, "listen-1.audio").exists())
    }

    @Test
    fun aServerErrorIsRetryable() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "120"))
        val outcome = runBlocking { engine().run(item()) }
        assertTrue(outcome is DownloadOutcome.Retry)
        assertEquals(120L, (outcome as DownloadOutcome.Retry).retryAfterSeconds)
    }

    @Test
    fun aBudgetPauseIsRescheduledAndResumesToCompletion() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(2048))))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes(2048))))
        var pause = true
        val first = runBlocking { engine().run(item(), shouldPause = { pause }) }
        assertTrue("a paused attempt is retryable, not a failure", first is DownloadOutcome.Retry)
        assertTrue((first as DownloadOutcome.Retry).paused)
        assertEquals(DownloadStatus.PAUSED, record()!!.status)
        assertEquals("a budget pause must not consume the failure budget", 0, record()!!.failures)

        pause = false
        val second = runBlocking { engine().run(item(), shouldPause = { pause }) }
        assertTrue(second is DownloadOutcome.Complete)
        assertEquals(2048L, second.bytes)
        assertEquals(2048L, File(dir, "listen-1.audio").length())
    }

    @Test
    fun aMissingEpisodeFailsInsteadOfRetryingForever() {
        server.enqueue(MockResponse().setResponseCode(404))
        val outcome = runBlocking { engine().run(item()) }
        assertTrue(outcome is DownloadOutcome.Failed)
        assertEquals(1, server.requestCount)
    }
}
