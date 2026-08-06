package com.mamba.picme.core.common

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.domain.model.DuplicateGroup

/**
 * 图片去重检测器（端侧）：精确 MD5 + 近似 pHash 两层。
 *
 * 纯算法见 [PerceptualHash]（可 JVM 单测）；本对象只负责 Android I/O
 * （ContentResolver 读取、Bitmap 解码）与分组编排。所有媒体字节 100% 本地处理。
 *
 * 两组内成员按「像素最多 → 评分最高 → 最新」择优排序，最优者在前，
 * 作为默认保留项（UI 取 index 0 保留，删其余）。
 */
object DuplicateImageDetector {

    private const val TAG = "PoLang:Gallery"

    /** 一张待检图片的最小信号。像素宽高由 pHash 解码顺带获得，精确组无需。 */
    data class DedupItem(
        val uri: String,
        val sizeBytes: Long,
        val mime: String,
        val captureDate: Long,
        val aestheticScore: Float? = null,
    )

    /** 解码后附带原始像素面积，用于近似组择优。 */
    private data class Decoded(val item: DedupItem, val pixelArea: Int)

    /**
     * 两层检测：
     * 1. 精确：(size, mime) 分桶 → 桶内 MD5 流式 → MD5 相同成组。
     * 2. 近似：全部图 pHash → 汉明 ≤ [PerceptualHash.SIMILAR_HAMMING_THRESHOLD] 并查集聚类
     *    → 含 ≥2 个不同 MD5 的聚类作为相似组（全部字节相同的聚类已是精确组，跳过）。
     *
     * 本方法内含阻塞 I/O（MD5 流式读 + Bitmap 解码），由调用方包在
     * `withContext(Dispatchers.IO)` 中执行。
     */
    fun findDuplicates(context: Context, items: List<DedupItem>): List<DuplicateGroup> {
        if (items.size < 2) return emptyList()
        val cr = context.contentResolver

        // 1. 一次性算好 MD5（流式，缓存，避免两遍 I/O）
        val md5ByUri = LinkedHashMap<String, String?>()
        for (item in items) md5ByUri[item.uri] = md5FromUri(cr, item.uri)

        // 2. 精确组
        val exact = findExact(items, md5ByUri)
        val exactUris: Set<String> = exact.flatMap { group -> group.fileUris }.toSet()

        // 3. 近似组（排除与精确组完全重合的聚类）
        val near = findNear(cr, items, md5ByUri)
            .filter { group -> group.fileUris.any { uri -> uri !in exactUris } }

        return exact + near
    }

    private fun findExact(
        items: List<DedupItem>,
        md5ByUri: Map<String, String?>
    ): List<DuplicateGroup> {
        val results = mutableListOf<DuplicateGroup>()
        items
            .groupBy { "${it.sizeBytes}|${it.mime}" }
            .filter { it.value.size >= 2 }
            .forEach { (_, bucket) ->
                bucket
                    .mapNotNull { item -> md5ByUri[item.uri]?.let { md5 -> md5 to item } }
                    .groupBy({ it.first }, { it.second })
                    .filter { it.value.size >= 2 }
                    .forEach { (md5, group) ->
                        results += DuplicateGroup(
                            id = "exact:$md5",
                            fileUris = rankExact(group).map { it.uri },
                            isExactDuplicate = true
                        )
                    }
            }
        return results
    }

    private fun findNear(
        cr: ContentResolver,
        items: List<DedupItem>,
        md5ByUri: Map<String, String?>
    ): List<DuplicateGroup> {
        val decoded = mutableListOf<Decoded>()
        val hashes = mutableListOf<Long>()
        for (item in items) {
            val (hash, area) = phashFromUri(cr, item.uri) ?: continue
            hashes += hash
            decoded += Decoded(item, area)
        }
        val results = mutableListOf<DuplicateGroup>()
        for (cluster in PerceptualHash.clusterByHamming(hashes)) {
            val members = cluster.map { idx -> decoded[idx] }
            val distinctMd5 = members.mapNotNull { it.item.uri.let { u -> md5ByUri[u] } }.toSet()
            if (distinctMd5.size < 2) continue // 全部字节相同 → 已是精确组
            results += DuplicateGroup(
                id = "near:${members.first().item.uri}",
                fileUris = rankNear(members).map { it.item.uri },
                isExactDuplicate = false
            )
        }
        return results
    }

    private fun rankExact(items: List<DedupItem>): List<DedupItem> =
        items.sortedWith(
            compareByDescending<DedupItem> { it.aestheticScore ?: -1f }
                .thenByDescending { it.captureDate }
        )

    private fun rankNear(decoded: List<Decoded>): List<Decoded> =
        decoded.sortedWith(
            compareByDescending<Decoded> { it.pixelArea }
                .thenByDescending { it.item.aestheticScore ?: -1f }
                .thenByDescending { it.item.captureDate }
        )

    /** 流式 MD5；失败/不可读返回 null。 */
    private fun md5FromUri(cr: ContentResolver, uri: String): String? = try {
        cr.openInputStream(Uri.parse(uri))?.use { PerceptualHash.md5Hex(it) }
    } catch (e: Exception) {
        Logger.w(TAG, "md5 failed for $uri", e)
        null
    }

    /** 返回 (pHash, 原始 width*height)；解码失败返回 null。降采样到 32×32 后转灰度。 */
    private fun phashFromUri(cr: ContentResolver, uri: String): Pair<Long, Int>? {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            cr.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            Logger.w(TAG, "phash bounds failed for $uri", e)
            return null
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val target = PerceptualHash.PHASH_SIZE
        val sample = maxOf(1, maxOf(w, h) / target)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp: Bitmap = try {
            cr.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        } catch (e: Exception) {
            Logger.w(TAG, "phash decode failed for $uri", e)
            return null
        }
        val scaled = if (bmp.width != target || bmp.height != target) {
            Bitmap.createScaledBitmap(bmp, target, target, false).also { if (it !== bmp) bmp.recycle() }
        } else {
            bmp
        }
        return try {
            val px = IntArray(target * target)
            scaled.getPixels(px, 0, target, 0, 0, target, target)
            val gray = DoubleArray(target * target) { i ->
                val p = px[i]
                0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            }
            PerceptualHash.phash(gray, target) to (w * h)
        } catch (e: Exception) {
            Logger.w(TAG, "phash compute failed for $uri", e)
            null
        } finally {
            scaled.recycle()
        }
    }
}
