/*
 * SPDX-FileCopyrightText: 2022-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.chiller3.rsaf.rclone.KeepAliveService
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.sync.SyncJob

class Notifications(private val context: Context) {
    companion object {
        private const val CHANNEL_ID_KEEP_ALIVE = "keep_alive"
        private const val CHANNEL_ID_FAILURE = "failure"
        private const val CHANNEL_ID_SYNC_JOBS = "sync_jobs"

        private val LEGACY_CHANNEL_IDS = arrayOf(
            "open_files",
            "background_uploads",
        )

        const val ID_KEEP_ALIVE = -1
        const val ID_AUTHORIZE = -2
    }

    private val prefs = Preferences(context)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /** Create a low priority notification channel for the keep alive notification. */
    private fun createKeepAliveChannel() = NotificationChannel(
        CHANNEL_ID_KEEP_ALIVE,
        context.getString(R.string.notification_channel_keep_alive_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.notification_channel_keep_alive_desc)
    }

    /** Create a high priority notification channel for failure alerts. */
    private fun createFailureAlertsChannel() = NotificationChannel(
        CHANNEL_ID_FAILURE,
        context.getString(R.string.notification_channel_failure_name),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.notification_channel_failure_desc)
    }

    /** Create a low priority notification channel for sync job progress. */
    private fun createSyncJobsChannel() = NotificationChannel(
        CHANNEL_ID_SYNC_JOBS,
        context.getString(R.string.notification_channel_sync_jobs_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.notification_channel_sync_jobs_desc)
    }

    /**
     * Ensure notification channels are up-to-date.
     *
     * Legacy notification channels are deleted without migrating settings.
     */
    fun updateChannels() {
        notificationManager.createNotificationChannels(listOf(
            createKeepAliveChannel(),
            createFailureAlertsChannel(),
            createSyncJobsChannel(),
        ))
        LEGACY_CHANNEL_IDS.forEach { notificationManager.deleteNotificationChannel(it) }
    }

    fun createKeepAliveNotification(state: KeepAliveService.State): Notification {
        val message = if (state.total == 0) {
            context.getString(R.string.notification_keep_alive_cleanup_wait_desc)
        } else {
            buildString {
                for ((resId, count) in arrayOf(
                    R.plurals.notification_keep_alive_files_open_desc to state.open,
                    R.plurals.notification_keep_alive_files_uploading_desc to
                            state.syncUploading + state.asyncUploading,
                    R.plurals.notification_keep_alive_files_pending_desc to state.asyncPending,
                )) {
                    if (count == 0) {
                        continue
                    } else if (isNotEmpty()) {
                        append('\n')
                    }

                    append(context.resources.getQuantityString(resId, count, count))
                }
            }
        }

        return Notification.Builder(context, CHANNEL_ID_KEEP_ALIVE).run {
            setContentTitle(context.getString(R.string.notification_keep_alive_title))
            setSmallIcon(R.drawable.ic_notifications)
            setOngoing(true)
            setOnlyAlertOnce(true)

            setContentText(message)
            style = Notification.BigTextStyle()

            // Inhibit 10-second delay when showing persistent notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            build()
        }
    }

    fun createAuthorizeNotification() =
        Notification.Builder(context, CHANNEL_ID_KEEP_ALIVE).run {
            setContentTitle(context.getString(R.string.dialog_authorize_title))
            setSmallIcon(R.drawable.ic_notifications)
            setOngoing(true)
            setOnlyAlertOnce(true)

            // Inhibit 10-second delay when showing persistent notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            build()
        }

    fun createSyncJobNotification(
        job: SyncJob,
        stats: RcloneRpc.TransferStats?,
        cancelIntent: PendingIntent,
    ): Notification = Notification.Builder(context, CHANNEL_ID_SYNC_JOBS).run {
        setContentTitle(context.getString(R.string.notification_sync_job_title))
        setContentText(context.getString(R.string.sync_job_name, job.source, job.dest))
        setSmallIcon(R.drawable.ic_notifications)
        setOngoing(true)
        setOnlyAlertOnce(true)

        if (stats == null || stats.totalBytes == 0L) {
            setProgress(0, 0, true)
        } else {
            setProgress(100, (stats.bytes * 100 / stats.totalBytes).toInt(), false)
        }

        addAction(Notification.Action.Builder(
            null,
            context.getString(android.R.string.cancel),
            cancelIntent,
        ).build())

        build()
    }

    fun notifySyncJobFailed(job: SyncJob, errorMsg: String) {
        val notification = Notification.Builder(context, CHANNEL_ID_FAILURE).run {
            val text = buildString {
                append(context.getString(R.string.sync_job_name, job.source, job.dest))
                append("\n\n")
                append(errorMsg.trim())
            }

            setContentTitle(context.getString(R.string.notification_sync_job_failed_title))
            setContentText(text)
            style = Notification.BigTextStyle().bigText(text)
            setSmallIcon(R.drawable.ic_notifications)

            build()
        }

        notificationManager.notify(prefs.nextNotificationId, notification)
    }

    fun notifyBackgroundUploadFailed(documentId: String, errorMsg: String) {
        val notificationId = prefs.nextNotificationId

        val notification = Notification.Builder(context, CHANNEL_ID_FAILURE).run {
            val text = buildString {
                val errorMsgTrimmed = errorMsg.trim()
                if (errorMsgTrimmed.isNotBlank()) {
                    append(errorMsgTrimmed)
                }
                append("\n\n")
                append(documentId)
            }

            setContentTitle(context.getString(R.string.notification_background_upload_failed_title))
            if (text.isNotBlank()) {
                setContentText(text)
                style = Notification.BigTextStyle().bigText(text)
            }
            setSmallIcon(R.drawable.ic_notifications)

            build()
        }

        notificationManager.notify(notificationId, notification)
    }
}
