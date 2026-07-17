package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.DEVICE_ID_HEADER
import com.mamba.picme.server.auth.GuestService
import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestDeletionRouteTest {

    @Test
    fun `DELETE guest device removes the anonymous device row`() = testApplication {
        TestDb.init(AnonymousDevices)
        runBlocking { GuestService.checkAndIncrementQuota("dev-x", 100) }
        application { routing { guestDeletionRoute() } }

        val resp = client.delete("/guest/device") {
            header(DEVICE_ID_HEADER, "dev-x")
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(0L, transaction(Db.instance) { AnonymousDevices.selectAll().count() })
    }

    @Test
    fun `DELETE guest device returns 400 without device id header`() = testApplication {
        TestDb.init(AnonymousDevices)
        application { routing { guestDeletionRoute() } }

        val resp = client.delete("/guest/device")

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
