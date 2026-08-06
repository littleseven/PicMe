# 证件照页可编辑化（边缘参数 + 涂抹修补）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **执行前必读**：开工前先按 `using-git-worktrees` skill 在 `.worktrees/` 下建隔离工作区与专用分支（项目 AGENTS.md §3.4 强制）。

**Goal:** 证件照页从「一次性结果」升级为可所见即所得调整：边缘参数滑块（对比度/收缩扩张/羽化）+ 恢复/擦除画笔涂抹修补，全部基于缓存的融合 alpha 后处理，不重跑模型。

**Architecture:** 两层调整叠加在缓存 fusedAlpha 上：参数层（`MaskPostProcessor.adjustEdges`，纯 JVM 可测）→ 描边层（`StrokeLayer` 矢量描边重放）。ViewModel 只做编排；坐标映射等逻辑全部下沉为纯函数。UI 在 `IDPhotoScreen` 底部改 4-tab 面板（底色/尺寸/边缘/修补）。

**Tech Stack:** Kotlin、Jetpack Compose、JUnit4（`app/src/test` JVM 单测）。

**Spec:** `docs/superpowers/specs/2026-08-06-idphoto-adjustable-matting-design.md`

**关键事实（探索结论，执行时不必重查）：**
- `MaskSource.FUSION` 全项目仅 `IDPhotoViewModel.load()` 一处使用（`app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoViewModel.kt:73`），移动 `sharpenAlpha` 出融合管线不影响其他消费者。
- 融合管线现于 `MattingEngine.kt:136` 固定执行 `sharpenAlpha(fused, 2.5f)`；本计划将其移入参数层，`EdgeParams.DEFAULT_CONTRAST = 2.5f` 复现现行为。
- `previewBaseCache` 现按 `selectedColorIndex` 缓存（`IDPhotoViewModel.kt:132`），需扩展缓存键。
- 测试风格参考 `app/src/test/java/com/mamba/picme/domain/matting/MaskPostProcessorTest.kt`（JUnit4，反引号命名，assertArrayEquals/assertEquals 带 delta）。
- i18n 三文件：`app/src/main/res/values/strings.xml`（英文）、`values-zh-rCN`、`values-zh-rTW`（`values-zh` 无 id_photo 条目，不维护）。
- 单测命令：`./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.*"`

---

### Task 1: MaskPostProcessor 腐蚀/扩张（morphology）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/matting/MaskPostProcessor.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/matting/MaskPostProcessorTest.kt`

- [ ] **Step 1: 写失败测试**

在 `MaskPostProcessorTest.kt` 类内追加：

```kotlin
    @Test
    fun `erode radius 0 returns copy`() {
        val alpha = floatArrayOf(0f, 1f, 1f, 0f)
        val out = MaskPostProcessor.erode(alpha, w = 4, h = 1, radius = 0)
        assertArrayEquals(alpha, out, 0.0001f)
    }

    @Test
    fun `erode shrinks foreground strip`() {
        // 1x5: 0 1 1 1 0 ; radius 1 min-filter -> 0 0 1 0 0
        val alpha = floatArrayOf(0f, 1f, 1f, 1f, 0f)
        val out = MaskPostProcessor.erode(alpha, w = 5, h = 1, radius = 1)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f), out, 0.0001f)
    }

    @Test
    fun `dilate grows foreground strip`() {
        // 1x5: 0 0 1 0 0 ; radius 1 max-filter -> 0 1 1 1 0
        val alpha = floatArrayOf(0f, 0f, 1f, 0f, 0f)
        val out = MaskPostProcessor.dilate(alpha, w = 5, h = 1, radius = 1)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f, 0f), out, 0.0001f)
    }

    @Test
    fun `dilate at image edge clamps window`() {
        // 1x3: 1 0 0 ; radius 1 -> 1 1 0（左边缘不外溢）
        val alpha = floatArrayOf(1f, 0f, 0f)
        val out = MaskPostProcessor.dilate(alpha, w = 3, h = 1, radius = 1)
        assertArrayEquals(floatArrayOf(1f, 1f, 0f), out, 0.0001f)
    }

    @Test
    fun `erode never increases foreground area on 2d mask`() {
        // 3x3 全 1，中心一个 0 空洞；腐蚀后前景计数不增
        val alpha = floatArrayOf(
            1f, 1f, 1f,
            1f, 0f, 1f,
            1f, 1f, 1f
        )
        val out = MaskPostProcessor.erode(alpha, w = 3, h = 3, radius = 1)
        val before = alpha.count { it >= 0.5f }
        val after = out.count { it >= 0.5f }
        assertTrue(after <= before)
    }
```

（文件头需补 `import org.junit.Assert.assertTrue`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.MaskPostProcessorTest"`
Expected: 编译失败 / FAIL（`erode`/`dilate` 未定义）

- [ ] **Step 3: 实现 erode/dilate**

在 `MaskPostProcessor` object 内（`sharpenAlpha` 之后）追加，风格对齐现有 `feather`（朴素窗口 + 边缘钳制）：

```kotlin
    /** 腐蚀（收缩前景）：分离式滑动窗口最小值滤波。radius<=0 返回拷贝。 */
    fun erode(alpha: FloatArray, w: Int, h: Int, radius: Int): FloatArray =
        windowPass(windowPass(alpha, w, h, radius, horizontal = true, isMax = false),
            w, h, radius, horizontal = false, isMax = false)

    /** 扩张（扩展前景）：分离式滑动窗口最大值滤波。radius<=0 返回拷贝。 */
    fun dilate(alpha: FloatArray, w: Int, h: Int, radius: Int): FloatArray =
        windowPass(windowPass(alpha, w, h, radius, horizontal = true, isMax = true),
            w, h, radius, horizontal = false, isMax = true)

    /** 单方向滑动窗口 min/max 滤波；越界位置跳过（边缘钳制，与 [feather] 一致）。 */
    private fun windowPass(
        alpha: FloatArray, w: Int, h: Int, radius: Int, horizontal: Boolean, isMax: Boolean
    ): FloatArray {
        if (radius <= 0) return alpha.copyOf()
        val out = FloatArray(alpha.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var best = if (isMax) 0f else 1f
                for (d in -radius..radius) {
                    val sx = if (horizontal) x + d else x
                    val sy = if (horizontal) y else y + d
                    if (sx in 0 until w && sy in 0 until h) {
                        val v = alpha[sy * w + sx]
                        best = if (isMax) maxOf(best, v) else minOf(best, v)
                    }
                }
                out[y * w + x] = best
            }
        }
        return out
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.MaskPostProcessorTest"`
Expected: PASS（含原有用例）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/MaskPostProcessor.kt \
        app/src/test/java/com/mamba/picme/domain/matting/MaskPostProcessorTest.kt
git commit -m "feat(matting): add erode/dilate morphology to MaskPostProcessor"
```

---

### Task 2: EdgeParams + adjustEdges 参数层流水线

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/EdgeParams.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/matting/MaskPostProcessor.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/matting/MaskPostProcessorTest.kt`

- [ ] **Step 1: 写失败测试**

在 `MaskPostProcessorTest.kt` 追加：

```kotlin
    @Test
    fun `adjustEdges with default params equals sharpen 2_5 only`() {
        val alpha = floatArrayOf(0f, 0.3f, 0.7f, 1f)
        val out = MaskPostProcessor.adjustEdges(alpha, w = 4, h = 1, params = EdgeParams())
        val expected = MaskPostProcessor.sharpenAlpha(alpha, contrast = EdgeParams.DEFAULT_CONTRAST)
        assertArrayEquals(expected, out, 1e-5f)
    }

    @Test
    fun `adjustEdges positive shrinkExpand dilates`() {
        val alpha = floatArrayOf(0f, 0f, 1f, 0f, 0f)
        val out = MaskPostProcessor.adjustEdges(
            alpha, w = 5, h = 1,
            params = EdgeParams(contrast = 1f, shrinkExpandPx = 1)
        )
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f, 0f), out, 0.0001f)
    }

    @Test
    fun `adjustEdges negative shrinkExpand erodes`() {
        val alpha = floatArrayOf(0f, 1f, 1f, 1f, 0f)
        val out = MaskPostProcessor.adjustEdges(
            alpha, w = 5, h = 1,
            params = EdgeParams(contrast = 1f, shrinkExpandPx = -1)
        )
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f), out, 0.0001f)
    }

    @Test
    fun `adjustEdges applies order contrast then morph then feather`() {
        // 对比度先把 0.4 压到 0.2（contrast=2 关于 0.5），不会被后续 morph 当作前景
        val alpha = floatArrayOf(0.4f, 0.4f, 0.4f)
        val out = MaskPostProcessor.adjustEdges(
            alpha, w = 3, h = 1,
            params = EdgeParams(contrast = 2f, shrinkExpandPx = 0, featherRadiusPx = 0)
        )
        assertArrayEquals(floatArrayOf(0.2f, 0.2f, 0.2f), out, 1e-5f)
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.MaskPostProcessorTest"`
Expected: 编译失败（`EdgeParams`/`adjustEdges` 未定义）

- [ ] **Step 3: 创建 EdgeParams.kt**

```kotlin
package com.mamba.picme.domain.matting

/**
 * 证件照边缘调整参数（参数层）。默认值 = 2026-08 前融合管线内固定行为
 * （sharpen 2.5，无缩扩/羽化），即默认输出与旧版本逐像素一致。
 */
data class EdgeParams(
    val contrast: Float = DEFAULT_CONTRAST,
    val shrinkExpandPx: Int = 0,
    val featherRadiusPx: Int = 0
) {
    companion object {
        const val DEFAULT_CONTRAST = 2.5f
        const val MIN_CONTRAST = 1.0f
        const val MAX_CONTRAST = 4.0f
        const val MAX_SHRINK_EXPAND_PX = 20
        const val MAX_FEATHER_PX = 20
    }
}
```

- [ ] **Step 4: 在 MaskPostProcessor 追加 adjustEdges**

```kotlin
    /**
     * 证件照参数层流水线：对比度 → 收缩/扩张 → 羽化（先定边缘位置，再软化）。
     * 各环节为默认值时自然短路（sharpen=1/erode/dilate/feather radius<=0 均返回拷贝）。
     */
    fun adjustEdges(alpha: FloatArray, w: Int, h: Int, params: EdgeParams): FloatArray {
        var out = sharpenAlpha(alpha, params.contrast)
        if (params.shrinkExpandPx > 0) {
            out = dilate(out, w, h, params.shrinkExpandPx)
        } else if (params.shrinkExpandPx < 0) {
            out = erode(out, w, h, -params.shrinkExpandPx)
        }
        if (params.featherRadiusPx > 0) {
            out = feather(out, w, h, params.featherRadiusPx)
        }
        return out
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.MaskPostProcessorTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/EdgeParams.kt \
        app/src/main/java/com/mamba/picme/domain/matting/MaskPostProcessor.kt \
        app/src/test/java/com/mamba/picme/domain/matting/MaskPostProcessorTest.kt
git commit -m "feat(matting): add EdgeParams parameter layer pipeline"
```

---

### Task 3: StrokeLayer 矢量描边层

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/StrokeLayer.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/matting/StrokeLayerTest.kt`

说明：spec §5 写的是「归一化坐标」，实现改为**原图像素坐标**——会话内 alpha 尺寸加载后不可变，归一化无实际收益（YAGNI），且省去 VM/测试里的反复换算。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mamba/picme/domain/matting/StrokeLayerTest.kt`：

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeLayerTest {

    private fun stroke(mode: StrokeMode, x: Float, y: Float, radius: Float = 1.5f) = BrushStroke(
        mode = mode,
        radiusPx = radius,
        softness = 0f,
        points = listOf(StrokePoint(x, y))
    )

    @Test
    fun `add undo redo clear state transitions`() {
        val layer = StrokeLayer()
        assertFalse(layer.canUndo)
        layer.addStroke(stroke(StrokeMode.RESTORE, 2f, 2f))
        assertTrue(layer.canUndo)
        assertEquals(1, layer.count)
        assertTrue(layer.undo())
        assertFalse(layer.canUndo)
        assertTrue(layer.canRedo)
        assertTrue(layer.redo())
        assertEquals(1, layer.count)
        // 新描边清空 redo 栈
        layer.undo()
        layer.addStroke(stroke(StrokeMode.ERASE, 2f, 2f))
        assertFalse(layer.canRedo)
        layer.clear()
        assertEquals(0, layer.count)
        assertFalse(layer.canUndo)
    }

    @Test
    fun `replay restore hard brush sets disc to 1`() {
        val layer = StrokeLayer()
        layer.addStroke(stroke(StrokeMode.RESTORE, x = 2f, y = 2f, radius = 1.5f))
        val base = FloatArray(25) // 5x5 全 0
        val out = layer.replayOnto(base, w = 5, h = 5)
        assertEquals(1f, out[2 * 5 + 2], 0.001f) // 圆心
        assertEquals(1f, out[2 * 5 + 3], 0.001f) // 半径内
        assertEquals(0f, out[0], 0.001f)         // 远处不受影响
    }

    @Test
    fun `replay erase hard brush sets disc to 0`() {
        val layer = StrokeLayer()
        layer.addStroke(stroke(StrokeMode.ERASE, x = 2f, y = 2f, radius = 1.5f))
        val base = FloatArray(25) { 1f } // 5x5 全 1
        val out = layer.replayOnto(base, w = 5, h = 5)
        assertEquals(0f, out[2 * 5 + 2], 0.001f)
        assertEquals(1f, out[0], 0.001f)
    }

    @Test
    fun `replay applies strokes in order restore then erase ends 0`() {
        val layer = StrokeLayer()
        layer.addStroke(stroke(StrokeMode.RESTORE, 2f, 2f))
        layer.addStroke(stroke(StrokeMode.ERASE, 2f, 2f))
        val out = layer.replayOnto(FloatArray(25), w = 5, h = 5)
        assertEquals(0f, out[2 * 5 + 2], 0.001f)
    }

    @Test
    fun `replay does not modify input array`() {
        val layer = StrokeLayer()
        layer.addStroke(stroke(StrokeMode.RESTORE, 2f, 2f))
        val base = FloatArray(25)
        val snapshot = base.copyOf()
        layer.replayOnto(base, w = 5, h = 5)
        assertArrayEquals(snapshot, base, 0f)
    }

    @Test
    fun `empty layer returns copy of base`() {
        val base = floatArrayOf(0.3f, 0.7f)
        val out = StrokeLayer().replayOnto(base, w = 2, h = 1)
        assertArrayEquals(base, out, 0f)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.StrokeLayerTest"`
Expected: 编译失败（类未定义）

- [ ] **Step 3: 实现 StrokeLayer.kt**

```kotlin
package com.mamba.picme.domain.matting

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

enum class StrokeMode { RESTORE, ERASE }

/** 原图像素坐标点。 */
data class StrokePoint(val x: Float, val y: Float)

/**
 * 一条涂抹描边（矢量记录，非像素快照）：
 * [radiusPx] 原图像素坐标系下的半径；[softness] 0=硬边，1=全软边；
 * [points] 原图像素坐标折线（会话内 alpha 尺寸不可变，故不做归一化）。
 */
data class BrushStroke(
    val mode: StrokeMode,
    val radiusPx: Float,
    val softness: Float,
    val points: List<StrokePoint>
)

/**
 * 描边层：持有有序描边列表 + 重做栈，重放到参数层结果之上。
 * 撤销 = 移除尾条重放，天然无损；重放只写各描边包围盒局部区域。
 * 非线程安全：仅在 ViewModel 编排下使用（主线程收集点 + Default 调度重放，经状态串行化）。
 */
class StrokeLayer {

    private val strokes = mutableListOf<BrushStroke>()
    private val redoStack = mutableListOf<BrushStroke>()

    val count: Int get() = strokes.size
    val canUndo: Boolean get() = strokes.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun addStroke(stroke: BrushStroke) {
        strokes.add(stroke)
        redoStack.clear()
    }

    fun undo(): Boolean {
        val s = strokes.removeLastOrNull() ?: return false
        redoStack.add(s)
        return true
    }

    fun redo(): Boolean {
        val s = redoStack.removeLastOrNull() ?: return false
        strokes.add(s)
        return true
    }

    fun clear() {
        strokes.clear()
        redoStack.clear()
    }

    /** 把全部描边按序重放到 [base] 的拷贝上（不修改入参）。无描边时返回拷贝。 */
    fun replayOnto(base: FloatArray, w: Int, h: Int): FloatArray {
        val out = base.copyOf()
        for (stroke in strokes) replayStroke(out, w, h, stroke)
        return out
    }

    private fun replayStroke(out: FloatArray, w: Int, h: Int, stroke: BrushStroke) {
        if (stroke.points.isEmpty() || stroke.radiusPx <= 0f) return
        val target = if (stroke.mode == StrokeMode.RESTORE) 1f else 0f
        val step = max(1f, stroke.radiusPx / 2f)
        for (i in 0 until stroke.points.size) {
            val p = stroke.points[i]
            stampDisc(out, w, h, p.x, p.y, stroke.radiusPx, stroke.softness, target)
            if (i + 1 < stroke.points.size) {
                val q = stroke.points[i + 1]
                val dx = q.x - p.x
                val dy = q.y - p.y
                val dist = sqrt(dx * dx + dy * dy)
                var t = step
                while (t < dist) {
                    stampDisc(out, w, h, p.x + dx * t / dist, p.y + dy * t / dist,
                        stroke.radiusPx, stroke.softness, target)
                    t += step
                }
            }
        }
    }

    /** 在 (cx,cy) 处盖一个半径 r 的圆盘：weight 内向 target 混合，只写包围盒局部。 */
    private fun stampDisc(
        out: FloatArray, w: Int, h: Int,
        cx: Float, cy: Float, r: Float, softness: Float, target: Float
    ) {
        val x0 = floor(cx - r).toInt().coerceIn(0, w - 1)
        val x1 = ceil(cx + r).toInt().coerceIn(0, w - 1)
        val y0 = floor(cy - r).toInt().coerceIn(0, h - 1)
        val y1 = ceil(cy + r).toInt().coerceIn(0, h - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy) / r
                if (d >= 1f) continue
                val weight = if (softness <= 0f) 1f else ((1f - d) / softness).coerceIn(0f, 1f)
                if (weight <= 0f) continue
                val idx = y * w + x
                out[idx] = out[idx] * (1f - weight) + target * weight
            }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.StrokeLayerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/StrokeLayer.kt \
        app/src/test/java/com/mamba/picme/domain/matting/StrokeLayerTest.kt
git commit -m "feat(matting): add vector StrokeLayer with undo/redo replay"
```

---

### Task 4: 融合管线移除固定 sharpen（迁移到参数层）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/matting/MattingEngine.kt:115-138`

说明：FUSION 仅 IDPhotoViewModel 使用（见头部「关键事实」），无其他消费者受影响。本任务只改融合输出语义；Task 5 让 ViewModel 用 `EdgeParams()` 默认对比度 2.5 复现原行为，两任务一起交付行为不变。

- [ ] **Step 1: 修改 fusionMatting**

`MattingEngine.kt` 中 `fusionMatting` 的尾部：

```kotlin
        val fused = FloatArray(alphaSelfie.size) { i -> maxOf(alphaSelfie[i], alphaModnet[i]) }
        val refined = MaskPostProcessor.sharpenAlpha(fused, contrast = 2.5f)
        return MattingResult(alpha = refined, width = bitmap.width, height = bitmap.height)
```

改为：

```kotlin
        val fused = FloatArray(alphaSelfie.size) { i -> maxOf(alphaSelfie[i], alphaModnet[i]) }
        // 不做固定 sharpen：边缘锐化已迁移到证件照参数层（EdgeParams.DEFAULT_CONTRAST=2.5），
        // 此处返回未锐化的融合 alpha，供参数层/描边层后处理。
        return MattingResult(alpha = fused, width = bitmap.width, height = bitmap.height)
```

同时把 `fusionMatting` 的 KDoc 从「再 alpha 锐化收窄融合边缘」改为「融合结果不做固定锐化，交由证件照参数层处理」。

- [ ] **Step 2: 编译 + 跑 matting 全部单测**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.*"`
Expected: BUILD SUCCESSFUL，全部 PASS（engine 无 JVM 单测，纯语义迁移）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/MattingEngine.kt
git commit -m "refactor(matting): move fusion sharpen out of engine into edge param layer"
```

---

### Task 5: ViewModel 集成（参数层 + 描边层 + tab 状态）与坐标映射纯函数

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/idphoto/IdPhotoTab.kt`
- Modify: `app/src/main/java/com/mamba/picme/domain/matting/IDPhotoComposer.kt`（追加坐标映射纯函数）
- Modify: `app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/matting/IDPhotoComposerTest.kt`

说明：`MediaRepository` 接口过大，VM 单测造假成本高（spec §10 的 VM 测试项改由纯函数测试 + 手动闭环验证覆盖）；可测逻辑全部下沉 domain。

- [ ] **Step 1: 写坐标映射失败测试**

在 `IDPhotoComposerTest.kt` 追加：

```kotlin
    @Test
    fun `frameToSource maps frame position into crop window`() {
        // cropRect = (100,200)-(300,600)（宽200 高400），画框 100x200：中心点应映射到 crop 中心
        val crop = IDPhotoComposer.CropRect(100, 200, 300, 600)
        val p = IDPhotoComposer.frameToSource(px = 50f, py = 100f, frameW = 100f, frameH = 200f, crop = crop)
        assertEquals(200f, p.x, 0.01f)
        assertEquals(400f, p.y, 0.01f)
    }

    @Test
    fun `frameToSource maps frame origin to crop left top`() {
        val crop = IDPhotoComposer.CropRect(100, 200, 300, 600)
        val p = IDPhotoComposer.frameToSource(px = 0f, py = 0f, frameW = 100f, frameH = 200f, crop = crop)
        assertEquals(100f, p.x, 0.01f)
        assertEquals(200f, p.y, 0.01f)
    }

    @Test
    fun `frameRadiusToSource scales by crop width over frame width`() {
        val crop = IDPhotoComposer.CropRect(0, 0, 200, 400)
        val r = IDPhotoComposer.frameRadiusToSource(radiusPx = 10f, frameW = 100f, crop = crop)
        assertEquals(20f, r, 0.01f)
    }
```

（文件已有 `IDPhotoComposer` 测试，确认 import 有 `org.junit.Assert.assertEquals`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.IDPhotoComposerTest"`
Expected: 编译失败（函数未定义）

- [ ] **Step 3: 在 IDPhotoComposer 追加纯函数**

```kotlin
    /** 预览画框坐标（px，画框把 [crop] 区域拉伸到 frameW×frameH）→ 原图像素坐标。 */
    fun frameToSource(px: Float, py: Float, frameW: Float, frameH: Float, crop: CropRect): StrokePoint {
        val cropW = (crop.right - crop.left).toFloat()
        val cropH = (crop.bottom - crop.top).toFloat()
        return StrokePoint(
            x = crop.left + px / frameW.coerceAtLeast(1f) * cropW,
            y = crop.top + py / frameH.coerceAtLeast(1f) * cropH
        )
    }

    /** 画框内笔刷半径（px）→ 原图像素半径（按 crop 宽 / 画框宽缩放）。 */
    fun frameRadiusToSource(radiusPx: Float, frameW: Float, crop: CropRect): Float {
        val cropW = (crop.right - crop.left).toFloat()
        return radiusPx * cropW / frameW.coerceAtLeast(1f)
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.IDPhotoComposerTest"`
Expected: PASS

- [ ] **Step 5: 创建 IdPhotoTab.kt**

```kotlin
package com.mamba.picme.features.idphoto

/** 证件照页底部面板 tab。 */
enum class IdPhotoTab { BG_COLOR, SIZE, EDGE, REPAIR }
```

- [ ] **Step 6: 改造 IDPhotoViewModel**

改动点（完整语义如下，逐段编辑现有文件）：

a) import 追加：

```kotlin
import com.mamba.picme.domain.matting.BrushStroke
import com.mamba.picme.domain.matting.EdgeParams
import com.mamba.picme.domain.matting.MaskPostProcessor
import com.mamba.picme.domain.matting.StrokeLayer
import com.mamba.picme.domain.matting.StrokeMode
import com.mamba.picme.domain.matting.StrokePoint
```

b) `State.Ready` 增加字段：

```kotlin
            val selectedColorIndex: Int = 0,
            val selectedSizeIndex: Int = 0,
            val activeTab: IdPhotoTab = IdPhotoTab.BG_COLOR,
            val edgeParams: EdgeParams = EdgeParams(),
            val strokeVersion: Int = 0,
            val canUndoStroke: Boolean = false,
            val canRedoStroke: Boolean = false,
            val isSaving: Boolean = false,
            val error: String? = null
```

c) 缓存与描边层成员（替换现有 `previewBaseCache` 声明处）：

```kotlin
    /** 预览底图缓存键：底色 + 参数层 + 描边版本。手势只改变换参数，不重建底图，保证跟手。 */
    private data class PreviewKey(val colorIndex: Int, val params: EdgeParams, val strokeVersion: Int)

    private var previewBaseCache: Pair<PreviewKey, Bitmap>? = null
    private var adjustedAlphaCache: Pair<PreviewKey, FloatArray>? = null
    private val strokeLayer = StrokeLayer()

    /** 进行中的描边（源图像素坐标，UI 拖完一笔后提交）。 */
    private var activeStrokePoints: MutableList<StrokePoint>? = null
    private var activeStrokeMode: StrokeMode = StrokeMode.RESTORE
    private var activeStrokeRadius: Float = 0f
    private var activeStrokeSoftness: Float = 0f
```

d) `previewBase()` 改用 adjustedAlpha + 新缓存键：

```kotlin
    /** 参数层 + 描边层叠加后的 alpha（按 PreviewKey 缓存）。 */
    private fun adjustedAlpha(current: State.Ready): FloatArray {
        val key = PreviewKey(current.selectedColorIndex, current.edgeParams, current.strokeVersion)
        adjustedAlphaCache?.takeIf { it.first == key }?.let { return it.second }
        val paramApplied = MaskPostProcessor.adjustEdges(
            current.alpha, current.alphaWidth, current.alphaHeight, current.edgeParams
        )
        val adjusted = strokeLayer.replayOnto(paramApplied, current.alphaWidth, current.alphaHeight)
        adjustedAlphaCache = key to adjusted
        return adjusted
    }

    /** 预览底图（original+adjustedAlpha 按当前底色合成，原图尺寸）；按 PreviewKey 缓存，跨手势复用。 */
    suspend fun previewBase(): Bitmap? = withContext(Dispatchers.Default) {
        val current = _state.value as? State.Ready ?: return@withContext null
        val key = PreviewKey(current.selectedColorIndex, current.edgeParams, current.strokeVersion)
        previewBaseCache?.takeIf { it.first == key }?.let { return@withContext it.second }
        val base = BackgroundComposer.apply(
            current.originalBitmap, adjustedAlpha(current),
            current.originalBitmap.width, current.originalBitmap.height,
            IDPhotoSpecs.COLORS[current.selectedColorIndex].argb
        )
        previewBaseCache = key to base
        base
    }
```

e) 新增动作方法：

```kotlin
    fun selectTab(tab: IdPhotoTab) {
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(activeTab = tab)
    }

    /** 滑块松手时调用（UI 在 onValueChangeFinished 触发，天然防抖）。 */
    fun setEdgeParams(params: EdgeParams) {
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(edgeParams = params)
    }

    fun resetEdgeParams() = setEdgeParams(EdgeParams())

    /** 开始一笔涂抹（[radiusPx]/[softness] 已换算为源图像素坐标系）。 */
    fun beginStroke(mode: StrokeMode, radiusPx: Float, softness: Float) {
        if (_state.value !is State.Ready) return
        activeStrokeMode = mode
        activeStrokeRadius = radiusPx
        activeStrokeSoftness = softness
        activeStrokePoints = mutableListOf()
    }

    /** 追加一个源图像素坐标点（UI 经 [IDPhotoComposer.frameToSource] 换算后传入）。 */
    fun appendStrokePoint(point: StrokePoint) {
        activeStrokePoints?.add(point)
    }

    /** 结束一笔：提交描边层，strokeVersion+1 使缓存失效触发底图重建。 */
    fun endStroke() {
        val points = activeStrokePoints ?: return
        activeStrokePoints = null
        if (points.isEmpty()) return
        val current = _state.value as? State.Ready ?: return
        strokeLayer.addStroke(
            BrushStroke(activeStrokeMode, activeStrokeRadius, activeStrokeSoftness, points.toList())
        )
        _state.value = current.copy(
            strokeVersion = current.strokeVersion + 1,
            canUndoStroke = strokeLayer.canUndo,
            canRedoStroke = strokeLayer.canRedo
        )
    }

    /** 是否有进行中的描边（UI 判断是否需要画进行态覆盖层）。 */
    fun hasActiveStroke(): Boolean = activeStrokePoints != null

    fun undoStroke() {
        val current = _state.value as? State.Ready ?: return
        if (!strokeLayer.undo()) return
        _state.value = current.copy(
            strokeVersion = current.strokeVersion + 1,
            canUndoStroke = strokeLayer.canUndo,
            canRedoStroke = strokeLayer.canRedo
        )
    }

    fun redoStroke() {
        val current = _state.value as? State.Ready ?: return
        if (!strokeLayer.redo()) return
        _state.value = current.copy(
            strokeVersion = current.strokeVersion + 1,
            canUndoStroke = strokeLayer.canUndo,
            canRedoStroke = strokeLayer.canRedo
        )
    }

    fun clearStrokes() {
        val current = _state.value as? State.Ready ?: return
        if (strokeLayer.count == 0) return
        strokeLayer.clear()
        _state.value = current.copy(
            strokeVersion = current.strokeVersion + 1,
            canUndoStroke = false,
            canRedoStroke = false
        )
    }
```

f) `load()` 开头重置会话状态（在 `previewBaseCache = null` 一行处扩展）：

```kotlin
        previewBaseCache = null
        adjustedAlphaCache = null
        strokeLayer.clear()
        activeStrokePoints = null
```

- [ ] **Step 7: 编译 + 全量单测**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.*"`
Expected: BUILD SUCCESSFUL，全部 PASS

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoViewModel.kt \
        app/src/main/java/com/mamba/picme/features/idphoto/IdPhotoTab.kt \
        app/src/main/java/com/mamba/picme/domain/matting/IDPhotoComposer.kt \
        app/src/test/java/com/mamba/picme/domain/matting/IDPhotoComposerTest.kt
git commit -m "feat(idphoto): wire edge params and stroke layer into view model"
```

---

### Task 6: i18n 字符串（三语）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（英文，id_photo_* 区块，约 121 行后）
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: 英文（values/strings.xml，追加在 id_photo_color_white 之后）**

```xml
    <string name="id_photo_tab_color">Color</string>
    <string name="id_photo_tab_size">Size</string>
    <string name="id_photo_tab_edge">Edge</string>
    <string name="id_photo_tab_repair">Repair</string>
    <string name="id_photo_edge_feather">Feather</string>
    <string name="id_photo_edge_shrink_expand">Shrink / Expand</string>
    <string name="id_photo_edge_contrast">Edge contrast</string>
    <string name="id_photo_edge_reset">Reset</string>
    <string name="id_photo_repair_restore">Restore</string>
    <string name="id_photo_repair_erase">Erase</string>
    <string name="id_photo_repair_brush_size">Brush size</string>
    <string name="id_photo_repair_soft_edge">Soft edge</string>
    <string name="id_photo_repair_undo">Undo</string>
    <string name="id_photo_repair_redo">Redo</string>
    <string name="id_photo_repair_clear">Clear strokes</string>
    <string name="id_photo_repair_hint">Paint on the photo to restore or erase areas</string>
```

- [ ] **Step 2: 简中（values-zh-rCN）**

```xml
    <string name="id_photo_tab_color">底色</string>
    <string name="id_photo_tab_size">尺寸</string>
    <string name="id_photo_tab_edge">边缘</string>
    <string name="id_photo_tab_repair">修补</string>
    <string name="id_photo_edge_feather">羽化</string>
    <string name="id_photo_edge_shrink_expand">收缩 / 扩张</string>
    <string name="id_photo_edge_contrast">边缘对比度</string>
    <string name="id_photo_edge_reset">重置参数</string>
    <string name="id_photo_repair_restore">恢复</string>
    <string name="id_photo_repair_erase">擦除</string>
    <string name="id_photo_repair_brush_size">笔刷大小</string>
    <string name="id_photo_repair_soft_edge">软边</string>
    <string name="id_photo_repair_undo">撤销</string>
    <string name="id_photo_repair_redo">重做</string>
    <string name="id_photo_repair_clear">清除描边</string>
    <string name="id_photo_repair_hint">在照片上涂抹，恢复或擦除局部区域</string>
```

- [ ] **Step 3: 繁中（values-zh-rTW）**

```xml
    <string name="id_photo_tab_color">底色</string>
    <string name="id_photo_tab_size">尺寸</string>
    <string name="id_photo_tab_edge">邊緣</string>
    <string name="id_photo_tab_repair">修補</string>
    <string name="id_photo_edge_feather">羽化</string>
    <string name="id_photo_edge_shrink_expand">收縮 / 擴張</string>
    <string name="id_photo_edge_contrast">邊緣對比度</string>
    <string name="id_photo_edge_reset">重設參數</string>
    <string name="id_photo_repair_restore">恢復</string>
    <string name="id_photo_repair_erase">擦除</string>
    <string name="id_photo_repair_brush_size">筆刷大小</string>
    <string name="id_photo_repair_soft_edge">軟邊</string>
    <string name="id_photo_repair_undo">復原</string>
    <string name="id_photo_repair_redo">重做</string>
    <string name="id_photo_repair_clear">清除筆畫</string>
    <string name="id_photo_repair_hint">在照片上塗抹，恢復或擦除局部區域</string>
```

- [ ] **Step 4: 编译验证资源**

Run: `./gradlew :app:compileDebugKotlin`（或 `:app:processDebugResources`）
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(idphoto): add edge/repair panel strings in three languages"
```

---

### Task 7: UI 面板组件（EdgePanel / RepairPanel / TabRow）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/idphoto/components/IdPhotoTabRow.kt`
- Create: `app/src/main/java/com/mamba/picme/features/idphoto/components/EdgePanel.kt`
- Create: `app/src/main/java/com/mamba/picme/features/idphoto/components/RepairPanel.kt`

配色对齐现有 `SizeChipRow`（显式深色配色），slider 拖动中只更新本地态、松手才回调 ViewModel（天然防抖）。

- [ ] **Step 1: IdPhotoTabRow.kt**

```kotlin
package com.mamba.picme.features.idphoto.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.features.idphoto.IdPhotoTab

/** 底部 4-tab 行：底色/尺寸/边缘/修补。 */
@Composable
fun IdPhotoTabRow(selected: IdPhotoTab, onSelect: (IdPhotoTab) -> Unit) {
    @StringRes val labels = mapOf(
        IdPhotoTab.BG_COLOR to R.string.id_photo_tab_color,
        IdPhotoTab.SIZE to R.string.id_photo_tab_size,
        IdPhotoTab.EDGE to R.string.id_photo_tab_edge,
        IdPhotoTab.REPAIR to R.string.id_photo_tab_repair
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        IdPhotoTab.entries.forEach { tab ->
            FilterChip(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                label = {
                    Text(
                        stringResource(labels.getValue(tab)),
                        color = if (tab == selected) Color.Black else Color.White
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF2A2A2A),
                    selectedContainerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
```

- [ ] **Step 2: EdgePanel.kt**

```kotlin
package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.domain.matting.EdgeParams
import kotlin.math.roundToInt

/**
 * 边缘参数面板：羽化 / 收缩扩张 / 边缘对比度。
 * 拖动中只更新本地滑块态，松手（onValueChangeFinished）才回调，避免每帧重建底图。
 */
@Composable
fun EdgePanel(
    params: EdgeParams,
    onParamsChange: (EdgeParams) -> Unit,
    onReset: () -> Unit
) {
    var feather by remember(params) { mutableFloatStateOf(params.featherRadiusPx.toFloat()) }
    var shrinkExpand by remember(params) { mutableFloatStateOf(params.shrinkExpandPx.toFloat()) }
    var contrast by remember(params) { mutableFloatStateOf(params.contrast) }

    val sliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = Color(0xFF3A3A3A)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        EdgeSlider(
            label = stringResource(R.string.id_photo_edge_feather),
            value = feather,
            valueRange = 0f..EdgeParams.MAX_FEATHER_PX.toFloat(),
            display = "${feather.roundToInt()}px",
            onValueChange = { feather = it },
            onFinished = {
                onParamsChange(params.copy(featherRadiusPx = feather.roundToInt()))
            },
            colors = sliderColors
        )
        EdgeSlider(
            label = stringResource(R.string.id_photo_edge_shrink_expand),
            value = shrinkExpand,
            valueRange = -EdgeParams.MAX_SHRINK_EXPAND_PX.toFloat()..EdgeParams.MAX_SHRINK_EXPAND_PX.toFloat(),
            display = "${shrinkExpand.roundToInt()}px",
            onValueChange = { shrinkExpand = it },
            onFinished = {
                onParamsChange(params.copy(shrinkExpandPx = shrinkExpand.roundToInt()))
            },
            colors = sliderColors
        )
        EdgeSlider(
            label = stringResource(R.string.id_photo_edge_contrast),
            value = contrast,
            valueRange = EdgeParams.MIN_CONTRAST..EdgeParams.MAX_CONTRAST,
            display = "%.1f".format(contrast),
            onValueChange = { contrast = it },
            onFinished = { onParamsChange(params.copy(contrast = contrast)) },
            colors = sliderColors
        )
        TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.id_photo_edge_reset), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EdgeSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
    colors: androidx.compose.material3.SliderColors
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        Text(display, color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onFinished,
        valueRange = valueRange,
        colors = colors,
        modifier = Modifier.fillMaxWidth()
    )
}
```

- [ ] **Step 3: RepairPanel.kt**

```kotlin
package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.matting.StrokeMode

/** 修补面板：恢复/擦除模式 + 笔刷大小 + 软边 + 撤销/重做/清除。 */
@Composable
fun RepairPanel(
    mode: StrokeMode,
    brushSizePx: Float,
    softEdge: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    hasStrokes: Boolean,
    onModeChange: (StrokeMode) -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onSoftEdgeChange: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                StrokeMode.RESTORE to R.string.id_photo_repair_restore,
                StrokeMode.ERASE to R.string.id_photo_repair_erase
            ).forEach { (m, labelRes) ->
                FilterChip(
                    selected = mode == m,
                    onClick = { onModeChange(m) },
                    label = {
                        Text(
                            stringResource(labelRes),
                            color = if (mode == m) Color.Black else Color.White
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF2A2A2A),
                        selectedContainerColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.id_photo_repair_brush_size),
                color = Color.White, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f))
            Text("${brushSizePx.toInt()}px", color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = brushSizePx,
            onValueChange = onBrushSizeChange,
            valueRange = 8f..80f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color(0xFF3A3A3A)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.id_photo_repair_soft_edge),
                color = Color.White, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f))
            Switch(checked = softEdge, onCheckedChange = onSoftEdgeChange)
        }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onUndo, enabled = canUndo) {
                Text(stringResource(R.string.id_photo_repair_undo))
            }
            TextButton(onClick = onRedo, enabled = canRedo) {
                Text(stringResource(R.string.id_photo_repair_redo))
            }
            TextButton(onClick = onClear, enabled = hasStrokes) {
                Text(stringResource(R.string.id_photo_repair_clear))
            }
        }
    }
}
```

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（组件暂未接线，IDE 会有 unused 警告，可接受）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/idphoto/components/IdPhotoTabRow.kt \
        app/src/main/java/com/mamba/picme/features/idphoto/components/EdgePanel.kt \
        app/src/main/java/com/mamba/picme/features/idphoto/components/RepairPanel.kt
git commit -m "feat(idphoto): add tab row, edge and repair panel components"
```

---

### Task 8: IDPhotoScreen 接线（tab 面板 + 涂抹手势）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoScreen.kt`

要点：
- `produceState` 的 key 从 `s.selectedColorIndex` 扩展为 `Triple(s.selectedColorIndex, s.edgeParams, s.strokeVersion)`。
- 修补 tab：预览画框手势切换为涂抹（`detectDragGestures`），其他 tab 保持 `detectTransformGestures`。
- 涂抹拖拽中：UI 本地维护「画框坐标点列」用于实时覆盖层（白=恢复/黑=擦除，40% 透明圆点连线）+ 笔刷圈光标；每个点同步经 `IDPhotoComposer.frameToSource` 换算喂给 `viewModel.appendStrokePoint`；`onDragEnd` 调 `viewModel.endStroke()`。
- 提示文案按 tab 切换：修补 tab 显示 `id_photo_repair_hint`，其他显示 `id_photo_drag_hint`。

- [ ] **Step 1: 重写 IDPhotoScreen 的 Ready 分支**

将 `is IDPhotoViewModel.State.Ready -> { ... }` 整块替换为（import 按需补齐：`detectDragGestures`、`IdPhotoTab`、三个新组件、`StrokeMode`、`StrokePoint`、`IDPhotoComposer`、`mutableStateListOf`、`mutableStateOf`、`Offset`、`StrokeCap`、`androidx.compose.ui.geometry.Offset`、`androidx.compose.foundation.gestures.detectDragGestures` 等）：

```kotlin
                is IDPhotoViewModel.State.Ready -> {
                    // 底图在加载/换底色/参数或描边变化时重建；手势只改 cropRect 绘制窗口，保证跟手
                    val base by produceState<android.graphics.Bitmap?>(
                        initialValue = null,
                        s.selectedColorIndex, s.edgeParams, s.strokeVersion
                    ) {
                        value = viewModel.previewBase()
                    }
                    // 修补 tab 的本地交互态（画框坐标系，仅用于实时覆盖层与笔刷光标）
                    var brushMode by remember { mutableStateOf(StrokeMode.ERASE) }
                    var brushSize by remember { mutableFloatStateOf(32f) }
                    var softEdge by remember { mutableStateOf(false) }
                    val overlayPoints = remember { mutableStateListOf<Offset>() }
                    var cursor by remember { mutableStateOf<Offset?>(null) }

                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            val bmp = base
                            val cropRect = viewModel.currentCropRect()
                            if (bmp != null && cropRect != null) {
                                val sizeSpec = IDPhotoSpecs.SIZES[s.selectedSizeIndex]
                                val frameW = 220.dp
                                val frameH = frameW * sizeSpec.heightPx / sizeSpec.widthPx
                                val repairing = s.activeTab == IdPhotoTab.REPAIR
                                Box(
                                    modifier = Modifier
                                        .size(frameW, frameH)
                                        .background(Color.White, RoundedCornerShape(4.dp))
                                        .clip(RoundedCornerShape(4.dp))
                                        .then(
                                            if (repairing) {
                                                Modifier.pointerInput(cropRect) {
                                                    detectDragGestures(
                                                        onDragStart = { start ->
                                                            overlayPoints.clear()
                                                            cursor = start
                                                            val radiusSrc = IDPhotoComposer.frameRadiusToSource(
                                                                brushSize / 2f, size.width.toFloat(), cropRect
                                                            )
                                                            viewModel.beginStroke(
                                                                brushMode, radiusSrc,
                                                                if (softEdge) 0.5f else 0f
                                                            )
                                                        },
                                                        onDrag = { change, _ ->
                                                            change.consume()
                                                            cursor = change.position
                                                            overlayPoints.add(change.position)
                                                            viewModel.appendStrokePoint(
                                                                IDPhotoComposer.frameToSource(
                                                                    px = change.position.x,
                                                                    py = change.position.y,
                                                                    frameW = size.width.toFloat(),
                                                                    frameH = size.height.toFloat(),
                                                                    crop = cropRect
                                                                )
                                                            )
                                                        },
                                                        onDragEnd = {
                                                            cursor = null
                                                            overlayPoints.clear()
                                                            viewModel.endStroke()
                                                        },
                                                        onDragCancel = {
                                                            cursor = null
                                                            overlayPoints.clear()
                                                            viewModel.endStroke()
                                                        }
                                                    )
                                                }
                                            } else {
                                                Modifier.pointerInput(Unit) {
                                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                                        viewModel.transformBy(
                                                            dxFraction = pan.x / size.width,
                                                            dyFraction = pan.y / size.height,
                                                            zoomChange = gestureZoom
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                ) {
                                    val imageBmp = remember(bmp) { bmp.asImageBitmap() }
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawImage(
                                            image = imageBmp,
                                            srcOffset = IntOffset(cropRect.left, cropRect.top),
                                            srcSize = IntSize(
                                                cropRect.right - cropRect.left,
                                                cropRect.bottom - cropRect.top
                                            ),
                                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                                        )
                                        // 进行中的描边覆盖层：恢复=白、擦除=黑，40% 透明
                                        if (overlayPoints.isNotEmpty()) {
                                            val overlayColor =
                                                (if (brushMode == StrokeMode.RESTORE) Color.White else Color.Black)
                                                    .copy(alpha = 0.4f)
                                            for (i in 1 until overlayPoints.size) {
                                                drawLine(
                                                    color = overlayColor,
                                                    start = overlayPoints[i - 1],
                                                    end = overlayPoints[i],
                                                    strokeWidth = brushSize,
                                                    cap = StrokeCap.Round
                                                )
                                            }
                                        }
                                        // 笔刷圈光标
                                        cursor?.let { pos ->
                                            drawCircle(
                                                color = Color.White.copy(alpha = 0.8f),
                                                radius = brushSize / 2f,
                                                center = pos,
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            text = stringResource(
                                if (s.activeTab == IdPhotoTab.REPAIR) R.string.id_photo_repair_hint
                                else R.string.id_photo_drag_hint
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        IdPhotoTabRow(selected = s.activeTab, onSelect = viewModel::selectTab)
                        when (s.activeTab) {
                            IdPhotoTab.BG_COLOR ->
                                ColorSwatchRow(IDPhotoSpecs.COLORS, s.selectedColorIndex, viewModel::selectColor)
                            IdPhotoTab.SIZE ->
                                SizeChipRow(IDPhotoSpecs.SIZES, s.selectedSizeIndex, viewModel::selectSize)
                            IdPhotoTab.EDGE ->
                                EdgePanel(
                                    params = s.edgeParams,
                                    onParamsChange = viewModel::setEdgeParams,
                                    onReset = viewModel::resetEdgeParams
                                )
                            IdPhotoTab.REPAIR ->
                                RepairPanel(
                                    mode = brushMode,
                                    brushSizePx = brushSize,
                                    softEdge = softEdge,
                                    canUndo = s.canUndoStroke,
                                    canRedo = s.canRedoStroke,
                                    hasStrokes = s.strokeVersion > 0 || s.canUndoStroke,
                                    onModeChange = { brushMode = it },
                                    onBrushSizeChange = { brushSize = it },
                                    onSoftEdgeChange = { softEdge = it },
                                    onUndo = viewModel::undoStroke,
                                    onRedo = viewModel::redoStroke,
                                    onClear = viewModel::clearStrokes
                                )
                        }
                    }
                }
```

同时删除文件顶部 `@Suppress("LongMethod")` 注释里的「待重构」字样保留 Suppress（方法变长仍需要），并把旧的 `ColorSwatchRow`/`SizeChipRow` 直排两行调用删除（已并入 when 分支）。

- [ ] **Step 2: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 全量单测回归**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.*"`
Expected: 全部 PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoScreen.kt
git commit -m "feat(idphoto): wire tab panels and brush painting into screen"
```

---

### Task 9: 闭环验证 + 文档同步（[DOC-SYNC] 红线）

**Files:**
- Modify: `app/AGENTS.md`（证件照/matting 相关小节，更新为「边缘参数层 + 描边层」描述）

- [ ] **Step 1: 完整构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 更新 app/AGENTS.md**

在 matting/证件照相关段落补充：`MaskPostProcessor` 新增 `erode/dilate/adjustEdges`；`EdgeParams`（默认对比度 2.5 复现旧行为）；`StrokeLayer` 矢量描边撤销/重做；`MattingEngine` 融合管线不再固定 sharpen；证件照页底部 4-tab（底色/尺寸/边缘/修补）。

- [ ] **Step 3: 真机手动验证（需连接设备）**

Run: `./scripts/auto-dev-loop.sh`（或 `adb install` + 手动）
验证清单：
- 不调整任何参数时，证件照输出与旧版本视觉一致
- 边缘 tab 三个滑块松手后预览更新，服装边缘瑕疵可通过收缩+对比度改善
- 修补 tab 涂抹恢复/擦除生效，撤销/重做/清除正常
- 切换底色/尺寸后调整仍保留
- 保存 JPEG 为调整后效果

- [ ] **Step 4: Commit**

```bash
git add app/AGENTS.md
git commit -m "docs(app): sync idphoto edge params and stroke repair design"
```

---

## Self-Review 记录

- **Spec 覆盖**：参数层（Task 1/2）✅、描边层（Task 3）✅、sharpen 迁移（Task 4）✅、VM 状态扩展（Task 5）✅、UI 4-tab（Task 6/7/8）✅、保存复用 previewBase（Task 5 自动覆盖）✅、i18n（Task 6）✅、DOC-SYNC（Task 9）✅
- **与 spec 的有意偏差**（已在对应任务注明）：① 描边坐标用原图像素坐标而非归一化（alpha 尺寸会话内不可变，YAGNI）；② VM 单测改为纯函数测试 + 手动闭环（MediaRepository 造假成本高）；③ 滑块防抖用 `onValueChangeFinished` 而非 150ms 定时器（更简单等价）
- **类型一致性**：`StrokePoint`/`BrushStroke`/`StrokeMode`/`EdgeParams`/`IdPhotoTab`/`PreviewKey`/`frameToSource`/`frameRadiusToSource` 跨任务命名一致 ✅
