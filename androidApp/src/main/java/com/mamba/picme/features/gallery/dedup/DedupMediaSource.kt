package com.mamba.picme.features.gallery.dedup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.dedup.DedupContentType
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

/** ocrText 长度超此阈值判为 DOCUMENT（spec §10.2，字符数启发式）。 */
internal const val DOCUMENT_OCR_CHAR_THRESHOLD = 200

/** MediaStore 截图目录约定（RELATIVE_PATH contains，大小写不敏感）。 */
private const val SCREENSHOT_DIR_KEYWORD = "screenshots"

/**
 * DOCUMENT 标签关键词启发式：labels 为 TAG Pass 3 产出的自由文本（中英混合），
 * 简单 contains 即可，命中即保守归为文档/证件；误伤代价仅是 VISUAL 组不预选。
 */
private val DOCUMENT_LABEL_KEYWORDS = listOf(
    "document", "receipt", "text", "screenshot_text",
    "文档", "证件", "票据", "截图文字", "文字",
)

/**
 * 内容类型识别纯函数（spec §10.2，零额外推理，可 JVM 单测）。
 * 优先级 SCREENSHOT > DOCUMENT > PORTRAIT > GENERAL；TAG 未覆盖（信号全空）一律 GENERAL。
 */
internal fun detectContentType(
    relativePath: String?,
    ocrText: String?,
    labels: String?,
    hasFace: Boolean,
    faceQualityScore: Float?,
): DedupContentType = when {
    relativePath?.contains(SCREENSHOT_DIR_KEYWORD, ignoreCase = true) == true ->
        DedupContentType.SCREENSHOT
    (ocrText?.length ?: 0) > DOCUMENT_OCR_CHAR_THRESHOLD ||
        labels?.let { text ->
            DOCUMENT_LABEL_KEYWORDS.any { keyword -> text.contains(keyword, ignoreCase = true) }
        } == true ->
        DedupContentType.DOCUMENT
    hasFace || faceQualityScore != null -> DedupContentType.PORTRAIT
    else -> DedupContentType.GENERAL
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
                contentType = detectContentType(
                    relativePath = meta.relativePath,
                    ocrText = asset.ocrText,
                    labels = asset.labels,
                    hasFace = asset.hasFace,
                    faceQualityScore = asset.faceQualityScore,
                ),
                faceQualityScore = asset.faceQualityScore,
            )
        }
    }

    private data class ImageMeta(
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        val mime: String,
        /** RELATIVE_PATH（API 29+；低版本恒 null，截图识别退化为 GENERAL 等其余信号）。 */
        val relativePath: String?,
    )

    /** key = content uri 字符串（与 `MediaAsset.uri` 同源：withAppendedId(EXTERNAL_CONTENT_URI, _ID)） */
    private fun queryImageMeta(resolver: ContentResolver): Map<String, ImageMeta> {
        val contentUri = MediaStore.Images.Media.getContentUri("external")
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        ).apply {
            if (hasRelativePath) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
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
                val pathCol = if (hasRelativePath) {
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                while (cursor.moveToNext()) {
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue
                    val uri = ContentUris.withAppendedId(contentUri, cursor.getLong(idCol)).toString()
                    result[uri] = ImageMeta(
                        sizeBytes = size,
                        modifiedAtMs = cursor.getLong(modifiedCol) * 1_000L,
                        mime = cursor.getString(mimeCol) ?: "image/*",
                        relativePath = if (pathCol >= 0) cursor.getString(pathCol) else null,
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
