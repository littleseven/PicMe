# P1 一键去背景（u2netp）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在图片编辑页新增「一键去背景」：点击顶部栏按钮 → 本地 ONNX Runtime 跑 u2netp 出掩码 → 生成透明 PNG 抠图（棋盘格预览）/ 或合成纯色背景，作为非破坏性 `EditRecipe.cutout` 操作，可撤销/重做。

**Architecture:** 镜像 `MobileClipOnnxBackend` 的 ONNX Runtime 用法，新增 `domain/matting/` 包：`U2NetOnnxBackend`（推理）+ `MaskPostProcessor`（掩码二值化/上采样/羽化，纯数组核心可 JVM 单测）+ `CutoutComposer`/`BackgroundComposer`（Alpha→透明/合成，纯数组核心可 JVM 单测）+ `MattingModelResolver`（assets 源，未来切 ModelScope 零重构）+ `MattingEngine`（门面接口，便于测试）。`RecipeApplier` 新增 `applyCutout()` 阶段（在 GPU 滤镜后、markup 前）；`PhotoEditorViewModel` 新增 `removeBackground()` 把 cutout 写入 recipe 触发预览；保存路径按 `bgMode` 分支 PNG/JPEG。P1 不接人脸路由（P2），所有图都走 u2netp。

**Tech Stack:** Kotlin · ONNX Runtime 1.24.3（已在 app 依赖）· Coroutines · Jetpack Compose · Moshi（反射式，`PhotoEditRecipeRepository` 手写 JSONObject 序列化）· JUnit4 + mockk + Robolectric 4.14.1（Bitmap 相关 JVM 测试）。

**Spec:** `docs/superpowers/specs/2026-07-18-background-removal-matting-design.md`（P1 = §5 P1 行）。

**Scope note:** 本计划只覆盖 P1（u2netp）。P2（MODNet + 人脸路由）、P3（证件照专区）各自后续独立 plan。

---

## File Structure

**新建（`app/src/main/java/com/mamba/picme/`）：**
- `domain/matting/MattingResult.kt` — 推理结果（alpha FloatArray + 宽高）
- `domain/matting/MaskSource.kt` — `enum class MaskSource { U2NETP, MODNET }`（recipe 与 engine 共享）
- `domain/matting/MaskPostProcessor.kt` — object；掩码二值化/双线性上采样/羽化（纯 FloatArray 核心）
- `domain/matting/CutoutComposer.kt` — object；Alpha→透明 ARGB（纯 IntArray 核心 + Bitmap 包装）
- `domain/matting/BackgroundComposer.kt` — object；Alpha→合成纯色背景（纯 IntArray 核心 + Bitmap 包装）
- `domain/matting/U2NetPreprocessor.kt` — object；Bitmap→320 NCHW ImageNet 归一化（纯 IntArray 核心）
- `domain/matting/MattingModelResolver.kt` — 接口 + `AssetMattingModelResolver`（assets→filesDir 拷贝/缓存）
- `domain/matting/MattingEngine.kt` — 接口 + `MattingEngineImpl`（持有 `U2NetOnnxBackend`）
- `domain/matting/U2NetOnnxBackend.kt` — OrtSession 推理（照抄 `MobileClipOnnxBackend`，设备验证）
- `features/editor/CutoutRecipe.kt` — `CutoutRecipe` + `BgMode`（`MaskSource` 从 domain 导入）
- `features/editor/components/CheckerboardBackground.kt` — 透明预览的棋盘格 Composable

**新建资产：** `app/src/main/assets/matting/u2netp.onnx`（fp32，外部获取，见 Task 11）

**新建测试（`app/src/test/java/com/mamba/picme/`）：**
- `domain/matting/MaskPostProcessorTest.kt`
- `domain/matting/CutoutComposerTest.kt`
- `domain/matting/BackgroundComposerTest.kt`
- `domain/matting/U2NetPreprocessorTest.kt`
- `domain/matting/AssetMattingModelResolverTest.kt`
- `features/editor/RecipeApplierCutoutTest.kt`（Robolectric，真实 Bitmap）
- `features/editor/CutoutRecipeSerializationTest.kt`

**修改：**
- `features/editor/EditRecipe.kt:8,10-21` — 加 `cutout` 字段，`RECIPE_VERSION` 1→2
- `data/repository/PhotoEditRecipeRepository.kt:56-87,90-146` — `toJson`/`fromJson` 处理 cutout
- `features/editor/RecipeApplier.kt:24-27` — 构造增 `mattingEngine`（可空，默认 null）；新增 `applyCutout`
- `features/editor/PhotoEditorViewModel.kt:44-51,252-300,308-326` — 构造增 `mattingEngine`；`processPreview`/`save` 插入 applyCutout；`saveBitmapToMediaStore` PNG 分支；新增 `removeBackground()`
- `features/editor/PhotoEditorViewModelFactory.kt:44-56` — 构造 `MattingEngineImpl(appContext)` 传入 VM
- `features/editor/components/EditorTopBar.kt:31-103` — 新增 `onRemoveBackground` 图标按钮
- `features/editor/PhotoEditorScreen.kt:66-105` — 顶部栏回调 + 棋盘格预览
- `res/values/strings.xml` + `res/values-zh-rCN/strings.xml` + `res/values-zh-rTW/strings.xml` — 新增文案

---

## Task 1: CutoutRecipe 数据模型 + EditRecipe 集成 + 序列化

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/editor/CutoutRecipe.kt`
- Create: `app/src/test/java/com/mamba/picme/features/editor/CutoutRecipeSerializationTest.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/EditRecipe.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/repository/PhotoEditRecipeRepository.kt`

- [ ] **Step 1: 写 CutoutRecipe 模型**

Create `app/src/main/java/com/mamba/picme/domain/matting/MaskSource.kt`:

```kotlin
package com.mamba.picme.domain.matting

/** 抠图掩码来源。P1 仅 U2NETP；P2 接入 MODNET。 */
enum class MaskSource { U2NETP, MODNET }
```

Create `app/src/main/java/com/mamba/picme/features/editor/CutoutRecipe.kt`:

```kotlin
package com.mamba.picme.features.editor

import com.mamba.picme.domain.matting.MaskSource

/** 抠图（去背景）配方。null 表示未启用去背景。 */
data class CutoutRecipe(
    val maskSource: MaskSource = MaskSource.U2NETP,
    val threshold: Float = 0.5f,
    val bgMode: BgMode = BgMode.TRANSPARENT,
    val bgColor: Int? = null,
    val feather: Int = 0
) {
    enum class BgMode { TRANSPARENT, COLOR, BLUR }
}
```

- [ ] **Step 2: EditRecipe 加字段并升版本**

Modify `EditRecipe.kt`: change `private const val RECIPE_VERSION = 1` → `= 2`, and add the `cutout` field after `markup`:

```kotlin
private const val RECIPE_VERSION = 2

data class EditRecipe(
    val sourceUri: String,
    val crop: CropRecipe = CropRecipe(),
    val adjustments: AdjustmentRecipe = AdjustmentRecipe(),
    val beauty: BeautySettings = BeautySettings(enabled = true),
    val colorFilter: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter = StyleFilter.NONE,
    val markup: List<MarkupAction> = emptyList(),
    val cutout: CutoutRecipe? = null,
    val version: Int = RECIPE_VERSION
) {
    companion object
}
```

Add import `import com.mamba.picme.domain.matting.MaskSource` is NOT needed here (CutoutRecipe carries it). No new import in EditRecipe.kt beyond `CutoutRecipe` being same package.

- [ ] **Step 3: 序列化 cutout（toJson + fromJson）**

In `PhotoEditRecipeRepository.kt`, add to imports:

```kotlin
import com.mamba.picme.domain.matting.MaskSource
import com.mamba.picme.features.editor.CutoutRecipe
```

In `toJson()` (inside the `JSONObject().apply { ... }`), after `put("markup", emptyList<String>())`, add:

```kotlin
            cutout?.let {
                put("cutout", JSONObject().apply {
                    put("maskSource", it.maskSource.name)
                    put("threshold", it.threshold)
                    put("bgMode", it.bgMode.name)
                    put("bgColor", it.bgColor)
                    put("feather", it.feather)
                })
            }
```

In `fromJson()` (the `return EditRecipe(...)`), add a `cutout =` named arg after `markup`/before `version`. Above the `return`, add parsing:

```kotlin
            val cutout = if (root.has("cutout")) {
                val c = root.getJSONObject("cutout")
                CutoutRecipe(
                    maskSource = try {
                        MaskSource.valueOf(c.optString("maskSource", "U2NETP"))
                    } catch (_: IllegalArgumentException) {
                        MaskSource.U2NETP
                    },
                    threshold = c.optDouble("threshold", 0.5).toFloat(),
                    bgMode = try {
                        CutoutRecipe.BgMode.valueOf(c.optString("bgMode", "TRANSPARENT"))
                    } catch (_: IllegalArgumentException) {
                        CutoutRecipe.BgMode.TRANSPARENT
                    },
                    bgColor = if (c.isNull("bgColor")) null else c.optInt("bgColor", 0).takeIf { c.has("bgColor") },
                    feather = c.optInt("feather", 0)
                )
            } else null
```

Then in the `return EditRecipe(...)` add `cutout = cutout,` (after `styleFilter = ...`). Leave `version = root.optInt("version", 1)`.

- [ ] **Step 4: 写失败测试**

Create `app/src/test/java/com/mamba/picme/features/editor/CutoutRecipeSerializationTest.kt`:

```kotlin
package com.mamba.picme.features.editor

import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.matting.MaskSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CutoutRecipeSerializationTest {

    private val repo = PhotoEditRecipeRepository(dao = com.mamba.picme.data.local.dao.PhotoEditRecipeDao::class.java
        .let { io.mockk.mockk(relaxed = true) })

    @Test
    fun `recipe without cutout round-trips to null cutout`() {
        val original = EditRecipe(sourceUri = "file://a.jpg")
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")
        assertNull(parsed.cutout)
    }

    @Test
    fun `recipe with transparent cutout round-trips`() {
        val original = EditRecipe(
            sourceUri = "file://a.jpg",
            cutout = CutoutRecipe(
                maskSource = MaskSource.U2NETP,
                threshold = 0.5f,
                bgMode = CutoutRecipe.BgMode.TRANSPARENT
            )
        )
        val json = repo.toJsonForTest(original)
        val parsed = PhotoEditRecipeRepository.fromJsonForTest(json, "file://a.jpg")
        val cutout = parsed.cutout
        assertNotNull(cutout)
        assertEquals(MaskSource.U2NETP, cutout!!.maskSource)
        assertEquals(0.5f, cutout.threshold, 0.0001f)
        assertEquals(CutoutRecipe.BgMode.TRANSPARENT, cutout.bgMode)
        assertNull(cutout.bgColor)
    }
}
```

To expose these for test, temporarily expose internal helpers. Add to `PhotoEditRecipeRepository`:

```kotlin
    /** 测试可见的序列化入口。 */
    internal fun toJsonForTest(recipe: EditRecipe): String = recipe.toJson()
```

And to the `companion object`:

```kotlin
        internal fun fromJsonForTest(json: String, fallbackSourceUri: String): EditRecipe =
            EditRecipe.fromJson(json, fallbackSourceUri)
```

- [ ] **Step 5: 运行测试**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.CutoutRecipeSerializationTest"`
Expected: 2 tests PASS.

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/MaskSource.kt \
  app/src/main/java/com/mamba/picme/features/editor/CutoutRecipe.kt \
  app/src/main/java/com/mamba/picme/features/editor/EditRecipe.kt \
  app/src/main/java/com/mamba/picme/data/repository/PhotoEditRecipeRepository.kt \
  app/src/test/java/com/mamba/picme/features/editor/CutoutRecipeSerializationTest.kt
git commit -m "feat(editor): 新增 CutoutRecipe 字段与序列化（一键去背景 P1）"
```

---

## Task 2: MaskPostProcessor（二值化 + 双线性上采样 + 羽化）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/MaskPostProcessor.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/matting/MaskPostProcessorTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/matting/MaskPostProcessorTest.kt`:

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MaskPostProcessorTest {

    @Test
    fun `binarize above threshold is 1 below is 0`() {
        val probs = floatArrayOf(0.2f, 0.6f, 0.5f, 0.9f)
        val out = MaskPostProcessor.binarize(probs, threshold = 0.5f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f), out, 0.0001f)
    }

    @Test
    fun `upsample 2x2 to 4x4 bilinear interpolates`() {
        // corners: 0 1
        //          0 1   -> center column interpolated ~0.5
        val alpha = floatArrayOf(0f, 1f, 0f, 1f)
        val out = MaskPostProcessor.upsample(alpha, srcW = 2, srcH = 2, dstW = 4, dstH = 4)
        assertEquals(16, out.size)
        // top-right corner stays 1, top-left stays 0
        assertEquals(1f, out[3], 0.01f)
        assertEquals(0f, out[0], 0.01f)
    }

    @Test
    fun `upsample same size returns copy`() {
        val alpha = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val out = MaskPostProcessor.upsample(alpha, 2, 2, 2, 2)
        assertArrayEquals(alpha, out, 0.0001f)
    }

    @Test
    fun `feather radius 0 returns copy`() {
        val alpha = floatArrayOf(1f, 0f, 0f, 1f)
        val out = MaskPostProcessor.feather(alpha, w = 2, h = 2, radius = 0)
        assertArrayEquals(alpha, out, 0.0001f)
    }

    @Test
    fun `feather smooths hard edge`() {
        // 1x4 strip: 1 1 0 0 ; radius 1 box blur -> middle values between 0 and 1
        val alpha = floatArrayOf(1f, 1f, 0f, 0f)
        val out = MaskPostProcessor.feather(alpha, w = 4, h = 1, radius = 1)
        // index 1 window {1,1,0} avg = 2/3 ; index 2 window {1,0,0} avg = 1/3
        assertEquals(2f / 3f, out[1], 0.01f)
        assertEquals(1f / 3f, out[2], 0.01f)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.MaskPostProcessorTest"`
Expected: FAIL（`MaskPostProcessor` 未定义 / unresolved reference）。

- [ ] **Step 3: 实现**

Create `app/src/main/java/com/mamba/picme/domain/matting/MaskPostProcessor.kt`:

```kotlin
package com.mamba.picme.domain.matting

/**
 * 掩码后处理：u2netp 输出的概率图 → 二值 Alpha → 上采样到原图 → 可选羽化。
 * 全部基于 FloatArray，可在纯 JVM 单测中验证（不依赖 Bitmap）。
 */
object MaskPostProcessor {

    /** 概率 >= threshold 记为 1（前景），否则 0。 */
    fun binarize(probabilities: FloatArray, threshold: Float): FloatArray =
        FloatArray(probabilities.size) { if (probabilities[it] >= threshold) 1f else 0f }

    /** 双线性上采样 (srcW,srcH) -> (dstW,dstH)。尺寸相同则返回拷贝。 */
    fun upsample(alpha: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        if (srcW == dstW && srcH == dstH) return alpha.copyOf()
        val out = FloatArray(dstW * dstH)
        val xRatio = (srcW - 1).toFloat() / dstW.coerceAtLeast(1)
        val yRatio = (srcH - 1).toFloat() / dstH.coerceAtLeast(1)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val sx = x * xRatio
                val sy = y * yRatio
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val y0 = sy.toInt().coerceIn(0, srcH - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                val y1 = (y0 + 1).coerceIn(0, srcH - 1)
                val fx = sx - x0
                val fy = sy - y0
                val v00 = alpha[y0 * srcW + x0]
                val v01 = alpha[y0 * srcW + x1]
                val v10 = alpha[y1 * srcW + x0]
                val v11 = alpha[y1 * srcW + x1]
                val top = v00 + (v01 - v00) * fx
                val bottom = v10 + (v11 - v10) * fx
                out[y * dstW + x] = top + (bottom - top) * fy
            }
        }
        return out
    }

    /** 可分离盒滤波羽化。radius<=0 返回拷贝。 */
    fun feather(alpha: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return alpha.copyOf()
        val tmp = FloatArray(alpha.size)
        // 水平
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (dx in -radius..radius) {
                    val sx = x + dx
                    if (sx in 0 until w) {
                        sum += alpha[y * w + sx]
                        count++
                    }
                }
                tmp[y * w + x] = sum / count
            }
        }
        val out = FloatArray(alpha.size)
        // 垂直
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (dy in -radius..radius) {
                    val sy = y + dy
                    if (sy in 0 until h) {
                        sum += tmp[sy * w + x]
                        count++
                    }
                }
                out[y * w + x] = sum / count
            }
        }
        return out
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.MaskPostProcessorTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/MaskPostProcessor.kt \
  app/src/test/java/com/mamba/picme/domain/matting/MaskPostProcessorTest.kt
git commit -m "feat(matting): MaskPostProcessor 二值化/上采样/羽化（纯数组核心 + 单测）"
```

---

## Task 3: CutoutComposer（Alpha → 透明 ARGB）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/CutoutComposer.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/matting/CutoutComposerTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/matting/CutoutComposerTest.kt`:

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class CutoutComposerTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `composeTransparent keeps rgb and sets alpha from mask`() {
        // opaque red pixel, full-alpha mask -> fully opaque
        val pixels = intArrayOf(argb(255, 255, 0, 0))
        val alpha = floatArrayOf(1f)
        val out = CutoutComposer.composeTransparent(pixels, alpha)
        assertEquals(argb(255, 255, 0, 0), out[0])
    }

    @Test
    fun `composeTransparent zero alpha makes pixel fully transparent`() {
        val pixels = intArrayOf(argb(255, 10, 20, 30))
        val out = CutoutComposer.composeTransparent(pixels, floatArrayOf(0f))
        // alpha channel 0, rgb preserved
        assertEquals(0, (out[0] ushr 24) and 0xFF)
        assertEquals(10, (out[0] shr 16) and 0xFF)
    }

    @Test
    fun `composeTransparent fractional alpha quantizes to 0_255`() {
        val pixels = intArrayOf(argb(255, 0, 0, 0))
        val out = CutoutComposer.composeTransparent(pixels, floatArrayOf(0.5f))
        val a = (out[0] ushr 24) and 0xFF
        assertEquals(128, a) // 0.5*255 = 127.5 -> +0.5 -> 128
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.CutoutComposerTest"`
Expected: FAIL（unresolved reference）。

- [ ] **Step 3: 实现**

Create `app/src/main/java/com/mamba/picme/domain/matting/CutoutComposer.kt`:

```kotlin
package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** Alpha → 透明 ARGB。核心 [composeTransparent] 基于数组，可 JVM 单测。 */
object CutoutComposer {

    /** 保留原 RGB，按 alpha（0..1）重写 Alpha 通道，返回透明 ARGB IntArray。 */
    fun composeTransparent(pixels: IntArray, alpha: FloatArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val a = (alpha[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        return out
    }

    /** 包装：把 source bitmap 按 alpha（width×height）合成成透明 Bitmap。 */
    fun apply(source: Bitmap, alpha: FloatArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = composeTransparent(pixels, alpha)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setHasAlpha(true)
        result.setPixels(out, 0, width, 0, 0, width, height)
        return result
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.CutoutComposerTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/CutoutComposer.kt \
  app/src/test/java/com/mamba/picme/domain/matting/CutoutComposerTest.kt
git commit -m "feat(matting): CutoutComposer Alpha→透明 ARGB"
```

---

## Task 4: BackgroundComposer（Alpha → 合成纯色背景）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/BackgroundComposer.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/matting/BackgroundComposerTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/matting/BackgroundComposerTest.kt`:

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundComposerTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `full alpha keeps foreground pixel opaque`() {
        val fg = argb(255, 100, 50, 25)
        val out = BackgroundComposer.composeOnColor(intArrayOf(fg), floatArrayOf(1f), bgColor = argb(255, 0, 0, 255))
        assertEquals(argb(255, 100, 50, 25), out[0])
    }

    @Test
    fun `zero alpha yields background color opaque`() {
        val fg = argb(255, 100, 50, 25)
        val out = BackgroundComposer.composeOnColor(intArrayOf(fg), floatArrayOf(0f), bgColor = argb(255, 0, 0, 255))
        assertEquals(argb(255, 0, 0, 255), out[0])
    }

    @Test
    fun `half alpha blends foreground and background`() {
        val fg = argb(255, 200, 0, 0)
        val bg = argb(255, 0, 0, 0)
        val out = BackgroundComposer.composeOnColor(intArrayOf(fg), floatArrayOf(0.5f), bg)
        val r = (out[0] shr 16) and 0xFF
        assertEquals(100, r) // 200*0.5 + 0*0.5 = 100
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.BackgroundComposerTest"`
Expected: FAIL。

- [ ] **Step 3: 实现**

Create `app/src/main/java/com/mamba/picme/domain/matting/BackgroundComposer.kt`:

```kotlin
package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** Alpha → 合成到不透明纯色背景。核心 [composeOnColor] 基于数组，可 JVM 单测。 */
object BackgroundComposer {

    /** alpha（0..1）混合前景像素与 bgColor，输出不透明 ARGB IntArray。 */
    fun composeOnColor(pixels: IntArray, alpha: FloatArray, bgColor: Int): IntArray {
        val out = IntArray(pixels.size)
        val br = (bgColor shr 16) and 0xFF
        val bg = (bgColor shr 8) and 0xFF
        val bb = bgColor and 0xFF
        for (i in pixels.indices) {
            val a = alpha[i].coerceIn(0f, 1f)
            val p = pixels[i]
            val r = (((p shr 16) and 0xFF) * a + br * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) * a + bg * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
            val b = ((p and 0xFF) * a + bb * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    fun apply(source: Bitmap, alpha: FloatArray, width: Int, height: Int, bgColor: Int): Bitmap {
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = composeOnColor(pixels, alpha, bgColor)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, width, 0, 0, width, height)
        return result
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.BackgroundComposerTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/BackgroundComposer.kt \
  app/src/test/java/com/mamba/picme/domain/matting/BackgroundComposerTest.kt
git commit -m "feat(matting): BackgroundComposer Alpha→纯色背景合成"
```

---

## Task 5: U2Net 预处理（可测）+ U2NetOnnxBackend（设备验证）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/U2NetPreprocessor.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/matting/U2NetPreprocessorTest.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/matting/U2NetOnnxBackend.kt`

- [ ] **Step 1: 写失败测试（预处理数学）**

Create `app/src/test/java/com/mamba/picme/domain/matting/U2NetPreprocessorTest.kt`:

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class U2NetPreprocessorTest {

    @Test
    fun `toNchw normalizes rgb with imagenet mean std in NCHW layout`() {
        // 单像素纯白图 1x1
        val white = intArrayOf((255 shl 16) or (255 shl 8) or 255 or 0xFF000000.toInt())
        val out = U2NetPreprocessor.toNchw(white, size = 1)
        assertEquals(3, out.size)
        // (1.0 - 0.485) / 0.229
        val expectedR = (1f - 0.485f) / 0.229f
        assertEquals(expectedR, out[0], 0.001f)
        // G plane at index plane=1*1*1
        val expectedG = (1f - 0.456f) / 0.224f
        assertEquals(expectedG, out[1], 0.001f)
        val expectedB = (1f - 0.406f) / 0.225f
        assertEquals(expectedB, out[2], 0.001f)
    }

    @Test
    fun `toNchw 320 has expected length`() {
        val pixels = IntArray(320 * 320) { 0xFF000000.toInt() }
        val out = U2NetPreprocessor.toNchw(pixels, size = 320)
        assertEquals(3 * 320 * 320, out.size)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.U2NetPreprocessorTest"`
Expected: FAIL。

- [ ] **Step 3: 实现预处理**

Create `app/src/main/java/com/mamba/picme/domain/matting/U2NetPreprocessor.kt`:

```kotlin
package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** u2netp 输入预处理：320×320 RGB → ImageNet 归一化 NCHW。核心 [toNchw] 基于数组，可 JVM 单测。 */
object U2NetPreprocessor {
    const val INPUT_SIZE = 320
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** pixels：ARGB IntArray，长度 = size*size。返回 NCHW [3*size*size]。 */
    fun toNchw(pixels: IntArray, size: Int = INPUT_SIZE): FloatArray {
        val plane = size * size
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i] = (r - MEAN[0]) / STD[0]
            out[plane + i] = (g - MEAN[1]) / STD[1]
            out[2 * plane + i] = (b - MEAN[2]) / STD[2]
        }
        return out
    }

    /** 把 Bitmap 缩放到 size×size（接受轻微长宽比失真，掩码可整体映射回原图），返回 NCHW FloatArray。 */
    fun bitmapToNchw(source: Bitmap, size: Int = INPUT_SIZE): FloatArray {
        val scaled = if (source.width == size && source.height == size) source
        else Bitmap.createScaledBitmap(source, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== source) scaled.recycle()
        return toNchw(pixels, size)
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.U2NetPreprocessorTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: 实现 ONNX 推理后端（照抄 MobileClipOnnxBackend；设备验证）**

Create `app/src/main/java/com/mamba/picme/domain/matting/U2NetOnnxBackend.kt`:

```kotlin
package com.mamba.picme.domain.matting

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/** u2netp ONNX Runtime 推理后端。返回概率图 FloatArray（长度 INPUT_SIZE^2，0..1），失败返回 null。 */
class U2NetOnnxBackend(
    context: Context,
    private val resolver: MattingModelResolver
) {
    companion object {
        private const val TAG = "PoLang:Matting"
        private const val MODEL_ID = "u2netp-onnx"
        private const val MODEL_FILE = "u2netp.onnx"
    }

    private val appContext = context.applicationContext
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    val isInitialized: Boolean get() = session != null

    suspend fun initialize(): Boolean {
        if (session != null) return true
        val modelFile = resolver.resolve(MODEL_ID) ?: run {
            Log.w(TAG, "u2netp model not found via resolver")
            return false
        }
        return try {
            val options = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
            }
            session = env.createSession(modelFile.absolutePath, options)
            Log.i(TAG, "U2NetOnnxBackend initialized (${modelFile.name})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init u2netp session", e)
            release()
            false
        }
    }

    /** 推理；返回概率图（0..1），长度 INPUT_SIZE*INPUT_SIZE。 */
    fun infer(bitmap: Bitmap): FloatArray? {
        val s = session ?: run {
            Log.w(TAG, "session not initialized")
            return null
        }
        return try {
            val nchw = U2NetPreprocessor.bitmapToNchw(bitmap, U2NetPreprocessor.INPUT_SIZE)
            val shape = longArrayOf(
                1L, 3L,
                U2NetPreprocessor.INPUT_SIZE.toLong(),
                U2NetPreprocessor.INPUT_SIZE.toLong()
            )
            val inputName = s.inputNames.first()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(nchw), shape)
            try {
                s.run(mapOf(inputName to tensor)).use { results ->
                    val outputName = s.outputNames.first()
                    val raw = (results.get(outputName).get().value as Array<FloatArray>)[0]
                    // sigmoid（部分导出已含 sigmoid；对已饱和值幂等安全）
                    FloatArray(raw.size) { i ->
                        val v = raw[i]
                        if (v in 0f..1f) v else (1.0f / (1.0f + Math.exp((-v).toDouble())).toFloat())
                    }
                }
            } finally {
                tensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "u2netp inference failed", e)
            null
        }
    }

    fun release() {
        session?.close()
        session = null
        Log.i(TAG, "U2NetOnnxBackend session released (OrtEnvironment kept alive)")
    }
}
```

- [ ] **Step 6: 编译确认（推理本身在设备验证，Task 12）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（确认 OrtSession API 与 import 正确）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/U2NetPreprocessor.kt \
  app/src/main/java/com/mamba/picme/domain/matting/U2NetOnnxBackend.kt \
  app/src/test/java/com/mamba/picme/domain/matting/U2NetPreprocessorTest.kt
git commit -m "feat(matting): U2Net 预处理（可测）+ ONNX Runtime 推理后端"
```

---

## Task 6: MattingModelResolver（assets 源，未来切 ModelScope 零重构）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/MattingModelResolver.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/matting/AssetMattingModelResolverTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/matting/AssetMattingModelResolverTest.kt`:

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssetMattingModelResolverTest {

    @Test
    fun `resolve copies asset to filesDir and caches on second call`() {
        val tmpRoot = createTempDirectory(prefix = "matting_test")
        val fakeBytes = byteArrayOf(1, 2, 3, 4)
        val provider = object : AssetBytesProvider {
            override fun readBytes(path: String): ByteArray? = if (path == "matting/u2netp.onnx") fakeBytes else null
        }
        val resolver = AssetMattingModelResolver(modelDirRoot = tmpRoot, provider = provider)

        val first = resolver.resolveBlocking("u2netp-onnx", assetPath = "matting/u2netp.onnx", fileName = "u2netp.onnx")
        assertTrue(first != null)
        assertEquals(fakeBytes.toList(), first!!.readBytes().toList())

        // 第二次：provider 不再被读取即视为命中缓存
        val silentProvider = object : AssetBytesProvider {
            override fun readBytes(path: String): ByteArray? = error("cache miss: $path")
        }
        val resolver2 = AssetMattingModelResolver(modelDirRoot = tmpRoot, provider = silentProvider)
        val cached = resolver2.resolveBlocking("u2netp-onnx", "matting/u2netp.onnx", "u2netp.onnx")
        assertTrue(cached != null)
        assertArrayEquals(fakeBytes, cached!!.readBytes())

        tmpRoot.deleteRecursively()
    }

    private fun createTempDirectory(prefix: String): File =
        File.createTempFile(prefix, null).apply { delete(); mkdirs() }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.AssetMattingModelResolverTest"`
Expected: FAIL。

- [ ] **Step 3: 实现**

Create `app/src/main/java/com/mamba/picme/domain/matting/MattingModelResolver.kt`:

```kotlin
package com.mamba.picme.domain.matting

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 模型位置抽象：引擎只依赖 [resolve] 返回的 File，不关心来源（assets / ModelScope 下载）。 */
interface MattingModelResolver {
    /** 返回模型目录下的目标文件；不存在返回 null（由调用方决定引导下载）。 */
    suspend fun resolve(modelId: String): File?
}

/** 读取 assets 字节的抽象，便于单测注入。 */
fun interface AssetBytesProvider {
    fun readBytes(path: String): ByteArray?
}

/**
 * assets 源解析器（demo 阶段）：首次访问把 assets 中的模型拷贝到 filesDir，之后命中缓存。
 * 未来切 ModelScope：新增 DownloadSource 实现同接口即可，引擎零改动。
 */
class AssetMattingModelResolver(
    context: Context,
    private val provider: AssetBytesProvider = AssetBytesProvider { path ->
        context.assets.open(path).use { it.readBytes() }
    },
    private val modelDirRoot: File = File(context.filesDir, "llm_models")
) : MattingModelResolver {

    private val assetPaths = mapOf(
        "u2netp-onnx" to ("matting/u2netp.onnx" to "u2netp.onnx")
    )

    override suspend fun resolve(modelId: String): File? = withContext(Dispatchers.IO) {
        resolveBlocking(modelId, assetPaths[modelId]?.first, assetPaths[modelId]?.second)
    }

    /** 测试可见的同步版本。 */
    internal fun resolveBlocking(modelId: String, assetPath: String?, fileName: String?): File? {
        if (assetPath == null || fileName == null) return null
        val dir = File(modelDirRoot, modelId).apply { mkdirs() }
        val target = File(dir, fileName)
        if (target.exists() && target.length() > 0) return target
        val bytes = provider.readBytes(assetPath) ?: return null
        target.writeBytes(bytes)
        return target
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.AssetMattingModelResolverTest"`
Expected: 1 test PASS.

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/MattingModelResolver.kt \
  app/src/test/java/com/mamba/picme/domain/matting/AssetMattingModelResolverTest.kt
git commit -m "feat(matting): MattingModelResolver assets 源（可切 ModelScope）"
```

---

## Task 7: MattingResult + MattingEngine 门面（P1: 仅 u2netp）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/MattingResult.kt`
- Create: `app/src/main/java/com/mamba/picme/domain/matting/MattingEngine.kt`

- [ ] **Step 1: MattingResult**

Create `app/src/main/java/com/mamba/picme/domain/matting/MattingResult.kt`:

```kotlin
package com.mamba.picme.domain.matting

/** 抠图结果：alpha（0..1，width×height）已上采样到原图尺寸。 */
data class MattingResult(
    val alpha: FloatArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MattingResult) return false
        return width == other.width && height == other.height && alpha.contentEquals(other.alpha)
    }

    override fun hashCode(): Int = 31 * (31 * width + height) + alpha.contentHashCode()
}
```

- [ ] **Step 2: MattingEngine 接口 + Impl**

Create `app/src/main/java/com/mamba/picme/domain/matting/MattingEngine.kt`:

```kotlin
package com.mamba.picme.domain.matting

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 抠图门面接口（便于在 RecipeApplier/VM 测试中注入 fake）。P1 仅 u2netp 路径。 */
interface MattingEngine {
    suspend fun removeBackground(bitmap: Bitmap): MattingResult?
}

class MattingEngineImpl(context: Context) : MattingEngine {

    private val backend = U2NetOnnxBackend(context, AssetMattingModelResolver(context))
    private var initialized = false

    private suspend fun ensureInitialized(): Boolean {
        if (initialized) return true
        initialized = backend.initialize()
        return initialized
    }

    override suspend fun removeBackground(bitmap: Bitmap): MattingResult? = withContext(Dispatchers.Default) {
        if (!ensureInitialized()) return@withContext null
        val probs = backend.infer(bitmap) ?: return@withContext null
        val maskSize = U2NetPreprocessor.INPUT_SIZE
        // u2netp 输出 320×320；二值化后双线性上采样回原图尺寸
        val binary = MaskPostProcessor.binarize(probs, threshold = 0.5f)
        val upsampled = MaskPostProcessor.upsample(
            binary, srcW = maskSize, srcH = maskSize, dstW = bitmap.width, dstH = bitmap.height
        )
        MattingResult(alpha = upsampled, width = bitmap.width, height = bitmap.height)
    }

    fun release() = backend.release()
}
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/MattingResult.kt \
  app/src/main/java/com/mamba/picme/domain/matting/MattingEngine.kt
git commit -m "feat(matting): MattingEngine 门面（u2netp 路径 + 上采样到原图）"
```

---

## Task 8: RecipeApplier.applyCutout 阶段

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/editor/RecipeApplier.kt`
- Create: `app/src/test/java/com/mamba/picme/features/editor/RecipeApplierCutoutTest.kt`

- [ ] **Step 1: 写失败测试（Robolectric 真实 Bitmap）**

Create `app/src/test/java/com/mamba/picme/features/editor/RecipeApplierCutoutTest.kt`:

```kotlin
package com.mamba.picme.features.editor

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.matting.MattingResult
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecipeApplierCutoutTest {

    private val processor = mockk<PhotoProcessor>(relaxed = true)

    @Test
    fun `applyCutout with null cutout returns same bitmap`() = runBlocking {
        val engine = mockk<MattingEngine>(relaxed = true)
        val applier = RecipeApplier(processor, mattingEngine = engine)
        val src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val out = applier.applyCutout(src, cutout = null)
        assertTrue(src === out)
    }

    @Test
    fun `applyCutout transparent removes background where mask is zero`() = runBlocking {
        // 2x2 mask: 全 0（背景）-> 抠图后全部透明
        val alpha = FloatArray(4) { 0f }
        val engine = object : MattingEngine {
            override suspend fun removeBackground(bitmap: Bitmap): MattingResult =
                MattingResult(alpha, width = bitmap.width, height = bitmap.height)
        }
        val applier = RecipeApplier(processor, mattingEngine = engine)
        val src = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cutout = CutoutRecipe(bgMode = CutoutRecipe.BgMode.TRANSPARENT)
        val out = applier.applyCutout(src, cutout)
        assertEquals(0, (out.getPixel(0, 0) ushr 24) and 0xFF)
    }

    @Test
    fun `applyCutout color mode composites on solid color`() = runBlocking {
        // 全 0 alpha -> 结果 == 背景色（红）
        val engine = object : MattingEngine {
            override suspend fun removeBackground(bitmap: Bitmap): MattingResult =
                MattingResult(FloatArray(4) { 0f }, width = 2, height = 2)
        }
        val applier = RecipeApplier(processor, mattingEngine = engine)
        val src = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val red = 0xFFFF0000.toInt()
        val out = applier.applyCutout(src, CutoutRecipe(bgMode = CutoutRecipe.BgMode.COLOR, bgColor = red))
        assertEquals(red, out.getPixel(0, 0))
    }
}
```

Add Robolectric Android SDK config if not present. If `ApplicationProvider` import unused causes lint, remove it (kept minimal). The test does not need ApplicationProvider; drop that import.

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.RecipeApplierCutoutTest"`
Expected: FAIL（`applyCutout` 未定义 / 构造无 `mattingEngine`）。

- [ ] **Step 3: 修改 RecipeApplier**

In `RecipeApplier.kt`, add import `import com.mamba.picme.domain.matting.MattingEngine` and `import com.mamba.picme.domain.matting.CutoutComposer` / `BackgroundComposer` / `MaskPostProcessor` as needed. Change constructor:

```kotlin
class RecipeApplier(
    private val photoProcessor: PhotoProcessor,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mattingEngine: MattingEngine? = null
) {
```

Add the new stage (CPU only, on `Dispatchers.Default`; not on the EGL single-thread dispatcher):

```kotlin
    /**
     * 去背景阶段：u2netp/MODNet 出 Alpha，按 bgMode 生成透明抠图或合成纯色背景。
     * 纯 CPU 像素操作，运行在 [processingDispatcher] 之外的 Default 调度器。
     * cutout 为 null 或未注入 mattingEngine 时原样返回。
     */
    suspend fun applyCutout(bitmap: Bitmap, cutout: CutoutRecipe?): Bitmap {
        if (cutout == null || mattingEngine == null) return bitmap
        val result = mattingEngine.removeBackground(bitmap) ?: return bitmap
        var alpha = result.alpha
        if (cutout.feather > 0) {
            alpha = MaskPostProcessor.feather(alpha, result.width, result.height, cutout.feather)
        }
        return when (cutout.bgMode) {
            CutoutRecipe.BgMode.TRANSPARENT ->
                CutoutComposer.apply(bitmap, alpha, result.width, result.height)
            CutoutRecipe.BgMode.COLOR ->
                BackgroundComposer.apply(bitmap, alpha, result.width, result.height, cutout.bgColor ?: 0xFFFFFFFF.toInt())
            CutoutRecipe.BgMode.BLUR ->
                BackgroundComposer.apply(bitmap, alpha, result.width, result.height, cutout.bgColor ?: 0xFFFFFFFF.toInt())
        }
    }
```

Add imports at top:

```kotlin
import com.mamba.picme.domain.matting.BackgroundComposer
import com.mamba.picme.domain.matting.CutoutComposer
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.matting.MaskPostProcessor
```

Note: `CutoutRecipe` is same package (`features.editor`), no import needed.

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.RecipeApplierCutoutTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/RecipeApplier.kt \
  app/src/test/java/com/mamba/picme/features/editor/RecipeApplierCutoutTest.kt
git commit -m "feat(editor): RecipeApplier 新增 applyCutout 去背景阶段"
```

---

## Task 9: PhotoEditorViewModel 接线（removeBackground + 预览/保存插阶段 + PNG 导出 + 工厂）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModel.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModelFactory.kt`

- [ ] **Step 1: VM 构造增 mattingEngine**

In `PhotoEditorViewModel.kt`, add import `import com.mamba.picme.domain.matting.MattingEngine` and add constructor param (after `aiOptimizeUseCase`):

```kotlin
@OptIn(FlowPreview::class)
class PhotoEditorViewModel(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val recipeRepository: PhotoEditRecipeRepository,
    private val mediaRepository: MediaRepository,
    private val userSettingsRepository: UserSettingsRepository? = null,
    private val aiOptimizeUseCase: AiOptimizeUseCase? = null,
    private val mattingEngine: MattingEngine? = null
) : ViewModel() {
```

- [ ] **Step 2: processPreview / save 插入 applyCutout**

In `processPreview()` (around line 258-261), construct applier with engine and insert cutout between GPU effects and markup:

```kotlin
                val applier = RecipeApplier(photoProcessor, photoProcessingDispatcher, mattingEngine)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(base, recipe.crop) }
                val processed = applier.applyGpuEffects(cropped, recipe, cachedFaceData)
                val cutout = withContext(Dispatchers.Default) { applier.applyCutout(processed, recipe.cutout) }
                val marked = withContext(Dispatchers.Default) { applier.applyMarkup(cutout, recipe.markup) }
```

In `save()` (around line 282-285), same change:

```kotlin
                val applier = RecipeApplier(photoProcessor, photoProcessingDispatcher, mattingEngine)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(fullBitmap, recipe.crop) }
                val processed = applier.applyGpuEffects(cropped, recipe, cachedFaceData)
                val afterCutout = withContext(Dispatchers.Default) { applier.applyCutout(processed, recipe.cutout) }
                val finalBitmap = withContext(Dispatchers.Default) { applier.applyMarkup(afterCutout, recipe.markup) }
                val transparent = recipe.cutout?.bgMode == CutoutRecipe.BgMode.TRANSPARENT
                val outputUri = saveBitmapToMediaStore(context, finalBitmap, transparent)
```

- [ ] **Step 3: saveBitmapToMediaStore 增加 PNG 分支**

Change signature and body (around line 314-326):

```kotlin
    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap, transparent: Boolean): String? {
        val ext = if (transparent) "png" else "jpg"
        val mime = if (transparent) "image/png" else "image/jpeg"
        val name = "EDITED_${System.currentTimeMillis()}.$ext"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoLang")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return uri?.also {
            context.contentResolver.openOutputStream(it)?.use { out ->
                if (transparent) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            }
        }?.toString()
    }
```

- [ ] **Step 4: 新增 removeBackground() 一键入口**

Add near `undo()`/`redo()` (e.g., after `redo()` around line 250):

```kotlin
    /** 一键去背景：写入 cutout 配方（默认透明抠图），可撤销/重做。 */
    fun removeBackground() {
        val current = _state.value as? State.Ready ?: return
        val recipe = current.recipe.copy(
            cutout = CutoutRecipe(
                maskSource = com.mamba.picme.domain.matting.MaskSource.U2NETP,
                bgMode = CutoutRecipe.BgMode.TRANSPARENT
            )
        )
        pushRecipe(recipe)
    }
```

Confirm `pushRecipe` exists (it backs undo/redo; if the actual method name differs, use the same helper `undo`/`redo` use — check: the existing code uses `history.push(recipe)` + sets `_recipeChanges.value`). If there is no `pushRecipe`, inline:

```kotlin
    fun removeBackground() {
        val current = _state.value as? State.Ready ?: return
        val recipe = current.recipe.copy(
            cutout = CutoutRecipe(
                maskSource = com.mamba.picme.domain.matting.MaskSource.U2NETP,
                bgMode = CutoutRecipe.BgMode.TRANSPARENT
            )
        )
        history.push(recipe)
        _state.value = current.copy(recipe = recipe)
        _recipeChanges.value = recipe
    }
```

Use whichever matches the existing `updateRecipe`/push pattern in the file (read `updateRecipe` to confirm; both `undo` and `updateRecipe` already do `history.push` + set state + set `_recipeChanges`). Mirror exactly.

Add import at top: `import com.mamba.picme.features.editor.CutoutRecipe` is same package (no import). `MaskSource` is referenced fully-qualified here to avoid an import; alternatively add `import com.mamba.picme.domain.matting.MaskSource`.

- [ ] **Step 5: 工厂构造 MattingEngineImpl 注入**

In `PhotoEditorViewModelFactory.kt`, add imports:

```kotlin
import com.mamba.picme.domain.matting.MattingEngineImpl
```

In `create()`, construct and pass:

```kotlin
        if (modelClass.isAssignableFrom(PhotoEditorViewModel::class.java)) {
            return PhotoEditorViewModel(
                photoProcessor = photoProcessorFactory(appContext),
                faceDetector = faceDetector,
                recipeRepository = recipeRepository,
                mediaRepository = mediaRepository,
                userSettingsRepository = userSettingsRepository,
                aiOptimizeUseCase = aiOptimizeUseCase,
                mattingEngine = MattingEngineImpl(appContext)
            ) as T
        }
```

- [ ] **Step 6: 编译 + 既有编辑器单测确认未回归**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.*"`
Expected: BUILD SUCCESSFUL，editor 包测试全部 PASS（RecipeApplierTest 既有的 `RecipeApplier(processor)` 因 `mattingEngine` 默认 null 仍编译通过）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModel.kt \
  app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModelFactory.kt
git commit -m "feat(editor): VM 接入去背景（removeBackground + PNG 导出 + applyCutout）"
```

---

## Task 10: 顶部栏「去背景」按钮 + 棋盘格透明预览

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/editor/components/EditorTopBar.kt`
- Create: `app/src/main/java/com/mamba/picme/features/editor/components/CheckerboardBackground.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorScreen.kt`

- [ ] **Step 1: EditorTopBar 加按钮**

In `EditorTopBar.kt` add import:

```kotlin
import androidx.compose.material.icons.outlined.LayersClear
```

Add param `onRemoveBackground: () -> Unit,` to the `EditorTopBar(...)` signature (before `onAiOptimize`). Insert a new `IconButton` immediately after the title `Text(...){}` block (before the AiOptimize `IconButton`):

```kotlin
            IconButton(onClick = onRemoveBackground, enabled = !isSaving) {
                Icon(
                    imageVector = Icons.Outlined.LayersClear,
                    contentDescription = stringResource(R.string.remove_background),
                    tint = colors.actionIconContentColor
                )
            }
```

- [ ] **Step 2: 棋盘格 Composable**

Create `app/src/main/java/com/mamba/picme/features/editor/components/CheckerboardBackground.kt`:

```kotlin
package com.mamba.picme.features.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 透明抠图预览用的棋盘格背景。 */
@Composable
fun CheckerboardBackground(modifier: Modifier = Modifier) {
    val light = Color(0xFFE6E6E6)
    val dark = Color(0xFFBDBDBD)
    val cell = 16.dp
    Canvas(modifier = modifier) {
        val n = cell.toPx()
        val cols = (size.width / n).toInt() + 1
        val rows = (size.height / n).toInt() + 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) light else dark,
                    topLeft = Offset(c * n, r * n),
                    size = androidx.compose.ui.geometry.Size(n, n)
                )
            }
        }
    }
}
```

- [ ] **Step 3: PhotoEditorScreen 接线（顶部栏回调 + 棋盘格）**

In `PhotoEditorScreen.kt` add import:

```kotlin
import com.mamba.picme.features.editor.components.CheckerboardBackground
```

In the `EditorTopBar(...)` call, add `onRemoveBackground = viewModel::removeBackground,` (before `onAiOptimize`).

In the preview `Box` (the `padding` Box around line 99-105), make the background conditional on transparent cutout:

```kotlin
        ) {
            val ready = state as? PhotoEditorViewModel.State.Ready
            val transparent = ready?.recipe?.cutout?.bgMode == CutoutRecipe.BgMode.TRANSPARENT
            if (transparent) {
                CheckerboardBackground(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }
```

And change the outer Box `.background(Color.Black)` to be applied only when not transparent. Simplest: wrap—replace `.background(Color.Black)` with `.background(if (transparent) Color.Transparent else Color.Black)`. (The checkerboard behind fills the area when transparent.) Add `CutoutRecipe` usage; it's same package, no import.

- [ ] **Step 4: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.（`R.string.remove_background` 在 Task 11 添加；此处会因缺 string 编译失败 → 若先做 Task 11 的 strings 再回来，或临时先加 string。执行顺序：先 Task 11 Step 1 加 strings，再做本 Task。）

> 依赖顺序提示：Task 10 引用 `R.string.remove_background`，需 Task 11 的 strings 先就位。**建议执行时先完成 Task 11 Step 1（加三语 string），再回到本 Task。**

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/components/EditorTopBar.kt \
  app/src/main/java/com/mamba/picme/features/editor/components/CheckerboardBackground.kt \
  app/src/main/java/com/mamba/picme/features/editor/PhotoEditorScreen.kt
git commit -m "feat(editor): 顶部栏去背景按钮 + 透明预览棋盘格"
```

---

## Task 11: 模型资产 + i18n 三语文案

**Files:**
- Create: `app/src/main/assets/matting/u2netp.onnx`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: 新增三语 string**

Add to `values/strings.xml` (EN):

```xml
    <string name="remove_background">Remove background</string>
    <string name="remove_background_failed">Failed to remove background</string>
```

Add to `values-zh-rCN/strings.xml`:

```xml
    <string name="remove_background">去背景</string>
    <string name="remove_background_failed">去背景失败</string>
```

Add to `values-zh-rTW/strings.xml`:

```xml
    <string name="remove_background">去背景</string>
    <string name="remove_background_failed">去背景失敗</string>
```

(Use each file's existing indentation/place near other editor strings.)

- [ ] **Step 2: 放置 u2netp 模型**

Obtain a valid **fp32** `u2netp.onnx`（单图像输入 / 单掩码输出，~4.7 MB）。可从 U-2-Net 官方权重导出，或任一公开 fp32 u2netp ONNX。放置到：

```
app/src/main/assets/matting/u2netp.onnx
```

> 校验：模型 input 为 `[1,3,320,320]` float、output 为 `[1,1,320,320]` float。后端按 `session.inputNames/outputNames` 动态读取名字，不依赖具体导出命名。

- [ ] **Step 3: 编译 + 资源校验**

Run: `./gradlew :app:processDebugResources`
Expected: BUILD SUCCESSFUL（三语 string 齐全、无 lint hardcode 报错）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/assets/matting/u2netp.onnx \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(matting): 打包 u2netp fp32 模型 + 去背景三语文案"
```

---

## Task 12: 设备端到端验证 + 最终回归

**Files:** 无新增（验证性任务）

- [ ] **Step 1: 全量 JVM 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（全部既有 + 新增 matting/editor 测试通过）。

- [ ] **Step 2: 编译 debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，APK 含 `assets/matting/u2netp.onnx`。

- [ ] **Step 3: 设备端到端（手动 / adb-bot）**

Install 到设备，进入相册 → 编辑一张含明显前景物体的图：

1. 点顶部栏「去背景」图标 → 预览应出现棋盘格 + 前景抠图（背景变透明）。
2. 撤销 → 恢复原图（非破坏性）。
3. 再次去背景 → 点完成保存 → 相册新副本为 **PNG**，用图库/详情确认背景透明。
4. 编辑一张人像图 → 走 u2netp（P2 前），边缘可能锯齿（预期，记录对比样本供 P2 MODNet 对照）。
5. 日志：`adb logcat -s "PoLang:Matting:*"` 应见 `U2NetOnnxBackend initialized` 与推理完成，无 NaN/异常。

- [ ] **Step 4: 失败路径验证**

- 删除/重命名 assets 中模型重新装包 → 点去背景应静默回退原图（`removeBackground` 返回 null → applyCutout 返回原图），无崩溃。（demo 阶段打包后此路径不触发，仅作健壮性确认。）

- [ ] **Step 5: 最终提交（如有 verify 修复）**

```bash
git add -A
git commit -m "test(matting): P1 设备端到端验证通过"
```

---

## Self-Review

**Spec coverage（spec §5 P1 行 + §1-§4）：**
- `U2NetOnnxBackend` → Task 5 ✓
- `MattingModelResolver`（assets 源）→ Task 6 ✓
- `MaskPostProcessor` → Task 2 ✓
- `CutoutComposer`/`BackgroundComposer` → Task 3/4 ✓
- PNG 导出 → Task 9 ✓
- 顶部栏按钮 → Task 10 ✓
- `EditRecipe.cutout` 字段 + 序列化 → Task 1 ✓
- `RecipeApplier.applyCutout` → Task 8 ✓
- 棋盘格预览 → Task 10 ✓
- 模型打包 + i18n → Task 11 ✓
- 人脸路由：**P1 不做**（P2），spec §5 明确 ✓
- MODNet：**P1 不做**（P2）✓

**Placeholder scan：** 无 TBD/TODO；每步含完整代码或精确命令。

**Type/签名一致性：** `MattingEngine.removeBackground(Bitmap): MattingResult?`（Task 7）↔ RecipeApplier/测试（Task 8）↔ VM（Task 9）一致。`MattingResult(alpha, width, height)` 三处一致。`CutoutRecipe.BgMode.TRANSPARENT/COLOR/BLUR` + `maskSource` 在 Task 1/8/9 一致。`MaskPostProcessor` 方法签名 Task 2/7/8 一致。`saveBitmapToMediaStore(context, bitmap, transparent)` Task 9 定义与调用一致。

**已知执行注意：**
- Task 10 依赖 Task 11 的 `R.string.remove_background`——执行顺序先 Task 11 Step 1。
- Task 9 Step 4 的 `removeBackground` 须复用文件中既有的 push 模式（`history.push` + 状态 + `_recipeChanges`）；执行时读 `updateRecipe` 确认实际写法后镜像。
- Robolectric 4.14.1 默认 SDK 应可跑 `Bitmap.createBitmap`；若报 SDK 不支持，给 `RecipeApplierCutoutTest` 加 `@Config(sdk = [33])`。
