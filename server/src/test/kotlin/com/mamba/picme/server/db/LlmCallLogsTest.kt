package com.mamba.picme.server.db

import com.mamba.picme.server.util.TestDb
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmCallLogsTest {

    @Test
    fun `insert and read a log row`() {
        TestDb.init(LlmCallLogs)
        transaction(Db.instance) {
            LlmCallLogs.insert {
                it[accountId] = 1
                it[model] = "deepseek-chat"
                it[provider] = "CLOUDFLARE"
                it[promptTokens] = 100
                it[completionTokens] = 50
                it[totalTokens] = 150
                it[costCny] = 0.001
                it[respBytes] = 2048
                it[status] = "ok"
                it[latencyMs] = 320
                it[createdAt] = 1_700_000_000_000L
            }
            // 读取需在事务内：autoIncrement 列取值会触发 dialect 查询
            val row = LlmCallLogs.selectAll().single()
            assertEquals(1, row[LlmCallLogs.accountId])
            assertEquals("deepseek-chat", row[LlmCallLogs.model])
            assertEquals(150, row[LlmCallLogs.totalTokens])
            assertEquals("ok", row[LlmCallLogs.status])
            assertEquals(2048, row[LlmCallLogs.respBytes])
            assertEquals(1L, row[LlmCallLogs.id])
        }
    }

    @Test
    fun `token columns nullable and defaults applied`() {
        TestDb.init(LlmCallLogs)
        transaction(Db.instance) {
            LlmCallLogs.insert {
                it[accountId] = 2
                it[model] = "deepseek-chat"
                it[provider] = "CLOUDFLARE"
                it[status] = "upstream_error"
                it[createdAt] = 1_700_000_000_000L
            }
            val row = LlmCallLogs.selectAll().single()
            assertNull(row[LlmCallLogs.promptTokens])
            assertEquals(0, row[LlmCallLogs.respBytes]) // default
            assertEquals(0.0, row[LlmCallLogs.costCny], 0.0) // default
        }
    }
}
