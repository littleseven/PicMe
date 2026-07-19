# P3 证件照专区 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增「证件照专区」：从照片详情页进入，对人选照片用 MODNet 抠图，选背景色（蓝/红/白）+ 标准尺寸（1寸/2寸/小1寸/小2寸），合成纯色背景并裁剪到目标尺寸，导出 JPEG。

**Architecture:** 独立 `IDPhotoScreen` + `IDPhotoViewModel`，复用 P2 的 `MattingEngine`（强制 `MaskSource.MODNET`）与 P1 的 `BackgroundComposer`。新增 `IDPhotoSpecs`（尺寸/颜色预设）+ `IDPhotoComposer`（cover-crop 矩形纯函数 + Bitmap 合成包装）。新增 `Screen.IDPhoto` 路由（镜像 `Screen.PhotoEditor`）。入口：照片详情 `MediaPager` 在「编辑」旁加「证件照」按钮 → `Screen.IDPhoto.createRoute(sourceUri)`。

**Tech Stack:** Kotlin · Coroutines · Jetpack Compose · JUnit4 + mockk + Robolectric 4.14.1。

**Spec:** `docs/superpowers/specs/2026-07-18-background-removal-matting-design.md`（P3 = §5 P3 行 + §4 证件照入口行）。

**Scope:** 单张证件照（批量留后续）。

---

## File Structure

**新建（`app/src/main/java/com/mamba/picme/`）：**
- `domain/matting/IDPhotoSpecs.kt` — 尺寸/颜色预设（data）
- `domain/matting/IDPhotoComposer.kt` — `coverCropRect` 纯函数（可单测）+ `compose` Bitmap 包装
- `features/idphoto/IDPhotoViewModel.kt` — 加载/MODNet 抠图/合成/保存
- `features/idphoto/IDPhotoViewModelFactory.kt` — VM 工厂
- `features/idphoto/IDPhotoScreen.kt` — UI（预览 + 颜色/尺寸选择 + 保存）
- `features/idphoto/components/ColorSwatchRow.kt` — 颜色选择行
- `features/idphoto/components/SizeChipRow.kt` — 尺寸选择行

**新建测试：**
- `app/src/test/java/com/mamba/picme/domain/matting/IDPhotoSpecsTest.kt`
- `app/src/test/java/com/mamba/picme/domain/matting/IDPhotoComposerTest.kt`

**修改：**
- `navigation/Screen.kt:53` — 新增 `Screen.IDPhoto`
- `MainActivity.kt:275` — 新增 `composable(Screen.IDPhoto.route)` block
- `di/AppContainer.kt` — 新增 `createIDPhotoViewModelFactory()`
- `features/gallery/components/MediaPager.kt` — 新增「证件照」按钮 + `onIdPhoto` 回调
- `features/gallery/GalleryScreen.kt:778` — 透传 `onIdPhoto` 导航
- `res/values/strings.xml` + `values-zh-rCN` + `values-zh-rTW` — 证件照文案 + 尺寸/颜色名

---

## Task 1: IDPhotoSpecs（尺寸/颜色预设）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/IDPhotoSpecs.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/matting/IDPhotoSpecsTest.kt`

- [ ] **Step 1: 写预设 + 失败测试**

Create `app/src/main/java/com/mamba/picme/domain/matting/IDPhotoSpecs.kt`:

```kotlin
package com.mamba.picme.domain.matting

import com.mamba.picme.R

/** 证件照尺寸/颜色预设（@300dpi 国标）。 */
object IDPhotoSpecs {

    data class Size(val nameRes: Int, val widthPx: Int, val heightPx: Int, val labelCn: String)

    data class Color(val nameRes: Int, val argb: Int, val labelCn: String)

    /** 1寸 25×35mm、2寸 35×49mm、小1寸 22×32mm、小2寸 30×40mm（@300dpi）。 */
    val SIZES: List<Size> = listOf(
        Size(R.string.id_photo_size_1in, 295, 413, "1寸"),
        Size(R.string.id_photo_size_2in, 413, 579, "2寸"),
        Size(R.string.id_photo_size_small_1in, 260, 378, "小1寸"),
        Size(R.string.id_photo_size_small_2in, 354, 472, "小2寸")
    )

    /** 标准蓝 / 标准红 / 白。 */
    val COLORS: List<Color> = listOf(
        Color(R.string.id_photo_color_blue, 0xFF438EDB.toInt(), "标准蓝"),
        Color(R.string.id_photo_color_red, 0xFFD9001B.toInt(), "标准红"),
        Color(R.string.id_photo_color_white, 0xFFFFFFFF.toInt(), "白")
    )
}
```

Create `app/src/test/java/com/mamba/picme/domain/matting/IDPhotoSpecsTest.kt`:

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IDPhotoSpecsTest {

    @Test
    fun `sizes are portrait orientation and non-empty`() {
        assertTrue(IDPhotoSpecs.SIZES.isNotEmpty())
        IDPhotoSpecs.SIZES.forEach {
            assertTrue("${it.labelCn} w>0", it.widthPx > 0)
            assertTrue("${it.labelCn} h>0", it.heightPx > 0)
            assertTrue("${it.labelCn} should be portrait", it.heightPx > it.widthPx)
        }
    }

    @Test
    fun `colors include blue red white`() {
        assertEquals(3, IDPhotoSpecs.COLORS.size)
        assertTrue(IDPhotoSpecs.COLORS.any { it.labelCn == "标准蓝" })
        assertTrue(IDPhotoSpecs.COLORS.any { it.labelCn == "标准红" })
        assertTrue(IDPhotoSpecs.COLORS.any { it.labelCn == "白" })
    }

    @Test
    fun `1in size matches national standard 295x413`() {
        val one = IDPhotoSpecs.SIZES.first { it.labelCn == "1寸" }
        assertEquals(295, one.widthPx)
        assertEquals(413, one.heightPx)
    }
}
```

- [ ] **Step 2: 运行**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.IDPhotoSpecsTest"`
Expected: 3 tests PASS（`R.string.*` 在 Task 6 加，但此处仅引用 `R.string.id_photo_size_1in` 等——需先加 string 否则编译失败。**依赖顺序：先做 Task 6 Step 1 加 strings，再跑此测试。** 或临时把 `nameRes` 改为占位 `0`，Task 6 再换。本计划采用：**先 Task 6 Step 1 加 strings，再回到 Task 1 跑测试**。）

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/IDPhotoSpecs.kt \
  app/src/test/java/com/mamba/picme/domain/matting/IDPhotoSpecsTest.kt
git commit -m "feat(idphoto): 证件照尺寸/颜色预设（国标 @300dpi）"
```

---

## Task 2: IDPhotoComposer（cover-crop 纯函数 + 合成包装）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/matting/IDPhotoComposer.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/matting/IDPhotoComposerTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/matting/IDPhotoComposerTest.kt`:

```kotlin
package com.mamba.picme.domain.matting

import org.junit.Assert.assertEquals
import org.junit.Test

class IDPhotoComposerTest {

    @Test
    fun `coverCropRect on square source to portrait returns centered vertical crop`() {
        // 100x100 源 → 50x100 目标（更高）：cover 需裁掉左右，宽取 50 居中
        val rect = IDPhotoComposer.coverCropRect(srcW = 100, srcH = 100, dstW = 50, dstH = 100)
        assertEquals(25, rect.left)
        assertEquals(75, rect.right)
        assertEquals(0, rect.top)
        assertEquals(100, rect.bottom)
    }

    @Test
    fun `coverCropRect on wide source to square returns centered horizontal crop`() {
        // 200x100 源 → 100x100 目标：裁掉左右
        val rect = IDPhotoComposer.coverCropRect(srcW = 200, srcH = 100, dstW = 100, dstH = 100)
        assertEquals(50, rect.left)
        assertEquals(150, rect.right)
        assertEquals(0, rect.top)
        assertEquals(100, rect.bottom)
    }

    @Test
    fun `coverCropRect same aspect returns full source`() {
        val rect = IDPhotoComposer.coverCropRect(srcW = 200, srcH = 300, dstW = 100, dstH = 150)
        assertEquals(0, rect.left)
        assertEquals(200, rect.right)
        assertEquals(0, rect.top)
        assertEquals(300, rect.bottom)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.IDPhotoComposerTest"`
Expected: FAIL（unresolved reference）。

- [ ] **Step 3: 实现**

Create `app/src/main/java/com/mamba/picme/domain/matting/IDPhotoComposer.kt`:

```kotlin
package com.mamba.picme.domain.matting

import android.graphics.Bitmap
import android.graphics.Rect

/** 证件照合成：把「Alpha 抠图 + 纯色背景」按目标尺寸 cover-crop。核心 [coverCropRect] 可 JVM 单测。 */
object IDPhotoComposer {

    data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 计算 src 尺寸按 dst 宽高比 cover（填满）所需裁掉的源矩形（居中）。 */
    fun coverCropRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CropRect {
        val srcRatio = srcW.toFloat() / srcH.toFloat()
        val dstRatio = dstW.toFloat() / dstH.toFloat()
        return if (srcRatio > dstRatio) {
            // 源更宽：裁左右
            val cropW = (srcH * dstRatio).toInt()
            val left = (srcW - cropW) / 2
            CropRect(left, 0, left + cropW, srcH)
        } else {
            // 源更高：裁上下
            val cropH = (srcW / dstRatio).toInt()
            val top = (srcH - cropH) / 2
            CropRect(0, top, srcW, top + cropH)
        }
    }

    /** 合成：original+alpha → bgColor（原图尺寸）→ cover-crop → 缩放到 (targetW, targetH)。 */
    fun compose(
        original: Bitmap,
        alpha: FloatArray,
        bgColor: Int,
        targetW: Int,
        targetH: Int
    ): Bitmap {
        val w = original.width
        val h = original.height
        val composited = BackgroundComposer.apply(original, alpha, w, h, bgColor)
        val cr = coverCropRect(w, h, targetW, targetH)
        val cropped = Bitmap.createBitmap(
            composited, cr.left, cr.top, cr.right - cr.left, cr.bottom - cr.top
        )
        val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        if (cropped !== composited && cropped !== scaled) cropped.recycle()
        if (composited !== scaled) composited.recycle()
        return scaled
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.matting.IDPhotoComposerTest"`
Expected: 3 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/matting/IDPhotoComposer.kt \
  app/src/test/java/com/mamba/picme/domain/matting/IDPhotoComposerTest.kt
git commit -m "feat(idphoto): IDPhotoComposer cover-crop 纯函数 + 合成包装"
```

---

## Task 3: Screen.IDPhoto 路由 + MainActivity + AppContainer 工厂

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/navigation/Screen.kt`
- Modify: `app/src/main/java/com/mamba/picme/MainActivity.kt`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`

- [ ] **Step 1: Screen.IDPhoto 路由**

In `Screen.kt`, before the closing `}` of the sealed class, add:

```kotlin
    data object IDPhoto : Screen("id_photo/{sourceUri}") {
        fun createRoute(sourceUri: String): String {
            val encoded = java.net.URLEncoder.encode(sourceUri, "UTF-8")
            return "id_photo/$encoded"
        }
    }
```

- [ ] **Step 2: AppContainer 工厂接口 + 实现**

In `AppContainer.kt`:
- Interface (`AppContainer`): add near `createPhotoEditorViewModelFactory`:

```kotlin
    fun createIDPhotoViewModelFactory(): ViewModelProvider.Factory
```

- Impl (`AppContainerImpl`): add a lazy factory near `photoEditorViewModelFactory`:

```kotlin
    private val idPhotoViewModelFactory: ViewModelProvider.Factory by lazy {
        IDPhotoViewModelFactory(
            appContext = context,
            mattingEngineFactory = { ctx -> MattingEngineImpl(ctx) },
            mediaRepository = repository
        )
    }
```

And the override:

```kotlin
    override fun createIDPhotoViewModelFactory(): ViewModelProvider.Factory = idPhotoViewModelFactory
```

Add imports: `import com.mamba.picme.domain.matting.MattingEngineImpl` and `import com.mamba.picme.features.idphoto.IDPhotoViewModelFactory`. Ensure `ViewModelProvider` is imported.

- [ ] **Step 3: MainActivity composable block**

In `MainActivity.kt`, after the `Screen.PhotoEditor` composable block (after its closing `}` around line 313), add:

```kotlin
                            composable(
                                route = Screen.IDPhoto.route,
                                arguments = listOf(
                                    navArgument("sourceUri") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val encodedSource = backStackEntry.arguments?.getString("sourceUri") ?: ""
                                val sourceUri = java.net.URLDecoder.decode(encodedSource, "UTF-8")
                                val factory = app.container.createIDPhotoViewModelFactory()
                                val viewModel: IDPhotoViewModel = viewModel(factory = factory)
                                IDPhotoScreen(
                                    sourceUri = sourceUri,
                                    viewModel = viewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onSaved = { navController.popBackStack() }
                                )
                            }
```

Add import `import com.mamba.picme.features.idphoto.IDPhotoScreen` and `import com.mamba.picme.features.idphoto.IDPhotoViewModel`. (`NavType`, `viewModel`, `navArgument` already imported for PhotoEditor.)

- [ ] **Step 4: 编译确认（IDPhotoScreen/VM/Factory 暂未创建会失败——先做 Task 4/5 再编译）**

> 依赖顺序：Task 3 引用 `IDPhotoScreen`/`IDPhotoViewModel`/`IDPhotoViewModelFactory`（Task 4 创建）。**先完成 Task 4，再回来编译 Task 3。**

- [ ] **Step 5: 提交（与 Task 4 一起，编译通过后）**

---

## Task 4: IDPhotoViewModel + Factory

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoViewModel.kt`
- Create: `app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoViewModelFactory.kt`

- [ ] **Step 1: ViewModel**

Create `app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoViewModel.kt`:

```kotlin
package com.mamba.picme.features.idphoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.matting.IDPhotoComposer
import com.mamba.picme.domain.matting.IDPhotoSpecs
import com.mamba.picme.domain.matting.MaskSource
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PoLang:IDPhoto"
private const val PREVIEW_MAX_DIM = 1024

class IDPhotoViewModel(
    private val mattingEngine: MattingEngine,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    sealed class State {
        object Loading : State()
        data class Ready(
            val originalBitmap: Bitmap,
            val alpha: FloatArray,
            val alphaWidth: Int,
            val alphaHeight: Int,
            val selectedColorIndex: Int = 0,
            val selectedSizeIndex: Int = 0,
            val isSaving: Boolean = false,
            val error: String? = null
        ) : State
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private var appContext: Context? = null

    fun load(context: Context, sourceUri: String) {
        appContext = context.applicationContext
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val bitmap = decodePreview(context, Uri.parse(sourceUri))
                    ?: run {
                        _state.value = State.Error(context.getString(R.string.editor_load_failed))
                        return@launch
                    }
                val result = mattingEngine.removeBackground(bitmap, MaskSource.MODNET)
                if (result == null) {
                    _state.value = State.Error(context.getString(R.string.id_photo_matting_failed))
                    return@launch
                }
                _state.value = State.Ready(
                    originalBitmap = bitmap,
                    alpha = result.alpha,
                    alphaWidth = result.width,
                    alphaHeight = result.height
                )
            } catch (e: Exception) {
                Logger.e(TAG, "IDPhoto load failed", e)
                _state.value = State.Error(context.getString(R.string.editor_load_failed_with_reason, e.message ?: ""))
            }
        }
    }

    fun selectColor(index: Int) {
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(selectedColorIndex = index)
    }

    fun selectSize(index: Int) {
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(selectedSizeIndex = index)
    }

    /** 合成预览图（供 UI 渲染）。 */
    suspend fun composePreview(): Bitmap? = withContext(Dispatchers.Default) {
        val current = _state.value as? State.Ready ?: return@withContext null
        val color = IDPhotoSpecs.COLORS[current.selectedColorIndex]
        val size = IDPhotoSpecs.SIZES[current.selectedSizeIndex]
        IDPhotoComposer.compose(
            original = current.originalBitmap,
            alpha = current.alpha,
            bgColor = color.argb,
            targetW = size.widthPx,
            targetH = size.heightPx
        )
    }

    fun save(context: Context) {
        val current = _state.value as? State.Ready ?: return
        viewModelScope.launch {
            _state.value = current.copy(isSaving = true)
            try {
                val preview = composePreview() ?: return@launch
                val outputUri = saveBitmapToMediaStore(context, preview)
                if (outputUri != null) {
                    mediaRepository.refreshMediaLibrary()
                    _state.value = (current.copy(isSaving = false))
                    onSaveComplete?.invoke(outputUri)
                } else {
                    _state.value = current.copy(isSaving = false, error = context.getString(R.string.editor_save_failed))
                }
            } catch (e: Exception) {
                Logger.e(TAG, "IDPhoto save failed", e)
                _state.value = current.copy(isSaving = false, error = context.getString(R.string.editor_save_failed_with_reason, e.message ?: ""))
            }
        }
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap): String? {
        val size = (_state.value as? State.Ready)?.selectedSizeIndex?.let { IDPhotoSpecs.SIZES[it] }
        val name = "IDPHOTO_${System.currentTimeMillis()}_${size?.widthPx ?: 0}x${size?.heightPx ?: 0}.jpg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoLang")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return uri?.also {
            context.contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }?.toString()
    }

    private fun decodePreview(context: Context, uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }

    var onSaveComplete: ((String) -> Unit)? = null

    override fun onCleared() {
        super.onCleared()
        (mattingEngine as? com.mamba.picme.domain.matting.MattingEngineImpl)?.release()
    }
}
```

- [ ] **Step 2: Factory**

Create `app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoViewModelFactory.kt`:

```kotlin
package com.mamba.picme.features.idphoto

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.repository.MediaRepository

class IDPhotoViewModelFactory(
    private val appContext: Context,
    private val mattingEngineFactory: (Context) -> MattingEngine,
    private val mediaRepository: MediaRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IDPhotoViewModel::class.java)) {
            return IDPhotoViewModel(
                mattingEngine = mattingEngineFactory(appContext),
                mediaRepository = mediaRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
```

- [ ] **Step 3: 编译（含 Task 3 的接线）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（Task 3 + Task 4 一起编译通过）。若 Task 3 还未改，先补 Task 3 的三处改动。

- [ ] **Step 4: 提交 Task 3 + Task 4**

```bash
git add app/src/main/java/com/mamba/picme/navigation/Screen.kt \
  app/src/main/java/com/mamba/picme/MainActivity.kt \
  app/src/main/java/com/mamba/picme/di/AppContainer.kt \
  app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoViewModel.kt \
  app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoViewModelFactory.kt
git commit -m "feat(idphoto): IDPhoto 路由/VM/工厂（MODNet 抠图 + 合成 + JPEG 保存）"
```

---

## Task 5: IDPhotoScreen UI

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoScreen.kt`
- Create: `app/src/main/java/com/mamba/picme/features/idphoto/components/ColorSwatchRow.kt`
- Create: `app/src/main/java/com/mamba/picme/features/idphoto/components/SizeChipRow.kt`

- [ ] **Step 1: ColorSwatchRow**

Create `app/src/main/java/com/mamba/picme/features/idphoto/components/ColorSwatchRow.kt`:

```kotlin
package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun ColorSwatchRow(
    colors: List<com.mamba.picme.domain.matting.IDPhotoSpecs.Color>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEachIndexed { index, c ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(c.argb), CircleShape)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape
                    )
                    .selectable(selected = selected, onClick = { onSelect(index) })
            )
        }
    }
}
```

- [ ] **Step 2: SizeChipRow**

Create `app/src/main/java/com/mamba/picme/features/idphoto/components/SizeChipRow.kt`:

```kotlin
package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun SizeChipRow(
    sizes: List<com.mamba.picme.domain.matting.IDPhotoSpecs.Size>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sizes.forEachIndexed { index, s ->
            AssistChip(
                onClick = { onSelect(index) },
                label = { Text(stringResource(s.nameRes)) },
                colors = if (index == selectedIndex) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                }
            )
        }
    }
}
```

- [ ] **Step 3: IDPhotoScreen**

Create `app/src/main/java/com/mamba/picme/features/idphoto/IDPhotoScreen.kt`:

```kotlin
package com.mamba.picme.features.idphoto

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.matting.IDPhotoSpecs
import com.mamba.picme.features.idphoto.components.ColorSwatchRow
import com.mamba.picme.features.idphoto.components.SizeChipRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IDPhotoScreen(
    sourceUri: String,
    viewModel: IDPhotoViewModel,
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    LaunchedEffect(sourceUri) {
        viewModel.load(context, sourceUri)
        viewModel.onSaveComplete = { onSaved() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.id_photo_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val ready = state as? IDPhotoViewModel.State.Ready
                    IconButton(
                        onClick = { viewModel.save(context) },
                        enabled = ready != null && !ready.isSaving
                    ) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.done))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF101010)),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is IDPhotoViewModel.State.Loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                is IDPhotoViewModel.State.Error -> Text(s.message, color = Color.White, modifier = Modifier.padding(16.dp))
                is IDPhotoViewModel.State.Ready -> {
                    val preview by produceState<android.graphics.Bitmap?>(null, s.selectedColorIndex, s.selectedSizeIndex) {
                        value = viewModel.composePreview()
                    }
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
                            val bmp = preview
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = stringResource(R.string.id_photo_title),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(width = 220.dp, height = 300.dp)
                                        .background(Color.White, RoundedCornerShape(4.dp))
                                )
                            } else {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        ColorSwatchRow(IDPhotoSpecs.COLORS, s.selectedColorIndex, viewModel::selectColor)
                        SizeChipRow(IDPhotoSpecs.SIZES, s.selectedSizeIndex, viewModel::selectSize)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: 编译确认**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/idphoto/
git commit -m "feat(idphoto): IDPhotoScreen UI（预览 + 颜色/尺寸选择 + 保存）"
```

---

## Task 6: 入口（MediaPager 证件照按钮）+ i18n 文案

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`
- Modify: `app/src/main/res/values/strings.xml` + `values-zh-rCN` + `values-zh-rTW`

- [ ] **Step 1: 三语 strings**

Add to `values/strings.xml`:

```xml
    <string name="id_photo_title">ID Photo</string>
    <string name="id_photo_matting_failed">Portrait matting failed</string>
    <string name="id_photo_action">ID Photo</string>
    <string name="id_photo_size_1in">1 inch</string>
    <string name="id_photo_size_2in">2 inch</string>
    <string name="id_photo_size_small_1in">Small 1 inch</string>
    <string name="id_photo_size_small_2in">Small 2 inch</string>
    <string name="id_photo_color_blue">Blue</string>
    <string name="id_photo_color_red">Red</string>
    <string name="id_photo_color_white">White</string>
```

Add to `values-zh-rCN/strings.xml`:

```xml
    <string name="id_photo_title">证件照</string>
    <string name="id_photo_matting_failed">人像抠图失败</string>
    <string name="id_photo_action">证件照</string>
    <string name="id_photo_size_1in">1寸</string>
    <string name="id_photo_size_2in">2寸</string>
    <string name="id_photo_size_small_1in">小1寸</string>
    <string name="id_photo_size_small_2in">小2寸</string>
    <string name="id_photo_color_blue">标准蓝</string>
    <string name="id_photo_color_red">标准红</string>
    <string name="id_photo_color_white">白</string>
```

Add to `values-zh-rTW/strings.xml`:

```xml
    <string name="id_photo_title">證件照</string>
    <string name="id_photo_matting_failed">人像去背失敗</string>
    <string name="id_photo_action">證件照</string>
    <string name="id_photo_size_1in">1寸</string>
    <string name="id_photo_size_2in">2寸</string>
    <string name="id_photo_size_small_1in">小1寸</string>
    <string name="id_photo_size_small_2in">小2寸</string>
    <string name="id_photo_color_blue">標準藍</string>
    <string name="id_photo_color_red">標準紅</string>
    <string name="id_photo_color_white">白</string>
```

> 放在 Task 1 之前执行此 Step，让 `R.string.id_photo_*` 在 IDPhotoSpecs 编译时已存在。

- [ ] **Step 2: MediaPager 加「证件照」按钮**

In `MediaPager.kt`, add a callback param `onIdPhoto: (MediaAsset) -> Unit` next to `onAiOptimize: (MediaAsset) -> Unit` (around line 149). Then near the 编辑 button (around line 1039–1054), add a parallel「证件照」button:

```kotlin
                            // 证件照
                            IconButton(
                                onClick = { currentAsset?.let { onIdPhoto(it) } },
                                modifier = Modifier.semantics { contentDescription = context.getString(R.string.id_photo_action) }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Badge,
                                    contentDescription = stringResource(R.string.id_photo_action),
                                    tint = Color.White
                                )
                            }
```

(Match the existing 编辑 button's exact Modifier/icon style — read the 编辑 button block and mirror it. Add import `androidx.compose.material.icons.outlined.Badge`.)

- [ ] **Step 3: GalleryScreen 透传 onIdPhoto 导航**

In `GalleryScreen.kt`, the `MediaPager(...)` call (around line 778) already passes `onNavigateToEditor` and `onAiOptimize`. Add:

```kotlin
                        onIdPhoto = { asset ->
                            navController.navigate(
                                Screen.IDPhoto.createRoute(sourceUri = asset.uri),
                                navOptions { launchSingleTop = true }
                            )
                        },
```

Add import `import com.mamba.picme.navigation.Screen` if not present (likely already imported).

- [ ] **Step 4: 编译 + 资源校验**

Run: `./gradlew :app:compileDebugKotlin :app:processDebugResources`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt \
  app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(idphoto): 照片详情新增证件照入口 + 三语文案"
```

---

## Task 7: 设备端到端验证

**Files:** 无新增（验证性任务）

- [ ] **Step 1: 全量单测 + APK**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 安装 + 证件照流程**

`adb install -r`，相册打开一张**人像** → 照片详情页应见「证件照」按钮（编辑旁）→ 点击 → 进入证件照专区：
1. 加载 → MODNet 抠图（日志 `ModNetOnnxBackend initialized`）。
2. 预览：人像合成在默认蓝色背景、1寸尺寸。
3. 切换颜色（蓝/红/白）→ 预览实时变背景色。
4. 切换尺寸（1寸/2寸/...）→ 预览宽高比变。
5. 点完成 → 保存 JPEG（`IDPHOTO_*_295x413.jpg`）到 Pictures/PoLang。

- [ ] **Step 3: 校验输出**

```
adb shell 'ls -t /sdcard/Pictures/PoLang/ | head -1'
```
拉取后确认：JPEG、尺寸 = 所选规格（如 295×413）、背景为所选纯色、人像软边（MODNet）。

- [ ] **Step 4: 失败路径**

无人脸物体图进入证件照 → MODNet 抠图效果差但不崩（状态 Error 或效果不佳，可接受；证件照本就面向人像）。

- [ ] **Step 5: 提交（如有 verify 修复）**

```bash
git add -A
git commit -m "test(idphoto): P3 证件照专区设备端到端验证通过"
```

---

## Self-Review

**Spec coverage（spec §5 P3 + §4 证件照入口）：**
- 独立入口（照片详情「证件照」）→ Task 6 ✓
- 固定 MODNet → Task 4（`MaskSource.MODNET`）✓
- 背景色选择（蓝/红/白）→ Task 1 预设 + Task 5 UI ✓
- 标准尺寸裁剪 → Task 1 预设 + Task 2 cover-crop + Task 5 UI ✓
- 输出 JPEG 合成 → Task 4 saveBitmapToMediaStore ✓
- 批量：**P3 不做**（后续）✓

**Placeholder scan：** Task 6 Step 2 的 MediaPager 按钮要求镜像既有「编辑」按钮风格（读取后照抄），有明确指引；其余每步含完整代码或命令。

**类型/签名一致性：** `Screen.IDPhoto.createRoute(sourceUri)`（Task 3）↔ GalleryScreen 导航（Task 6）↔ MainActivity 解码（Task 3）一致。`IDPhotoViewModel.State.Ready` 字段在 VM（Task 4）↔ Screen（Task 5）一致。`MattingEngine.removeBackground(bitmap, MaskSource.MODNET)`（P2 接口）调用一致。`IDPhotoComposer.compose(original, alpha, bgColor, targetW, targetH)` Task 2 定义、Task 4 调用一致。

**已知执行注意：**
- **执行顺序**：先 Task 6 Step 1（strings），再 Task 1（IDPhotoSpecs 引用 R.string），再 Task 2，再 Task 4 → Task 3（Task 3 引用 Task 4 的 Screen/VM/Factory），再 Task 5，最后 Task 6 Step 2-5（入口）。即：**T6-strings → T1 → T2 → T4 → T3 → T5 → T6-entry → T7**。
- Task 6 Step 2 的 `Icons.Outlined.Badge` 需 material-icons-extended（已确认 P1 时可用）。
- `produceState` 重算预览：颜色/尺寸变化触发；MODNet 抠图只跑一次（load 时）。
- Task 7 Step 4：无人脸图 MODNet 效果差是预期（证件照面向人像）。
