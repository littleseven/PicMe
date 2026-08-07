package com.mamba.picme.domain.agent.remote

/**
 * 远程控制通道抽象。
 *
 * - [replyToken] 为通道不透明串：飞书侧 = messageId，Telegram 侧 = chatId。
 *   单通道模型下 token 必来自当前激活通道，各通道自行解释，调度器仅透传。
 * - 重连不放进接口：由 [RemoteChannelManager] 统一负责（重新 activate）。
 */
interface RemoteChannel {
    val channelId: String
    val isConnected: Boolean
    var onMessageReceived: ((text: String, replyToken: String) -> Unit)?
    var onConnectionStateChanged: ((connected: Boolean) -> Unit)?
    fun sendMessage(text: String, replyToken: String)
    fun sendImage(bytes: ByteArray, replyToken: String)
}
