package com.termllm.app

import com.termllm.app.listen.DownloadConstraints
import com.termllm.app.listen.DownloadCoordinator
import com.termllm.app.listen.DownloadEngine
import com.termllm.app.listen.DownloadLedger
import com.termllm.app.listen.DownloadScheduler
import com.termllm.app.listen.DownloadStatus
import com.termllm.app.listen.DownloadStore
import com.termllm.app.listen.ListenItem
import com.termllm.app.listen.ListenStore
import com.termllm.app.listen.RangeTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The coordinator is what makes a download "durable": it queues unique work,
 * mirrors durable state back into the queue exactly once, and reconciles a
 * finished worker after a process death. The scheduler is faked here so the test
 * proves the contract rather than WorkManager's own behaviour.
 */
class DownloadCoordinatorTest {

    private class FakeScheduler : DownloadScheduler {
        val enqueued = ConcurrentLinkedQueue<String>()
        val cancelled = ConcurrentLinkedQueue<String>()
        var lastConstraints: DownloadConstraints? = null
        override fun enqueue(itemId: String, constraints: DownloadConstraints) {
            enqueued += itemId
            lastConstraints = constraints
        }
        override fun cancel(itemId: String) {
            cancelled += itemId
        }
        override fun cancelAll() {
            enqueued.clear()
        }
    }

    private lateinit var root: File
    private lateinit var queueDir: File
    private lateinit var downloadDir: File
    private lateinit var scheduler: FakeScheduler
    private lateinit var store: ListenStore
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        root = createTempDir(prefix = "download-coordinator")
        queueDir = File(root, "queue").apply { mkdirs() }
        downloadDir = File(root, "listen-downloads").apply { mkdirs() }
        scheduler = FakeScheduler()
        store = ListenStore(queueDir)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        // Simulated process deaths inside tests shut the old writers down, so the
        // barriers are best effort.
        runCatching { store.flushBlocking() }
        runCatching { ListenStore.forgetForTest(queueDir) }
        runCatching { DownloadStore.open(downloadDir).flushBlocking() }
        runCatching { DownloadStore.forgetForTest(downloadDir) }
        root.deleteRecursively()
    }

    private fun item() = ListenItem(id = "listen-1", url = "https://a/1.mp3", title = "Episode")

    private fun coordinator(store: ListenStore = this.store): DownloadCoordinator {
        val downloads = DownloadStore.open(downloadDir)
        val engine = DownloadEngine(
            transfer = RangeTransfer(OkHttpClient.Builder().build()),
            dir = downloadDir,
            downloads = downloads,
            ledger = DownloadLedger(File(root, "listen-network.json")),
        )
        return DownloadCoordinator(
            store = store,
            downloads = downloads,
            engine = engine,
            scheduler = scheduler,
            scope = scope,
        ).also { coordinator ->
            // Store initialization/recovery is truly asynchronous even with an
            // Unconfined test scope. Assert only after the explicit readiness fence.
            kotlinx.coroutines.runBlocking { coordinator.awaitReady() }
        }
    }

    @Test
    fun requestingADownloadQueuesUniqueDurableWork() {
        val coordinator = coordinator()
        val queued = item()
        store.add(queued)
        coordinator.request(queued)

        assertEquals(listOf("listen-1"), scheduler.enqueued.toList())
        assertEquals(DownloadStatus.QUEUED, DownloadStore.open(downloadDir).record("listen-1")!!.status)
        assertNotNull("network constraints must be applied", scheduler.lastConstraints)
        assertTrue(scheduler.lastConstraints!!.networkRequired)
        assertFalse("metered is allowed by default", scheduler.lastConstraints!!.unmetered)
    }

    @Test
    fun uniqueJobNamesAreStablePerItem() {
        assertEquals(DownloadScheduler.uniqueName("listen-1"), DownloadScheduler.uniqueName("listen-1"))
        assertNotEquals(DownloadScheduler.uniqueName("listen-1"), DownloadScheduler.uniqueName("listen-2"))
    }

    @Test
    fun progressIsExposedForActiveDownloadsOnly() {
        val coordinator = coordinator()
        val queued = item()
        store.add(queued)
        coordinator.request(queued)

        val downloads = DownloadStore.open(downloadDir)
        downloads.markRunning("listen-1")
        downloads.markProgress("listen-1", 512, 2048)
        coordinator.reconcile()

        assertEquals(0.25f, coordinator.progress.value["listen-1"]!!, 0.001f)
        downloads.markComplete("listen-1", 2048, 2048, "v1")
        coordinator.reconcile()
        assertNull("a finished download shows the done state, not a percentage", coordinator.progress.value["listen-1"])
    }

    @Test
    fun completionMarksTheQueueItemDownloadedAndSettlesTheCallback() {
        val coordinator = coordinator()
        val queued = item()
        store.add(queued)
        var callback: Boolean? = null
        coordinator.request(queued) { done -> callback = done }

        val downloads = DownloadStore.open(downloadDir)
        File(downloadDir, "listen-1.audio").writeBytes(ByteArray(64))
        downloads.markProgress("listen-1", 2048, 2048)
        downloads.markComplete("listen-1", 2048, 2048, "\"v1\"")
        coordinator.reconcile()

        val saved = store.item("listen-1")!!
        assertTrue(saved.downloaded)
        assertEquals(File(downloadDir, "listen-1.audio").absolutePath, saved.localPath)
        assertEquals("\"v1\"", saved.etag)
        assertEquals(true, callback)
    }

    @Test
    fun cancellingStopsTheJobAndKeepsThePartialBytes() {
        val coordinator = coordinator()
        val queued = item()
        store.add(queued)
        var callback: Boolean? = null
        coordinator.request(queued) { done -> callback = done }
        File(downloadDir, "listen-1.audio").writeBytes(ByteArray(64))

        coordinator.cancel("listen-1")

        assertEquals(listOf("listen-1"), scheduler.cancelled.toList())
        assertEquals(DownloadStatus.CANCELLED, DownloadStore.open(downloadDir).record("listen-1")!!.status)
        assertEquals(false, callback)
        assertTrue("cancelling keeps the resume point", File(downloadDir, "listen-1.audio").exists())
        assertNull(coordinator.progress.value["listen-1"])
    }

    @Test
    fun aWorkerThatFinishedBeforeTheProcessDiedIsReconciledOnRestart() {
        // The worker completed the transfer and persisted COMPLETE, then the process
        // died before the queue could be updated.
        val downloads = DownloadStore.open(downloadDir)
        val queued = item()
        downloads.markQueued(queued)
        downloads.markRunning("listen-1")
        downloads.markComplete("listen-1", 2048, 2048, "\"v1\"")
        downloads.flushBlocking()
        File(downloadDir, "listen-1.audio").writeBytes(ByteArray(2048))

        store.add(queued)
        store.flushBlocking()
        ListenStore.forgetForTest(queueDir)
        DownloadStore.forgetForTest(downloadDir)

        val restartedStore = ListenStore(queueDir)
        assertFalse("the queue never saw the completion", restartedStore.item("listen-1")!!.downloaded)
        val coordinator = coordinator(restartedStore)
        // The restarted store reads its snapshot on its own thread.
        DownloadStore.open(downloadDir).flushBlocking()
        coordinator.reconcile()

        assertTrue(restartedStore.item("listen-1")!!.downloaded)
        assertEquals(File(downloadDir, "listen-1.audio").absolutePath, restartedStore.item("listen-1")!!.localPath)
        restartedStore.flushBlocking()
        ListenStore.forgetForTest(queueDir)
    }

    @Test
    fun aCompletionForARemovedItemDoesNotResurrectIt() {
        val coordinator = coordinator()
        val downloads = DownloadStore.open(downloadDir)
        val queued = item()
        downloads.markQueued(queued)
        downloads.markComplete("listen-1", 2048, 2048, "v1")
        File(downloadDir, "listen-1.audio").writeBytes(ByteArray(2048))

        coordinator.reconcile()
        assertNull("a removed episode stays removed", store.item("listen-1"))
        assertTrue(store.items().isEmpty())
    }

    @Test
    fun aFailureSettlesTheCallbackWithoutMarkingTheItemDownloaded() {
        val coordinator = coordinator()
        val queued = item()
        store.add(queued)
        var callback: Boolean? = null
        coordinator.request(queued) { done -> callback = done }

        val downloads = DownloadStore.open(downloadDir)
        downloads.markFailed("listen-1", "HTTP 500")
        coordinator.reconcile()

        assertEquals(false, callback)
        assertFalse(store.item("listen-1")!!.downloaded)
    }

    @Test
    fun durableIntentLeftByADeadProcessIsRequeuedAtStartup() {
        // The gap: the record was persisted as wanted, then the process died before
        // the WorkManager enqueue landed. A new process must re-queue it by itself.
        val downloads = DownloadStore.open(downloadDir)
        downloads.markQueued(item())
        downloads.markQueued(ListenItem(id = "listen-2", url = "https://a/2.mp3", title = "Two"))
        downloads.markRunning("listen-2")
        downloads.markQueued(ListenItem(id = "listen-3", url = "https://a/3.mp3", title = "Three"))
        downloads.markPaused("listen-3")
        downloads.flushBlocking()
        DownloadStore.forgetForTest(downloadDir)

        val coordinator = coordinator()
        runBlocking { coordinator.awaitReady() }

        assertEquals(
            "queued, running and paused work must be re-queued without a user tap",
            listOf("listen-1", "listen-2", "listen-3"),
            scheduler.enqueued.toList().sorted(),
        )
    }

    @Test
    fun startupRecoverySkipsTerminalRecordsAndIsIdempotent() {
        val downloads = DownloadStore.open(downloadDir)
        downloads.markQueued(item())
        downloads.markComplete("listen-1", 64, 64, "v1")
        downloads.markQueued(ListenItem(id = "listen-2", url = "https://a/2.mp3", title = "Two"))
        downloads.markFailed("listen-2", "HTTP 404")
        downloads.markQueued(ListenItem(id = "listen-3", url = "https://a/3.mp3", title = "Three"))
        downloads.markCancelled("listen-3")
        downloads.markQueued(ListenItem(id = "listen-4", url = "https://a/4.mp3", title = "Four"))
        downloads.flushBlocking()
        DownloadStore.forgetForTest(downloadDir)

        val coordinator = coordinator()
        runBlocking { coordinator.awaitReady() }
        coordinator.recover() // app resume: must not queue anything twice

        assertEquals(
            "only wanted work is re-queued, and only once",
            listOf("listen-4"),
            scheduler.enqueued.toList(),
        )
    }

    @Test
    fun anExplicitRequestQueuesAgainAfterACancel() {
        val coordinator = coordinator()
        val queued = item()
        store.add(queued)
        coordinator.request(queued)
        coordinator.cancel("listen-1")
        coordinator.request(queued)

        assertEquals("a user retry must reach the scheduler again", 2, scheduler.enqueued.size)
        assertEquals(listOf("listen-1"), scheduler.cancelled.toList())
    }

    @Test
    fun requestingAnAlreadyDownloadedItemCompletesImmediately() {
        val coordinator = coordinator()
        val queued = item()
        store.add(queued)
        DownloadStore.open(downloadDir).markQueued(queued)
        DownloadStore.open(downloadDir).markComplete("listen-1", 64, 64, "v1")
        File(downloadDir, "listen-1.audio").writeBytes(ByteArray(64))

        var callback: Boolean? = null
        coordinator.request(queued) { done -> callback = done }

        assertEquals("no duplicate transfer is queued", 0, scheduler.enqueued.size)
        assertEquals(true, callback)
        assertTrue(store.item("listen-1")!!.downloaded)
    }
}
