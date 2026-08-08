package com.mamba.picme.domain.agent.capability.optimize.recipe

import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.domain.agent.capability.optimize.OptimizeResultDto
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.features.editor.AdjustmentRecipe
import com.mamba.picme.features.editor.EditRecipe

/**
 * 将 AI 优化预设映射为编辑器可应用的 EditRecipe
 */
object OptimizeRecipeMapper {

    /**
     * 将 OptimizePreset 映射为 EditRecipe
     *
     * @param preset AI 优化预设
     * @param sourceUri 原图 URI
     * @param baseRecipe 基础 Recipe（保留裁剪等非 AI 参数）
     * @return 可用于编辑器渲染和保存的 EditRecipe
     */
    fun toEditRecipe(
        preset: OptimizePreset,
        sourceUri: String,
        baseRecipe: EditRecipe = EditRecipe(sourceUri = sourceUri)
    ): EditRecipe {
        return baseRecipe.copy(
            sourceUri = sourceUri,
            beauty = toBeautySettings(preset),
            colorFilter = resolveFilterType(preset.filter.colorFilter),
            styleFilter = resolveStyleFilter(preset.filter.styleFilter),
            adjustments = toAdjustmentRecipe(preset)
        )
    }

    /**
     * 将执行结果 DTO 重建为 EditRecipe
     */
    fun toEditRecipe(dto: OptimizeResultDto): EditRecipe {
        return toEditRecipe(
            preset = dto.preset,
            sourceUri = dto.sourceUri
        )
    }

    /**
     * 反向映射：从编辑器已应用的 [EditRecipe] 重建 [OptimizePreset]
     *
     * 用于 Capability 将执行结果序列化为 DTO 时收口映射逻辑，
     * 避免在各调用方手工逐字段拷贝。
     *
     * @param scene 触发优化的场景
     * @param recipe 编辑器实际应用的配方
     */
    fun toOptimizePreset(scene: Scene, recipe: EditRecipe): OptimizePreset {
        val beauty = recipe.beauty
        val adjustments = recipe.adjustments
        return OptimizePreset(
            scene = scene.name,
            beauty = BeautyPreset(
                enabled = beauty.enabled,
                smoothing = beauty.smoothing,
                whitening = beauty.whitening,
                slimFace = beauty.slimFace,
                bigEyes = beauty.bigEyes,
                lipColor = beauty.lipColor,
                blush = beauty.blush
            ),
            filter = FilterPreset(
                colorFilter = recipe.colorFilter.name,
                styleFilter = recipe.styleFilter.name
            ),
            adjustment = AdjustmentPreset(
                brightness = adjustments.brightness,
                exposure = adjustments.exposure,
                contrast = adjustments.contrast,
                saturation = adjustments.saturation,
                temperature = adjustments.temperature,
                tint = adjustments.tint
            )
        )
    }

    /**
     * 反向映射并包装为 [OptimizeResultDto]
     *
     * 内部调用 [toOptimizePreset]，供 Capability 一行完成结果 DTO 构造。
     *
     * @param sourceUri 原图 URI
     * @param scene 触发优化的场景
     * @param explanation 场景说明文案
     * @param recipe 编辑器实际应用的配方
     */
    fun toResultDto(
        sourceUri: String,
        scene: Scene,
        explanation: String,
        recipe: EditRecipe
    ): OptimizeResultDto {
        return OptimizeResultDto(
            sourceUri = sourceUri,
            scene = scene.name,
            explanation = explanation,
            preset = toOptimizePreset(scene, recipe)
        )
    }

    /**
     * 构建场景说明文案
     */
    fun buildExplanation(scene: Scene): String {
        return when (scene) {
            Scene.SELFIE -> "检测到自拍，已适度磨皮美白并提亮肤色"
            Scene.PORTRAIT -> "检测到人像，已优化肤色与光影"
            Scene.GROUP -> "检测到合影，已均匀优化人脸与整体色调"
            Scene.FOOD -> "检测到美食，已增强色彩与食欲感"
            Scene.LANDSCAPE -> "检测到风景，已提升通透度与色彩层次"
            Scene.LOW_LIGHT -> "检测到夜景，已提亮暗部并降低噪感"
            Scene.DOCUMENT -> "检测到文档，已增强文字清晰度"
            Scene.GENERAL -> "已应用通用优化"
        }
    }

    private fun toBeautySettings(preset: OptimizePreset): BeautySettings {
        val beauty = preset.beauty
        return BeautySettings(
            enabled = beauty.enabled,
            smoothing = beauty.smoothing,
            whitening = beauty.whitening,
            slimFace = beauty.slimFace,
            bigEyes = beauty.bigEyes,
            lipColor = beauty.lipColor,
            blush = beauty.blush,
            colorFilter = resolveFilterType(preset.filter.colorFilter),
            styleFilter = resolveStyleFilter(preset.filter.styleFilter)
        )
    }

    private fun toAdjustmentRecipe(preset: OptimizePreset): AdjustmentRecipe {
        val adjustment = preset.adjustment
        return AdjustmentRecipe(
            brightness = adjustment.brightness,
            exposure = adjustment.exposure,
            contrast = adjustment.contrast,
            saturation = adjustment.saturation,
            temperature = adjustment.temperature,
            tint = adjustment.tint
        )
    }

    /**
     * 解析滤镜名称
     */
    fun resolveFilterType(name: String): FilterType {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> FilterType.NONE
            "LEICA_CLASSIC", "徕卡经典", "徕卡经典滤镜" -> FilterType.LEICA_CLASSIC
            "LEICA_VIBRANT", "VIBRANT", "LEICA_VIVID", "VIVID", "徕卡鲜艳", "徕卡鲜艳滤镜" -> FilterType.LEICA_VIBRANT
            "LEICA_BW", "BW", "BLACK_WHITE", "LEICA_MONOCHROME", "MONOCHROME", "徕卡黑白", "徕卡黑白滤镜" -> FilterType.LEICA_BW
            "FILM_GOLD", "胶片金", "胶片金滤镜" -> FilterType.FILM_GOLD
            "FILM_FUJI", "胶片富士", "富士", "胶片富士滤镜" -> FilterType.FILM_FUJI
            "VINTAGE", "RETRO", "OLD", "复古", "怀旧" -> FilterType.VINTAGE
            "COOL", "COLD", "冷色", "冷色调", "冷色滤镜", "冷调", "冷调滤镜", "冷滤镜" -> FilterType.COOL
            "WARM", "暖色", "暖色调", "暖色滤镜", "暖调", "暖调滤镜", "暖滤镜" -> FilterType.WARM
            else -> runCatching { FilterType.valueOf(normalized) }.getOrDefault(FilterType.NONE)
        }
    }

    /**
     * 解析风格特效名称
     */
    fun resolveStyleFilter(name: String): StyleFilter {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> StyleFilter.NONE
            "TOON", "CARTOON", "COMIC", "卡通" -> StyleFilter.TOON
            "SKETCH", "素描" -> StyleFilter.SKETCH
            "POSTERIZE", "POSTER", "海报" -> StyleFilter.POSTERIZE
            "EMBOSS", "浮雕" -> StyleFilter.EMBOSS
            "CROSSHATCH", "CROSS_HATCH", "交叉线" -> StyleFilter.CROSSHATCH
            else -> runCatching { StyleFilter.valueOf(normalized) }.getOrDefault(StyleFilter.NONE)
        }
    }
}
