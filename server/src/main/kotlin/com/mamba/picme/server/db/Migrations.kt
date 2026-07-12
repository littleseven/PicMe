package com.mamba.picme.server.db

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object Migrations {
    fun run() {
        transaction(Db.instance) {
            SchemaUtils.create(Rules, Assets, TelemetryEvents, LlmDailyCounters)
        }
    }
}
