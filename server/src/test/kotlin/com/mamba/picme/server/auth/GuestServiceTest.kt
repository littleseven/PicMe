package com.mamba.picme.server.auth

import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestServiceTest {

    private val limit = 3

    @Test
    fun `first call is allowed and creates a row with used 1`() = runBlocking {
        TestDb.init(AnonymousDevices)
        val r = GuestService.checkAndIncrementQuota("dev-1", limit)
        assertTrue(r.allowed)
        assertEquals(2, r.remaining) // limit(3) - used(1)
        val used = transaction { AnonymousDevices.selectAll().single()[AnonymousDevices.llmCallsUsed] }
        assertEquals(1, used)
    }

    @Test
    fun `allows exactly limit calls then blocks`() = runBlocking {
        TestDb.init(AnonymousDevices)
        repeat(limit) { i ->
            val r = GuestService.checkAndIncrementQuota("dev-2", limit)
            assertTrue("call ${i + 1} should be allowed", r.allowed)
        }
        val blocked = GuestService.checkAndIncrementQuota("dev-2", limit)
        assertFalse(blocked.allowed)
        assertEquals(0, blocked.remaining)
    }

    @Test
    fun `devices are independent`() = runBlocking {
        TestDb.init(AnonymousDevices)
        repeat(limit) { GuestService.checkAndIncrementQuota("dev-a", limit) }
        val other = GuestService.checkAndIncrementQuota("dev-b", limit)
        assertTrue(other.allowed) // dev-b unaffected by dev-a exhaustion
    }

    @Test
    fun `revert decrements used`() = runBlocking {
        TestDb.init(AnonymousDevices)
        GuestService.checkAndIncrementQuota("dev-3", limit)
        GuestService.revertQuota("dev-3")
        val used = transaction { AnonymousDevices.selectAll().single()[AnonymousDevices.llmCallsUsed] }
        assertEquals(0, used)
    }

    @Test
    fun `revert is a no-op for unknown device`() = runBlocking {
        TestDb.init(AnonymousDevices)
        GuestService.revertQuota("never-seen") // must not throw / not go negative
        assertEquals(0L, transaction { AnonymousDevices.selectAll().count() })
    }
}
