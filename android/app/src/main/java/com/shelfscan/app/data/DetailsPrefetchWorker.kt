package com.shelfscan.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shelfscan.app.ShelfScanApp
import java.util.concurrent.TimeUnit

/**
 * Gets book pages ready in the background: synopsis and large cover for every book that doesn't have
 * them yet, and similar books for recently added ones. Runs after books are added (and once at launch),
 * one book at a time so it never floods the model server, and retries later if the server is down.
 */
class DetailsPrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as ShelfScanApp).repository
        return if (repo.prefetchPendingDetails()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "prefetch-details"

        /**
         * After adding books, append a pass (a run already in progress may have missed them); at launch,
         * keep whatever is already scheduled.
         */
        fun schedule(context: Context, afterAdding: Boolean) {
            val request = OneTimeWorkRequestBuilder<DetailsPrefetchWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            val policy = if (afterAdding) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}
