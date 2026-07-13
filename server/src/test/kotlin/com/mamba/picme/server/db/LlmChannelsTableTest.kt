package com.mamba.picme.server.db

import com.mamba.picme.server.util.TestDb
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmChannelsTableTest {
    @Test
    fun `insert and read a channel row`() {
        TestDb.init(LlmChannels)
        transaction(Db.instance) {
            LlmChannels.insert {
                it[name] = "TestChannel"
                it[kind] = "direct"
                it[baseUrl] = "https://example.com/chat"
                it[authStyle] = "bearer"
                it[apiToken] = "secret"
                it[modelMapJson] = """{"deepseek-chat":"glm-5.2"}"""
                it[enabled] = 1
                it[isActive] = 0
                it[createdAt] = 1_700_000_000_000L
                it[updatedAt] = 1_700_000_000_000L
            }
        }
        val row = transaction(Db.instance) { LlmChannels.selectAll().first() }
        assertEquals("TestChannel", row[LlmChannels.name])
        assertEquals("bearer", row[LlmChannels.authStyle])
        assertEquals(1, row[LlmChannels.enabled])
    }

    @Test
    fun `default_model column round-trips`() {
        TestDb.init(LlmChannels)
        transaction(Db.instance) {
            LlmChannels.insert {
                it[name] = "T"
                it[kind] = "direct"
                it[baseUrl] = "https://x"
                it[authStyle] = "bearer"
                it[apiToken] = "k"
                it[modelMapJson] = "{}"
                it[defaultModel] = "deepseek-v4-flash"
                it[enabled] = 1
                it[isActive] = 0
                it[createdAt] = 1L
                it[updatedAt] = 1L
            }
        }
        val row = transaction(Db.instance) { LlmChannels.selectAll().first() }
        assertEquals("deepseek-v4-flash", row[LlmChannels.defaultModel])
    }
}
