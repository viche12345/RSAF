/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SyncJob(
    val id: String,
    val source: String,
    val dest: String,
    /** Copy instead of sync (never delete files in [dest]). */
    val copy: Boolean,
)

/** Persists sync jobs as a JSON array in a dedicated SharedPreferences file. */
class SyncJobStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "sync_jobs"
        private const val KEY_JOBS = "jobs"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val jobs: List<SyncJob>
        get() {
            val array = JSONArray(prefs.getString(KEY_JOBS, null) ?: "[]")

            return (0 until array.length()).map {
                val obj = array.getJSONObject(it)
                SyncJob(
                    obj.getString("id"),
                    obj.getString("source"),
                    obj.getString("dest"),
                    obj.getBoolean("copy"),
                )
            }
        }

    /** Emits [jobs] now and whenever they change. */
    val jobsFlow: Flow<List<SyncJob>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(jobs)
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(jobs)

        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    operator fun get(id: String): SyncJob? = jobs.find { it.id == id }

    fun add(source: String, dest: String, copy: Boolean) {
        save(jobs + SyncJob(UUID.randomUUID().toString(), source, dest, copy))
    }

    fun remove(id: String) {
        save(jobs.filter { it.id != id })
    }

    private fun save(jobs: List<SyncJob>) {
        val array = JSONArray()
        for (job in jobs) {
            array.put(JSONObject()
                .put("id", job.id)
                .put("source", job.source)
                .put("dest", job.dest)
                .put("copy", job.copy))
        }

        prefs.edit { putString(KEY_JOBS, array.toString()) }
    }
}
