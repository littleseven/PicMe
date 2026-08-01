package com.mamba.picme.server.admin

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.AiEngineerWhitelists
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.ReportedIssues
import com.mamba.picme.server.issue.GitHubIssueClient
import com.mamba.picme.server.issue.IssueReportService
import com.mamba.picme.server.llm.ChannelBalanceService
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminDiagnosisRoutesTest {

    private val token = "test-admin-token"
    private val cos = CosService(AppConfig.load())
    private val balance = ChannelBalanceService(HttpClient(CIO))
    private val cookieVal get() = AdminAuth.expectedCookieValue(token)
    private val github = GitHubIssueClient(
        HttpClient(MockEngine { respond("""{"number":1,"html_url":"https://github.com/o/r/issues/1"}""", HttpStatusCode.Created) }),
        "tok",
        "o/r",
    )
    private val issueService = IssueReportService(github)

    private fun seed() {
        TestDb.init(AiEngineerWhitelists, ReportedIssues)
    }

    @Test
    fun `旧白名单路径 301 重定向到诊断页`() = testApplication {
        seed()
        application { routing { adminRoute(token, cos, balance, issueReportService = issueService) } }
        val c = createClient { followRedirects = false }
        val resp = c.get("/admin/ai-engineer-whitelist") { cookie(AdminAuth.COOKIE_NAME, cookieVal) }
        assertEquals(HttpStatusCode.MovedPermanently, resp.status)
        assertTrue(resp.headers[HttpHeaders.Location]?.contains("/admin/diagnosis?tab=whitelist") == true)
    }

    @Test
    fun `管理员可查看白名单 tab`() = testApplication {
        seed()
        application { routing { adminRoute(token, cos, balance, issueReportService = issueService) } }
        val resp = client.get("/admin/diagnosis?tab=whitelist") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val html = resp.bodyAsText()
        assertTrue(html.contains("AI 工程师白名单"))
        assertTrue(html.contains("/admin/diagnosis/whitelist"))
    }

    @Test
    fun `管理员可添加白名单邮箱`() = testApplication {
        seed()
        application { routing { adminRoute(token, cos, balance, issueReportService = issueService) } }
        val c = createClient { followRedirects = false }
        val resp = c.post("/admin/diagnosis/whitelist") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=admin%40x.com")
        }
        assertEquals(HttpStatusCode.Found, resp.status)
        assertTrue(resp.headers[HttpHeaders.Location]?.contains("/admin/diagnosis?tab=whitelist") == true)
    }

    @Test
    fun `管理员可查看用户上报问题 tab`() = testApplication {
        seed()
        runBlocking { issueService.submit(1, "u@x.com", "crash", "闪退", "打开相册闪退") }
        application { routing { adminRoute(token, cos, balance, issueReportService = issueService) } }
        val resp = client.get("/admin/diagnosis?tab=issues") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val html = resp.bodyAsText()
        assertTrue(html.contains("用户上报问题"))
        assertTrue(html.contains("闪退"))
    }

    @Test
    fun `管理员可更新问题状态`() = testApplication {
        seed()
        val id = runBlocking { issueService.submit(1, "u@x.com", "bug", "t", "d") }
        application { routing { adminRoute(token, cos, balance, issueReportService = issueService) } }
        val c = createClient { followRedirects = false }
        val resp = c.post("/admin/diagnosis/issues/$id/status") {
            cookie(AdminAuth.COOKIE_NAME, cookieVal)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("status=investigating")
        }
        assertEquals(HttpStatusCode.Found, resp.status)
        val row = transaction(Db.instance) {
            ReportedIssues.selectAll().where { ReportedIssues.id eq id }.firstOrNull()
        }
        assertEquals("investigating", row?.get(ReportedIssues.status))
    }
}
