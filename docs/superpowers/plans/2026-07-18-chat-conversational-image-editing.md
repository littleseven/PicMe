# Chat 对话式图片编辑实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Chat 页实现对话式图片编辑：用户发送图片后可通过自然语言调节美颜/滤镜/调色参数，结果 inline 返回聊天；智能消除/局部美颜本期不实现。

**Architecture:** 复用现有 `PhotoEditorViewModel` 的 Recipe → Bitmap 渲染链路；新增 `ChatEditRecipeBuilder` 把 LLM 结构化意图转成 `EditRecipe`；新增 `ChatEditProcessor` 负责渲染与保存；新增 `ImageEditCapability` 接入 `CapabilityRegistry`；`ChatEditStateHolder` 维护会话级当前 Recipe 支持多轮 delta。

**Tech Stack:** Kotlin, Android Compose, Room, OpenGL ES（大美丽引擎）, MNN/Qwen3.5-2B（本地）, DeepSeek/OpenAI API（远程）

---

## 文件结构

### 新增文件

| 文件 | 职责 |
|------|------|
| `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/EditParams.kt` | LLM 结构化编辑意图数据类（美颜/滤镜/调色 delta） |
| `app/src/main/java/com/mamba/picme/features/chat/ChatEditStateHolder.kt` | 会话级 `EditRecipe` 状态管理 |
| `app/src/main/java/com/mamba/picme/domain/model/ChatEditRecipeBuilder.kt` | 自然语言意图 → `EditRecipe` |
| `app/src/main/java/com/mamba/picme/domain/usecase/ChatEditProcessor.kt` | `EditRecipe` + 原图 → 结果图保存 |
| `app/src/main/java/com/mamba/picme/domain/agent/capability/ImageEditCapability.kt` | Capability 分发 `EditImage` 命令 |
| `app/src/test/java/com/mamba/picme/domain/model/ChatEditRecipeBuilderTest.kt` | Builder 单元测试 |
| `app/src/test/java/com/mamba/picme/domain/usecase/ChatEditProcessorTest.kt` | Processor 单元测试 |
| `app/src/test/java/com/mamba/picme/domain/agent/capability/ImageEditCapabilityTest.kt` | Capability 集成测试 |

### 修改文件

| 文件 | 修改点 |
|------|--------|
| `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt` | 新增 `EditImage` 命令；更新 `getMethodName` |
| `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt` | 新增 `edit_image` JSON 解析 |
| `app/src/main/java/com/mamba/picme/features/editor/EditRecipe.kt` | 新增 `filterIntensity: Float = 1.0f` |
| `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` | 新增 `AGENT_EDIT_RESULT` UI；注册 ImageEditCapability |
| `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` | 处理 `AgentAction` 编辑结果；保存/展示结果消息 |
| `app/src/main/java/com/mamba/picme/data/local/ChatMessageEntity.kt` | 无需 schema 变更，复用 `metadata` 字段存储说明和原图 URI |
| `app/src/main/res/values/strings.xml` 等 | 新增用户可见文案 |

---

### Task 1: 新增 EditParams 数据类

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/EditParams.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/model/command/EditParamsTest.kt`

`EditParams` 是 `:runtime-core` 能理解的中性数据结构，不依赖 `:app` 的 `EditRecipe`。

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.model.command

import org.junit.Assert.assertEquals
import org.junit.Test

class EditParamsTest {

    @Test
    fun `default EditParams has no changes`() {
        val params = EditParams()
        assertEquals(EditParams.Unchanged, params.smoothing)
        assertEquals(EditParams.Unchanged, params.filterName)
        assertEquals(null, params.filterIntensity)
    }

    @Test
    fun `absolute value is preserved`() {
        val params = EditParams(smoothing = EditParams.Absolute(35f))
        assertEquals(35f, (params.smoothing as EditParams.Absolute).value, 0.001f)
    }

    @Test
    fun `delta value is preserved`() {
        val params = EditParams(brightness = EditParams.Delta(15f))
        assertEquals(15f, (params.brightness as EditParams.Delta).value, 0.001f)
    }
}
```

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.command.EditParamsTest"`
Expected: FAIL — `EditParams` not found

- [ ] **Step 2: Write the implementation**

```kotlin
package com.mamba.picme.agent.core.model.command

/**
 * LLM 结构化编辑意图。
 *
 * 每个字段可以是：
 * - [Unchanged]：不修改
 * - [Absolute]：设置为绝对值
 * - [Delta]：在当前值基础上增减
 */
data class EditParams(
    val smoothing: Value = Unchanged,
    val whitening: Value = Unchanged,
    val slimFace: Value = Unchanged,
    val bigEyes: Value = Unchanged,
    val lipColor: Value = Unchanged,
    val blush: Value = Unchanged,
    val eyebrow: Value = Unchanged,
    val brightness: Value = Unchanged,
    val exposure: Value = Unchanged,
    val contrast: Value = Unchanged,
    val saturation: Value = Unchanged,
    val temperature: Value = Unchanged,
    val tint: Value = Unchanged,
    val filterName: Value = Unchanged,
    val filterIntensity: Float? = null,
    val styleName: Value = Unchanged
) {
    sealed interface Value
    data object Unchanged : Value
    data class Absolute(val value: Float) : Value
    data class Delta(val value: Float) : Value
}
```

- [ ] **Step 3: Run the test**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.command.EditParamsTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/EditParams.kt
-git add runtime-core/src/test/java/com/mamba/picme/agent/core/model/command/EditParamsTest.kt
git commit -m "feat(runtime-core): add EditParams for structured image edit intents"
```

---

### Task 2: 扩展 AgentCommand 添加 EditImage

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/model/command/AgentCommandEditImageTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.model.command

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentCommandEditImageTest {

    @Test
    fun `EditImage command has correct method name`() {
        val command = AgentCommand.EditImage(
            imageUri = "file:///test.jpg",
            params = EditParams(brightness = EditParams.Delta(15f)),
            explanation = "调亮一点"
        )
        assertEquals("edit_image", AgentCommand.getMethodName(command))
        assertEquals("file:///test.jpg", command.imageUri)
    }
}
```

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.command.AgentCommandEditImageTest"`
Expected: FAIL — `EditImage` unresolved

- [ ] **Step 2: Add EditImage to AgentCommand and update getMethodName**

在 `AgentCommands.kt` 中，找到 `// ==================== 编辑命令 ====================` 区域，在 `AiOptimize` 后添加：

```kotlin
    /**
     * 对话式图片编辑
     *
     * @property params 结构化编辑意图（美颜/滤镜/调色 delta）
     * @property imageUri 待编辑图片 URI；为空时使用会话最近一张用户图片
     * @property explanation 给用户的一句话说明
     */
    data class EditImage(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val params: EditParams,
        val imageUri: String = "",
        val explanation: String? = null
    ) : AgentCommand()
```

在 `getMethodName` 的 `is AiOptimize -> "ai_optimize"` 后添加：

```kotlin
            is EditImage -> "edit_image"
```

- [ ] **Step 3: Run the test**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.command.AgentCommandEditImageTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/AgentCommands.kt
git add runtime-core/src/test/java/com/mamba/picme/agent/core/model/command/AgentCommandEditImageTest.kt
git commit -m "feat(runtime-core): add EditImage AgentCommand"
```

---

### Task 3: 扩展 LocalCommandParser 解析 edit_image

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParserEditImageTest.kt`

先读 `LocalCommandParser.kt` 了解现有解析模式。

- [ ] **Step 1: Inspect existing parser structure**

Run: `Read runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt`

- [ ] **Step 2: Write the failing test**

```kotlin
package com.mamba.picme.agent.core.inference.local.parser

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.EditParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandParserEditImageTest {

    @Test
    fun `parse edit_image with absolute smoothing`() {
        val json = """
            {"method":"edit_image","args":{"image_uri":"file:///test.jpg","smoothing":35,"filter_name":"FILM_GOLD","explanation":"磨皮并换成胶片风"}}
        """.trimIndent()
        val command = LocalCommandParser.parseLlmResponse(json, agentContext = null)
        assertTrue(command is AgentCommand.EditImage)
        val edit = command as AgentCommand.EditImage
        assertEquals("file:///test.jpg", edit.imageUri)
        assertEquals(35f, (edit.params.smoothing as EditParams.Absolute).value, 0.001f)
        assertEquals("FILM_GOLD", (edit.params.filterName as EditParams.Absolute).value.toString())
        assertEquals("磨皮并换成胶片风", edit.explanation)
    }

    @Test
    fun `parse edit_image with delta brightness`() {
        val json = """
            {"method":"edit_image","args":{"brightness_delta":15}}
        """.trimIndent()
        val command = LocalCommandParser.parseLlmResponse(json, agentContext = null)
        assertTrue(command is AgentCommand.EditImage)
        val edit = command as AgentCommand.EditImage
        assertEquals(15f, (edit.params.brightness as EditParams.Delta).value, 0.001f)
    }
}
```

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.local.parser.LocalCommandParserEditImageTest"`
Expected: FAIL — parser returns Unknown or Error

- [ ] **Step 3: Add edit_image parsing logic**

在 `LocalCommandParser.kt` 的 `parseLlmResponse` 或对应分派位置，新增 `edit_image` 分支：

```kotlin
            "edit_image" -> {
                val args = json.optJSONObject("args") ?: JSONObject()
                AgentCommand.EditImage(
                    params = EditParams(
                        smoothing = parseEditParam(args, "smoothing"),
                        whitening = parseEditParam(args, "whitening"),
                        slimFace = parseEditParam(args, "slim_face"),
                        bigEyes = parseEditParam(args, "big_eyes"),
                        lipColor = parseEditParam(args, "lip_color"),
                        blush = parseEditParam(args, "blush"),
                        eyebrow = parseEditParam(args, "eyebrow"),
                        brightness = parseEditParam(args, "brightness"),
                        exposure = parseEditParam(args, "exposure"),
                        contrast = parseEditParam(args, "contrast"),
                        saturation = parseEditParam(args, "saturation"),
                        temperature = parseEditParam(args, "temperature"),
                        tint = parseEditParam(args, "tint"),
                        filterName = parseEditParam(args, "filter_name"),
                        filterIntensity = args.optDouble("filter_intensity").takeIf { !it.isNaN() }?.toFloat(),
                        styleName = parseEditParam(args, "style_name")
                    ),
                    imageUri = args.optString("image_uri", ""),
                    explanation = args.optString("explanation").takeIf { it.isNotBlank() }
                )
            }
```

并在同一文件新增辅助函数：

```kotlin
    private fun parseEditParam(args: JSONObject, key: String): EditParams.Value {
        if (!args.has(key)) return EditParams.Unchanged
        val value = args.opt(key) ?: return EditParams.Unchanged
        return when {
            value is Number -> EditParams.Absolute(value.toFloat())
            value is String && value.endsWith("_delta") -> {
                // delta 字段约定：brightness_delta 等
                val deltaKey = value
                val deltaValue = args.optDouble(deltaKey, 0.0)
                EditParams.Delta(deltaValue.toFloat())
            }
            else -> EditParams.Unchanged
        }
    }
```

**注意**：如果 parser 现有结构使用 `method` 到 lambda 的映射表而非 `when` 分支，采用相同风格添加 `edit_image` 映射。

- [ ] **Step 4: Run the test**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.inference.local.parser.LocalCommandParserEditImageTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt
git add runtime-core/src/test/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParserEditImageTest.kt
git commit -m "feat(runtime-core): parse edit_image commands in LocalCommandParser"
```

---

### Task 4: 扩展 EditRecipe 支持滤镜强度

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/editor/EditRecipe.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/editor/RecipeApplier.kt`（将强度应用到滤镜）
- Test: `app/src/test/java/com/mamba/picme/features/editor/EditRecipeFilterIntensityTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.features.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditRecipeFilterIntensityTest {

    @Test
    fun `default filter intensity is 1_0`() {
        val recipe = EditRecipe(sourceUri = "file:///test.jpg")
        assertEquals(1.0f, recipe.filterIntensity, 0.001f)
    }

    @Test
    fun `filter intensity can be customized`() {
        val recipe = EditRecipe(sourceUri = "file:///test.jpg", filterIntensity = 0.4f)
        assertEquals(0.4f, recipe.filterIntensity, 0.001f)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.EditRecipeFilterIntensityTest"`
Expected: FAIL — `filterIntensity` unresolved

- [ ] **Step 2: Add filterIntensity field to EditRecipe**

```kotlin
data class EditRecipe(
    val sourceUri: String,
    val crop: CropRecipe = CropRecipe(),
    val adjustments: AdjustmentRecipe = AdjustmentRecipe(),
    val beauty: BeautySettings = BeautySettings(enabled = true),
    val colorFilter: FilterType = FilterType.NONE,
    val styleFilter: StyleFilter = StyleFilter.NONE,
    val filterIntensity: Float = 1.0f,
    val markup: List<MarkupAction> = emptyList(),
    val version: Int = RECIPE_VERSION
)
```

- [ ] **Step 3: Apply filter intensity in RecipeApplier CPU fallback**

在 `applyCpuFilterFallback` 中，当 `colorFilter != NONE` 时，按 `filterIntensity` 混合滤镜矩阵与原图：

```kotlin
    private fun applyCpuFilterFallback(bitmap: Bitmap, recipe: EditRecipe): Bitmap {
        val hasFilter = recipe.colorFilter != FilterType.NONE || recipe.styleFilter != StyleFilter.NONE
        if (!hasFilter) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colorMatrix = ColorMatrix()
        if (recipe.colorFilter != FilterType.NONE) {
            colorMatrix.postConcat(recipe.colorFilter.toAndroidColorMatrix())
        }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

        val intensity = recipe.filterIntensity.coerceIn(0f, 1f)
        if (intensity >= 0.99f) {
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        } else {
            // 先画原图
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            // 再叠加滤镜，透明度为 intensity
            paint.alpha = (intensity * 255).toInt()
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }
        return output
    }
```

**注意**：GPU 路径的滤镜强度实现更复杂，需要修改 Shader。本期先保证 CPU fallback 路径正确；GPU 路径可在 `PhotoProcessor` 的 shader 中通过 uniforms 实现，若本期无法完成，则保持 `filterIntensity = 1.0` 作为默认行为。

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.editor.EditRecipeFilterIntensityTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/editor/EditRecipe.kt
git add app/src/main/java/com/mamba/picme/features/editor/RecipeApplier.kt
git add app/src/test/java/com/mamba/picme/features/editor/EditRecipeFilterIntensityTest.kt
git commit -m "feat(editor): add filterIntensity to EditRecipe and CPU fallback"
```

---

### Task 5: 创建 ChatEditStateHolder

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/ChatEditStateHolder.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatEditStateHolderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.features.editor.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatEditStateHolderTest {

    @Test
    fun `returns default recipe for unknown session`() {
        val holder = ChatEditStateHolder()
        val recipe = holder.get("session-a")
        assertEquals(EditRecipe(sourceUri = ""), recipe)
    }

    @Test
    fun `updates and retrieves recipe per session`() {
        val holder = ChatEditStateHolder()
        val recipeA = EditRecipe(sourceUri = "uri-a", brightness = 10f)
        holder.update("session-a", recipeA)
        assertEquals(10f, holder.get("session-a").brightness, 0.001f)

        val recipeB = EditRecipe(sourceUri = "uri-b", brightness = 20f)
        holder.update("session-b", recipeB)
        assertEquals(20f, holder.get("session-b").brightness, 0.001f)
        assertEquals(10f, holder.get("session-a").brightness, 0.001f)
    }

    @Test
    fun `reset clears session recipe`() {
        val holder = ChatEditStateHolder()
        holder.update("session-a", EditRecipe(sourceUri = "uri-a", brightness = 10f))
        holder.reset("session-a")
        assertEquals(0f, holder.get("session-a").brightness, 0.001f)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatEditStateHolderTest"`
Expected: FAIL — class not found

- [ ] **Step 2: Implement ChatEditStateHolder**

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.features.editor.EditRecipe

/**
 * 维护每个 Chat 会话当前的编辑 Recipe，用于多轮 delta 调整。
 *
 * 生命周期：应用进程内有效；切换会话、清空对话或发送新图片时重置。
 */
class ChatEditStateHolder {

    private val states = mutableMapOf<String, EditRecipe>()

    fun get(sessionId: String): EditRecipe {
        return states[sessionId] ?: EditRecipe(sourceUri = "")
    }

    fun update(sessionId: String, recipe: EditRecipe) {
        states[sessionId] = recipe
    }

    fun reset(sessionId: String) {
        states.remove(sessionId)
    }

    fun resetAll() {
        states.clear()
    }
}
```

- [ ] **Step 3: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatEditStateHolderTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatEditStateHolder.kt
git add app/src/test/java/com/mamba/picme/features/chat/ChatEditStateHolderTest.kt
git commit -m "feat(chat): add ChatEditStateHolder for session-level edit state"
```

---

### Task 6: 创建 ChatEditRecipeBuilder

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/model/ChatEditRecipeBuilder.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/model/ChatEditRecipeBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.domain.model

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.EditParams
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.features.editor.AdjustmentRecipe
import com.mamba.picme.features.editor.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatEditRecipeBuilderTest {

    private val builder = ChatEditRecipeBuilder()
    private val base = EditRecipe(sourceUri = "file:///test.jpg")

    @Test
    fun `absolute smoothing sets value`() {
        val result = builder.build(
            base,
            AgentCommand.EditImage(params = EditParams(smoothing = EditParams.Absolute(35f)))
        )
        assertEquals(35f, result.beauty.smoothing, 0.001f)
    }

    @Test
    fun `delta brightness adds to current`() {
        val current = base.copy(adjustments = AdjustmentRecipe(brightness = 10f))
        val result = builder.build(
            current,
            AgentCommand.EditImage(params = EditParams(brightness = EditParams.Delta(15f)))
        )
        assertEquals(25f, result.adjustments.brightness, 0.001f)
    }

    @Test
    fun `filter name maps to FilterType`() {
        val result = builder.build(
            base,
            AgentCommand.EditImage(params = EditParams(filterName = EditParams.AbsoluteString("FILM_GOLD")))
        )
        assertEquals(FilterType.FILM_GOLD, result.colorFilter)
        assertEquals(1.0f, result.filterIntensity, 0.001f)
    }

    @Test
    fun `filter intensity halves default`() {
        val result = builder.build(
            base,
            AgentCommand.EditImage(
                params = EditParams(
                    filterName = EditParams.AbsoluteString("COOL"),
                    filterIntensity = 0.4f
                )
            )
        )
        assertEquals(FilterType.COOL, result.colorFilter)
        assertEquals(0.4f, result.filterIntensity, 0.001f)
    }

    @Test
    fun `temperature delta converts to Kelvin step`() {
        val current = base.copy(adjustments = AdjustmentRecipe(temperature = 5000f))
        val result = builder.build(
            current,
            AgentCommand.EditImage(params = EditParams(temperature = EditParams.Delta(300f)))
        )
        assertEquals(5300f, result.adjustments.temperature, 0.001f)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.model.ChatEditRecipeBuilderTest"`
Expected: FAIL — `ChatEditRecipeBuilder` not found

- [ ] **Step 2: Implement ChatEditRecipeBuilder**

```kotlin
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
            slimFace = resolveAbsolute(current.slimFace, params.slimFace, min = -50f, max = 50f),
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
        return when (val value = params.filterName) {
            is EditParams.AbsoluteString -> resolveFilterType(value.value)
            else -> current
        }
    }

    private fun buildStyleFilter(current: StyleFilter, params: EditParams): StyleFilter {
        return when (val value = params.styleName) {
            is EditParams.AbsoluteString -> resolveStyleFilter(value.value)
            else -> current
        }
    }

    private fun resolveAbsolute(
        current: Float,
        value: EditParams.Value,
        min: Float = 0f,
        max: Float = 100f
    ): Float = when (value) {
        is EditParams.Absolute -> value.value.coerceIn(min, max)
        is EditParams.Delta -> (current + value.value).coerceIn(min, max)
        EditParams.Unchanged -> current
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
```

**注意**：`EditParams` 当前只有 `Value` 接口和 `Absolute(Float)` / `Delta(Float)` / `Unchanged`。但 `filterName` 和 `styleName` 需要字符串类型。回到 Task 1，扩展 `EditParams`：

```kotlin
    data class AbsoluteString(val value: String) : Value
```

并更新 Task 1 的测试和实现。

- [ ] **Step 3: Update EditParams to support string values**

在 Task 1 的 `EditParams.kt` 中添加：

```kotlin
    data class AbsoluteString(val value: String) : Value
```

在 `parseEditParam` 中，当 key 是 `filter_name` 或 `style_name` 且 value 是 String 时返回 `AbsoluteString(value)`。

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.model.ChatEditRecipeBuilderTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/model/ChatEditRecipeBuilder.kt
git add app/src/test/java/com/mamba/picme/domain/model/ChatEditRecipeBuilderTest.kt
git add runtime-core/src/main/java/com/mamba/picme/agent/core/model/command/EditParams.kt
git commit -m "feat(chat): add ChatEditRecipeBuilder with value normalization"
```

---

### Task 7: 创建 ChatEditProcessor

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/usecase/ChatEditProcessor.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/usecase/ChatEditProcessorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import com.mamba.picme.beauty.api.FaceData
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.features.editor.EditRecipe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEditProcessorTest {

    @Test
    fun `process returns result uri when successful`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val photoProcessor = mockk<PhotoProcessor>(relaxed = true)
        val faceDetector = mockk<com.mamba.picme.beauty.api.facedetect.FaceDetector>(relaxed = true)
        val mediaRepository = mockk<com.mamba.picme.domain.repository.MediaRepository>(relaxed = true)

        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        every { photoProcessor.process(any(), any(), any()) } returns bitmap

        val processor = ChatEditProcessor(photoProcessor, faceDetector, mediaRepository)
        val recipe = EditRecipe(sourceUri = "file:///test.jpg")
        val result = processor.execute(context, "file:///test.jpg", recipe)

        assertTrue(result.isSuccess)
        verify { photoProcessor.process(any(), any(), any()) }
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.ChatEditProcessorTest"`
Expected: FAIL — `ChatEditProcessor` not found

- [ ] **Step 2: Implement ChatEditProcessor**

```kotlin
package com.mamba.picme.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.beauty.api.toBeautyParams
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.features.editor.EditRecipe
import com.mamba.picme.features.editor.RecipeApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "ChatEditProcessor"
private const val PREVIEW_MAX_DIM = 2048

class ChatEditProcessor(
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val mediaRepository: MediaRepository
) {

    private val photoProcessingDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /**
     * 执行编辑并保存结果图。
     *
     * @return 保存后的图片 URI，失败时返回异常
     */
    suspend fun execute(context: Context, sourceUri: String, recipe: EditRecipe): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val fullBitmap = decodeFullBitmap(context, Uri.parse(sourceUri))
                    ?: return@withContext Result.failure(IllegalStateException("无法加载原图: $sourceUri"))

                val applier = RecipeApplier(photoProcessor, photoProcessingDispatcher)
                val cropped = withContext(Dispatchers.Default) { applier.applyCrop(fullBitmap, recipe.crop) }
                val faceData = detectFace(cropped)
                val processed = applier.applyGpuEffects(cropped, recipe, faceData)
                val outputUri = saveBitmapToMediaStore(context, processed)

                if (outputUri != null) {
                    mediaRepository.refreshMediaLibrary()
                    Result.success(outputUri)
                } else {
                    Result.failure(IllegalStateException("保存结果图失败"))
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Chat edit failed", e)
                Result.failure(e)
            }
        }
    }

    private fun decodeFullBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Decode full bitmap failed", e)
            null
        }
    }

    private suspend fun detectFace(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        runCatching {
            faceDetector.detectPhoto(bitmap, lensFacing = 1)?.landmarks106?.let { landmarks ->
                com.mamba.picme.features.editor.FaceDataConverter.fromLandmarks106(
                    landmarks, bitmap.width, bitmap.height
                )
            }
        }.getOrNull()
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap): String? {
        val name = "CHAT_EDIT_${System.currentTimeMillis()}.jpg"
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
}
```

**注意**：`FaceDataConverter` 当前在 `PhotoEditorViewModel.kt` 的同一包下。如果它是 `private`，需要将其提取到 `app/src/main/java/com/mamba/picme/features/editor/FaceDataConverter.kt` 并改为 `internal` 或 `public`。

- [ ] **Step 3: Extract FaceDataConverter if needed**

如果 `FaceDataConverter` 在 `PhotoEditorViewModel.kt` 中为 private，新建文件：

```kotlin
package com.mamba.picme.features.editor

import com.mamba.picme.beauty.api.FaceData

object FaceDataConverter {
    fun fromLandmarks106(landmarks: FloatArray, width: Int, height: Int): FaceData {
        // 保持原有实现
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.usecase.ChatEditProcessorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/usecase/ChatEditProcessor.kt
-git add app/src/test/java/com/mamba/picme/domain/usecase/ChatEditProcessorTest.kt
git add app/src/main/java/com/mamba/picme/features/editor/FaceDataConverter.kt
git commit -m "feat(chat): add ChatEditProcessor for inline image rendering"
```

---

### Task 8: 创建 ImageEditCapability

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/agent/capability/ImageEditCapability.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/agent/capability/ImageEditCapabilityTest.kt`

先读 `Capability.kt` 和现有 Capability 实现了解接口。

- [ ] **Step 1: Inspect Capability interface**

Run:
- `Read runtime-core/src/main/java/com/mamba/picme/agent/core/capability/Capability.kt`
- `Read app/src/main/java/com/mamba/picme/domain/agent/capability/AGENTS.md`

- [ ] **Step 2: Write the failing test**

```kotlin
package com.mamba.picme.domain.agent.capability

import android.content.Context
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.command.EditParams
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.domain.model.ChatEditRecipeBuilder
import com.mamba.picme.domain.usecase.ChatEditProcessor
import com.mamba.picme.features.chat.ChatEditStateHolder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditCapabilityTest {

    @Test
    fun `dispatch EditImage returns Success action`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val processor = mockk<ChatEditProcessor>(relaxed = true)
        val stateHolder = ChatEditStateHolder()
        val capability = ImageEditCapability(context, processor, stateHolder)

        coEvery { processor.execute(any(), any(), any()) } returns Result.success("file:///result.jpg")

        val command = AgentCommand.EditImage(
            imageUri = "file:///test.jpg",
            params = EditParams(brightness = EditParams.Delta(15f)),
            explanation = "调亮一点"
        )
        val result = capability.dispatch(command, AgentContext(scene = com.mamba.picme.agent.core.model.context.AgentScene.CHAT))

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() is com.mamba.picme.agent.core.model.context.AgentAction.Success)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.ImageEditCapabilityTest"`
Expected: FAIL — `ImageEditCapability` not found

- [ ] **Step 3: Implement ImageEditCapability**

```kotlin
package com.mamba.picme.domain.agent.capability

import android.content.Context
import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentIdGenerator
import com.mamba.picme.domain.model.ChatEditRecipeBuilder
import com.mamba.picme.domain.usecase.ChatEditProcessor
import com.mamba.picme.features.chat.ChatEditStateHolder
import com.mamba.picme.features.editor.EditRecipe
import kotlinx.coroutines.runBlocking

class ImageEditCapability(
    private val context: Context,
    private val chatEditProcessor: ChatEditProcessor,
    private val chatEditStateHolder: ChatEditStateHolder
) : Capability {

    override val scene: String = "CHAT"

    override fun canHandle(command: AgentCommand): Boolean {
        return command is AgentCommand.EditImage
    }

    override fun dispatch(command: AgentCommand, agentContext: AgentContext): Result<AgentAction> {
        val editCommand = command as? AgentCommand.EditImage
            ?: return Result.success(
                AgentAction.Error(
                    commandId = AgentIdGenerator.nextId(),
                    errorCode = AgentErrorCode.INVALID_REQUEST,
                    message = "命令类型不匹配"
                )
            )

        val sessionId = agentContext.memorySessionId
        val currentRecipe = chatEditStateHolder.get(sessionId)
            .takeIf { it.sourceUri.isNotBlank() }
            ?: EditRecipe(sourceUri = editCommand.imageUri)

        val targetUri = editCommand.imageUri.takeIf { it.isNotBlank() }
            ?: currentRecipe.sourceUri.takeIf { it.isNotBlank() }
            ?: return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INVALID_REQUEST,
                    message = "请先发送一张图片"
                )
            )

        val recipe = ChatEditRecipeBuilder.build(currentRecipe.copy(sourceUri = targetUri), editCommand)

        return runBlocking {
            chatEditProcessor.execute(context.applicationContext, targetUri, recipe)
                .fold(
                    onSuccess = { outputUri ->
                        chatEditStateHolder.update(sessionId, recipe)
                        Result.success(
                            AgentAction.Success(
                                commandId = command.commandId,
                                command = editCommand.copy(imageUri = outputUri)
                            )
                        )
                    },
                    onFailure = { error ->
                        Result.success(
                            AgentAction.Error(
                                commandId = command.commandId,
                                errorCode = AgentErrorCode.INTERNAL_ERROR,
                                message = "编辑失败：${error.message ?: "未知错误"}"
                            )
                        )
                    }
                )
        }
    }
}
```

**注意**：`Capability` 接口的实际签名需要与项目一致（泛型参数 `<T, C, P, A>`）。请根据 `runtime-core/src/main/java/com/mamba/picme/agent/core/capability/Capability.kt` 调整。

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.agent.capability.ImageEditCapabilityTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/agent/capability/ImageEditCapability.kt
git add app/src/test/java/com/mamba/picme/domain/agent/capability/ImageEditCapabilityTest.kt
git commit -m "feat(chat): add ImageEditCapability for CapabilityRegistry"
```

---

### Task 9: 扩展 ChatViewModel 处理编辑结果

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelEditResultTest.kt`

- [ ] **Step 1: Add EditResult data class and AGENT_EDIT_RESULT handling**

在 `ChatViewModel.kt` 中：

1. 注入 `ChatEditStateHolder` 和 `ChatEditProcessor`（通过 `ChatViewModelDependencies`）。
2. 在 `handleAgentAction` 中新增 `AgentAction.Success` 且 command 为 `AgentCommand.EditImage` 的分支。
3. 新增 `insertEditResultMessage` 方法。

```kotlin
    private suspend fun handleAgentAction(
        action: AgentAction?,
        sessionId: String,
        modelLabel: String,
        performance: LlmPerformance? = null
    ) {
        when (action) {
            // ... 已有分支 ...
            is AgentAction.Success -> {
                when (val cmd = action.command) {
                    is AgentCommand.AiOptimize -> { /* 现有逻辑 */ }
                    is AgentCommand.EditImage -> {
                        val outputUri = cmd.imageUri
                        val explanation = cmd.explanation ?: "✅ 已为你编辑这张照片"
                        chatEditStateHolder.update(sessionId, /* 需要保留 recipe，见下方 */)
                        insertEditResultMessage(sessionId, outputUri, explanation, modelLabel, performance)
                    }
                    else -> {
                        insertAgentMessage(sessionId, describeCommandResult(cmd), "command", performance)
                    }
                }
            }
            // ... 其余分支 ...
        }
    }
```

4. 新增 `insertEditResultMessage`：

```kotlin
    private suspend fun insertEditResultMessage(
        sessionId: String,
        imageUri: String,
        explanation: String,
        modelUsed: String,
        performance: LlmPerformance? = null
    ) {
        val metadata = JSONObject().apply {
            put("imageUri", imageUri)
            put("explanation", explanation)
            put("suggestions", JSONArray(listOf("再亮一点", "去编辑页微调")))
        }.toString()
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = "agent_edit_result",
                content = imageUri,
                modelUsed = modelUsed,
                metadata = metadata
            )
        )
        chatSessionDao.touchSession(sessionId)
    }
```

5. 更新 `toUiModel()` 添加 `agent_edit_result` 类型映射：

```kotlin
                "agent_edit_result" -> ChatMessageType.AGENT_EDIT_RESULT
```

- [ ] **Step 2: Update ChatViewModelDependencies**

在 `ChatViewModelDependencies.kt` 中新增：

```kotlin
    val chatEditStateHolder: ChatEditStateHolder,
    val chatEditProcessor: ChatEditProcessor
```

并在 `AppContainer` / `ChatViewModel` 工厂中提供实例。

- [ ] **Step 3: Write the test**

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelEditResultTest {

    @Test
    fun `handleAgentAction for EditImage inserts edit result message`() = runTest {
        // 使用真实 ChatViewModel 或抽离的 helper 测试；若难以构造，可改为测试 insertEditResultMessage
        val chatMessageDao = mockk<ChatMessageDao>(relaxed = true)
        val chatSessionDao = mockk<ChatSessionDao>(relaxed = true)
        // 验证 insertMessage 被调用且 type = agent_edit_result
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatViewModelEditResultTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
-git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt
git add app/src/test/java/com/mamba/picme/features/chat/ChatViewModelEditResultTest.kt
git commit -m "feat(chat): handle EditImage result in ChatViewModel"
```

---

### Task 10: 扩展 ChatScreen 展示编辑结果消息

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: Add AGENT_EDIT_RESULT to ChatMessageType and UI handling**

在 `ChatMessageType` 枚举中添加：

```kotlin
    AGENT_EDIT_RESULT
```

在 `ChatMessageItem` 的 `when` 分支中新增 `AGENT_EDIT_RESULT` 处理：

```kotlin
                message.type == ChatMessageType.AGENT_EDIT_RESULT -> {
                    val metadata = parseEditResultMetadata(message.content, message.imageUri)
                    Column(
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(
                            text = metadata.explanation,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        AsyncImage(
                            model = metadata.imageUri,
                            contentDescription = stringResource(R.string.cd_edit_result),
                            contentScale = ContentScale.FillHeight,
                            modifier = Modifier
                                .height(200.dp)
                                .widthIn(max = 260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val uri = Uri.parse(metadata.imageUri)
                                    onImageClick(uri)
                                }
                        )
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            metadata.suggestions.forEach { suggestion ->
                                SuggestionChip(
                                    text = suggestion,
                                    onClick = { onSuggestionClick(suggestion, metadata.imageUri) }
                                )
                            }
                        }
                    }
                }
```

- [ ] **Step 2: Add SuggestionChip composable**

在 `ChatScreen.kt` 中添加：

```kotlin
@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    androidx.compose.material3.SuggestionChip(
        onClick = onClick,
        label = { Text(text, fontSize = 12.sp) }
    )
}
```

- [ ] **Step 3: Wire suggestion clicks to ViewModel**

在 `ChatScreen` 的 `ChatMessageItem` 调用处传入 `onSuggestionClick`：

```kotlin
                                ChatMessageItem(
                                    message = message,
                                    onImageClick = { imageUri -> previewImageUri = imageUri },
                                    onSuggestionClick = { suggestion, imageUri ->
                                        viewModel.sendMessage(suggestion, imageUri)
                                    }
                                )
```

并更新 `ChatMessageItem` 签名：

```kotlin
private fun ChatMessageItem(
    message: ChatMessageUi,
    onImageClick: (Uri) -> Unit = {},
    onSuggestionClick: (String, String) -> Unit = { _, _ -> }
)
```

- [ ] **Step 4: Register ImageEditCapability in ChatScreen**

在 `ChatScreen` 中已有 `RegisterCapability(ChatSearchCapability...)` 的位置添加：

```kotlin
    val imageEditCapability = remember { ImageEditCapability(context, chatEditProcessor, chatEditStateHolder) }
    RegisterCapability(imageEditCapability)
```

**注意**：`RegisterCapability` 只能注册无参构造的 Capability 单例，或需要自定义注册机制。如果 `RegisterCapability` 不支持带参数的 Capability，则改为在 `PoLangApplication` 或 `MainActivity` 中通过 `AgentOrchestrator.getInstance(context).registerCapability(imageEditCapability)` 注册。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): render AGENT_EDIT_RESULT messages with suggestions"
```

---

### Task 11: 更新依赖注入与字符串资源

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

- [ ] **Step 1: Provide ChatEditProcessor and ChatEditStateHolder in AppContainer**

在 `AppContainer.kt` 中新增 provider：

```kotlin
    val chatEditStateHolder: ChatEditStateHolder by lazy {
        ChatEditStateHolder()
    }

    val chatEditProcessor: ChatEditProcessor by lazy {
        ChatEditProcessor(
            photoProcessor = photoProcessor,
            faceDetector = faceDetector,
            mediaRepository = mediaRepository
        )
    }
```

并将它们注入 `ChatViewModelDependencies` 的创建处。

- [ ] **Step 2: Add string resources (3 languages)**

`app/src/main/res/values/strings.xml`:

```xml
    <string name="cd_edit_result">Edited photo</string>
    <string name="edit_result_open_editor">Open editor</string>
    <string name="chat_edit_unsupported_erase">Object removal is in development. You can use the editor manually.</string>
    <string name="chat_edit_unsupported_local_beauty">Per-area beauty adjustment is not supported yet.</string>
    <string name="chat_edit_hint">Try: smooth skin 30, film filter, brighter</string>
```

`app/src/main/res/values-zh-rCN/strings.xml`:

```xml
    <string name="cd_edit_result">编辑后的照片</string>
    <string name="edit_result_open_editor">去编辑页微调</string>
    <string name="chat_edit_unsupported_erase">智能消除正在开发中，你可以先在编辑页手动处理。</string>
    <string name="chat_edit_unsupported_local_beauty">局部美颜暂不支持。</string>
    <string name="chat_edit_hint">试试：磨皮 30、胶片风、调亮一点</string>
```

`app/src/main/res/values-zh-rTW/strings.xml`:

```xml
    <string name="cd_edit_result">編輯後的照片</string>
    <string name="edit_result_open_editor">去編輯頁微調</string>
    <string name="chat_edit_unsupported_erase">智慧消除正在開發中，你可以先在編輯頁手動處理。</string>
    <string name="chat_edit_unsupported_local_beauty">局部美顏暫不支援。</string>
    <string name="chat_edit_hint">試試：磨皮 30、膠片風、調亮一點</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/di/AppContainer.kt
-git add app/src/main/res/values/strings.xml
-git add app/src/main/res/values-zh-rCN/strings.xml
-git add app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(chat): wire ChatEdit dependencies and add I18N strings"
```

---

### Task 12: 处理未支持能力的友好提示

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/model/ChatEditRecipeBuilder.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt`

- [ ] **Step 1: Add UnsupportedEdit intent to parser**

在 parser 中识别“消除”“去水印”“局部美颜”“只放大左眼”等指令，返回 `AgentCommand.TextReply` 而非错误：

```kotlin
            "edit_image_unsupported" -> {
                val args = json.optJSONObject("args") ?: JSONObject()
                val reason = args.optString("reason", "unsupported")
                AgentCommand.TextReply(
                    message = when (reason) {
                        "erase" -> context.getString(R.string.chat_edit_unsupported_erase)
                        "local_beauty" -> context.getString(R.string.chat_edit_unsupported_local_beauty)
                        else -> "暂不支持该编辑操作"
                    }
                )
            }
```

**注意**：`LocalCommandParser` 在 `:runtime-core` 模块，无法直接访问 `:app` 的 `R.string`。应保持返回结构化 reason，由 `ChatViewModel` 或 `ImageEditCapability` 映射到用户文案。

修正方案：parser 返回 `AgentCommand.EditImage` 带 `params = EditParams.Unsupported(reason)`，或在 `ImageEditCapability` 中识别未支持语义并返回 `AgentAction.TextReply`。

更简单的做法：在 `ChatEditRecipeBuilder.resolveFilterType` 等函数中无法识别的指令不报错，而是由 LLM prompt 明确约束只输出支持的参数。若 LLM 输出 unsupported，parser 返回 `AgentCommand.TextReply`。

- [ ] **Step 2: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/parser/LocalCommandParser.kt
git commit -m "feat(chat): graceful unsupported edit handling"
```

---

### Task 13: 编译与单元测试验证

**Files:** N/A

- [ ] **Step 1: Compile runtime-core**

Run: `./gradlew :runtime-core:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Compile app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all new unit tests**

Run:
```bash
./gradlew :runtime-core:testDebugUnitTest --tests "com.mamba.picme.agent.core.model.command.*" --tests "com.mamba.picme.agent.core.inference.local.parser.*"
./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatEditStateHolderTest" --tests "com.mamba.picme.domain.model.ChatEditRecipeBuilderTest" --tests "com.mamba.picme.domain.usecase.ChatEditProcessorTest" --tests "com.mamba.picme.domain.agent.capability.ImageEditCapabilityTest" --tests "com.mamba.picme.features.editor.EditRecipeFilterIntensityTest"
```
Expected: all PASS

- [ ] **Step 4: Commit any fixes**

```bash
git commit -m "fix(chat): address compile and test issues for conversational editing"
```

---

### Task 14: 更新相关 AGENTS.md 文档

**Files:**
- Modify: `app/AGENTS.md`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/AGENTS.md`（若存在）

- [ ] **Step 1: Update app/AGENTS.md**

在 `app/AGENTS.md` 的“子包结构”或“常见变更检查清单”附近添加：

```markdown
- **Chat 对话式编辑**：`domain/model/ChatEditRecipeBuilder.kt`、`domain/usecase/ChatEditProcessor.kt`、`domain/agent/capability/ImageEditCapability.kt`、`features/chat/ChatEditStateHolder.kt` 实现从自然语言到 GPU 渲染的闭环。
```

- [ ] **Step 2: Update chat module AGENTS.md**

如果存在 `app/src/main/java/com/mamba/picme/features/chat/AGENTS.md`，添加对话式编辑实现说明和测试指引。

- [ ] **Step 3: Commit**

```bash
git add app/AGENTS.md
-git add app/src/main/java/com/mamba/picme/features/chat/AGENTS.md
git commit -m "docs(chat): update AGENTS.md for conversational image editing"
```

---

### Task 15: 手动/集成验收

**Files:** N/A

- [ ] **Step 1: 在设备或模拟器上安装 debug APK**

Run: `./gradlew :app:installDebug`
Expected: INSTALL SUCCESS

- [ ] **Step 2: 手动测试核心场景**

1. 打开 Chat → 发送一张图片 → 输入“磨皮 30” → 期望看到结果图返回。
2. 输入“再亮一点” → 期望在上一轮基础上亮度增加。
3. 输入“换成胶片风” → 期望应用 FILM_GOLD 滤镜。
4. 输入“去掉路人” → 期望看到友好提示，不崩溃。
5. 点击结果图的“去编辑页微调” → 期望跳转到 PhotoEditor 且参数已预填。

- [ ] **Step 3: 记录性能基线**

使用 `adb logcat -s ChatEditProcessor:V AgentOrchestrator:V` 抓取：
- 端到端延迟（用户发送 → 结果图展示）
- GPU 渲染耗时
- LLM 首 token 延迟

目标：轻量编辑 < 2s。

- [ ] **Step 4: Commit test notes or update PERFORMANCE_BASELINE_REPORT.md**

如果性能不达标，记录到 `docs/06-QA/PERFORMANCE_BASELINE_REPORT.md` 并创建优化任务。

---

### Task 16: Self-Review and Final Verification

- [ ] **Spec coverage check**

对照 `docs/superpowers/specs/2026-07-18-chat-conversational-image-editing-design.md`：

| Spec 要求 | 实现任务 |
|-----------|---------|
| 发送图片 + 自然语言编辑 | Task 9, 10 |
| inline 返回结果图 | Task 9, 10 |
| 多轮 delta 调整 | Task 5, 9 |
| 美颜参数映射 | Task 6 |
| 滤镜/调色映射 | Task 4, 6 |
| 未支持能力提示 | Task 12 |
| 不覆盖原图 | Task 7 |
| 测试覆盖 | Task 1-8 各自的测试 |

- [ ] **Placeholder scan**

搜索 plan 中的 `TBD`、`TODO`、`...`、`implement later`。修复任何占位符。

- [ ] **Type consistency check**

确认：
- `AgentCommand.EditImage` 的 `params` 类型为 `EditParams`
- `ChatEditRecipeBuilder.build` 返回 `EditRecipe`
- `ChatEditProcessor.execute` 返回 `Result<String>`
- `ImageEditCapability` 实现项目实际的 `Capability` 接口

- [ ] **Final commit**

```bash
git add docs/superpowers/plans/2026-07-18-chat-conversational-image-editing.md
git commit -m "docs: add implementation plan for chat conversational image editing"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-18-chat-conversational-image-editing.md`.

Two execution options:

**1. Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using `executing-plans`, batch execution with checkpoints for review.

Which approach?
