package com.mamba.picme.domain.agent.remote

import com.mamba.picme.domain.model.RemoteChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelActivationResolverTest {

    @Test
    fun none_type_disconnects_all() {
        assertEquals(
            ChannelActivation.None,
            ChannelActivationResolver.resolve(
                RemoteChannelType.NONE, "id", "secret", "token", "chat"
            )
        )
    }

    @Test
    fun feishu_with_both_creds_activates_feishu() {
        val r = ChannelActivationResolver.resolve(
            RemoteChannelType.FEISHU, "id", "secret", "token", "chat"
        )
        assertTrue(r is ChannelActivation.Feishu)
        assertEquals("id", (r as ChannelActivation.Feishu).appId)
        assertEquals("secret", r.appSecret)
    }

    @Test
    fun feishu_missing_secret_yields_none() {
        assertEquals(
            ChannelActivation.None,
            ChannelActivationResolver.resolve(
                RemoteChannelType.FEISHU, "id", "   ", "token", "chat"
            )
        )
    }

    @Test
    fun feishu_missing_id_yields_none() {
        assertEquals(
            ChannelActivation.None,
            ChannelActivationResolver.resolve(
                RemoteChannelType.FEISHU, "", "secret", "token", "chat"
            )
        )
    }

    @Test
    fun telegram_with_token_activates_even_without_chatid() {
        val r = ChannelActivationResolver.resolve(
            RemoteChannelType.TELEGRAM, "id", "secret", "token", ""
        )
        assertTrue(r is ChannelActivation.Telegram)
        assertEquals("token", (r as ChannelActivation.Telegram).botToken)
        assertEquals("", r.allowedChatId)
    }

    @Test
    fun telegram_without_token_yields_none() {
        assertEquals(
            ChannelActivation.None,
            ChannelActivationResolver.resolve(
                RemoteChannelType.TELEGRAM, "id", "secret", "  ", "chat"
            )
        )
    }
}
