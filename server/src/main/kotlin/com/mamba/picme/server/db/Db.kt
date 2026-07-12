package com.mamba.picme.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database

object Db {
    lateinit var instance: Database
        private set

    fun init(path: String) {
        val cfg = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$path"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 4
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_UNCOMMITTED"
        }
        instance = Database.connect(HikariDataSource(cfg))
    }
}
