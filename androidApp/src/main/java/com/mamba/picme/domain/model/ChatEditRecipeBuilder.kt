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
 * - [EditParams.Absolute]：直接设置绝对值（视为用户显式数值请求，不做步进限幅）
 * - [EditParams.Delta]：在当前 Recipe 基础上累加/递减，按参数类别设单次步进上限，
 *   避免模糊请求（"美白一点"）被 LLM 放大成剧烈跳变；大幅调整通过多轮叠加达成
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

    /** 美颜类（磨皮/美白/大眼/唇色/腮红/眉毛）单次 delta 上限（0~100 量程） */
    private const val BEAUTY_DELTA_MAX = 10f

    /** 亮度/曝光单次 delta 上限（-100~100 量程） */
    private const val LIGHT_DELTA_MAX = 15f

    /** 对比度/饱和度单次 delta 上限（0~200 量程） */
    private const val TONE_DELTA_MAX = 15f

    /** 色温单次 delta 上限（开尔文，2000~8000 量程） */
    private const val TEMPERATURE_DELTA_MAX = 500f

    /** 色调单次 delta 上限（-100~100 量程） */
    private const val TINT_DELTA_MAX = 15f

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
            smoothing = resolve(current.smoothing, params.smoothing, max = 100f, deltaMax = BEAUTY_DELTA_MAX),
            whitening = resolve(current.whitening, params.whitening, max = 100f, deltaMax = BEAUTY_DELTA_MAX),
            slimFace = resolve(current.slimFace, params.slimFace, min = -50f, max = 50f, deltaMax = SLIM_FACE_DELTA_MAX),
            bigEyes = resolve(current.bigEyes, params.bigEyes, max = 100f, deltaMax = BEAUTY_DELTA_MAX),
            lipColor = resolve(current.lipColor, params.lipColor, max = 100f, deltaMax = BEAUTY_DELTA_MAX),
            blush = resolve(current.blush, params.blush, max = 100f, deltaMax = BEAUTY_DELTA_MAX),
            eyebrow = resolve(current.eyebrow, params.eyebrow, max = 100f, deltaMax = BEAUTY_DELTA_MAX)
        )
    }

    private fun buildAdjustments(current: AdjustmentRecipe, params: EditParams): AdjustmentRecipe {
        return current.copy(
            brightness = resolve(current.brightness, params.brightness, min = -100f, max = 100f, deltaMax = LIGHT_DELTA_MAX),
            exposure = resolve(current.exposure, params.exposure, min = -100f, max = 100f, deltaMax = LIGHT_DELTA_MAX),
            contrast = resolve(current.contrast, params.contrast, min = 0f, max = 200f, deltaMax = TONE_DELTA_MAX),
            saturation = resolve(current.saturation, params.saturation, min = 0f, max = 200f, deltaMax = TONE_DELTA_MAX),
            temperature = resolve(current.temperature, params.temperature, min = 2000f, max = 8000f, deltaMax = TEMPERATURE_DELTA_MAX),
            tint = resolve(current.tint, params.tint, min = -100f, max = 100f, deltaMax = TINT_DELTA_MAX)
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

    /**
     * 统一解析单个参数：
     * - Absolute：显式数值请求，直接设置（仅做全量程 clamp，不限制步进）
     * - Delta：模糊/相对调整，单次变化量限制在 ±[deltaMax] 内（null = 不限）
     */
    private fun resolve(
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
