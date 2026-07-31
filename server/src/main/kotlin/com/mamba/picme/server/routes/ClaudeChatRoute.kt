package com.mamba.picme.server.routes

import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully

/** 反代到本地 chisel 隧道口（Phase 1 的 KimiClaw Claude 网关）。 */
private const val CLAUDE_UPSTREAM = "http://127.0.0.1:3001/chat"

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

/** 取 owner tokenHash：优先全局拦截器写入的 TokenHashKey，否则兜底 validateToken（路由单测用）。 */
private suspend fun ApplicationCall.ownerTokenHash(): String? {
    attributes.getOrNull(TokenHashKey)?.let { return it }
    val raw = request.headers[APP_TOKEN_HEADER] ?: return null
    return AccountService.validateToken(raw).takeIf { it.valid }?.tokenHash
}
