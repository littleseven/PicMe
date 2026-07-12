package com.mamba.picme.server

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.Migrations
import com.mamba.picme.server.routes.healthzRoute
import com.mamba.picme.server.routes.recommendRoute
import com.mamba.picme.server.routes.telemetryRoute
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
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
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("picme-server")

val appJson = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

fun main() {
    val config = AppConfig.load()
    Db.init(config.dbPath)
    Migrations.run()
    embeddedServer(CIO, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig) {
    install(CallLogging) { level = Level.INFO }
    install(DefaultHeaders)
    install(ContentNegotiation) { json(appJson) }
    install(StatusPages) {
        // 顺序敏感：具体异常先注册，Throwable 兜底放最后。统一返回 {error, message}，不泄露堆栈。
        // Ktor 3 handler 形式为 suspend (call, cause) -> Unit。
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "bad_request", "message" to "malformed request body"),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "bad_request", "message" to (cause.message ?: "invalid argument")),
            )
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception in request", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "internal_error", "message" to (cause.message ?: "internal error")),
            )
        }
    }
    routing {
        healthzRoute()
        recommendRoute(appJson)
        telemetryRoute()
    }
}
