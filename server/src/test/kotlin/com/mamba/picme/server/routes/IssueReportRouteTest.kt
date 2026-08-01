package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.ReportedIssues
import com.mamba.picme.server.issue.GitHubIssueClient
import com.mamba.picme.server.issue.IssueReportService
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.util.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportRouteTest {

    private val github = GitHubIssueClient(
        HttpClient(MockEngine {
            respond("""{"number":1,"html_url":"https://github.com/o/r/issues/1"}""", HttpStatusCode.Created)
        }),
        "tok",
        "o/r",
    )
    private val service = IssueReportService(github)
    private val rateLimiter = RateLimiter(10, 24 * 60 * 60_000L)

    private fun TestApplicationBuilder.app() {
        TestDb.init(Accounts, ReportedIssues)
        application {
            install(ContentNegotiation) { json(appJson) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers["X-App-Token"]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { issueReportRoute(service, rateLimiter) }
        }
    }

    private fun seedToken(email: String = "u@x.com"): String = runBlocking {
        AccountService.createOrRefresh(email, 100).token
    }

    @Test
    fun `无 token 返回 401`() = testApplication {
        app()
        val resp = client.post("/v1/report-issue") {
            contentType(ContentType.Application.Json)
            setBody("""{"title":"crash"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `提交成功返回 issueId`() = testApplication {
        app()
        val token = seedToken()
        val resp = client.post("/v1/report-issue") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"category":"crash","title":"闪退","description":"打开相册就闪退"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("true", json["ok"]?.jsonPrimitive?.content)
        assertTrue(json["issueId"]?.jsonPrimitive?.content?.toIntOrNull() != null)
    }

    @Test
    fun `缺标题返回 400`() = testApplication {
        app()
        val token = seedToken()
        val resp = client.post("/v1/report-issue") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"description":"只有描述"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `超限返回 429`() = testApplication {
        app()
        val token = seedToken()
        val body = """{"title":"t","description":"d"}"""
        repeat(10) {
            client.post("/v1/report-issue") {
                header(APP_TOKEN_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val over = client.post("/v1/report-issue") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.TooManyRequests, over.status)
    }
}
