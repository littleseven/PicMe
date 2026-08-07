package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mamba.picme.beauty.api.FaceData
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.openImageInputStream
import com.mamba.picme.domain.agent.capability.optimize.recipe.OptimizeRecipeMapper
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.RecipeApplier

/**
 * 抽卡候选渲染器：降采样解码 + 经 [RecipeApplier] 渲染候选 preset。
 *
 * 候选一律在 [CANDIDATE_MAX_EDGE] 小图上渲染与评分（速度），
 * 最终应用的全分辨率渲染走编辑器现有路径，不在本类职责内。
 */
class CandidateRenderer(
    private val context: Context,
    private val recipeApplier: RecipeApplier,
    private val faceData: FaceData? = null
) {

    companion object {
        private const val TAG = "PoLang:OptimizeGacha"
        const val CANDIDATE_MAX_EDGE = 512
    }

    /**
     * 解码长边不超过 [maxEdge] 的降采样 Bitmap；失败返回 null（不抛出）。
     * 支持 content://、file:// 与无 scheme 裸路径（chat 附件持久化格式），见 [openImageInputStream]。
     */
    fun decodeDownscaled(imageUri: String, maxEdge: Int = CANDIDATE_MAX_EDGE): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = openImageInputStream(context, imageUri) ?: run {
                Logger.w(TAG, "decodeDownscaled: openInputStream null: $imageUri")
                return null
            }
            // inJustDecodeBounds 模式下 decodeStream 返回 null 是正常的，只取 outWidth/outHeight
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Logger.w(TAG, "decodeDownscaled: bounds decode failed: $imageUri")
                return null
            }

            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / (sample * 2) >= maxEdge) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val pixelStream = openImageInputStream(context, imageUri) ?: run {
                Logger.w(TAG, "decodeDownscaled: reopen stream null: $imageUri")
                return null
            }
            pixelStream.use { BitmapFactory.decodeStream(it, null, options) } ?: run {
                Logger.w(TAG, "decodeDownscaled: pixels decode null: $imageUri")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "decodeDownscaled failed: $imageUri", e)
            null
        }
    }

    /** 提取整图像素数组（护栏计算用）。 */
    fun extractPixels(bitmap: Bitmap): IntArray {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return px
    }

    /**
     * 渲染单个候选 preset。
     *
     * GPU 失败时 [RecipeApplier] 内部已做 CPU 滤镜兜底；此处只捕获未预期异常，
     * 异常返回 null 由编排层丢弃该卡。
     */
    suspend fun render(candidate: OptimizeCandidate, base: Bitmap, sourceUri: String): Bitmap? {
        return try {
            val recipe = OptimizeRecipeMapper.toEditRecipe(
                preset = candidate.preset,
                sourceUri = sourceUri,
                baseRecipe = EditRecipe(sourceUri = sourceUri)
            )
            recipeApplier.applyGpuEffects(base, recipe, faceData)
        } catch (e: Exception) {
            Logger.e(TAG, "render candidate ${candidate.index} (${candidate.direction}) failed", e)
            null
        }
    }
}
