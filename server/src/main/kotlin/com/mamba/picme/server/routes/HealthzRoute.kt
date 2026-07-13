package com.mamba.picme.server.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

private val APP_VERSION: String =
    object {}.javaClass.`package`?.implementationVersion ?: "dev"

fun Routing.healthzRoute() {
    get("/healthz") {
        call.respond(
            mapOf(
                "status" to "ok",
                "service" to "picme-server",
                "version" to APP_VERSION,
            ),
        )
    }
}

