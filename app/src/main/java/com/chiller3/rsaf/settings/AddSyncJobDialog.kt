/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chiller3.rsaf.R

@Composable
fun AddSyncJobDialog(
    remotes: List<String>,
    onAdd: (source: String, dest: String, copy: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var dest by rememberSaveable { mutableStateOf<String?>(null) }
    var copy by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        title = { Text(text = stringResource(R.string.dialog_add_sync_job_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(state = rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RemoteDropdown(
                    label = stringResource(R.string.dialog_add_sync_job_source),
                    remotes = remotes,
                    selected = source,
                    onSelect = { source = it },
                )
                RemoteDropdown(
                    label = stringResource(R.string.dialog_add_sync_job_dest),
                    remotes = remotes,
                    selected = dest,
                    onSelect = { dest = it },
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        false to R.string.sync_job_mode_sync,
                        true to R.string.sync_job_mode_copy,
                    ).forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = copy == value,
                            onClick = { copy = value },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) {
                            Text(text = stringResource(label))
                        }
                    }
                }

                Text(text = stringResource(
                    if (copy) {
                        R.string.dialog_add_sync_job_copy_desc
                    } else {
                        R.string.dialog_add_sync_job_sync_warning
                    }
                ))
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onAdd(source!!, dest!!, copy) },
                enabled = source != null && dest != null && source != dest,
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteDropdown(
    label: String,
    remotes: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(text = label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (remote in remotes) {
                DropdownMenuItem(
                    text = { Text(text = remote) },
                    onClick = {
                        onSelect(remote)
                        expanded = false
                    },
                )
            }
        }
    }
}
