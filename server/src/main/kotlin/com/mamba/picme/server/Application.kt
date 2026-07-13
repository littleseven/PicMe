package com.mamba.picme.server

import com.mamba.picme.server.admin.adminRoute
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.EmailService
import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.Migrations
import com.mamba.picme.server.llm.LlmProxy
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.llm.llmRoute
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.routes.TokenHashKey
import com.mamba.picme.server.routes.authRoute
import com.mamba.picme.server.routes.healthzRoute
import com.mamba.picme.server.routes.quotaRoute
import com.mamba.picme.server.routes.recommendRoute
import com.mamba.picme.server.routes.telemetryRoute
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("picme-server")

val appJson = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

fun main() {
    val config = AppConfig.load()
    Db.init(config.dbPath)
    Migrations.run(config)
    runBlocking { ChannelRegistry.reload() }
    embeddedServer(CIO, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

// Public routes that don't require token auth
private val publicRoutes = setOf("/healthz", "/auth/email/send", "/auth/email/verify")

fun Application.module(config: AppConfig) {
    install(CallLogging) { level = Level.INFO }
    install(DefaultHeaders)
    install(ContentNegotiation) { json(appJson) }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to "malformed request body"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "message" to (cause.message ?: "invalid argument")))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception in request", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error", "message" to (cause.message ?: "internal error")))
        }
    }

    // --- Auth interceptor ---
    intercept(ApplicationCallPipeline.Plugins) {
        val uri = call.request.local.uri.substringBefore("?")
        // /admin/** 由 admin 路由组自己的 cookie 拦截认证，不走 app-token
        if (uri in publicRoutes || uri == "/admin" || uri.startsWith("/admin/")) return@intercept

        val rawToken = call.request.headers[APP_TOKEN_HEADER]
        if (rawToken == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            finish()
            return@intercept
        }

        val authResult = AccountService.validateToken(rawToken)
        if (!authResult.valid) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            finish()
            return@intercept
        }

        // Store token hash for downstream quota checks
        authResult.tokenHash?.let { call.attributes.put(TokenHashKey, it) }
    }

    // --- HttpClient (shared by LLM proxy + email) ---
    val httpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        engine { requestTimeout = 60_000 }
    }

    val llmProxy = LlmProxy(
        httpClient = httpClient,
        cloudflareUrl = config.cloudflareAigUrl,
        cloudflareAigToken = config.cloudflareAigToken,
        tokenhubUrl = config.tokenhubUrl,
        tokenhubApiToken = config.tokenhubApiToken,
        forceProvider = config.forceProvider.takeIf { it.isNotBlank() },
        maxTokensCap = config.maxTokensCap,
    )
    val rateLimiter = if (config.rateLimitPerMin > 0) RateLimiter(config.rateLimitPerMin) else null
    val emailService = EmailService(httpClient, config.resendApiKey, config.emailFrom)

    routing {
        // Public
        healthzRoute()
        authRoute(emailService, config.freeLlmQuota)

        // Protected (auth interceptor above enforces token)
        recommendRoute(appJson)
        telemetryRoute()
        quotaRoute()
        llmRoute(llmProxy, rateLimiter, config.llmPrices)
        // 管理后台（/admin/**，独立 cookie 认证）
        adminRoute(config.adminToken)
    }
}
