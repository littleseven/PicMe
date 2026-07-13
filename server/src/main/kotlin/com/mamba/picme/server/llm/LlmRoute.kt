package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.Price
import com.mamba.picme.server.analytics.UsageRecorder
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
import kotlinx.serialization.json.JsonPrimitive

fun Route.llmRoute(
    proxy: LlmProxy,
    rateLimiter: RateLimiter?,
    prices: Map<String, Price>,
) {
    listOf("/v1/chat/completions", "/chat/completions").forEach { path ->
        post(path) {
            val clientIp = call.request.clientIp()

            // 限流优先：命中限流前不做任何 DB / body 解析，避免洪水请求打满单连接 SQLite
            // （HikariCP maximumPoolSize=1）。命中限流直接 429，不写 llm_call_log——
            // 限流命中数见服务日志（CallLogging）；后台「blocked」统计仍含 blocked_quota。
            if (rateLimiter != null && !rateLimiter.allow(clientIp)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limit_exceeded"))
                return@post
            }

            val tokenHash = call.attributes[TokenHashKey]
            // auth 拦截器已保证 tokenHash 有效 → accountId 必非空；防御性 ?: 处理。
            val accountId = AccountService.idForTokenHash(tokenHash)
            val body = call.receive<JsonObject>()
            val requestedModel = (body["model"] as? JsonPrimitive)?.content ?: ""

            // Quota check
            if (!AccountService.checkAndIncrementQuota(tokenHash)) {
                accountId?.let {
                    UsageRecorder.log(it, requestedModel, "", null, 0, "blocked_quota", null, prices)
                }
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "quota_exceeded", "message" to "free quota used up"),
                )
                return@post
            }

            val started = System.currentTimeMillis()
            val result = proxy.forward(clientIp, body)
            val latencyMs = (System.currentTimeMillis() - started).toInt()
            when (result) {
                is ProxyResult.Success -> {
                    accountId?.let {
                        UsageRecorder.log(
                            accountId = it,
                            model = result.model,
                            provider = result.provider,
                            usage = result.usage,
                            respBytes = result.bytes.size,
                            status = "ok",
                            latencyMs = latencyMs,
                            prices = prices,
                        )
                    }
                    call.respondBytes(result.bytes, ContentType.Application.Json, result.status)
                }
                is ProxyResult.Error -> {
                    // LLM call failed — revert quota increment
                    AccountService.revertQuota(tokenHash)
                    accountId?.let {
                        UsageRecorder.log(it, requestedModel, "", null, 0, result.logStatus, null, prices)
                    }
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
