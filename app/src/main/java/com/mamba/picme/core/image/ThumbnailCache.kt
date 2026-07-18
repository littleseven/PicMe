package com.mamba.picme.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 双级缩略图缓存：L1 LRU 内存缓存 + L2 本地磁盘文件。
 *
 * L1（内存）：[LinkedHashMap] access-order LRU，最多 [maxMemoryEntries] 条目，
 *   驱逐时自动回收 Bitmap。使用 [Synchronized] 保护访问。
 * L2（磁盘）：[context.cacheDir]/thumbnails/，JPEG Q80，上限 [maxDiskSizeBytes]。
 *   超出上限时按 lastModified 删除最旧文件至 80% 水位。
 *
 * 生成策略（API 29+）：
 *   [ContentResolver.loadThumbnail]（系统拍照时预生成，极快）
 *   → 失败时 fallback: [MediaStore.Video.Thumbnails.getThumbnail]
 *
 * @param context Application context
 * @param maxMemoryEntries L1 内存缓存最大条目数（默认 200）
 * @param maxDiskSizeBytes L2 磁盘缓存上限（默认 100MB）
 */
class ThumbnailCache(
    private val context: Context,
    private val maxMemoryEntries: Int = 200,
    private val maxDiskSizeBytes: Long = 100L * 1024 * 1024
) {
    companion object {
        private const val TAG = "PoLang:ThumbCache"
        private const val THUMBNAIL_SIZE_PX = 360
        private const val JPEG_QUALITY = 80
        private const val DISK_CACHE_DIR = "thumbnails"
    }

    // 后台写入协程作用域（不阻塞主流程）
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // L1: LRU memory cache — access-order = true，最近访问的条目排到最后
    //     使用 @Synchronized 保证线程安全（操作均为 O(1) HashMap 访问，无挂起）
    //
    //     注意：驱逐时仅移除 Map 引用，不调用 bitmap.recycle()。
    //     ThumbnailCacheFetcher 通过 BitmapDrawable 将同一个 Bitmap 实例传递给 Coil/Compose，
    //     若此处 recycle 则 Compose 绘制时遇到 "Canvas: trying to use a recycled bitmap" 崩溃。
    //     evict()/clear() 中的显式 recycle 保留（调用方确认 Bitmap 不再被引用）。
    private val memoryCache = object : LinkedHashMap<String, Bitmap>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > maxMemoryEntries
        }
    }

    // L2: Disk cache directory
    private val diskCacheDir: File by lazy {
        File(context.cacheDir, DISK_CACHE_DIR).also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    /**
     * 获取缩略图。查询顺序：L1（内存 LRU）→ L2（磁盘文件）→ 系统生成 → null。
     *
     * @param uriString content:// 媒体 URI 字符串
     * @return Bitmap，生成失败时返回 null（调用方应走 Coil 正常流程）
     */
    suspend fun get(uriString: String): Bitmap? {
        // 1. L1: 内存 LRU 缓存（同步访问）
        synchronized(memoryCache) {
            memoryCache[uriString]?.let { cached ->
                if (!cached.isRecycled) return cached
                memoryCache.remove(uriString)
            }
        }

        // 2. L2: 磁盘缓存
        val diskFile = diskFile(uriString)
        if (diskFile.exists()) {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(diskFile.absolutePath)
            }
            if (bitmap != null) {
                synchronized(memoryCache) { memoryCache[uriString] = bitmap }
                return bitmap
            } else {
                // 文件损坏，删除
                diskFile.delete()
            }
        }

        // 3. 系统生成（API 29+）—— 在 IO 线程执行
        val generated = withContext(Dispatchers.IO) {
            generateThumbnail(uriString)
        }
        if (generated != null) {
            synchronized(memoryCache) { memoryCache[uriString] = generated }
            persistToDiskAsync(uriString, generated)
            return generated
        }

        // 4. 兜底 null → 调用方走 Coil
        return null
    }

    /**
     * 缓存 Coil 成功解码的 Bitmap（Interceptor 回填）。
     *
     * 同步写入 L1，异步写入 L2。确保后续同 URI 请求命中缓存。
     */
    fun cacheDecoded(uriString: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(memoryCache) {
            memoryCache[uriString] = bitmap
        }
        persistToDiskAsync(uriString, bitmap)
    }

    /**
     * 批量预加载缩略图（替代 ThumbnailPrefetcher.prefetchWindow）。
     *
     * 在后台 IO 协程中检测缓存缺失并生成，不阻塞主线程。
     * 跳过已在 L1 或 L2 中的 URI。
     *
     * @param uris 需要预加载的 URI 列表
     */
    fun preload(uris: List<String>) {
        if (uris.isEmpty()) return
        writeScope.launch {
            var loaded = 0
            for (uri in uris) {
                synchronized(memoryCache) {
                    if (memoryCache.containsKey(uri)) continue
                }
                if (diskFile(uri).exists()) continue

                val bitmap = generateThumbnail(uri)
                if (bitmap != null) {
                    synchronized(memoryCache) { memoryCache[uri] = bitmap }
                    persistToDiskSync(uri, bitmap)
                    loaded++
                }
            }
            if (loaded > 0) {
                Logger.d(TAG, "Preloaded $loaded thumbnails (${uris.size} requested)")
            }
        }
    }

    /**
     * 清除指定 URI 的全部缓存（媒体删除时调用）。
     */
    fun evict(uriString: String) {
        synchronized(memoryCache) {
            memoryCache.remove(uriString)?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        diskFile(uriString).delete()
    }

    /**
     * 清空全部缓存（调试 / 存储清理）。
     */
    fun clear() {
        synchronized(memoryCache) {
            memoryCache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            memoryCache.clear()
        }
        diskCacheDir.listFiles()?.forEach { it.delete() }
        Logger.d(TAG, "Cache cleared")
    }

    // --- private ---

    /**
     * 使用 [ContentResolver.loadThumbnail] 生成缩略图。
     * 失败时尝试 [MediaStore.Video.Thumbnails.getThumbnail]（视频 fallback）。
     *
     * 必须在 IO 线程调用（ContentResolver 操作是阻塞的）。
     */
    private fun generateThumbnail(uriString: String): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val uri = Uri.parse(uriString)
        val size = Size(THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX)

        return runCatching {
            context.contentResolver.loadThumbnail(uri, size, null)
        }.recoverCatching {
            // loadThumbnail 对部分视频 URI 会失败，尝试视频专用 API
            getVideoThumbnail(uri)
        }.getOrNull()
    }

    /**
     * 视频缩略图 fallback: [MediaStore.Video.Thumbnails.getThumbnail]。
     */
    @Suppress("DEPRECATION")
    private fun getVideoThumbnail(uri: Uri): Bitmap? {
        return try {
            val videoId = uri.lastPathSegment?.toLongOrNull() ?: return null
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                videoId,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 异步写入磁盘缓存（fire-and-forget）。
     */
    private fun persistToDiskAsync(uriString: String, bitmap: Bitmap) {
        writeScope.launch {
            persistToDiskSync(uriString, bitmap)
        }
    }

    /**
     * 同步写入 JPEG 文件。
     */
    private fun persistToDiskSync(uriString: String, bitmap: Bitmap) {
        runCatching {
            ensureDiskCacheSize()
            val file = diskFile(uriString)
            if (!file.exists()) {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            }
        }.onFailure { e ->
            Logger.w(TAG, "Failed to persist thumbnail: ${e.message}")
        }
    }

    /**
     * 磁盘缓存空间管理：超过上限时删除最旧文件至 80% 水位。
     */
    private fun ensureDiskCacheSize() {
        val files = diskCacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= maxDiskSizeBytes) return

        files.sortBy { it.lastModified() }
        val targetSize = (maxDiskSizeBytes * 0.8).toLong()
        for (file in files) {
            if (totalSize <= targetSize) break
            totalSize -= file.length()
            file.delete()
        }
        Logger.d(TAG, "Disk cache evicted: over limit of ${maxDiskSizeBytes / 1024 / 1024}MB")
    }

    /**
     * URI → 磁盘缓存文件映射。
     * 文件名 = SHA-256(uri) 前 16 hex 字符 + .jpg。
     */
    private fun diskFile(uriString: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(uriString.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return File(diskCacheDir, "$hash.jpg")
    }
}
