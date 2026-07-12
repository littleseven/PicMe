package com.mamba.picme.server.util

import com.mamba.picme.server.db.Db
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files

/**
 * 测试用临时 SQLite：每次 init 建独立临时文件并建表，避免测试间共享状态。
 * Db 是单例（多次 init 替换 instance）；Gradle 测试默认串行，安全。
 */
object TestDb {
    fun init(vararg tables: Table) {
        val file = Files.createTempFile("picme-test-", ".db").toAbsolutePath().toString()
        Db.init(file)
        transaction(Db.instance) {
            SchemaUtils.create(*tables)
        }
    }
}
