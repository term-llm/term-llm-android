package com.termllm.app

import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.termllm.app.listen.DownloadScheduler
import com.termllm.app.listen.DownloadStatus
import com.termllm.app.listen.DownloadStore
import com.termllm.app.listen.ListenDownloader
import com.termllm.app.listen.ListenItem
import com.termllm.app.listen.ListenStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Device-level proof of the durable download path: the real WorkManager queue, the
 * real queue store (the worker and this test must share `filesDir`, otherwise the
 * worker cannot find the item), the real byte-range transfer, and the real
 * cancellation path.
 *
 * These are integration tests, so they poll; each poll has a hard timeout and
 * every failure message says which invariant broke.
 */
@RunWith(AndroidJUnit4::class)
class DownloadDurabilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var server: MockWebServer
    private lateinit var store: ListenStore
    private lateinit var downloader: ListenDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // The worker builds ListenStore(context), so the test must use the same dir.
        store = ListenStore(context)
        store.flushBlocking()
        downloader = ListenDownloader(context, store)
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {
            // A broken connection is the point of the cancellation test.
        }
        store.flushBlocking()
    }

    private fun bytes(count: Int) = ByteArray(count) { (it % 251).toByte() }

    private fun await(timeoutMillis: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        assertTrue(message, condition())
    }

    private fun workInfos(itemId: String): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DownloadScheduler.uniqueName(itemId))
            .get(20, TimeUnit.SECONDS)

    private fun pendingWork(itemId: String): List<WorkInfo> = workInfos(itemId).filter {
        it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED
    }

    @Test
    fun durableDownloadCompletesThroughWorkManagerAndMarksTheItemOffline() {
        val body = bytes(8192)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(body))
        )
        val item = store.add(
            ListenItem(
                id = "durable-" + System.nanoTime(),
                url = server.url("/episode.mp3").toString(),
                title = "Durable episode",
            )
        )
        store.flushBlocking()
        assertNotNull("the queue must be readable before the job runs", store.item(item.id))

        val callbackOnMain = AtomicBoolean(false)
        val callbackValue = AtomicReference<Boolean?>(null)
        val callback = CountDownLatch(1)
        downloader.download(item) { done ->
            callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
            callbackValue.set(done)
            callback.countDown()
        }

        await(90_000, "the worker must mark the episode offline") {
            store.item(item.id)?.downloaded == true
        }

        val saved = store.item(item.id)!!
        val path = downloader.fileFor(item)
        assertEquals("the queue must point at the downloaded file", path.absolutePath, saved.localPath)
        assertEquals("the whole episode must be on disk", 8192L, File(path.absolutePath).length())
        assertEquals("\"v1\"", saved.etag)
        assertEquals(DownloadStatus.COMPLETE, downloader.status(item.id)?.status)

        assertTrue("the completion callback must fire", callback.await(30, TimeUnit.SECONDS))
        assertTrue("callbacks reach the main thread so Compose state is safe", callbackOnMain.get())
        assertEquals(true, callbackValue.get())

        // Queue publication precedes WorkManager's terminal database commit.
        // Wait for that commit instead of racing a successful worker's return.
        await(10_000, "a finished download must not leave a duplicate job behind") {
            pendingWork(item.id).isEmpty()
        }

        downloader.removeDownload(item.id)
        store.remove(item.id)
    }

    @Test
    fun removeDownloadCancelsTheJobAndReclaimsThePartialFile() {
        // A body the worker cannot finish quickly, so there is a partial file to
        // reclaim and a job still running when we remove it.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-65535/1048576")
                .setBody(Buffer().write(bytes(65536)))
                .throttleBody(4096, 1, TimeUnit.SECONDS)
        )
        val item = store.add(
            ListenItem(
                id = "remove-" + System.nanoTime(),
                url = server.url("/slow.mp3").toString(),
                title = "Slow episode",
            )
        )
        store.flushBlocking()
        downloader.download(item)
        val path = downloader.fileFor(item)

        await(30_000, "the worker must have started writing bytes") {
            path.exists() && path.length() > 0L
        }
        assertFalse("a partial file must never look complete", store.item(item.id)?.downloaded == true)

        downloader.removeDownload(item.id)

        await(60_000, "removal must drop the durable state") { downloader.status(item.id) == null }
        assertNull("the download record is gone", downloader.status(item.id))
        assertTrue(
            "removal must keep no partial bytes: " + (if (path.exists()) path.length() else 0L) + " left",
            !path.exists(),
        )
        await(30_000, "removal must stop the unique job") { pendingWork(item.id).isEmpty() }

        store.remove(item.id)
    }

    @Test
    fun aStrandedQueuedRecordIsRequeuedAtStartupWithoutAUserTap() {
        val body = bytes(4096)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"v1\"")
                .setBody(Buffer().write(body))
        )
        val item = store.add(
            ListenItem(
                id = "stranded-" + System.nanoTime(),
                url = server.url("/stranded.mp3").toString(),
                title = "Stranded episode",
            )
        )
        store.flushBlocking()

        // Exactly the crash window: durable intent, no job (the process died after
        // persisting QUEUED and before the enqueue reached WorkManager).
        val downloads = DownloadStore.open(File(context.filesDir, "listen-downloads"))
        downloads.markQueued(item)
        downloads.flushBlocking()

        // A new "process": construction plus the startup recovery pass.
        val facade = ListenDownloader(context, store)
        assertTrue("startup recovery must finish", facade.awaitReadyBlocking(20_000))

        await(90_000, "the stranded download must be re-queued and complete by itself") {
            store.item(item.id)?.downloaded == true
        }
        assertEquals(4096L, facade.fileFor(item).length())
        assertTrue("no job must be left pending", pendingWork(item.id).isEmpty())
        facade.removeDownload(item.id)
        store.remove(item.id)
    }

    @Test
    fun requestingAndCancellingADownloadDoesNoDiskIOOnTheUiThread() {
        val item = store.add(
            ListenItem(
                id = "strict-" + System.nanoTime(),
                url = server.url("/strict.mp3").toString(),
                title = "Strict mode episode",
            )
        )
        store.flushBlocking()
        server.enqueue(MockResponse().setResponseCode(500))

        val previous = StrictMode.getThreadPolicy()
        val violation = AtomicReference<String?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskWrites()
                    .detectDiskReads()
                    .penaltyDeath()
                    .build()
            )
            try {
                // Construction (runtime + store + scheduler), queueing and cancelling
                // must stay off the disk: the UI thread only publishes state.
                val facade = ListenDownloader(context, store)
                facade.download(item)
                facade.cancel(item.id)
                facade.removeDownload(item.id)
            } catch (e: Throwable) {
                violation.set(e.stackTraceToString())
            } finally {
                StrictMode.setThreadPolicy(previous)
            }
        }
        assertNull("UI-thread disk IO in the download path: " + violation.get(), violation.get())
        store.remove(item.id)
    }
}
