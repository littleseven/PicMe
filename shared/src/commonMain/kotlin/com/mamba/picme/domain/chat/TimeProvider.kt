package com.mamba.picme.domain.chat

/**
 * 当前 epoch 毫秒（commonMain 时间抽象，供 [ChatMessage.timestamp] 默认值用）。
 *
 * commonMain 无 `System.currentTimeMillis()`；各平台 actual 提供：
 * - androidMain：`System.currentTimeMillis()`
 * - iosMain：`NSDate.timeIntervalSince1970`
 */
internal expect fun nowEpochMillis(): Long
