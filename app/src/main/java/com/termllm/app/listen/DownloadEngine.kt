package com.termllm.app.listen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What one download attempt produced. The scheduler maps this onto its own retry
 * policy: only [Retry] is worth another attempt, [Failed] would fail again.
 */
sealed class DownloadOutcome {
    abstract val bytes: Long
    abstract val totalBytes: Long?
    abstract val etag: String?
    abstract val error: String?

    data class Complete(
        override val bytes: Long,
        override val totalBytes: Long?,
        override val etag: String?,
        val path: String,
    ) : DownloadOutcome() {
        override val error: String? get() = null
    }

    data class Retry(
        override val bytes: Long,
        override val totalBytes: Long?,
        override val etag: String?,
        override val error: String,
        val retryAfterSeconds: Long? = null,
        /**
         * True when the attempt stopped on its own time budget (or a constraint
         * stop) rather than failing: the work must be rescheduled, and it must not
         * count against the failure cap.
         */
        val paused: Boolean = false,
    ) : DownloadOutcome()

    data class Failed(
        override val bytes: Long,
        override val totalBytes: Long?,
        override val etag: String?,
        override val error: String,
        val unauthorized: Boolean = false,
    ) : DownloadOutcome()

    data class Cancelled(
        override val bytes: Long,
        override val totalBytes: Long?,
        override val etag: String?,
    ) : DownloadOutcome() {
        override val error: String? get() = null
    }
}

/**
 * One offline download, executed against [RangeTransfer] and recorded durably in
 * [DownloadStore]. Deliberately free of Android types: the WorkManager worker and
 * the tests drive the same engine, and every side effect either writes a file or
 * updates the state store.
 *
 * Work is bounded: after [budgetMillis] the transfer stops at a chunk boundary,
 * keeps the partial file and its validator, and reports a paused [DownloadOutcome.Retry]
 * so the worker can hand the rest to its next run well before WorkManager kills it.
 * There is no foreground service and no build flag involved.
 */
class DownloadEngine(
    private val transfer: RangeTransfer,
    private val dir: File,
    private val downloads: DownloadStore,
    private val ledger: DownloadLedger,
    private val auth: DownloadAuth? = null,
    private val preferences: () -> DownloadPreferences = { DownloadPreferences() },
    /** Free space on the download volume; null means "unknown, do not gate". */
    private val freeBytes: () -> Long? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val budgetMillis: Long = DownloadPolicy.ATTEMPT_BUDGET_MILLIS,
) {

    fun fileFor(item: ListenItem): File = fileForId(item.id)

    fun fileForId(itemId: String): File = File(dir, DownloadPolicy.fileNameFor(itemId))

    /**
     * Run one bounded attempt. [isCancelled] means the user stopped this download;
     * [shouldPause] means the caller must stop (WorkManager stopped the worker for
     * a constraint change) but the download is still wanted.
     */
    suspend fun run(
        item: ListenItem,
        shouldPause: () -> Boolean = { false },
        isCancelled: () -> Boolean = { false },
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        downloads.awaitReady()
        attempt(item, isCancelled, shouldPause)
    }

    private fun attempt(item: ListenItem, isCancelled: () -> Boolean, shouldPause: () -> Boolean): DownloadOutcome {
        val target = fileFor(item)
        val preferencesNow = preferences()
        val startedAt = now()
        dir.mkdirs()

        if (downloads.record(item.id) == null) downloads.markQueued(item, now())
        var known = downloads.record(item.id)

        // Idempotent: bytes we already hold for this resource are not fetched twice.
        if (target.exists() && target.length() > 0L) {
            val recorded = known?.totalBytes
            if (DownloadPolicy.canMarkComplete(target.length(), recorded)) {
                val etag = known?.etag ?: item.etag
                downloads.markComplete(item.id, target.length(), recorded, etag, now())
                return DownloadOutcome.Complete(target.length(), recorded, etag, target.absolutePath)
            }
        }

        if (isCancelled()) {
            downloads.markCancelled(item.id, now())
            return DownloadOutcome.Cancelled(target.length(), known?.totalBytes, known?.etag)
        }

        if (!DownloadPolicy.hasSpace(freeBytes(), known?.totalBytes, preferencesNow.reserveBytes)) {
            val missing = DownloadPolicy.missingSpace(freeBytes(), known?.totalBytes, preferencesNow.reserveBytes)
            val error = "Not enough free space" + if (missing != null) " (need " + missing + " more bytes)" else ""
            downloads.markFailed(item.id, error, now())
            return DownloadOutcome.Retry(target.length(), known?.totalBytes, known?.etag, error)
        }

        downloads.markRunning(item.id, now())
        var lastProgressAt = 0L
        val onProgress: (Long, Long?) -> Unit = { bytes, total ->
            val at = now()
            if (at - lastProgressAt >= PROGRESS_INTERVAL_MS || (total != null && bytes >= total)) {
                lastProgressAt = at
                downloads.markProgress(item.id, bytes, total, at)
            }
        }
        // Our own budget first, so a long episode checkpoints before WorkManager kills us.
        val pauseSignal = {
            shouldPause() || (budgetMillis > 0L && now() - startedAt >= budgetMillis)
        }

        var before = if (target.exists()) target.length() else 0L
        var result = fetch(item, target, known, isCancelled, pauseSignal, onProgress, preferencesNow.maxBytes)
        var fetched = (result.bytes - before).coerceAtLeast(0L)
        if (result.restart && !result.cancelled && !result.complete && !result.paused) {
            // The bytes on disk belong to something else: drop them and refetch once
            // from zero rather than retrying an append that can never settle.
            RangeTransfer.discardPartial(target)
            downloads.markProgress(item.id, 0L, result.totalBytes, now())
            before = if (target.exists()) target.length() else 0L
            result = fetch(item, target, downloads.record(item.id) ?: known, isCancelled, pauseSignal, onProgress, preferencesNow.maxBytes)
            fetched += (result.bytes - before).coerceAtLeast(0L)
        }
        if (fetched > 0L) ledger.record(fetched, 1, now())
        known = downloads.record(item.id) ?: known

        val total = result.totalBytes ?: known?.totalBytes
        if (result.cancelled) {
            downloads.markProgress(item.id, result.bytes, total, now())
            downloads.markCancelled(item.id, now())
            return DownloadOutcome.Cancelled(result.bytes, total, result.etag)
        }
        if (result.paused) {
            // Resumable by construction: bytes and validator are already on disk.
            downloads.markProgress(item.id, result.bytes, total, now())
            downloads.markPaused(item.id, now())
            return DownloadOutcome.Retry(
                result.bytes, total, result.etag,
                "Paused at the attempt time budget; resuming later", retryAfterSeconds = 0L, paused = true,
            )
        }
        if (result.complete && DownloadPolicy.canMarkComplete(result.bytes, total) && target.length() == result.bytes) {
            downloads.markComplete(item.id, result.bytes, total, result.etag, now())
            return DownloadOutcome.Complete(result.bytes, total, result.etag, target.absolutePath)
        }
        if (result.unauthorized) {
            val error = result.error ?: "The source rejected the download credential"
            downloads.markFailed(item.id, error, now())
            return DownloadOutcome.Failed(result.bytes, total, result.etag, error, unauthorized = true)
        }
        if (!retryable(result)) {
            val error = result.error ?: "Download failed"
            downloads.markFailed(item.id, error, now())
            return DownloadOutcome.Failed(result.bytes, total, result.etag, error)
        }
        val error = result.error ?: "Transfer ended before the advertised length"
        downloads.markProgress(item.id, result.bytes, total, now())
        downloads.markFailed(item.id, error, now())
        return DownloadOutcome.Retry(result.bytes, total, result.etag, error, result.retryAfterSeconds)
    }

    private fun fetch(
        item: ListenItem,
        target: File,
        known: DownloadRecord?,
        isCancelled: () -> Boolean,
        shouldPause: () -> Boolean,
        onProgress: (Long, Long?) -> Unit,
        maxBytes: Long,
    ): RangeTransfer.Result = transfer.fetch(
        url = item.url,
        target = target,
        etag = item.etag ?: known?.etag,
        isCancelled = isCancelled,
        shouldPause = shouldPause,
        credential = auth?.provider(item.sourceId),
        onProgress = onProgress,
        maxBytes = maxBytes,
    )

    private fun retryable(result: RangeTransfer.Result): Boolean {
        val error = result.error
        // A policy refusal is not a transport problem: another attempt changes nothing.
        if (error != null && error.startsWith(TOO_LARGE)) return false
        if (result.restart) return true
        val code = result.code
        if (code == null) return true
        if (code == 200 || code == 206) return true
        if (code in 500..599) return true
        if (code == 408 || code == 429 || code == 416) return true
        // Everything else in 4xx means the request itself is wrong (400, 404, 410).
        return false
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
        /** Matches the refusal text produced by [RangeTransfer] for oversized bodies. */
        const val TOO_LARGE = "file too large"
    }
}
