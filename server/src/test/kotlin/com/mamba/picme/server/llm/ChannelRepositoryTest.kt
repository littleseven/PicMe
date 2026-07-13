package com.mamba.picme.server.llm

import com.mamba.picme.server.db.LlmChannels
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChannelRepositoryTest {

    private fun input(name: String = "C1", enabled: Boolean = true) = ChannelInput(
        name = name,
        kind = "direct",
        baseUrl = "https://example.com/chat",
        authStyle = "bearer",
        apiToken = "tok-123456",
        modelMap = mapOf("deepseek-chat" to "glm-5.2"),
        enabled = enabled,
        defaultModel = "deepseek-v4-flash",
    )

    @Before
    fun setUp() {
        TestDb.init(LlmChannels)
    }

    @Test
    fun `create and list`() = runBlocking {
        val id = ChannelRepository.create(input())
        val rows = ChannelRepository.list()
        assertEquals(1, rows.size)
        assertEquals(id, rows[0].id)
        assertEquals("••••3456", rows[0].apiTokenMasked)
    }

    @Test
    fun `update with empty token keeps original`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.update(id, input(name = "C2").copy(apiToken = ""))
        val row = ChannelRepository.get(id)!!
        assertEquals("C2", row.name)
        assertEquals("••••3456", row.apiTokenMasked)
    }

    @Test
    fun `update with new token overwrites`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.update(id, input().copy(apiToken = "new-tok-9999"))
        assertEquals("••••9999", ChannelRepository.get(id)!!.apiTokenMasked)
    }

    @Test
    fun `setActive clears others and only one active`() = runBlocking {
        val a = ChannelRepository.create(input("A"))
        val b = ChannelRepository.create(input("B"))
        ChannelRepository.setActive(a)
        assertTrue(ChannelRepository.get(a)!!.isActive)
        assertFalse(ChannelRepository.get(b)!!.isActive)
        ChannelRepository.setActive(b)
        assertFalse(ChannelRepository.get(a)!!.isActive)
        assertTrue(ChannelRepository.get(b)!!.isActive)
    }

    @Test
    fun `setActive rejects disabled channel`() = runBlocking {
        val id = ChannelRepository.create(input(enabled = false))
        assertFalse(ChannelRepository.setActive(id))
        assertFalse(ChannelRepository.get(id)!!.isActive)
    }

    @Test
    fun `delete active channel rejected`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.setActive(id)
        assertFalse(ChannelRepository.delete(id))
        assertEquals(1, ChannelRepository.list().size)
    }

    @Test
    fun `delete non-active channel succeeds`() = runBlocking {
        val id = ChannelRepository.create(input())
        assertTrue(ChannelRepository.delete(id))
        assertTrue(ChannelRepository.list().isEmpty())
    }

    @Test
    fun `set enabled false clears active on that channel`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.setActive(id)
        ChannelRepository.setEnabled(id, false)
        val row = ChannelRepository.get(id)!!
        assertFalse(row.enabled)
        assertFalse(row.isActive)
    }

    @Test
    fun `loadActive returns active enabled config with token`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.setActive(id)
        val cfg = ChannelRepository.loadActive()
        assertEquals("C1", cfg!!.name)
        assertEquals("tok-123456", cfg.apiToken)
        assertEquals(AuthStyle.BEARER, cfg.authStyle)
    }

    @Test
    fun `loadActive returns null when none active`() = runBlocking {
        ChannelRepository.create(input())
        assertNull(ChannelRepository.loadActive())
    }

    @Test
    fun `create and update carry defaultModel`() = runBlocking {
        val id = ChannelRepository.create(input())
        assertEquals("deepseek-v4-flash", ChannelRepository.get(id)!!.defaultModel)
        ChannelRepository.update(id, input().copy(defaultModel = "glm-5.2"))
        assertEquals("glm-5.2", ChannelRepository.get(id)!!.defaultModel)
        // 空串能清空
        ChannelRepository.update(id, input().copy(defaultModel = ""))
        assertEquals("", ChannelRepository.get(id)!!.defaultModel)
    }
}
