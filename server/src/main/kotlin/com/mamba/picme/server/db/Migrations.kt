package com.mamba.picme.server.db

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

object Migrations {
    fun run() {
        transaction(Db.instance) {
            SchemaUtils.create(
                Rules, Assets, TelemetryEvents, LlmDailyCounters,
                Accounts, EmailVerifications,
            )
            seedRules()
        }
    }

    /**
     * 幂等加载初始推荐规则。seed_rules.sql 用 INSERT OR IGNORE，重复启动不会重复插入。
     * 若跳过此步，首次启动查不到任何规则，/recommend 必返回 404。
     */
    private fun Transaction.seedRules() {
        val sql = Migrations::class.java.getResource("/seed_rules.sql")?.readText() ?: return
        sql.lines()
            .map { it.substringBefore("--").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stmt -> exec(stmt) }
    }
}
