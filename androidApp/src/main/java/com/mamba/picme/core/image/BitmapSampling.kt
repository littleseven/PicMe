package com.mamba.picme.core.image

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * BitmapFactory 降采样工具：统一「先 inJustDecodeBounds 量尺寸 → 算 inSampleSize → 真解码」两遍流程，
 * 避免把全分辨率位图一次性载入内存（Play Console「通过位图降采样来提升应用性能」）。
 *
 * 仅适用于「显示/处理尺寸有上界」的场景（缩略图、模型输入、编辑预览/导出）。
 * GL 纹理（LUT/妆容贴图）必须 1:1 加载，不应走本工具。
 */
object BitmapSampling {

    /**
     * 按「解码后最长边 ≤ [maxDim]」计算 inSampleSize（2 的幂；BitmapFactory 会向下取整到 2 的幂，
     * 故实际尺寸可能略大于 [maxDim]）。尺寸非法时返回 1（不降采样）。
     */
    fun inSampleSizeFor(outWidth: Int, outHeight: Int, maxDim: Int): Int {
        if (maxDim <= 0 || outWidth <= 0 || outHeight <= 0) return 1
        var sample = 1
        val longest = maxOf(outWidth, outHeight)
        while (longest / (sample * 2) >= maxDim) {
            sample *= 2
        }
        return sample
    }

    /** 从 assets 降采样解码（流需开两次：一次量界、一次真解）；失败返回 null。 */
    fun decodeAsset(assets: AssetManager, path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            assets.open(path).use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = inSampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            assets.open(path).use { stream -> BitmapFactory.decodeStream(stream, null, opts) }
        }.getOrNull()
    }

    /** 从文件路径降采样解码；失败返回 null。 */
    fun decodeFile(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = inSampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    /**
     * 从可重复打开的流降采样解码（[opener] 会被调用两次：一次量界、一次真解）；失败返回 null。
     * 适用于 ContentResolver.openInputStream 这类每次返回新流的来源。
     */
    fun decodeStream(opener: () -> InputStream?, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            opener()?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = inSampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            opener()?.use { stream -> BitmapFactory.decodeStream(stream, null, opts) }
        }.getOrNull()
    }
}
