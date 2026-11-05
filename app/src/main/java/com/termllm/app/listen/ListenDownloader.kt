package com.termllm.app.listen

import android.content.Context
import com.termllm.app.data.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Offline downloads for Listen.
 *
 * A download is durable work, not a coroutine: [download] queues a unique
 * WorkManager job ([DownloadScheduler]) that survives process death and reboot,
 * waits for the network, backs off on failure and resumes from the bytes already
 * on disk (`Range` + `If-Range`, verified against the ETag that produced them).
 * Each attempt is time-bounded, so a long episode checkpoints and continues in the
 * next run instead of being killed by the job limit. An item is marked
 * "downloaded" only when the engine reports an exact byte match with the
 * advertised length; partial or failed transfers stay resumable and are never
 * presented as ready.
 *
 * Threading: constructing this class and calling [download]/[cancel]/[removeDownload]
 * from the UI thread does no disk IO — the state change is visible immediately and
 * the work is dispatched on an IO scope. [download]'s callback is always delivered
 * on the main thread, so callers may touch Compose state directly.
 *
 * Credentials are source-bound ([DownloadAuth]): a public URL never receives a
 * source's cookies, even when it sits inside a signed-in mount. The HTTP client
 * itself has no cookie jar, and redirects leaving the source origin drop the
 * credential ([MediaAuth.redirectGuard]).
 */
class ListenDownloader(
    private val context: Context,
    private val store: ListenStore,
    private val mediaAuth: MediaAuth? = null,
    /**
     * Known sources, used to check that a download's credential belongs to the
     * item's own source. Empty means "only the source id is enforced" — pass
     * `{ vm.connections }` from the UI for the strict check.
     */
    connections: () -> List<Connection> = { emptyList() },
    /**
     * Policy hook for a future settings toggle (metered networks, reserve space,
     * per-episode cap). Read on the IO scope for every enqueue, so a UI preference
     * can be passed as `{ DownloadPreferences(allowMetered = ...) }`.
     */
    preferences: () -> DownloadPreferences = { DownloadSettings.read(context) },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime = DownloadRuntime.forContext(context, mediaAuth, connections, preferences, filesDir = store.directory)
    private val coordinator = runtime.coordinator(store, scope)

    private val mainHandler: android.os.Handler? by lazy {
        android.os.Looper.getMainLooper()?.let { looper -> android.os.Handler(looper) }
    }

    /** Fractions complete for downloads that are currently active. */
    val progress: StateFlow<Map<String, Float>> = coordinator.progress

    /** Durable per-item download state (status, bytes, validator, error). */
    val downloads: StateFlow<Map<String, DownloadRecord>> = runtime.downloads.state

    /** Simple network ledger: bytes fetched per day for Listen transfers. */
    fun ledgerFile(): File = runtime.ledger.file()

    fun recordBytes(count: Long) {
        runtime.ledger.record(count)
    }

    fun todayBytes(): Long = runtime.ledger.bytesToday()

    fun fileFor(item: ListenItem): File = runtime.engine.fileFor(item)

    /**
     * Suspends until the durable download state is loaded and any download a dead
     * process left queued (but never enqueued) has been re-queued. Tests and tools
     * use this as the startup barrier; the UI does not need to wait for it.
     */
    suspend fun awaitReady() {
        runtime.downloads.awaitReady()
        coordinator.awaitReady()
    }

    /** Suspends until no download is queued, running or paused in this process. */
    suspend fun awaitIdle() {
        awaitReady()
        coordinator.awaitIdle()
    }

    /** Blocking [awaitReady] for instrumentation/tools. Never call on the main thread. */
    fun awaitReadyBlocking(timeoutMillis: Long = 10_000L): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMillis) {
            awaitReady()
            true
        } ?: false
    }

    /** Blocking [awaitIdle] for instrumentation/tools. Never call on the main thread. */
    fun awaitIdleBlocking(timeoutMillis: Long = 120_000L): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMillis) {
            awaitIdle()
            true
        } ?: false
    }

    fun status(itemId: String): DownloadRecord? = coordinator.record(itemId)

    /** Stop the job for this item. Partial bytes stay on disk so a later request resumes. */
    fun cancel(itemId: String) {
        coordinator.cancel(itemId)
    }

    /**
     * Forget this item's download: stop the job, delete the partial file and its
     * validator sidecar, and drop the durable record so the queue item no longer
     * claims any offline bytes. The daily byte ledger is cumulative usage (those
     * bytes really were fetched) and is intentionally not rewritten.
     */
    fun removeDownload(itemId: String) {
        store.clearDownloaded(itemId)
        coordinator.remove(itemId)
    }

    /**
     * Queue an offline download. [onDone] is invoked on the main thread once the
     * attempt settles in this process (true only when the file is complete); if the
     * process dies the job continues and the queue is reconciled on the next
     * [DownloadCoordinator.reconcile].
     */
    fun download(item: ListenItem, onDone: (Boolean) -> Unit = {}) {
        coordinator.request(item) { done ->
            onMain { onDone(done) }
        }
    }

    private fun onMain(block: () -> Unit) {
        val handler = mainHandler
        if (handler == null || android.os.Looper.myLooper() == handler.looper) {
            block()
        } else {
            handler.post { block() }
        }
    }
}
