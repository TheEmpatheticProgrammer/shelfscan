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
 * Sends scans saved while the server was unreachable. Runs when the phone has a network; if the
 * server still can't be reached, WorkManager tries again later, waiting longer each time (up to a
 * few hours). Survives the app closing and the phone restarting.
 */
class ScanQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as ShelfScanApp).repository
        return if (repo.processPendingScans()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "scan-queue"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScanQueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Append: a scan queued while the worker is mid-run still gets its own pass afterwards.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
