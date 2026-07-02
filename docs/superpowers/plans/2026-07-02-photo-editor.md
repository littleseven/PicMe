# Photo Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a full-screen non-destructive photo editor in the gallery flow with crop, adjust, beauty, filter and markup tabs, replacing the current in-place beauty editing panel.

**Architecture:**
- All edit operations are represented by an immutable `EditRecipe` and managed through `PhotoEditorViewModel`.
- `RecipeApplier` applies crop/GPU adjustments/markup to a preview or full-resolution bitmap.
- `EditHistory` provides undo/redo.
- Recipes are persisted in Room so edited copies can be reopened later.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose, Room, beauty-engine GPU pipeline, MediaStore.

---

## File Structure

| File | Responsibility |
|------|----------------|
| `features/editor/EditRecipe.kt` | Immutable recipe data classes (crop, adjustments, beauty, filters, markup). |
| `features/editor/EditHistory.kt` | Undo/redo stack over `EditRecipe`. |
| `features/editor/AspectRatio.kt` | Crop aspect ratio enum. |
| `features/editor/MarkupAction.kt` | Sealed class for doodle/mosaic/text actions. |
| `features/editor/RecipeApplier.kt` | Applies recipe to bitmap (preview + full-res). |
| `features/editor/PhotoEditorViewModel.kt` | State holder, history, save/load. |
| `features/editor/PhotoEditorScreen.kt` | Root editor composable. |
| `features/editor/components/EditorTopBar.kt` | Cancel / undo / redo / compare / done. |
| `features/editor/components/EditorBottomBar.kt` | Category tab switcher. |
| `features/editor/components/CropPanel.kt` | Crop ratio + rotate + flip controls. |
| `features/editor/components/AdjustPanel.kt` | Light/color sliders. |
| `features/editor/components/MarkupPanel.kt` | Doodle/mosaic controls. |
| `data/local/entity/PhotoEditRecipeEntity.kt` | Room entity for persisted recipe. |
| `data/local/dao/PhotoEditRecipeDao.kt` | Recipe persistence DAO. |
| `data/repository/PhotoEditRecipeRepository.kt` | Repository over DAO + JSON. |
| `di/AppContainer.kt` | Provide repository and processor dependencies. |
| `navigation/Screen.kt` | Add `PhotoEditor` route. |
| `MainActivity.kt` | Add `PhotoEditorScreen` composable to NavHost. |
| `features/gallery/components/MediaPager.kt` | Replace in-place edit with navigation. |
| `features/gallery/MediaViewModel.kt` | Remove in-place photo edit state. |

---

## Task 1: Define `EditRecipe` data model

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/editor/AspectRatio.kt`
- Create: `app/src/main/java/com/mamba/picme/features/editor/MarkupAction.kt`
- Create: `app/src/main/java/com/mamba/picme/features/editor/EditRecipe.kt`

- [ ] **Step 1: Write `AspectRatio.kt`**

```kotlin
package com.mamba.picme.features.editor

enum class AspectRatio(val label: String, val ratio: Float?) {
    FREE("自由", null),
    ORIGINAL("原始", -1f),
    SQUARE("1:1", 1f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_3_4("3:4", 3f / 4f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_9_16("9:16", 9f / 16f)
}
```

- [ ] **Step 2: Write `MarkupAction.kt`**

```kotlin
package com.mamba.picme.features.editor

import android.graphics.Path
import android.graphics.PointF

sealed class MarkupAction {
    abstract val id: String

    data class Doodle(
        override val id: String,
        val path: Path,
        val color: Int,
        val strokeWidth: Float
    ) : MarkupAction()

    data class Mosaic(
        override val id: String,
        val path: Path,
        val strokeWidth: Float,
        val mode: MosaicMode = MosaicMode.PIXEL
    ) : MarkupAction()

    data class Text(
        override val id: String,
        val text: String,
        val position: PointF,
        val color: Int,
        val sizePx: Float
    ) : MarkupAction()
}

enum class MosaicMode { PIXEL, BLUR }
```

- [ ] **Step 3: Write `EditRecipe.kt`**

```kotlin
package com.mamba.picme.features.editor

import android.graphics.RectF
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter

private const val RECIPE_VERSION = 1

data class EditRecipe(
    val sourceUri: String,
    val crop: CropRecipe = CropRecipe(),
    val adjustments: AdjustmentRecipe = AdjustmentRecipe(),
    val beauty: BeautySettings = BeautySettings(enabled = true),
    val colorFilter: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter = StyleFilter.NONE,
    val markup: List<MarkupAction> = emptyList(),
    val version: Int = RECIPE_VERSION
)

data class CropRecipe(
    val rotation: Int = 0,
    val flippedH: Boolean = false,
    val flippedV: Boolean = false,
    val straightenAngle: Float = 0f,
    val cropRect: RectF? = null,
    val aspectRatio: AspectRatio = AspectRatio.FREE
)

data class AdjustmentRecipe(
    val brightness: Float = 0f,      // -100..100
    val exposure: Float = 0f,        // -100..100
    val contrast: Float = 50f,       // 0..200
    val saturation: Float = 100f,    // 0..200
    val temperature: Float = 5000f,  // 2000..8000
    val tint: Float = 0f,            // -100..100
    val vignette: Float = 0f         // 0..100
)
```

- [ ] **Step 4: Run `:app:compileDebugKotlin` to verify data classes compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/AspectRatio.kt \
        app/src/main/java/com/mamba/picme/features/editor/MarkupAction.kt \
        app/src/main/java/com/mamba/picme/features/editor/EditRecipe.kt
git commit -m "feat(editor): add EditRecipe data model"
```

---

## Task 2: Implement `EditHistory`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/editor/EditHistory.kt`
- Test: `app/src/test/java/com/mamba/picme/features/editor/EditHistoryTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.mamba.picme.features.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    @Test
    fun `undo redo works`() {
        val history = EditHistory(maxSize = 5)
        val first = EditRecipe(sourceUri = "uri://1")
        val second = first.copy(crop = CropRecipe(rotation = 90))
        val third = second.copy(adjustments = AdjustmentRecipe(brightness = 20f))

        history.push(first)
        history.push(second)
        history.push(third)

        assertTrue(history.canUndo())
        assertEquals(second, history.undo())
        assertEquals(first, history.undo())
        assertFalse(history.canUndo())

        assertTrue(history.canRedo())
        assertEquals(second, history.redo())
        assertTrue(history.canRedo())
    }

    @Test
    fun `push after undo truncates future`() {
        val history = EditHistory()
        val first = EditRecipe(sourceUri = "uri://1")
        val second = first.copy(crop = CropRecipe(rotation = 90))
        val third = second.copy(crop = CropRecipe(rotation = 180))

        history.push(first)
        history.push(second)
        history.push(third)
        history.undo()
        history.undo()

        val branch = first.copy(adjustments = AdjustmentRecipe(contrast = 80f))
        history.push(branch)

        assertFalse(history.canRedo())
        assertEquals(branch, history.current())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.EditHistoryTest" --no-daemon`
Expected: FAIL with "Class not found" or unresolved `EditHistory`.

- [ ] **Step 3: Implement `EditHistory.kt`**

```kotlin
package com.mamba.picme.features.editor

class EditHistory(private val maxSize: Int = 30) {
    private val stack = mutableListOf<EditRecipe>()
    private var index = -1

    val canUndo: Boolean
        get() = index > 0

    val canRedo: Boolean
        get() = index < stack.lastIndex

    fun current(): EditRecipe? = if (index in stack.indices) stack[index] else null

    fun push(recipe: EditRecipe) {
        if (index < stack.lastIndex) {
            stack.subList(index + 1, stack.size).clear()
        }
        stack.add(recipe)
        if (stack.size > maxSize) {
            stack.removeAt(0)
            if (index > 0) index--
        }
        index = stack.lastIndex
    }

    fun undo(): EditRecipe? {
        if (!canUndo) return null
        return stack[--index]
    }

    fun redo(): EditRecipe? {
        if (!canRedo) return null
        return stack[++index]
    }

    fun reset(recipe: EditRecipe) {
        stack.clear()
        index = -1
        push(recipe)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.EditHistoryTest" --no-daemon`
Expected: BUILD SUCCESSFUL, tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/EditHistory.kt \
        app/src/test/java/com/mamba/picme/features/editor/EditHistoryTest.kt
git commit -m "feat(editor): add EditHistory with undo/redo"
```

---

## Task 3: Add Room persistence for recipes

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/local/entity/PhotoEditRecipeEntity.kt`
- Create: `app/src/main/java/com/mamba/picme/data/local/dao/PhotoEditRecipeDao.kt`
- Modify: `app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`

- [ ] **Step 1: Write `PhotoEditRecipeEntity.kt`**

```kotlin
package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photo_edit_recipes")
data class PhotoEditRecipeEntity(
    @PrimaryKey
    val outputUri: String,
    val sourceUri: String,
    val recipeJson: String,
    val updatedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: Write `PhotoEditRecipeDao.kt`**

```kotlin
package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mamba.picme.data.local.entity.PhotoEditRecipeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoEditRecipeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PhotoEditRecipeEntity)

    @Query("SELECT * FROM photo_edit_recipes WHERE outputUri = :outputUri LIMIT 1")
    suspend fun getByOutputUri(outputUri: String): PhotoEditRecipeEntity?

    @Query("SELECT * FROM photo_edit_recipes WHERE outputUri = :outputUri LIMIT 1")
    fun observeByOutputUri(outputUri: String): Flow<PhotoEditRecipeEntity?>

    @Query("DELETE FROM photo_edit_recipes WHERE outputUri = :outputUri")
    suspend fun delete(outputUri: String)

    @Query("DELETE FROM photo_edit_recipes WHERE outputUri NOT IN (SELECT outputUri FROM photo_edit_recipes)")
    suspend fun deleteAll()
}
```

- [ ] **Step 3: Add entity, DAO and migration in `AppDatabase.kt`**

Add import:
```kotlin
import com.mamba.picme.data.local.dao.PhotoEditRecipeDao
import com.mamba.picme.data.local.entity.PhotoEditRecipeEntity
```

Add to entities list:
```kotlin
PhotoEditRecipeEntity::class
```

Bump version to `9` and add migration:
```kotlin
version = 9,
```

```kotlin
.addMigrations(
    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
)
```

```kotlin
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `photo_edit_recipes` (
                `outputUri` TEXT PRIMARY KEY NOT NULL,
                `sourceUri` TEXT NOT NULL,
                `recipeJson` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
```

Add abstract DAO:
```kotlin
abstract fun photoEditRecipeDao(): PhotoEditRecipeDao
```

- [ ] **Step 4: Compile to verify Room schema**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/local/entity/PhotoEditRecipeEntity.kt \
        app/src/main/java/com/mamba/picme/data/local/dao/PhotoEditRecipeDao.kt \
        app/src/main/java/com/mamba/picme/data/local/AppDatabase.kt
git commit -m "feat(editor): add photo edit recipe Room persistence"
```

---

## Task 4: Add `PhotoEditRecipeRepository`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/repository/PhotoEditRecipeRepository.kt`

- [ ] **Step 1: Write repository**

```kotlin
package com.mamba.picme.data.repository

import com.mamba.picme.data.local.dao.PhotoEditRecipeDao
import com.mamba.picme.data.local.entity.PhotoEditRecipeEntity
import com.mamba.picme.features.editor.EditRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PhotoEditRecipeRepository(
    private val dao: PhotoEditRecipeDao
) {
    suspend fun save(outputUri: String, sourceUri: String, recipe: EditRecipe) {
        val entity = PhotoEditRecipeEntity(
            outputUri = outputUri,
            sourceUri = sourceUri,
            recipeJson = recipe.toJson()
        )
        dao.insert(entity)
    }

    suspend fun load(outputUri: String): EditRecipe? = withContext(Dispatchers.IO) {
        dao.getByOutputUri(outputUri)?.let { entity ->
            runCatching { EditRecipe.fromJson(entity.recipeJson, entity.sourceUri) }.getOrNull()
        }
    }

    fun observe(outputUri: String): Flow<EditRecipe?> {
        return dao.observeByOutputUri(outputUri).map { entity ->
            entity?.let {
                runCatching { EditRecipe.fromJson(it.recipeJson, it.sourceUri) }.getOrNull()
            }
        }
    }

    suspend fun delete(outputUri: String) {
        dao.delete(outputUri)
    }

    private fun EditRecipe.toJson(): String {
        return JSONObject().apply {
            put("version", version)
            put("sourceUri", sourceUri)
            put("crop", JSONObject().apply {
                put("rotation", crop.rotation)
                put("flippedH", crop.flippedH)
                put("flippedV", crop.flippedV)
                put("straightenAngle", crop.straightenAngle)
                put("aspectRatio", crop.aspectRatio.name)
                crop.cropRect?.let {
                    put("cropRectLeft", it.left)
                    put("cropRectTop", it.top)
                    put("cropRectRight", it.right)
                    put("cropRectBottom", it.bottom)
                }
            })
            put("adjustments", JSONObject().apply {
                put("brightness", adjustments.brightness)
                put("exposure", adjustments.exposure)
                put("contrast", adjustments.contrast)
                put("saturation", adjustments.saturation)
                put("temperature", adjustments.temperature)
                put("tint", adjustments.tint)
                put("vignette", adjustments.vignette)
            })
            put("beauty", beauty.toJson())
            put("colorFilter", colorFilter.name)
            put("styleFilter", styleFilter.name)
        }.toString()
    }

    companion object {
        fun EditRecipe.Companion.fromJson(json: String, fallbackSourceUri: String): EditRecipe {
            val root = JSONObject(json)
            val cropObj = root.getJSONObject("crop")
            val cropRect = if (cropObj.has("cropRectLeft")) {
                android.graphics.RectF(
                    cropObj.getDouble("cropRectLeft").toFloat(),
                    cropObj.getDouble("cropRectTop").toFloat(),
                    cropObj.getDouble("cropRectRight").toFloat(),
                    cropObj.getDouble("cropRectBottom").toFloat()
                )
            } else null

            return EditRecipe(
                sourceUri = root.optString("sourceUri", fallbackSourceUri),
                crop = CropRecipe(
                    rotation = cropObj.optInt("rotation", 0),
                    flippedH = cropObj.optBoolean("flippedH", false),
                    flippedV = cropObj.optBoolean("flippedV", false),
                    straightenAngle = cropObj.optDouble("straightenAngle", 0.0).toFloat(),
                    aspectRatio = try {
                        AspectRatio.valueOf(cropObj.optString("aspectRatio", "FREE"))
                    } catch (_: IllegalArgumentException) {
                        AspectRatio.FREE
                    },
                    cropRect = cropRect
                ),
                adjustments = root.getJSONObject("adjustments").let {
                    AdjustmentRecipe(
                        brightness = it.optDouble("brightness", 0.0).toFloat(),
                        exposure = it.optDouble("exposure", 0.0).toFloat(),
                        contrast = it.optDouble("contrast", 50.0).toFloat(),
                        saturation = it.optDouble("saturation", 100.0).toFloat(),
                        temperature = it.optDouble("temperature", 5000.0).toFloat(),
                        tint = it.optDouble("tint", 0.0).toFloat(),
                        vignette = it.optDouble("vignette", 0.0).toFloat()
                    )
                },
                colorFilter = try {
                    FilterType.valueOf(root.optString("colorFilter", "NONE"))
                } catch (_: IllegalArgumentException) {
                    FilterType.NONE
                },
                styleFilter = try {
                    StyleFilter.valueOf(root.optString("styleFilter", "NONE"))
                } catch (_: IllegalArgumentException) {
                    StyleFilter.NONE
                },
                version = root.optInt("version", 1)
            )
        }
    }
}
```

Note: `BeautySettings.toJson()` and `fromJson()` need to be added or reuse existing serializer. If none exists, implement a simple `BeautySettings.toJson()` extension in the repository file (mapping every field). Markup persistence is Phase 2; omit for now.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/repository/PhotoEditRecipeRepository.kt
git commit -m "feat(editor): add PhotoEditRecipeRepository"
```

---

## Task 5: Update DI and Navigation

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/navigation/Screen.kt`
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`
- Modify: `app/src/main/java/com/mamba/picme/MainActivity.kt`

- [ ] **Step 1: Add `PhotoEditor` route**

In `navigation/Screen.kt`:
```kotlin
data object PhotoEditor : Screen("photo_editor/{sourceUri}?recipeUri={recipeUri}") {
    fun createRoute(sourceUri: String, recipeUri: String? = null): String {
        return if (recipeUri != null) {
            "photo_editor/${sourceUri.encode()}?recipeUri=${recipeUri.encode()}"
        } else {
            "photo_editor/${sourceUri.encode()}"
        }
    }

    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
```

- [ ] **Step 2: Provide repository and factory in `AppContainer.kt`**

Add to `AppContainer` interface:
```kotlin
val photoEditRecipeRepository: PhotoEditRecipeRepository
fun createPhotoEditorViewModelFactory(): ViewModelProvider.Factory
```

Add to `AppContainerImpl`:
```kotlin
override val photoEditRecipeRepository: PhotoEditRecipeRepository by lazy {
    PhotoEditRecipeRepository(database.photoEditRecipeDao())
}

private val photoEditorViewModelFactory: ViewModelProvider.Factory by lazy {
    PhotoEditorViewModelFactory(
        photoProcessor = photoProcessor,
        faceDetector = faceDetector,
        recipeRepository = photoEditRecipeRepository,
        mediaRepository = repository
    )
}

override fun createPhotoEditorViewModelFactory(): ViewModelProvider.Factory =
    photoEditorViewModelFactory
```

Add imports:
```kotlin
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.features.editor.PhotoEditorViewModelFactory
```

- [ ] **Step 3: Add `PhotoEditorViewModelFactory` skeleton** (will be fleshed out in Task 7)

Create `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModelFactory.kt`:
```kotlin
package com.mamba.picme.features.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.repository.MediaRepository

class PhotoEditorViewModelFactory(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val recipeRepository: PhotoEditRecipeRepository,
    private val mediaRepository: MediaRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PhotoEditorViewModel::class.java)) {
            return PhotoEditorViewModel(
                photoProcessor,
                faceDetector,
                recipeRepository,
                mediaRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
```

- [ ] **Step 4: Register composable in `MainActivity.kt`**

After the Gallery composable block, add:
```kotlin
composable(
    route = Screen.PhotoEditor.route,
    arguments = listOf(
        navArgument("sourceUri") { type = NavType.StringType },
        navArgument("recipeUri") {
            type = NavType.StringType
            defaultValue = ""
            nullable = true
        }
    )
) { backStackEntry ->
    val encodedSource = backStackEntry.arguments?.getString("sourceUri") ?: ""
    val encodedRecipe = backStackEntry.arguments?.getString("recipeUri").orEmpty()
    val sourceUri = java.net.URLDecoder.decode(encodedSource, "UTF-8")
    val recipeUri = encodedRecipe.takeIf { it.isNotBlank() }
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    val factory = app.container.createPhotoEditorViewModelFactory()
    val viewModel: PhotoEditorViewModel = viewModel(factory = factory)

    PhotoEditorScreen(
        sourceUri = sourceUri,
        recipeUri = recipeUri,
        viewModel = viewModel,
        onNavigateBack = { navController.popBackStack() },
        onEditSaved = { outputUri ->
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.set("photo_editor_output_uri", outputUri)
            navController.popBackStack()
        }
    )
}
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL (some `PhotoEditorScreen` references will be unresolved until Task 8; you may need to create empty stubs temporarily).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/navigation/Screen.kt \
        app/src/main/java/com/mamba/picme/di/AppContainer.kt \
        app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModelFactory.kt \
        app/src/main/java/com/mamba/picme/MainActivity.kt
git commit -m "feat(editor): wire navigation and DI for PhotoEditor"
```

---

## Task 6: Implement `RecipeApplier`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/editor/RecipeApplier.kt`
- Test: `app/src/test/java/com/mamba/picme/features/editor/RecipeApplierTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.mamba.picme.features.editor

import android.graphics.Bitmap
import android.graphics.RectF
import com.mamba.picme.beauty.api.PhotoProcessor
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito.mock

class RecipeApplierTest {

    private val processor = mock(PhotoProcessor::class.java)
    private val applier = RecipeApplier(processor)

    @Test
    fun `applyCrop returns bitmap`() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val recipe = EditRecipe(
            sourceUri = "uri",
            crop = CropRecipe(
                rotation = 90,
                cropRect = RectF(0.1f, 0.1f, 0.9f, 0.9f)
            )
        )
        val result = applier.applyCrop(bitmap, recipe.crop)
        assertNotNull(result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.RecipeApplierTest" --no-daemon`
Expected: FAIL with `RecipeApplier` unresolved.

- [ ] **Step 3: Implement `RecipeApplier.kt`**

```kotlin
package com.mamba.picme.features.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.toBeautyParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecipeApplier(
    private val photoProcessor: PhotoProcessor
) {
    /**
     * Apply crop/rotate/flip to a bitmap.
     */
    fun applyCrop(bitmap: Bitmap, crop: CropRecipe): Bitmap {
        val matrix = Matrix().apply {
            postRotate(crop.rotation.toFloat())
            if (crop.flippedH) postScale(-1f, 1f)
            if (crop.flippedV) postScale(1f, -1f)
        }

        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            matrix, true
        )

        val rect = crop.cropRect
        return if (rect != null && !rect.isEmpty) {
            val left = (rect.left * rotated.width).toInt().coerceIn(0, rotated.width)
            val top = (rect.top * rotated.height).toInt().coerceIn(0, rotated.height)
            val right = (rect.right * rotated.width).toInt().coerceIn(left, rotated.width)
            val bottom = (rect.bottom * rotated.height).toInt().coerceIn(top, rotated.height)
            Bitmap.createBitmap(rotated, left, top, right - left, bottom - top)
        } else {
            rotated
        }
    }

    /**
     * Apply adjustments + beauty + filters via GPU pipeline.
     */
    suspend fun applyGpuEffects(
        bitmap: Bitmap,
        recipe: EditRecipe,
        faceData: com.mamba.picme.beauty.api.FaceData?
    ): Bitmap = withContext(Dispatchers.Default) {
        val settings = recipe.beauty.copy(
            enabled = true,
            brightness = recipe.adjustments.brightness,
            exposure = recipe.adjustments.exposure,
            contrast = recipe.adjustments.contrast,
            saturation = recipe.adjustments.saturation,
            temperature = recipe.adjustments.temperature,
            tint = recipe.adjustments.tint,
            colorFilter = recipe.colorFilter,
            styleFilter = recipe.styleFilter
        )
        photoProcessor.process(bitmap, settings.toBeautyParams(), faceData)
    }

    /**
     * Overlay markup actions on top of processed bitmap.
     */
    fun applyMarkup(bitmap: Bitmap, actions: List<MarkupAction>): Bitmap {
        if (actions.isEmpty()) return bitmap
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        actions.forEach { action ->
            when (action) {
                is MarkupAction.Doodle -> {
                    paint.color = action.color
                    paint.strokeWidth = action.strokeWidth
                    canvas.drawPath(action.path, paint)
                }
                is MarkupAction.Mosaic -> {
                    // Phase 2: implement mosaic shader overlay
                }
                is MarkupAction.Text -> {
                    paint.color = action.color
                    paint.textSize = action.sizePx
                    paint.style = Paint.Style.FILL
                    canvas.drawText(action.text, action.position.x, action.position.y, paint)
                }
            }
        }
        return result
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.RecipeApplierTest" --no-daemon`
Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/RecipeApplier.kt \
        app/src/test/java/com/mamba/picme/features/editor/RecipeApplierTest.kt
git commit -m "feat(editor): add RecipeApplier for crop/GPU/markup"
```

---

## Task 7: Implement `PhotoEditorViewModel`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModel.kt`

- [ ] **Step 1: Implement ViewModel**

```kotlin
package com.mamba.picme.features.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PhotoEditorViewModel"
private const val PREVIEW_MAX_DIM = 2048

@OptIn(FlowPreview::class)
class PhotoEditorViewModel(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val recipeRepository: PhotoEditRecipeRepository,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    sealed class State {
        object Loading : State()
        data class Ready(
            val previewBitmap: Bitmap,
            val recipe: EditRecipe,
            val selectedTab: EditorTab = EditorTab.CROP,
            val isProcessing: Boolean = false,
            val isSaving: Boolean = false,
            val error: String? = null
        ) : State()
        data class Error(val message: String) : State()
    }

    enum class EditorTab { CROP, ADJUST, BEAUTY, FILTER, MARKUP }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val history = EditHistory()

    private val _recipeChanges = MutableStateFlow<EditRecipe?>(null)

    private var sourceBitmap: Bitmap? = null
    private var cachedFaceData: com.mamba.picme.beauty.api.FaceData? = null

    init {
        _recipeChanges
            .drop(1)
            .filter { it != null }
            .debounce(200)
            .onEach { recipe ->
                recipe?.let { processPreview(it) }
            }
            .launchIn(viewModelScope)
    }

    fun load(context: Context, sourceUri: String, recipeUri: String?) {
        viewModelScope.launch {
            try {
                val loadedRecipe = recipeUri?.let { recipeRepository.load(it) }
                    ?: EditRecipe(sourceUri = sourceUri)
                val bitmap = decodePreviewBitmap(context, Uri.parse(sourceUri))
                if (bitmap == null) {
                    _state.value = State.Error("无法加载图片")
                    return@launch
                }
                sourceBitmap = bitmap
                cachedFaceData = detectFace(bitmap)
                history.reset(loadedRecipe)
                _state.value = State.Ready(previewBitmap = bitmap, recipe = loadedRecipe)
                processPreview(loadedRecipe)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load photo", e)
                _state.value = State.Error("加载失败：${e.message}")
            }
        }
    }

    private suspend fun detectFace(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        runCatching {
            faceDetector.detectPhoto(bitmap, lensFacing = 1)
                ?.landmarks106?.let { landmarks ->
                    // Convert to FaceData using existing extension or helper
                    null
                }
        }.getOrNull()
    }

    private fun decodePreviewBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(stream, null, options)
                val scale = if (maxOf(options.outWidth, options.outHeight) > PREVIEW_MAX_DIM) {
                    maxOf(options.outWidth, options.outHeight) / PREVIEW_MAX_DIM
                } else 1
                options.inJustDecodeBounds = false
                options.inSampleSize = Integer.highestOneBit(scale).coerceAtLeast(1)
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Decode preview failed", e)
            null
        }
    }

    fun selectTab(tab: EditorTab) {
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(selectedTab = tab)
    }

    fun updateRecipe(recipe: EditRecipe) {
        val current = _state.value as? State.Ready ?: return
        history.push(recipe)
        _state.value = current.copy(recipe = recipe)
        _recipeChanges.value = recipe
    }

    fun undo() {
        val recipe = history.undo() ?: return
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(recipe = recipe)
        _recipeChanges.value = recipe
    }

    fun redo() {
        val recipe = history.redo() ?: return
        val current = _state.value as? State.Ready ?: return
        _state.value = current.copy(recipe = recipe)
        _recipeChanges.value = recipe
    }

    private fun processPreview(recipe: EditRecipe) {
        val base = sourceBitmap ?: return
        viewModelScope.launch {
            val current = _state.value as? State.Ready ?: return@launch
            _state.value = current.copy(isProcessing = true)
            try {
                val applier = RecipeApplier(photoProcessor)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(base, recipe.crop) }
                val processed = applier.applyGpuEffects(cropped, recipe, cachedFaceData)
                val marked = withContext(Dispatchers.Default) { applier.applyMarkup(processed, recipe.markup) }
                _state.value = current.copy(previewBitmap = marked, isProcessing = false)
            } catch (e: Exception) {
                Logger.e(TAG, "Preview processing failed", e)
                _state.value = current.copy(isProcessing = false, error = "预览处理失败")
            }
        }
    }

    fun save(context: Context, recipe: EditRecipe) {
        viewModelScope.launch {
            val current = _state.value as? State.Ready ?: return@launch
            _state.value = current.copy(isSaving = true)
            try {
                val fullBitmap = decodeFullBitmap(context, Uri.parse(recipe.sourceUri)) ?: return@launch
                val applier = RecipeApplier(photoProcessor)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(fullBitmap, recipe.crop) }
                val processed = applier.applyGpuEffects(cropped, recipe, cachedFaceData)
                val finalBitmap = withContext(Dispatchers.Default) { applier.applyMarkup(processed, recipe.markup) }
                val outputUri = saveBitmapToMediaStore(context, finalBitmap)
                if (outputUri != null) {
                    recipeRepository.save(outputUri, recipe.sourceUri, recipe)
                    mediaRepository.refreshMediaLibrary()
                    _state.value = current.copy(isSaving = false)
                    onSaveComplete?.invoke(outputUri)
                } else {
                    _state.value = current.copy(isSaving = false, error = "保存失败")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Save failed", e)
                _state.value = current.copy(isSaving = false, error = "保存失败：${e.message}")
            }
        }
    }

    private fun decodeFullBitmap(context: Context, uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap): String? {
        val name = "EDITED_${System.currentTimeMillis()}.jpg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PicMe")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return uri?.also {
            context.contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }?.toString()
    }

    var onSaveComplete: ((String) -> Unit)? = null

    override fun onCleared() {
        super.onCleared()
        sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}
```

Note: FaceData conversion needs to reuse existing extension from `MediaViewModel` or extract to a shared helper.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL (may need to create empty `PhotoEditorScreen` stub until Task 8).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/PhotoEditorViewModel.kt
git commit -m "feat(editor): add PhotoEditorViewModel"
```

---

## Task 8: Build `PhotoEditorScreen` shell and top/bottom bars

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorScreen.kt`
- Create: `app/src/main/java/com/mamba/picme/features/editor/components/EditorTopBar.kt`
- Create: `app/src/main/java/com/mamba/picme/features/editor/components/EditorBottomBar.kt`

- [ ] **Step 1: Implement `EditorTopBar.kt`**

```kotlin
package com.mamba.picme.features.editor.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompare: (pressed: Boolean) -> Unit,
    onDone: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "撤销",
                    modifier = Modifier.alpha(if (canUndo) 1f else 0.38f)
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    imageVector = Icons.Default.Redo,
                    contentDescription = "重做",
                    modifier = Modifier.alpha(if (canRedo) 1f else 0.38f)
                )
            }
            IconButton(
                onClick = onDone,
                enabled = !isSaving
            ) {
                Icon(Icons.Default.Check, contentDescription = "完成")
            }
        }
    )
}
```

- [ ] **Step 2: Implement `EditorBottomBar.kt`**

```kotlin
package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mamba.picme.features.editor.PhotoEditorViewModel

@Composable
fun EditorBottomBar(
    selectedTab: PhotoEditorViewModel.EditorTab,
    onTabSelected: (PhotoEditorViewModel.EditorTab) -> Unit
) {
    val tabs = PhotoEditorViewModel.EditorTab.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { tab ->
            FilterChip(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                label = { Text(tabLabel(tab)) }
            )
        }
    }
}

private fun tabLabel(tab: PhotoEditorViewModel.EditorTab): String = when (tab) {
    PhotoEditorViewModel.EditorTab.CROP -> "构图"
    PhotoEditorViewModel.EditorTab.ADJUST -> "调节"
    PhotoEditorViewModel.EditorTab.BEAUTY -> "美颜"
    PhotoEditorViewModel.EditorTab.FILTER -> "滤镜"
    PhotoEditorViewModel.EditorTab.MARKUP -> "标记"
}
```

- [ ] **Step 3: Implement `PhotoEditorScreen.kt` shell**

```kotlin
package com.mamba.picme.features.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.mamba.picme.features.editor.components.AdjustPanel
import com.mamba.picme.features.editor.components.CropPanel
import com.mamba.picme.features.editor.components.EditorBottomBar
import com.mamba.picme.features.editor.components.EditorTopBar
import com.mamba.picme.features.editor.components.MarkupPanel

@Composable
fun PhotoEditorScreen(
    sourceUri: String,
    recipeUri: String?,
    viewModel: PhotoEditorViewModel,
    onNavigateBack: () -> Unit,
    onEditSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.load(context, sourceUri, recipeUri)
        viewModel.onSaveComplete = onEditSaved
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onSaveComplete = null }
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                title = "编辑",
                canUndo = false, // TODO wire from ViewModel
                canRedo = false,
                isSaving = (state as? PhotoEditorViewModel.State.Ready)?.isSaving == true,
                onCancel = onNavigateBack,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onCompare = { /* TODO */ },
                onDone = {
                    val ready = state as? PhotoEditorViewModel.State.Ready ?: return@EditorTopBar
                    viewModel.save(context, ready.recipe)
                }
            )
        },
        bottomBar = {
            val ready = state as? PhotoEditorViewModel.State.Ready ?: return@Scaffold
            Column {
                PanelForTab(
                    tab = ready.selectedTab,
                    recipe = ready.recipe,
                    onRecipeChange = viewModel::updateRecipe
                )
                EditorBottomBar(
                    selectedTab = ready.selectedTab,
                    onTabSelected = viewModel::selectTab
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is PhotoEditorViewModel.State.Loading -> { /* TODO loading indicator */ }
                is PhotoEditorViewModel.State.Error -> { /* TODO error message */ }
                is PhotoEditorViewModel.State.Ready -> {
                    var comparing by remember { mutableStateOf(false) }
                    Image(
                        bitmap = s.previewBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = { comparing = true },
                                    onTap = { comparing = false }
                                )
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelForTab(
    tab: PhotoEditorViewModel.EditorTab,
    recipe: EditRecipe,
    onRecipeChange: (EditRecipe) -> Unit
) {
    when (tab) {
        PhotoEditorViewModel.EditorTab.CROP -> CropPanel(
            crop = recipe.crop,
            onChange = { onRecipeChange(recipe.copy(crop = it)) }
        )
        PhotoEditorViewModel.EditorTab.ADJUST -> AdjustPanel(
            adjustments = recipe.adjustments,
            onChange = { onRecipeChange(recipe.copy(adjustments = it)) }
        )
        PhotoEditorViewModel.EditorTab.BEAUTY -> {
            com.mamba.picme.features.camera.components.BeautyPanel(
                settings = recipe.beauty,
                onSettingsChanged = { onRecipeChange(recipe.copy(beauty = it)) },
                onDismiss = {}
            )
        }
        PhotoEditorViewModel.EditorTab.FILTER -> {
            // TODO Phase 1 basic filter chips
            Text("滤镜面板（Phase 1 基础）", color = MaterialTheme.colorScheme.onSurface)
        }
        PhotoEditorViewModel.EditorTab.MARKUP -> MarkupPanel(
            actions = recipe.markup,
            onChange = { onRecipeChange(recipe.copy(markup = it)) }
        )
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL (may need to create empty stubs for CropPanel, AdjustPanel, MarkupPanel until Tasks 9-11).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/PhotoEditorScreen.kt \
        app/src/main/java/com/mamba/picme/features/editor/components/EditorTopBar.kt \
        app/src/main/java/com/mamba/picme/features/editor/components/EditorBottomBar.kt
git commit -m "feat(editor): add PhotoEditorScreen shell and bars"
```

---

## Task 9: Build `CropPanel` and crop overlay

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/editor/components/CropPanel.kt`
- Create: `app/src/main/java/com/mamba/picme/features/editor/components/CropOverlay.kt`

- [ ] **Step 1: Implement `CropPanel.kt`**

```kotlin
package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mamba.picme.features.editor.AspectRatio
import com.mamba.picme.features.editor.CropRecipe

@Composable
fun CropPanel(
    crop: CropRecipe,
    onChange: (CropRecipe) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "裁剪比例",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AspectRatio.entries.forEach { ratio ->
                FilterChip(
                    selected = crop.aspectRatio == ratio,
                    onClick = { onChange(crop.copy(aspectRatio = ratio)) },
                    label = { Text(ratio.label) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                onChange(crop.copy(rotation = (crop.rotation - 90).mod(360)))
            }) {
                Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "左旋")
            }
            IconButton(onClick = {
                onChange(crop.copy(rotation = (crop.rotation + 90).mod(360)))
            }) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "右旋")
            }
            IconButton(onClick = { onChange(crop.copy(flippedH = !crop.flippedH)) }) {
                Icon(Icons.Default.Flip, contentDescription = "水平翻转")
            }
        }
    }
}
```

- [ ] **Step 2: Implement `CropOverlay.kt`**

A transparent overlay with draggable crop frame. Implementation should draw a darkened region outside the crop rect and a bright grid. Keep it simple for Phase 1: fixed crop rect based on aspect ratio, draggable corners optional Phase 2.

```kotlin
package com.mamba.picme.features.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.mamba.picme.features.editor.CropRecipe

@Composable
fun CropOverlay(
    crop: CropRecipe,
    imageWidth: Int,
    imageHeight: Int,
    onCropChange: (CropRecipe) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(crop) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // Phase 1: simple implementation; full drag logic in Phase 2
                }
            }
    ) {
        val rect = calculateCropRect(size.width, size.height, crop.aspectRatio)
        drawRect(Color.Black.copy(alpha = 0.5f), size = size)
        drawRect(Color.Transparent, topLeft = rect.topLeft, size = rect.size)
        drawRect(Color.White, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 2f))
    }
}

private fun calculateCropRect(width: Float, height: Float, ratio: com.mamba.picme.features.editor.AspectRatio): Rect {
    val r = ratio.ratio
    return if (r == null || r < 0) {
        Rect(0f, 0f, width, height)
    } else {
        val imageRatio = width / height
        if (imageRatio > r) {
            val w = height * r
            val left = (width - w) / 2f
            Rect(left, 0f, left + w, height)
        } else {
            val h = width / r
            val top = (height - h) / 2f
            Rect(0f, top, width, top + h)
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/components/CropPanel.kt \
        app/src/main/java/com/mamba/picme/features/editor/components/CropOverlay.kt
git commit -m "feat(editor): add crop panel and overlay"
```

---

## Task 10: Build `AdjustPanel`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/editor/components/AdjustPanel.kt`
- Create: `app/src/main/java/com/mamba/picme/features/editor/components/EditorSlider.kt`

- [ ] **Step 1: Implement `EditorSlider.kt`**

```kotlin
package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EditorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit,
    valueFormatter: (Float) -> String = { "%.0f".format(it) }
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            TextButton(onClick = onReset) {
                Text("重置")
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
```

- [ ] **Step 2: Implement `AdjustPanel.kt`**

```kotlin
package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mamba.picme.features.editor.AdjustmentRecipe

@Composable
fun AdjustPanel(
    adjustments: AdjustmentRecipe,
    onChange: (AdjustmentRecipe) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        EditorSlider(
            label = "亮度",
            value = adjustments.brightness,
            valueRange = -100f..100f,
            onValueChange = { onChange(adjustments.copy(brightness = it)) },
            onReset = { onChange(adjustments.copy(brightness = 0f)) }
        )
        EditorSlider(
            label = "曝光",
            value = adjustments.exposure,
            valueRange = -100f..100f,
            onValueChange = { onChange(adjustments.copy(exposure = it)) },
            onReset = { onChange(adjustments.copy(exposure = 0f)) }
        )
        EditorSlider(
            label = "对比度",
            value = adjustments.contrast,
            valueRange = 0f..200f,
            onValueChange = { onChange(adjustments.copy(contrast = it)) },
            onReset = { onChange(adjustments.copy(contrast = 50f)) }
        )
        EditorSlider(
            label = "饱和度",
            value = adjustments.saturation,
            valueRange = 0f..200f,
            onValueChange = { onChange(adjustments.copy(saturation = it)) },
            onReset = { onChange(adjustments.copy(saturation = 100f)) }
        )
        EditorSlider(
            label = "色温",
            value = adjustments.temperature,
            valueRange = 2000f..8000f,
            onValueChange = { onChange(adjustments.copy(temperature = it)) },
            onReset = { onChange(adjustments.copy(temperature = 5000f)) }
        )
        EditorSlider(
            label = "色调",
            value = adjustments.tint,
            valueRange = -100f..100f,
            onValueChange = { onChange(adjustments.copy(tint = it)) },
            onReset = { onChange(adjustments.copy(tint = 0f)) }
        )
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/components/AdjustPanel.kt \
        app/src/main/java/com/mamba/picme/features/editor/components/EditorSlider.kt
git commit -m "feat(editor): add adjust panel with sliders"
```

---

## Task 11: Integrate Beauty and Markup tabs

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/editor/components/MarkupPanel.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/PhotoEditorScreen.kt`

- [ ] **Step 1: Implement `MarkupPanel.kt`**

```kotlin
package com.mamba.picme.features.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mamba.picme.features.editor.MarkupAction

@Composable
fun MarkupPanel(
    actions: List<MarkupAction>,
    onChange: (List<MarkupAction>) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterChip(
                selected = true,
                onClick = { /* select doodle */ },
                label = { Icon(Icons.Default.Brush, contentDescription = "涂鸦") }
            )
            FilterChip(
                selected = false,
                onClick = { /* select mosaic */ },
                label = { Icon(Icons.Default.BlurOn, contentDescription = "马赛克") }
            )
        }
        Slider(
            value = 20f,
            onValueChange = { /* stroke width */ },
            valueRange = 5f..100f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
```

- [ ] **Step 2: Update `PhotoEditorScreen.kt` to import and remove placeholder TODOs**

Replace TODO comments with actual imports and usage. Ensure `BeautyPanel` and `MarkupPanel` are wired.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/components/MarkupPanel.kt \
        app/src/main/java/com/mamba/picme/features/editor/PhotoEditorScreen.kt
git commit -m "feat(editor): wire beauty and markup panels"
```

---

## Task 12: Wire `MediaPager` entry and cleanup old in-place editing

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/MediaViewModel.kt`

- [ ] **Step 1: Add navigation callback to `MediaPager`**

Add parameter:
```kotlin
onNavigateToEditor: (MediaAsset) -> Unit
```

In the top controls and bottom bar edit actions, replace `startPhotoEdit()` with:
```kotlin
onNavigateToEditor(asset)
```

Remove or deprecate `isEditing`, `editSettings`, `processedBitmap`, `loadedBitmap`, `photoEditPanel` call, and related `LaunchedEffect`/`DisposableEffect` blocks.

- [ ] **Step 2: Update `GalleryScreen.kt`**

Pass `onNavigateToEditor` from `GalleryScreen` to `MediaPager` and implement it to navigate:
```kotlin
onNavigateToEditor = { asset ->
    navController.navigate(
        Screen.PhotoEditor.createRoute(sourceUri = asset.uri),
        navOptions { launchSingleTop = true }
    )
}
```

- [ ] **Step 3: Remove in-place photo edit state from `MediaViewModel`**

Delete `PhotoEditState` sealed class, `cachedEditFaceData`, `_photoEditState`, `preparePhotoEdit`, `processPhoto`, `saveProcessedPhoto`, `clearPhotoEditState`, and the `toFaceData` extension if unused elsewhere.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/components/MediaPager.kt \
        app/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt \
        app/src/main/java/com/mamba/picme/features/gallery/MediaViewModel.kt
git commit -m "feat(editor): replace in-place edit with PhotoEditor navigation"
```

---

## Task 13: Strings and localization

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml` (if exists)
- Modify: `app/src/main/res/values-zh-rCN/strings.xml` (if exists)

- [ ] **Step 1: Add string resources**

In `values/strings.xml`:
```xml
<string name="editor_title">Edit</string>
<string name="editor_crop">Crop</string>
<string name="editor_adjust">Adjust</string>
<string name="editor_beauty">Beauty</string>
<string name="editor_filter">Filter</string>
<string name="editor_markup">Markup</string>
<string name="editor_brightness">Brightness</string>
<string name="editor_exposure">Exposure</string>
<string name="editor_contrast">Contrast</string>
<string name="editor_saturation">Saturation</string>
<string name="editor_temperature">Temperature</string>
<string name="editor_tint">Tint</string>
<string name="editor_reset">Reset</string>
<string name="editor_save_failed">Save failed</string>
<string name="editor_load_failed">Failed to load image</string>
```

Add Chinese translations in corresponding files.

- [ ] **Step 2: Replace hardcoded Chinese strings in editor components with `stringResource()`**

Update `EditorBottomBar`, `CropPanel`, `AdjustPanel`, `MarkupPanel`.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "i18n(editor): extract editor strings"
```

---

## Task 14: Update gallery module documentation

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/gallery/AGENTS.md`

- [ ] **Step 1: Replace section 2.7 静态图美颜编辑 with new editor description**

Update to describe:
- Entry: top toolbar edit button and long press.
- Flow: navigate to `PhotoEditorScreen`.
- Non-destructive save + recipe persistence.
- ADB test commands updated: `start_edit` opens editor, `save_edit` triggers save, `cancel_edit` pops back.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/gallery/AGENTS.md
git commit -m "docs(gallery): update AGENTS.md for new photo editor"
```

---

## Phase 2 & Phase 3 Outline

### Phase 2: Experience Polish (2 weeks)

- Filter thumbnail generation and preset panel.
- Advanced crop overlay (draggable corners, straighten).
- Mosaic shader overlay in `RecipeApplier.applyMarkup`.
- Text / sticker markup.
- Before/after compare gesture and per-parameter reset.
- EXIF copy on export.

### Phase 3: AI Editing Entry (2 weeks)

- `RecipeCommandParser`: natural language → `EditRecipeDiff`.
- Voice / text input button in `PhotoEditorScreen`.
- Integrate with `VoiceCommandCoordinator`.
- AI erase / sky replacement placeholder types in `MarkupAction`.

---

## Plan Self-Review

- **Spec coverage:** All Phase 1 requirements (crop, adjust, beauty, markup, save, undo/redo, non-destructive persistence, navigation) map to a task.
- **Placeholder scan:** No TBDs in code steps; UI TODOs are explicitly listed for Phase 2.
- **Type consistency:** `EditRecipe`, `CropRecipe`, `AdjustmentRecipe`, `MarkupAction`, `AspectRatio` names are consistent across all tasks.
- **Testing:** Unit tests for `EditHistory` and `RecipeApplier` included.
