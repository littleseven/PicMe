package com.mamba.picme.server.llm

import com.mamba.picme.server.ratelimit.RateLimiter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.request.ApplicationRequest
import kotlinx.serialization.json.JsonObject

fun Route.llmRoute(
    proxy: LlmProxy,
    rateLimiter: RateLimiter?,
) {
    // Same paths as SCF — new clients use /v1/chat/completions, legacy /chat/completions kept for compat
    listOf("/v1/chat/completions", "/chat/completions").forEach { path ->
        post(path) {
            val clientIp = call.request.clientIp()

            // rate limit
            if (rateLimiter != null && !rateLimiter.allow(clientIp)) {
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    mapOf("error" to "rate_limit_exceeded"),
                )
                return@post
            }

            val body = call.receive<JsonObject>()
            val result = proxy.forward(clientIp, body)
            when (result) {
                is ProxyResult.Success -> {
                    call.respondBytes(result.bytes, ContentType.Application.Json, result.status)
                }
                is ProxyResult.Error -> {
                    call.respond(result.status, result.body)
                }
            }
        }
    }
}

private fun ApplicationRequest.clientIp(): String =
    headers["X-Forwarded-For"]?.substringBefore(",")
        ?: headers["X-Real-IP"]
        ?: "unknown"
