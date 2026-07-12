package com.mamba.picme.server.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

fun Routing.healthzRoute() {
    get("/healthz") {
        call.respond(
            mapOf(
                "status" to "ok",
                "service" to "picme-server",
                "version" to "0.3.0",
            ),
        )
    }
}
