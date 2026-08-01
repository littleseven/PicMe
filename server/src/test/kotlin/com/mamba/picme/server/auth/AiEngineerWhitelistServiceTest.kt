package com.mamba.picme.server.auth

import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.AiEngineerWhitelists
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEngineerWhitelistServiceTest {

    private fun seed() {
        TestDb.init(Accounts, AiEngineerWhitelists)
    }

    @Test
    fun `空表时所有邮箱均被拒绝`() = runBlocking {
        seed()
        assertFalse(AiEngineerWhitelistService.isAllowed("a@x.com"))
    }

    @Test
    fun `添加后放行且大小写不敏感`() = runBlocking {
        seed()
        assertTrue(AiEngineerWhitelistService.allow("A@X.COM"))
        assertTrue(AiEngineerWhitelistService.isAllowed("a@x.com"))
        assertTrue(AiEngineerWhitelistService.isAllowed("A@X.COM"))
    }

    @Test
    fun `重复添加返回 false`() = runBlocking {
        seed()
        assertTrue(AiEngineerWhitelistService.allow("a@x.com"))
        assertFalse(AiEngineerWhitelistService.allow("a@x.com"))
    }

    @Test
    fun `移除后拒绝`() = runBlocking {
        seed()
        AiEngineerWhitelistService.allow("a@x.com")
        assertTrue(AiEngineerWhitelistService.revoke("A@X.COM"))
        assertFalse(AiEngineerWhitelistService.isAllowed("a@x.com"))
    }

    @Test
    fun `移除不存在的邮箱返回 false`() = runBlocking {
        seed()
        assertFalse(AiEngineerWhitelistService.revoke("a@x.com"))
    }

    @Test
    fun `按 tokenHash 判断找不到账号返回 false`() = runBlocking {
        seed()
        assertFalse(AiEngineerWhitelistService.isAllowedByTokenHash("no-such-hash"))
    }

    @Test
    fun `按 tokenHash 判断命中账号但不在白名单返回 false`() = runBlocking {
        seed()
        val token = AccountService.createOrRefresh("a@x.com", 100).token
        val hash = AccountService.sha256(token)
        assertFalse(AiEngineerWhitelistService.isAllowedByTokenHash(hash))
    }

    @Test
    fun `按 tokenHash 判断命中账号且在白名单返回 true`() = runBlocking {
        seed()
        val token = AccountService.createOrRefresh("a@x.com", 100).token
        val hash = AccountService.sha256(token)
        AiEngineerWhitelistService.allow("a@x.com")
        assertTrue(AiEngineerWhitelistService.isAllowedByTokenHash(hash))
    }

    @Test
    fun `list 按时间倒序`() = runBlocking {
        seed()
        AiEngineerWhitelistService.allow("b@x.com")
        Thread.sleep(10)
        AiEngineerWhitelistService.allow("a@x.com")
        val list = AiEngineerWhitelistService.list()
        assertEquals(2, list.size)
        assertEquals("a@x.com", list[0].email)
        assertEquals("b@x.com", list[1].email)
    }
}
