package com.mamba.picme.server.routes

import com.mamba.picme.server.appJson
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.db.Accounts
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
 * ClaudeChatRoute 测试：MockEngine 模拟 upstream（127.0.0.1:3001）的 SSE / 连接失败，
 * 最小 intercept 复刻 token→TokenHashKey，断言鉴权 / 健康推断 / SSE 透传 / 限流。
 */
class ClaudeChatRouteTest {

    private val sseBody =
        "event: session\ndata: {\"sid\":\"s1\"}\n\n" +
            "event: assistant_text\ndata: {\"delta\":\"hi\"}\n\n" +
            "event: done\ndata: {}\n\n"

    private fun TestApplicationBuilder.claudeApp(upstream: HttpClient, limiter: RateLimiter? = null) {
        TestDb.init(Accounts)
        application {
            install(ContentNegotiation) { json(appJson) }
            intercept(ApplicationCallPipeline.Plugins) {
                call.request.headers[APP_TOKEN_HEADER]
                    ?.let { AccountService.sha256(it) }
                    ?.let { call.attributes.put(TokenHashKey, it) }
            }
            routing { claudeChatRoute(upstream, limiter) }
        }
    }

    private fun okUpstream() = HttpClient(
        MockEngine { respond(sseBody, HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream")) },
    )

    @Test
    fun `无 token 返回 401`() = testApplication {
        claudeApp(okUpstream())
        val resp = client.post("/v1/claude-chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `upstream 连接失败返回 503 ai_offline`() = testApplication {
        val badUpstream = HttpClient(MockEngine { throw java.net.ConnectException("refused") })
        claudeApp(badUpstream)
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        val resp = client.post("/v1/claude-chat") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        assertTrue(resp.bodyAsText().contains("ai_offline"))
    }

    @Test
    fun `stream request proxies SSE from upstream`() = testApplication {
        claudeApp(okUpstream())
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        val resp = client.post("/v1/claude-chat") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(sseBody, resp.bodyAsText())
    }

    @Test
    fun `rate limit returns 429`() = testApplication {
        claudeApp(okUpstream(), RateLimiter(1, 60_000L))
        val token = runBlocking { AccountService.createOrRefresh("u@x.com", 100).token }
        client.post("/v1/claude-chat") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }
        val second = client.post("/v1/claude-chat") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }
}
