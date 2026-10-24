/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.sync

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chiller3.rsaf.BuildConfig
import com.chiller3.rsaf.Notifications
import com.chiller3.rsaf.Preferences
import com.chiller3.rsaf.extension.toSingleLineString
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.rclone.RcloneRpcException
import com.chiller3.rsaf.rclone.VfsCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class MissingRemoteException(remote: String) :
    Exception("Remote does not exist: $remote:")

/**
 * Runs a single sync job via rclone's `sync/sync`, `sync/copy`, or `sync/move` RPC calls.
 *
 * rclone runs as a library inside the app process, so the worker promotes itself to a foreground
 * service to keep the process alive for the duration of the transfer.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private val TAG = SyncWorker::class.java.simpleName

        /** Tag applied to all sync job work requests. */
        const val TAG_SYNC = "sync"
        private const val TAG_JOB_PREFIX = "sync-job:"

        private const val KEY_JOB_ID = "job_id"
        private const val KEY_DRY_RUN = "dry_run"

        private const val POLL_INTERVAL_MS = 1000L
        private const val STOP_RETRY_INTERVAL_MS = 500L

        private fun uniqueWorkName(jobId: String) = "$TAG_JOB_PREFIX$jobId"

        /** Start a run of the specified job. This does nothing if the job is already running. */
        fun enqueue(context: Context, jobId: String, dryRun: Boolean) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId, KEY_DRY_RUN to dryRun))
                .addTag(TAG_SYNC)
                .addTag("$TAG_JOB_PREFIX$jobId")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueWorkName(jobId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, jobId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(jobId))
        }

        /** Get the sync job ID associated with a work request. */
        fun getJobId(workInfo: WorkInfo): String? =
            workInfo.tags.find { it.startsWith(TAG_JOB_PREFIX) }?.substring(TAG_JOB_PREFIX.length)
    }

    private val prefs = Preferences(applicationContext)
    private val notifications = Notifications(applicationContext)
    private val notificationManager =
        applicationContext.getSystemService(NotificationManager::class.java)
    private val notificationId = prefs.nextNotificationId

    private var isForeground = false

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?: return@withContext Result.failure()
        val dryRun = inputData.getBoolean(KEY_DRY_RUN, false)
        val store = SyncJobStore.getInstance(applicationContext)
        val job = store.get(jobId) ?: run {
            Log.w(TAG, "Sync job $jobId no longer exists")
            return@withContext Result.failure()
        }

        Log.i(TAG, "Starting sync job $job (dryRun=$dryRun)")

        val group = "rsaf-sync/$id"
        var stats = RcloneRpc.TransferStats.EMPTY
        var error: String? = null
        var cancelled = false

        try {
            val remotes = RcloneRpc.remoteNames
            for (remote in arrayOf(job.source, job.destination)) {
                if (remote !in remotes) {
                    throw MissingRemoteException(remote)
                }
            }

            // The sync reads directly from the backend, so files that are still pending upload in
            // the VFS cache would be missed.
            while (VfsCache.hasOngoingUploads(job.source)) {
                report(job, SyncProgress(true, dryRun, stats))
                delay(POLL_INTERVAL_MS)
            }

            report(job, SyncProgress(false, dryRun, stats))

            runTransfer(job, dryRun, group) { newStats ->
                stats = newStats
                report(job, SyncProgress(false, dryRun, newStats))
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Sync job $jobId was cancelled")
            cancelled = true
        } catch (e: Exception) {
            Log.w(TAG, "Sync job $jobId failed", e)
            error = when (e) {
                is RcloneRpcException -> e.error ?: e.message
                is MissingRemoteException -> e.message
                else -> e.toSingleLineString()
            }
        }

        return@withContext withContext(NonCancellable) {
            try {
                stats = RcloneRpc.coreStats(group)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get final stats for $group", e)
            }
            try {
                RcloneRpc.deleteStatsGroup(group)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete stats group $group", e)
            }

            if (!dryRun) {
                refreshRemote(job.destination)
                if (job.mode == RcloneRpc.TransferMode.MOVE) {
                    refreshRemote(job.source)
                }
            }

            val result = SyncJobResult(
                endTime = System.currentTimeMillis(),
                success = error == null && !cancelled,
                dryRun = dryRun,
                cancelled = cancelled,
                error = error,
                bytes = stats.bytes,
                transfers = stats.transfers,
                deletes = stats.deletes,
                errors = stats.errors,
            )

            Log.i(TAG, "Finished sync job $jobId: $result")

            store.setResult(jobId, result)

            // WorkManager removes the notification when the foreground service stops, but not if the
            // worker failed to become a foreground service.
            notificationManager.cancel(notificationId)
            if (!cancelled) {
                notifications.notifySyncFinished(job, result)
            }

            if (result.success) {
                Result.success()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * Run the blocking rclone RPC call on a separate thread, polling for stats until it completes.
     * If the coroutine is cancelled, the rclone job is stopped and this waits for it to exit.
     */
    private suspend fun runTransfer(
        job: SyncJob,
        dryRun: Boolean,
        group: String,
        onStats: suspend (RcloneRpc.TransferStats) -> Unit,
    ) = coroutineScope {
        val op = async(Dispatchers.IO) {
            runCatching {
                RcloneRpc.runTransfer(
                    mode = job.mode,
                    srcFs = "${job.source}:",
                    dstFs = "${job.destination}:",
                    dryRun = dryRun,
                    group = group,
                )
            }
        }

        val poller = launch {
            while (isActive) {
                try {
                    onStats(RcloneRpc.coreStats(group))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query stats for $group", e)
                }

                delay(POLL_INTERVAL_MS)
            }
        }

        try {
            op.await().getOrThrow()
        } finally {
            poller.cancel()

            if (!op.isCompleted) {
                withContext(NonCancellable) {
                    // The RPC call is blocking and can't be interrupted by coroutine cancellation.
                    // Keep retrying in case the job wasn't registered in the group yet.
                    while (!op.isCompleted) {
                        Log.d(TAG, "Stopping rclone jobs in $group")
                        try {
                            RcloneRpc.jobStopGroup(group)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to stop jobs in $group", e)
                        }

                        delay(STOP_RETRY_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private suspend fun report(job: SyncJob, progress: SyncProgress) {
        setProgress(progress.toData())

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = notifications.createSyncProgressNotification(job, progress, cancelIntent)

        if (!isForeground) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }

            try {
                setForeground(ForegroundInfo(notificationId, notification, type))
                isForeground = true
                return
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }

                // This should only happen if the app is somehow in the background. The job will
                // still run, but the process may be killed.
                Log.w(TAG, "Failed to promote sync worker to foreground", e)
            }
        }

        // Updating the notification of a running foreground service only requires reposting it.
        notificationManager.notify(notificationId, notification)
    }

    /** Make the changes visible to the VFS and SAF clients. */
    private fun refreshRemote(remote: String) {
        VfsCache.forgetDirCache(remote)

        val uri = DocumentsContract.buildChildDocumentsUri(
            BuildConfig.DOCUMENTS_AUTHORITY,
            "$remote:",
        )
        applicationContext.contentResolver.notifyChange(uri, null)
    }
}
