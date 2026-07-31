package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully

/** 反代到本地 chisel 隧道口（Phase 1 的 KimiClaw Claude 网关）。 */
private const val CLAUDE_UPSTREAM = "http://127.0.0.1:3001/chat"

/** 交付动作反代到网关 /deliver（spec §8：commit + push claude-chat/<sid>）。 */
private const val CLAUDE_DELIVER_UPSTREAM = "http://127.0.0.1:3001/deliver"

fun Route.claudeChatRoute(httpClient: HttpClient, rateLimiter: RateLimiter?) {
    post("/v1/claude-chat") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        if (rateLimiter != null && !rateLimiter.allow(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded")); return@post
        }
        val body = call.receiveText()
        val upstream = try {
            httpClient.post(CLAUDE_UPSTREAM) {
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
        call.respondBytesWriter(ContentType.Text.EventStream, upstream.status) {
            val ch = upstream.bodyAsChannel()
            val buf = ByteArray(8 * 1024)
            try {
                while (!ch.isClosedForRead) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    writeFully(buf, 0, n)
                    flush()
                }
            } catch (e: Throwable) {
                ch.cancel(e); throw e
            }
        }
    }
}

/**
 * `POST /v1/claude-deliver`：AppToken 鉴权 + 限流后反代到网关 `/deliver`（JSON 透传）。
 * 网关 MVP 仅 push：workdir commit + push `claude-chat/<sid>`，返回 {ok, branch}。
 */
fun Route.claudeDeliverRoute(httpClient: HttpClient, rateLimiter: RateLimiter?) {
    post("/v1/claude-deliver") {
        val owner = call.ownerTokenHash() ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized")); return@post
        }
        if (rateLimiter != null && !rateLimiter.allow(owner)) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded")); return@post
        }
        val body = call.receiveText()
        val upstream = try {
            httpClient.post(CLAUDE_DELIVER_UPSTREAM) {
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

/** 取 owner tokenHash：优先全局拦截器写入的 TokenHashKey，否则兜底 validateToken（路由单测用）。 */
private suspend fun ApplicationCall.ownerTokenHash(): String? {
    attributes.getOrNull(TokenHashKey)?.let { return it }
    val raw = request.headers[APP_TOKEN_HEADER] ?: return null
    return AccountService.validateToken(raw).takeIf { it.valid }?.tokenHash
}
