package com.mamba.picme.domain.agent.remote

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.RemoteChannelType

/**
 * 单通道管理器：同一时刻仅一个通道连接。实现 [RemoteChannel] 供调度器透明使用。
 *
 * - [activate] 由 Application 在「通道选择 / 凭据」变化时调用；先全断开再按 [ChannelActivationResolver] 决策启动。
 *   凭据缺失则保持断开。manager 记住上次参数以支持 [reconnect]。
 * - [reconnect] 用上次参数重新 activate（网络恢复 / 回前台）。
 * - 发送与回调委托给当前激活通道；无激活通道时空操作。
 * - [activeSourceTag] / [channelId] 供拍照观察者判断照片来源与目标会话。
 */
class RemoteChannelManager(
    private val feishu: FeishuChannelHandler,
    private val telegram: TelegramChannelHandler
) : RemoteChannel {

    @Volatile private var lastType: RemoteChannelType = RemoteChannelType.NONE
    @Volatile private var lastFeishuAppId: String = ""
    @Volatile private var lastFeishuAppSecret: String = ""
    @Volatile private var lastTelegramToken: String = ""
    @Volatile private var lastTelegramChatId: String = ""

    fun activate(
        type: RemoteChannelType,
        feishuAppId: String,
        feishuAppSecret: String,
        telegramBotToken: String,
        telegramAllowedChatId: String
    ) {
        lastType = type
        lastFeishuAppId = feishuAppId
        lastFeishuAppSecret = feishuAppSecret
        lastTelegramToken = telegramBotToken
        lastTelegramChatId = telegramAllowedChatId

        feishu.disconnect()
        telegram.disconnect()

        when (
            val decision = ChannelActivationResolver.resolve(
                type, feishuAppId, feishuAppSecret, telegramBotToken, telegramAllowedChatId
            )
        ) {
            ChannelActivation.None ->
                Logger.i(TAG, "activate: 无激活通道（type=$type）")
            is ChannelActivation.Feishu ->
                feishu.init(decision.appId, decision.appSecret)
            is ChannelActivation.Telegram ->
                telegram.connect(decision.botToken, decision.allowedChatId)
        }
    }

    fun reconnect() {
        Logger.i(TAG, "reconnect: 重新 activate 上次选择（type=$lastType）")
        activate(lastType, lastFeishuAppId, lastFeishuAppSecret, lastTelegramToken, lastTelegramChatId)
    }

    override val channelId: String
        get() = when (lastType) {
            RemoteChannelType.FEISHU -> feishu.channelId
            RemoteChannelType.TELEGRAM -> telegram.channelId
            RemoteChannelType.NONE -> ""
        }

    /** 媒体来源标签（拍照观察者据此过滤 feishu_remote / telegram_remote）。 */
    val activeSourceTag: String
        get() = when (lastType) {
            RemoteChannelType.FEISHU -> "feishu_remote"
            RemoteChannelType.TELEGRAM -> "telegram_remote"
            RemoteChannelType.NONE -> ""
        }

    override val isConnected: Boolean
        get() = when (lastType) {
            RemoteChannelType.FEISHU -> feishu.isConnected
            RemoteChannelType.TELEGRAM -> telegram.isConnected
            RemoteChannelType.NONE -> false
        }

    override var onMessageReceived: ((text: String, replyToken: String) -> Unit)? = null
        set(value) {
            field = value
            feishu.onMessageReceived = value
            telegram.onMessageReceived = value
        }

    override var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null
        set(value) {
            field = value
            feishu.onConnectionStateChanged = value
            telegram.onConnectionStateChanged = value
        }

    private fun activeSender(): RemoteChannel? = when (lastType) {
        RemoteChannelType.FEISHU -> feishu
        RemoteChannelType.TELEGRAM -> telegram
        RemoteChannelType.NONE -> null
    }

    override fun sendMessage(text: String, replyToken: String) {
        activeSender()?.sendMessage(text, replyToken)
    }

    override fun sendImage(bytes: ByteArray, replyToken: String) {
        activeSender()?.sendImage(bytes, replyToken)
    }

    companion object {
        private const val TAG = "RemoteChannelManager"
    }
}
