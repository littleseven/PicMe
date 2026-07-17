package com.mamba.picme.server.auth

import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestServiceDeleteTest {

    @Test
    fun `deleteByDeviceId removes the device row`() = runBlocking {
        TestDb.init(AnonymousDevices)
        GuestService.checkAndIncrementQuota("dev-1", 100) // creates the row
        assertEquals(1L, transaction(Db.instance) { AnonymousDevices.selectAll().count() })

        GuestService.deleteByDeviceId("dev-1")

        assertEquals(0L, transaction(Db.instance) { AnonymousDevices.selectAll().count() })
    }

    @Test
    fun `deleteByDeviceId is idempotent for unknown device`() = runBlocking {
        TestDb.init(AnonymousDevices)
        GuestService.deleteByDeviceId("never-seen")
        assertEquals(0L, transaction(Db.instance) { AnonymousDevices.selectAll().count() })
    }
}
