/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.sync

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.chiller3.rsaf.rclone.RcloneRpc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

object SyncJobsSerializer : Serializer<List<SyncJob>> {
    private const val VERSION = 1

    override val defaultValue: List<SyncJob> = emptyList()

    override suspend fun readFrom(input: InputStream): List<SyncJob> {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) {
            return defaultValue
        }

        try {
            val root = JSONObject(text)
            val version = root.getInt("version")
            if (version != VERSION) {
                throw CorruptionException("Unsupported sync jobs version: $version")
            }

            val jobsJson = root.getJSONArray("jobs")

            return (0 until jobsJson.length()).map { jobFromJson(jobsJson.getJSONObject(it)) }
        } catch (e: JSONException) {
            throw CorruptionException("Failed to parse sync jobs", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Invalid sync job", e)
        }
    }

    override suspend fun writeTo(t: List<SyncJob>, output: OutputStream) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("jobs", JSONArray().apply { t.forEach { put(jobToJson(it)) } })

        output.write(root.toString().encodeToByteArray())
    }

    private fun jobFromJson(json: JSONObject) = SyncJob(
        id = json.getString("id"),
        name = json.getString("name"),
        source = json.getString("source"),
        destination = json.getString("destination"),
        mode = RcloneRpc.TransferMode.valueOf(json.getString("mode")),
        // Added after the initial version, so these are optional.
        deleteBefore = json.optBoolean("delete_before", false),
        transfers = json.optInt("transfers", SyncJob.DEFAULT_TRANSFERS)
            .coerceIn(SyncJob.MIN_TRANSFERS, SyncJob.MAX_TRANSFERS),
        lastResult = json.optJSONObject("last_result")?.let(::resultFromJson),
    )

    private fun jobToJson(job: SyncJob) = JSONObject()
        .put("id", job.id)
        .put("name", job.name)
        .put("source", job.source)
        .put("destination", job.destination)
        .put("mode", job.mode.name)
        .put("delete_before", job.deleteBefore)
        .put("transfers", job.transfers)
        .apply { job.lastResult?.let { put("last_result", resultToJson(it)) } }

    private fun resultFromJson(json: JSONObject) = SyncJobResult(
        endTime = json.getLong("end_time"),
        success = json.getBoolean("success"),
        dryRun = json.getBoolean("dry_run"),
        cancelled = json.getBoolean("cancelled"),
        error = if (json.isNull("error")) null else json.getString("error"),
        bytes = json.getLong("bytes"),
        transfers = json.getLong("transfers"),
        deletes = json.getLong("deletes"),
        errors = json.getLong("errors"),
    )

    private fun resultToJson(result: SyncJobResult) = JSONObject()
        .put("end_time", result.endTime)
        .put("success", result.success)
        .put("dry_run", result.dryRun)
        .put("cancelled", result.cancelled)
        .put("error", result.error ?: JSONObject.NULL)
        .put("bytes", result.bytes)
        .put("transfers", result.transfers)
        .put("deletes", result.deletes)
        .put("errors", result.errors)
}

/**
 * Persistent storage for sync job definitions and their last results.
 *
 * This is intentionally separate from the default shared preferences because every change there
 * triggers an Android backup and the results are updated after every run.
 */
class SyncJobStore private constructor(context: Context) {
    companion object {
        private val TAG = SyncJobStore::class.java.simpleName

        private const val FILENAME = "sync_jobs.json"

        @Volatile
        private var instance: SyncJobStore? = null

        fun getInstance(context: Context): SyncJobStore =
            instance ?: synchronized(this) {
                instance ?: SyncJobStore(context.applicationContext).also { instance = it }
            }
    }

    private val dataStore: DataStore<List<SyncJob>> = DataStoreFactory.create(
        serializer = SyncJobsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { e ->
            Log.w(TAG, "Sync jobs file is corrupted; discarding", e)
            SyncJobsSerializer.defaultValue
        },
        produceFile = { context.dataStoreFile(FILENAME) },
    )

    val jobs: Flow<List<SyncJob>> = dataStore.data

    suspend fun get(id: String): SyncJob? = jobs.first().find { it.id == id }

    suspend fun add(job: SyncJob) {
        dataStore.updateData { it + job }
    }

    /** Update the definition of an existing job. The last result is preserved. */
    suspend fun update(job: SyncJob) {
        dataStore.updateData { jobs ->
            jobs.map { if (it.id == job.id) job.copy(lastResult = it.lastResult) else it }
        }
    }

    suspend fun delete(id: String) {
        dataStore.updateData { jobs -> jobs.filter { it.id != id } }
    }

    suspend fun setResult(id: String, result: SyncJobResult) {
        dataStore.updateData { jobs ->
            jobs.map { if (it.id == id) it.copy(lastResult = result) else it }
        }
    }
}
