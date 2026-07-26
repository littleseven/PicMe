package com.mamba.picme.domain.agent.remote

import com.mamba.picme.core.common.Logger
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendPhoto
import com.pengrad.telegrambot.request.SendMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Telegram 通道处理器：基于 Pengrad 长轮询（getUpdates，库内部管 offset/重试），无需公网 IP。
 *
 * 安全：仅处理来自 [connect] 时传入的 allowedChatId 的消息（fail-closed，见 [TelegramMessageFilter]）。
 * 生命周期对齐飞书：[connect] 启动长轮询、[disconnect] 停止；重连由 [RemoteChannelManager] 重新 activate。
 *
 * replyToken 语义：Telegram 侧为 chatId（字符串），发送时 [String.toLongOrNull] 还原。
 */
class TelegramChannelHandler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : RemoteChannel {

    override val channelId: String = "telegram"

    private var bot: TelegramBot? = null

    @Volatile
    private var allowedChatId: String = ""

    @Volatile
    override var isConnected: Boolean = false
        private set

    override var onMessageReceived: ((text: String, replyToken: String) -> Unit)? = null
    override var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null

    /**
     * 启动 Telegram 长轮询。
     * @param botToken BotFather 颁发的 token
     * @param allowedChatId 允许下发指令的聊天 ID（空则 fail-closed 拒绝全部）
     */
    fun connect(botToken: String, allowedChatId: String) {
        if (botToken.isBlank()) {
            Logger.w(TAG, "Telegram Bot Token 未配置，通道不可用")
            return
        }
        disconnect()
        this.allowedChatId = allowedChatId
        val b = TelegramBot(botToken)
        bot = b
        b.setUpdatesListener(
            UpdatesListener { updates ->
                if (!isConnected) {
                    isConnected = true
                    onConnectionStateChanged?.invoke(true)
                    Logger.i(TAG, "Telegram 长轮询已连接")
                }
                for (update: Update in updates) {
                    handleMessage(update)
                }
                UpdatesListener.CONFIRMED_UPDATES_ALL
            }
        )
    }

    private fun handleMessage(update: Update) {
        val msg = update.message() ?: return
        val text = msg.text() ?: return
        val chat = msg.chat() ?: return
        val chatId = chat.id().toString()
        if (!TelegramMessageFilter.shouldAccept(chatId, allowedChatId)) {
            Logger.w(TAG, "Telegram 消息被白名单拒绝: chatId=$chatId")
            return
        }
        onMessageReceived?.invoke(text, chatId)
    }

    override fun sendMessage(text: String, replyToken: String) {
        val b = bot ?: run {
            Logger.w(TAG, "发送失败：Telegram 客户端未初始化")
            return
        }
        val chatId = replyToken.toLongOrNull() ?: run {
            Logger.w(TAG, "发送失败：非法 chatId=$replyToken")
            return
        }
        scope.launch {
            val resp = b.execute(SendMessage(chatId, text))
            Logger.i(TAG, "Telegram 发送消息: ok=${resp.isOk}")
        }
    }

    override fun sendImage(bytes: ByteArray, replyToken: String) {
        val b = bot ?: return
        val chatId = replyToken.toLongOrNull() ?: return
        scope.launch {
            val resp = b.execute(SendPhoto(chatId, bytes))
            Logger.i(TAG, "Telegram 发送图片: ok=${resp.isOk}")
        }
    }

    fun disconnect() {
        bot?.let {
            runCatching { it.removeGetUpdatesListener() }
            runCatching { it.shutdown() }
        }
        bot = null
        if (isConnected) {
            isConnected = false
            onConnectionStateChanged?.invoke(false)
        }
        Logger.i(TAG, "Telegram 已断开")
    }

    companion object {
        private const val TAG = "TelegramHandler"
    }
}
