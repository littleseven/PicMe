# Face-Aware List Alignment 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **环境提示：** 当前会话子代理（Agent/Workflow）不可用——执行时使用 superpowers:executing-plans（inline）。提交（git commit）按 CLAUDE.md「只在用户要求时提交」执行；本计划的 commit 步骤在用户授权前用「暂存改动 + 编译/测试验证」替代。

**Goal:** 让相册主网格与 chat 搜索横滑两个列表在 `ContentScale.Crop`（不整体缩放）前提下，按人脸纵向位置对齐，含人脸的照片不再「砍头」。

**Architecture:** 复用 Pass 1 已有的端侧人脸检测，把「人脸包络中心」归一化为 `faceFocusY`（0~1）持久化到 `MediaEntity.faceFocusY` 新列（Room 17→18 migration），经 `MediaAsset` 透传到 UI；新增纯 UI 工具 `faceAwareVerticalAlignment(faceFocusY)` 返回自定义 `Alignment`，替换两处 `AsyncImage` 的默认居中对齐（`contentScale=Crop` 不变）。老照片通过 scheduler 的 `backfillFaceFocus` 一次性回填。

**Tech Stack:** Kotlin · Jetpack Compose（Alignment/ContentScale）· Room（Migration）· Coil AsyncImage · 端侧人脸检测（pipeline 自有 faceDetector）· JUnit4 JVM 单测

**Spec:** `docs/superpowers/specs/2026-07-29-face-aware-list-alignment-design.md`

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `app/src/main/java/com/mamba/picme/domain/tag/PipelineModels.kt` | `computeFaceFocusY` 纯函数 + `Stage1WithEmbeddingsResult.faceFocusY` 字段 | Modify |
| `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt` | `stage1WithEmbeddings` 填充 faceFocusY；新增 `detectFaceFocusY(uri)` | Modify |
| `app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt` | 加 `faceFocusY: Float?` 列 | Modify |
| `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/MediaAsset.kt` | 加 `faceFocusY: Float?` 字段 | Modify |
| `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt` | `version 17→18` + `MIGRATION_17_18` + 注册 | Modify |
| `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt` | `updateFaceFocusY` + `getMediaWithFacesWithoutFocus` | Modify |
| `app/src/main/java/com/mamba/picme/data/repository/MediaRepositoryImpl.kt` | `toDomain`/`toEntity` 透传 faceFocusY | Modify |
| `app/src/main/java/com/mamba/picme/core/image/FaceAwareAlignment.kt` | `faceAwareVerticalAlignment(faceFocusY, biasUp)` 纯 UI 工具 | Create |
| `app/src/main/java/com/mamba/picme/features/gallery/components/MediaGrid.kt` | `MediaItem.AsyncImage` 加 alignment | Modify |
| `app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt` | `MediaCard.AsyncImage` 加 alignment | Modify |
| `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt` | `executeFaceDetection` 写 faceFocusY；新增 `backfillFaceFocus` 并挂载 | Modify |
| `app/src/test/java/com/mamba/picme/domain/tag/FaceFocusTest.kt` | `computeFaceFocusY` JVM 单测 | Create |
| `app/src/test/java/com/mamba/picme/core/image/FaceAwareAlignmentTest.kt` | `faceAwareVerticalAlignment` JVM 单测 | Create |

---

## Task 1: 纯函数 `computeFaceFocusY` + 单测（TDD）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/PipelineModels.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/tag/FaceFocusTest.kt`

`computeFaceFocusY(faces, bitmapHeight)`：取所有人脸 ROI 纵向并集 `(min top + max bottom) / 2`，除以 `bitmapHeight` 归一化，clamp 到 `[0,1]`；faces 空 → `null`。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mamba/picme/domain/tag/FaceFocusTest.kt`：

```kotlin
package com.mamba.picme.domain.tag

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceFocusTest {
    @Test
    fun empty_faces_returns_null() {
        assertNull(computeFaceFocusY(emptyList(), bitmapHeight = 1000))
    }

    @Test
    fun single_face_center() {
        // 人脸框 top=300 bottom=500 → 中心 400 / 1000 = 0.4
        val faces = listOf(FaceRoi(RectF(100f, 300f, 200f, 500f)))
        val y = computeFaceFocusY(faces, bitmapHeight = 1000)
        assertEquals(0.4f, y!!, 1e-4f)
    }

    @Test
    fun group_photo_uses_envelope_center() {
        // 两人脸：top=200/bottom=400 与 top=600/bottom=800
        // 并集中心 = (min(200,600) + max(400,800))/2 = (200+800)/2 = 500 → 0.5
        val faces = listOf(
            FaceRoi(RectF(10f, 200f, 100f, 400f)),
            FaceRoi(RectF(10f, 600f, 100f, 800f))
        )
        val y = computeFaceFocusY(faces, bitmapHeight = 1000)
        assertEquals(0.5f, y!!, 1e-4f)
    }

    @Test
    fun result_is_clamped_to_unit_range() {
        // 极端框：top=-100 bottom=2000，bitmapHeight=1000
        // 中心 = (-100+2000)/2 = 950 → 0.95（已落在 [0,1]，clamp 不触发）
        val faces = listOf(FaceRoi(RectF(0f, -100f, 10f, 2000f)))
        val y = computeFaceFocusY(faces, bitmapHeight = 1000)
        assertEquals(0.95f, y!!, 1e-4f)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.FaceFocusTest"`
Expected: 编译失败（`computeFaceFocusY` 未定义）。

- [ ] **Step 3: 实现纯函数**

在 `PipelineModels.kt` 文件末尾（最后一个 `data class` 之后）追加顶层函数：

```kotlin
/**
 * 计算人脸纵向「聚焦点」——所有人脸 ROI 纵向并集的中心，归一化到 [0,1]（相对 bitmap 高度）。
 *
 * 用于列表缩略图在 ContentScale.Crop 下的纵向对齐：null 表示无人脸（UI 回退居中）。
 * 仅取纵向（top/bottom）并集，忽略横向；与镜头方向无关。
 */
fun computeFaceFocusY(faces: List<FaceRoi>, bitmapHeight: Int): Float? {
    if (faces.isEmpty() || bitmapHeight <= 0) return null
    val minTop = faces.minOf { it.roi.top }
    val maxBottom = faces.maxOf { it.roi.bottom }
    val center = (minTop + maxBottom) / 2f
    return (center / bitmapHeight.toFloat()).coerceIn(0f, 1f)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.FaceFocusTest"`
Expected: PASS（4 个用例全绿）。

- [ ] **Step 5: 暂存改动（提交待用户授权）**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/PipelineModels.kt \
        app/src/test/java/com/mamba/picme/domain/tag/FaceFocusTest.kt
```

---

## Task 2: 数据层 schema（MediaEntity / MediaAsset 字段 + Room 17→18 + DAO 查询）

整层一起改，避免 entity 加字段后 Room schema 校验编译断裂。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/MediaAsset.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/MediaDao.kt`

- [ ] **Step 1: MediaEntity 加列**

在 `MediaEntity.kt` 的 `faceRoiResult` 字段之后追加：

```kotlin
    /** 人脸纵向聚焦点（归一化 0~1，Pass 1 检测算出；null=无人脸/未回填）。供列表缩略图纵向对齐。 */
    val faceFocusY: Float? = null,
```

- [ ] **Step 2: MediaAsset 加字段**

在 `MediaAsset.kt` 的 `indexedAt` 字段之后追加：

```kotlin
    /** 人脸纵向聚焦点（归一化 0~1，null=无人脸/未回填）。列表缩略图纵向对齐用。 */
    val faceFocusY: Float? = null
```

（注意上一个字段 `indexedAt` 末尾无逗号，改为 `indexedAt: Long? = null,` 后再追加 `faceFocusY`。）

- [ ] **Step 3: Room version 17→18 + migration**

在 `AppDatabase.kt`：
1. 将 `version = 17,` 改为 `version = 18,`（约 line 55）。
2. 在 `addMigrations(...)` 调用中，于 `MIGRATION_16_17` 之后追加 `, MIGRATION_17_18`（约 line 90）。
3. 在 `MIGRATION_16_17` 定义之后追加新 migration：

```kotlin
        /**
         * Migration 17 → 18：新增 media_assets.faceFocusY 字段（人脸纵向聚焦点，列表缩略图对齐）
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `media_assets` ADD COLUMN `faceFocusY` REAL"
                )
            }
        }
```

- [ ] **Step 4: MediaDao 新增查询**

在 `MediaDao.kt` 中 `updateFaceRoiResult` 定义附近追加：

```kotlin
    /** 更新人脸纵向聚焦点（Pass 1 检测 / 回填产出） */
    @Query("UPDATE media_assets SET faceFocusY = :faceFocusY WHERE id = :mediaId")
    suspend fun updateFaceFocusY(mediaId: Long, faceFocusY: Float?)

    /** 含人脸但尚未回填 faceFocusY 的照片（供一次性回填扫描） */
    @Query("SELECT * FROM media_assets WHERE hasFace = 1 AND faceFocusY IS NULL AND type = 'PHOTO' ORDER BY captureDate DESC")
    suspend fun getMediaWithFacesWithoutFocus(): List<MediaEntity>
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin :runtime-core:compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 暂存改动**

```bash
git add app/src/main/java/com/mamba/picme/data/model/MediaEntity.kt \
        runtime-core/src/main/java/com/mamba/picme/agent/core/model/context/MediaAsset.kt \
        app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt \
        app/src/main/java/com/mamba/picme/data/local/MediaDao.kt
```

---

## Task 3: Repository 映射透传 faceFocusY

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/data/repository/MediaRepositoryImpl.kt`（`toDomain` 约 line 529；`toEntity` 紧随其后）

- [ ] **Step 1: toDomain 透传**

在 `toDomain()` 的 `indexedAt = indexedAt` 之后加一行（并在 `indexedAt = indexedAt` 末尾补逗号）：

```kotlin
        indexedAt = indexedAt,
        faceFocusY = faceFocusY
```

- [ ] **Step 2: toEntity 透传**

在 `toEntity()` 的 `indexedAt = indexedAt` 之后加一行（并补逗号）：

```kotlin
        indexedAt = indexedAt,
        faceFocusY = faceFocusY
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

> 说明：`toDomain`/`toEntity` 为 private，且依赖 `AppLanguage` 等运行时状态，无现成 Room in-memory 测试基架。faceFocusY 透传为 data class 字段直传，编译期保证正确性；端到端 round-trip 在 Task 9 设备验证。

- [ ] **Step 4: 暂存改动**

```bash
git add app/src/main/java/com/mamba/picme/data/repository/MediaRepositoryImpl.kt
```

---

## Task 4: Stage 1 产出 faceFocusY（pipeline 填充 + 回填用 IO 入口）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/PipelineModels.kt`（`Stage1WithEmbeddingsResult` 加字段）
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt`

- [ ] **Step 1: Stage1WithEmbeddingsResult 加字段**

在 `PipelineModels.kt` 的 `Stage1WithEmbeddingsResult` 中，`semanticEmbedding` 字段后追加：

```kotlin
    /** 人脸纵向聚焦点（归一化 0~1；null=无人脸/解码失败）。供列表对齐持久化。 */
    val faceFocusY: Float? = null
```

- [ ] **Step 2: stage1WithEmbeddings 填充 faceFocusY**

在 `TagGenerationPipeline.kt` 的 `stage1WithEmbeddings` 中，将 line 225 的 return：

```kotlin
            return Stage1WithEmbeddingsResult(faceRoiJson, embeddings, semanticEmbedding)
```

改为：

```kotlin
            val faceFocusY = computeFaceFocusY(stage1Result.faces, faceBitmap.height)
            return Stage1WithEmbeddingsResult(faceRoiJson, embeddings, semanticEmbedding, faceFocusY)
```

（line 188 的早退 `return Stage1WithEmbeddingsResult(null, emptyList())` 无需改——新字段默认 null。）

- [ ] **Step 3: 新增回填用 IO 入口 detectFaceFocusY(uri)**

在 `TagGenerationPipeline.kt` 中 `stage1WithEmbeddings` 函数之后追加：

```kotlin
    /**
     * 仅做人脸纵向聚焦点检测（轻量，不提取 embedding / 不做 MobileCLIP）。
     * 供老照片一次性回填 [MediaEntity.faceFocusY] 使用。null=解码失败或无人脸。
     */
    suspend fun detectFaceFocusY(uri: String): Float? {
        val bitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE) ?: return null
        try {
            val stage1 = stage1FaceDetection(bitmap, androidx.camera.core.CameraSelector.LENS_FACING_BACK)
            return computeFaceFocusY(stage1.faces, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
```

- [ ] **Step 4: 编译 + 跑 Task 1 测试确认无回归**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.FaceFocusTest"`
Expected: BUILD SUCCESSFUL + 测试 PASS。

- [ ] **Step 5: 暂存改动**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/PipelineModels.kt \
        app/src/main/java/com/mamba/picme/domain/tag/TagGenerationPipeline.kt
```

---

## Task 5: UI 对齐工具 `faceAwareVerticalAlignment` + 单测（TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/core/image/FaceAwareAlignment.kt`
- Test: `app/src/test/java/com/mamba/picme/core/image/FaceAwareAlignmentTest.kt`

算法：`faceFocusY == null` → `Alignment.Center`；否则自定义 `Alignment`，`x` 保持居中，`y = (space.h/2 − biasUp·space.h − faceFocusY·size.h)` 取整后 `coerceIn(space.h − size.h, 0)`，即把人脸中心对齐到框中心上方 `biasUp` 处并 clamp。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mamba/picme/core/image/FaceAwareAlignmentTest.kt`：

```kotlin
package com.mamba.picme.core.image

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceAwareAlignmentTest {
    // 竖图 2:3 进正方形 cell（Crop）：space=100×100，绘制后图片 size=100×150
    private val space = IntSize(100, 100)
    private val portrait = IntSize(100, 150)

    @Test
    fun null_focus_returns_center_alignment() {
        val a = faceAwareVerticalAlignment(null)
        // 与 Alignment.Center 结果一致
        assertEquals(
            Alignment.Center.align(portrait, space, androidx.compose.ui.unit.LayoutDirection.Ltr),
            a.align(portrait, space, androidx.compose.ui.unit.LayoutDirection.Ltr)
        )
    }

    @Test
    fun centered_face_shifts_up_by_bias() {
        // faceFocusY=0.5：rawY = 50 - (1/6)*100 - 0.5*150 = 50 - 16.6667 - 75 = -41.6667 → -42
        val a = faceAwareVerticalAlignment(0.5f)
        assertEquals(
            IntOffset(0, -42),
            a.align(portrait, space, androidx.compose.ui.unit.LayoutDirection.Ltr)
        )
    }

    @Test
    fun top_face_clamps_to_zero() {
        // faceFocusY=0.2：rawY = 50 - 16.6667 - 30 = 3.333 → coerceIn(-50,0) = 0
        val a = faceAwareVerticalAlignment(0.2f)
        assertEquals(
            IntOffset(0, 0),
            a.align(portrait, space, androidx.compose.ui.unit.LayoutDirection.Ltr)
        )
    }

    @Test
    fun bottom_face_clamps_to_min() {
        // faceFocusY=0.9：rawY = 50 - 16.6667 - 135 = -101.667 → coerceIn(-50,0) = -50
        val a = faceAwareVerticalAlignment(0.9f)
        assertEquals(
            IntOffset(0, -50),
            a.align(portrait, space, androidx.compose.ui.unit.LayoutDirection.Ltr)
        )
    }

    @Test
    fun landscape_keeps_horizontal_centered() {
        // 横图进竖卡（Crop）：space=100×150，size=200×150
        val s = IntSize(100, 150)
        val landscape = IntSize(200, 150)
        // faceFocusY=0.5：x=(100-200)/2=-50；y: minY=0, rawY=75-25-75=-25→coerceIn(0,0)=0
        val a = faceAwareVerticalAlignment(0.5f)
        assertEquals(
            IntOffset(-50, 0),
            a.align(landscape, s, androidx.compose.ui.unit.LayoutDirection.Ltr)
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.image.FaceAwareAlignmentTest"`
Expected: 编译失败（`faceAwareVerticalAlignment` 未定义）。

- [ ] **Step 3: 实现 UI 工具**

创建 `app/src/main/java/com/mamba/picme/core/image/FaceAwareAlignment.kt`：

```kotlin
package com.mamba.picme.core.image

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * 列表缩略图「人脸感知」纵向对齐。
 *
 * 在 [androidx.compose.foundation.Image] / Coil AsyncImage 使用 `ContentScale.Crop`（不整体缩放）时，
 * 用本函数的返回值作为 `alignment`，使含人脸的照片按人脸纵向位置对齐，避免居中裁剪「砍头」。
 *
 * - [faceFocusY] == null（无人脸 / 未回填 / 视频）→ 返回 [Alignment.Center]，与改动前一致。
 * - 否则把「人脸中心」对齐到「框中心上方 [biasUp]·框高」处，并 clamp 到合法裁剪范围。
 * - 横向始终居中（本功能只优化上下方向）。
 *
 * @param faceFocusY 人脸纵向聚焦点（归一化 0~1，来自 MediaAsset.faceFocusY）
 * @param biasUp 人脸中心相对框中心向上偏移的比例，默认 1/6（头顶留白，接近主流相册观感）
 */
fun faceAwareVerticalAlignment(
    faceFocusY: Float?,
    biasUp: Float = 1f / 6f
): Alignment = if (faceFocusY == null) {
    Alignment.Center
} else {
    Alignment { size, space, _ ->
        val x = ((space.width - size.width) / 2f).roundToInt()
        val minY = space.height - size.height
        val rawY = space.height / 2f - biasUp * space.height - faceFocusY * size.height
        val y = rawY.roundToInt().coerceIn(minY, 0)
        IntOffset(x, y)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.image.FaceAwareAlignmentTest"`
Expected: PASS（5 个用例全绿）。

- [ ] **Step 5: 暂存改动**

```bash
git add app/src/main/java/com/mamba/picme/core/image/FaceAwareAlignment.kt \
        app/src/test/java/com/mamba/picme/core/image/FaceAwareAlignmentTest.kt
```

---

## Task 6: 接入 MediaGrid（相册主网格）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/MediaGrid.kt`（`MediaItem` 的 `AsyncImage`，约 line 227-240）

- [ ] **Step 1: 加 import**

在 `MediaGrid.kt` import 区（`com.mamba.picme.core.image.ThumbnailCache` 附近）加：

```kotlin
import com.mamba.picme.core.image.faceAwareVerticalAlignment
```

- [ ] **Step 2: AsyncImage 加 alignment**

将 `MediaItem` 中 `AsyncImage(...)` 的 `contentScale = ContentScale.Crop,` 行之后，新增一行 `alignment`：

```kotlin
            contentScale = ContentScale.Crop,
            alignment = faceAwareVerticalAlignment(asset.faceFocusY),
            placeholder = ThumbnailPlaceholderPainter,
            error = ThumbnailPlaceholderPainter
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 暂存改动**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/MediaGrid.kt
```

---

## Task 7: 接入 MediaResultsCarousel（chat 搜索横滑）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt`（`MediaCard` 的 `AsyncImage`，约 line 125-140）

- [ ] **Step 1: 加 import**

在 `MediaResultsCarousel.kt` import 区加：

```kotlin
import com.mamba.picme.core.image.faceAwareVerticalAlignment
```

- [ ] **Step 2: AsyncImage 加 alignment**

将 `MediaCard` 中 `AsyncImage(...)` 的 `contentScale = ContentScale.Crop,` 行之后，新增：

```kotlin
                contentScale = ContentScale.Crop,
                alignment = faceAwareVerticalAlignment(asset.faceFocusY),
                modifier = Modifier
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 暂存改动**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/components/MediaResultsCarousel.kt
```

---

## Task 8: 写库接线（新照片 + 老照片回填）

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt`

- [ ] **Step 1: 新照片写 faceFocusY**

在 `executeFaceDetection`（约 line 1115 `dao.updateFaceRoiResult(...)` 之后）追加：

```kotlin
        val faceFocusY = result.faceFocusY
        if (faceFocusY != null) {
            dao.updateFaceFocusY(entity.id, faceFocusY)
        }
```

- [ ] **Step 2: 新增回填函数 backfillFaceFocus**

在 `cleanupInvalidHasFace`（约 line 717）之后新增：

```kotlin
    /**
     * 一次性回填老照片的 faceFocusY（hasFace=1 但 faceFocusY IS NULL）。
     *
     * 仅做轻量人脸检测算纵向聚焦点，不重提 embedding / 不重算 MobileCLIP。
     * 幂等：已回填的（faceFocusY NOT NULL）自动跳过。挂在聚类流程末尾，随扫描渐进覆盖。
     */
    private suspend fun backfillFaceFocus(dao: com.mamba.picme.data.local.MediaDao) {
        val pending = dao.getMediaWithFacesWithoutFocus()
        if (pending.isEmpty()) return
        Log.i(TAG, "Backfilling faceFocusY for ${pending.size} media")
        for (media in pending) {
            currentCoroutineContext().ensureActive()
            val focusY = pipeline.detectFaceFocusY(media.uri) ?: continue
            dao.updateFaceFocusY(media.id, focusY)
        }
        Log.i(TAG, "faceFocusY backfill done")
    }
```

- [ ] **Step 3: 挂载回填（聚类流程末尾，cleanupInvalidHasFace 旁）**

在 `TagGenerationScheduler.kt` line 638 `cleanupInvalidHasFace(dao)` 之后追加一行：

```kotlin
        cleanupInvalidHasFace(dao)
        backfillFaceFocus(dao)
```

> 说明：回填依赖设备端人脸检测（ML Kit / 自有 faceDetector），无 JVM 单测；归为集成验证（Task 9 设备端）。核心纯函数 `computeFaceFocusY` 已在 Task 1 覆盖。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 暂存改动**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
```

---

## Task 9: 验收（全量编译 + 全 JVM 测试 + 设备端到端 + 红线/文档复核）

- [ ] **Step 1: 全量编译**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 全 JVM 单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全绿（含 FaceFocusTest、FaceAwareAlignmentTest，且无既有测试回归）。

- [ ] **Step 3: 安装并设备验证**

Run: `./gradlew :app:installDebug`（或 `adb install -r app/build/outputs/apk/debug/*.apk`）

验证清单：
1. 相册主网格：含人脸的竖图不再「砍头」，人脸落在框中心偏上；无人脸/视频保持居中。
2. chat 搜索结果横滑：同上。
3. 老照片：触发一次扫描（聚类/Pass）后，含人脸的老照片逐步获得人脸感知对齐（观察 logcat `PoLang:TagGen` 的 `Backfilling faceFocusY for N media` / `faceFocusY backfill done`）。

> 若启动因 worktree DB 版本不符崩溃（onDowngrade/onCreate 异常），用 `adb shell pm clear com.mamba.picme` 绕过后重试（已知 worktree 坑）。

- [ ] **Step 4: 红线复核**

确认：人脸检测全程端侧；`faceFocusY` 仅是归一化 float，无任何图片/视频文件上传。符合 `[PRIVACY]`。

- [ ] **Step 5: 文档同步（doc-sync-guardian）**

- `docs/04-AGENT-CAPABILITIES/` 不涉及（无 capability 变更）。
- 若 `docs/` 有人脸数据/扫描相关 spec 提及 `faceRoiResult` 字段清单，补注 `faceFocusY` 新列（检索 `faceRoiResult` 出现的 spec 文件，按需补一行）。
- 本计划与 spec 已在 `docs/superpowers/{specs,plans}/`，无需额外同步。

- [ ] **Step 6: 收尾提交（待用户明确授权后）**

```bash
git add -A
git commit -m "feat(gallery): 人脸感知列表缩略图纵向对齐——含人脸照片不再砍头

- 新增 faceFocusY（归一化人脸包络中心）持久化：MediaEntity 加列 + Room 17→18 migration
- 复用 Pass 1 端侧检测产出 faceFocusY，经 MediaAsset 透传到 UI
- faceAwareVerticalAlignment：Crop 下按人脸纵向位置对齐（合影包络中心/单脸略偏上）
- 接入 MediaGrid + MediaResultsCarousel；老照片 backfillFaceFocus 一次性回填
- 端侧检测，仅存归一化位置，符合 PRIVACY 红线"
```

---

## Self-Review

**1. Spec 覆盖**
- §2 目标（两列表 / Crop 不缩放 / 合影包络中心 / 单脸偏上 / 老照片回填）→ Task 5（算法）+ 6/7（接入）+ 8（回填）。✅
- §3 数据来源 A（复用 Pass1）→ Task 4。✅
- §5 存储（独立列 + 17→18 + 两写入点 + computeFaceFocusY）→ Task 2（列/migration/DAO）+ 4（新照片写入）+ 8（回填）。✅
- §6 UI 算法（null→Center / 自定义 align / x 居中 / y 公式 + clamp）→ Task 5，5 个测试覆盖 null/中/上/下/横向。✅
- §7 降级边界性能隐私 → Task 5（null→Center）+ 8（幂等回填）+ 9 Step4（红线）。✅
- §8 范围 → Task 6/7（仅两列表）。✅
- §9 测试 → Task 1（computeFaceFocusY）+ Task 5（alignment）；toDomain 透传因 private + 无 Room 测试基架改编译期保证 + 设备验证（Task 3 Step3 已说明）。✅

**2. Placeholder 扫描**
- 已清查：所有步骤均含完整、可直接落地的代码 / 精确命令 / 期望输出，无 TBD / TODO / 占位函数 / 拼写占位。

**3. 类型/命名一致性**
- `faceFocusY: Float?`：MediaEntity / MediaAsset / Stage1WithEmbeddingsResult / faceAwareVerticalAlignment 形参 / updateFaceFocusY 形参，全程一致。✅
- `computeFaceFocusY(faces, bitmapHeight)`：定义（Task1）与调用（Task4 stage1WithEmbeddings、detectFaceFocusY）签名一致。✅
- `detectFaceFocusY(uri)`：定义（Task4）与调用（Task8 backfillFaceFocus `pipeline.detectFaceFocusY`）一致；`pipeline` 字段名与既有 `executeFaceDetection` 用法（scheduler:1103）一致。✅
- `getMediaWithFacesWithoutFocus()` / `updateFaceFocusY()`：定义（Task2）与调用（Task8）一致。✅
- `asset.faceFocusY`：Task6/7 接入处，`MediaItem`/`MediaCard` 的 `asset: MediaAsset` 已有该字段（Task2 Step2）。✅

**4. 风险点**
- Room schema：entity 加列必须与 version+migration 同 Task（Task2）落地，否则 Room 编译报错——已在 Task2 整层一起改。✅
- Compose `Alignment`/`IntSize`/`IntOffset` 在 JVM test classpath：app 模块含 compose-ui，既有 `app/src/test/.../core/image/LipColorAlgorithmTest` 证明 core/image 可 JVM 测；Alignment 为 compose-ui 纯值类，可用。若 testDebugUnitTest 因 compose 依赖缺失编译失败，改用 `:app:testDebugUnitTest` 已含。✅
- 老照片回填的设备依赖：归类为 Task9 集成验证，不阻断 JVM 绿。✅
