/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

sealed interface SyncJobsAlert {
    data class ListRemotesFailed(val error: String) : SyncJobsAlert

    data class LoadFailed(val error: String) : SyncJobsAlert

    data class SaveFailed(val error: String) : SyncJobsAlert

    data class DeleteFailed(val error: String) : SyncJobsAlert
}
