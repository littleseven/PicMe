package com.mamba.picme.domain.agent.remote

/**
 * Telegram chatId 白名单过滤。
 *
 * 安全语义（fail-closed）：未配置 [allowedChatId] 时拒绝全部消息，
 * 避免任意知道 bot 用户名者控制设备。详见 spec §7。
 */
object TelegramMessageFilter {
    fun shouldAccept(chatId: String?, allowedChatId: String): Boolean =
        allowedChatId.isNotBlank() && chatId != null && chatId == allowedChatId
}
