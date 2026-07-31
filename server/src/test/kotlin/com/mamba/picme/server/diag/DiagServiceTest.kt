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

    @Test
    fun `activate resets ARCHIVED to QUEUED and clears produced fields but keeps createdAt`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        val created = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single()[DiagJobs.createdAt] }
        // 造一个有完整产出的 ARCHIVED 行
        transaction(Db.instance) {
            DiagJobs.update({ DiagJobs.id eq id }) {
                it[DiagJobs.status] = DiagStatus.ARCHIVED.name
                it[DiagJobs.rootCause] = "old rc"
                it[DiagJobs.fixMode] = "push"
                it[DiagJobs.fixBranch] = "diag-fix/old"
                it[DiagJobs.compareUrl] = "https://x/compare"
                it[DiagJobs.workerLog] = "old log"
                it[DiagJobs.tested] = 1
                it[DiagJobs.claimedAt] = 1_700_000_000_000L
            }
        }
        val ok = runBlocking { DiagService.activate(id) }
        assertTrue(ok)
        val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertEquals(DiagStatus.QUEUED.name, row[DiagJobs.status])
        assertNull(row[DiagJobs.rootCause])
        assertNull(row[DiagJobs.fixMode])
        assertNull(row[DiagJobs.fixBranch])
        assertNull(row[DiagJobs.compareUrl])
        assertNull(row[DiagJobs.workerLog])
        assertEquals(0, row[DiagJobs.tested])
        assertNull(row[DiagJobs.claimedAt])
        assertEquals(created, row[DiagJobs.createdAt]) // 创建时间保留不变
    }

    @Test
    fun `activate rejects QUEUED and FIX_REQUESTED`() {
        TestDb.init(DiagJobs)
        val queued = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        assertFalse(runBlocking { DiagService.activate(queued) })
        // FIX_REQUESTED：走完整流程到该态
        val fixReq = runBlocking { DiagService.createJob("o2", null, "d2", "{}", "sha2") }
        runBlocking { DiagService.submitDiagnosis(fixReq, "rc", DiagStatus.DIAGNOSED, null) }
        runBlocking { DiagService.confirmFix(fixReq, "o2", "push") }
        assertFalse(runBlocking { DiagService.activate(fixReq) })
        // 状态未被改
        val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq fixReq }.single() }
        assertEquals(DiagStatus.FIX_REQUESTED.name, row[DiagJobs.status])
    }

    @Test
    fun `submitDiagnosis is ignored after the job is archived`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        runBlocking { DiagService.archive(id) } // 先废弃
        runBlocking { DiagService.submitDiagnosis(id, "late rc", DiagStatus.DIAGNOSED, null) }
        val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        // 守卫挡住：仍是 ARCHIVED，未写入迟到回传
        assertEquals(DiagStatus.ARCHIVED.name, row[DiagJobs.status])
        assertNull(row[DiagJobs.rootCause])
    }

    @Test
    fun `submitFix is ignored after the job is archived`() {
        TestDb.init(DiagJobs)
        val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
        // 走到 FIX_REQUESTED 再废弃，模拟修复阶段迟到回传
        runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
        runBlocking { DiagService.confirmFix(id, "o", "push") }
        runBlocking { DiagService.archive(id) }
        runBlocking { DiagService.submitFix(id, DiagStatus.FIXED, "diag-fix/late", null, tested = true, error = null) }
        val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
        assertEquals(DiagStatus.ARCHIVED.name, row[DiagJobs.status])
        assertNull(row[DiagJobs.fixBranch])
    }
}
