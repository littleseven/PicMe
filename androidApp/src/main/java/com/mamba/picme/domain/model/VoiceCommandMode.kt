package com.mamba.picme.domain.model

/**
 * 语音命令模式
 *
 * - DISABLED: 关闭语音控制
 * - PUSH_TO_TALK: 按住语音按钮说话
 * - WAKE_WORD: 唤醒词监听模式（免手）
 */
enum class VoiceCommandMode {
    DISABLED,
    PUSH_TO_TALK,
    WAKE_WORD
}
