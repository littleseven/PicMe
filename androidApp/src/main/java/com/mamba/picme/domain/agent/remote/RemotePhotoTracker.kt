package com.mamba.picme.domain.agent.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 远程拍照追踪器：桥接「远程控制拍照命令」与「照片保存完成」事件。
 *
 * 持有 pending [replyToken]（飞书=messageId，Telegram=chatId），照片保存后据此回复。
 * 通道无关：由 [RemoteCommandDispatcher] 标记、PoLangApplication 媒体观察者消费。
 */
object RemotePhotoTracker {

    private val _pendingReplyToken = MutableStateFlow<String?>(null)
    val pendingReplyToken: StateFlow<String?> = _pendingReplyToken.asStateFlow()

    /**
     * 标记远程拍照请求开始。
     * @param replyToken 通道回复令牌（飞书 messageId / Telegram chatId）
     */
    fun startCapture(replyToken: String) {
        _pendingReplyToken.value = replyToken
    }

    /** 标记远程拍照请求完成（照片已处理）。 */
    fun finishCapture() {
        _pendingReplyToken.value = null
    }

    /** 获取并清空 pending replyToken（一次性消费）。 */
    fun consumePendingReplyToken(): String? {
        val token = _pendingReplyToken.value
        _pendingReplyToken.value = null
        return token
    }

    /** 当前是否有待处理的远程拍照请求。 */
    fun hasPendingCapture(): Boolean = _pendingReplyToken.value != null
}
