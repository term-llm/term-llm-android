package com.termllm.app.listen

import android.content.Context
import com.termllm.app.data.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared wiring for one app process: the download state store, the byte ledger,
 * the transfer engine, and the durable scheduler. The UI coordinator and the
 * WorkManager worker build the same runtime, which is why a worker's progress is
 * visible without IPC and why a restarted process resumes from the same state.
 *
 * Building a runtime touches no disk: the download state loads on its own IO
 * thread, so `remember { ListenDownloader(...) }` on the main thread is cheap.
 */
class DownloadRuntime(
    val dir: File,
    val downloads: DownloadStore,
    val ledger: DownloadLedger,
    val engine: DownloadEngine,
    val scheduler: DownloadScheduler,
    val preferences: () -> DownloadPreferences,
) {

    fun coordinator(store: ListenStore, scope: CoroutineScope): DownloadCoordinator =
        DownloadCoordinator(store, downloads, engine, scheduler, preferences, scope)

    companion object {

        private val sharedClient = AtomicReference<OkHttpClient?>(null)

        /** One connection pool for the whole process, cookie-less by construction. */
        fun client(): OkHttpClient = sharedClient.get() ?: synchronized(sharedClient) {
            sharedClient.get() ?: OkHttpClient.Builder()
                .followRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
                .also { sharedClient.set(it) }
        }

        fun forContext(
            context: Context,
            mediaAuth: MediaAuth?,
            connections: () -> List<Connection> = { emptyList() },
            preferences: () -> DownloadPreferences = { DownloadSettings.read(context) },
            filesDir: File? = null,
        ): DownloadRuntime {
            val app = context.applicationContext
            val base = filesDir ?: app.filesDir
            val dir = File(base, "listen-downloads")
            val downloads = DownloadStore.open(dir)
            val ledger = DownloadLedger(File(base, "listen-network.json"))
            val auth = DownloadAuth(mediaAuth, connections)
            val transfer = RangeTransfer(
                client = client(),
                // No ambient credential: every request names its item's source.
                authHeader = { null },
                redirectGuard = { origin -> auth.redirectGuard(origin) },
            )
            val engine = DownloadEngine(
                transfer = transfer,
                dir = dir,
                downloads = downloads,
                ledger = ledger,
                auth = auth,
                preferences = preferences,
                freeBytes = { dir.usableSpace.takeIf { it > 0L } },
            )
            return DownloadRuntime(dir, downloads, ledger, engine, WorkManagerDownloadScheduler(app), preferences)
        }
    }
}

/**
 * The app-side face of downloads: queues durable work, mirrors durable state into
 * the queue (so an item becomes `downloaded` exactly once, even if the worker did
 * the transfer in a process that then died), and exposes progress.
 *
 * Calls made from the UI thread only touch memory: the state change is visible
 * immediately, while the file checks and the WorkManager binder calls happen on
 * [scope]. Completion callbacks therefore arrive on a background thread — the
 * `ListenDownloader` facade dispatches them to the main looper.
 */
class DownloadCoordinator(
    private val store: ListenStore,
    private val downloads: DownloadStore,
    private val engine: DownloadEngine,
    private val scheduler: DownloadScheduler,
    private val preferences: () -> DownloadPreferences = { DownloadPreferences() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val waiters = ConcurrentHashMap<String, MutableList<(Boolean) -> Unit>>()
    private val settled = ConcurrentHashMap<String, Boolean>()

    /** Items this process has already handed to the scheduler. */
    private val dispatched = ConcurrentHashMap.newKeySet<String>()

    private val ready = CompletableDeferred<Unit>()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    init {
        scope.launch {
            // Wait for the durable snapshot, then re-queue anything a dead process
            // left wanted but never enqueued, before observing further changes.
            downloads.awaitReady()
            runCatching { recover() }
            ready.complete(Unit)
            downloads.state.collect { records -> reconcile(records) }
        }
    }

    /**
     * Suspends until the startup recovery pass has finished (tests/tools seam; the
     * UI does not need it). After this returns, every durable download that was
     * wanted at startup has a job — no user tap required.
     */
    suspend fun awaitReady() {
        ready.await()
    }

    /**
     * Re-queue durable work that is wanted but was never given to the scheduler —
     * the gap where the process died after persisting `QUEUED` (or mid-`RUNNING` /
     * `PAUSED`) and before the enqueue landed.
     *
     * Idempotent and safe to call again (app resume): an item already dispatched by
     * this process is skipped, and the scheduler's unique-work name with `KEEP`
     * refuses to start a second transfer for the same item even if two callers race.
     * Terminal records (`COMPLETE`, `FAILED`, `CANCELLED`) are never resurrected.
     */
    fun recover() {
        downloads.records().values
            .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED }
            .forEach { record -> enqueue(record.itemId) }
        reconcile(downloads.records())
    }

    /** Queue a durable download for [item]; [onDone] fires once it settles. */
    fun request(item: ListenItem, onDone: (Boolean) -> Unit = {}) {
        synchronized(waiters) {
            waiters.computeIfAbsent(item.id) { mutableListOf() }.add(onDone)
        }
        settled.remove(item.id)
        val wasComplete = downloads.record(item.id)?.status == DownloadStatus.COMPLETE
        // In-memory only: the status is visible at once, the write is async.
        downloads.markQueued(item)
        scope.launch { reconcile(downloads.records()) }
        // An explicit request always (re)queues, even after a failure or a cancel.
        dispatched.remove(item.id)
        scope.launch {
            // A scheduler that cannot enqueue must not leave a caller waiting forever.
            runCatching { dispatch(item, wasComplete) }.onFailure { settle(item.id, false) }
        }
    }

    /** Stop the job. Partial bytes are kept so a later request resumes. */
    fun cancel(itemId: String) {
        downloads.markCancelled(itemId)
        settle(itemId, false)
        scope.launch { reconcile(downloads.records()) }
        dispatched.remove(itemId)
        scope.launch { runCatching { scheduler.cancel(itemId) } }
    }

    /**
     * Remove the download entirely: stop the job, delete the partial file and its
     * validator sidecar, and forget the state. The queue item itself is untouched.
     */
    fun remove(itemId: String) {
        downloads.markCancelled(itemId)
        settle(itemId, false)
        scope.launch { reconcile(downloads.records()) }
        dispatched.remove(itemId)
        scope.launch {
            runCatching { scheduler.cancel(itemId) }
            // Reclaim the bytes even if the scheduler could not be reached.
            RangeTransfer.discardPartial(engine.fileForId(itemId))
            downloads.forget(itemId)
        }
    }

    /**
     * Suspends until nothing is queued, running or paused in this process. A record
     * waiting on WorkManager's retry backoff is `FAILED` and therefore idle; use the
     * queue's `downloaded` flag to wait for an actual completion.
     */
    suspend fun awaitIdle() {
        downloads.state.first { records -> records.values.none { it.active } }
    }

    fun progressFor(itemId: String): Float? = _progress.value[itemId]

    fun record(itemId: String): DownloadRecord? = downloads.record(itemId)

    /**
     * Bring the observable view and the queue in line with durable state. Called
     * by the state collector and directly by callers that need a barrier.
     */
    fun reconcile(records: Map<String, DownloadRecord> = downloads.records()) {
        _progress.value = records.values
            .filter { it.active }
            .mapNotNull { record -> record.progress?.let { fraction -> record.itemId to fraction } }
            .toMap()

        records.values.forEach { record ->
            when (record.status) {
                DownloadStatus.COMPLETE -> {
                    // Only ever complete an item that still exists: a finished
                    // transfer for a removed episode must not resurrect it.
                    val item = store.item(record.itemId)
                    if (item != null && !item.downloaded) {
                        val path = engine.fileForId(record.itemId)
                        if (path.exists()) store.markDownloaded(record.itemId, path.absolutePath, record.etag)
                    }
                    settle(record.itemId, true)
                }
                DownloadStatus.CANCELLED, DownloadStatus.FAILED -> settle(record.itemId, false)
                else -> Unit
            }
        }
    }

    private fun dispatch(item: ListenItem, wasComplete: Boolean) {
        val existing = downloads.record(item.id)
        val file = engine.fileFor(item)
        if (wasComplete && existing != null && file.exists()) {
            // Re-request of a finished download: nothing to fetch, just restate it.
            val bytes = file.length()
            downloads.markComplete(item.id, bytes, existing.totalBytes ?: bytes, existing.etag ?: item.etag)
            store.markDownloaded(item.id, file.absolutePath, existing.etag ?: item.etag)
            settle(item.id, true)
            return
        }
        enqueue(item.id)
    }

    /** One enqueue per item per process, so startup recovery cannot spam WorkManager. */
    private fun enqueue(itemId: String) {
        if (!dispatched.add(itemId)) return
        runCatching { scheduler.enqueue(itemId, DownloadPolicy.constraintsFor(preferences())) }
            .onFailure { dispatched.remove(itemId) }
    }

    private fun settle(itemId: String, success: Boolean) {
        if (settled.putIfAbsent(itemId, success) != null) return
        val listeners = synchronized(waiters) { waiters.remove(itemId) } ?: return
        listeners.forEach { listener ->
            runCatching { listener(success) }
        }
    }
}
