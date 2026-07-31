package com.mamba.picme.server

import com.mamba.picme.server.admin.adminRoute
import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.APP_TOKEN_HEADER
import com.mamba.picme.server.auth.DEVICE_ID_HEADER
import com.mamba.picme.server.auth.EmailService
import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.Migrations
import com.mamba.picme.server.diag.DiagService
import com.mamba.picme.server.llm.LlmProxy
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.llm.ChannelBalanceService
import com.mamba.picme.server.llm.llmRoute
import com.mamba.picme.server.ratelimit.RateLimiter
import com.mamba.picme.server.routes.DeviceIdKey
import com.mamba.picme.server.routes.TokenHashKey
import com.mamba.picme.server.routes.accountDeletionRoute
import com.mamba.picme.server.routes.guestDeletionRoute
import com.mamba.picme.server.routes.authRoute
import com.mamba.picme.server.routes.diagRoute
import com.mamba.picme.server.routes.downloadRoute
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    runBlocking { SettingsService.load() }
    runBlocking {
        val purged = AccountService.purgeExpiredDeleted(AccountService.RETENTION_MS)
        logger.info("Purged $purged expired deleted accounts (retention=${AccountService.RETENTION_MS}ms)")
    }
    // S4：worker token 未配置时打 WARN（消除静默 401）
    if (config.diagWorkerToken.isBlank()) {
        logger.warn("DIAG_WORKER_TOKEN 未配置：/diag/work/** 端点已禁用（worker 请求一律 401）")
    }
    // S1：diag 任务回收 sweeper —— 每 5 分钟扫一次（领取回收 15min + 整体超时 1h → TIMED_OUT）
    val diagSweepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    diagSweepScope.launch {
        while (true) {
            delay(5 * 60_000L)
            runCatching {
                val (reclaimed, timedOut) = DiagService.sweepStaleJobs(System.currentTimeMillis())
                if (reclaimed > 0 || timedOut > 0) {
                    logger.info("diag sweeper: reclaimed=$reclaimed timedOut=$timedOut")
                }
            }.onFailure { logger.warn("diag sweeper failed", it) }
        }
    }
    embeddedServer(CIO, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

// Public routes that don't require token auth
private val publicRoutes = setOf("/healthz", "/auth/email/send", "/auth/email/verify", "/download", "/guest/device")

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
        if (uri in publicRoutes || uri == "/admin" || uri.startsWith("/admin/") ||
            uri.startsWith("/diag/work")
        ) return@intercept

        val rawToken = call.request.headers[APP_TOKEN_HEADER]
        val authResult = rawToken?.let { AccountService.validateToken(it) }
        if (authResult?.valid == true) {
            // 有效账号 token → 存 hash 供下游额度校验
            authResult.tokenHash?.let { call.attributes.put(TokenHashKey, it) }
            // 注册用户请求若带 X-Device-Id,亦存 DeviceIdKey 供后台 device 维度展示(device_id 列)
            call.request.headers[DEVICE_ID_HEADER]?.takeIf { it.isNotBlank() }?.let {
                call.attributes.put(DeviceIdKey, it)
            }
            return@intercept
        }

        // 无有效账号 token → 仅在 LLM 代理路径上允许设备级访客试用
        val isLlmPath = uri == "/chat/completions" || uri == "/v1/chat/completions"
        val deviceId = call.request.headers[DEVICE_ID_HEADER]
        if (isLlmPath && !deviceId.isNullOrBlank()) {
            call.attributes.put(DeviceIdKey, deviceId)
            return@intercept
        }

        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
        finish()
        return@intercept
    }

    // --- HttpClient (shared by LLM proxy + email) ---
    val httpClient = HttpClient(io.ktor.client.engine.cio.CIO) {
        engine { requestTimeout = 60_000 }
    }

    val llmProxy = LlmProxy(
        httpClient = httpClient,
        maxTokensCap = config.maxTokensCap,
    )
    val rateLimiter = if (config.rateLimitPerMin > 0) RateLimiter(config.rateLimitPerMin) else null
    val emailService = EmailService(httpClient, config.resendApiKey, config.emailFrom)

    val cosService = CosService(config)
    val balanceService = ChannelBalanceService(httpClient)

    routing {
        // Public
        downloadRoute(cosService)
        healthzRoute()
        authRoute(emailService)

        // Protected (auth interceptor above enforces token)
        recommendRoute(appJson)
        telemetryRoute()
        quotaRoute()
        accountDeletionRoute()
        guestDeletionRoute()
        llmRoute(llmProxy, rateLimiter, config.llmPrices)
        diagRoute(config.diagWorkerToken)
        // 管理后台（/admin/**，独立 cookie 认证）
        adminRoute(config.adminToken, cosService, balanceService, config.llmPrices)
    }
}
