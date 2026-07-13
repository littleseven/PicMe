package com.mamba.picme.server.llm

import com.mamba.picme.server.db.LlmChannels
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChannelRegistryTest {

    private fun input(name: String = "C1", enabled: Boolean = true) = ChannelInput(
        name = name,
        kind = "direct",
        baseUrl = "https://example.com/chat",
        authStyle = "bearer",
        apiToken = "tok",
        modelMap = mapOf("deepseek-chat" to "glm-5.2"),
        enabled = enabled,
    )

    @Before
    fun setUp() {
        TestDb.init(LlmChannels)
    }

    @Test
    fun `reload loads the active enabled channel`() = runBlocking {
        val id = ChannelRepository.create(input())
        ChannelRepository.setActive(id)
        ChannelRegistry.reload()
        assertEquals("C1", ChannelRegistry.active()?.name)
        assertEquals("glm-5.2", ChannelRegistry.active()?.modelMap?.get("deepseek-chat"))
    }

    @Test
    fun `reload picks the enabled active channel`() = runBlocking {
        ChannelRepository.create(input("OFF", enabled = false))
        val on = ChannelRepository.create(input("ON", enabled = true))
        ChannelRepository.setActive(on)
        ChannelRegistry.reload()
        assertEquals("ON", ChannelRegistry.active()?.name)
    }

    @Test
    fun `active is null when no active channel`() = runBlocking {
        ChannelRepository.create(input())
        ChannelRegistry.reload()
        assertNull(ChannelRegistry.active())
    }

    @Test
    fun `setActiveForTesting injects config without DB`() {
        val cfg = ChannelConfig(1, "X", "direct", "u", AuthStyle.BEARER, "t", emptyMap())
        ChannelRegistry.setActiveForTesting(cfg)
        assertEquals("X", ChannelRegistry.active()?.name)
        ChannelRegistry.setActiveForTesting(null)
        assertNull(ChannelRegistry.active())
    }
}
