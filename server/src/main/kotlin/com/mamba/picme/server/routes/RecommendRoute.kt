package com.mamba.picme.server.routes

import com.mamba.picme.server.recommend.RecommendRequest
import com.mamba.picme.server.recommend.RuleEngine
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

fun Routing.recommendRoute(json: Json) {
    val engine = RuleEngine(json)
    post("/recommend") {
        val req = call.receive<RecommendRequest>()
        val result = engine.recommend(req)
        if (result == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no_rule"))
        } else {
            call.respond(result)
        }
    }
}
