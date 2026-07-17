package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assert.assertEquals
import org.junit.Test

class PurgeExpiredDeletedTest {

    @Test
    fun `purges expired deleted accounts and their call logs`() = runBlocking {
        TestDb.init(Accounts, LlmCallLogs)
        val info = AccountService.createOrRefresh("old@example.com", 100)
        val id = AccountService.idForTokenHash(AccountService.sha256(info.token))!!
        AccountService.softDelete(AccountService.sha256(info.token))
        val ancient = 1_000L
        transaction(Db.instance) {
            Accounts.update({ Accounts.id eq id }) { it[Accounts.deletedAt] = ancient }
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = id
                it[LlmCallLogs.model] = "deepseek-chat"
                it[LlmCallLogs.provider] = "CLOUDFLARE"
                it[LlmCallLogs.costCny] = 0.0
                it[LlmCallLogs.respBytes] = 0
                it[LlmCallLogs.status] = "ok"
                it[LlmCallLogs.createdAt] = ancient
            }
        }

        val n = AccountService.purgeExpiredDeleted(1L)

        assertEquals(1, n)
        assertEquals(0L, transaction(Db.instance) { Accounts.selectAll().count() })
        assertEquals(0L, transaction(Db.instance) { LlmCallLogs.selectAll().count() })
    }

    @Test
    fun `does not purge accounts within retention`() = runBlocking {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("recent@example.com", 100)
        AccountService.softDelete(AccountService.sha256(info.token))

        val n = AccountService.purgeExpiredDeleted(Long.MAX_VALUE / 2)

        assertEquals(0, n)
        assertEquals(1L, transaction(Db.instance) { Accounts.selectAll().count() })
    }

    @Test
    fun `does not purge active accounts`() = runBlocking {
        TestDb.init(Accounts)
        AccountService.createOrRefresh("active@example.com", 100)

        val n = AccountService.purgeExpiredDeleted(1L)

        assertEquals(0, n)
        assertEquals(1L, transaction(Db.instance) { Accounts.selectAll().count() })
    }
}
