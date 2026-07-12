package com.mamba.picme.server.llm

import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.routes.TokenHashKey
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject

fun Route.llmRoute(
    proxy: LlmProxy,
    rateLimiter: RateLimiter?,
) {
    listOf("/v1/chat/completions", "/chat/completions").forEach { path ->
        post(path) {
            val clientIp = call.request.clientIp()

            if (rateLimiter != null && !rateLimiter.allow(clientIp)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded"))
                return@post
            }

            // Quota check
            val tokenHash = call.attributes[TokenHashKey]
            if (!AccountService.checkAndIncrementQuota(tokenHash)) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "quota_exceeded", "message" to "free quota used up"),
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
                    // LLM call failed — revert quota increment
                    AccountService.revertQuota(tokenHash)
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
