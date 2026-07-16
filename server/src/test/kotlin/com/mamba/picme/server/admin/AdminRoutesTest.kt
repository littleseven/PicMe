package com.mamba.picme.server.admin

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.util.TestDb
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminRoutesTest {

    private val token = "test-admin-token"
    private val cos = CosService(AppConfig.load())
    private val cookieVal get() = AdminAuth.expectedCookieValue(token)

    private fun seed() {
        TestDb.init(Accounts, LlmCallLogs)
        transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.id] = 1
                it[Accounts.email] = "a@x.com"
                it[Accounts.tokenHash] = "h1"
                it[Accounts.status] = "active"
                it[Accounts.llmCallsUsed] = 0
                it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1_700_000_000_000L
            }
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = 1
                it[LlmCallLogs.model] = "deepseek-chat"
                it[LlmCallLogs.provider] = "CLOUDFLARE"
                it[LlmCallLogs.promptTokens] = 10
                it[LlmCallLogs.completionTokens] = 5
                it[LlmCallLogs.totalTokens] = 15
                it[LlmCallLogs.costCny] = 0.2
                it[LlmCallLogs.respBytes] = 100
                it[LlmCallLogs.status] = "ok"
                it[LlmCallLogs.createdAt] = 1_700_000_001_000L
            }
        }
    }

    @Test
    fun `full admin auth and view flow`() = testApplication {
        seed()
        application { routing { adminRoute(token, cos) } }
        val c = createClient { followRedirects = false }

        // 1. no cookie → redirect to login
        val r1 = c.get("/admin")
        assertEquals(HttpStatusCode.Found, r1.status)
        assertEquals("/admin/login", r1.headers[HttpHeaders.Location])

        // 2. login page reachable
        val r2 = c.get("/admin/login")
        assertEquals(HttpStatusCode.OK, r2.status)
        assertTrue(r2.bodyAsText().contains("PoLang 管理后台"))

        // 3. wrong password → 401
        val r3 = c.post("/admin/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=wrong")
        }
        assertEquals(HttpStatusCode.Unauthorized, r3.status)

        // 4. correct password → 302 redirect to /admin + 安全 cookie 标志
        val r4 = c.post("/admin/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=$token")
        }
        assertEquals(HttpStatusCode.Found, r4.status)
        val setCookie = r4.headers[HttpHeaders.SetCookie]
        assertTrue("cookie 应含 HttpOnly", setCookie?.contains("HttpOnly") == true)
        assertTrue("cookie 应含 SameSite=Lax", setCookie?.contains("SameSite=Lax") == true)

        // 5. valid cookie → overview 200
        val r5 = c.get("/admin") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r5.status)
        assertTrue(r5.bodyAsText().contains("概览"))

        // 6. users page lists the seeded email
        val r6 = c.get("/admin/users") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r6.status)
        assertTrue(r6.bodyAsText().contains("a@x.com"))

        // 7. user detail
        val r7 = c.get("/admin/users/1") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r7.status)
        assertTrue(r7.bodyAsText().contains("deepseek-chat"))

        // 8. unknown user → 404
        val r8 = c.get("/admin/users/999") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.NotFound, r8.status)

        // 9. traffic page
        val r9 = c.get("/admin/traffic") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.OK, r9.status)
        assertTrue(r9.bodyAsText().contains("Total Token"))
    }

    @Test
    fun `disabled admin token returns 503`() = testApplication {
        seed()
        application { routing { adminRoute("", cos) } } // 空 token → 禁用
        val c = createClient { followRedirects = false }
        val r = c.get("/admin") { cookie(AdminAuth.COOKIE_NAME, "anything") }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
    }
}
