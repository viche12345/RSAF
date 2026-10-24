/*
 * SPDX-FileCopyrightText: 2026 Vincent Kwok
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.rsaf

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chiller3.rsaf.rclone.RcloneRpc
import com.chiller3.rsaf.rclone.RcloneRpcException
import com.chiller3.rsaf.rclone.VfsCache
import com.chiller3.rsaf.sync.SyncJob
import com.chiller3.rsaf.sync.SyncJobResult
import com.chiller3.rsaf.sync.SyncJobStore
import com.chiller3.rsaf.sync.SyncJobsSerializer
import com.chiller3.rsaf.sync.SyncProgress
import com.chiller3.rsaf.sync.SyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncTest {
    companion object {
        private val TAG = SyncTest::class.java.simpleName

        private fun <R> retryTimeout(timeoutMs: Long, block: () -> R): R {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                try {
                    return block()
                } catch (e: Throwable) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw IllegalStateException("Condition not met after $timeoutMs ms", e)
                    }
                    Thread.sleep(100)
                }
            }
        }
    }

    private lateinit var appContext: Context
    private lateinit var srcRemote: String
    private lateinit var dstRemote: String
    private lateinit var srcDir: File
    private lateinit var dstDir: File

    private fun createAliasRemote(dir: File): String {
        val remote = "testing." + RandomUtils.generatePassword(16, RandomUtils.ASCII_ALPHANUMERIC)
        dir.mkdirs()

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

        assert(RcloneRpc.remoteNames.contains(remote))

        return remote
    }

    @Before
    fun createTempRemotes() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext

        val baseDir = File(appContext.cacheDir, "sync-test")
        baseDir.deleteRecursively()
        srcDir = File(baseDir, "src")
        dstDir = File(baseDir, "dst")

        srcRemote = createAliasRemote(srcDir)
        dstRemote = createAliasRemote(dstDir)

        File(srcDir, "a.txt").writeText("a")
        File(srcDir, "sub").mkdir()
        File(srcDir, "sub/b.txt").writeText("bb")
        File(dstDir, "extra.txt").writeText("extra")
    }

    @After
    fun deleteTempRemotes() {
        RcloneRpc.deleteRemote(srcRemote)
        RcloneRpc.deleteRemote(dstRemote)
        srcDir.parentFile!!.deleteRecursively()
    }

    private fun transfer(mode: RcloneRpc.TransferMode, dryRun: Boolean = false): RcloneRpc.TransferStats {
        val group = "rsaf-sync-test/" + RandomUtils.generatePassword(8, RandomUtils.ASCII_ALPHANUMERIC)

        RcloneRpc.runTransfer(mode, "$srcRemote:", "$dstRemote:", dryRun, group)

        return RcloneRpc.coreStats(group).also {
            RcloneRpc.deleteStatsGroup(group)
        }
    }

    private fun File.relativeFiles(): Set<String> =
        walkTopDown().filter { it.isFile }.map { it.relativeTo(this).path }.toSet()

    @Test
    fun sync() {
        val stats = transfer(RcloneRpc.TransferMode.SYNC)

        assertEquals(setOf("a.txt", "sub/b.txt"), dstDir.relativeFiles())
        assertEquals(setOf("a.txt", "sub/b.txt"), srcDir.relativeFiles())
        assertEquals("bb", File(dstDir, "sub/b.txt").readText())
        assertEquals(2L, stats.transfers)
        assertEquals(1L, stats.deletes)
        assertEquals(0L, stats.errors)
    }

    @Test
    fun copy() {
        transfer(RcloneRpc.TransferMode.COPY)

        assertEquals(setOf("a.txt", "extra.txt", "sub/b.txt"), dstDir.relativeFiles())
        assertEquals(setOf("a.txt", "sub/b.txt"), srcDir.relativeFiles())
    }

    @Test
    fun move() {
        transfer(RcloneRpc.TransferMode.MOVE)

        assertEquals(setOf("a.txt", "extra.txt", "sub/b.txt"), dstDir.relativeFiles())
        assertEquals(emptySet<String>(), srcDir.relativeFiles())
    }

    @Test
    fun syncDryRun() {
        val stats = transfer(RcloneRpc.TransferMode.SYNC, dryRun = true)
        Log.i(TAG, "Dry run stats: $stats")

        assertEquals(setOf("extra.txt"), dstDir.relativeFiles())
        assertEquals(setOf("a.txt", "sub/b.txt"), srcDir.relativeFiles())
        assertEquals(0L, stats.errors)
    }

    @Test
    fun missingRemoteError() {
        val e = assertThrows(RcloneRpcException::class.java) {
            RcloneRpc.runTransfer(
                RcloneRpc.TransferMode.SYNC,
                "$srcRemote:",
                "nonexistent.remote.for.testing:",
                false,
                "rsaf-sync-test/missing",
            )
        }

        assertTrue(e.error!!.isNotEmpty())
        assertEquals(setOf("extra.txt"), dstDir.relativeFiles())
    }

    @Test
    fun stopUnknownGroup() {
        RcloneRpc.jobStopGroup("rsaf-sync-test/nonexistent")
    }

    private fun workInfo(jobId: String): WorkInfo? =
        WorkManager.getInstance(appContext)
            .getWorkInfosByTag(SyncWorker.TAG_SYNC)
            .get()
            .find { SyncWorker.getJobId(it) == jobId }

    @Test
    fun workerWaitsForPendingUploads() {
        val store = SyncJobStore.getInstance(appContext)
        val job = SyncJob(UUID.randomUUID().toString(), "test", srcRemote, dstRemote,
            RcloneRpc.TransferMode.SYNC)
        runBlocking { store.add(job) }

        try {
            // Pretend that a SAF client just closed a file on the source remote and it's still
            // being uploaded from the VFS cache.
            VfsCache.syncUploadIncrement(srcRemote)
            try {
                SyncWorker.enqueue(appContext, job.id, false)

                retryTimeout(10_000) {
                    val progress = SyncProgress.fromData(workInfo(job.id)!!.progress)
                    assertTrue(progress!!.waitingForUploads)
                }

                // Nothing should be transferred while uploads are pending.
                Thread.sleep(2000)
                assertEquals(WorkInfo.State.RUNNING, workInfo(job.id)!!.state)
                assertEquals(setOf("extra.txt"), dstDir.relativeFiles())
            } finally {
                VfsCache.syncUploadDecrement(srcRemote)
            }

            val finalInfo = retryTimeout(30_000) {
                workInfo(job.id)!!.also { assertTrue(it.state.isFinished) }
            }
            assertEquals(WorkInfo.State.SUCCEEDED, finalInfo.state)
            assertEquals(setOf("a.txt", "sub/b.txt"), dstDir.relativeFiles())

            val result = runBlocking { store.get(job.id)!!.lastResult!! }
            assertTrue(result.success)
            assertEquals(2L, result.transfers)
            assertEquals(1L, result.deletes)
        } finally {
            SyncWorker.cancel(appContext, job.id)
            runBlocking { store.delete(job.id) }
        }
    }

    @Test
    fun serializerRoundTrip() = runBlocking {
        val jobs = listOf(
            SyncJob("1", "Job 1", "a", "b", RcloneRpc.TransferMode.SYNC),
            SyncJob(
                "2", "Job → 2", "c", "d", RcloneRpc.TransferMode.MOVE,
                SyncJobResult(1234, false, true, false, "some error", 5, 6, 7, 8),
            ),
            SyncJob(
                "3", "Job 3", "e", "f", RcloneRpc.TransferMode.COPY,
                SyncJobResult(5678, true, false, true, null, 0, 0, 0, 0),
            ),
        )

        val output = ByteArrayOutputStream()
        SyncJobsSerializer.writeTo(jobs, output)

        val decoded = SyncJobsSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(jobs, decoded)
    }

    @Test
    fun serializerEmptyInput() = runBlocking {
        val decoded = SyncJobsSerializer.readFrom(ByteArrayInputStream(ByteArray(0)))
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun serializerCorruptInput() {
        for (input in arrayOf(
            "not json",
            """{"version": 999, "jobs": []}""",
            """{"version": 1, "jobs": [{"id": "1"}]}""",
            """{"version": 1, "jobs": [{"id": "1", "name": "n", "source": "a", "destination": "b", "mode": "BISYNC"}]}""",
        )) {
            val e = runCatching {
                runBlocking {
                    SyncJobsSerializer.readFrom(ByteArrayInputStream(input.toByteArray()))
                }
            }.exceptionOrNull()

            assertTrue("Expected CorruptionException for: $input", e is CorruptionException)
        }
    }
}
