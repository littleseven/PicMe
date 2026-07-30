package com.mamba.picme.server.diag

import com.mamba.picme.server.db.DiagJobs
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
