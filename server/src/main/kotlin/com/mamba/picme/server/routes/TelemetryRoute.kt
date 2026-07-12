package com.mamba.picme.server.routes

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.TelemetryEvents
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class TelemetryBatch(val events: List<TelemetryRecord>)

@Serializable
data class TelemetryRecord(val type: String, val payload: JsonObject? = null)

fun Routing.telemetryRoute() {
    post("/telemetry") {
        val batch = call.receive<TelemetryBatch>()
        val now = System.currentTimeMillis()
        transaction(Db.instance) {
            batch.events.forEach { ev ->
                TelemetryEvents.insert {
                    it[TelemetryEvents.type] = ev.type
                    it[TelemetryEvents.payloadJson] = ev.payload?.toString()
                    it[TelemetryEvents.createdAt] = now
                }
            }
        }
        call.respond(mapOf("accepted" to batch.events.size))
    }
}
