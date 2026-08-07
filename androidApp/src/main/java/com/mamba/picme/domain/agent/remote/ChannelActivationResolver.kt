package com.mamba.picme.domain.agent.remote

import com.mamba.picme.domain.model.RemoteChannelType

/** [RemoteChannelManager.activate] 的纯决策结果。 */
sealed interface ChannelActivation {
    data object None : ChannelActivation
    data class Feishu(val appId: String, val appSecret: String) : ChannelActivation
    data class Telegram(val botToken: String, val allowedChatId: String) : ChannelActivation
}

/**
 * 纯函数：按通道选择与凭据决定激活哪个通道（凭据缺失则 [ChannelActivation.None]）。
 *
 * - FEISHU：appId 与 appSecret 均非空才激活。
 * - TELEGRAM：botToken 非空即激活（allowedChatId 可空 —— 由 handler 层 fail-closed 过滤消息）。
 * - NONE / 凭据缺失：[ChannelActivation.None]（断开全部）。
 *
 * 无副作用，便于单测。
 */
object ChannelActivationResolver {
    fun resolve(
        type: RemoteChannelType,
        feishuAppId: String,
        feishuAppSecret: String,
        telegramBotToken: String,
        telegramAllowedChatId: String
    ): ChannelActivation = when (type) {
        RemoteChannelType.NONE -> ChannelActivation.None
        RemoteChannelType.FEISHU ->
            if (feishuAppId.isNotBlank() && feishuAppSecret.isNotBlank()) {
                ChannelActivation.Feishu(feishuAppId, feishuAppSecret)
            } else {
                ChannelActivation.None
            }
        RemoteChannelType.TELEGRAM ->
            if (telegramBotToken.isNotBlank()) {
                ChannelActivation.Telegram(telegramBotToken, telegramAllowedChatId)
            } else {
                ChannelActivation.None
            }
    }
}
