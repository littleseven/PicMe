package com.mamba.picme.server.admin

import com.mamba.picme.server.auth.AiEngineerWhitelistService
import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.AiEngineerWhitelists
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.llm.ChannelBalanceService
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAiEngineerWhitelistRoutesTest {

    private val token = "test-admin-token"
    private val cos = CosService(AppConfig.load())
    private val balance = ChannelBalanceService(HttpClient(CIO))
    private val cookieVal get() = AdminAuth.expectedCookieValue(token)

    @Test
    fun `未登录访问白名单页被重定向到登录`() = testApplication {
        TestDb.init(AiEngineerWhitelists)
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        val resp = c.get("/admin/ai-engineer-whitelist")
        assertEquals(HttpStatusCode.Found, resp.status)
        assertEquals("/admin/login", resp.headers[HttpHeaders.Location])
    }

    @Test
    fun `管理员可查看空白名单页`() = testApplication {
        TestDb.init(AiEngineerWhitelists)
        application { routing { adminRoute(token, cos, balance) } }
        val resp = client.get("/admin/ai-engineer-whitelist") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val html = resp.bodyAsText()
        assertTrue(html.contains("AI 工程师模式白名单"))
        assertTrue(html.contains("暂无白名单记录"))
    }

    @Test
    fun `管理员可添加邮箱到白名单`() = testApplication {
        TestDb.init(AiEngineerWhitelists)
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        val resp = c.post("/admin/ai-engineer-whitelist") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=admin%40x.com")
        }
        assertEquals(HttpStatusCode.Found, resp.status)
        assertTrue(resp.headers[HttpHeaders.Location]?.contains("/admin/ai-engineer-whitelist") == true)
        val allowed = runBlocking { AiEngineerWhitelistService.isAllowed("admin@x.com") }
        assertTrue(allowed)
    }

    @Test
    fun `管理员可移除白名单邮箱`() = testApplication {
        TestDb.init(AiEngineerWhitelists)
        runBlocking { AiEngineerWhitelistService.allow("admin@x.com") }
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        val resp = c.post("/admin/ai-engineer-whitelist/revoke") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=admin%40x.com")
        }
        assertEquals(HttpStatusCode.Found, resp.status)
        val allowed = runBlocking { AiEngineerWhitelistService.isAllowed("admin@x.com") }
        assertEquals(false, allowed)
    }

    @Test
    fun `空邮箱添加会重定向并提示错误`() = testApplication {
        TestDb.init(AiEngineerWhitelists)
        application { routing { adminRoute(token, cos, balance) } }
        val c = createClient { followRedirects = false }
        val resp = c.post("/admin/ai-engineer-whitelist") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=")
        }
        assertEquals(HttpStatusCode.Found, resp.status)
        assertTrue(resp.headers[HttpHeaders.Location]?.contains("%E8%AF%B7%E8%BE%93%E5%85%A5%E6%9C%89%E6%95%88%E9%82%AE%E7%AE%B1") == true)
    }
}
