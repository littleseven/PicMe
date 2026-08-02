package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.AiEngineerWhitelistService
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClaudeDeliverRoute 测试：AI 工程师白名单 / JSON 透传（错误场景见 [ClaudeRouteErrorTest]）。
 */
class ClaudeDeliverRouteTest {

    private val deliverBody = """{"ok":true,"branch":"claude-chat/s1"}"""

    @Test
    fun `未加入白名单返回 403 ai_engineer_not_allowed`() = testApplication {
        val upstream = okUpstream(deliverBody, "application/json")
        claudePipeline { claudeDeliverRoute(upstream, null) }
        val token = seedClaudeToken()
        val resp = client.post("/v1/claude-deliver") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"sid":"s1"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertTrue(resp.bodyAsText().contains("ai_engineer_not_allowed"))
    }

    @Test
    fun `deliver proxies json from upstream`() = testApplication {
        val upstream = okUpstream(deliverBody, "application/json")
        claudePipeline { claudeDeliverRoute(upstream, null) }
        val token = seedClaudeToken()
        runBlocking { AiEngineerWhitelistService.allow("u@x.com") }
        val resp = client.post("/v1/claude-deliver") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"sid":"s1","mode":"push"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(deliverBody, resp.bodyAsText())
    }
}
