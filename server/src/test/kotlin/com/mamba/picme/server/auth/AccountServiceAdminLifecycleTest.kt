package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.LlmCallLogs
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountServiceAdminLifecycleTest {

    private fun seedAccount(email: String = "admin-test@example.com"): Pair<Int, String> {
        TestDb.init(Accounts, LlmCallLogs)
        return transaction(Db.instance) {
            Accounts.insert {
                it[Accounts.email] = email
                it[Accounts.tokenHash] = "hash-${email}"
                it[Accounts.tokenPlain] = "plain-token"
                it[Accounts.status] = "active"
                it[Accounts.llmCallsUsed] = 0
                it[Accounts.llmCallsLimit] = 100
                it[Accounts.createdAt] = 1_700_000_000_000L
            }
            val id = Accounts.selectAll().single()[Accounts.id]
            LlmCallLogs.insert {
                it[LlmCallLogs.accountId] = id
                it[LlmCallLogs.model] = "gpt-4o"
                it[LlmCallLogs.provider] = "CLOUDFLARE"
                it[LlmCallLogs.totalTokens] = 10
                it[LlmCallLogs.costCny] = 0.1
                it[LlmCallLogs.respBytes] = 50
                it[LlmCallLogs.status] = "ok"
                it[LlmCallLogs.createdAt] = 1_700_000_001_000L
            }
            id to email
        }
    }

    @Test
    fun `setStatus revokes and unrevokes active account`() = runBlocking {
        val (id, _) = seedAccount()

        assertTrue(AccountService.setStatus(id, "revoked"))
        val revoked = transaction(Db.instance) {
            Accounts.selectAll().where { Accounts.id eq id }.single()[Accounts.status]
        }
        assertEquals("revoked", revoked)

        assertTrue(AccountService.setStatus(id, "active"))
        val active = transaction(Db.instance) {
            Accounts.selectAll().where { Accounts.id eq id }.single()[Accounts.status]
        }
        assertEquals("active", active)
    }

    @Test
    fun `setStatus returns false when status unchanged or account missing`() = runBlocking {
        val (id, _) = seedAccount()

        assertFalse(AccountService.setStatus(id, "active"))
        assertFalse(AccountService.setStatus(9999, "revoked"))
    }

    @Test
    fun `setStatus rejects invalid status`() = runBlocking {
        seedAccount()
        try {
            AccountService.setStatus(1, "deleted")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `adminSoftDelete anonymizes account and deletes call logs`() = runBlocking {
        val (id, email) = seedAccount()

        assertTrue(AccountService.adminSoftDelete(id))

        transaction(Db.instance) {
            val acc = Accounts.selectAll().where { Accounts.id eq id }.single()
            assertEquals("deleted", acc[Accounts.status])
            assertTrue(acc[Accounts.email].startsWith("deleted_${id}__${email}"))
            assertEquals("", acc[Accounts.tokenPlain])
            assertTrue(acc[Accounts.deletedAt] != null && acc[Accounts.deletedAt]!! > 0)

            val logs = LlmCallLogs.selectAll().where { LlmCallLogs.accountId eq id }.count()
            assertEquals(0L, logs)
        }
    }

    @Test
    fun `adminSoftDelete returns false for already deleted or missing account`() = runBlocking {
        val (id, _) = seedAccount()
        AccountService.adminSoftDelete(id)

        assertFalse(AccountService.adminSoftDelete(id))
        assertFalse(AccountService.adminSoftDelete(9999))
    }

    @Test
    fun `purgeAccount removes account and all call logs immediately`() = runBlocking {
        val (id, _) = seedAccount()

        assertTrue(AccountService.purgeAccount(id))

        transaction(Db.instance) {
            val acc = Accounts.selectAll().where { Accounts.id eq id }.firstOrNull()
            assertNull(acc)
            val logs = LlmCallLogs.selectAll().where { LlmCallLogs.accountId eq id }.count()
            assertEquals(0L, logs)
        }
    }

    @Test
    fun `purgeAccount returns false for missing account`() = runBlocking {
        seedAccount()
        assertFalse(AccountService.purgeAccount(9999))
    }

    @Test
    fun `resetQuota zeroes used but keeps limit and call logs`() = runBlocking {
        val (id, _) = seedAccount()
        transaction(Db.instance) {
            Accounts.update({ Accounts.id eq id }) {
                with(SqlExpressionBuilder) { it[llmCallsUsed] = llmCallsUsed + 30 }
            }
        }

        assertTrue(AccountService.resetQuota(id))

        transaction(Db.instance) {
            val acc = Accounts.selectAll().where { Accounts.id eq id }.single()
            assertEquals(0, acc[Accounts.llmCallsUsed])
            assertEquals(100, acc[Accounts.llmCallsLimit]) // limit 不变
            assertEquals(1L, LlmCallLogs.selectAll().where { LlmCallLogs.accountId eq id }.count())
        }
    }

    @Test
    fun `resetQuota returns false for missing account`() = runBlocking {
        assertFalse(AccountService.resetQuota(9999))
    }

    @Test
    fun `setLimit updates limit`() = runBlocking {
        val (id, _) = seedAccount()
        assertTrue(AccountService.setLimit(id, 500))
        transaction(Db.instance) {
            assertEquals(500, Accounts.selectAll().where { Accounts.id eq id }.single()[Accounts.llmCallsLimit])
        }
    }

    @Test
    fun `setLimit rejects negative limit`() = runBlocking {
        seedAccount()
        try {
            AccountService.setLimit(1, -1)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
