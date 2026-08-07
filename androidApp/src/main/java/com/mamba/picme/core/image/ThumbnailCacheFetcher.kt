package com.mamba.picme.core.image

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.fetch.FetchResult
import coil.request.Options
import coil.size.Dimension
import coil.size.isOriginal

/**
 * Coil [Fetcher]：拦截 content:// 缩略图请求，
 * 优先从 [ThumbnailCache] 返回结果，绕过 Coil 解码管线和请求队列。
 *
 * 在 [ComponentRegistry] 中按注册顺序优先于 ContentUriFetcher。
 * - 缓存命中：直接返回 [DrawableResult]（Coil 跳过 decode 步骤）
 * - 缓存未命中：返回 null，Coil 自动 fallback 到 ContentUriFetcher 正常流程
 *
 * 拦截条件（在 [Factory.create] 中判断）：
 *   1. URI scheme 为 "content"
 *   2. size 为固定像素且宽度 <= 640（缩略图请求）
 *   3. 不拦截 Size.ORIGINAL（原图请求，如 MediaPager）
 */
class ThumbnailCacheFetcher(
    private val uri: Uri,
    private val cache: ThumbnailCache
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cached = cache.get(uri.toString())
        if (cached != null && !cached.isRecycled) {
            // 创建 Bitmap 副本，避免 ThumbnailCache LRU 驱逐或 evict() 回收后
            // Compose 绘制时出现 "Canvas: trying to use a recycled bitmap" 崩溃
            val copiedBitmap = cached.copy(cached.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
            return DrawableResult(
                drawable = BitmapDrawable(copiedBitmap),
                isSampled = true,
                dataSource = DataSource.MEMORY_CACHE
            )
        }
        // 缓存未命中 → 返回 null，Coil 自动 fallback 到 ContentUriFetcher
        return null
    }

    /**
     * 工厂：根据请求参数决定是否创建 [ThumbnailCacheFetcher]。
     * 返回 null 表示不拦截，Coil 走默认 Fetcher 链。
     */
    class Factory(private val cache: ThumbnailCache) : Fetcher.Factory<Uri> {

        companion object {
            /** 超过此尺寸的请求不拦截，走 Coil 原图流程 */
            private const val MAX_INTERCEPT_SIZE_PX = 640
        }

        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            // 仅处理 content:// URI
            if (data.scheme != "content") return null

            // 不拦截原图请求（MediaPager 全屏查看等）
            if (options.size.isOriginal) return null

            // 仅拦截小尺寸缩略图请求
            val width = options.size.width
            if (width is Dimension.Pixels && width.px <= MAX_INTERCEPT_SIZE_PX) {
                return ThumbnailCacheFetcher(data, cache)
            }

            return null
        }
    }
}
