package com.mamba.picme.server

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.Migrations
import com.mamba.picme.server.routes.healthzRoute
import com.mamba.picme.server.routes.recommendRoute
import com.mamba.picme.server.routes.telemetryRoute
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

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
    routing {
        healthzRoute()
        recommendRoute(appJson)
        telemetryRoute()
    }
}
