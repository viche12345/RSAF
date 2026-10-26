/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.rsaf.R
import com.chiller3.rsaf.ui.AppScreen
import com.chiller3.rsaf.ui.Preference
import com.chiller3.rsaf.ui.PreferenceColumn
import com.chiller3.rsaf.ui.betterSegmentedShapes
import com.chiller3.rsaf.ui.copy

@Composable
fun SyncJobsScreen(
    onBack: () -> Unit,
    viewModel: SyncJobsViewModel = viewModel(),
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val remotes by viewModel.remotes.collectAsStateWithLifecycle()

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var selectedJobId by rememberSaveable { mutableStateOf<String?>(null) }

    AppScreen(
        title = { Text(text = stringResource(R.string.sync_jobs_title)) },
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAddDialog = true }) {
                Text(text = stringResource(R.string.sync_jobs_add))
            }
        },
    ) { params ->
        PreferenceColumn(
            // Leave room so the FAB doesn't cover the last job.
            contentPadding = params.contentPadding.copy(
                bottom = params.contentPadding.calculateBottomPadding() + 88.dp,
            ),
        ) {
            if (jobs.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.sync_jobs_empty),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            itemsIndexed(jobs, key = { _, state -> state.job.id }) { index, state ->
                Preference(
                    onClick = { selectedJobId = state.job.id },
                    shapes = betterSegmentedShapes(index, jobs.size),
                    title = { Text(text = jobName(state)) },
                    summary = { Text(text = jobSummary(state)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (showAddDialog) {
        AddSyncJobDialog(
            remotes = remotes,
            onAdd = { source, dest, copy ->
                viewModel.add(source, dest, copy)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    selectedJobId?.let { id ->
        val state = jobs.find { it.job.id == id } ?: return@let
        val onDismiss = { selectedJobId = null }

        AlertDialog(
            title = { Text(text = jobName(state)) },
            text = { Text(text = jobSummary(state)) },
            onDismissRequest = onDismiss,
            confirmButton = {
                if (state.status.isActive) {
                    TextButton(onClick = { viewModel.stop(id) }) {
                        Text(text = stringResource(R.string.sync_job_action_stop))
                    }
                } else {
                    TextButton(onClick = { viewModel.run(id) }) {
                        Text(text = stringResource(R.string.sync_job_action_run))
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.delete(id)
                        onDismiss()
                    }) {
                        Text(text = stringResource(R.string.sync_job_action_delete))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.sync_job_action_close))
                    }
                }
            },
        )
    }
}

@Composable
private fun jobName(state: SyncJobState): String =
    stringResource(R.string.sync_job_name, state.job.source, state.job.dest)

@Composable
private fun jobSummary(state: SyncJobState): String {
    val context = LocalContext.current

    val mode = stringResource(
        if (state.job.copy) R.string.sync_job_mode_copy else R.string.sync_job_mode_sync,
    )
    val status = when (val s = state.status) {
        SyncJobStatus.Idle -> stringResource(R.string.sync_job_status_idle)
        SyncJobStatus.Queued -> stringResource(R.string.sync_job_status_queued)
        is SyncJobStatus.Running -> if (s.totalBytes == 0L) {
            stringResource(R.string.sync_job_status_running)
        } else {
            stringResource(
                R.string.sync_job_status_running_progress,
                Formatter.formatShortFileSize(context, s.bytes),
                Formatter.formatShortFileSize(context, s.totalBytes),
            )
        }
        SyncJobStatus.Succeeded -> stringResource(R.string.sync_job_status_succeeded)
        is SyncJobStatus.Failed -> stringResource(R.string.sync_job_status_failed, s.error)
        SyncJobStatus.Cancelled -> stringResource(R.string.sync_job_status_cancelled)
    }

    return stringResource(R.string.sync_job_summary, mode, status)
}
