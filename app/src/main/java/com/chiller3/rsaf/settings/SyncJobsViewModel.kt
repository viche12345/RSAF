/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.chiller3.rsaf.extension.toSingleLineString
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.sync.SyncJob
import com.chiller3.rsaf.sync.SyncJobStore
import com.chiller3.rsaf.sync.SyncProgress
import com.chiller3.rsaf.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncJobState(
    val job: SyncJob,
    /** Remotes referenced by the job that no longer exist (without the trailing colon). */
    val missingRemotes: List<String>,
    /** Whether the job is queued or running. */
    val isActive: Boolean,
    /** Progress of the current run, if [isActive] and the worker has reported progress. */
    val progress: SyncProgress?,
) {
    val canRun: Boolean
        get() = !isActive && missingRemotes.isEmpty()
}

class SyncJobsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val TAG = SyncJobsViewModel::class.java.simpleName
    }

    private val store = SyncJobStore.getInstance(application)
    private val workManager = WorkManager.getInstance(application)

    /** Names of all remotes (without the trailing colon), or null if not loaded yet. */
    private val _remotes = MutableStateFlow<List<String>?>(null)
    val remotes = _remotes.asStateFlow()

    private val _alerts = MutableStateFlow<List<SyncJobsAlert>>(emptyList())
    val alerts = _alerts.asStateFlow()

    /** All sync jobs and their current state, or null if not loaded yet. */
    val jobs: StateFlow<List<SyncJobState>?> = combine(
        store.jobs.catch { e ->
            Log.w(TAG, "Failed to load sync jobs", e)
            addAlert(SyncJobsAlert.LoadFailed(e.toSingleLineString()))
            emit(emptyList())
        },
        workManager.getWorkInfosByTagFlow(SyncWorker.TAG_SYNC),
        _remotes,
    ) { jobs, workInfos, remotes ->
        val activeWork = workInfos
            .filter { !it.state.isFinished }
            .associateBy { SyncWorker.getJobId(it) }

        jobs.map { job ->
            val workInfo = activeWork[job.id]
            val missingRemotes = if (remotes == null) {
                emptyList()
            } else {
                listOf(job.source, job.destination).distinct().filter { it !in remotes }
            }

            SyncJobState(
                job = job,
                missingRemotes = missingRemotes,
                isActive = workInfo != null,
                progress = workInfo?.let { SyncProgress.fromData(it.progress) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        refreshRemotes()
    }

    private fun refreshRemotes() {
        viewModelScope.launch {
            try {
                _remotes.value = withContext(Dispatchers.IO) {
                    RcloneRpc.remoteNames.sorted()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list remotes", e)
                _remotes.value = emptyList()
                addAlert(SyncJobsAlert.ListRemotesFailed(e.toSingleLineString()))
            }
        }
    }

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }

    private fun addAlert(alert: SyncJobsAlert) {
        _alerts.update { it + alert }
    }

    /** Add [job] if [isNew]. Otherwise, update the existing job's definition. */
    fun saveJob(job: SyncJob, isNew: Boolean) {
        viewModelScope.launch {
            try {
                if (isNew) {
                    store.add(job)
                } else {
                    store.update(job)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save sync job", e)
                addAlert(SyncJobsAlert.SaveFailed(e.toSingleLineString()))
            }
        }
    }

    fun deleteJob(id: String) {
        SyncWorker.cancel(getApplication(), id)

        viewModelScope.launch {
            try {
                store.delete(id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete sync job", e)
                addAlert(SyncJobsAlert.DeleteFailed(e.toSingleLineString()))
            }
        }
    }

    fun runJob(id: String, dryRun: Boolean) {
        SyncWorker.enqueue(getApplication(), id, dryRun)
    }

    fun stopJob(id: String) {
        SyncWorker.cancel(getApplication(), id)
    }
}
