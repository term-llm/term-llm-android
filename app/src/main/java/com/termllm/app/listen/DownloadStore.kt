package com.termllm.app.listen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Durable download state shared by the UI process and the WorkManager worker
 * (both resolve to the same instance per download directory, so a worker's
 * progress is observable without an IPC hop).
 *
 * Construction never touches the disk: the initial snapshot is read on the
 * store's own IO thread ([awaitReady] / [flushBlocking] are the barriers), so a
 * Compose `remember { ListenDownloader(...) }` and `request()` stay off the main
 * thread. Writes are atomic (tmp + fsync + rename) and serialised on the same
 * thread, because progress updates happen while the body is still streaming: a
 * torn file would lose the resume point of every queued episode at once.
 */
class DownloadStore private constructor(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(dir, "downloads.json")
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "listen-download-state").apply { isDaemon = true }
    }

    private val _state = MutableStateFlow<Map<String, DownloadRecord>>(emptyMap())
    val state: StateFlow<Map<String, DownloadRecord>> = _state

    private val ready = CompletableDeferred<Unit>()
    private var loaded = false
    /** State written before the snapshot finished loading; replayed on top of it. */
    private val pending = HashMap<String, DownloadRecord>()
    private val removals = HashSet<String>()

    init {
        writer.execute { load() }
    }

    /** Suspends until the on-disk snapshot has been read (never blocks the caller's thread). */
    suspend fun awaitReady() {
        ready.await()
    }

    fun records(): Map<String, DownloadRecord> = _state.value

    fun record(itemId: String): DownloadRecord? = _state.value[itemId]

    fun markQueued(item: ListenItem, at: Long = System.currentTimeMillis()) {
        update(item.id, at) { previous ->
            DownloadRecord(
                itemId = item.id,
                url = item.url,
                status = DownloadStatus.QUEUED,
                bytes = previous?.bytes ?: 0L,
                totalBytes = previous?.totalBytes,
                etag = previous?.etag ?: item.etag,
                attempts = previous?.attempts ?: 0,
            )
        }
    }

    fun markRunning(itemId: String, at: Long = System.currentTimeMillis()) {
        update(itemId, at) { previous ->
            previous?.copy(status = DownloadStatus.RUNNING, attempts = previous.attempts + 1, error = null)
        }
    }

    fun markProgress(itemId: String, bytes: Long, totalBytes: Long?, at: Long = System.currentTimeMillis()) {
        update(itemId, at) { previous ->
            previous?.copy(
                bytes = bytes.coerceAtLeast(0L),
                totalBytes = totalBytes ?: previous.totalBytes,
            )
        }
    }

    /** Stopped on its own time budget or by a constraint change: resumable, not a failure. */
    fun markPaused(itemId: String, at: Long = System.currentTimeMillis()) {
        update(itemId, at) { previous ->
            previous?.copy(status = DownloadStatus.PAUSED, error = null, failures = 0)
        }
    }

    fun markComplete(itemId: String, bytes: Long, totalBytes: Long?, etag: String?, at: Long = System.currentTimeMillis()) {
        update(itemId, at) { previous ->
            previous?.copy(
                status = DownloadStatus.COMPLETE,
                bytes = bytes.coerceAtLeast(0L),
                totalBytes = totalBytes ?: previous.totalBytes,
                etag = etag ?: previous.etag,
                error = null,
                failures = 0,
            )
        }
    }

    fun markFailed(itemId: String, error: String?, at: Long = System.currentTimeMillis()) {
        update(itemId, at) { previous ->
            previous?.copy(status = DownloadStatus.FAILED, error = error, failures = previous.failures + 1)
        }
    }

    fun markCancelled(itemId: String, at: Long = System.currentTimeMillis()) {
        update(itemId, at) { previous -> previous?.copy(status = DownloadStatus.CANCELLED, error = null) }
    }

    /** The item left the queue: drop its state (its partial file is cleaned separately). */
    fun forget(itemId: String) {
        synchronized(lock) {
            val before = _state.value
            if (!before.containsKey(itemId)) {
                // Nothing loaded yet, so remember the removal and apply it to the
                // snapshot when it arrives.
                if (!loaded) removals.add(itemId)
                return
            }
            val after = before - itemId
            _state.value = after
            if (!loaded) {
                pending.remove(itemId)
                removals.add(itemId)
            } else {
                writer.execute { persist(after) }
            }
        }
    }

    /** Durability barrier: returns once the snapshot is loaded and every write hit the disk. */
    fun flushBlocking() {
        writer.submit {}.get()
    }

    private fun update(itemId: String, at: Long, transform: (DownloadRecord?) -> DownloadRecord?) {
        mutate { current ->
            val previous = current[itemId]
            val next = transform(previous) ?: return@mutate current
            current + (itemId to next.copy(itemId = itemId, updatedAt = at))
        }
    }

    private fun mutate(transform: (Map<String, DownloadRecord>) -> Map<String, DownloadRecord>) {
        synchronized(lock) {
            val before = _state.value
            val after = transform(before)
            if (after == before) return
            _state.value = after
            if (!loaded) {
                for (id in before.keys + after.keys) {
                    val record = after[id]
                    if (record == null) {
                        pending.remove(id)
                        removals.add(id)
                    } else {
                        removals.remove(id)
                        pending[id] = record
                    }
                }
                return
            }
            writer.execute { persist(after) }
        }
    }

    private fun load() {
        var restored: Map<String, DownloadRecord> = emptyMap()
        var merged: Map<String, DownloadRecord> = emptyMap()
        try {
            restored = read()
            merged = restored
            synchronized(lock) {
                val next = restored.toMutableMap()
                pending.forEach { (id, record) -> next[id] = record }
                removals.forEach { id -> next.remove(id) }
                merged = next
                _state.value = next
                loaded = true
                pending.clear()
                removals.clear()
            }
        } catch (_: Exception) {
            // Unreadable snapshot: keep the in-memory view and carry on resumable.
        } finally {
            // Every awaiter must be released, even if the snapshot was unusable.
            ready.complete(Unit)
        }
        if (merged != restored) persist(merged)
    }

    private fun persist(records: Map<String, DownloadRecord>) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "downloads.json.tmp")
            java.io.FileOutputStream(tmp).use { output ->
                val snapshot = DownloadSnapshot(records.values.toList())
                output.write(json.encodeToString(DownloadSnapshot.serializer(), snapshot).toByteArray())
                output.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            // Advisory state: the partial file and its validator sidecar still let
            // the next attempt resume, so a failed snapshot is not fatal.
        }
    }

    private fun read(): Map<String, DownloadRecord> = try {
        if (file.exists()) {
            json.decodeFromString(DownloadSnapshot.serializer(), file.readText()).records.associateBy { it.itemId }
        } else {
            emptyMap()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    companion object {
        private val shared = ConcurrentHashMap<String, DownloadStore>()

        /**
         * One instance per directory per process. Keyed by `absolutePath` (a string
         * operation) so opening a store never touches the filesystem.
         */
        fun open(dir: File): DownloadStore = shared.computeIfAbsent(dir.absolutePath) { path ->
            DownloadStore(File(path))
        }

        /** New-process simulation for isolated tests, only after a durability barrier. */
        fun forgetForTest(dir: File) {
            shared.remove(dir.absolutePath)?.writer?.shutdown()
        }
    }
}
