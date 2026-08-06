package com.mamba.picme.domain.agent.capability.optimize.gacha

import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 抽卡候选采样器（纯函数，无 Android 依赖，可 JVM 单测）。
 *
 * 以 base preset 为锚点生成 [DEFAULT_COUNT] 个差异化候选：
 * - 卡 0：base 原样（锚点）
 * - 其余：从场景方向池随机取方向模板，叠加 ±30% seed 抖动；
 *   人像类场景（SELFIE/PORTRAIT/GROUP）额外叠加 smoothing/whitening 小幅抖动。
 *
 * 扰动只动调色维度 + smoothing/whitening；形变维度（slimFace/bigEyes 等）保持 base 值，
 * 因为形变依赖人脸关键点，512px 候选小图上不可靠（见 spec §4）。
 */
class CandidateSampler(private val random: Random = Random.Default) {

    /** 方向模板：叠加到 base 上的调色偏移量 */
    data class DirectionTemplate(
        val name: String,
        val brightness: Float = 0f,
        val exposure: Float = 0f,
        val contrast: Float = 0f,
        val saturation: Float = 0f,
        val temperature: Float = 0f,
        val tint: Float = 0f
    )

    companion object {
        const val DEFAULT_COUNT = 4
        private const val MAX_RETRY = 20
        private const val JITTER_RATIO = 0.3f
        private const val BEAUTY_JITTER_SMOOTHING = 10f
        private const val BEAUTY_JITTER_WHITENING = 8f

        private val CLARITY = DirectionTemplate("clarity", contrast = 8f, saturation = 6f)
        private val VIVID = DirectionTemplate("vivid", contrast = 5f, saturation = 10f)
        private val WARM = DirectionTemplate("warm", temperature = 400f, tint = 3f)
        private val COOL = DirectionTemplate("cool", temperature = -400f, brightness = 5f)
        private val BRIGHTEN = DirectionTemplate("brighten", brightness = 6f, exposure = 3f)
        private val CRISP = DirectionTemplate("crisp", contrast = 12f, brightness = 4f)

        private val PORTRAIT_SCENES = setOf(Scene.SELFIE, Scene.PORTRAIT, Scene.GROUP)

        fun directionPool(scene: Scene): List<DirectionTemplate> = when (scene) {
            Scene.FOOD, Scene.LANDSCAPE -> listOf(CLARITY, VIVID, WARM, COOL)
            Scene.SELFIE, Scene.PORTRAIT, Scene.GROUP -> listOf(WARM, COOL, CLARITY, BRIGHTEN)
            Scene.LOW_LIGHT -> listOf(BRIGHTEN, WARM, CLARITY, CRISP)
            Scene.DOCUMENT -> listOf(CLARITY, BRIGHTEN, CRISP)
            Scene.GENERAL -> listOf(CLARITY, WARM, COOL, BRIGHTEN)
        }

        /** 参数量化到整数栅格后的指纹，用于「换一组」去重。 */
        fun fingerprint(preset: OptimizePreset): String {
            val a = preset.adjustment
            val b = preset.beauty
            return listOf(
                a.brightness.roundToInt(), a.exposure.roundToInt(), a.contrast.roundToInt(),
                a.saturation.roundToInt(), (a.temperature / 50).roundToInt(), a.tint.roundToInt(),
                b.smoothing.roundToInt(), b.whitening.roundToInt(),
                preset.filter.colorFilter, preset.filter.styleFilter
            ).joinToString("|")
        }
    }

    /**
     * 生成候选卡。
     *
     * @param base 锚点 preset（卡 0 原样使用）
     * @param scene 当前场景（决定方向池与是否叠加美颜抖动）
     * @param count 候选总数（含锚点卡）
     * @param exclude 已出现过的 fingerprint 集合（「换一组」去重）；锚点卡不受排除约束
     * @return 候选卡列表，首张恒为 base 锚点卡（index 0、direction "base"）。
     * 当方向空间耗尽（exclude 过大或方向池过小）且达到 [MAX_RETRY] 上限时，
     * 返回数量可能少于 [count]，调用方需处理短结果。
     */
    fun sample(
        base: OptimizePreset,
        scene: Scene,
        count: Int = DEFAULT_COUNT,
        exclude: Set<String> = emptySet()
    ): List<OptimizeCandidate> {
        val result = mutableListOf(OptimizeCandidate(index = 0, direction = "base", preset = base))
        val seen = exclude.toMutableSet()
        seen += fingerprint(base)

        val pool = directionPool(scene)
        var retry = 0
        while (result.size < count && retry < MAX_RETRY) {
            retry++
            val template = pool[random.nextInt(pool.size)]
            val jittered = applyTemplate(base, template, beautyJitter = scene in PORTRAIT_SCENES)
            val fp = fingerprint(jittered)
            if (fp in seen) continue
            seen += fp
            result += OptimizeCandidate(index = result.size, direction = template.name, preset = jittered)
        }
        return result
    }

    private fun applyTemplate(
        base: OptimizePreset,
        t: DirectionTemplate,
        beautyJitter: Boolean
    ): OptimizePreset {
        fun jitter(delta: Float): Float =
            if (delta == 0f) 0f else delta * (1f + (random.nextFloat() * 2f - 1f) * JITTER_RATIO)

        val a = base.adjustment
        val adjustment = a.copy(
            brightness = (a.brightness + jitter(t.brightness)).coerceIn(-100f, 100f),
            exposure = (a.exposure + jitter(t.exposure)).coerceIn(-100f, 100f),
            contrast = (a.contrast + jitter(t.contrast)).coerceIn(0f, 200f),
            saturation = (a.saturation + jitter(t.saturation)).coerceIn(0f, 200f),
            temperature = (a.temperature + jitter(t.temperature)).coerceIn(2000f, 8000f),
            tint = (a.tint + jitter(t.tint)).coerceIn(-100f, 100f)
        )
        val b = base.beauty
        val beauty = if (beautyJitter && b.enabled) {
            b.copy(
                smoothing = (b.smoothing + (random.nextFloat() * 2f - 1f) * BEAUTY_JITTER_SMOOTHING)
                    .coerceIn(0f, 100f),
                whitening = (b.whitening + (random.nextFloat() * 2f - 1f) * BEAUTY_JITTER_WHITENING)
                    .coerceIn(0f, 100f)
            )
        } else {
            b
        }
        return base.copy(adjustment = adjustment, beauty = beauty)
    }
}
