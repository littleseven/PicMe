package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AccountServiceIdForTokenHashTest {

    @Test
    fun `returns id for known token hash`() = runBlocking {
        TestDb.init(Accounts)
        val info = AccountService.createOrRefresh("a@b.com", 100)
        val hash = AccountService.sha256(info.token)
        val id = AccountService.idForTokenHash(hash)
        assertNotNull(id)
        // 与 account 表自增主键一致
        val rowId = transaction(Db.instance) { Accounts.selectAll().single()[Accounts.id] }
        assertEquals(rowId, id)
    }

    @Test
    fun `returns null for unknown hash`() = runBlocking {
        TestDb.init(Accounts)
        assertNull(AccountService.idForTokenHash("deadbeef"))
    }
}
