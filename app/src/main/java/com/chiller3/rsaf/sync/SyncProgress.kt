/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.sync

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.work.Data
import androidx.work.workDataOf
import com.chiller3.rsaf.R
import com.chiller3.rsaf.rclone.RcloneRpc

/** Progress of a running sync job, as reported by [SyncWorker]. */
data class SyncProgress(
    val waitingForUploads: Boolean,
    val dryRun: Boolean,
    val stats: RcloneRpc.TransferStats,
) {
    companion object {
        private const val KEY_WAITING_FOR_UPLOADS = "waiting_for_uploads"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_BYTES = "bytes"
        private const val KEY_TOTAL_BYTES = "total_bytes"
        private const val KEY_TRANSFERS = "transfers"
        private const val KEY_TOTAL_TRANSFERS = "total_transfers"
        private const val KEY_CHECKS = "checks"
        private const val KEY_TOTAL_CHECKS = "total_checks"
        private const val KEY_DELETES = "deletes"
        private const val KEY_RENAMES = "renames"
        private const val KEY_ERRORS = "errors"
        private const val KEY_SPEED = "speed"
        private const val KEY_ETA = "eta"

        /** Returns null if [data] does not contain progress information yet. */
        fun fromData(data: Data): SyncProgress? {
            if (KEY_WAITING_FOR_UPLOADS !in data.keyValueMap) {
                return null
            }

            return SyncProgress(
                waitingForUploads = data.getBoolean(KEY_WAITING_FOR_UPLOADS, false),
                dryRun = data.getBoolean(KEY_DRY_RUN, false),
                stats = RcloneRpc.TransferStats(
                    bytes = data.getLong(KEY_BYTES, 0),
                    totalBytes = data.getLong(KEY_TOTAL_BYTES, 0),
                    transfers = data.getLong(KEY_TRANSFERS, 0),
                    totalTransfers = data.getLong(KEY_TOTAL_TRANSFERS, 0),
                    checks = data.getLong(KEY_CHECKS, 0),
                    totalChecks = data.getLong(KEY_TOTAL_CHECKS, 0),
                    deletes = data.getLong(KEY_DELETES, 0),
                    renames = data.getLong(KEY_RENAMES, 0),
                    errors = data.getLong(KEY_ERRORS, 0),
                    speed = data.getDouble(KEY_SPEED, 0.0),
                    eta = data.getLong(KEY_ETA, -1).takeIf { it >= 0 },
                    lastError = null,
                ),
            )
        }
    }

    fun toData(): Data = workDataOf(
        KEY_WAITING_FOR_UPLOADS to waitingForUploads,
        KEY_DRY_RUN to dryRun,
        KEY_BYTES to stats.bytes,
        KEY_TOTAL_BYTES to stats.totalBytes,
        KEY_TRANSFERS to stats.transfers,
        KEY_TOTAL_TRANSFERS to stats.totalTransfers,
        KEY_CHECKS to stats.checks,
        KEY_TOTAL_CHECKS to stats.totalChecks,
        KEY_DELETES to stats.deletes,
        KEY_RENAMES to stats.renames,
        KEY_ERRORS to stats.errors,
        KEY_SPEED to stats.speed,
        KEY_ETA to (stats.eta ?: -1L),
    )

    /** Fraction in [0, 1], or null if the total amount of work isn't known yet. */
    val fraction: Float?
        get() = when {
            waitingForUploads -> null
            stats.totalBytes > 0 ->
                (stats.bytes.toDouble() / stats.totalBytes).coerceIn(0.0, 1.0).toFloat()
            stats.totalTransfers > 0 ->
                (stats.transfers.toDouble() / stats.totalTransfers).coerceIn(0.0, 1.0).toFloat()
            else -> null
        }

    /** Human-readable single line description, like "12/30 files · 140 MB / 1.2 GB · 3.2 MB/s". */
    fun describe(context: Context): String {
        if (waitingForUploads) {
            return context.getString(R.string.sync_progress_waiting_for_uploads)
        }

        val pieces = mutableListOf<String>()

        if (stats.totalTransfers > 0) {
            pieces.add(context.resources.getQuantityString(
                R.plurals.sync_progress_files,
                stats.totalTransfers.toInt(),
                stats.transfers,
                stats.totalTransfers,
            ))
        } else if (stats.totalChecks > 0) {
            pieces.add(context.getString(
                R.string.sync_progress_checks,
                stats.checks,
                stats.totalChecks,
            ))
        } else {
            pieces.add(context.getString(R.string.sync_progress_comparing))
        }

        if (stats.totalBytes > 0) {
            pieces.add(context.getString(
                R.string.sync_progress_bytes,
                Formatter.formatShortFileSize(context, stats.bytes),
                Formatter.formatShortFileSize(context, stats.totalBytes),
            ))
        }

        if (stats.speed > 0) {
            pieces.add(context.getString(
                R.string.sync_progress_speed,
                Formatter.formatShortFileSize(context, stats.speed.toLong()),
            ))
        }

        stats.eta?.takeIf { it > 0 }?.let {
            pieces.add(context.getString(
                R.string.sync_progress_eta,
                DateUtils.formatElapsedTime(it),
            ))
        }

        if (stats.errors > 0) {
            pieces.add(context.getString(R.string.sync_progress_errors, stats.errors))
        }

        return pieces.joinToString(context.getString(R.string.sync_separator))
    }
}
