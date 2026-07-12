package com.mamba.picme.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import org.jetbrains.exposed.sql.Database

object Db {
    lateinit var instance: Database
        private set

    fun init(path: String) {
        val url = "jdbc:sqlite:$path"
        // SQLite 单写者模型：连接池降到 1 即可，多连接只增锁冲突。
        // WAL 让读写不互斥；busy_timeout 让锁等待在超时内重试而非立即抛异常。
        enableWal(url)
        val cfg = HikariConfig().apply {
            jdbcUrl = url
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_UNCOMMITTED"
            // 连接级 pragma，每个连接进入池时执行一次（幂等）
            connectionInitSql = "PRAGMA busy_timeout=5000"
        }
        instance = Database.connect(HikariDataSource(cfg))
    }

    private fun enableWal(jdbcUrl: String) {
        // journal_mode 是数据库级持久属性，设一次即可（幂等，重启不丢）。
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("PRAGMA journal_mode=WAL") }
        }
    }
}
