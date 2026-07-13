package com.mamba.picme.server.db

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.llm.ChannelRepository
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MigrationsSeedChannelsTest {

    private val config = AppConfig.load()

    @Before
    fun setUp() {
        TestDb.init(LlmChannels)
    }

    @Test
    fun `seedChannels creates 5 channels with one active`() = runBlocking {
        Migrations.seedChannels(config)
        val channels = ChannelRepository.list()
        assertEquals(5, channels.size)
        assertEquals(1, channels.count { it.isActive })
    }

    @Test
    fun `default active channel is Cloudflare when FORCE_PROVIDER unset`() = runBlocking {
        Migrations.seedChannels(config)
        val active = ChannelRepository.list().first { it.isActive }
        assertEquals("Cloudflare", active.name)
    }

    @Test
    fun `seedChannels is idempotent`() = runBlocking {
        Migrations.seedChannels(config)
        Migrations.seedChannels(config)
        assertEquals(5, ChannelRepository.list().size)
    }

    @Test
    fun `seedChannels seeds direct providers disabled`() = runBlocking {
        Migrations.seedChannels(config)
        val direct = ChannelRepository.list().filter { it.kind == "direct" }
        assertEquals(3, direct.size)
        assertTrue(direct.none { it.enabled })
    }
}
