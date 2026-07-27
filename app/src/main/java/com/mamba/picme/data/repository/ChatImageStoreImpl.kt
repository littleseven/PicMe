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
    // 测试可注入假的 MediaStore 写入器，避开 Android 静态 API（ContentValues/MediaStore）
    private val galleryInserter: ((srcFile: File, displayName: String) -> String?)? = null
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
        val src = File(filePath.removePrefix("file://"))
        if (!src.exists()) {
            Logger.w(TAG, "copyToGallery: source missing $filePath")
            return@withContext null
        }
        val inserter = galleryInserter ?: { file, name -> insertIntoMediaStore(file, name) }
        inserter(src, "PoLang_edit_${UUID.randomUUID()}.jpg")
    }

    override suspend fun markSaved(filePath: String) {
        val abs = filePath.removePrefix("file://")
        runCatching { File(abs).delete() }
        dao.updateStatus(abs, ChatImageStore.Status.SAVED)
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

    override suspend fun reconcileColdStart() = withContext(Dispatchers.IO) {
        // 1) ACTIVE 行文件缺失 → EVICTED；SAVED/EVICTED 行文件还在 → 删
        dao.allRows().forEach { row ->
            val f = File(row.filePath)
            when (row.status) {
                ChatImageStore.Status.ACTIVE ->
                    if (!f.exists()) dao.updateStatus(row.filePath, ChatImageStore.Status.EVICTED)
                ChatImageStore.Status.SAVED, ChatImageStore.Status.EVICTED ->
                    if (f.exists()) runCatching { f.delete() }
            }
        }
        // 2) 删孤儿文件（缓存目录里有、表里没有）
        val known = dao.allFilePaths().toHashSet()
        cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.absolutePath !in known) runCatching { f.delete() }
        }
        // 3) prune 终态行（UUID 文件名不复用，安全）
        dao.pruneTerminalRows()
        // 4) 重新执行容量约束
        enforceCap()
    }

    override suspend fun evictForSession(sessionId: String) {
        dao.getActiveBySession(sessionId).forEach { row ->
            runCatching { File(row.filePath).delete() }
            dao.updateStatus(row.filePath, ChatImageStore.Status.EVICTED)
        }
    }

    /** 真正把文件写入 MediaStore Pictures/PoLang，返回 content:// URI。 */
    private fun insertIntoMediaStore(src: File, displayName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val itemUri = context.contentResolver.insert(collection, values) ?: return null
        var ok = false
        context.contentResolver.openOutputStream(itemUri)?.use { out ->
            src.inputStream().use { input -> input.copyTo(out) }
            ok = true
        }
        if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(itemUri, values, null, null)
        }
        return if (ok) itemUri.toString() else null
    }
}
