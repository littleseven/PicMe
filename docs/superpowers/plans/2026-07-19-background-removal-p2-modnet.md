# P2 人像精修（MODNet + 人脸路由）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 P1（u2netp 一键去背景）基础上，接入 MODNet 人像 Alpha Matting：检测到人脸的图走 MODNet（连续 Alpha，发丝软边），无人脸的图继续走 u2netp。路由对用户透明，仍是同一个「去背景」按钮。

**Architecture:** 新增 `ModNetOnnxBackend`（镜像 P1 的 `U2NetOnnxBackend`，跑在同一套 ONNX Runtime 上）+ `ModNetPreprocessor`（256×256，`(x/255-0.5)/0.5` 归一化）。`MattingRouter` 把「是否人像」映射为 `MaskSource`（纯函数，可单测）。`MattingEngine.removeBackground(bitmap, maskSource)` 按 maskSource 分派到对应 backend，并对 u2netp 二值化、对 MODNet 直传连续 Alpha。`PhotoEditorViewModel.removeBackground()` 用 `MattingRouter.choose(cachedFaceData != null)` 写入 `cutout.maskSource`；`applyCutout` 读 `cutout.maskSource` 透传给引擎——无需新增参数。

**Tech Stack:** Kotlin · ONNX Runtime 1.24.3（复用）· Coroutines · JUnit4 + mockk + Robolectric 4.14.1。

**Spec:** `docs/superpowers/specs/2026-07-18-background-removal-matting-design.md`（P2 = §5 P2 行 + §1 MattingRouter/ModNetOnnxBackend + §3 模型表 MODNet 行）。

**Scope note:** 仅 P2（MODNet + 路由）。P3（证件照专区）不在本期。

---

## File Structure

**新建（`app/src/main/java/com/mamba/picme/domain/matting/`）：**
- `ModNetPreprocessor.kt` — 256 NCHW 归一化（纯数组核心，可单测）
- `ModNetOnnxBackend.kt` — MODNet ONNX 推理（镜像 `U2NetOnnxBackend`，秩无关输出）
- `MattingRouter.kt` — `choose(hasFace): MaskSource` 纯函数

**新建资产：** `app/src/main/assets/matting/modnet.onnx`（移动端量化版，外部获取，见 Task 6）

**新建测试（`app/src/test/java/com/mamba/picme/domain/matting/`）：**
- `ModNetPreprocessorTest.kt`
- `MattingRouterTest.kt`

**修改：**
- `domain/matting/MattingEngine.kt` — 接口 `removeBackground(bitmap)` → `removeBackground(bitmap, maskSource)`；`MattingEngineImpl` 持有 modnet backend、按 maskSource 分派、逐 backend 懒初始化、按 backend 后处理（u2net 二值化 / modnet 直传）
- `features/editor/RecipeApplier.kt` — `applyCutout` 内 `removeBackground` 调用增传 `cutout.maskSource`（签名不变）
- `features/editor/PhotoEditorViewModel.kt` — `removeBackground()` 用 `MattingRouter.choose(cachedFaceData != null)` 写 maskSource
- `app/src/test/java/com/mamba/picme/features/editor/RecipeApplierCutoutTest.kt` — fake engine 的 `removeBackground` override 适配新签名

---

## Task 1: ModNetPreprocessor（可单测）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/ModNetPreprocessor.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/matting/ModNetPreprocessorTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/matting/ModNetPreprocessorTest.kt`:

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class ModNetPreprocessorTest {

    @Test
    fun `toNchw maps white to plus one and black to minus one`() {
        val white = intArrayOf(0xFFFFFFFF.toInt()) // 1x1
        val out = ModNetPreprocessor.toNchw(white, size = 1)
        assertEquals(3, out.size)
        // (1.0 - 0.5) / 0.5 = 1.0
        assertEquals(1.0f, out[0], 0.001f)
        assertEquals(1.0f, out[1], 0.001f)
        assertEquals(1.0f, out[2], 0.001f)

        val black = intArrayOf(0xFF000000.toInt())
        val ob = ModNetPreprocessor.toNchw(black, size = 1)
        // (0 - 0.5) / 0.5 = -1.0
        assertEquals(-1.0f, ob[0], 0.001f)
    }

    @Test
    fun `toNchw 256 has expected length`() {
        val pixels = IntArray(256 * 256) { 0xFF000000.toInt() }
        val out = ModNetPreprocessor.toNchw(pixels, size = 256)
        assertEquals(3 * 256 * 256, out.size)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.ModNetPreprocessorTest"`
Expected: FAIL（unresolved reference）。

- [ ] **Step 3: 实现**

Create `app/src/main/java/com/mamba/picme/domain/matting/ModNetPreprocessor.kt`:

```kotlin
package com.mamba.picme.domain.matting

import android.graphics.Bitmap

/** MODNet 输入预处理：256×256 RGB → (x/255-0.5)/0.5 归一化 NCHW。核心 [toNchw] 基于数组，可 JVM 单测。 */
object ModNetPreprocessor {
    const val INPUT_SIZE = 256
    private const val MEAN = 0.5f
    private const val STD = 0.5f

    /** pixels：ARGB IntArray，长度 = size*size。返回 NCHW [3*size*size]。 */
    fun toNchw(pixels: IntArray, size: Int = INPUT_SIZE): FloatArray {
        val plane = size * size
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = (((p shr 16) and 0xFF) / 255f - MEAN) / STD
            val g = (((p shr 8) and 0xFF) / 255f - MEAN) / STD
            val b = ((p and 0xFF) / 255f - MEAN) / STD
            out[i] = r
            out[plane + i] = g
            out[2 * plane + i] = b
        }
        return out
    }

    /** 把 Bitmap 缩放到 size×size，返回 NCHW FloatArray。 */
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

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.ModNetPreprocessorTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/ModNetPreprocessor.kt \
  app/src/test/java/com/mamba/picme/domain/matting/ModNetPreprocessorTest.kt
git commit -m "feat(matting): ModNet 预处理（256 (x/255-0.5)/0.5 归一化，可单测）"
```

---

## Task 2: ModNetOnnxBackend（镜像 U2Net，设备验证）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/ModNetOnnxBackend.kt`

- [ ] **Step 1: 实现（镜像 U2NetOnnxBackend）**

Create `app/src/main/java/com/mamba/picme/domain/matting/ModNetOnnxBackend.kt`:

```kotlin
package com.mamba.picme.domain.matting

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/** MODNet ONNX Runtime 推理后端。返回连续 Alpha FloatArray（长度 INPUT_SIZE^2，0..1），失败返回 null。 */
class ModNetOnnxBackend(
    context: Context,
    private val resolver: MattingModelResolver
) {
    companion object {
        private const val TAG = "PoLang:Matting"
        private const val MODEL_ID = "modnet-onnx"
    }

    private val appContext = context.applicationContext
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    val isInitialized: Boolean
        get() = session != null

    suspend fun initialize(): Boolean {
        if (session != null) return true
        val modelFile = resolver.resolve(MODEL_ID) ?: run {
            Log.w(TAG, "modnet model not found via resolver")
            return false
        }
        return try {
            val options = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
            }
            session = env.createSession(modelFile.absolutePath, options)
            Log.i(TAG, "ModNetOnnxBackend initialized (${modelFile.name})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init modnet session", e)
            release()
            false
        }
    }

    /** 推理；返回连续 Alpha（0..1），长度 INPUT_SIZE*INPUT_SIZE。秩无关读取。 */
    fun infer(bitmap: Bitmap): FloatArray? {
        val s = session ?: run {
            Log.w(TAG, "modnet session not initialized")
            return null
        }
        return try {
            val size = ModNetPreprocessor.INPUT_SIZE
            val nchw = ModNetPreprocessor.bitmapToNchw(bitmap, size)
            val shape = longArrayOf(1L, 3L, size.toLong(), size.toLong())
            val inputName = s.inputNames.first()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(nchw), shape)
            try {
                s.run(mapOf(inputName to tensor)).use { results ->
                    val raw = flattenFloats(results.get(0).value)
                    // MODNet 输出已是 sigmoid Alpha；对越界值幂等保护
                    FloatArray(raw.size) { i ->
                        val v = raw[i]
                        if (v in 0f..1f) v else (1.0f / (1.0f + Math.exp((-v).toDouble()))).toFloat()
                    }
                }
            } finally {
                tensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "modnet inference failed", e)
            null
        }
    }

    private fun flattenFloats(value: Any): FloatArray {
        val out = ArrayList<Float>()
        walkFloats(value, out)
        return FloatArray(out.size) { i -> out[i] }
    }

    private fun walkFloats(v: Any?, out: ArrayList<Float>) {
        when (v) {
            is FloatArray -> for (f in v) out.add(f)
            is Array<*> -> for (e in v) walkFloats(e, out)
            is Number -> out.add(v.toFloat())
        }
    }

    fun release() {
        session?.close()
        session = null
        Log.i(TAG, "ModNetOnnxBackend session released (OrtEnvironment kept alive)")
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（OrtSession API 与 P1 一致，确认通过）。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/ModNetOnnxBackend.kt
git commit -m "feat(matting): ModNet ONNX Runtime 推理后端（连续 Alpha）"
```

---

## Task 3: MattingRouter（纯函数，可单测）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/MattingRouter.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/matting/MattingRouterTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/matting/MattingRouterTest.kt`:

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class MattingRouterTest {

    @Test
    fun `portrait with face routes to MODNet`() {
        assertEquals(MaskSource.MODNET, MattingRouter.choose(hasFace = true))
    }

    @Test
    fun `image without face routes to U2Net`() {
        assertEquals(MaskSource.U2NETP, MattingRouter.choose(hasFace = false))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.MattingRouterTest"`
Expected: FAIL。

- [ ] **Step 3: 实现**

Create `app/src/main/java/com/mamba/picme/domain/matting/MattingRouter.kt`:

```kotlin
package com.mamba.picme.domain.matting

/** 抠图路由：人像（检测到人脸）走 MODNet 软边精修；其余走 u2netp 通用分割。 */
object MattingRouter {
    fun choose(hasFace: Boolean): MaskSource =
        if (hasFace) MaskSource.MODNET else MaskSource.U2NETP
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.MattingRouterTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/MattingRouter.kt \
  app/src/test/java/com/mamba/picme/domain/matting/MattingRouterTest.kt
git commit -m "feat(matting): MattingRouter 人像路由（人脸→MODNet / 无人脸→u2netp）"
```

---

## Task 4: MattingEngine 接 maskSource 分派（+ 更新测试 fakes）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/matting/MattingEngine.kt`
- Modify: `app/src/test/java/com/mamba/picme/features/editor/RecipeApplierCutoutTest.kt`

- [ ] **Step 1: 改 MattingEngine 接口与实现**

Replace the interface method signature in `MattingEngine.kt`:

```kotlin
interface MattingEngine {
    suspend fun removeBackground(bitmap: Bitmap, maskSource: MaskSource): MattingResult?
}
```

Update `MattingEngineImpl` to hold both backends, lazy-init per backend, dispatch by `maskSource`, and post-process per backend (u2net 二值化 / modnet 直传连续 Alpha):

```kotlin
class MattingEngineImpl(context: Context) : MattingEngine {

    private val u2netBackend = U2NetOnnxBackend(context, AssetMattingModelResolver(context))
    private val modnetBackend = ModNetOnnxBackend(context, AssetMattingModelResolver(context))
    private var u2netReady = false
    private var modnetReady = false

    private suspend fun ensureBackend(source: MaskSource): Boolean = when (source) {
        MaskSource.U2NETP -> {
            if (!u2netReady) u2netReady = u2netBackend.initialize()
            u2netReady
        }
        MaskSource.MODNET -> {
            if (!modnetReady) modnetReady = modnetBackend.initialize()
            modnetReady
        }
    }

    override suspend fun removeBackground(bitmap: Bitmap, maskSource: MaskSource): MattingResult? =
        withContext(Dispatchers.Default) {
            if (!ensureBackend(maskSource)) return@withContext null
            val raw = when (maskSource) {
                MaskSource.U2NETP -> u2netBackend.infer(bitmap)
                MaskSource.MODNET -> modnetBackend.infer(bitmap)
            } ?: return@withContext null
            val maskSize = if (maskSource == MaskSource.U2NETP) {
                U2NetPreprocessor.INPUT_SIZE
            } else {
                ModNetPreprocessor.INPUT_SIZE
            }
            // u2netp：二值化；MODNet：连续 Alpha 直传
            val alpha = if (maskSource == MaskSource.U2NETP) {
                MaskPostProcessor.binarize(raw, threshold = 0.5f)
            } else {
                raw
            }
            val upsampled = MaskPostProcessor.upsample(
                alpha, srcW = maskSize, srcH = maskSize, dstW = bitmap.width, dstH = bitmap.height
            )
            MattingResult(alpha = upsampled, width = bitmap.width, height = bitmap.height)
        }

    fun release() {
        u2netBackend.release()
        modnetBackend.release()
    }
}
```

Keep imports: `import com.mamba.picme.domain.matting.MaskSource` is same package (no import needed within the file). Ensure `MaskPostProcessor`, `U2NetPreprocessor`, `ModNetPreprocessor`, `U2NetOnnxBackend`, `ModNetOnnxBackend`, `AssetMattingModelResolver` are all same package — no imports needed.

- [ ] **Step 2: 更新 RecipeApplierCutoutTest 的 fake engines**

The fake `MattingEngine` overrides in `RecipeApplierCutoutTest.kt` must match the new signature. Change each `override suspend fun removeBackground(bitmap: Bitmap): MattingResult` to:

```kotlin
            override suspend fun removeBackground(bitmap: Bitmap, maskSource: MaskSource): MattingResult =
                MattingResult(FloatArray(bitmap.width * bitmap.height) { 0f }, bitmap.width, bitmap.height)
```

Add import `import com.mamba.picme.domain.matting.MaskSource` to the test file. There are two fake `object : MattingEngine` instances (transparent test, color test) plus one `mockk<MattingEngine>(relaxed = true)` (the relaxed mock auto-stubs the new signature).

- [ ] **Step 3: RecipeApplier 传 maskSource**

In `RecipeApplier.applyCutout`, change the engine call from `mattingEngine.removeBackground(bitmap)` to:

```kotlin
        val result = mattingEngine.removeBackground(bitmap, cutout.maskSource) ?: return bitmap
```

- [ ] **Step 4: 编译 + RecipeApplier 测试回归**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.RecipeApplierCutoutTest"`
Expected: BUILD SUCCESSFUL，4 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/MattingEngine.kt \
  app/src/main/java/com/mamba/picme/features/editor/RecipeApplier.kt \
  app/src/test/java/com/mamba/picme/features/editor/RecipeApplierCutoutTest.kt
git commit -m "feat(matting): MattingEngine 按 maskSource 分派 u2netp/MODNet + 逐 backend 后处理"
```

---

## Task 5: VM.removeBackground 用 MattingRouter 写 maskSource

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModel.kt`

- [ ] **Step 1: removeBackground 按人脸写 maskSource**

In `PhotoEditorViewModel.kt`, the current `removeBackground()` hardcodes `MaskSource.U2NETP`. Change it to route via `MattingRouter` using `cachedFaceData`:

```kotlin
    /** 一键去背景：按是否人像路由写入 cutout 配方（默认透明抠图），可撤销/重做，复用 [updateRecipe] 触发预览。 */
    fun removeBackground() {
        val current = _state.value as? State.Ready ?: return
        val source = MattingRouter.choose(cachedFaceData != null)
        updateRecipe(
            current.recipe.copy(
                cutout = CutoutRecipe(
                    maskSource = source,
                    bgMode = CutoutRecipe.BgMode.TRANSPARENT
                )
            )
        )
    }
```

Add imports at top:

```kotlin
import com.mamba.picme.domain.matting.MattingRouter
```

(`MaskSource` 不再被本文件直接引用——由 `MattingRouter` 返回；移除 P1 为 `MaskSource.U2NETP` 加的 import `com.mamba.picme.domain.matting.MaskSource` 以避免未使用 import 警告，若 ktlint 报未使用。)

- [ ] **Step 2: 编译 + 全量单测回归**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部测试 PASS。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModel.kt
git commit -m "feat(editor): removeBackground 按人脸路由 maskSource（人像→MODNet）"
```

---

## Task 6: 打包 modnet.onnx（移动端量化版）

**Files:**
- Create: `app/src/main/assets/matting/modnet.onnx`
- Modify: `app/src/main/java/com/mamba/picme/domain/matting/MattingModelResolver.kt`（`MODEL_ASSET_PATHS` 增 modnet 条目）

- [ ] **Step 1: MattingModelResolver 注册 modnet 路径**

In `MattingModelResolver.kt`, `AssetMattingModelResolver.companion` `MODEL_ASSET_PATHS`, add:

```kotlin
        private val MODEL_ASSET_PATHS = mapOf(
            "u2netp-onnx" to ("matting/u2netp.onnx" to "u2netp.onnx"),
            "modnet-onnx" to ("matting/modnet.onnx" to "modnet.onnx")
        )
```

- [ ] **Step 2: 放置 modnet.onnx**

获取移动端 **MODNet ONNX**（256×256 输入，单 Alpha 输出，量化版 ~7-8MB；fp32 256 ~25MB 亦可接受）。置于：

```
app/src/main/assets/matting/modnet.onnx
```

校验：input `[1,3,256,256]` float、output 为 `[1,1,256,256]` float（Alpha）。后端按 `session.inputNames/outputNames` 动态读取、秩无关 flatten，多数导出可用。

> 来源说明：MODNet 官方（ZHKKKe/MODNet）仅提供 PyTorch 权重，需 ONNX 导出/转换，或采用社区预导出的移动端 ONNX。若一时无可靠来源，向用户确认其 ModelScope 空间是否有 modnet.onnx，或改用 fp32 256 导出。`get(0)` 取首个输出；若该导出有多个输出且首个非最终 Alpha，需据 `onnx.load` 核对后调整取的索引。

- [ ] **Step 3: 编译 + 资源校验**

Run: `./gradlew :app:processDebugResources :app:assembleDebug`
Expected: BUILD SUCCESSFUL（APK 含 `assets/matting/modnet.onnx`）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/assets/matting/modnet.onnx \
  app/src/main/java/com/mamba/picme/domain/matting/MattingModelResolver.kt
git commit -m "feat(matting): 打包 modnet 模型 + resolver 注册 modnet-onnx 路径"
```

---

## Task 7: 设备端人像路由验证

**Files:** 无新增（验证性任务）

- [ ] **Step 1: 全量单测 + APK**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 安装 + 人像路由验证**

`adb install -r`，进入相册编辑一张**人像**图：

1. 点「去背景」→ 预览应棋盘格 + 人像抠出，**发丝边缘软**（无锯齿）。
2. 日志 `adb logcat -s "PoLang:Matting:*"` 应见 `ModNetOnnxBackend initialized (modnet.onnx)`（人像触发了 MODNet）。
3. 保存 → 透明 PNG；Pillow 核对边缘半透明像素占比应明显高于 P1 的 u2netp（MODNet 连续 Alpha）。

- [ ] **Step 3: 物体路由回归验证**

编辑一张**无人脸**物体图点「去背景」：

1. 日志应见 `U2NetOnnxBackend initialized`（走 u2netp，不加载 modnet）。
2. 抠图正常，与 P1 表现一致。

- [ ] **Step 4: 失败路径**

临时把 `modnet.onnx` 移走重新装包，编辑人像点去背景 → MODNet 初始化失败返回 null → applyCutout 回退原图（不崩）。日志见 `modnet model not found`。

- [ ] **Step 5: 提交（如有 verify 修复）**

```bash
git add -A
git commit -m "test(matting): P2 人像路由设备端到端验证通过"
```

---

## Self-Review

**Spec coverage（spec §5 P2 + §1 MattingRouter/ModNetOnnxBackend + §3 MODNet 行）：**
- `ModNetOnnxBackend` → Task 2 ✓
- `MattingRouter` 人脸路由 → Task 3 ✓（+ Task 5 VM 接入）
- `MattingEngine` 路由分派 → Task 4 ✓
- 人像→MODNet 软边、物体→u2netp → Task 7 ✓
- MODNet 模型打包 → Task 6 ✓
- 证件照专区：**P2 不做**（P3）✓

**Placeholder scan：** Task 6 Step 2 的模型获取有明确 I/O 契约与来源说明（MODNet 官方为 PyTorch，需导出/社区源），无 TBD；其余每步含完整代码或命令。

**类型/签名一致性：** `MattingEngine.removeBackground(bitmap, maskSource)` 在 Task 4 定义，RecipeApplier（Task 4 Step 3）、RecipeApplierCutoutTest fakes（Task 4 Step 2）、MattingEngineImpl（Task 4 Step 1）一致。`MattingRouter.choose(hasFace): MaskSource` Task 3 定义、Task 5 调用一致。`CutoutRecipe.maskSource` 字段（P1 已定义）驱动路由，无需新增字段。

**已知执行注意：**
- Task 4 接口签名变更会破坏 P1 的 RecipeApplierCutoutTest fakes——Step 2 已包含修复。
- Task 6 MODNet 模型来源是唯一外部依赖；若获取不到，停下来与用户确认 ModelScope 或改用 fp32 导出。
- MODNet 与 U2Net 两个 backend 结构高度相似，本期各自独立实现；后续可抽 `OnnxMattingBackend` 公共基类（YAGNI，暂不做）。
