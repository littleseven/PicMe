# AI 优化抽卡闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **工作区要求：** 按 AGENTS.md §3.4，执行前先用 superpowers:using-git-worktrees 在 `.worktrees/` 下建隔离工作区与专用分支，所有提交落在该分支。

**Goal:** 为 AI 一键优化引入「抽 4 候选 → 渲染 → NIMA 评分 → 自动选优 + 退化守卫」闭环，支持「换一组」重抽手选，并将选择行为落库。

**Architecture:** 新增 `optimize/gacha/` 包四个组件：`CandidateSampler`（纯函数采样）→ `CandidateRenderer`（512px 小图 + `RecipeApplier` 渲染）→ `OptimizeScorer`（NIMA + 技术护栏 + 退化守卫）→ `OptimizeGachaEngine`（编排）。`AiOptimizeUseCase` 新增 `optimizeWithGacha()`，编辑器接入并新增 `GachaCandidateBar` UI，反馈经 `OptimizeFeedbackLogger` 写入 Room 新表 `optimize_feedback`。

**Tech Stack:** Kotlin、Room、ONNX Runtime（NIMA 已有）、Jetpack Compose、JUnit4 + MockK + kotlinx-coroutines-test。

**Spec:** `docs/superpowers/specs/2026-08-06-ai-optimize-gacha-design.md`（commit `37d02c90`）

---

## File Structure

**新增：**
- `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/GachaModels.kt` — OptimizeCandidate / ScoredCandidate / GachaResult
- `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/CandidateSampler.kt` — 纯函数采样器
- `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/Guardrails.kt` — 纯函数技术护栏
- `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeScorer.kt` — 评分 + 选优 + 守卫
- `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/CandidateRenderer.kt` — 解码 + 渲染
- `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeGachaEngine.kt` — 编排
- `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeFeedbackLogger.kt` — 反馈落库
- `app/src/main/java/com/mamba/picme/domain/aesthetic/AestheticScorer.kt` — 评分器抽象接口
- `app/src/main/java/com/mamba/picme/data/local/entity/OptimizeFeedbackEntity.kt` — Room 实体
- `app/src/main/java/com/mamba/picme/data/local/dao/OptimizeFeedbackDao.kt` — Room DAO
- `app/src/main/java/com/mamba/picme/features/editor/components/GachaCandidateBar.kt` — 抽卡 UI
- 测试：`app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/` 下 4 个测试类

**修改：**
- `app/src/main/java/com/mamba/picme/domain/aesthetic/NimaScorer.kt` — 实现 `AestheticScorer`
- `app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt` — 新增 `optimizeWithGacha()` + `GachaOutcome`
- `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt` — 版本 19→20 + MIGRATION_19_20
- `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModel.kt` — 接抽卡 + 换一组/点选/关闭
- `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModelFactory.kt` — 注入 feedbackLogger
- `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorScreen.kt` — 挂载 GachaCandidateBar
- `app/src/main/java/com/mamba/picme/di/AppContainer.kt` — 组装 gacha 依赖
- `app/src/main/res/values{,-zh,-zh-rCN,-zh-rTW}/strings.xml` — 新文案
- `docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md`、`app/src/main/java/com/mamba/picme/features/editor/AGENTS.md` — 文档同步

---

## Task 1: GachaModels + CandidateSampler（纯函数采样器）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/GachaModels.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/CandidateSampler.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/CandidateSamplerTest.kt`

- [ ] **Step 1: 写数据模型 GachaModels.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.graphics.Bitmap
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset

/**
 * 单张抽卡候选卡。
 *
 * @property index 卡组内序号（0 为 base preset 锚点）
 * @property direction 扰动方向标签（"base" / "clarity" / "warm" / ...），UI 展示与落库用
 * @property preset 候选参数
 */
data class OptimizeCandidate(
    val index: Int,
    val direction: String,
    val preset: OptimizePreset
)

/**
 * 评分后的候选卡。
 *
 * @property nimaScore NIMA 美学分（1~10），null = 未评分（护栏淘汰或推理失败）
 * @property rejected 是否被护栏/评分失败淘汰
 * @property rejectReason 淘汰原因（日志与落库用）
 * @property thumbnail 512px 渲染结果（「换一组」对比条展示用）
 */
data class ScoredCandidate(
    val candidate: OptimizeCandidate,
    val nimaScore: Float?,
    val rejected: Boolean,
    val rejectReason: String? = null,
    val thumbnail: Bitmap? = null
)

/** 抽卡结果 */
sealed interface GachaResult {

    /** 最优候选过退化守卫，可应用 */
    data class Selected(
        val best: ScoredCandidate,
        val all: List<ScoredCandidate>,
        val originalScore: Float?
    ) : GachaResult

    /** 全部候选未显著优于原图，保持原图 */
    data class KeepOriginal(
        val all: List<ScoredCandidate>,
        val originalScore: Float?
    ) : GachaResult

    /** 抽卡不可用（NIMA 未下载 / 解码失败 / 有效卡不足），调用方退回固定预设路径 */
    data object Unavailable : GachaResult
}
```

- [ ] **Step 2: 写失败测试 CandidateSamplerTest.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CandidateSamplerTest {

    private fun basePreset() = OptimizePreset(
        scene = "GENERAL",
        beauty = BeautyPreset(enabled = true, smoothing = 15f, whitening = 10f, slimFace = 5f),
        filter = FilterPreset("NONE", "NONE"),
        adjustment = AdjustmentPreset(
            brightness = 2f, exposure = 0f, contrast = 52f,
            saturation = 100f, temperature = 5000f, tint = 0f
        )
    )

    @Test
    fun `sample returns count candidates with base preset as anchor card`() {
        val base = basePreset()
        val cards = CandidateSampler(Random(42)).sample(base, Scene.GENERAL)

        assertEquals(CandidateSampler.DEFAULT_COUNT, cards.size)
        assertEquals("base", cards[0].direction)
        assertEquals(base, cards[0].preset)
        cards.forEachIndexed { i, card -> assertEquals(i, card.index) }
    }

    @Test
    fun `same seed produces identical candidates`() {
        val base = basePreset()
        val a = CandidateSampler(Random(7)).sample(base, Scene.GENERAL)
        val b = CandidateSampler(Random(7)).sample(base, Scene.GENERAL)
        assertEquals(a, b)
    }

    @Test
    fun `all candidates have distinct fingerprints`() {
        val cards = CandidateSampler(Random(1)).sample(basePreset(), Scene.GENERAL)
        val fps = cards.map { CandidateSampler.fingerprint(it.preset) }
        assertEquals(fps.size, fps.toSet().size)
    }

    @Test
    fun `params stay within legal ranges across many seeds`() {
        val base = basePreset()
        for (seed in 0L until 50L) {
            val cards = CandidateSampler(Random(seed)).sample(base, Scene.SELFIE)
            for (c in cards) {
                val a = c.preset.adjustment
                assertTrue(a.brightness in -100f..100f)
                assertTrue(a.exposure in -100f..100f)
                assertTrue(a.contrast in 0f..200f)
                assertTrue(a.saturation in 0f..200f)
                assertTrue(a.temperature in 2000f..8000f)
                assertTrue(a.tint in -100f..100f)
                assertTrue(c.preset.beauty.smoothing in 0f..100f)
                assertTrue(c.preset.beauty.whitening in 0f..100f)
                // 形变维度不扰动
                assertEquals(base.beauty.slimFace, c.preset.beauty.slimFace, 0.001f)
                assertEquals(base.beauty.bigEyes, c.preset.beauty.bigEyes, 0.001f)
            }
        }
    }

    @Test
    fun `exclude forces new combinations on reroll`() {
        val base = basePreset()
        val first = CandidateSampler(Random(3)).sample(base, Scene.GENERAL)
        val exclude = first.map { CandidateSampler.fingerprint(it.preset) }.toSet()

        val second = CandidateSampler(Random(4)).sample(base, Scene.GENERAL, exclude = exclude)
        val secondNonBase = second.drop(1).map { CandidateSampler.fingerprint(it.preset) }

        assertTrue(secondNonBase.none { it in exclude })
    }

    @Test
    fun `fingerprint quantizes sub-integer differences`() {
        val p1 = basePreset()
        val p2 = basePreset().copy(
            adjustment = basePreset().adjustment.copy(brightness = 2.4f)
        )
        val p3 = basePreset().copy(
            adjustment = basePreset().adjustment.copy(brightness = 3.0f)
        )
        assertEquals(CandidateSampler.fingerprint(p1), CandidateSampler.fingerprint(p2))
        assertNotEquals(CandidateSampler.fingerprint(p1), CandidateSampler.fingerprint(p3))
    }

    @Test
    fun `non-portrait scene does not jitter beauty params`() {
        val base = basePreset()
        val cards = CandidateSampler(Random(9)).sample(base, Scene.LANDSCAPE)
        cards.forEach { c ->
            assertEquals(base.beauty.smoothing, c.preset.beauty.smoothing, 0.001f)
            assertEquals(base.beauty.whitening, c.preset.beauty.whitening, 0.001f)
        }
    }
}
```

- [ ] **Step 3: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.CandidateSamplerTest"`
Expected: FAIL — `CandidateSampler`  unresolved

- [ ] **Step 4: 实现 CandidateSampler.kt**

```kotlin
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
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.CandidateSamplerTest"`
Expected: PASS（7 个用例）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/ app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/
git commit -m "feat(optimize): add gacha candidate sampler with direction templates"
```

---

## Task 2: AestheticScorer 接口 + Guardrails + OptimizeScorer

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/aesthetic/AestheticScorer.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/aesthetic/NimaScorer.kt`（类声明与三个方法加 override）
- Create: `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/Guardrails.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeScorer.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/GuardrailsTest.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeScorerTest.kt`

- [ ] **Step 1: 写失败测试 GuardrailsTest.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardrailsTest {

    private fun pixel(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `highlightClipRatio counts near-white pixels`() {
        // 4 个采样点中 1 个纯白（step=1 全采样）
        val px = intArrayOf(
            pixel(255, 255, 255), pixel(100, 100, 100),
            pixel(50, 50, 50), pixel(200, 200, 200)
        )
        assertEquals(0.25f, Guardrails.highlightClipRatio(px, step = 1), 0.001f)
    }

    @Test
    fun `highlightClipRatio returns 0 for empty array`() {
        assertEquals(0f, Guardrails.highlightClipRatio(intArrayOf()), 0.001f)
    }

    @Test
    fun `meanLuminance of pure white is 1 and pure black is 0`() {
        val white = IntArray(16) { pixel(255, 255, 255) }
        val black = IntArray(16) { pixel(0, 0, 0) }
        assertEquals(1f, Guardrails.meanLuminance(white), 0.001f)
        assertEquals(0f, Guardrails.meanLuminance(black), 0.001f)
    }

    @Test
    fun `check rejects candidate exceeding highlight clip limit`() {
        // 全白图：裁剪率 1.0 > 0.05
        val px = IntArray(64) { pixel(255, 255, 255) }
        val reason = Guardrails.check(px, originalMeanLuminance = 1.0f)
        assertNotNull(reason)
        assertTrue(reason!!.startsWith("highlight_clip"))
    }

    @Test
    fun `check rejects candidate with excessive luminance drift`() {
        // 原图亮度 0.5，候选全白（漂移 100% > 15%）
        val px = IntArray(64) { pixel(255, 255, 255) }
        val reason = Guardrails.check(px, originalMeanLuminance = 0.5f)
        assertNotNull(reason)
        assertTrue(reason!!.startsWith("luminance_drift"))
    }

    @Test
    fun `check passes candidate within guardrails`() {
        // 中灰图 vs 原图亮度 0.5：裁剪率 0，漂移约 0.4%（0.502 vs 0.5）
        val px = IntArray(64) { pixel(128, 128, 128) }
        assertNull(Guardrails.check(px, originalMeanLuminance = 0.5f))
    }
}
```

- [ ] **Step 2: 写失败测试 OptimizeScorerTest.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.graphics.Bitmap
import com.mamba.picme.domain.aesthetic.AestheticScorer
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizeScorerTest {

    private fun preset() = OptimizePreset(
        scene = "GENERAL",
        beauty = BeautyPreset(),
        filter = FilterPreset(),
        adjustment = AdjustmentPreset()
    )

    private fun candidate(index: Int) =
        OptimizeCandidate(index = index, direction = "d$index", preset = preset())

    private fun grayPx() = IntArray(64) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }

    @Test
    fun `scoreCandidate rejects card failing guardrails without calling scorer`() {
        val scorer = mockk<AestheticScorer>()
        val whitePx = IntArray(64) { (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255 }

        val result = OptimizeScorer(scorer).scoreCandidate(
            candidate = candidate(0),
            rendered = mockk<Bitmap>(),
            renderedPx = whitePx,
            originalMeanLuminance = 1.0f   // 亮度漂移为 0，确保命中的是高光裁剪
        )

        assertTrue(result.rejected)
        assertEquals("highlight_clip:1.0", result.rejectReason)
        assertNull(result.nimaScore)
    }

    @Test
    fun `scoreCandidate marks card rejected when nima returns null`() {
        val scorer = mockk<AestheticScorer>()
        every { scorer.score(any()) } returns null

        val result = OptimizeScorer(scorer).scoreCandidate(
            candidate = candidate(0),
            rendered = mockk<Bitmap>(),
            renderedPx = grayPx(),
            originalMeanLuminance = 0.5f
        )

        assertTrue(result.rejected)
        assertEquals("nima_failed", result.rejectReason)
    }

    @Test
    fun `select returns Selected when best exceeds original plus threshold`() {
        val scorer = mockk<AestheticScorer>()
        val scored = listOf(
            ScoredCandidate(candidate(0), nimaScore = 5.0f, rejected = false),
            ScoredCandidate(candidate(1), nimaScore = 5.3f, rejected = false),
            ScoredCandidate(candidate(2), nimaScore = 4.8f, rejected = false)
        )

        val result = OptimizeScorer(scorer).select(scored, originalScore = 5.0f)

        assertTrue(result is GachaResult.Selected)
        assertEquals(1, (result as GachaResult.Selected).best.candidate.index)
    }

    @Test
    fun `select returns KeepOriginal when improvement below threshold`() {
        val scorer = mockk<AestheticScorer>()
        val scored = listOf(
            ScoredCandidate(candidate(0), nimaScore = 5.0f, rejected = false),
            ScoredCandidate(candidate(1), nimaScore = 5.04f, rejected = false)
        )

        val result = OptimizeScorer(scorer).select(scored, originalScore = 5.0f)

        assertTrue(result is GachaResult.KeepOriginal)
    }

    @Test
    fun `select skips guard when original score unavailable`() {
        val scorer = mockk<AestheticScorer>()
        val scored = listOf(
            ScoredCandidate(candidate(0), nimaScore = 5.0f, rejected = false),
            ScoredCandidate(candidate(1), nimaScore = 5.0f, rejected = false)
        )

        val result = OptimizeScorer(scorer).select(scored, originalScore = null)

        assertTrue(result is GachaResult.Selected)
    }

    @Test
    fun `select returns Unavailable when valid cards below minimum`() {
        val scorer = mockk<AestheticScorer>()
        val scored = listOf(
            ScoredCandidate(candidate(0), nimaScore = 5.0f, rejected = false),
            ScoredCandidate(candidate(1), nimaScore = null, rejected = true, rejectReason = "nima_failed")
        )

        val result = OptimizeScorer(scorer).select(scored, originalScore = 5.0f)

        assertEquals(GachaResult.Unavailable, result)
    }
}
```

- [ ] **Step 3: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.*"`
Expected: FAIL — `Guardrails` / `OptimizeScorer` / `AestheticScorer` unresolved

- [ ] **Step 4: 实现 AestheticScorer.kt**

```kotlin
package com.mamba.picme.domain.aesthetic

import android.graphics.Bitmap

/**
 * 整图美学评分器抽象。
 *
 * 抽卡链路（optimize/gacha）依赖本接口而非具体实现，便于单测 mock 与未来替换评分模型。
 */
interface AestheticScorer {

    /** 初始化模型；不可用（模型未下载等）返回 false，调用方走降级。 */
    suspend fun initialize(): Boolean

    /** 给整图打分，分数越高越美；推理失败返回 null。 */
    fun score(bitmap: Bitmap): Float?

    /** 释放模型资源。 */
    fun release()
}
```

- [ ] **Step 5: NimaScorer 实现接口**

修改 `app/src/main/java/com/mamba/picme/domain/aesthetic/NimaScorer.kt`：

```kotlin
// 类声明改为：
class NimaScorer(private val context: Context) : AestheticScorer {

// 三个方法签名加 override：
    override suspend fun initialize(): Boolean {
    override fun score(bitmap: Bitmap): Float? {
    override fun release() {
```

其余实现不变。

- [ ] **Step 6: 实现 Guardrails.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import kotlin.math.abs

/**
 * 候选渲染结果的技术护栏（纯函数，操作像素数组，可 JVM 单测）。
 *
 * NIMA 偏好高对比高饱和，护栏用于淘汰过曝/亮度异常漂移的候选（见 spec §5.1）。
 * 阈值均为初始值，按离线样张验证结果调整。
 */
object Guardrails {

    /** 高光裁剪率上限：r,g,b 均 >= 250 的采样像素占比超过该值则淘汰 */
    const val HIGHLIGHT_CLIP_LIMIT = 0.05f

    /** 平均亮度漂移上限：候选均亮度相对原图漂移超过该比例则淘汰 */
    const val LUMINANCE_DRIFT_LIMIT = 0.15f

    /** 高光裁剪率，∈[0,1]；[step] 为采样步长（默认每 4 像素采 1 个）。 */
    fun highlightClipRatio(px: IntArray, step: Int = 4): Float {
        if (px.isEmpty()) return 0f
        var clipped = 0
        var sampled = 0
        for (i in px.indices step step) {
            sampled++
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r >= 250 && g >= 250 && b >= 250) clipped++
        }
        return if (sampled == 0) 0f else clipped.toFloat() / sampled
    }

    /** 平均亮度（Rec.601 luma 归一化到 [0,1]）。 */
    fun meanLuminance(px: IntArray, step: Int = 4): Float {
        if (px.isEmpty()) return 0f
        var sum = 0.0
        var sampled = 0
        for (i in px.indices step step) {
            sampled++
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        }
        return if (sampled == 0) 0f else (sum / sampled).toFloat()
    }

    /**
     * 护栏检查。
     *
     * @param candidatePx 候选渲染结果像素
     * @param originalMeanLuminance 原图平均亮度
     * @return null 表示通过；否则为淘汰原因（日志与落库用）
     */
    fun check(candidatePx: IntArray, originalMeanLuminance: Float): String? {
        val clip = highlightClipRatio(candidatePx)
        if (clip > HIGHLIGHT_CLIP_LIMIT) return "highlight_clip:$clip"
        val lum = meanLuminance(candidatePx)
        if (originalMeanLuminance > 0f &&
            abs(lum - originalMeanLuminance) / originalMeanLuminance > LUMINANCE_DRIFT_LIMIT
        ) {
            return "luminance_drift:$lum"
        }
        return null
    }
}
```

- [ ] **Step 7: 实现 OptimizeScorer.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.graphics.Bitmap
import com.mamba.picme.domain.aesthetic.AestheticScorer

/**
 * 抽卡评分器：技术护栏 → NIMA 打分 → 选优 + 退化守卫。
 */
class OptimizeScorer(private val scorer: AestheticScorer) {

    companion object {
        /** 退化守卫阈值：最优候选相对原图的最小 NIMA 提升（初始值，离线样张校准） */
        const val MIN_IMPROVEMENT = 0.05f

        /** 有效候选卡下限，低于则判定抽卡不可用 */
        const val MIN_VALID_CARDS = 2
    }

    /**
     * 给单张渲染结果评分：先护栏后 NIMA（护栏淘汰的卡不再打分）。
     *
     * @param rendered 候选渲染结果（同时作为 thumbnail 带回）
     * @param renderedPx [rendered] 的像素数组（护栏计算用）
     * @param originalMeanLuminance 原图平均亮度
     */
    fun scoreCandidate(
        candidate: OptimizeCandidate,
        rendered: Bitmap,
        renderedPx: IntArray,
        originalMeanLuminance: Float
    ): ScoredCandidate {
        val rejectReason = Guardrails.check(renderedPx, originalMeanLuminance)
        if (rejectReason != null) {
            return ScoredCandidate(
                candidate = candidate,
                nimaScore = null,
                rejected = true,
                rejectReason = rejectReason,
                thumbnail = rendered
            )
        }
        val score = scorer.score(rendered)
        return ScoredCandidate(
            candidate = candidate,
            nimaScore = score,
            rejected = score == null,
            rejectReason = if (score == null) "nima_failed" else null,
            thumbnail = rendered
        )
    }

    /**
     * 选优 + 退化守卫。
     *
     * - 有效卡（未淘汰且有分）< [MIN_VALID_CARDS] → [GachaResult.Unavailable]
     * - 原图分可用且最优卡提升 ≤ [MIN_IMPROVEMENT] → [GachaResult.KeepOriginal]
     * - 原图分不可用 → 跳过守卫直接选优（spec §9）
     */
    fun select(all: List<ScoredCandidate>, originalScore: Float?): GachaResult {
        val valid = all.filter { !it.rejected && it.nimaScore != null }
        if (valid.size < MIN_VALID_CARDS) return GachaResult.Unavailable
        val best = valid.maxBy { it.nimaScore!! }
        return if (originalScore != null && best.nimaScore!! <= originalScore + MIN_IMPROVEMENT) {
            GachaResult.KeepOriginal(all = all, originalScore = originalScore)
        } else {
            GachaResult.Selected(best = best, all = all, originalScore = originalScore)
        }
    }
}
```

- [ ] **Step 8: 跑测试确认通过（含 NimaScorer 既有测试不回归）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.*" --tests "com.mamba.picme.domain.aesthetic.*"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/aesthetic/ app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/ app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/
git commit -m "feat(optimize): add gacha scorer with guardrails and regression guard"
```

---

## Task 3: CandidateRenderer（小图解码 + 渲染）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/CandidateRenderer.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/CandidateRendererTest.kt`

- [ ] **Step 1: 写失败测试 CandidateRendererTest.kt**

`decodeDownscaled` 依赖 Android ContentResolver，不在 JVM 单测覆盖（真机闭环验证）；单测只覆盖 `render` 的委托与异常兜底。

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.content.Context
import android.graphics.Bitmap
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.features.editor.RecipeApplier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CandidateRendererTest {

    private val imageUri = "file:///test.jpg"

    private fun candidate() = OptimizeCandidate(
        index = 1,
        direction = "warm",
        preset = OptimizePreset(
            scene = "GENERAL",
            beauty = BeautyPreset(),
            filter = FilterPreset(),
            adjustment = AdjustmentPreset(temperature = 5400f)
        )
    )

    @Test
    fun `render delegates to recipeApplier and returns its output`() = runTest {
        val applier = mockk<RecipeApplier>()
        val base = mockk<Bitmap>()
        val rendered = mockk<Bitmap>()
        coEvery { applier.applyGpuEffects(base, any(), null) } returns rendered

        val renderer = CandidateRenderer(mockk<Context>(), applier, faceData = null)
        val result = renderer.render(candidate(), base, imageUri)

        assertEquals(rendered, result)
        coVerify(exactly = 1) { applier.applyGpuEffects(base, any(), null) }
    }

    @Test
    fun `render returns null when recipeApplier throws`() = runTest {
        val applier = mockk<RecipeApplier>()
        val base = mockk<Bitmap>()
        coEvery { applier.applyGpuEffects(base, any(), null) } throws RuntimeException("gpu dead")

        val renderer = CandidateRenderer(mockk<Context>(), applier, faceData = null)
        val result = renderer.render(candidate(), base, imageUri)

        assertNull(result)
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.CandidateRendererTest"`
Expected: FAIL — `CandidateRenderer` unresolved

- [ ] **Step 3: 实现 CandidateRenderer.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mamba.picme.beauty.api.FaceData
import com.mamba.picme.core.common.Logger
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
     * 支持 content:// 与 file:// URI。
     */
    fun decodeDownscaled(imageUri: String, maxEdge: Int = CANDIDATE_MAX_EDGE): Bitmap? {
        return try {
            val uri = Uri.parse(imageUri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longEdge / (sample * 2) >= maxEdge) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/CandidateRenderer.kt app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/CandidateRendererTest.kt
git commit -m "feat(optimize): add gacha candidate renderer (512px decode + recipe render)"
```

---

## Task 4: OptimizeGachaEngine（编排）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeGachaEngine.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeGachaEngineTest.kt`

- [ ] **Step 1: 写失败测试 OptimizeGachaEngineTest.kt**

引擎测试用真实 `CandidateSampler(Random(seed))` + 真实 `OptimizeScorer`（mock 内部 `AestheticScorer`），mock `CandidateRenderer` 隔离 Android 依赖。

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.graphics.Bitmap
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.aesthetic.AestheticScorer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class OptimizeGachaEngineTest {

    private val imageUri = "file:///test.jpg"

    private fun basePreset() = OptimizePreset(
        scene = "GENERAL",
        beauty = BeautyPreset(enabled = true, smoothing = 15f, whitening = 10f),
        filter = FilterPreset("NONE", "NONE"),
        adjustment = AdjustmentPreset(contrast = 52f, saturation = 100f)
    )

    /** 中灰像素（过护栏，亮度 0.502） */
    private fun grayPx() = IntArray(64) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }

    /**
     * 装配引擎：base 图打 [originalScore]，候选渲染图统一打 [candidateScore]。
     * base 与 rendered 用不同 mock 实例以区分打分对象。
     */
    private fun engine(
        originalScore: Float?,
        candidateScore: Float?,
        renderNullFor: Set<Int> = emptySet()
    ): OptimizeGachaEngine {
        val baseBitmap = mockk<Bitmap>()
        val scorer = mockk<AestheticScorer>()
        coEvery { scorer.initialize() } returns true
        // 用引用匹配区分原图与候选渲染图（两个 matcher 不相交，无顺序依赖）
        every { scorer.score(match { it === baseBitmap }) } returns originalScore
        every { scorer.score(match { it !== baseBitmap }) } answers { candidateScore }

        val renderer = mockk<CandidateRenderer>()
        every { renderer.decodeDownscaled(imageUri, any()) } returns baseBitmap
        every { renderer.extractPixels(any()) } returns grayPx()
        coEvery { renderer.render(any(), baseBitmap, imageUri) } answers {
            val c = firstArg<OptimizeCandidate>()
            if (c.index in renderNullFor) null else mockk<Bitmap>()
        }

        return OptimizeGachaEngine(
            sampler = CandidateSampler(Random(42)),
            renderer = renderer,
            optimizeScorer = OptimizeScorer(scorer),
            aestheticScorer = scorer
        )
    }

    @Test
    fun `run returns Selected when candidate beats original`() = runTest {
        val result = engine(originalScore = 5.0f, candidateScore = 5.4f)
            .run(imageUri, Scene.GENERAL, basePreset())

        assertTrue(result is GachaResult.Selected)
        assertEquals(4, (result as GachaResult.Selected).all.size)
    }

    @Test
    fun `run returns KeepOriginal when no candidate beats original plus threshold`() = runTest {
        val result = engine(originalScore = 5.4f, candidateScore = 5.0f)
            .run(imageUri, Scene.GENERAL, basePreset())

        assertTrue(result is GachaResult.KeepOriginal)
    }

    @Test
    fun `run returns Unavailable when scorer not initialized`() = runTest {
        val scorer = mockk<AestheticScorer>()
        coEvery { scorer.initialize() } returns false
        val engine = OptimizeGachaEngine(
            sampler = CandidateSampler(Random(1)),
            renderer = mockk(),
            optimizeScorer = OptimizeScorer(scorer),
            aestheticScorer = scorer
        )

        assertEquals(GachaResult.Unavailable, engine.run(imageUri, Scene.GENERAL, basePreset()))
    }

    @Test
    fun `run returns Unavailable when decode fails`() = runTest {
        val scorer = mockk<AestheticScorer>()
        coEvery { scorer.initialize() } returns true
        val renderer = mockk<CandidateRenderer>()
        every { renderer.decodeDownscaled(imageUri, any()) } returns null
        val engine = OptimizeGachaEngine(
            sampler = CandidateSampler(Random(1)),
            renderer = renderer,
            optimizeScorer = OptimizeScorer(scorer),
            aestheticScorer = scorer
        )

        assertEquals(GachaResult.Unavailable, engine.run(imageUri, Scene.GENERAL, basePreset()))
    }

    @Test
    fun `run returns Unavailable when fewer than 2 cards render`() = runTest {
        // 4 张卡中 3 张渲染失败 → 有效卡 1 < MIN_VALID_CARDS
        val result = engine(originalScore = 5.0f, candidateScore = 5.4f, renderNullFor = setOf(1, 2, 3))
            .run(imageUri, Scene.GENERAL, basePreset())

        assertEquals(GachaResult.Unavailable, result)
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeGachaEngineTest"`
Expected: FAIL — `OptimizeGachaEngine` unresolved

- [ ] **Step 3: 实现 OptimizeGachaEngine.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.aesthetic.AestheticScorer

/**
 * 抽卡编排引擎：采样 → 渲染 → 评分 → 选优/退化守卫。
 *
 * 所有媒体处理 100% 端侧（[PRIVACY] 红线）：小图解码、GPU 渲染、NIMA 评分均不出设备。
 */
class OptimizeGachaEngine(
    private val sampler: CandidateSampler,
    private val renderer: CandidateRenderer,
    private val optimizeScorer: OptimizeScorer,
    private val aestheticScorer: AestheticScorer
) {

    companion object {
        private const val TAG = "PoLang:OptimizeGacha"
    }

    /**
     * 执行一次抽卡。
     *
     * @param imageUri 原图 URI
     * @param scene 场景（决定采样方向池）
     * @param basePreset 锚点 preset（卡 0 原样使用）
     * @param count 候选总数（含锚点卡）
     * @param exclude 「换一组」时需排除的 fingerprint 集合
     */
    suspend fun run(
        imageUri: String,
        scene: Scene,
        basePreset: OptimizePreset,
        count: Int = CandidateSampler.DEFAULT_COUNT,
        exclude: Set<String> = emptySet()
    ): GachaResult {
        if (!aestheticScorer.initialize()) {
            Logger.w(TAG, "aesthetic scorer unavailable, gacha skipped")
            return GachaResult.Unavailable
        }
        val base = renderer.decodeDownscaled(imageUri) ?: return GachaResult.Unavailable
        val originalPx = renderer.extractPixels(base)
        val originalLuminance = Guardrails.meanLuminance(originalPx)
        val originalScore = aestheticScorer.score(base)

        val candidates = sampler.sample(basePreset, scene, count, exclude)
        val scored = candidates.mapNotNull { candidate ->
            val rendered = renderer.render(candidate, base, imageUri) ?: return@mapNotNull null
            val px = renderer.extractPixels(rendered)
            optimizeScorer.scoreCandidate(candidate, rendered, px, originalLuminance)
        }
        if (scored.size < OptimizeScorer.MIN_VALID_CARDS) {
            Logger.w(TAG, "only ${scored.size} cards rendered, gacha unavailable")
            return GachaResult.Unavailable
        }

        val result = optimizeScorer.select(scored, originalScore)
        Logger.i(
            TAG,
            "gacha done: scene=${scene.name}, cards=${scored.size}, " +
                "original=$originalScore, result=${result::class.simpleName}"
        )
        return result
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.*"`
Expected: PASS（gacha 包全部用例）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeGachaEngine.kt app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeGachaEngineTest.kt
git commit -m "feat(optimize): add gacha engine orchestrating sample-render-score-select"
```

---

## Task 5: OptimizeFeedbackLogger + Room 表 optimize_feedback

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/local/entity/OptimizeFeedbackEntity.kt`
- Create: `app/src/main/java/com/mamba/picme/data/local/dao/OptimizeFeedbackDao.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeFeedbackLogger.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`（entities + version + dao + MIGRATION_19_20）
- Test: `app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeFeedbackLoggerTest.kt`

- [ ] **Step 1: 写失败测试 OptimizeFeedbackLoggerTest.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import com.mamba.picme.data.local.dao.OptimizeFeedbackDao
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptimizeFeedbackLoggerTest {

    private fun scored(index: Int, score: Float?) = ScoredCandidate(
        candidate = OptimizeCandidate(
            index = index,
            direction = "d$index",
            preset = OptimizePreset(
                scene = "GENERAL",
                beauty = BeautyPreset(smoothing = 15f, whitening = 10f),
                filter = FilterPreset("WARM", "NONE"),
                adjustment = AdjustmentPreset(brightness = 5f, contrast = 60f, temperature = 5400f)
            )
        ),
        nimaScore = score,
        rejected = score == null,
        rejectReason = if (score == null) "nima_failed" else null
    )

    @Test
    fun `log inserts entity with hashed image key and candidates json`() = runTest {
        val dao = mockk<OptimizeFeedbackDao>()
        val slot = slot<com.mamba.picme.data.local.entity.OptimizeFeedbackEntity>()
        coEvery { dao.insert(capture(slot)) } just runs

        OptimizeFeedbackLogger(dao).log(
            imageUri = "file:///private/user/photo.jpg",
            scene = com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene.GENERAL,
            all = listOf(scored(0, 5.0f), scored(1, null)),
            selectedIndex = 0,
            source = OptimizeFeedbackLogger.SOURCE_AUTO
        )

        coVerify(exactly = 1) { dao.insert(any()) }
        val entity = slot.captured
        // image_key 是哈希，不含原始路径
        assertEquals(16, entity.imageKey.length)
        assertTrue(!entity.imageKey.contains("photo"))
        assertEquals("GENERAL", entity.scene)
        assertEquals(0, entity.selectedIndex)
        assertEquals("auto", entity.selectionSource)
        // candidates_json 含两张卡的参数与分数
        assertTrue(entity.candidatesJson.contains("\"direction\":\"d0\""))
        assertTrue(entity.candidatesJson.contains("\"nimaScore\":5.0"))
        assertTrue(entity.candidatesJson.contains("\"rejected\":true"))
        assertTrue(entity.candidatesJson.contains("\"temperature\":5400.0"))
    }

    @Test
    fun `log is no-op when dao is null`() = runTest {
        // 不抛异常即通过
        OptimizeFeedbackLogger(null).log(
            imageUri = "file:///a.jpg",
            scene = com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene.GENERAL,
            all = emptyList(),
            selectedIndex = -1,
            source = OptimizeFeedbackLogger.SOURCE_AUTO
        )
    }

    @Test
    fun `log swallows dao exceptions`() = runTest {
        val dao = mockk<OptimizeFeedbackDao>()
        coEvery { dao.insert(any()) } throws RuntimeException("db locked")

        // 不抛异常即通过
        OptimizeFeedbackLogger(dao).log(
            imageUri = "file:///a.jpg",
            scene = com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene.GENERAL,
            all = listOf(scored(0, 5.0f)),
            selectedIndex = 0,
            source = OptimizeFeedbackLogger.SOURCE_AUTO
        )
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLoggerTest"`
Expected: FAIL — `OptimizeFeedbackLogger` / `OptimizeFeedbackDao` / `OptimizeFeedbackEntity` unresolved

- [ ] **Step 3: 实现 OptimizeFeedbackEntity.kt**

```kotlin
package com.mamba.picme.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 优化抽卡反馈记录（见 spec §7）。
 *
 * v1 只记录不学习；Phase 2 按 scene 聚合 user pick 相对 base 的参数偏移，
 * 用于收窄采样中心（个性化）。
 */
@Entity(tableName = "optimize_feedback")
data class OptimizeFeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    /** 图片 URI 的 SHA-256 前 16 位（不存原始路径） */
    @ColumnInfo(name = "image_key") val imageKey: String,
    /** Scene 枚举名 */
    @ColumnInfo(name = "scene") val scene: String,
    /** 4 卡参数 + NIMA 分 + 护栏淘汰标记（JSON 数组） */
    @ColumnInfo(name = "candidates_json") val candidatesJson: String,
    /** 选中的卡序号；-1 = KeepOriginal */
    @ColumnInfo(name = "selected_index") val selectedIndex: Int,
    /** auto（NIMA 选优）/ user（换一组手选）/ dismiss（换一组后未选关闭） */
    @ColumnInfo(name = "selection_source") val selectionSource: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
```

- [ ] **Step 4: 实现 OptimizeFeedbackDao.kt**

```kotlin
package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mamba.picme.data.local.entity.OptimizeFeedbackEntity

@Dao
interface OptimizeFeedbackDao {

    @Insert
    suspend fun insert(feedback: OptimizeFeedbackEntity)

    @Query("SELECT * FROM optimize_feedback ORDER BY created_at DESC")
    suspend fun getAll(): List<OptimizeFeedbackEntity>

    /** Phase 2 个性化用：取某场景的用户手选记录 */
    @Query("SELECT * FROM optimize_feedback WHERE scene = :scene AND selection_source = 'user'")
    suspend fun getUserPicksForScene(scene: String): List<OptimizeFeedbackEntity>
}
```

- [ ] **Step 5: 实现 OptimizeFeedbackLogger.kt**

```kotlin
package com.mamba.picme.domain.agent.capability.optimize.gacha

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.dao.OptimizeFeedbackDao
import com.mamba.picme.data.local.entity.OptimizeFeedbackEntity
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 抽卡反馈落库（spec §7）。
 *
 * 自动选优（AiOptimizeUseCase）与用户手选/关闭（PhotoEditorViewModel）共用。
 * 落库失败只记日志，绝不影响主流程。
 */
class OptimizeFeedbackLogger(private val dao: OptimizeFeedbackDao?) {

    companion object {
        private const val TAG = "PoLang:OptimizeGacha"
        const val SOURCE_AUTO = "auto"
        const val SOURCE_USER = "user"
        const val SOURCE_DISMISS = "dismiss"
    }

    /**
     * @param selectedIndex 选中的卡序号；-1 = KeepOriginal / 未选择
     * @param source [SOURCE_AUTO] / [SOURCE_USER] / [SOURCE_DISMISS]
     */
    suspend fun log(
        imageUri: String,
        scene: Scene,
        all: List<ScoredCandidate>,
        selectedIndex: Int,
        source: String
    ) {
        val d = dao ?: return
        try {
            d.insert(
                OptimizeFeedbackEntity(
                    imageKey = imageKey(imageUri),
                    scene = scene.name,
                    candidatesJson = candidatesToJson(all),
                    selectedIndex = selectedIndex,
                    selectionSource = source,
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Logger.e(TAG, "feedback insert failed", e)
        }
    }

    /** 图片 URI → SHA-256 前 16 位（不存原始路径，spec §7）。 */
    fun imageKey(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 候选卡组 → JSON（含参数、NIMA 分、护栏淘汰标记，供 Phase 2 个性化消费）。 */
    fun candidatesToJson(all: List<ScoredCandidate>): String {
        val arr = JSONArray()
        all.forEach { sc ->
            val p = sc.candidate.preset
            arr.put(
                JSONObject().apply {
                    put("index", sc.candidate.index)
                    put("direction", sc.candidate.direction)
                    put("nimaScore", sc.nimaScore?.toDouble() ?: JSONObject.NULL)
                    put("rejected", sc.rejected)
                    put("rejectReason", sc.rejectReason ?: JSONObject.NULL)
                    put("beauty", JSONObject().apply {
                        put("smoothing", p.beauty.smoothing.toDouble())
                        put("whitening", p.beauty.whitening.toDouble())
                    })
                    put("filter", p.filter.colorFilter)
                    put("adjustment", JSONObject().apply {
                        put("brightness", p.adjustment.brightness.toDouble())
                        put("exposure", p.adjustment.exposure.toDouble())
                        put("contrast", p.adjustment.contrast.toDouble())
                        put("saturation", p.adjustment.saturation.toDouble())
                        put("temperature", p.adjustment.temperature.toDouble())
                        put("tint", p.adjustment.tint.toDouble())
                    })
                }
            )
        }
        return arr.toString()
    }
}
```

- [ ] **Step 6: AppDatabase 升级 19 → 20**

修改 `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`：

1. 新增 import：

```kotlin
import com.mamba.picme.data.local.dao.OptimizeFeedbackDao
import com.mamba.picme.data.local.entity.OptimizeFeedbackEntity
```

2. `@Database` 的 `entities` 数组末尾（`ChatImageCacheEntity::class` 之后）加：

```kotlin
        ChatImageCacheEntity::class,
        OptimizeFeedbackEntity::class
```

3. `version = 19` 改为 `version = 20`

4. 抽象方法区加：

```kotlin
    abstract fun optimizeFeedbackDao(): OptimizeFeedbackDao
```

5. `.addMigrations(...)` 列表末尾加 `MIGRATION_19_20`

6. companion object 末尾（`MIGRATION_18_19` 之后）加：

```kotlin
        /**
         * Migration 19 → 20：新增 optimize_feedback 表（AI 优化抽卡反馈，见 spec §7）
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `optimize_feedback` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `image_key` TEXT NOT NULL,
                        `scene` TEXT NOT NULL,
                        `candidates_json` TEXT NOT NULL,
                        `selected_index` INTEGER NOT NULL,
                        `selection_source` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
```

- [ ] **Step 7: 跑测试 + 编译确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.optimize.gacha.*" && ./gradlew :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL（Room 编译期校验实体与建表 SQL 一致性——若 migration SQL 与实体不符，运行时才暴露，真机闭环时重点验证升级路径）

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/local/ app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeFeedbackLogger.kt app/src/test/java/com/mamba/picme/domain/agent/capability/optimize/gacha/OptimizeFeedbackLoggerTest.kt
git commit -m "feat(optimize): add optimize_feedback table and gacha feedback logger"
```

---

## Task 6: AiOptimizeUseCase.optimizeWithGacha

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt`（整体替换为下方完整内容）
- Test: `app/src/test/java/com/mamba/picme/domain/usecase/AiOptimizeUseCaseGachaTest.kt`

- [ ] **Step 1: 写失败测试 AiOptimizeUseCaseGachaTest.kt**

```kotlin
package com.mamba.picme.domain.usecase

import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.analyzer.SceneAnalyzer
import com.mamba.picme.domain.agent.capability.optimize.gacha.GachaResult
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeCandidate
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeGachaEngine
import com.mamba.picme.domain.agent.capability.optimize.gacha.ScoredCandidate
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.agent.capability.optimize.preset.PresetRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiOptimizeUseCaseGachaTest {

    private val imageUri = "file:///test.jpg"

    private fun presetFor(scene: Scene, tag: Float = 0f) = OptimizePreset(
        scene = scene.name,
        beauty = BeautyPreset(enabled = true, smoothing = 15f + tag, whitening = 10f),
        filter = FilterPreset("NONE", "NONE"),
        adjustment = AdjustmentPreset(brightness = 2f + tag, contrast = 52f, saturation = 100f)
    )

    private fun scored(index: Int, score: Float, tag: Float) = ScoredCandidate(
        candidate = OptimizeCandidate(index, "d$index", presetFor(Scene.GENERAL, tag)),
        nimaScore = score,
        rejected = false
    )

    private fun useCase(
        engine: OptimizeGachaEngine?,
        logger: OptimizeFeedbackLogger? = null
    ): AiOptimizeUseCase {
        val analyzer = mockk<SceneAnalyzer>()
        val repository = mockk<PresetRepository>()
        coEvery { analyzer.analyze(imageUri) } returns Scene.GENERAL
        every { repository.getPreset(Scene.GENERAL) } returns presetFor(Scene.GENERAL)
        return AiOptimizeUseCase(repository, analyzer, engine, logger)
    }

    @Test
    fun `falls back to fixed preset when gacha engine is null`() = runTest {
        val outcome = useCase(engine = null).optimizeWithGacha(imageUri)

        assertEquals(GachaResult.Unavailable, outcome.result)
        // 兜底路径仍返回固定预设 recipe（现有行为）
        assertNotNull(outcome.editRecipe)
        assertEquals(15f, outcome.editRecipe!!.beauty.smoothing, 0.001f)
    }

    @Test
    fun `Selected maps best candidate preset into edit recipe`() = runTest {
        val all = listOf(scored(0, 5.0f, 0f), scored(1, 5.5f, 20f))
        val engine = mockk<OptimizeGachaEngine>()
        coEvery { engine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.Selected(best = all[1], all = all, originalScore = 5.0f)

        val outcome = useCase(engine).optimizeWithGacha(imageUri)

        assertTrue(outcome.result is GachaResult.Selected)
        // best 卡（tag=20）的参数被映射进 recipe
        assertEquals(35f, outcome.editRecipe!!.beauty.smoothing, 0.001f)
        assertEquals(22f, outcome.editRecipe!!.adjustments.brightness, 0.001f)
    }

    @Test
    fun `KeepOriginal returns null edit recipe`() = runTest {
        val all = listOf(scored(0, 5.0f, 0f), scored(1, 5.1f, 20f))
        val engine = mockk<OptimizeGachaEngine>()
        coEvery { engine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.KeepOriginal(all = all, originalScore = 5.2f)

        val outcome = useCase(engine).optimizeWithGacha(imageUri)

        assertTrue(outcome.result is GachaResult.KeepOriginal)
        assertNull(outcome.editRecipe)
    }

    @Test
    fun `engine Unavailable falls back to fixed preset recipe`() = runTest {
        val engine = mockk<OptimizeGachaEngine>()
        coEvery { engine.run(any(), any(), any(), any(), any()) } returns GachaResult.Unavailable

        val outcome = useCase(engine).optimizeWithGacha(imageUri)

        assertEquals(GachaResult.Unavailable, outcome.result)
        assertNotNull(outcome.editRecipe)
    }

    @Test
    fun `auto feedback logged on Selected and KeepOriginal but not Unavailable`() = runTest {
        val all = listOf(scored(0, 5.0f, 0f), scored(1, 5.5f, 20f))
        val logger = mockk<OptimizeFeedbackLogger>()
        coEvery { logger.log(any(), any(), any(), any(), any()) } returns Unit

        val selectedEngine = mockk<OptimizeGachaEngine>()
        coEvery { selectedEngine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.Selected(best = all[1], all = all, originalScore = 5.0f)
        useCase(selectedEngine, logger).optimizeWithGacha(imageUri)
        coVerify(exactly = 1) {
            logger.log(imageUri, Scene.GENERAL, all, 1, OptimizeFeedbackLogger.SOURCE_AUTO)
        }

        val keepEngine = mockk<OptimizeGachaEngine>()
        coEvery { keepEngine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.KeepOriginal(all = all, originalScore = 5.6f)
        useCase(keepEngine, logger).optimizeWithGacha(imageUri)
        coVerify(exactly = 1) {
            logger.log(imageUri, Scene.GENERAL, all, -1, OptimizeFeedbackLogger.SOURCE_AUTO)
        }

        val unavailableEngine = mockk<OptimizeGachaEngine>()
        coEvery { unavailableEngine.run(any(), any(), any(), any(), any()) } returns GachaResult.Unavailable
        useCase(unavailableEngine, logger).optimizeWithGacha(imageUri)
        // 总共仍只有前两次调用
        coVerify(exactly = 2) { logger.log(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `usedFingerprints accumulates exclude plus all candidate fingerprints`() = runTest {
        val all = listOf(scored(0, 5.0f, 0f), scored(1, 5.5f, 20f))
        val engine = mockk<OptimizeGachaEngine>()
        coEvery { engine.run(any(), any(), any(), any(), any()) } returns
            GachaResult.Selected(best = all[1], all = all, originalScore = 5.0f)

        val outcome = useCase(engine).optimizeWithGacha(imageUri, exclude = setOf("old-fp"))

        assertTrue("old-fp" in outcome.usedFingerprints)
        assertEquals(3, outcome.usedFingerprints.size)
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.AiOptimizeUseCaseGachaTest"`
Expected: FAIL — `optimizeWithGacha` / `GachaOutcome` unresolved

- [ ] **Step 3: 整体替换 AiOptimizeUseCase.kt**

新构造参数均有默认值，既有调用方与 `AiOptimizeUseCaseTest` 不受影响。

```kotlin
package com.mamba.picme.domain.usecase

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.analyzer.SceneAnalyzer
import com.mamba.picme.domain.agent.capability.optimize.gacha.CandidateSampler
import com.mamba.picme.domain.agent.capability.optimize.gacha.GachaResult
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeGachaEngine
import com.mamba.picme.domain.agent.capability.optimize.preset.PresetRepository
import com.mamba.picme.domain.agent.capability.optimize.recipe.OptimizeRecipeMapper
import com.mamba.picme.features.editor.EditRecipe

/**
 * AI 一键优化用例
 *
 * 独立于 Capability 的业务逻辑入口，同时服务：
 * - UI 层直接调用（媒体查看器、编辑器、批量优化）
 * - Agent Capability 委托执行
 *
 * 两条路径：
 * - [optimize]：固定预设路径（批量优化、抽卡不可用时的兜底）
 * - [optimizeWithGacha]：抽卡闭环路径（采样 4 候选 → 渲染 → NIMA 评分 → 选优 + 退化守卫）
 */
class AiOptimizeUseCase(
    private val presetRepository: PresetRepository,
    private val sceneAnalyzer: SceneAnalyzer,
    private val gachaEngine: OptimizeGachaEngine? = null,
    private val feedbackLogger: OptimizeFeedbackLogger? = null
) {

    companion object {
        private const val TAG = "PoLang:AiOptimizeUseCase"
    }

    /**
     * 优化结果
     *
     * @property scene 识别场景
     * @property confidence 置信度
     * @property editRecipe 可直接应用的编辑配方
     * @property explanation 一句话说明
     * @property processingTimeMs 处理耗时
     */
    data class Result(
        val scene: Scene,
        val confidence: Float,
        val editRecipe: EditRecipe,
        val explanation: String,
        val processingTimeMs: Long
    )

    /**
     * 抽卡优化结果
     *
     * @property result 抽卡结果（Selected / KeepOriginal / Unavailable）
     * @property scene 识别场景
     * @property editRecipe Selected 时为最优卡配方；Unavailable 时为固定预设兜底配方；
     *   KeepOriginal 时为 null（调用方保持原图）
     * @property explanation 场景说明文案
     * @property usedFingerprints 本次已出现的参数指纹（含传入的 exclude），「换一组」时回传去重
     * @property processingTimeMs 处理耗时
     */
    data class GachaOutcome(
        val result: GachaResult,
        val scene: Scene,
        val editRecipe: EditRecipe?,
        val explanation: String,
        val usedFingerprints: Set<String>,
        val processingTimeMs: Long
    )

    /**
     * 执行端侧场景感知优化
     *
     * 通过 [sceneAnalyzer] 端侧识别图片场景，按场景路由本地预设（零网络、隐私合规）。
     *
     * @param imageUri 图片本地 URI
     * @param baseRecipe 基础 Recipe（保留裁剪等既有参数）
     * @return 优化结果
     */
    suspend fun optimize(
        imageUri: String,
        baseRecipe: EditRecipe? = null
    ): Result {
        val startTime = System.currentTimeMillis()
        val scene = sceneAnalyzer.analyze(imageUri)
        val preset = presetRepository.getPreset(scene)
        val elapsed = System.currentTimeMillis() - startTime

        Logger.d(TAG, "Optimize: scene=${scene.name}, ${elapsed}ms")

        return Result(
            scene = scene,
            confidence = 1.0f,
            editRecipe = OptimizeRecipeMapper.toEditRecipe(
                preset = preset,
                sourceUri = imageUri,
                baseRecipe = baseRecipe ?: EditRecipe(sourceUri = imageUri)
            ),
            explanation = OptimizeRecipeMapper.buildExplanation(scene),
            processingTimeMs = elapsed
        )
    }

    /**
     * 执行抽卡闭环优化（best-of-N + NIMA 评分守卫）。
     *
     * 流程：场景识别 → base preset → [OptimizeGachaEngine] 抽卡选优。
     * 降级链（功能永不阻塞）：
     * - 无引擎 / 引擎返回 Unavailable → 退回固定预设（与 [optimize] 一致）
     * - KeepOriginal → editRecipe 为 null，调用方保持原图
     *
     * 自动选优与 KeepOriginal 均落库反馈（source=auto）；用户手选由 UI 层另行落库。
     *
     * @param imageUri 图片本地 URI
     * @param baseRecipe 基础 Recipe（保留裁剪等既有参数）
     * @param exclude 「换一组」时需排除的参数指纹集合
     */
    suspend fun optimizeWithGacha(
        imageUri: String,
        baseRecipe: EditRecipe? = null,
        exclude: Set<String> = emptySet()
    ): GachaOutcome {
        val startTime = System.currentTimeMillis()
        val scene = sceneAnalyzer.analyze(imageUri)
        val preset = presetRepository.getPreset(scene)
        val base = baseRecipe ?: EditRecipe(sourceUri = imageUri)

        val engine = gachaEngine
        if (engine == null) {
            val recipe = OptimizeRecipeMapper.toEditRecipe(preset, imageUri, base)
            return GachaOutcome(
                result = GachaResult.Unavailable,
                scene = scene,
                editRecipe = recipe,
                explanation = OptimizeRecipeMapper.buildExplanation(scene),
                usedFingerprints = exclude,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }

        val result = engine.run(
            imageUri = imageUri,
            scene = scene,
            basePreset = preset,
            exclude = exclude
        )

        val recipe: EditRecipe? = when (result) {
            is GachaResult.Selected ->
                OptimizeRecipeMapper.toEditRecipe(result.best.candidate.preset, imageUri, base)
            is GachaResult.KeepOriginal -> null
            GachaResult.Unavailable ->
                OptimizeRecipeMapper.toEditRecipe(preset, imageUri, base)
        }

        val allCandidates = when (result) {
            is GachaResult.Selected -> result.all
            is GachaResult.KeepOriginal -> result.all
            GachaResult.Unavailable -> emptyList()
        }
        val usedFingerprints = exclude + allCandidates.map { CandidateSampler.fingerprint(it.candidate.preset) }

        when (result) {
            is GachaResult.Selected ->
                feedbackLogger?.log(imageUri, scene, result.all, result.best.candidate.index, OptimizeFeedbackLogger.SOURCE_AUTO)
            is GachaResult.KeepOriginal ->
                feedbackLogger?.log(imageUri, scene, result.all, -1, OptimizeFeedbackLogger.SOURCE_AUTO)
            GachaResult.Unavailable -> Unit
        }

        val elapsed = System.currentTimeMillis() - startTime
        Logger.i(TAG, "optimizeWithGacha: scene=${scene.name}, result=${result::class.simpleName}, ${elapsed}ms")

        return GachaOutcome(
            result = result,
            scene = scene,
            editRecipe = recipe,
            explanation = OptimizeRecipeMapper.buildExplanation(scene),
            usedFingerprints = usedFingerprints,
            processingTimeMs = elapsed
        )
    }
}
```

- [ ] **Step 4: 跑全部 usecase + gacha 测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.*" --tests "com.mamba.picme.domain.agent.capability.optimize.*"`
Expected: PASS（含既有 `AiOptimizeUseCaseTest` 不回归）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/usecase/AiOptimizeUseCase.kt app/src/test/java/com/mamba/picme/domain/usecase/AiOptimizeUseCaseGachaTest.kt
git commit -m "feat(optimize): add optimizeWithGacha with fallback chain and auto feedback"
```

---

## Task 7: 编辑器集成（ViewModel + Factory + GachaCandidateBar + Screen）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModel.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModelFactory.kt`
- Create: `app/src/main/java/com/mamba/picme/features/editor/components/GachaCandidateBar.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorScreen.kt`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`

- [ ] **Step 1: PhotoEditorViewModel 改造**

1. 新增 import：

```kotlin
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.gacha.GachaResult
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.ScoredCandidate
import com.mamba.picme.domain.agent.capability.optimize.recipe.OptimizeRecipeMapper
```

2. 构造函数追加参数（`mattingEngine` 之后）：

```kotlin
    private val mattingEngine: MattingEngine? = null,
    private val feedbackLogger: OptimizeFeedbackLogger? = null
```

3. `State.Ready` 增加字段：

```kotlin
            val error: String? = null,
            val gachaRun: GachaRunUiState? = null
```

4. `State` 密封类之后新增 UI 状态类：

```kotlin
    /**
     * 抽卡运行状态（UI）。
     *
     * @property candidates 本组 4 张候选卡（含缩略图与分数）
     * @property selectedIndex 当前应用的卡序号；-1 = 当前应用结果不在本组或 KeepOriginal
     * @property exclude 已出现的参数指纹（「换一组」去重）
     * @property baseRecipe 首次优化前的 recipe；换一组/点选都基于它映射，避免参数叠加
     * @property scene 识别场景（落库用）
     * @property expanded true = 展开 4 卡对比条（换一组后）
     * @property keepOriginal true = 退化守卫判定保持原图
     */
    data class GachaRunUiState(
        val candidates: List<ScoredCandidate>,
        val selectedIndex: Int,
        val exclude: Set<String>,
        val baseRecipe: EditRecipe,
        val scene: Scene,
        val expanded: Boolean = false,
        val keepOriginal: Boolean = false
    )
```

5. `aiOptimize()` 整体替换（分支语义见 spec §6；保留原有 error 处理）：

```kotlin
    /**
     * AI 一键优化：抽卡闭环（采样 4 候选 → 渲染 → NIMA 评分 → 选优 + 退化守卫）。
     *
     * - Selected：自动应用最优卡，结果条提供「换一组」
     * - KeepOriginal：保持原图，结果条说明 + 「换一组」
     * - Unavailable（NIMA 未下载等）：退回固定预设直接应用（原行为），无抽卡 UI
     */
    fun aiOptimize() {
        val useCase = aiOptimizeUseCase ?: run {
            _state.value = (_state.value as? State.Ready)?.copy(
                error = appContext?.getString(R.string.ai_optimize_not_available) ?: "AI 优化不可用"
            ) ?: State.Error("AI 优化不可用")
            return
        }
        val current = _state.value as? State.Ready ?: return
        val sourceUri = current.recipe.sourceUri
        viewModelScope.launch {
            val processingState = current.copy(isProcessing = true, error = null)
            _state.value = processingState
            try {
                val outcome = useCase.optimizeWithGacha(sourceUri, current.recipe)
                when (val result = outcome.result) {
                    is GachaResult.Selected -> {
                        val recipe = outcome.editRecipe
                        if (recipe != null) {
                            history.push(recipe)
                            _state.value = processingState.copy(
                                recipe = recipe,
                                isProcessing = false,
                                gachaRun = GachaRunUiState(
                                    candidates = result.all,
                                    selectedIndex = result.best.candidate.index,
                                    exclude = outcome.usedFingerprints,
                                    baseRecipe = current.recipe,
                                    scene = outcome.scene
                                )
                            )
                            _recipeChanges.value = recipe
                        } else {
                            _state.value = processingState.copy(isProcessing = false)
                        }
                    }
                    is GachaResult.KeepOriginal -> {
                        _state.value = processingState.copy(
                            isProcessing = false,
                            gachaRun = GachaRunUiState(
                                candidates = result.all,
                                selectedIndex = -1,
                                exclude = outcome.usedFingerprints,
                                baseRecipe = current.recipe,
                                scene = outcome.scene,
                                keepOriginal = true
                            )
                        )
                    }
                    GachaResult.Unavailable -> {
                        val recipe = outcome.editRecipe
                        if (recipe != null) {
                            history.push(recipe)
                            _state.value = processingState.copy(recipe = recipe, isProcessing = false)
                            _recipeChanges.value = recipe
                        } else {
                            _state.value = processingState.copy(isProcessing = false)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "AI optimize failed", e)
                _state.value = processingState.copy(
                    isProcessing = false,
                    error = appContext?.getString(R.string.ai_optimize_failed, e.message ?: "") ?: "AI 优化失败"
                )
            }
        }
    }

    /**
     * 「换一组」：以首次优化前的 recipe 为基准重新抽卡（去重已出现组合），
     * 展开 4 卡对比条等用户点选；不自动应用。
     */
    fun rerollGacha() {
        val useCase = aiOptimizeUseCase ?: return
        val current = _state.value as? State.Ready ?: return
        val run = current.gachaRun ?: return
        viewModelScope.launch {
            _state.value = current.copy(isProcessing = true)
            try {
                val outcome = useCase.optimizeWithGacha(
                    imageUri = run.baseRecipe.sourceUri,
                    baseRecipe = run.baseRecipe,
                    exclude = run.exclude
                )
                val ready = _state.value as? State.Ready ?: return@launch
                val all = when (val r = outcome.result) {
                    is GachaResult.Selected -> r.all
                    is GachaResult.KeepOriginal -> r.all
                    GachaResult.Unavailable -> null
                }
                if (all != null) {
                    _state.value = ready.copy(
                        isProcessing = false,
                        gachaRun = GachaRunUiState(
                            candidates = all,
                            selectedIndex = -1,
                            exclude = outcome.usedFingerprints,
                            baseRecipe = run.baseRecipe,
                            scene = outcome.scene,
                            expanded = true,
                            keepOriginal = outcome.result is GachaResult.KeepOriginal
                        )
                    )
                } else {
                    _state.value = ready.copy(
                        isProcessing = false,
                        error = appContext?.getString(R.string.ai_optimize_failed, "gacha unavailable")
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "reroll gacha failed", e)
                val ready = _state.value as? State.Ready ?: return@launch
                _state.value = ready.copy(
                    isProcessing = false,
                    error = appContext?.getString(R.string.ai_optimize_failed, e.message ?: "")
                )
            }
        }
    }

    /**
     * 用户在 4 卡对比条点选某卡：基于 baseRecipe 映射应用（不叠加），落库 user 反馈。
     */
    fun pickGachaCandidate(index: Int) {
        val current = _state.value as? State.Ready ?: return
        val run = current.gachaRun ?: return
        val scored = run.candidates.find { it.candidate.index == index } ?: return
        if (scored.rejected) return

        val recipe = OptimizeRecipeMapper.toEditRecipe(
            preset = scored.candidate.preset,
            sourceUri = run.baseRecipe.sourceUri,
            baseRecipe = run.baseRecipe
        )
        history.push(recipe)
        _state.value = current.copy(
            recipe = recipe,
            gachaRun = run.copy(selectedIndex = index, expanded = false, keepOriginal = false)
        )
        _recipeChanges.value = recipe
        viewModelScope.launch {
            feedbackLogger?.log(
                imageUri = run.baseRecipe.sourceUri,
                scene = run.scene,
                all = run.candidates,
                selectedIndex = index,
                source = OptimizeFeedbackLogger.SOURCE_USER
            )
        }
    }

    /**
     * 关闭抽卡结果条；展开态下未点选即关闭时落库 dismiss 反馈。
     */
    fun dismissGacha() {
        val current = _state.value as? State.Ready ?: return
        val run = current.gachaRun ?: return
        if (run.expanded && run.selectedIndex < 0) {
            viewModelScope.launch {
                feedbackLogger?.log(
                    imageUri = run.baseRecipe.sourceUri,
                    scene = run.scene,
                    all = run.candidates,
                    selectedIndex = -1,
                    source = OptimizeFeedbackLogger.SOURCE_DISMISS
                )
            }
        }
        _state.value = current.copy(gachaRun = null)
    }
```

6. 删除临时调试方法 `scoreNimaDelta`（约 265-292 行，含注释 `临时:NIMA 美学量化 before→after Δ`）——抽卡链路已内置 NIMA 评分，该方法被取代。删除后按编译器警告清理不再使用的 import（`NimaScorer`、`RecipeApplier`、`Dispatchers`，以实际编译结果为准；注意 `photoProcessingDispatcher` 与 `mattingEngine` 仍被预览渲染使用，保留）。

- [ ] **Step 2: PhotoEditorViewModelFactory 追加参数**

构造函数 `downloadManager` 之后加：

```kotlin
    private val downloadManager: LlmModelDownloadManager? = null,
    private val feedbackLogger: OptimizeFeedbackLogger? = null
```

`PhotoEditorViewModel(...)` 调用处 `mattingEngine = ...` 之后加：

```kotlin
                mattingEngine = MattingEngineImpl(appContext, downloadManager),
                feedbackLogger = feedbackLogger
```

import 加 `com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger`。

- [ ] **Step 3: 新建 GachaCandidateBar.kt**

```kotlin
package com.mamba.picme.features.editor.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.features.editor.PhotoEditorViewModel

/**
 * AI 优化抽卡结果条。
 *
 * 收起态：自动选优/保持原图说明 +「换一组」+「关闭」；
 * 展开态（换一组后）：4 卡缩略图对比，点选应用；被淘汰的卡置灰不可点。
 */
@Composable
fun GachaCandidateBar(
    run: PhotoEditorViewModel.GachaRunUiState,
    onReroll: () -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (run.expanded) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(R.string.ai_optimize_pick_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    run.candidates.forEach { scored ->
                        val selected = scored.candidate.index == run.selectedIndex
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !scored.rejected) {
                                    onPick(scored.candidate.index)
                                }
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(4.dp)
                        ) {
                            scored.thumbnail?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = scored.candidate.direction,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }
                            Text(
                                text = scored.candidate.direction,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (scored.rejected) MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onReroll) {
                        Text(stringResource(R.string.ai_optimize_reroll))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.ai_optimize_dismiss))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        if (run.keepOriginal) R.string.ai_optimize_keep_original
                        else R.string.ai_optimize_best_applied
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onReroll) {
                    Text(stringResource(R.string.ai_optimize_reroll))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ai_optimize_dismiss))
                }
            }
        }
    }
}
```

- [ ] **Step 4: PhotoEditorScreen 挂载结果条**

`bottomBar` 的 `Column` 内、`PanelForTab(...)` 之前插入：

```kotlin
                ready.gachaRun?.let { run ->
                    GachaCandidateBar(
                        run = run,
                        onReroll = viewModel::rerollGacha,
                        onPick = viewModel::pickGachaCandidate,
                        onDismiss = viewModel::dismissGacha
                    )
                }
```

import 加 `com.mamba.picme.features.editor.components.GachaCandidateBar`。

- [ ] **Step 5: AppContainer 组装 gacha 依赖**

1. import 追加：

```kotlin
import com.mamba.picme.domain.agent.capability.optimize.gacha.CandidateRenderer
import com.mamba.picme.domain.agent.capability.optimize.gacha.CandidateSampler
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeGachaEngine
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeScorer
import com.mamba.picme.domain.aesthetic.AestheticScorer
import com.mamba.picme.domain.aesthetic.NimaScorer
import com.mamba.picme.features.editor.RecipeApplier
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
```

2. `aiOptimizeUseCase` 定义（385-390 行）整体替换为：

```kotlin
    private val optimizeFeedbackLogger: OptimizeFeedbackLogger by lazy {
        OptimizeFeedbackLogger(database.optimizeFeedbackDao())
    }

    private val gachaAestheticScorer: AestheticScorer by lazy { NimaScorer(context) }

    private val gachaProcessingDispatcher by lazy {
        // 与编辑器同一约束：PhotoProcessor 内部 EGL 上下文必须单线程调用，
        // 线程池切换会导致 EGL 上下文失效而黑屏（见 AI_OPTIMIZATION.md §9.1）
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    private val optimizeGachaEngine: OptimizeGachaEngine by lazy {
        OptimizeGachaEngine(
            sampler = CandidateSampler(),
            renderer = CandidateRenderer(
                context = context,
                // 独立 PhotoProcessor 实例：不与相机/编辑器共享 EGL 上下文（§9.1 教训）
                recipeApplier = RecipeApplier(photoProcessorFactory(context), gachaProcessingDispatcher)
            ),
            optimizeScorer = OptimizeScorer(gachaAestheticScorer),
            aestheticScorer = gachaAestheticScorer
        )
    }

    override val aiOptimizeUseCase: AiOptimizeUseCase by lazy {
        AiOptimizeUseCase(
            presetRepository = AssetPresetRepository(context),
            sceneAnalyzer = HeuristicSceneAnalyzer(context, faceDetector),
            gachaEngine = optimizeGachaEngine,
            feedbackLogger = optimizeFeedbackLogger
        )
    }
```

3. `photoEditorViewModelFactory`（576-587 行）`downloadManager = ...` 之后加：

```kotlin
            downloadManager = llmModelDownloadManager,
            feedbackLogger = optimizeFeedbackLogger
```

- [ ] **Step 6: 编译 + 全量单测**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL + 全部测试 PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/ app/src/main/java/com/mamba/picme/di/AppContainer.kt
git commit -m "feat(editor): integrate gacha optimize flow with reroll candidate bar"
```

---

## Task 8: i18n 文案 + 文档同步 + 闭环验证

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`
- Modify: `docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/AGENTS.md`

- [ ] **Step 1: 新增 5 条文案（四语言同步，[I18N] 红线）**

`values/strings.xml`（英文）追加：

```xml
    <string name="ai_optimize_best_applied">Best look applied</string>
    <string name="ai_optimize_keep_original">This photo already looks great — left unchanged</string>
    <string name="ai_optimize_reroll">Try another set</string>
    <string name="ai_optimize_pick_hint">Tap the one you like best</string>
    <string name="ai_optimize_dismiss">Close</string>
```

`values-zh/strings.xml` 与 `values-zh-rCN/strings.xml` 追加：

```xml
    <string name="ai_optimize_best_applied">已为你应用最佳方案</string>
    <string name="ai_optimize_keep_original">当前照片已很好，未做修改</string>
    <string name="ai_optimize_reroll">换一组</string>
    <string name="ai_optimize_pick_hint">点选你最喜欢的一张</string>
    <string name="ai_optimize_dismiss">关闭</string>
```

`values-zh-rTW/strings.xml` 追加：

```xml
    <string name="ai_optimize_best_applied">已為你套用最佳效果</string>
    <string name="ai_optimize_keep_original">目前照片效果已很好，未做修改</string>
    <string name="ai_optimize_reroll">換一組</string>
    <string name="ai_optimize_pick_hint">點選你最喜歡的一張</string>
    <string name="ai_optimize_dismiss">關閉</string>
```

注意：追加位置在各文件既有 `ai_optimize*` 字符串附近；若某文件已存在同名 key 则跳过该条。

- [ ] **Step 2: 编译验证文案**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: AI_OPTIMIZATION.md 同步**

在文档末尾（§11 相关文档索引之前）新增一节：

```markdown
## 11.5 抽卡闭环（2026-08-06 落地）

AI 优化已从「一次给值」升级为「抽卡闭环」（best-of-N + NIMA 评分守卫）：

- **链路**：`CandidateSampler` 以场景预设为锚点抽 4 候选 → `CandidateRenderer` 512px 渲染 → `OptimizeScorer` NIMA 评分 + 技术护栏（高光裁剪 5%、亮度漂移 15%）→ 自动选优
- **退化守卫**：最优候选 NIMA 分 ≤ 原图 + 0.05 时保持原图，从机制上杜绝"越优化越差"
- **换一组**：编辑器结果条支持重抽 4 卡用户手选；点选/关闭行为落库 `optimize_feedback` 表（Phase 2 个性化素材）
- **降级链**：NIMA 模型未下载 → 退回固定预设（原行为）；批量优化不走抽卡
- 设计与实现详见 `docs/superpowers/specs/2026-08-06-ai-optimize-gacha-design.md` 与 `docs/superpowers/plans/2026-08-06-ai-optimize-gacha.md`
```

同时把 §3.1 架构图下方的 Fast 路径说明补一句：`Fast 路径现已接入抽卡闭环，见 §11.5`。

- [ ] **Step 4: 编辑器 AGENTS.md 同步**

`app/src/main/java/com/mamba/picme/features/editor/AGENTS.md` 适当位置（AI 优化相关段落）补：

```markdown
- **AI 一键优化走抽卡闭环**：`aiOptimize()` 调 `AiOptimizeUseCase.optimizeWithGacha()`（采样 4 候选 → NIMA 评分 → 退化守卫），结果条 `GachaCandidateBar` 支持「换一组」手选；NIMA 未下载时自动退回固定预设。反馈落库 `optimize_feedback`（source: auto/user/dismiss）。
```

- [ ] **Step 5: 真机闭环验证**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

人工验证清单：
1. 查看器点「AI 优化」→ 进编辑器自动跑抽卡 → 自动应用最优卡，底部出现收起态结果条
2. 点「换一组」→ 展开 4 卡对比条 → 点选某卡应用、被淘汰卡置灰不可点
3. 找一张本身质量高的照片触发 KeepOriginal（保持原图文案）
4. NIMA 模型未下载（或删除模型文件）→ 退回固定预设直接应用、无抽卡条
5. 数据库从 v19 升级：覆盖安装旧版 → 装新版 → 不崩溃，跑一次优化后 `optimize_feedback` 表有 auto 记录（`adb shell run-as com.mamba.picme cat databases/picme_database` 或 Database Inspector 验证）
6. `adb logcat -s PoLang:OptimizeGacha PoLang:AiOptimizeUseCase` 观察抽卡日志（卡数、原图分、结果类型）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/ docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md app/src/main/java/com/mamba/picme/features/editor/AGENTS.md
git commit -m "feat(optimize): add gacha i18n strings and sync docs"
```

---

## Self-Review 结论（计划落盘后已核对）

- **Spec 覆盖**：§3 组件 → Task 1-4；§4 采样 → Task 1；§5 评分守卫 → Task 2/4；§6 交互 → Task 7；§7 落库 → Task 5；§8 性能 → Task 3（512px）+ Task 7 串行渲染；§9 降级 → Task 2/4/6/7 各分支；§10 测试 → 每 Task 内嵌；[I18N]/[DOC-SYNC] → Task 8。
- **类型一致性**：`GachaResult`/`ScoredCandidate`/`OptimizeCandidate`/`GachaRunUiState`/`GachaOutcome` 跨 Task 签名已逐一核对；`CandidateSampler.fingerprint` 为 companion 静态方法（Task 1 定义，Task 6 使用）。
- **已知留白（有意）**：`decodeDownscaled` 无 JVM 单测（依赖 ContentResolver，真机闭环覆盖）；Room migration SQL 与实体一致性由编译期 Room 校验 + 真机升级路径验证（Task 8 Step 5-5）。
