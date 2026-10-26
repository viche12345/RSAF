/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chiller3.rsaf.rclone.RcloneRpc
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SyncJobTest {
    private lateinit var appContext: Context
    private val remotes = mutableListOf<Pair<String, File>>()

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun deleteTempRemotes() {
        for ((remote, dir) in remotes) {
            RcloneRpc.deleteRemote(remote)
            dir.deleteRecursively()
        }
    }

    /** Create an alias remote pointing to a new temp directory. */
    private fun createTempRemote(): Pair<String, File> {
        val remote = "testing." + RandomUtils.generatePassword(16, RandomUtils.ASCII_ALPHANUMERIC)
        val dir = File(appContext.cacheDir, remote).apply { mkdirs() }

        val iq = RcloneRpc.InteractiveConfiguration(remote)
        while (true) {
            val (_, option) = iq.question ?: break

            when (option.name) {
                "type" -> iq.submit("alias")
                "remote" -> iq.submit(dir.toString())
                "config_fs_advanced" -> iq.submit("false")
                else -> throw IllegalStateException("Unexpected question: ${option.name}")
            }
        }

        return (remote to dir).also { remotes.add(it) }
    }

    private fun runJob(source: String, dest: String, copy: Boolean): RcloneRpc.JobStatus {
        val group = "test"
        val jobId = RcloneRpc.startSyncJob(source, dest, copy, group)

        try {
            while (true) {
                val status = RcloneRpc.jobStatus(jobId)
                if (status.finished) {
                    return status
                }

                Thread.sleep(100)
            }
        } finally {
            RcloneRpc.deleteGroupStats(group)
        }
    }

    private fun setUpSourceAndDest(): Pair<Pair<String, File>, Pair<String, File>> {
        val source = createTempRemote()
        val dest = createTempRemote()

        File(source.second, "file.txt").writeText("hello")
        File(dest.second, "extra.txt").writeText("extra")

        return source to dest
    }

    @Test
    fun copyKeepsExtraFiles() {
        val (source, dest) = setUpSourceAndDest()

        assertTrue(runJob(source.first, dest.first, true).success)
        assertEquals("hello", File(dest.second, "file.txt").readText())
        assertTrue(File(dest.second, "extra.txt").exists())
    }

    @Test
    fun syncDeletesExtraFiles() {
        val (source, dest) = setUpSourceAndDest()

        assertTrue(runJob(source.first, dest.first, false).success)
        assertEquals("hello", File(dest.second, "file.txt").readText())
        assertFalse(File(dest.second, "extra.txt").exists())
    }

    @Test
    fun missingRemoteFails() {
        val (dest, _) = createTempRemote()

        val status = runJob("testing.nonexistent", dest, false)
        assertFalse(status.success)
        assertTrue(status.error.isNotBlank())
    }
}
