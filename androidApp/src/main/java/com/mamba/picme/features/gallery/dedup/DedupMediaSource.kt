package com.mamba.picme.features.gallery.dedup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.dedup.DedupScanner
import com.mamba.picme.domain.repository.AndroidMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * 扫描输入源抽象（Agent First：显式注入接缝，ViewModel 单测用 lambda fake）。
 *
 * 生产实现为 [MediaStoreDedupMediaSource]。
 */
fun interface DedupMediaSource {
    suspend fun photoScanItems(): List<DedupScanner.ScanItem>
}

/**
 * 生产取数：相册库照片（[AndroidMediaRepository.allMedia]，仅元数据，[LAZY_LOAD]）
 * + MediaStore 一次性批量查询补 sizeBytes/mime/modifiedAt（DATE_MODIFIED 秒→毫秒）。
 * 元数据缺失（如文件已不可读）的照片跳过，不参与去重。
 *
 * 注意 join key 必须用 content uri：`MediaAsset.id` 是 [MediaRepositoryImpl] 的
 * syntheticMediaId 负值编码（区分系统/DB 来源），与 MediaStore `_ID` 不相等。
 */
class MediaStoreDedupMediaSource(
    private val context: Context,
    private val repository: AndroidMediaRepository,
) : DedupMediaSource {

    override suspend fun photoScanItems(): List<DedupScanner.ScanItem> = withContext(Dispatchers.IO) {
        val photos = repository.allMedia.firstOrNull().orEmpty()
            .filter { asset -> asset.type == MediaType.PHOTO }
        if (photos.isEmpty()) return@withContext emptyList()
        val metaByUri = queryImageMeta(context.contentResolver)
        photos.mapNotNull { asset ->
            val meta = metaByUri[asset.uri] ?: return@mapNotNull null
            DedupScanner.ScanItem(
                uri = asset.uri,
                sizeBytes = meta.sizeBytes,
                mime = meta.mime,
                captureDate = asset.captureDate,
                modifiedAt = meta.modifiedAtMs,
                aestheticScore = asset.aestheticScore,
            )
        }
    }

    private data class ImageMeta(val sizeBytes: Long, val modifiedAtMs: Long, val mime: String)

    /** key = content uri 字符串（与 `MediaAsset.uri` 同源：withAppendedId(EXTERNAL_CONTENT_URI, _ID)） */
    private fun queryImageMeta(resolver: ContentResolver): Map<String, ImageMeta> {
        val contentUri = MediaStore.Images.Media.getContentUri("external")
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val result = HashMap<String, ImageMeta>()
        runCatching {
            resolver.query(
                contentUri,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue
                    val uri = ContentUris.withAppendedId(contentUri, cursor.getLong(idCol)).toString()
                    result[uri] = ImageMeta(
                        sizeBytes = size,
                        modifiedAtMs = cursor.getLong(modifiedCol) * 1_000L,
                        mime = cursor.getString(mimeCol) ?: "image/*",
                    )
                }
            }
        }.onFailure { error -> Logger.w(TAG, "query image meta failed", error) }
        return result
    }

    private companion object {
        const val TAG = "PoLang:Dedup"
    }
}
