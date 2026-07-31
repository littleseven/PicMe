package com.mamba.picme.server.diag

import com.mamba.picme.server.db.DiagJobs
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagServiceTest {

    @Test
    fun `createJob inserts a QUEUED row owned by token hash`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("hash-a", null, "app crashes on open", "{}", "abc123") }
        val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertEquals(DiagStatus.QUEUED.name, row[DiagJobs.status])
        assertEquals("hash-a", row[DiagJobs.ownerTokenHash])
        assertEquals("abc123", row[DiagJobs.gitSha])
    }

    @Test
    fun `getJob returns the row only for its owner`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("owner-a", null, "d", "{}", "sha") }
        assertNotNull(runBlocking { DiagService.getJob(id, "owner-a") })
        assertNull(runBlocking { DiagService.getJob(id, "owner-b") })
    }

    @Test
    fun `claimNextJob returns QUEUED job as diagnose phase`() {
        TestDb.init(DiagJobs)
        runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        val claim = runBlocking { DiagService.claimNextJob() }
        assertNotNull(claim)
        assertEquals("diagnose", claim!!.phase)
    }

    @Test
    fun `submitDiagnosis moves QUEUED to DIAGNOSED with root cause`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, "NPE in Foo.kt:42", DiagStatus.DIAGNOSED, null) }
        val job = runBlocking { DiagService.getJob(id, "o") }!!
        assertEquals(DiagStatus.DIAGNOSED, job.status)
        assertEquals("NPE in Foo.kt:42", job.rootCause)
    }

    @Test
    fun `confirmFix moves DIAGNOSED to FIX_REQUESTED and stores mode`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
        val ok = runBlocking { DiagService.confirmFix(id, "o", "pr") }
        assertTrue(ok)
        val job = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertEquals(DiagStatus.FIX_REQUESTED.name, job[DiagJobs.status])
        assertEquals("pr", job[DiagJobs.fixMode])
    }

    @Test
    fun `confirmFix rejects wrong owner and non-DIAGNOSED state`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        // wrong owner
        assertFalse(runBlocking { DiagService.confirmFix(id, "other", "push") })
        // still QUEUED (not DIAGNOSED) → reject even for owner
        assertFalse(runBlocking { DiagService.confirmFix(id, "o", "push") })
    }

    @Test
    fun `submitFix moves FIX_REQUESTED to FIXED with branch`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
        runBlocking { DiagService.confirmFix(id, "o", "push") }
        runBlocking { DiagService.submitFix(id, DiagStatus.FIXED, "diag-fix/1", null, tested = true, error = null) }
        val job = runBlocking { DiagService.getJob(id, "o") }!!
        assertEquals(DiagStatus.FIXED, job.status)
        assertEquals("diag-fix/1", job.fixBranch)
        assertTrue(job.tested)
    }

    @Test
    fun `claimNextJob returns FIX_REQUESTED job as fix phase`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
        runBlocking { DiagService.confirmFix(id, "o", "push") }
        val claim = runBlocking { DiagService.claimNextJob() }
        assertEquals("fix", claim!!.phase)
        assertEquals("rc", claim.rootCause)
        assertEquals("push", claim.fixMode)
    }

    @Test
    fun `deleteById physically removes the job row`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.deleteById(id) }
        val count = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.count() }
        assertEquals(0L, count)
    }

    @Test
    fun `archive moves any status to ARCHIVED`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        // QUEUED 态直接废弃
        runBlocking { DiagService.archive(id) }
        var row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertEquals(DiagStatus.ARCHIVED.name, row[DiagJobs.status])
        // 再种一个 FIXED 态验证任意态可废弃
        val id2 = runBlocking { DiagService.createJob("o2", null, "d2", "{}", "sha2") }
        transaction(Db.instance) {
            DiagJobs.update({ DiagJobs.id eq id2 }) {
                it[DiagJobs.status] = DiagStatus.FIXED.name
                it[DiagJobs.fixBranch] = "diag-fix/x"
            }
        }
        runBlocking { DiagService.archive(id2) }
        row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id2 }.single() }
        assertEquals(DiagStatus.ARCHIVED.name, row[DiagJobs.status])
    }
}
