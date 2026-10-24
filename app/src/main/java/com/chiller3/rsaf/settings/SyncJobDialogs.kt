/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.os.Parcelable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.chiller3.rsaf.R
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.sync.SyncJob
import kotlinx.parcelize.Parcelize

@Parcelize
sealed interface SyncJobEditTarget : Parcelable {
    @Parcelize
    data object Add : SyncJobEditTarget

    @Parcelize
    data class Edit(val jobId: String) : SyncJobEditTarget
}

val RcloneRpc.TransferMode.labelResId: Int
    get() = when (this) {
        RcloneRpc.TransferMode.SYNC -> R.string.sync_mode_sync
        RcloneRpc.TransferMode.COPY -> R.string.sync_mode_copy
        RcloneRpc.TransferMode.MOVE -> R.string.sync_mode_move
    }

private val RcloneRpc.TransferMode.descResId: Int
    get() = when (this) {
        RcloneRpc.TransferMode.SYNC -> R.string.sync_mode_sync_desc
        RcloneRpc.TransferMode.COPY -> R.string.sync_mode_copy_desc
        RcloneRpc.TransferMode.MOVE -> R.string.sync_mode_move_desc
    }

/**
 * Dialog for adding a new job (if [initial] is null) or editing an existing job.
 *
 * @param remotes Names of all remotes, without the trailing colon.
 */
@Composable
fun SyncJobEditDialog(
    initial: SyncJob?,
    remotes: List<String>,
    onSave: (
        name: String,
        source: String,
        destination: String,
        mode: RcloneRpc.TransferMode,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val nameInput = rememberTextFieldState(initialText = initial?.name ?: "")
    var source by rememberSaveable { mutableStateOf(initial?.source) }
    var destination by rememberSaveable { mutableStateOf(initial?.destination) }
    var mode by rememberSaveable { mutableStateOf(initial?.mode ?: RcloneRpc.TransferMode.SYNC) }

    val sameRemote = source != null && source == destination
    val isValid = source in remotes && destination in remotes && !sameRemote
    val defaultName = if (source != null && destination != null) {
        stringResource(R.string.dialog_sync_job_default_name, "$source:", "$destination:")
    } else {
        ""
    }

    AlertDialog(
        title = {
            Text(text = stringResource(if (initial == null) {
                R.string.dialog_add_sync_job_title
            } else {
                R.string.dialog_edit_sync_job_title
            }))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                OutlinedTextField(
                    state = nameInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.dialog_sync_job_name_hint)) },
                    placeholder = { Text(text = defaultName) },
                    lineLimits = TextFieldLineLimits.SingleLine,
                )

                RemoteDropdown(
                    label = stringResource(R.string.dialog_sync_job_source_hint),
                    remotes = remotes,
                    selected = source,
                    onSelect = { source = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                RemoteDropdown(
                    label = stringResource(R.string.dialog_sync_job_destination_hint),
                    remotes = remotes,
                    selected = destination,
                    onSelect = { destination = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                if (sameRemote) {
                    Text(
                        text = stringResource(R.string.dialog_sync_job_same_remote),
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text(
                    text = stringResource(R.string.dialog_sync_job_mode),
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                )

                Column(modifier = Modifier.selectableGroup()) {
                    for (m in RcloneRpc.TransferMode.entries) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = mode == m,
                                    onClick = { mode = m },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == m, onClick = null)

                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(text = stringResource(m.labelResId))
                                Text(
                                    text = stringResource(m.descResId),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val name = nameInput.text.toString().trim().ifEmpty { defaultName }
                    onSave(name, source!!, destination!!, mode)
                },
                enabled = isValid,
            ) {
                Text(text = stringResource(R.string.dialog_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteDropdown(
    label: String,
    remotes: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected?.let { "$it:" } ?: "",
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            singleLine = true,
            label = { Text(text = label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = selected != null && selected !in remotes,
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (remote in remotes) {
                DropdownMenuItem(
                    text = { Text(text = "$remote:") },
                    onClick = {
                        onSelect(remote)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
fun SyncJobActionsDialog(
    state: SyncJobState,
    onRun: () -> Unit,
    onDryRun: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = { Text(text = state.job.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                if (state.isActive) {
                    ActionButton(R.string.sync_jobs_action_stop, true, onStop)
                } else {
                    ActionButton(R.string.sync_jobs_action_run, state.canRun, onRun)
                    ActionButton(R.string.sync_jobs_action_dry_run, state.canRun, onDryRun)
                }
                ActionButton(R.string.sync_jobs_action_edit, !state.isActive, onEdit)
                ActionButton(R.string.sync_jobs_action_delete, !state.isActive, onDelete)
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ActionButton(labelResId: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        Text(text = stringResource(labelResId), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun DeleteSyncJobDialog(
    job: SyncJob,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = { Text(text = stringResource(R.string.dialog_delete_sync_job_title)) },
        text = { Text(text = stringResource(R.string.dialog_delete_sync_job_message, job.name)) },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.sync_jobs_action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
