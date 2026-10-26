/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.sync.SyncJob
import com.chiller3.rsaf.sync.SyncJobStore
import com.chiller3.rsaf.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SyncJobStatus {
    data object Idle : SyncJobStatus
    data object Queued : SyncJobStatus
    data class Running(val bytes: Long, val totalBytes: Long) : SyncJobStatus
    data object Succeeded : SyncJobStatus
    data class Failed(val error: String) : SyncJobStatus
    data object Cancelled : SyncJobStatus

    val isActive: Boolean
        get() = this is Queued || this is Running
}

data class SyncJobState(
    val job: SyncJob,
    val status: SyncJobStatus,
)

class SyncJobsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val TAG = SyncJobsViewModel::class.java.simpleName

        private fun WorkInfo?.toStatus(): SyncJobStatus = when (this?.state) {
            null -> SyncJobStatus.Idle
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SyncJobStatus.Queued
            WorkInfo.State.RUNNING -> SyncJobStatus.Running(
                progress.getLong(SyncWorker.KEY_BYTES, 0),
                progress.getLong(SyncWorker.KEY_TOTAL_BYTES, 0),
            )
            WorkInfo.State.SUCCEEDED -> SyncJobStatus.Succeeded
            WorkInfo.State.FAILED ->
                SyncJobStatus.Failed(outputData.getString(SyncWorker.KEY_ERROR).orEmpty())
            WorkInfo.State.CANCELLED -> SyncJobStatus.Cancelled
        }
    }

    private val store = SyncJobStore(application)

    val jobs = combine(
        store.jobsFlow,
        WorkManager.getInstance(application).getWorkInfosByTagFlow(SyncWorker.TAG_SYNC),
    ) { jobs, workInfos ->
        jobs.map { job ->
            val workInfo = workInfos.find { SyncWorker.workName(job.id) in it.tags }
            SyncJobState(job, workInfo.toStatus())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _remotes = MutableStateFlow<List<String>>(emptyList())
    val remotes = _remotes.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _remotes.value = withContext(Dispatchers.IO) { RcloneRpc.remoteNames.sorted() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list remotes", e)
            }
        }
    }

    fun add(source: String, dest: String, copy: Boolean) {
        store.add(source, dest, copy)
    }

    fun run(jobId: String) {
        SyncWorker.enqueue(getApplication(), jobId)
    }

    fun stop(jobId: String) {
        SyncWorker.cancel(getApplication(), jobId)
    }

    fun delete(jobId: String) {
        SyncWorker.cancel(getApplication(), jobId)
        store.remove(jobId)
    }
}
