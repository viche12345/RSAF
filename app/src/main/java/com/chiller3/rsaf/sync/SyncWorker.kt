/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chiller3.rsaf.Notifications
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.extension.toSingleLineString
import com.chiller3.rsaf.rclone.RcloneRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Runs a [SyncJob] via rclone's async `sync/sync` or `sync/copy` RPC, reporting progress until it
 * finishes.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private val TAG = SyncWorker::class.java.simpleName

        /** Tag applied to all sync work. */
        const val TAG_SYNC = "sync"

        private const val KEY_JOB_ID = "job_id"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR = "error"

        private const val POLL_INTERVAL_MS = 1000L

        /** Unique work name and tag for a specific job. */
        fun workName(jobId: String) = "sync:$jobId"

        fun enqueue(context: Context, jobId: String) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId))
                .addTag(TAG_SYNC)
                .addTag(workName(jobId))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(jobId))
        }
    }

    private val notifications = Notifications(applicationContext)

    override suspend fun doWork(): Result {
        val job = inputData.getString(KEY_JOB_ID)?.let { SyncJobStore(applicationContext)[it] }
            ?: return Result.failure()

        val notificationId = Preferences(applicationContext).nextNotificationId
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        suspend fun updateForeground(stats: RcloneRpc.TransferStats?) = setForeground(ForegroundInfo(
            notificationId,
            notifications.createSyncJobNotification(job, stats, cancelIntent),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        ))

        updateForeground(null)

        val error = try {
            run(job, ::updateForeground)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync job ${job.id} failed", e)
            e.toSingleLineString()
        }

        return if (error == null) {
            Result.success()
        } else {
            notifications.notifySyncJobFailed(job, error)
            Result.failure(workDataOf(KEY_ERROR to error))
        }
    }

    /** Run the rclone job to completion. Returns an error message on failure. */
    private suspend fun run(
        job: SyncJob,
        onProgress: suspend (RcloneRpc.TransferStats) -> Unit,
    ): String? {
        val group = workName(job.id)
        val jobId = RcloneRpc.startSyncJob(job.source, job.dest, job.copy, group)

        try {
            while (true) {
                val status = RcloneRpc.jobStatus(jobId)
                if (status.finished) {
                    return if (status.success) null else status.error
                }

                val stats = RcloneRpc.groupStats(group)
                setProgress(workDataOf(
                    KEY_BYTES to stats.bytes,
                    KEY_TOTAL_BYTES to stats.totalBytes,
                ))
                onProgress(stats)

                delay(POLL_INTERVAL_MS)
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Stopping sync job ${job.id}")
            // The job may have finished in the meantime.
            runCatching { RcloneRpc.stopJob(jobId) }
            throw e
        } finally {
            RcloneRpc.deleteGroupStats(group)
        }
    }
}
