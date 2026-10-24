/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.rsaf.R
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.sync.SyncJob
import com.chiller3.rsaf.sync.SyncJobResult
import com.chiller3.rsaf.sync.SyncProgress
import com.chiller3.rsaf.sync.describe
import com.chiller3.rsaf.ui.AppScreen
import com.chiller3.rsaf.ui.Preference
import com.chiller3.rsaf.ui.PreferenceColumn
import com.chiller3.rsaf.ui.PreferenceDefaults
import com.chiller3.rsaf.ui.betterSegmentedShapes
import com.chiller3.rsaf.ui.copy
import com.chiller3.rsaf.ui.theme.AppTheme
import com.chiller3.rsaf.ui.theme.Icons

/** Extra space at the bottom of the list so the last item isn't covered by the FAB. */
private val FabClearance = 88.dp

@Composable
fun SyncJobsScreen(
    onBack: () -> Unit,
    viewModel: SyncJobsViewModel = viewModel(),
) {
    val resources = LocalResources.current

    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val remotes by viewModel.remotes.collectAsStateWithLifecycle()

    var showErrorDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showEditDialog by rememberSaveable { mutableStateOf<SyncJobEditTarget?>(null) }
    var showActionsDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteDialog by rememberSaveable { mutableStateOf<String?>(null) }

    AppScreen(
        title = { Text(text = stringResource(R.string.sync_jobs_title)) },
        onBack = onBack,
        floatingActionButton = {
            if (remotes != null && jobs != null) {
                FloatingActionButton(onClick = { showEditDialog = SyncJobEditTarget.Add }) {
                    Icon(
                        imageVector = Icons.Add,
                        contentDescription = stringResource(R.string.sync_jobs_add),
                    )
                }
            }
        },
    ) { params ->
        LaunchedEffect(Unit) {
            viewModel.alerts.collect { alerts ->
                val alert = alerts.firstOrNull() ?: return@collect
                val msg = when (alert) {
                    is SyncJobsAlert.ListRemotesFailed ->
                        resources.getString(R.string.alert_list_remotes_failure)
                    is SyncJobsAlert.LoadFailed ->
                        resources.getString(R.string.alert_load_sync_jobs_failure)
                    is SyncJobsAlert.SaveFailed ->
                        resources.getString(R.string.alert_save_sync_job_failure)
                    is SyncJobsAlert.DeleteFailed ->
                        resources.getString(R.string.alert_delete_sync_job_failure)
                }
                val details = when (alert) {
                    is SyncJobsAlert.ListRemotesFailed -> alert.error
                    is SyncJobsAlert.LoadFailed -> alert.error
                    is SyncJobsAlert.SaveFailed -> alert.error
                    is SyncJobsAlert.DeleteFailed -> alert.error
                }

                val result = params.snackbarHostState.showSnackbar(
                    message = msg,
                    resources.getString(R.string.action_details),
                    withDismissAction = true,
                )
                viewModel.acknowledgeFirstAlert()

                when (result) {
                    SnackbarResult.Dismissed -> {}
                    SnackbarResult.ActionPerformed -> { showErrorDialog = details }
                }
            }
        }

        showErrorDialog?.let { message ->
            ErrorDetailsDialog(
                message = message,
                onDismiss = { showErrorDialog = null },
            )
        }

        SyncJobsContent(
            jobs = jobs,
            onJobClick = { showActionsDialog = it },
            onRun = { viewModel.runJob(it, false) },
            onStop = { viewModel.stopJob(it) },
            contentPadding = params.contentPadding.copy(
                // There are no category headers on this screen to provide a gap below the app bar.
                top = 8.dp,
                bottom = params.contentPadding.calculateBottomPadding() + FabClearance,
            ),
        )
    }

    fun findJob(id: String) = jobs?.find { it.job.id == id }

    // The job may have been deleted in the meantime.
    showActionsDialog?.let(::findJob)?.let { state ->
        val id = state.job.id

        SyncJobActionsDialog(
            state = state,
            onRun = {
                viewModel.runJob(id, false)
                showActionsDialog = null
            },
            onDryRun = {
                viewModel.runJob(id, true)
                showActionsDialog = null
            },
            onStop = {
                viewModel.stopJob(id)
                showActionsDialog = null
            },
            onEdit = {
                showEditDialog = SyncJobEditTarget.Edit(id)
                showActionsDialog = null
            },
            onDelete = {
                showDeleteDialog = id
                showActionsDialog = null
            },
            onDismiss = { showActionsDialog = null },
        )
    }

    showEditDialog?.let { target ->
        val initial = when (target) {
            SyncJobEditTarget.Add -> null
            is SyncJobEditTarget.Edit -> findJob(target.jobId)?.job ?: return@let
        }

        SyncJobEditDialog(
            initial = initial,
            remotes = remotes ?: emptyList(),
            onSave = { job ->
                viewModel.saveJob(job, isNew = initial == null)
                showEditDialog = null
            },
            onDismiss = { showEditDialog = null },
        )
    }

    showDeleteDialog?.let(::findJob)?.let { state ->
        val id = state.job.id

        DeleteSyncJobDialog(
            job = state.job,
            onConfirm = {
                viewModel.deleteJob(id)
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null },
        )
    }
}

@Composable
private fun SyncJobsContent(
    jobs: List<SyncJobState>?,
    onJobClick: (String) -> Unit,
    onRun: (String) -> Unit,
    onStop: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    PreferenceColumn(contentPadding = contentPadding) {
        if (jobs == null) {
            return@PreferenceColumn
        }

        if (jobs.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.sync_jobs_empty),
                    modifier = Modifier
                        .widthIn(max = PreferenceDefaults.MaxWidth)
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                        .animateItem(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        itemsIndexed(jobs, key = { _, state -> state.job.id }) { index, state ->
            Preference(
                onClick = { onJobClick(state.job.id) },
                shapes = betterSegmentedShapes(index, jobs.size),
                title = { Text(text = state.job.name) },
                summary = { SyncJobSummary(state) },
                trailingContent = {
                    if (state.isActive) {
                        IconButton(onClick = { onStop(state.job.id) }) {
                            Icon(
                                imageVector = Icons.Stop,
                                contentDescription = stringResource(R.string.sync_jobs_action_stop),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { onRun(state.job.id) },
                            enabled = state.canRun,
                        ) {
                            Icon(
                                imageVector = Icons.PlayArrow,
                                contentDescription = stringResource(R.string.sync_jobs_action_run),
                            )
                        }
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun SyncJobSummary(state: SyncJobState) {
    val context = LocalContext.current
    val job = state.job

    Column {
        Text(text = stringResource(
            R.string.sync_job_route,
            job.source,
            job.destination,
            stringResource(job.mode.labelResId),
        ))

        val lastResult = job.lastResult

        when {
            state.isActive -> {
                val progress = state.progress

                Text(text = progress?.describe(context)
                    ?: stringResource(R.string.sync_job_status_queued))

                val fraction = progress?.fraction
                val modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = modifier)
                } else {
                    LinearProgressIndicator(modifier = modifier)
                }
            }
            state.missingRemotes.isNotEmpty() -> {
                Text(
                    text = stringResource(
                        R.string.sync_job_status_missing_remotes,
                        state.missingRemotes.joinToString { "$it:" },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            lastResult != null -> {
                val now = System.currentTimeMillis()
                val time = if (now - lastResult.endTime < DateUtils.MINUTE_IN_MILLIS) {
                    stringResource(R.string.sync_job_time_just_now)
                } else {
                    DateUtils.getRelativeTimeSpanString(
                        lastResult.endTime,
                        now,
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                }

                when {
                    lastResult.cancelled -> Text(
                        text = stringResource(R.string.sync_job_status_cancelled, time),
                    )
                    !lastResult.success -> Text(
                        text = stringResource(
                            R.string.sync_job_status_failed,
                            time,
                            lastResult.error ?: lastResult.describe(context),
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(
                        text = stringResource(
                            if (lastResult.dryRun) {
                                R.string.sync_job_status_dry_run_succeeded
                            } else {
                                R.string.sync_job_status_succeeded
                            },
                            time,
                            lastResult.describe(context),
                        ),
                    )
                }
            }
            else -> Text(text = stringResource(R.string.sync_job_status_never_run))
        }
    }
}

@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewSyncJobsScreen() {
    val now = System.currentTimeMillis()

    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.sync_jobs_title)) },
            onBack = {},
        ) { params ->
            SyncJobsContent(
                jobs = listOf(
                    SyncJobState(
                        job = SyncJob("1", "Photos backup", "phone", "b2",
                            RcloneRpc.TransferMode.SYNC),
                        missingRemotes = emptyList(),
                        isActive = true,
                        progress = SyncProgress(
                            waitingForUploads = false,
                            dryRun = false,
                            stats = RcloneRpc.TransferStats.EMPTY.copy(
                                bytes = 140_000_000,
                                totalBytes = 1_200_000_000,
                                transfers = 12,
                                totalTransfers = 30,
                                speed = 3_200_000.0,
                                eta = 300,
                            ),
                        ),
                    ),
                    SyncJobState(
                        job = SyncJob("2", "Documents", "gdrive", "nas",
                            RcloneRpc.TransferMode.COPY,
                            lastResult = SyncJobResult(now - 7_200_000, true, false, false, null,
                                1_200_000, 3, 0, 0)),
                        missingRemotes = emptyList(),
                        isActive = false,
                        progress = null,
                    ),
                    SyncJobState(
                        job = SyncJob("3", "Old job", "dropbox", "nas",
                            RcloneRpc.TransferMode.MOVE),
                        missingRemotes = listOf("dropbox"),
                        isActive = false,
                        progress = null,
                    ),
                ),
                onJobClick = {},
                onRun = {},
                onStop = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}
