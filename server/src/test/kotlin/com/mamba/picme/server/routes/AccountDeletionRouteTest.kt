package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AccountDeletionRouteTest {

    @Test
    fun `DELETE auth account soft deletes active account`() = testApplication {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("user@example.com", 100)
        val tokenHash = AccountService.sha256(info.token)
        application {
            // 测试用简化 interceptor 注入 tokenHash；真实 auth 链路由 AccountService.validateToken 覆盖
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(TokenHashKey, tokenHash)
            }
            routing { accountDeletionRoute() }
        }

        val resp = client.delete("/auth/account")

        assertEquals(HttpStatusCode.OK, resp.status)
        val row = transaction(Db.instance) { Accounts.selectAll().single() }
        assertEquals("deleted", row[Accounts.status])
        assertNotNull(row[Accounts.deletedAt])
    }

    @Test
    fun `DELETE auth account returns 404 when no active account`() = testApplication {
        TestDb.init(Accounts)
        application {
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(TokenHashKey, "nonexistent-hash")
            }
            routing { accountDeletionRoute() }
        }

        val resp = client.delete("/auth/account")

        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
