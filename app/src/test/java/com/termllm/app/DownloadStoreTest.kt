package com.termllm.app

import com.termllm.app.listen.DownloadRecord
import com.termllm.app.listen.DownloadStatus
import com.termllm.app.listen.DownloadStore
import com.termllm.app.listen.ListenItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Download state must survive a process death mid-episode: the bytes fetched, the
 * validator they came from, and whether the item is complete. A lost or torn
 * snapshot would silently restart every queued download.
 */
class DownloadStoreTest {

    private lateinit var dir: File
    private lateinit var store: DownloadStore

    @Before
    fun setUp() {
        dir = createTempDir(prefix = "download-state")
        store = DownloadStore.open(dir)
    }

    @After
    fun tearDown() {
        // A test may already have simulated process death, which shuts this
        // instance's writer down; the barrier is best effort here.
        runCatching { store.flushBlocking() }
        runCatching { DownloadStore.forgetForTest(dir) }
        dir.deleteRecursively()
    }

    private fun item(url: String = "https://a/1.mp3") = ListenItem(id = "listen-1", url = url, title = "Episode")

    @Test
    fun queuedToRunningToCompleteIsObservable() {
        store.markQueued(item())
        assertEquals(DownloadStatus.QUEUED, store.record("listen-1")!!.status)
        store.markRunning("listen-1")
        assertEquals(DownloadStatus.RUNNING, store.record("listen-1")!!.status)
        assertEquals(1, store.record("listen-1")!!.attempts)
        store.markProgress("listen-1", 512, 2048)
        assertEquals(0.25f, store.record("listen-1")!!.progress!!, 0.001f)
        store.markComplete("listen-1", 2048, 2048, "\"v1\"")
        val record = store.record("listen-1")!!
        assertEquals(DownloadStatus.COMPLETE, record.status)
        assertEquals(1f, record.progress!!, 0.001f)
        assertEquals("\"v1\"", record.etag)
        assertFalse(record.active)
    }

    @Test
    fun progressIsNullUntilTheLengthIsKnown() {
        store.markQueued(item())
        assertNull(store.record("listen-1")!!.progress)
        store.markProgress("listen-1", 1024, null)
        assertNull(store.record("listen-1")!!.progress)
        assertEquals(1024L, store.record("listen-1")!!.bytes)
    }

    @Test
    fun progressCannotExceedOne() {
        store.markQueued(item())
        store.markProgress("listen-1", 4096, 2048)
        assertEquals(1f, store.record("listen-1")!!.progress!!, 0.001f)
    }

    @Test
    fun stateSurvivesProcessDeath() {
        store.markQueued(item())
        store.markRunning("listen-1")
        store.markProgress("listen-1", 1024, 2048)
        store.flushBlocking()

        DownloadStore.forgetForTest(dir)
        val restarted = DownloadStore.open(dir)
        restarted.flushBlocking() // the new instance reads its snapshot off the caller's thread
        val record = restarted.record("listen-1")!!
        assertEquals(DownloadStatus.RUNNING, record.status)
        assertEquals(1024L, record.bytes)
        assertEquals(2048L, record.totalBytes)
        assertEquals(1, record.attempts)
        restarted.flushBlocking()
    }

    @Test
    fun cancellationKeepsTheResumePoint() {
        store.markQueued(item())
        store.markProgress("listen-1", 700, 2048)
        store.markCancelled("listen-1")
        val record = store.record("listen-1")!!
        assertEquals(DownloadStatus.CANCELLED, record.status)
        assertEquals("a cancelled download stays resumable", 700L, record.bytes)
        assertFalse(record.active)
    }

    @Test
    fun failureKeepsTheErrorAndTheBytes() {
        store.markQueued(item())
        store.markRunning("listen-1")
        store.markProgress("listen-1", 700, 2048)
        store.markFailed("listen-1", "read failed: unexpected end of stream")
        val record = store.record("listen-1")!!
        assertEquals(DownloadStatus.FAILED, record.status)
        assertEquals("read failed: unexpected end of stream", record.error)
        assertEquals(700L, record.bytes)
    }

    @Test
    fun concurrentUpdatesKeepEveryRecordAndPersistAll() {
        val pool = Executors.newFixedThreadPool(4)
        repeat(40) { index ->
            pool.submit {
                store.markQueued(ListenItem(id = "id-" + index, url = "https://a/" + index + ".mp3", title = "t"))
                store.markProgress("id-" + index, index.toLong(), 100L)
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(40, store.records().size)
        store.flushBlocking()
        DownloadStore.forgetForTest(dir)

        val restarted = DownloadStore.open(dir)
        restarted.flushBlocking()
        assertEquals(40, restarted.records().size)
        assertEquals(7L, restarted.record("id-7")!!.bytes)
        restarted.flushBlocking()
    }

    @Test
    fun forgettingAnItemDropsOnlyThatRecord() {
        store.markQueued(ListenItem(id = "a", url = "https://a/a.mp3", title = "a"))
        store.markQueued(ListenItem(id = "b", url = "https://a/b.mp3", title = "b"))
        store.forget("a")
        assertNull(store.record("a"))
        assertNotNull(store.record("b"))
    }

    @Test
    fun aCorruptSnapshotIsIgnoredInsteadOfCrashing() {
        File(dir, "downloads.json").writeText("{ this is not json")
        DownloadStore.forgetForTest(dir)
        val restarted = DownloadStore.open(dir)
        restarted.flushBlocking()
        assertTrue(restarted.records().isEmpty())
        restarted.markQueued(item())
        restarted.flushBlocking()
        DownloadStore.forgetForTest(dir)
        val recovered = DownloadStore.open(dir)
        recovered.flushBlocking()
        assertEquals(DownloadStatus.QUEUED, recovered.record("listen-1")!!.status)
        recovered.flushBlocking()
    }

    @Test
    fun mutationsMadeBeforeTheSnapshotLoadsAreKept() {
        // The app can request a download before the state file has been read: the
        // in-memory status must not be thrown away by the load.
        store.markQueued(item())
        store.markProgress("listen-1", 512, 2048)
        store.forget("never-seen") // removal recorded before load too
        store.flushBlocking()
        assertEquals(DownloadStatus.QUEUED, store.record("listen-1")!!.status)
        assertEquals(512L, store.record("listen-1")!!.bytes)

        DownloadStore.forgetForTest(dir)
        val restarted = DownloadStore.open(dir)
        restarted.flushBlocking()
        assertEquals(512L, restarted.record("listen-1")!!.bytes)
        assertNull(restarted.record("never-seen"))
    }

    @Test
    fun aBudgetPauseIsNotAFailure() {
        store.markQueued(item())
        store.markRunning("listen-1")
        store.markProgress("listen-1", 1024, 4096)
        store.markPaused("listen-1")
        val paused = store.record("listen-1")!!
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertEquals("a paused transfer is still resumable work", 0, paused.failures)
        assertEquals(1024L, paused.bytes)
        assertTrue(paused.active)
    }

    @Test
    fun failuresAccumulateAndResetOnANewRequest() {
        store.markQueued(item())
        store.markRunning("listen-1")
        store.markFailed("listen-1", "HTTP 500")
        store.markRunning("listen-1")
        store.markFailed("listen-1", "HTTP 500")
        assertEquals(2, store.record("listen-1")!!.failures)
        store.markQueued(item())
        assertEquals("a fresh request starts the failure budget again", 0, store.record("listen-1")!!.failures)
    }

    @Test
    fun recordsAreKeyedByItemAndCarryTheirUrl() {
        store.markQueued(item("https://a/other.mp3"))
        val record: DownloadRecord = store.record("listen-1")!!
        assertEquals("https://a/other.mp3", record.url)
        assertEquals("listen-1", record.itemId)
    }
}
