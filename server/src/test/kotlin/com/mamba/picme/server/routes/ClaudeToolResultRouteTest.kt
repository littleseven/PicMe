package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.AiEngineerWhitelistService
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.AiEngineerWhitelists
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
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClaudeToolResultRoute 测试：MockEngine 模拟网关 /tool-result 的 JSON / 连接失败，
 * 断言鉴权 / JSON 透传 / 限流。tool-result 是诊断只读链路，不校验白名单。
 */
class ClaudeToolResultRouteTest {

    private val upstreamBody = """{"ok":true}"""

    private fun TestApplicationBuilder.claudeApp(upstream: HttpClient, limiter: RateLimiter? = null) {
        TestDb.init(Accounts, AiEngineerWhitelists)
        application {
            install(ContentNegotiation) { json(appJson) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { claudeToolResultRoute(upstream, limiter) }
        }
    }

    private fun okUpstream() = HttpClient(
        MockEngine { respond(upstreamBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) },
    )

    private fun seedToken(email: String = "u@x.com"): String = runBlocking {
        AccountService.createOrRefresh(email, 100).token
    }

    @Test
    fun `无 token 返回 401`() = testApplication {
        claudeApp(okUpstream())
        val resp = client.post("/v1/claude-tool-result") {
            contentType(ContentType.Application.Json)
            setBody("""{"toolCallId":"t1","result":"ok"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `已认证用户未在白名单仍可回传 tool 结果`() = testApplication {
        claudeApp(okUpstream())
        val token = seedToken()
        val resp = client.post("/v1/claude-tool-result") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"toolCallId":"t1","result":"ok"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(upstreamBody, resp.bodyAsText())
    }

    @Test
    fun `upstream 连接失败返回 503 ai_offline`() = testApplication {
        val badUpstream = HttpClient(MockEngine { throw java.net.ConnectException("refused") })
        claudeApp(badUpstream)
        val token = seedToken()
        val resp = client.post("/v1/claude-tool-result") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"toolCallId":"t1","result":"ok"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        assertTrue(resp.bodyAsText().contains("ai_offline"))
    }

    @Test
    fun `tool-result proxies json from upstream`() = testApplication {
        claudeApp(okUpstream())
        val token = seedToken()
        val resp = client.post("/v1/claude-tool-result") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"toolCallId":"t1","result":"42 photos"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(upstreamBody, resp.bodyAsText())
    }

    @Test
    fun `rate limit returns 429`() = testApplication {
        claudeApp(okUpstream(), RateLimiter(1, 60_000L))
        val token = seedToken()
        client.post("/v1/claude-tool-result") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"toolCallId":"t1"}""")
        }
        val second = client.post("/v1/claude-tool-result") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"toolCallId":"t1"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }
}
