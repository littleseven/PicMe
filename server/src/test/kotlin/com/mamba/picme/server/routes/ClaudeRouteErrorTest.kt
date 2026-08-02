package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.AiEngineerWhitelistService
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 路由规格：路径 / 请求体 / 是否需要白名单 / 路由安装函数。 */
internal class ClaudeRouteSpec(
    val name: String,
    val path: String,
    val body: String,
    val needsWhitelist: Boolean,
    val install: (Routing, HttpClient, RateLimiter?) -> Unit,
) {
    override fun toString() = name
}

/**
 * 三个 Claude 路由（chat / tool-result / deliver）的错误场景参数化测试。
 * 每条 @Test 在 3 个路由上各运行一次，替代原来分散在各路由测试类中的 9 条重复用例。
 */
@RunWith(Parameterized::class)
internal class ClaudeRouteErrorTest(private val spec: ClaudeRouteSpec) {

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<out Any>> = listOf(
            arrayOf(
                ClaudeRouteSpec("chat", "/v1/claude-chat", """{"message":"hi"}""", false) { r, u, l ->
                    r.claudeChatRoute(u, l)
                },
            ),
            arrayOf(
                ClaudeRouteSpec("tool-result", "/v1/claude-tool-result", """{"toolCallId":"t1","result":"ok"}""", false) { r, u, l ->
                    r.claudeToolResultRoute(u, l)
                },
            ),
            arrayOf(
                ClaudeRouteSpec("deliver", "/v1/claude-deliver", """{"sid":"s1"}""", true) { r, u, l ->
                    r.claudeDeliverRoute(u, l)
                },
            ),
        )
    }

    private val okUpstream = okUpstream("{}", "application/json")

    @Test
    fun `no token returns 401`() = testApplication {
        claudePipeline { spec.install(this, okUpstream, null) }
        val resp = client.post(spec.path) {
            contentType(ContentType.Application.Json)
            setBody(spec.body)
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `upstream failure returns 503 ai_offline`() = testApplication {
        claudePipeline { spec.install(this, badUpstream(), null) }
        val token = seedClaudeToken()
        if (spec.needsWhitelist) runBlocking { AiEngineerWhitelistService.allow("u@x.com") }
        val resp = client.post(spec.path) {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(spec.body)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        assertTrue(resp.bodyAsText().contains("ai_offline"))
    }

    @Test
    fun `rate limit returns 429`() = testApplication {
        claudePipeline { spec.install(this, okUpstream, RateLimiter(1, 60_000L)) }
        val token = seedClaudeToken()
        if (spec.needsWhitelist) runBlocking { AiEngineerWhitelistService.allow("u@x.com") }
        client.post(spec.path) {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(spec.body)
        }
        val second = client.post(spec.path) {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(spec.body)
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }
}
