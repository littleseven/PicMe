package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ClaudeChatRoute 测试：SSE 透传（错误场景见 [ClaudeRouteErrorTest]）。
 * 诊断对话对所有已认证账号开放，不校验白名单。
 */
class ClaudeChatRouteTest {

    private val sseBody =
        "event: session\ndata: {\"sid\":\"s1\"}\n\n" +
            "event: assistant_text\ndata: {\"delta\":\"hi\"}\n\n" +
            "event: done\ndata: {}\n\n"

    @Test
    fun `已认证用户未在白名单仍可进入诊断对话`() = testApplication {
        val upstream = okUpstream(sseBody, "text/event-stream")
        claudePipeline { claudeChatRoute(upstream, null) }
        val token = seedClaudeToken()
        val resp = client.post("/v1/claude-chat") {
            header(APP_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(sseBody, resp.bodyAsText())
    }
}
