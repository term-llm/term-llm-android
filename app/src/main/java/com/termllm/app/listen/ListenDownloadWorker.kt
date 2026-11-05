package com.termllm.app.listen

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * One durable offline download, owned by WorkManager: it survives process death
 * and reboot, waits for the required network, and is cancelled through
 * [DownloadScheduler.cancel].
 *
 * Nothing is carried in memory between attempts — the queue and the download
 * state are re-read from disk — so an interrupted attempt resumes with
 * `Range`/`If-Range` instead of starting the episode again. Each attempt is
 * bounded by [DownloadPolicy.ATTEMPT_BUDGET_MILLIS], which ends the transfer at a
 * chunk boundary and asks WorkManager to reschedule; a stopped worker is treated
 * as a pause and retried unless the user cancelled the download explicitly.
 */
class ListenDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(DownloadScheduler.KEY_ITEM_ID)
            ?: return Result.failure()
        val context = applicationContext
        val store = ListenStore(context)
        store.awaitReady()
        val runtime = DownloadRuntime.forContext(context, mediaAuthFor(context), connectionSource(context))
        runtime.downloads.awaitReady()

        val item = store.item(itemId)
        if (item == null) {
            // Removed from the queue while the job was waiting: drop the job and
            // leave no partial bytes behind.
            runtime.downloads.markCancelled(itemId)
            RangeTransfer.discardPartial(runtime.engine.fileForId(itemId))
            runtime.downloads.forget(itemId)
            runtime.downloads.flushBlocking()
            return Result.success()
        }
        if (item.downloaded && item.localPath?.let { java.io.File(it).exists() } == true) {
            return Result.success()
        }

        // WorkManager stops a worker on cancellation AND on constraint changes.
        // Only the coordinator marks a record CANCELLED before stopping work, so a
        // stop without that marker is a pause that must be rescheduled.
        val userCancelled = { runtime.downloads.record(itemId)?.status == DownloadStatus.CANCELLED }
        val outcome = runtime.engine.run(
            item = item,
            isCancelled = { isStopped && userCancelled() },
            shouldPause = { isStopped && !userCancelled() },
        )
        runtime.downloads.flushBlocking()

        return when (outcome) {
            is DownloadOutcome.Complete -> {
                store.markDownloaded(itemId, outcome.path, outcome.etag)
                store.flushBlocking()
                Result.success()
            }
            is DownloadOutcome.Cancelled -> Result.success()
            is DownloadOutcome.Failed -> Result.failure()
            is DownloadOutcome.Retry -> {
                val failures = runtime.downloads.record(itemId)?.failures ?: (runAttemptCount + 1)
                when (DownloadPolicy.step(outcome.paused, failures)) {
                    DownloadStep.RETRY -> Result.retry()
                    DownloadStep.GIVE_UP -> Result.failure()
                }
            }
        }
    }

    private fun connectionSource(context: Context): () -> List<com.termllm.app.data.Connection> {
        val store = com.termllm.app.data.ConnectionStore(context)
        return { store.load().connections }
    }

    private fun mediaAuthFor(context: Context): MediaAuth {
        val connections = com.termllm.app.data.ConnectionStore(context)
        val credentials = com.termllm.app.data.CredentialStore(context)
        return MediaAuth({ connections.load().connections }, { id -> credentials.read(id) })
    }
}
