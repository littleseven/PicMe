package com.mamba.picme.domain.agent.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 飞书拍照追踪器
 *
 * 用于桥接飞书远程控制命令与照片拍摄完成事件。
 * 当飞书发送"拍照"命令时，[RemoteCommandDispatcher] 设置 pendingMessageId，
 * 照片保存完成后，[PoLangApplication] 监听媒体库变化并检查此状态，
 * 若存在 pending ID，则将照片发送到飞书并写入聊天记录。
 *
 * **线程安全**：所有操作通过 StateFlow 实现，线程安全。
 */
object FeishuPhotoTracker {

    private val _pendingMessageId = MutableStateFlow<String?>(null)
    val pendingMessageId: StateFlow<String?> = _pendingMessageId.asStateFlow()

    /**
     * 标记飞书拍照请求开始
     * @param messageId 飞书消息 ID，照片保存后用于回复
     */
    fun startCapture(messageId: String) {
        _pendingMessageId.value = messageId
    }

    /**
     * 标记飞书拍照请求完成（照片已处理）
     */
    fun finishCapture() {
        _pendingMessageId.value = null
    }

    /**
     * 获取并清空 pending messageId（一次性消费）
     */
    fun consumePendingMessageId(): String? {
        val id = _pendingMessageId.value
        _pendingMessageId.value = null
        return id
    }

    /**
     * 当前是否有待处理的飞书拍照请求
     */
    fun hasPendingCapture(): Boolean = _pendingMessageId.value != null
}
