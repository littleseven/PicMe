package com.mamba.picme.server.llm

import com.mamba.picme.server.analytics.Price
import com.mamba.picme.server.analytics.UsageRecorder
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.GuestService
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.routes.DeviceIdKey
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
    guestLlmQuota: Int,
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

            val tokenHash = call.attributes.getOrNull(TokenHashKey)
            val deviceId = call.attributes.getOrNull(DeviceIdKey)

            if (tokenHash == null && deviceId == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }

            val body = call.receive<JsonObject>()
            val requestedModel = (body["model"] as? JsonPrimitive)?.content ?: ""
            val isGuest = tokenHash == null
            val accountId = tokenHash?.let { AccountService.idForTokenHash(it) }

            // Quota check — account OR guest（各只增量一次）
            if (isGuest) {
                val guest = GuestService.checkAndIncrementQuota(deviceId!!, guestLlmQuota)
                if (!guest.allowed) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "quota_exceeded", "tier" to "guest", "message" to "guest quota used up"),
                    )
                    return@post
                }
            } else if (!AccountService.checkAndIncrementQuota(tokenHash)) {
                accountId?.let {
                    UsageRecorder.log(it, requestedModel, "", null, 0, "blocked_quota", null, prices)
                }
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "quota_exceeded", "tier" to "account", "message" to "free quota used up"),
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
                    if (isGuest) {
                        call.response.headers.append(
                            "X-Guest-Remaining",
                            GuestService.remainingReadOnly(deviceId!!, guestLlmQuota).toString(),
                        )
                    }
                    call.respondBytes(result.bytes, ContentType.Application.Json, result.status)
                }
                is ProxyResult.Error -> {
                    // LLM call failed — revert quota increment
                    if (isGuest) GuestService.revertQuota(deviceId!!) else AccountService.revertQuota(tokenHash)
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
