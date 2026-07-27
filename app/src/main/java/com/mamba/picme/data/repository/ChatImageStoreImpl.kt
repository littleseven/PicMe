package com.mamba.picme.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.dao.ChatImageCacheDao
import com.mamba.picme.data.local.entity.ChatImageCacheEntity
import com.mamba.picme.domain.repository.ChatImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "PoLang:ChatImageStore"
private const val JPEG_QUALITY = 95
private const val GALLERY_RELATIVE_PATH = "Pictures/PoLang"

class ChatImageStoreImpl(
    private val context: Context,
    private val dao: ChatImageCacheDao,
    private val cacheDir: File = File(context.filesDir, "chat_edit_cache"),
    private val maxSizeBytes: Long = ChatImageStore.DEFAULT_MAX_SIZE_BYTES,
    private val outputCollectionUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : ChatImageStore {

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    override suspend fun writeResult(sessionId: String, bitmap: Bitmap, mimeType: String): String =
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            val ext = if (mimeType.contains("png")) "png" else "jpg"
            val file = File(cacheDir, "edit_${UUID.randomUUID()}.$ext")
            java.io.FileOutputStream(file).use { out ->
                val format = if (ext == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bitmap.compress(format, JPEG_QUALITY, out)
            }
            val now = System.currentTimeMillis()
            dao.upsert(
                ChatImageCacheEntity(
                    filePath = file.absolutePath,
                    sessionId = sessionId,
                    createdAt = now,
                    lastAccessedAt = now,
                    sizeBytes = file.length(),
                    status = ChatImageStore.Status.ACTIVE
                )
            )
            enforceCap()
            "file://${file.absolutePath}"
        }

    override suspend fun copyToGallery(filePath: String): String? = withContext(Dispatchers.IO) {
        // 见 Task 3 实现
        null
    }

    override suspend fun markSaved(filePath: String) {
        // 见 Task 3 实现
    }

    override suspend fun touch(filePath: String) {
        dao.updateLastAccessed(filePath, System.currentTimeMillis())
    }

    override suspend fun enforceCap() {
        var guard = 0
        while (dao.sumSizeWhereActive() > maxSizeBytes && guard < 10000) {
            guard++
            val victims = dao.oldestActive(1)
            if (victims.isEmpty()) break
            val v = victims.first()
            // 单文件 >= cap 时不自删（避免删掉唯一/最新的大图），仅 log
            if (v.sizeBytes >= maxSizeBytes) {
                Logger.w(TAG, "Single file ${v.filePath} (${v.sizeBytes}B) >= cap $maxSizeBytes; skip eviction")
                break
            }
            runCatching { File(v.filePath).delete() }
            dao.updateStatus(v.filePath, ChatImageStore.Status.EVICTED)
            Logger.i(TAG, "LRU evicted ${v.filePath}")
        }
    }

    override suspend fun reconcileColdStart() {
        // 见 Task 4 实现
    }

    override suspend fun evictForSession(sessionId: String) {
        // 见 Task 4 实现
    }
}
