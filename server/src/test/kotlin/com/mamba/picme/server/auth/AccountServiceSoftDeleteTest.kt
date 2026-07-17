package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountServiceSoftDeleteTest {

    @Test
    fun `soft delete marks account deleted clears token plain and rewrites email`() = runBlocking {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("user@example.com", 100)
        val tokenHash = AccountService.sha256(info.token)

        val ok = AccountService.softDelete(tokenHash)

        assertTrue(ok)
        val row = transaction(Db.instance) { Accounts.selectAll().single() }
        assertEquals("deleted", row[Accounts.status])
        assertNotNull(row[Accounts.deletedAt])
        assertEquals("", row[Accounts.tokenPlain])
        assertTrue(row[Accounts.email].startsWith("deleted_"))
        assertTrue(row[Accounts.email].endsWith("__user@example.com"))
    }

    @Test
    fun `soft deleted token no longer validates`() = runBlocking {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("gone@example.com", 100)

        AccountService.softDelete(AccountService.sha256(info.token))

        assertFalse(AccountService.validateToken(info.token).valid)
    }

    @Test
    fun `soft delete returns false for unknown hash`() = runBlocking {
        TestDb.init(Accounts)
        assertFalse(AccountService.softDelete("no-such-hash"))
    }

    @Test
    fun `same email can register as a new account after soft delete`() = runBlocking {
        TestDb.init(Accounts)
        val first = AccountService.createOrRefresh("again@example.com", 100)
        AccountService.softDelete(AccountService.sha256(first.token))

        val second = AccountService.createOrRefresh("again@example.com", 100)

        assertTrue(first.token != second.token)
        assertEquals(2L, transaction(Db.instance) { Accounts.selectAll().count() })
    }
}
