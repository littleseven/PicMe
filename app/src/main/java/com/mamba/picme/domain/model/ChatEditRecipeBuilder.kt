package com.mamba.picme.domain.model

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.EditParams
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.features.editor.AdjustmentRecipe
import com.mamba.picme.features.editor.EditRecipe

/**
 * 将 LLM 的结构化编辑意图转换为可渲染的 [EditRecipe]。
 *
 * 处理规则：
 * - [EditParams.Absolute]：直接设置绝对值
 * - [EditParams.Delta]：在当前 Recipe 基础上累加/递减
 * - [EditParams.Unchanged]：保持当前值不变
 */
object ChatEditRecipeBuilder {

    /**
     * 瘦脸单次变化上限（全量程的 5%）。
     *
     * 用户在聊天中请求瘦脸时，照片通常已经处于较好状态，只需要微调；
     * 因此将 LLM 返回的 slim_face_delta 限制在 ±5（0~100 全量程的 5%），
     * 避免一次调整过度导致不自然。
     */
    private const val SLIM_FACE_DELTA_MAX = 5f

    fun build(currentRecipe: EditRecipe, command: AgentCommand.EditImage): EditRecipe {
        val params = command.params
        return currentRecipe.copy(
            sourceUri = command.imageUri.takeIf { it.isNotBlank() } ?: currentRecipe.sourceUri,
            beauty = buildBeautySettings(currentRecipe.beauty, params),
            adjustments = buildAdjustments(currentRecipe.adjustments, params),
            colorFilter = buildFilterType(currentRecipe.colorFilter, params),
            styleFilter = buildStyleFilter(currentRecipe.styleFilter, params),
            filterIntensity = params.filterIntensity ?: currentRecipe.filterIntensity
        )
    }

    private fun buildBeautySettings(current: BeautySettings, params: EditParams): BeautySettings {
        return current.copy(
            enabled = true,
            smoothing = resolveAbsolute(current.smoothing, params.smoothing, max = 100f),
            whitening = resolveAbsolute(current.whitening, params.whitening, max = 100f),
            slimFace = resolveAbsolute(current.slimFace, params.slimFace, min = -50f, max = 50f, deltaMax = SLIM_FACE_DELTA_MAX),
            bigEyes = resolveAbsolute(current.bigEyes, params.bigEyes, max = 100f),
            lipColor = resolveAbsolute(current.lipColor, params.lipColor, max = 100f),
            blush = resolveAbsolute(current.blush, params.blush, max = 100f),
            eyebrow = resolveAbsolute(current.eyebrow, params.eyebrow, max = 100f)
        )
    }

    private fun buildAdjustments(current: AdjustmentRecipe, params: EditParams): AdjustmentRecipe {
        return current.copy(
            brightness = resolveRelative(current.brightness, params.brightness, min = -100f, max = 100f),
            exposure = resolveRelative(current.exposure, params.exposure, min = -100f, max = 100f),
            contrast = resolveRelative(current.contrast, params.contrast, min = 0f, max = 200f),
            saturation = resolveRelative(current.saturation, params.saturation, min = 0f, max = 200f),
            temperature = resolveRelative(current.temperature, params.temperature, min = 2000f, max = 8000f),
            tint = resolveRelative(current.tint, params.tint, min = -100f, max = 100f)
        )
    }

    private fun buildFilterType(current: FilterType, params: EditParams): FilterType {
        val value = params.filterName
        return if (value is EditParams.AbsoluteString) resolveFilterType(value.value) else current
    }

    private fun buildStyleFilter(current: StyleFilter, params: EditParams): StyleFilter {
        val value = params.styleName
        return if (value is EditParams.AbsoluteString) resolveStyleFilter(value.value) else current
    }

    private fun resolveAbsolute(
        current: Float,
        value: EditParams.Value,
        min: Float = 0f,
        max: Float = 100f,
        deltaMax: Float? = null
    ): Float = when (value) {
        is EditParams.Absolute -> value.value.coerceIn(min, max)
        is EditParams.Delta -> {
            val clampedDelta = deltaMax?.let { value.value.coerceIn(-it, it) } ?: value.value
            (current + clampedDelta).coerceIn(min, max)
        }
        EditParams.Unchanged -> current
        is EditParams.AbsoluteString -> current
    }

    private fun resolveRelative(
        current: Float,
        value: EditParams.Value,
        min: Float,
        max: Float
    ): Float = when (value) {
        is EditParams.Absolute -> value.value.coerceIn(min, max)
        is EditParams.Delta -> (current + value.value).coerceIn(min, max)
        EditParams.Unchanged -> current
        is EditParams.AbsoluteString -> current
    }

    fun resolveFilterType(name: String): FilterType {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE", "原图", "无" -> FilterType.NONE
            "LEICA_CLASSIC", "徕卡经典" -> FilterType.LEICA_CLASSIC
            "LEICA_VIBRANT", "徕卡鲜艳", "鲜艳" -> FilterType.LEICA_VIBRANT
            "LEICA_BW", "徕卡黑白", "黑白" -> FilterType.LEICA_BW
            "FILM_GOLD", "胶片金", "胶片风" -> FilterType.FILM_GOLD
            "FILM_FUJI", "胶片富士", "富士" -> FilterType.FILM_FUJI
            "VINTAGE", "复古" -> FilterType.VINTAGE
            "COOL", "冷调", "冷色" -> FilterType.COOL
            "WARM", "暖调", "暖色" -> FilterType.WARM
            else -> runCatching { FilterType.valueOf(normalized) }.getOrDefault(FilterType.NONE)
        }
    }

    fun resolveStyleFilter(name: String): StyleFilter {
        val normalized = name.trim().uppercase().replace(" ", "_").replace("-", "_")
        return when (normalized) {
            "NONE" -> StyleFilter.NONE
            "TOON", "卡通" -> StyleFilter.TOON
            "SKETCH", "素描" -> StyleFilter.SKETCH
            "POSTERIZE", "海报" -> StyleFilter.POSTERIZE
            "EMBOSS", "浮雕" -> StyleFilter.EMBOSS
            "CROSSHATCH", "交叉线" -> StyleFilter.CROSSHATCH
            else -> runCatching { StyleFilter.valueOf(normalized) }.getOrDefault(StyleFilter.NONE)
        }
    }
}
