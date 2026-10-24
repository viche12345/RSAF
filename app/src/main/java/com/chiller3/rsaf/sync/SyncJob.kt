/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.sync

import android.content.Context
import android.text.format.Formatter
import com.chiller3.rsaf.R
import com.chiller3.rsaf.rclone.RcloneRpc

data class SyncJobResult(
    /** Unix timestamp in milliseconds. */
    val endTime: Long,
    val success: Boolean,
    val dryRun: Boolean,
    val cancelled: Boolean,
    val error: String?,
    val bytes: Long,
    val transfers: Long,
    val deletes: Long,
    val errors: Long,
)

data class SyncJob(
    /** Random UUID. */
    val id: String,
    val name: String,
    /** Source remote name, without the trailing colon. */
    val source: String,
    /** Destination remote name, without the trailing colon. */
    val destination: String,
    val mode: RcloneRpc.TransferMode,
    /**
     * Delete extra files on the destination before transferring instead of after. This frees up
     * space first, but if the run fails, the destination may be missing files until the next run.
     * Only applies to [RcloneRpc.TransferMode.SYNC].
     */
    val deleteBefore: Boolean = false,
    /** Number of files to transfer in parallel. */
    val transfers: Int = DEFAULT_TRANSFERS,
    val lastResult: SyncJobResult? = null,
) {
    companion object {
        const val DEFAULT_TRANSFERS = 1
        const val MIN_TRANSFERS = 1
        const val MAX_TRANSFERS = 8
    }
}

/** Human-readable summary of a finished run, like "Transferred: 3 (1.2 MB) · Deleted: 1". */
fun SyncJobResult.describe(context: Context): String {
    val pieces = mutableListOf<String>()
    val bytesStr = Formatter.formatShortFileSize(context, bytes)

    pieces.add(if (dryRun) {
        context.getString(R.string.sync_result_would_transfer, transfers, bytesStr)
    } else {
        context.getString(R.string.sync_result_transferred, transfers, bytesStr)
    })
    pieces.add(if (dryRun) {
        context.getString(R.string.sync_result_would_delete, deletes)
    } else {
        context.getString(R.string.sync_result_deleted, deletes)
    })
    if (errors > 0) {
        pieces.add(context.getString(R.string.sync_progress_errors, errors))
    }

    return pieces.joinToString(context.getString(R.string.sync_separator))
}
