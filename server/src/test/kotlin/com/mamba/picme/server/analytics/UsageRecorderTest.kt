package com.mamba.picme.server.analytics

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageRecorderTest {

    @Test
    fun `log inserts a row with computed cost`() = runBlocking {
        TestDb.init(LlmCallLogs)
        val prices = mapOf("deepseek-chat" to Price(2.0, 8.0))
        UsageRecorder.log(
            accountId = 7,
            model = "deepseek-chat",
            provider = "CLOUDFLARE",
            usage = TokenUsage(500_000, 250_000, 750_000),
            respBytes = 1024,
            status = "ok",
            latencyMs = 300,
            prices = prices,
            now = 12345L,
        )
        transaction(Db.instance) {
            val row = LlmCallLogs.selectAll().single()
            assertEquals(7, row[LlmCallLogs.accountId])
            assertEquals("deepseek-chat", row[LlmCallLogs.model])
            assertEquals(750_000, row[LlmCallLogs.totalTokens])
            assertEquals(1024, row[LlmCallLogs.respBytes])
            assertEquals("ok", row[LlmCallLogs.status])
            assertEquals(12345L, row[LlmCallLogs.createdAt])
            assertEquals(300, row[LlmCallLogs.latencyMs])
            // 0.5M*2 + 0.25M*8 per million = 1.0 + 2.0 = 3.0
            assertEquals(3.0, row[LlmCallLogs.costCny], 0.000001)
        }
    }

    @Test
    fun `log blocked path records null usage and zero bytes`() = runBlocking {
        TestDb.init(LlmCallLogs)
        UsageRecorder.log(
            accountId = 1,
            model = "deepseek-chat",
            provider = "CLOUDFLARE",
            usage = null,
            respBytes = 0,
            status = "blocked_quota",
            latencyMs = null,
            prices = emptyMap(),
            now = 1L,
        )
        transaction(Db.instance) {
            val row = LlmCallLogs.selectAll().single()
            assertEquals(null, row[LlmCallLogs.promptTokens])
            assertEquals(0, row[LlmCallLogs.respBytes])
            assertEquals(0.0, row[LlmCallLogs.costCny], 0.0)
            assertEquals("blocked_quota", row[LlmCallLogs.status])
        }
    }

    @Test
    fun `log writes device id when provided and leaves null when absent`() = runBlocking {
        TestDb.init(LlmCallLogs)
        UsageRecorder.log(
            accountId = 1,
            model = "m",
            provider = "P",
            usage = null,
            respBytes = 0,
            status = "ok",
            latencyMs = null,
            prices = emptyMap(),
            deviceId = "device-aaaa-bbbb-1234",
            now = 1L,
        )
        val row = transaction(Db.instance) { LlmCallLogs.selectAll().single() }
        assertEquals("device-aaaa-bbbb-1234", row[LlmCallLogs.deviceId])

        // 默认 null(现有调用不传 deviceId)
        TestDb.init(LlmCallLogs)
        UsageRecorder.log(
            accountId = 1, model = "m", provider = "P", usage = null,
            respBytes = 0, status = "ok", latencyMs = null, prices = emptyMap(), now = 2L,
        )
        val row2 = transaction(Db.instance) { LlmCallLogs.selectAll().single() }
        assertEquals(null, row2[LlmCallLogs.deviceId])
    }
}
