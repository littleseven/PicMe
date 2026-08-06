# Chat 页 AI 优化抽卡 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **工作区要求：** 按 AGENTS.md §3.4，执行前先用 superpowers:using-git-worktrees 在 `.worktrees/` 下建隔离工作区与专用分支，所有提交落在该分支。

**Goal:** chat 内 AI 优化指令从固定预设单发升级为抽卡闭环：对话内候选卡条 + 换一组 + 「就用这张」确认，确认后折叠为普通结果图消息并支持多轮 delta 续调。

**Architecture:** domain 层 `optimize/gacha/` 引擎零改动，直接复用 `AiOptimizeUseCase.optimizeWithGacha()`（三分支 Selected/KeepOriginal/Unavailable）。chat 侧新增：`OptimizeCandidateGroup` 消息负载（type=optimize_candidates，metadata JSON，无需 Room Migration）、`ChatOptimizeGachaController` 编排器（抽卡/换一组/确认/废弃 + pending 内存态 + 反馈落库）、`GachaCandidateStrip` 卡条 UI。`ChatViewModel` 负责消息插入/覆写（DAO insert-replace 模式）与 dismiss 拦截。

**Tech Stack:** Kotlin、Jetpack Compose、Coil、Room（复用现有表）、JUnit4 + MockK + kotlinx-coroutines-test。

**Spec:** `docs/superpowers/specs/2026-08-06-chat-optimize-gacha-design.md`（commit `dc955cb6`）

---

## 关键既有事实（实现前必读）

- `ChatMessageEntity`（`app/src/main/java/com/mamba/picme/data/local/ChatMessageEntity.kt`）：`type` 为自由字符串、`metadata` 为 JSON 扩展字段——**新增消息类型无需 Room Migration**。
- 更新已有消息的模式（`ChatViewModel.removeMediaResultAsset` :2378）：`chatMessageDao.getMessageById(id)` → `entity.copy(...)` → `chatMessageDao.insertMessage(...)`（INSERT OR REPLACE 语义）。
- `ChatImageRenderer.aiOptimize(imageUri, sessionId): Outcome(imageUri, explanation)`（`ChatImageRenderer.kt:122`）是现有单发路径，**保持不动**，作为抽卡 Unavailable/控制器未注入时的兜底。
- `ChatImageRenderer.renderRecipe(imageUri, recipe, sessionId): String?`（`ChatImageRenderer.kt:175`）是公开的全尺寸渲染入口，确认时直接调用。
- `AiOptimizeUseCase.GachaOutcome`（`AiOptimizeUseCase.kt:64`）：`result / scene / editRecipe / explanation / usedFingerprints / processingTimeMs`。
- `OptimizeFeedbackLogger.log(imageUri, scene, all, selectedIndex, source)`，`SOURCE_AUTO / SOURCE_USER / SOURCE_DISMISS`。
- 单测可直接用 `org.json.JSONObject`（先例：`app/src/test/java/com/mamba/picme/core/agenttools/AppToolExecutorTest.kt`）。
- UI Toast 约定：ViewModel 回调带结果 lambda，ChatScreen 里 `Toast.makeText`（先例：`ChatScreen.kt:2603` 保存失败 Toast）。
- 候选缩略图加载：Coil `AsyncImage(model = "file://...")`（ChatScreen 已有 Coil 依赖与用法）。
- 预览浮层 `ChatImagePreviewOverlay` 的保存按钮仅在 `ImagePreviewPage.isEditableResult == true` 时显示（`ChatScreen.kt:2597`）——候选卡预览传 `isEditableResult = false` 即天然无保存按钮。
- 可复用文案（`res/values/strings.xml:119-122`，各语言文件均已有翻译）：`ai_optimize_recommended` / `ai_optimize_reroll` / `ai_optimize_keep_hint`。

## File Structure

**新增：**
- `app/src/main/java/com/mamba/picme/features/chat/OptimizeCandidateGroup.kt` — 候选卡组消息负载 + JSON 双向
- `app/src/main/java/com/mamba/picme/features/chat/ChatOptimizeGachaController.kt` — 抽卡编排器
- `app/src/main/java/com/mamba/picme/features/chat/components/GachaCandidateStrip.kt` — 候选卡条 Composable
- `app/src/test/java/com/mamba/picme/features/chat/OptimizeCandidateGroupTest.kt`
- `app/src/test/java/com/mamba/picme/features/chat/ChatOptimizeGachaControllerTest.kt`
- `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelGachaTest.kt`

**修改：**
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt` — 新增 `optimizeGachaController` 字段
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` — AiOptimize 分支改走抽卡、卡条回调、消息覆写、toUiModel 解析、dismiss 拦截
- `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` — `ChatMessageType.OPTIMIZE_CANDIDATES` + `ChatMessageUi` 字段 + 卡条分支 + 预览接线
- `app/src/main/java/com/mamba/picme/di/AppContainer.kt` — 组装 `ChatOptimizeGachaController`
- `app/src/main/res/values{,-zh,-zh-rCN,-zh-rTW}/strings.xml` — 新文案三语同步
- `docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md` — 补「chat 抽卡」章节

---

## Task 1: OptimizeCandidateGroup（消息负载 + JSON 双向）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/OptimizeCandidateGroup.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/OptimizeCandidateGroupTest.kt`

- [ ] **Step 1: 写失败测试 OptimizeCandidateGroupTest.kt**

```kotlin
package com.mamba.picme.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimizeCandidateGroupTest {

    private fun group() = OptimizeCandidateGroup(
        sourceImageUri = "content://media/1",
        scene = "GENERAL",
        recommendedIndex = 1,
        candidates = listOf(
            OptimizeCandidateGroup.Candidate("base", "file:///a.jpg", 6.0f, rejected = false),
            OptimizeCandidateGroup.Candidate("warm", "file:///b.jpg", nimaScore = null, rejected = true)
        ),
        usedFingerprints = listOf("fp1", "fp2"),
        drawIndex = 2
    )

    @Test
    fun `toJson fromJson roundtrip preserves all fields`() {
        val restored = OptimizeCandidateGroup.fromJson(group().toJson())!!

        assertEquals("content://media/1", restored.sourceImageUri)
        assertEquals("GENERAL", restored.scene)
        assertEquals(1, restored.recommendedIndex)
        assertEquals(2, restored.drawIndex)
        assertEquals(listOf("fp1", "fp2"), restored.usedFingerprints)
        assertEquals(2, restored.candidates.size)
        assertEquals("base", restored.candidates[0].direction)
        assertEquals("file:///a.jpg", restored.candidates[0].thumbPath)
        assertEquals(6.0f, restored.candidates[0].nimaScore!!, 0.001f)
        assertEquals(false, restored.candidates[0].rejected)
    }

    @Test
    fun `nimaScore null survives roundtrip`() {
        val restored = OptimizeCandidateGroup.fromJson(group().toJson())!!
        assertNull(restored.candidates[1].nimaScore)
        assertEquals(true, restored.candidates[1].rejected)
    }

    @Test
    fun `recommendedIndex -1 (KeepOriginal) survives roundtrip`() {
        val restored = OptimizeCandidateGroup.fromJson(
            group().copy(recommendedIndex = -1).toJson()
        )!!
        assertEquals(-1, restored.recommendedIndex)
    }

    @Test
    fun `fromJson returns null for null or blank input`() {
        assertNull(OptimizeCandidateGroup.fromJson(null))
        assertNull(OptimizeCandidateGroup.fromJson(""))
        assertNull(OptimizeCandidateGroup.fromJson("   "))
    }

    @Test
    fun `fromJson returns null for malformed json`() {
        assertNull(OptimizeCandidateGroup.fromJson("{not json"))
    }

    @Test
    fun `fromJson tolerates missing optional fields`() {
        val json = """
            {"sourceImageUri":"content://x","candidates":[]}
        """.trimIndent()
        val restored = OptimizeCandidateGroup.fromJson(json)!!
        assertEquals("content://x", restored.sourceImageUri)
        assertEquals(-1, restored.recommendedIndex)
        assertEquals(1, restored.drawIndex)
        assertEquals(emptyList<String>(), restored.usedFingerprints)
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.OptimizeCandidateGroupTest"`
Expected: FAIL — `OptimizeCandidateGroup` unresolved

- [ ] **Step 3: 实现 OptimizeCandidateGroup.kt**

```kotlin
package com.mamba.picme.features.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * chat 抽卡候选卡组消息负载（type=[MESSAGE_TYPE] 消息的 metadata JSON）。
 *
 * 只存展示数据（缩略图路径 / NIMA 分 / 方向标签）；候选 preset 保存在
 * [ChatOptimizeGachaController] 的进程级内存态，不落消息——进程重建后
 * 内存态丢失，卡条由 UI 降级为只读展示（spec §4）。
 */
data class OptimizeCandidateGroup(
    val sourceImageUri: String,
    val scene: String,
    /** NIMA 最优卡 index；-1 = KeepOriginal 不预选 */
    val recommendedIndex: Int,
    val candidates: List<Candidate>,
    /** 「换一组」回传 exclude 的去重指纹 */
    val usedFingerprints: List<String>,
    /** 第几组（换一组 +1） */
    val drawIndex: Int
) {
    /**
     * 单张候选卡的展示数据。
     *
     * @property thumbPath ChatImageStore 落盘的 512px 候选图 file:// 路径；空串 = 落盘失败（UI 显示占位）
     * @property nimaScore NIMA 美学分；null = 未评分（护栏淘汰 / 推理失败）
     */
    data class Candidate(
        val direction: String,
        val thumbPath: String,
        val nimaScore: Float?,
        val rejected: Boolean
    )

    fun toJson(): String {
        val arr = JSONArray()
        candidates.forEach { c ->
            arr.put(JSONObject().apply {
                put("direction", c.direction)
                put("thumbPath", c.thumbPath)
                if (c.nimaScore != null) put("nimaScore", c.nimaScore.toDouble())
                put("rejected", c.rejected)
            })
        }
        return JSONObject().apply {
            put("sourceImageUri", sourceImageUri)
            put("scene", scene)
            put("recommendedIndex", recommendedIndex)
            put("drawIndex", drawIndex)
            put("candidates", arr)
            put("usedFingerprints", JSONArray(usedFingerprints))
        }.toString()
    }

    companion object {
        const val MESSAGE_TYPE = "optimize_candidates"

        /** 解析失败 / 必需字段缺失返回 null（调用方按无负载处理，不崩溃）。 */
        fun fromJson(json: String?): OptimizeCandidateGroup? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(json)
                val arr = obj.getJSONArray("candidates")
                val candidates = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    Candidate(
                        direction = c.optString("direction"),
                        thumbPath = c.optString("thumbPath"),
                        nimaScore = if (c.has("nimaScore")) c.getDouble("nimaScore").toFloat() else null,
                        rejected = c.optBoolean("rejected", false)
                    )
                }
                val fps = obj.optJSONArray("usedFingerprints")
                OptimizeCandidateGroup(
                    sourceImageUri = obj.getString("sourceImageUri"),
                    scene = obj.optString("scene"),
                    recommendedIndex = obj.optInt("recommendedIndex", -1),
                    candidates = candidates,
                    usedFingerprints = if (fps == null) emptyList() else (0 until fps.length()).map { fps.getString(it) },
                    drawIndex = obj.optInt("drawIndex", 1)
                )
            }.getOrNull()
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.OptimizeCandidateGroupTest"`
Expected: PASS（6 个用例）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/OptimizeCandidateGroup.kt app/src/test/java/com/mamba/picme/features/chat/OptimizeCandidateGroupTest.kt
git commit -m "feat(chat): add optimize candidate group message payload"
```

---

## Task 2: ChatOptimizeGachaController（抽卡编排器）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/ChatOptimizeGachaController.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatOptimizeGachaControllerTest.kt`

- [ ] **Step 1: 写失败测试 ChatOptimizeGachaControllerTest.kt**

```kotlin
package com.mamba.picme.features.chat

import android.graphics.Bitmap
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.gacha.GachaResult
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeCandidate
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.ScoredCandidate
import com.mamba.picme.domain.agent.capability.optimize.preset.AdjustmentPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.BeautyPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.FilterPreset
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatOptimizeGachaControllerTest {

    private val useCase = mockk<AiOptimizeUseCase>()
    private val renderer = mockk<ChatImageRenderer>()
    private val store = mockk<ChatImageStore>()
    private val feedbackLogger = mockk<OptimizeFeedbackLogger>(relaxUnitFun = true)
    private val stateHolder = mockk<ChatEditStateHolder>(relaxUnitFun = true)

    private fun controller() = ChatOptimizeGachaController(
        optimizeUseCase = useCase,
        chatImageRenderer = renderer,
        chatImageStore = store,
        feedbackLogger = feedbackLogger,
        chatEditStateHolder = stateHolder
    )

    private fun preset() = OptimizePreset(
        scene = "GENERAL",
        beauty = BeautyPreset(),
        filter = FilterPreset(),
        adjustment = AdjustmentPreset()
    )

    private fun scored(index: Int, score: Float?, rejected: Boolean = false) = ScoredCandidate(
        candidate = OptimizeCandidate(index = index, direction = "d$index", preset = preset()),
        nimaScore = score,
        rejected = rejected,
        rejectReason = if (rejected) "nima_failed" else null,
        thumbnail = mockk<Bitmap>()
    )

    private fun outcome(result: GachaResult, fingerprints: Set<String> = setOf("fp1")) =
        AiOptimizeUseCase.GachaOutcome(
            result = result,
            scene = Scene.GENERAL,
            editRecipe = null,
            explanation = "expl",
            usedFingerprints = fingerprints,
            processingTimeMs = 1L
        )

    @Test
    fun `draw Selected returns Candidates with best as recommendedIndex`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f), scored(2, 5.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        val result = c.draw("msg1", "uri", "session1")

        assertTrue(result is ChatOptimizeGachaController.DrawOutcome.Candidates)
        val group = (result as ChatOptimizeGachaController.DrawOutcome.Candidates).group
        assertEquals(1, group.recommendedIndex)
        assertEquals(3, group.candidates.size)
        assertEquals("file:///t.jpg", group.candidates[0].thumbPath)
        assertEquals(1, group.drawIndex)
        assertTrue(c.hasPending("msg1"))
    }

    @Test
    fun `draw KeepOriginal returns Candidates with recommendedIndex -1`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.02f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.KeepOriginal(all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val result = controller().draw("msg1", "uri", "session1")

        assertEquals(-1, (result as ChatOptimizeGachaController.DrawOutcome.Candidates).group.recommendedIndex)
    }

    @Test
    fun `draw Unavailable falls back to legacy single-shot`() = runTest {
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Unavailable)
        coEvery { renderer.aiOptimize("uri", "session1") } returns
            ChatImageRenderer.Outcome("file:///r.jpg", "legacy expl")

        val result = controller().draw("msg1", "uri", "session1")

        assertEquals(
            ChatOptimizeGachaController.DrawOutcome.Fallback("file:///r.jpg", "legacy expl"),
            result
        )
    }

    @Test
    fun `draw falls back when all thumbnails fail to persist`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } throws RuntimeException("disk full")
        coEvery { renderer.aiOptimize("uri", "session1") } returns
            ChatImageRenderer.Outcome("file:///r.jpg", "legacy expl")

        val result = controller().draw("msg1", "uri", "session1")

        assertTrue(result is ChatOptimizeGachaController.DrawOutcome.Fallback)
    }

    @Test
    fun `draw keeps candidate with blank thumbPath when individual persist fails`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        var call = 0
        coEvery { store.writeResult(any(), any(), any()) } answers {
            call++
            if (call == 1) throw RuntimeException("io") else "file:///t.jpg"
        }

        val result = controller().draw("msg1", "uri", "session1")

        val group = (result as ChatOptimizeGachaController.DrawOutcome.Candidates).group
        assertEquals("", group.candidates[0].thumbPath)
        assertEquals("file:///t.jpg", group.candidates[1].thumbPath)
    }

    @Test
    fun `reroll passes usedFingerprints as exclude and increments drawIndex`() = runTest {
        val first = listOf(scored(0, 6.0f), scored(1, 6.5f))
        val second = listOf(scored(0, 6.1f), scored(1, 6.6f))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = emptySet()) } returns
            outcome(GachaResult.Selected(best = first[1], all = first, originalScore = 6.0f), setOf("fp1"))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = setOf("fp1")) } returns
            outcome(GachaResult.Selected(best = second[1], all = second, originalScore = 6.0f), setOf("fp1", "fp2"))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.reroll("msg1")

        assertTrue(result is ChatOptimizeGachaController.RerollOutcome.Rerolled)
        val group = (result as ChatOptimizeGachaController.RerollOutcome.Rerolled).group
        assertEquals(2, group.drawIndex)
        assertEquals(listOf("fp1", "fp2"), group.usedFingerprints)
    }

    @Test
    fun `reroll returns Expired for unknown messageId`() = runTest {
        assertEquals(
            ChatOptimizeGachaController.RerollOutcome.Expired,
            controller().reroll("nope")
        )
    }

    @Test
    fun `reroll Unavailable keeps existing pending group`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = emptySet()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { useCase.optimizeWithGacha("uri", any(), exclude = setOf("fp1")) } returns
            outcome(GachaResult.Unavailable)
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.reroll("msg1")

        assertEquals(ChatOptimizeGachaController.RerollOutcome.Unavailable, result)
        assertTrue(c.hasPending("msg1"))
    }

    @Test
    fun `confirm renders full size, updates state holder, logs user, clears pending`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"
        coEvery { renderer.renderRecipe("uri", any(), "session1") } returns "file:///full.jpg"

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.confirm("msg1", 1)

        assertEquals("file:///full.jpg", result?.imageUri)
        verify(exactly = 1) { stateHolder.update("session1", any()) }
        coVerify(exactly = 1) {
            feedbackLogger.log("uri", Scene.GENERAL, all, 1, OptimizeFeedbackLogger.SOURCE_USER)
        }
        assertFalse(c.hasPending("msg1"))
    }

    @Test
    fun `confirm returns null for rejected card without side effects`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, null, rejected = true))
        coEvery { useCase.optimizeWithGacha("uri", any(), any()) } returns
            outcome(GachaResult.Selected(best = all[0], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        c.draw("msg1", "uri", "session1")
        val result = c.confirm("msg1", 1)

        assertNull(result)
        verify(exactly = 0) { stateHolder.update(any(), any()) }
        coVerify(exactly = 0) { feedbackLogger.log(any(), any(), any(), any(), OptimizeFeedbackLogger.SOURCE_USER) }
    }

    @Test
    fun `discardPending logs dismiss only for matching session`() = runTest {
        val all = listOf(scored(0, 6.0f), scored(1, 6.5f))
        coEvery { useCase.optimizeWithGacha(any(), any(), any()) } returns
            outcome(GachaResult.Selected(best = all[1], all = all, originalScore = 6.0f))
        coEvery { store.writeResult(any(), any(), any()) } returns "file:///t.jpg"

        val c = controller()
        c.draw("msg-a", "uri-a", "session1")
        c.draw("msg-b", "uri-b", "session2")
        c.discardPending("session1")

        coVerify(exactly = 1) {
            feedbackLogger.log("uri-a", Scene.GENERAL, all, -1, OptimizeFeedbackLogger.SOURCE_DISMISS)
        }
        assertFalse(c.hasPending("msg-a"))
        assertTrue(c.hasPending("msg-b"))
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatOptimizeGachaControllerTest"`
Expected: FAIL — `ChatOptimizeGachaController` unresolved

- [ ] **Step 3: 实现 ChatOptimizeGachaController.kt**

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.agent.capability.optimize.analyzer.Scene
import com.mamba.picme.domain.agent.capability.optimize.gacha.GachaResult
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeCandidate
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.ScoredCandidate
import com.mamba.picme.domain.agent.capability.optimize.recipe.OptimizeRecipeMapper
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.mamba.picme.features.editor.EditRecipe

/**
 * chat 页 AI 优化抽卡编排器。
 * spec: docs/superpowers/specs/2026-08-06-chat-optimize-gacha-design.md
 *
 * 职责：
 * - 调 [AiOptimizeUseCase.optimizeWithGacha] 抽卡，候选缩略图经 [ChatImageStore] 落盘，
 *   构造 [OptimizeCandidateGroup] 消息负载（auto 落库已在 usecase 内完成）
 * - 维护 pending 组内存态（messageId → 候选 preset / 评分），支撑换一组 / 确认 / 废弃
 * - 确认：全尺寸渲染 + 写 [ChatEditStateHolder]（多轮 delta 续调基础）+ 落库 user
 * - 废弃：落库 dismiss（用户发新消息 / 切会话 / 清空对话时由 ViewModel 触发）
 *
 * 内存态为进程级：进程重建后 pending 丢失，对应卡条由 UI 降级只读（spec §4）。
 */
class ChatOptimizeGachaController(
    private val optimizeUseCase: AiOptimizeUseCase,
    private val chatImageRenderer: ChatImageRenderer,
    private val chatImageStore: ChatImageStore,
    private val feedbackLogger: OptimizeFeedbackLogger?,
    private val chatEditStateHolder: ChatEditStateHolder
) {

    /** 一组 pending 候选的内存态（候选 preset 不落消息，确认/重抽从这里取）。 */
    data class PendingGroup(
        val messageId: String,
        val sessionId: String,
        val sourceImageUri: String,
        val scene: Scene,
        val candidates: List<OptimizeCandidate>,
        val scored: List<ScoredCandidate>,
        val usedFingerprints: Set<String>,
        val drawIndex: Int
    )

    /** 抽卡结果 */
    sealed interface DrawOutcome {
        /** 候选卡组（Selected / KeepOriginal 均发卡组，区别在 recommendedIndex） */
        data class Candidates(
            val group: OptimizeCandidateGroup,
            val explanation: String
        ) : DrawOutcome

        /** 抽卡不可用 / 缩略图全部落盘失败：退回现有单发结果（imageUri=null 时按错误文本处理） */
        data class Fallback(val imageUri: String?, val explanation: String) : DrawOutcome
    }

    /** 换一组结果 */
    sealed interface RerollOutcome {
        data class Rerolled(val group: OptimizeCandidateGroup, val explanation: String) : RerollOutcome

        /** 内存态丢失（进程重建后）；UI 已降级只读时不会触发 */
        data object Expired : RerollOutcome

        /** 引擎不可用 / 落盘全失败：保留当前卡条，由 UI 提示 */
        data object Unavailable : RerollOutcome
    }

    /** 确认结果 */
    data class ConfirmResult(val imageUri: String, val recipe: EditRecipe)

    private val pendingGroups = mutableMapOf<String, PendingGroup>()

    fun hasPending(messageId: String): Boolean = pendingGroups.containsKey(messageId)

    /**
     * 抽卡（新消息）。
     *
     * @param messageId 调用方生成的消息 id，pending 内存态以它为键
     */
    suspend fun draw(messageId: String, imageUri: String, sessionId: String): DrawOutcome {
        val outcome = optimizeUseCase.optimizeWithGacha(imageUri)
        val (scored, recommendedIndex) = when (val r = outcome.result) {
            is GachaResult.Selected -> r.all to r.best.candidate.index
            is GachaResult.KeepOriginal -> r.all to -1
            GachaResult.Unavailable -> return fallback(imageUri, sessionId)
        }
        return persistAndBuild(
            messageId = messageId,
            imageUri = imageUri,
            sessionId = sessionId,
            scene = outcome.scene,
            usedFingerprints = outcome.usedFingerprints,
            scored = scored,
            recommendedIndex = recommendedIndex,
            drawIndex = 1,
            explanation = outcome.explanation
        ) ?: fallback(imageUri, sessionId)
    }

    /** 换一组：以 pending 的 usedFingerprints 为 exclude 重抽并替换内存态。 */
    suspend fun reroll(messageId: String): RerollOutcome {
        val pending = pendingGroups[messageId] ?: return RerollOutcome.Expired
        val outcome = optimizeUseCase.optimizeWithGacha(
            imageUri = pending.sourceImageUri,
            exclude = pending.usedFingerprints
        )
        val (scored, recommendedIndex) = when (val r = outcome.result) {
            is GachaResult.Selected -> r.all to r.best.candidate.index
            is GachaResult.KeepOriginal -> r.all to -1
            GachaResult.Unavailable -> return RerollOutcome.Unavailable
        }
        val built = persistAndBuild(
            messageId = messageId,
            imageUri = pending.sourceImageUri,
            sessionId = pending.sessionId,
            scene = outcome.scene,
            usedFingerprints = outcome.usedFingerprints,
            scored = scored,
            recommendedIndex = recommendedIndex,
            drawIndex = pending.drawIndex + 1,
            explanation = outcome.explanation
        ) ?: return RerollOutcome.Unavailable
        return RerollOutcome.Rerolled(built.group, built.explanation)
    }

    /**
     * 确认选中卡：全尺寸渲染 → 写 [ChatEditStateHolder] → 落库 user → 清内存态。
     *
     * @return null = 内存态丢失 / 卡不存在 / 卡被淘汰 / 渲染失败（调用方提示重试）
     */
    suspend fun confirm(messageId: String, candidateIndex: Int): ConfirmResult? {
        val pending = pendingGroups[messageId] ?: return null
        val scored = pending.scored.firstOrNull { it.candidate.index == candidateIndex } ?: return null
        if (scored.rejected) return null
        val recipe = OptimizeRecipeMapper.toEditRecipe(
            preset = scored.candidate.preset,
            sourceUri = pending.sourceImageUri,
            baseRecipe = EditRecipe(sourceUri = pending.sourceImageUri)
        )
        val rendered = chatImageRenderer.renderRecipe(
            pending.sourceImageUri, recipe, pending.sessionId
        ) ?: return null
        chatEditStateHolder.update(pending.sessionId, recipe)
        pendingGroups.remove(messageId)
        runCatching {
            feedbackLogger?.log(
                pending.sourceImageUri, pending.scene, pending.scored,
                candidateIndex, OptimizeFeedbackLogger.SOURCE_USER
            )
        }
        return ConfirmResult(imageUri = rendered, recipe = recipe)
    }

    /**
     * 废弃会话内 pending 组（用户发新消息 / 切会话 / 清空对话 / 删会话时调用），落库 dismiss。
     *
     * @param exceptMessageId 需要保留的消息 id（一般不用；默认全部废弃）
     */
    suspend fun discardPending(sessionId: String, exceptMessageId: String? = null) {
        val discarded = pendingGroups.values.filter {
            it.sessionId == sessionId && it.messageId != exceptMessageId
        }
        discarded.forEach { p ->
            runCatching {
                feedbackLogger?.log(
                    p.sourceImageUri, p.scene, p.scored, -1,
                    OptimizeFeedbackLogger.SOURCE_DISMISS
                )
            }
            pendingGroups.remove(p.messageId)
        }
        if (discarded.isNotEmpty()) {
            Logger.d(TAG, "discarded ${discarded.size} pending gacha group(s) for session $sessionId")
        }
    }

    /** 候选缩略图落盘 + 构造消息负载 + 登记内存态；全部落盘失败返回 null（调用方走降级）。 */
    private suspend fun persistAndBuild(
        messageId: String,
        imageUri: String,
        sessionId: String,
        scene: Scene,
        usedFingerprints: Set<String>,
        scored: List<ScoredCandidate>,
        recommendedIndex: Int,
        drawIndex: Int,
        explanation: String
    ): DrawOutcome.Candidates? {
        val uiCandidates = scored.map { sc ->
            val thumbPath = sc.thumbnail?.let { bmp ->
                runCatching { chatImageStore.writeResult(sessionId, bmp, "image/jpeg") }
                    .onFailure { Logger.w(TAG, "persist thumbnail failed: ${it.message}") }
                    .getOrNull()
            }.orEmpty()
            OptimizeCandidateGroup.Candidate(
                direction = sc.candidate.direction,
                thumbPath = thumbPath,
                nimaScore = sc.nimaScore,
                rejected = sc.rejected
            )
        }
        if (uiCandidates.all { it.thumbPath.isBlank() }) {
            Logger.w(TAG, "all candidate thumbnails failed to persist, gacha degraded")
            return null
        }
        val group = OptimizeCandidateGroup(
            sourceImageUri = imageUri,
            scene = scene.name,
            recommendedIndex = recommendedIndex,
            candidates = uiCandidates,
            usedFingerprints = usedFingerprints.toList(),
            drawIndex = drawIndex
        )
        pendingGroups[messageId] = PendingGroup(
            messageId = messageId,
            sessionId = sessionId,
            sourceImageUri = imageUri,
            scene = scene,
            candidates = scored.map { it.candidate },
            scored = scored,
            usedFingerprints = usedFingerprints,
            drawIndex = drawIndex
        )
        return DrawOutcome.Candidates(group = group, explanation = explanation)
    }

    /** 退回现有单发路径（与抽卡接入前行为完全一致）。 */
    private suspend fun fallback(imageUri: String, sessionId: String): DrawOutcome {
        val outcome = chatImageRenderer.aiOptimize(imageUri, sessionId)
        return DrawOutcome.Fallback(imageUri = outcome.imageUri, explanation = outcome.explanation)
    }

    companion object {
        private const val TAG = "PoLang:ChatGacha"
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.ChatOptimizeGachaControllerTest"`
Expected: PASS（11 个用例）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatOptimizeGachaController.kt app/src/test/java/com/mamba/picme/features/chat/ChatOptimizeGachaControllerTest.kt
git commit -m "feat(chat): add optimize gacha controller with reroll/confirm/discard"
```

---

## Task 3: ChatViewModel 接线

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelGachaTest.kt`

- [ ] **Step 1: ChatViewModelDependencies 新增字段**

`ChatViewModelDependencies.kt` 构造函数末尾（`saveChatEditResultUseCase` 之后）加：

```kotlin
    val saveChatEditResultUseCase: SaveChatEditResultUseCase,
    val optimizeGachaController: ChatOptimizeGachaController? = null
```

- [ ] **Step 2: 写失败测试 ChatViewModelGachaTest.kt**

继承既有 `ChatViewModelTestBase`（mockk relaxed + `UnconfinedTestDispatcher` 风格，`newViewModel()` 覆盖注入 mock controller）。先读 `app/src/test/java/com/mamba/picme/features/chat/ChatViewModelTestBase.kt` 与 `ChatViewModelEditResultTest.kt` 对齐写法。

```kotlin
package com.mamba.picme.features.chat

import com.mamba.picme.data.local.ChatMessageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelGachaTest : ChatViewModelTestBase() {

    private val gachaController = mockk<ChatOptimizeGachaController>(relaxUnitFun = true)

    override fun newViewModel(): ChatViewModel = super.newViewModelWithGacha(gachaController)

    private fun group() = OptimizeCandidateGroup(
        sourceImageUri = "content://media/1",
        scene = "GENERAL",
        recommendedIndex = 1,
        candidates = listOf(
            OptimizeCandidateGroup.Candidate("base", "file:///a.jpg", 6.0f, false),
            OptimizeCandidateGroup.Candidate("warm", "file:///b.jpg", 6.5f, false)
        ),
        usedFingerprints = listOf("fp1"),
        drawIndex = 1
    )

    @Test
    fun `insertOptimizeCandidatesMessage persists optimize_candidates and seeds selection`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.insertOptimizeCandidatesMessage("default", "msg1", group(), "expl", "remote_deepseek")
        advanceUntilIdle()

        val slot = slot<ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }
        assertEquals(OptimizeCandidateGroup.MESSAGE_TYPE, slot.captured.type)
        assertEquals("msg1", slot.captured.id)
        assertEquals(group().toJson(), slot.captured.metadata)
        assertEquals(1, vm.gachaSelections.value["msg1"])
    }

    @Test
    fun `onOptimizeGachaConfirm rewrites message to agent_image`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        coEvery { gachaController.confirm("msg1", 1) } returns
            ChatOptimizeGachaController.ConfirmResult("file:///full.jpg", mockk())
        coEvery { chatMessageDao.getMessageById("msg1") } returns ChatMessageEntity(
            id = "msg1", sessionId = "default",
            type = OptimizeCandidateGroup.MESSAGE_TYPE,
            content = "expl", metadata = group().toJson()
        )

        vm.onOptimizeGachaConfirm("msg1", 1) { }
        advanceUntilIdle()

        val slot = slot<ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }
        assertEquals("agent_image", slot.captured.type)
        assertEquals("msg1", slot.captured.id)
        assertTrue(slot.captured.metadata!!.contains("file:///full.jpg"))
        assertEquals("expl", slot.captured.content)
    }

    @Test
    fun `onOptimizeGachaConfirm failure keeps message and reports error`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        coEvery { gachaController.confirm("msg1", 1) } returns null

        var failed = false
        vm.onOptimizeGachaConfirm("msg1", 1) { ok -> failed = !ok }
        advanceUntilIdle()

        assertTrue(failed)
        coVerify(exactly = 0) { chatMessageDao.insertMessage(any()) }
    }

    @Test
    fun `onOptimizeGachaReroll rewrites message metadata and resets selection`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        val newGroup = group().copy(drawIndex = 2, recommendedIndex = 0)
        coEvery { gachaController.reroll("msg1") } returns
            ChatOptimizeGachaController.RerollOutcome.Rerolled(newGroup, "new expl")
        coEvery { chatMessageDao.getMessageById("msg1") } returns ChatMessageEntity(
            id = "msg1", sessionId = "default",
            type = OptimizeCandidateGroup.MESSAGE_TYPE,
            content = "expl", metadata = group().toJson()
        )

        vm.onOptimizeGachaReroll("msg1") { }
        advanceUntilIdle()

        val slot = slot<ChatMessageEntity>()
        coVerify { chatMessageDao.insertMessage(capture(slot)) }
        assertEquals(newGroup.toJson(), slot.captured.metadata)
        assertEquals("new expl", slot.captured.content)
        assertEquals(0, vm.gachaSelections.value["msg1"])
    }

    @Test
    fun `clearChat discards pending gacha groups`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.clearChat()
        advanceUntilIdle()

        coVerify { gachaController.discardPending("default", any()) }
    }
}
```

> 注：`super.newViewModelWithGacha(...)` 需要在 `ChatViewModelTestBase` 里加一个受保护的重载（见 Step 3 末尾）；若基类结构不允许，则在本测试类内复制 `newViewModel()` 的构造并在 `ChatViewModelDependencies(...)` 末尾传 `optimizeGachaController = gachaController`。

- [ ] **Step 3: 实现 ChatViewModel 改动**

共 6 处改动（文件：`app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`）：

**(a) 依赖解包**（:152 附近，其他 `dependencies.xxx` 解包处）：

```kotlin
    private val optimizeGachaController = dependencies.optimizeGachaController
```

**(b) 卡条选中状态**（类字段区）：

```kotlin
    /** 卡条选中状态（messageId → 选中卡 index），「就用这张」可用性由 UI 依据该值判断。 */
    private val _gachaSelections = MutableStateFlow<Map<String, Int>>(emptyMap())
    val gachaSelections: StateFlow<Map<String, Int>> = _gachaSelections.asStateFlow()
```

**(c) AiOptimize 分支改写**（:1347-1373，整个 `is AgentCommand.AiOptimize ->` 块替换为）：

```kotlin
                    is AgentCommand.AiOptimize -> {
                        val targetUri = cmd.imageUri.takeIf { it.isNotBlank() }
                            ?: _lastUserImageUri.value
                        if (targetUri.isNullOrBlank()) {
                            insertAgentMessage(sessionId, "请先发送一张图片，再说“帮我优化这张照片”", currentModelLabel(), performance)
                        } else {
                            handleAiOptimize(sessionId, targetUri, cmd.explanation, currentModelLabel(), performance)
                        }
                    }
```

并在 `handleAgentAction` 之后新增两个私有方法：

```kotlin
    /**
     * AI 优化：抽卡闭环（候选卡组消息）；控制器未注入时退回旧单发路径。
     * spec: docs/superpowers/specs/2026-08-06-chat-optimize-gacha-design.md
     */
    private suspend fun handleAiOptimize(
        sessionId: String,
        targetUri: String,
        explanationOverride: String?,
        modelUsed: String,
        performance: LlmPerformance?
    ) {
        val controller = optimizeGachaController
        if (controller == null) {
            legacyAiOptimize(sessionId, targetUri, explanationOverride, modelUsed, performance)
            return
        }
        val messageId = UUID.randomUUID().toString()
        when (val outcome = controller.draw(messageId, targetUri, sessionId)) {
            is ChatOptimizeGachaController.DrawOutcome.Candidates -> {
                insertOptimizeCandidatesMessage(
                    sessionId = sessionId,
                    messageId = messageId,
                    group = outcome.group,
                    content = explanationOverride ?: outcome.explanation,
                    modelUsed = modelUsed
                )
            }
            is ChatOptimizeGachaController.DrawOutcome.Fallback -> {
                if (outcome.imageUri != null) {
                    insertAgentImageMessage(
                        sessionId = sessionId,
                        imageUri = outcome.imageUri,
                        content = explanationOverride ?: outcome.explanation,
                        modelUsed = modelUsed,
                        performance = performance
                    )
                } else {
                    insertAgentMessage(sessionId, outcome.explanation, modelUsed, performance)
                }
            }
        }
    }

    /** 抽卡控制器未注入时的旧单发路径（与抽卡接入前行为一致）。 */
    private suspend fun legacyAiOptimize(
        sessionId: String,
        targetUri: String,
        explanationOverride: String?,
        modelUsed: String,
        performance: LlmPerformance?
    ) {
        val renderer = chatImageRenderer
        if (renderer == null) {
            insertAgentMessage(sessionId, "⚠️ 图像优化暂不可用", modelUsed, performance)
            return
        }
        val outcome = renderer.aiOptimize(targetUri, sessionId)
        Logger.i(TAG, "AiOptimize outcome (legacy): imageUri=${outcome.imageUri}, explanation=${outcome.explanation}")
        if (outcome.imageUri != null) {
            insertAgentImageMessage(sessionId, outcome.imageUri, explanationOverride ?: outcome.explanation, modelUsed, performance)
        } else {
            insertAgentMessage(sessionId, outcome.explanation, modelUsed, performance)
        }
    }
```

**(d) 卡条消息插入与三个回调**（放在 `insertEditResultMessage` 之后）：

```kotlin
    /** 插入候选卡组消息（type=optimize_candidates），并按推荐卡初始化选中态。 */
    @VisibleForTesting
    internal suspend fun insertOptimizeCandidatesMessage(
        sessionId: String,
        messageId: String,
        group: OptimizeCandidateGroup,
        content: String,
        modelUsed: String
    ) {
        chatMessageDao.insertMessage(
            ChatMessageEntity(
                id = messageId,
                sessionId = sessionId,
                type = OptimizeCandidateGroup.MESSAGE_TYPE,
                content = content,
                modelUsed = modelUsed,
                metadata = group.toJson()
            )
        )
        chatSessionDao.touchSession(sessionId)
        _gachaSelections.value = _gachaSelections.value + (messageId to group.recommendedIndex)
    }

    /** 点选候选卡（同时触发全屏预览，由 UI 侧处理）。 */
    fun onOptimizeGachaSelection(messageId: String, index: Int) {
        _gachaSelections.value = _gachaSelections.value + (messageId to index)
    }

    /**
     * 换一组：重抽并覆写该条消息。
     *
     * @param onResult true=成功；false=不可用（UI toast，卡条保持）
     */
    fun onOptimizeGachaReroll(messageId: String, onResult: (Boolean) -> Unit) {
        val controller = optimizeGachaController ?: return
        viewModelScope.launch {
            when (val outcome = controller.reroll(messageId)) {
                is ChatOptimizeGachaController.RerollOutcome.Rerolled -> {
                    chatMessageDao.getMessageById(messageId)?.let { entity ->
                        chatMessageDao.insertMessage(
                            entity.copy(content = outcome.explanation, metadata = outcome.group.toJson())
                        )
                    }
                    _gachaSelections.value = _gachaSelections.value + (messageId to outcome.group.recommendedIndex)
                    onResult(true)
                }
                ChatOptimizeGachaController.RerollOutcome.Expired,
                ChatOptimizeGachaController.RerollOutcome.Unavailable -> onResult(false)
            }
        }
    }

    /**
     * 就用这张：全尺寸渲染 → 该条消息改写为 agent_image 结果消息（复用 insert-replace 模式）。
     *
     * @param onResult true=成功；false=失败（UI toast，卡条保持可重试）
     */
    fun onOptimizeGachaConfirm(messageId: String, candidateIndex: Int, onResult: (Boolean) -> Unit) {
        val controller = optimizeGachaController ?: return
        viewModelScope.launch {
            val result = controller.confirm(messageId, candidateIndex)
            if (result == null) {
                onResult(false)
                return@launch
            }
            chatMessageDao.getMessageById(messageId)?.let { entity ->
                val metadata = JSONObject().apply {
                    put("imageUri", result.imageUri)
                    put("saved", false)
                }.toString()
                chatMessageDao.insertMessage(
                    entity.copy(type = "agent_image", metadata = metadata)
                )
            }
            _gachaSelections.value = _gachaSelections.value - messageId
            onResult(true)
        }
    }
```

**(e) dismiss 拦截**——新增私有方法并在 5 个入口调用：

```kotlin
    /** 废弃当前会话的 pending 卡条（落库 dismiss）；在用户发新消息/切会话等打断点调用。 */
    private suspend fun discardPendingOptimizeGacha() {
        optimizeGachaController?.discardPending(_currentSessionId.value)
    }
```

调用点：
1. `sendMessage`（:1069 `val sessionId = _currentSessionId.value` 之后、`ensureSessionExists` 之前）加一行：`discardPendingOptimizeGacha()`
2. `switchSession`（:954）函数体第一行（`_currentSessionId.value = sessionId` 之前）改为：
   ```kotlin
       fun switchSession(sessionId: String) {
           viewModelScope.launch { discardPendingOptimizeGacha() }
           _currentSessionId.value = sessionId
   ```
3. `newSession`（:967 launch 块内 try 之前）：`discardPendingOptimizeGacha()`
4. `deleteSession`（:1005 try 块第一行）：`optimizeGachaController?.discardPending(sessionId)`
5. `clearChat`（:2430 `val sessionId = ...` 之后）：`optimizeGachaController?.discardPending(sessionId)`

**(f) toUiModel 解析**（:2464-2494）：
- `when (type)` 里加 `"optimize_candidates" -> ChatMessageType.OPTIMIZE_CANDIDATES`（用常量：`OptimizeCandidateGroup.MESSAGE_TYPE -> ChatMessageType.OPTIMIZE_CANDIDATES`）
- `ChatMessageUi(...)` 构造里加两个字段：

```kotlin
            optimizeCandidates = if (type == OptimizeCandidateGroup.MESSAGE_TYPE) {
                OptimizeCandidateGroup.fromJson(metadata)
            } else {
                null
            },
            gachaInteractive = type == OptimizeCandidateGroup.MESSAGE_TYPE &&
                optimizeGachaController?.hasPending(id) == true,
```

并在 `ChatViewModelTestBase` 加受保护重载（若基类已有类似扩展点则沿用）：

```kotlin
    protected open fun newViewModelWithGacha(
        controller: ChatOptimizeGachaController
    ): ChatViewModel = ChatViewModel(
        ChatViewModelDependencies(
            // 与 newViewModel() 完全相同的参数，
            // 末尾追加 optimizeGachaController = controller
        )
    )
```

- [ ] **Step 4: 跑测试确认通过（含既有 chat 测试不回归）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.*"`
Expected: PASS（新 5 用例 + 既有全部）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt app/src/test/java/com/mamba/picme/features/chat/
git commit -m "feat(chat): wire optimize gacha into ChatViewModel message flow"
```

---

## Task 4: 卡条 UI + ChatScreen 接线 + i18n

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/components/GachaCandidateStrip.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

- [ ] **Step 1: 新增文案（5 个 key × 4 语言文件）**

放在各文件 `ai_optimize_*` 条目附近，风格对齐：

| key | values (en) | values-zh / values-zh-rCN | values-zh-rTW |
|-----|-------------|---------------------------|----------------|
| `chat_gacha_use_this` | Use this | 就用这张 | 就用這張 |
| `chat_gacha_pick_hint` | Tap a card to preview it, then confirm | 点选一张候选预览，确认后应用 | 點選一張候選預覽，確認後套用 |
| `chat_gacha_expired` | This candidate set has expired — start a new optimization | 该组候选已过期，可重新发起优化 | 該組候選已過期，可重新發起優化 |
| `chat_gacha_confirm_failed` | Failed to apply, please try again | 应用失败，请重试 | 套用失敗，請重試 |
| `chat_gacha_reroll_unavailable` | Shuffling is unavailable right now | 换一组暂不可用，请稍后再试 | 換一組暫不可用，請稍後再試 |

> `ai_optimize_recommended` / `ai_optimize_reroll` / `ai_optimize_keep_hint` 直接复用，不新增。

- [ ] **Step 2: 实现 GachaCandidateStrip.kt**

```kotlin
package com.mamba.picme.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.features.chat.OptimizeCandidateGroup

/**
 * chat 对话内的 AI 优化候选卡条（spec §3.2）。
 *
 * - 点卡 = 选中高亮 + 全屏预览（预览由调用方处理）
 * - 「就用这张」：有选中卡且该卡未被护栏淘汰时可用
 * - [interactive] = false（进程重建后内存态丢失）时降级只读：隐藏按钮，提示已过期
 */
@Composable
fun GachaCandidateStrip(
    group: OptimizeCandidateGroup,
    interactive: Boolean,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onReroll: () -> Unit,
    onConfirm: () -> Unit
) {
    val hint = when {
        !interactive -> stringResource(R.string.chat_gacha_expired)
        group.recommendedIndex < 0 -> stringResource(R.string.ai_optimize_keep_hint)
        else -> stringResource(R.string.chat_gacha_pick_hint)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                group.candidates.forEachIndexed { index, candidate ->
                    CandidateCard(
                        candidate = candidate,
                        recommended = index == group.recommendedIndex,
                        selected = index == selectedIndex,
                        enabled = interactive && !candidate.rejected,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (interactive) {
                val selectedRejected = group.candidates
                    .getOrNull(selectedIndex)?.rejected != false
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onReroll) {
                        Text(stringResource(R.string.ai_optimize_reroll))
                    }
                    TextButton(
                        onClick = onConfirm,
                        enabled = selectedIndex >= 0 && !selectedRejected
                    ) {
                        Text(stringResource(R.string.chat_gacha_use_this))
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: OptimizeCandidateGroup.Candidate,
    recommended: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                )
                .alpha(if (candidate.rejected) 0.4f else 1f)
                .clickable(enabled = enabled, onClick = onClick)
        ) {
            if (candidate.thumbPath.isNotBlank()) {
                AsyncImage(
                    model = candidate.thumbPath,
                    contentDescription = candidate.direction,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surface)
                )
            }
            if (recommended) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(bottomEnd = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ai_optimize_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
        Text(
            text = candidate.direction,
            style = MaterialTheme.typography.labelSmall,
            color = if (candidate.rejected) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
```

- [ ] **Step 3: ChatScreen.kt 改动（4 处）**

**(a) `ChatMessageType` 枚举**（:2330）加 `OPTIMIZE_CANDIDATES`：

```kotlin
enum class ChatMessageType {
    USER_TEXT, AGENT_TEXT, USER_IMAGE, USER_IMAGE_TEXT,
    AGENT_IMAGE, AGENT_EDIT_RESULT, COMMAND, PLAN_PREVIEW,
    MEDIA_RESULTS, CHART, OPTIMIZE_CANDIDATES
}
```

**(b) `ChatMessageUi`**（:2304）加两个字段：

```kotlin
    val optimizeCandidates: OptimizeCandidateGroup? = null,
    val gachaInteractive: Boolean = false,
```

**(c) 收集选中态**：在 `ChatScreen` 内其他 `collectAsState()` 附近加：

```kotlin
    val gachaSelections by viewModel.gachaSelections.collectAsState()
```

**(d) 消息分发分支**（:510-548 的 `if (message.type == ChatMessageType.MEDIA_RESULTS ...)` 链中插入新分支）：

```kotlin
    } else if (message.type == ChatMessageType.OPTIMIZE_CANDIDATES && message.optimizeCandidates != null) {
        val group = message.optimizeCandidates!!
        val selected = gachaSelections[message.id] ?: group.recommendedIndex
        GachaCandidateStrip(
            group = group,
            interactive = message.gachaInteractive,
            selectedIndex = selected,
            onSelect = { index ->
                viewModel.onOptimizeGachaSelection(message.id, index)
                // 点卡 = 选中 + 全屏预览该组候选（isEditableResult=false → 无保存按钮）
                val pages = group.candidates.mapIndexedNotNull { i, c ->
                    c.thumbPath.takeIf { it.isNotBlank() }?.let { path ->
                        ImagePreviewPage(
                            messageId = "${message.id}#$i",
                            rawUri = path,
                            isEditableResult = false,
                            isSaved = false
                        )
                    }
                }
                if (pages.isNotEmpty()) {
                    val startAt = pages.indexOfFirst { it.messageId == "${message.id}#$index" }
                        .coerceAtLeast(0)
                    imagePreview = ChatImagePreviewState(pages = pages, initialIndex = startAt)
                }
            },
            onReroll = {
                viewModel.onOptimizeGachaReroll(message.id) { ok ->
                    if (!ok) Toast.makeText(context, rerollUnavailableText, Toast.LENGTH_SHORT).show()
                }
            },
            onConfirm = {
                viewModel.onOptimizeGachaConfirm(message.id, selected) { ok ->
                    if (!ok) Toast.makeText(context, confirmFailedText, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
```

分支前在组合函数作用域加两个文案变量（与 `expiredToast` 同款写法，:2503 参考）：

```kotlin
    val rerollUnavailableText = stringResource(R.string.chat_gacha_reroll_unavailable)
    val confirmFailedText = stringResource(R.string.chat_gacha_confirm_failed)
```

同时补 import：`com.mamba.picme.features.chat.components.GachaCandidateStrip`、`com.mamba.picme.features.chat.OptimizeCandidateGroup`（`Toast` / `ImagePreviewPage` / `ChatImagePreviewState` 本文件已有）。

- [ ] **Step 4: 编译 + 全量单测**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.*"`
Expected: BUILD SUCCESSFUL + PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/components/GachaCandidateStrip.kt app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(chat): add gacha candidate strip UI with preview and confirm"
```

---

## Task 5: DI 组装 + 文档同步 + 真机闭环

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/di/AppContainer.kt`
- Modify: `docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md`

- [ ] **Step 1: AppContainer 组装**

`AppContainer.kt` 在 `chatImageRenderer`（:639）之后新增：

```kotlin
private val chatOptimizeGachaController: ChatOptimizeGachaController by lazy {
    ChatOptimizeGachaController(
        optimizeUseCase = aiOptimizeUseCase,
        chatImageRenderer = chatImageRenderer,
        chatImageStore = chatImageStore,
        feedbackLogger = optimizeFeedbackLogger,
        chatEditStateHolder = chatEditStateHolder
    )
}
```

`chatViewModelDependencies`（:653）的 `ChatViewModelDependencies(...)` 参数列表末尾加：

```kotlin
        optimizeGachaController = chatOptimizeGachaController
```

（`optimizeFeedbackLogger` :395、`chatEditStateHolder` :493、`chatImageStore` 均已存在，直接引用。）

- [ ] **Step 2: 编译 + 全量单测**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL + 全部 PASS（重点回归：既有 chat 测试、editor gacha 测试）

- [ ] **Step 3: 文档同步**

`docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md` 追加一节：

```markdown
## Chat 页抽卡（2026-08-06）

chat 内 AI 优化指令同样走抽卡闭环（复用 `optimizeWithGacha`，domain 引擎零改动）：
结果以对话内候选卡条呈现（type=optimize_candidates 消息，metadata 存展示数据，
候选 preset 在 `ChatOptimizeGachaController` 进程级内存态），支持「换一组」去重重抽、
点卡全屏预览、显式「就用这张」确认——确认后全尺寸渲染并折叠为普通 agent_image 结果消息，
选中 recipe 写入 `ChatEditStateHolder` 支撑多轮 delta 续调。
NIMA 不可用 / 缩略图落盘全失败时退回原固定预设单发路径。
反馈经 `OptimizeFeedbackLogger` 落库（auto/user/dismiss），与编辑器抽卡共用 optimize_feedback 表。
详见 `docs/superpowers/specs/2026-08-06-chat-optimize-gacha-design.md`。
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/di/AppContainer.kt docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md
git commit -m "feat(chat): wire gacha controller in AppContainer + docs"
```

- [ ] **Step 5: 真机闭环验证**

按 AGENTS.md 闭环习惯（`./scripts/auto-dev-loop.sh` 或手动编译→安装→验证），逐项验证：

- [ ] chat 发图 → 输入「帮我优化这张照片」→ 出现候选卡条（4 卡 + 推荐徽标 + 两个按钮）
- [ ] 点卡 → 选中高亮 + 全屏预览（无保存按钮）
- [ ] 「换一组」→ 卡条内容更新（新组合，无重复）
- [ ] 「就用这张」→ 卡条折叠为普通结果图消息；再说「再亮一点」→ 基于选中卡继续调整
- [ ] KeepOriginal 场景（找一张已很好的图）→ 提示「AI 认为原图已很好」，按钮初始禁用，点选后可用
- [ ] 卡条 pending 时发送新消息 → 再回来卡条仍在（消息已持久化）
- [ ] 杀进程重进 → 卡条降级只读（无按钮 + 过期提示）
- [ ] NIMA 模型未下载（删模型或新装机）→ 退回单发结果图（与旧行为一致）
