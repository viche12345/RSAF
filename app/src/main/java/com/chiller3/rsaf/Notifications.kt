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
import com.chiller3.rsaf.settings.SyncJobsActivity
import com.chiller3.rsaf.sync.SyncJob
import com.chiller3.rsaf.sync.SyncJobResult
import com.chiller3.rsaf.sync.SyncProgress
import com.chiller3.rsaf.sync.describe

class Notifications(private val context: Context) {
    companion object {
        private const val CHANNEL_ID_KEEP_ALIVE = "keep_alive"
        private const val CHANNEL_ID_FAILURE = "failure"
        private const val CHANNEL_ID_SYNC_PROGRESS = "sync_progress"
        private const val CHANNEL_ID_SYNC_RESULT = "sync_result"

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
    private fun createSyncProgressChannel() = NotificationChannel(
        CHANNEL_ID_SYNC_PROGRESS,
        context.getString(R.string.notification_channel_sync_progress_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.notification_channel_sync_progress_desc)
    }

    /** Create a default priority notification channel for successful sync job results. */
    private fun createSyncResultChannel() = NotificationChannel(
        CHANNEL_ID_SYNC_RESULT,
        context.getString(R.string.notification_channel_sync_result_name),
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = context.getString(R.string.notification_channel_sync_result_desc)
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
            createSyncProgressChannel(),
            createSyncResultChannel(),
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

    private fun createSyncJobsPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        SyncJobsActivity.createIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun createSyncProgressNotification(
        job: SyncJob,
        progress: SyncProgress,
        cancelIntent: PendingIntent,
    ): Notification = Notification.Builder(context, CHANNEL_ID_SYNC_PROGRESS).run {
        val titleResId = if (progress.dryRun) {
            R.string.notification_sync_dry_run_title
        } else {
            R.string.notification_sync_running_title
        }
        val text = progress.describe(context)

        setContentTitle(context.getString(titleResId, job.name))
        setContentText(text)
        style = Notification.BigTextStyle().bigText(text)
        setSmallIcon(R.drawable.ic_notifications)
        setOngoing(true)
        setOnlyAlertOnce(true)
        setContentIntent(createSyncJobsPendingIntent())

        val fraction = progress.fraction
        if (fraction != null) {
            setProgress(1000, (fraction * 1000).toInt(), false)
        } else {
            setProgress(0, 0, true)
        }

        addAction(Notification.Action.Builder(
            null,
            context.getString(android.R.string.cancel),
            cancelIntent,
        ).build())

        // Inhibit 10-second delay when showing persistent notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        build()
    }

    fun notifySyncFinished(job: SyncJob, result: SyncJobResult) {
        val notificationId = prefs.nextNotificationId
        val channel = if (result.success) CHANNEL_ID_SYNC_RESULT else CHANNEL_ID_FAILURE

        val notification = Notification.Builder(context, channel).run {
            val titleResId = when {
                !result.success -> R.string.notification_sync_failed_title
                result.dryRun -> R.string.notification_sync_dry_run_succeeded_title
                else -> R.string.notification_sync_succeeded_title
            }
            val text = buildString {
                result.error?.trim()?.takeIf { it.isNotBlank() }?.let {
                    append(it)
                    append("\n\n")
                }
                append(result.describe(context))
            }

            setContentTitle(context.getString(titleResId, job.name))
            setContentText(text)
            style = Notification.BigTextStyle().bigText(text)
            setSmallIcon(R.drawable.ic_notifications)
            setContentIntent(createSyncJobsPendingIntent())
            setAutoCancel(true)

            build()
        }

        notificationManager.notify(notificationId, notification)
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
