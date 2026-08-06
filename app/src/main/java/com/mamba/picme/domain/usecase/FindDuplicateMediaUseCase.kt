package com.mamba.picme.domain.usecase

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.DuplicateImageDetector
import com.mamba.picme.domain.model.DuplicateGroup
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * 查找重复图片：取全部照片 → 查 size/mime → 组装 [DuplicateImageDetector.DedupItem]
 * → 调端侧检测器。媒体读取 100% 本地（ContentResolver），不上传。
 *
 * v1 仅照片（视频精确去重为零解码的便宜后续项，不在本次）。
 */
class FindDuplicateMediaUseCase(
    private val repository: MediaRepository,
    private val context: Context
) {
    suspend operator fun invoke(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val allAssets = repository.allMedia.firstOrNull() ?: return@withContext emptyList()
        val cr = context.contentResolver
        val items = allAssets
            .filter { it.type == MediaType.PHOTO }
            .mapNotNull { asset -> asset.toDedupItem(cr) }
        DuplicateImageDetector.findDuplicates(context, items)
    }

    private fun MediaAsset.toDedupItem(cr: ContentResolver): DuplicateImageDetector.DedupItem? {
        val size = fileSizeBytes(cr, uri) ?: return null
        val mime = cr.getType(Uri.parse(uri)) ?: "image/*"
        return DuplicateImageDetector.DedupItem(
            uri = uri,
            sizeBytes = size,
            mime = mime,
            captureDate = captureDate,
            aestheticScore = aestheticScore
        )
    }

    private fun fileSizeBytes(cr: ContentResolver, uri: String): Long? = try {
        cr.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize }
    } catch (e: Exception) {
        null
    }
}
