package com.mamba.picme.domain.repository

import android.graphics.Bitmap

/**
 * chat 编辑/优化结果图的私有缓存仓储。
 *
 * 所有 chat 内生成的结果图经 [writeResult] 落盘到 filesDir/chat_edit_cache/，
 * 不直接写 MediaStore；用户在预览页主动保存后才复制进相册（[copyToGallery]），
 * 并把消息 imageUri 重指向 content:// URI、释放私有文件（[markSaved]）。
 * 未经保存的结果按总磁盘容量做 LRU 回收（[enforceCap]）。
 */
interface ChatImageStore {

    /** 渲染结果落盘到缓存目录、写 ACTIVE 行、触发 enforceCap，返回 file:// 路径。 */
    suspend fun writeResult(sessionId: String, bitmap: Bitmap, mimeType: String): String

    /** 复制私有文件到 Pictures/PoLang，返回新 content:// URI；不动文件、不动表。失败返回 null。 */
    suspend fun copyToGallery(filePath: String): String?

    /** 删私有文件 + 行置 SAVED（消息已重指向后调用）。 */
    suspend fun markSaved(filePath: String)

    /** 刷新 lastAccessedAt（打开预览 / 再编辑时调用）。 */
    suspend fun touch(filePath: String)

    /** 超 cap 则按 lastAccessedAt 最旧的 ACTIVE 逐个删文件 + 置 EVICTED。 */
    suspend fun enforceCap()

    /** 冷启对账：修缺文件行 / 孤儿文件 / 终态行，再 enforceCap。 */
    suspend fun reconcileColdStart()

    /** 删会话时调用，清理该会话的 ACTIVE 文件。 */
    suspend fun evictForSession(sessionId: String)

    object Status {
        const val ACTIVE = "ACTIVE"
        const val SAVED = "SAVED"
        const val EVICTED = "EVICTED"
    }

    companion object {
        const val DEFAULT_MAX_SIZE_BYTES: Long = 200L * 1024 * 1024
    }
}
