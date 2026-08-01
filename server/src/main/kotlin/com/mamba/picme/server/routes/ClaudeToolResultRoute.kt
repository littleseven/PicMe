package com.mamba.picme.server.routes

import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** App tool 结果回传反代到网关 /tool-result（spec §5，与 /v1/claude-chat 同一 chisel 隧道口）。 */
private const val CLAUDE_TOOL_RESULT_UPSTREAM = "http://127.0.0.1:3001/tool-result"

/**
 * `POST /v1/claude-tool-result`：AppToken 鉴权 + 限流后反代到网关 `/tool-result`（JSON 透传）。
 * App 执行完本地 tool 后回传结果，网关注入回 agent 会话。
 */
fun Route.claudeToolResultRoute(httpClient: HttpClient, rateLimiter: RateLimiter?) {
    post("/v1/claude-tool-result") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        if (!call.requireAiEngineerWhitelist()) return@post
        if (rateLimiter != null && !rateLimiter.allow(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded")); return@post
        }
        val body = call.receiveText()
        val upstream = try {
            httpClient.post(CLAUDE_TOOL_RESULT_UPSTREAM) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Throwable) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "ai_offline", "message" to "tunnel unavailable"),
            )
            return@post
        }
        call.respondText(
            text = upstream.bodyAsText(),
            contentType = ContentType.Application.Json,
            status = upstream.status,
        )
    }
}
