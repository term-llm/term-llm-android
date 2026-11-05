package com.termllm.app.listen

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Durable job queue for offline downloads. Kept behind an interface so the
 * coordinator can be tested without WorkManager (and so a future scheduler swap
 * touches one class).
 */
interface DownloadScheduler {

    /** Queue (or keep) the unique job for this item. */
    fun enqueue(itemId: String, constraints: DownloadConstraints)

    /** Stop the job for this item; a partial file stays resumable. */
    fun cancel(itemId: String)

    /** Stop every Listen download. */
    fun cancelAll()

    companion object {
        const val KEY_ITEM_ID = "listen-download-item"

        const val TAG = "listen-download"

        /** Unique work name: one durable job per queue item, never a duplicate. */
        fun uniqueName(itemId: String): String = "listen-download:" + itemId
    }
}

/**
 * WorkManager-backed scheduler: survives process death and reboot, waits for the
 * network, defers while storage is low, and backs off exponentially on retry.
 */
class WorkManagerDownloadScheduler(context: Context) : DownloadScheduler {

    private val appContext = context.applicationContext

    // Lazy so constructing the downloader never fails when WorkManager has not
    // been initialised (unit tests, disabled initializer).
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    override fun enqueue(itemId: String, constraints: DownloadConstraints) {
        val request = OneTimeWorkRequestBuilder<ListenDownloadWorker>()
            .setInputData(workDataOf(DownloadScheduler.KEY_ITEM_ID to itemId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (constraints.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, DownloadPolicy.MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(DownloadScheduler.TAG)
            .build()
        // KEEP: requesting an item that is already queued/running must not start a
        // second transfer of the same bytes.
        workManager.enqueueUniqueWork(DownloadScheduler.uniqueName(itemId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(itemId: String) {
        workManager.cancelUniqueWork(DownloadScheduler.uniqueName(itemId))
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(DownloadScheduler.TAG)
    }
}
