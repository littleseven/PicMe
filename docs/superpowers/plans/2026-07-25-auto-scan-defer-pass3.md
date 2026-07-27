# 自动扫描两阶段链式 + Pass3 流控 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 自动扫描先全量跑 Pass1+Pass2、全部完成后再遍历 Pass3；并为连续 Pass3 加入自适应散热流控、修复失效的 guardCheck 热保护。

**Architecture:** 在 `ScanQueuePolicy` 增加 `deferredPasses` 表达延迟阶段，`scheduleAutoScan` 在候选耗尽时切换阶段（复用现有批次链式调度，零改 `runSession`）；把 `TagGenerationScheduler.guardCheck()` 接回 `executeQwenTagging` 并在每张 Pass3 后按热状态自适应 `delay`。

**Tech Stack:** Kotlin + Coroutines + Room + PowerManager thermal API；测试 mockk + JUnit4 纯 JVM 单测。

**Spec:** `docs/superpowers/specs/2026-07-25-auto-scan-defer-pass3-design.md`

**验证命令（本环境真门槛=编译+JVM单测）:**
- 编译：`./gradlew :app:assembleDebug`
- 单测：`./gradlew :app:testDebugUnitTest`
- 单个测试类：`./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.*"`

**约定:**
- 禁止 `com.mamba.picme.*` 全限定名（用 import）；禁止 wildcard import；lambda 参数显式命名；4 空格缩进。
- 每个任务末尾提交一次（Conventional Commits）。commit message 结尾加 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `app/src/main/java/com/mamba/picme/domain/tag/scan/ScanQueuePolicy.kt` | 扫描策略数据类 | 改：`passes` 默认值 + 新增 `deferredPasses` |
| `app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt` | 扫描编排器 | 改：新增 `nextPhasePolicy` 纯函数；`scheduleAutoScan` 阶段切换 + 移除多余实体加载 |
| `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt` | 原子任务执行 | 改：构造函数新增 `getPass3CooldownMs`；`executeQwenTagging` 接 `guardCheck` + 末尾 `delay` |
| `app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt` | 前台 Service | 改：新增 `PASS3_COOLDOWN_BY_THERMAL` 常量 + `getPass3CooldownMs()`；构造 scheduler 注入 |
| `app/src/test/java/com/mamba/picme/domain/tag/scan/ScanQueuePolicyTest.kt` | 默认值断言 | 新建 |
| `app/src/test/java/com/mamba/picme/domain/tag/TagScanOrchestratorTest.kt` | 阶段切换纯函数单测 | 扩展 |

---

## Task 1: `ScanQueuePolicy` 新增 `deferredPasses` + 默认值单测

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/scan/ScanQueuePolicy.kt`
- Create: `app/src/test/java/com/mamba/picme/domain/tag/scan/ScanQueuePolicyTest.kt`

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/mamba/picme/domain/tag/scan/ScanQueuePolicyTest.kt`:

```kotlin
package com.mamba.picme.domain.tag.scan

import com.mamba.picme.data.local.entity.TagScanPass
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanQueuePolicyTest {

    @Test
    fun `default policy runs pass1 and pass2 in first phase`() {
        val policy = ScanQueuePolicy()
        assertEquals(
            listOf(TagScanPass.FACE_DETECTION, TagScanPass.DBSCAN),
            policy.passes
        )
    }

    @Test
    fun `default policy defers pass3 to second phase`() {
        val policy = ScanQueuePolicy()
        assertEquals(
            listOf(TagScanPass.IMAGE_TAGGING),
            policy.deferredPasses
        )
    }

    @Test
    fun `conservative preset inherits two-phase defaults`() {
        val policy = ScanQueuePolicy.conservative()
        assertEquals(listOf(TagScanPass.IMAGE_TAGGING), policy.deferredPasses)
    }

    @Test
    fun `overnight preset inherits two-phase defaults`() {
        val policy = ScanQueuePolicy.overnight()
        assertEquals(listOf(TagScanPass.IMAGE_TAGGING), policy.deferredPasses)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.scan.ScanQueuePolicyTest"`
Expected: 编译失败 —— `deferredPasses` 未定义。

- [ ] **Step 3: 实现 `deferredPasses` 字段**

Modify `app/src/main/java/com/mamba/picme/domain/tag/scan/ScanQueuePolicy.kt`，把 `passes` 默认值改为只含 Pass1+Pass2，并新增 `deferredPasses`：

```kotlin
    /** 本次扫描第一阶段立即执行的 Pass */
    val passes: List<TagScanPass> = listOf(
        TagScanPass.FACE_DETECTION,
        TagScanPass.DBSCAN
    ),

    /** 第一阶段全量完成后才执行的 Pass（延迟阶段）。
     *  阶段切换由 TagScanOrchestrator.scheduleAutoScan 在第一批次链式耗尽时自动触发。 */
    val deferredPasses: List<TagScanPass> = listOf(
        TagScanPass.IMAGE_TAGGING
    ),
```

> `conservative()` / `overnight()` 用命名参数构造、未指定 `passes` / `deferredPasses`，自动继承新默认值，**无需改动**。

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.scan.ScanQueuePolicyTest"`
Expected: PASS（4 个测试全绿）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/scan/ScanQueuePolicy.kt \
        app/src/test/java/com/mamba/picme/domain/tag/scan/ScanQueuePolicyTest.kt
git commit -m "feat(scan): ScanQueuePolicy 拆分 deferredPasses，Pass3 延迟到第二阶段

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: 阶段切换纯函数 `nextPhasePolicy` + 单测（TDD）

把"是否切换阶段、切换后的 policy"提取为 companion 纯函数，单测覆盖（含防死循环）。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt`（companion object）
- Modify: `app/src/test/java/com/mamba/picme/domain/tag/TagScanOrchestratorTest.kt`

- [ ] **Step 1: 写失败测试**

在 `TagScanOrchestratorTest.kt` 末尾 `}` 之前追加（文件已 import `TagScanPass`、`TagScanOrchestrator`、`assertEquals`/`assertNull`；需补 `import com.mamba.picme.domain.tag.scan.ScanQueuePolicy` 与 `assertNull`）：

```kotlin
    @Test
    fun `nextPhasePolicy returns second-phase policy when deferredPasses non-empty`() {
        val policy = ScanQueuePolicy() // passes=[Pass1,Pass2], deferredPasses=[Pass3]
        val next = TagScanOrchestrator.nextPhasePolicy(policy)

        assertNotNull(next)
        assertEquals(listOf(TagScanPass.IMAGE_TAGGING), next!!.passes)
        // 防死循环：第二阶段不再有延迟阶段
        assertTrue(next.deferredPasses.isEmpty())
    }

    @Test
    fun `nextPhasePolicy returns null when deferredPasses empty`() {
        val policy = ScanQueuePolicy(
            passes = listOf(TagScanPass.FACE_DETECTION),
            deferredPasses = emptyList()
        )
        assertNull(TagScanOrchestrator.nextPhasePolicy(policy))
    }

    @Test
    fun `nextPhasePolicy on second-phase policy returns null (no third phase)`() {
        // 模拟第二阶段 policy（deferredPasses 已空）
        val secondPhase = TagScanOrchestrator.nextPhasePolicy(ScanQueuePolicy())!!
        assertNull(TagScanOrchestrator.nextPhasePolicy(secondPhase))
    }
```

补 import（文件顶部）：

```kotlin
import com.mamba.picme.domain.tag.scan.ScanQueuePolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.TagScanOrchestratorTest"`
Expected: 编译失败 —— `nextPhasePolicy` 未定义。

- [ ] **Step 3: 实现 `nextPhasePolicy`**

在 `TagScanOrchestrator.kt` 的 `companion object { ... }` 内（紧挨 `isPassesCovered` 之后）添加：

```kotlin
        /**
         * 计算自动扫描的阶段切换策略。
         *
         * - [ScanQueuePolicy.deferredPasses] 非空：返回第二阶段 policy（passes=deferredPasses，
         *   deferredPasses 清空）。第二阶段再调用必返回 null，保证只会切换一次（防死循环）。
         * - [ScanQueuePolicy.deferredPasses] 为空：返回 null，表示无后续阶段。
         */
        fun nextPhasePolicy(policy: ScanQueuePolicy): ScanQueuePolicy? {
            if (policy.deferredPasses.isEmpty()) return null
            return policy.copy(
                passes = policy.deferredPasses,
                deferredPasses = emptyList()
            )
        }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.TagScanOrchestratorTest"`
Expected: PASS（新增 3 个 + 原有全绿）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt \
        app/src/test/java/com/mamba/picme/domain/tag/TagScanOrchestratorTest.kt
git commit -m "feat(scan): 新增 nextPhasePolicy 纯函数表达两阶段切换

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: `scheduleAutoScan` 接入阶段切换 + 移除多余实体加载

把 `nextPhasePolicy` 接入 `media.isEmpty()` 分支；同时移除多余的完整 `MediaEntity` 加载（`createTasks` 只用 id）。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt:193-234`（`scheduleAutoScan`）

- [ ] **Step 1: 替换 `scheduleAutoScan` 的候选处理段**

把现有这段（约 206–234 行）：

```kotlin
        val filteredIds = projections
            .filter { !isPassesCovered(it.lastTagScanPasses, requestedPassNumbers) }
            .take(policy.maxBatchSize)
            .map { it.id }

        // 仅对最终入选的媒体加载完整实体，保持与投影查询一致的顺序。
        val media = if (filteredIds.isEmpty()) {
            emptyList()
        } else {
            db.mediaDao().getMediaByIds(filteredIds).sortedBy { media ->
                filteredIds.indexOf(media.id)
            }
        }

        if (media.isEmpty()) {
            logInfo(sessionId, "没有需要增量扫描的媒体")
            _progress.value = TagScanSessionProgress(
                sessionId = sessionId,
                state = ScanSessionState.COMPLETED,
                messages = listOf(ScanMessage(level = MessageLevel.INFO, text = "没有需要扫描的媒体"))
            )
            return sessionId
        }

        createTasks(sessionId, media.map { it.id }, TagCategory.ALL, policy.passes, policy)
        sessionPolicies[sessionId] = policy
        startSession(sessionId)
        return sessionId
```

替换为：

```kotlin
        val filteredIds = projections
            .filter { !isPassesCovered(it.lastTagScanPasses, requestedPassNumbers) }
            .take(policy.maxBatchSize)
            .map { it.id }

        if (filteredIds.isEmpty()) {
            // 第一阶段全量完成：若有延迟阶段，切换到第二阶段（递归一层，第二阶段 deferredPasses 已空 → 不会死循环）
            val nextPolicy = nextPhasePolicy(policy)
            if (nextPolicy != null) {
                logInfo(sessionId, "延迟阶段切换: ${policy.passes} 全量完成 → 进入 ${nextPolicy.passes}")
                return scheduleAutoScan(nextPolicy)
            }
            logInfo(sessionId, "没有需要增量扫描的媒体")
            _progress.value = TagScanSessionProgress(
                sessionId = sessionId,
                state = ScanSessionState.COMPLETED,
                messages = listOf(ScanMessage(level = MessageLevel.INFO, text = "没有需要扫描的媒体"))
            )
            return sessionId
        }

        // createTasks 只需 mediaId；不再加载完整 MediaEntity（含 faceRoiResult/semanticEmbedding 大字段），降低 Heap 峰值。
        createTasks(sessionId, filteredIds, TagCategory.ALL, policy.passes, policy)
        sessionPolicies[sessionId] = policy
        startSession(sessionId)
        return sessionId
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。若报 `getMediaByIds` 未使用属于预期（投影查询已覆盖）。

- [ ] **Step 3: 跑全量单测，确认无回归**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（Task 1/2 测试 + 既有测试全绿）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt
git commit -m "feat(scan): scheduleAutoScan 两阶段切换 + 移除多余 MediaEntity 加载

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: Service 新增 Pass3 cooldown 常量与取值 + scheduler 注入 lambda

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt`（companion + 私有方法 + onCreate 构造）

- [ ] **Step 1: 在 `companion object` 内增加常量**

在 `TagGenerationService.kt` 的 `companion object { ... }` 内（`BATTERY_CRITICAL_THRESHOLD` 之后）添加：

```kotlin
        /** Pass3 流控：每张推理后的散热间歇（毫秒），随热状态递增（平衡档）。
         *  SEVERE 及以上由 [checkGuard] ABORT 兜底，不在此表。 */
        val PASS3_COOLDOWN_BY_THERMAL: Map<Int, Long> = mapOf(
            PowerManager.THERMAL_STATUS_NONE to 800L,
            PowerManager.THERMAL_STATUS_LIGHT to 1_500L,
            PowerManager.THERMAL_STATUS_MODERATE to 3_000L
        )
        const val PASS3_COOLDOWN_DEFAULT_MS = 800L
```

- [ ] **Step 2: 增加 `getPass3CooldownMs()` 私有方法**

在 `getAdaptiveThrottleMs()` 之后添加：

```kotlin
    /**
     * Pass3 每张推理后的散热间歇：随热状态递增。
     * 凉机轻间歇（控温为主），发热明显拉长；SEVERE 及以上由 [checkGuard] ABORT。
     */
    private fun getPass3CooldownMs(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(PowerManager::class.java)
            return PASS3_COOLDOWN_BY_THERMAL[pm.currentThermalStatus] ?: PASS3_COOLDOWN_DEFAULT_MS
        }
        return PASS3_COOLDOWN_DEFAULT_MS
    }
```

- [ ] **Step 3: 构造 scheduler 时注入**

把 `onCreate()` 里构造 scheduler 的代码（约 268–273 行）：

```kotlin
        val sched = TagGenerationScheduler(
            context = this,
            dispatcher = taskDispatcher,
            guard = { checkGuard() },
            getThrottleMs = { getAdaptiveThrottleMs() }
        )
```

改为：

```kotlin
        val sched = TagGenerationScheduler(
            context = this,
            dispatcher = taskDispatcher,
            guard = { checkGuard() },
            getThrottleMs = { getAdaptiveThrottleMs() },
            getPass3CooldownMs = { getPass3CooldownMs() }
        )
```

- [ ] **Step 4: 编译验证（预期失败：scheduler 构造函数尚未有该参数）**

Run: `./gradlew :app:assembleDebug`
Expected: 编译失败 —— `TagGenerationScheduler` 无 `getPass3CooldownMs` 参数。Task 5 会修好。

> 说明：本任务与 Task 5 紧耦合，中间状态不可编译是预期。若希望每个任务独立可编译，可把 Task 4 与 Task 5 合并执行。

- [ ] **Step 5: 暂不提交（等 Task 5 一起提交）**

---

## Task 5: scheduler 构造函数加 `getPass3CooldownMs` + `executeQwenTagging` 接 guardCheck 与 cooldown

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt`（构造函数 + `executeQwenTagging`）

- [ ] **Step 1: 构造函数新增 lambda 参数**

在 `TagGenerationScheduler` 主构造函数（约 64–73 行）的 `getThrottleMs` 之后加一行：

```kotlin
    private val getThrottleMs: () -> Long = { 1000L },
    private val getPass3CooldownMs: () -> Long = { 800L },
```

- [ ] **Step 2: `executeQwenTagging` 开头接 `guardCheck`**

把 `executeQwenTagging`（约 1016 行起）的开头：

```kotlin
    suspend fun executeQwenTagging(mediaId: Long) {
        val dao = db.mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return

        val startMs = System.currentTimeMillis()
```

改为：

```kotlin
    suspend fun executeQwenTagging(mediaId: Long) {
        // 接回守卫：热 SEVERE / 电量危机时 ABORT，抛异常 → 任务 FAILED → handleTaskFailure 退避重试（自带散热窗口）。
        // 热 MODERATE / 电量低时 guardCheck 内部已 delay(getThrottleMs())，不抛异常。
        if (!guardCheck()) {
            throw IllegalStateException("[Pass 3] Guard ABORT (thermal/battery) mediaId=$mediaId")
        }

        val dao = db.mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return

        val startMs = System.currentTimeMillis()
```

- [ ] **Step 3: `executeQwenTagging` 末尾加自适应 cooldown**

把该方法末尾的 `Log.d(TAG, "[Benchmark] Pass 3 ...")` 之后（方法闭合 `}` 之前）追加：

```kotlin
        Log.d(TAG, "[Benchmark] Pass 3 (Qwen) done: mediaId=$mediaId, " +
            "durationMs=${System.currentTimeMillis() - startMs}, tags=${qwenResult.tags}")

        // Pass3 连续执行发热严重：每张推理后自适应散热（热状态越高间歇越长）。
        // SEVERE 及以上已由上面的 guardCheck ABORT 兜底，不会走到这里。
        delay(getPass3CooldownMs())
    }
```

> `delay` 已在文件顶部 import（`kotlinx.coroutines.delay`，line 28）；`guardCheck()` 是同类内既有私有方法（line 308）。

- [ ] **Step 4: 编译验证（此时 Task 4 + 5 应共同通过）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 跑全量单测，确认无回归**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS。

- [ ] **Step 6: 提交（Task 4 + 5 合并提交）**

```bash
git add app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt \
        app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
git commit -m "feat(scan): Pass3 接回 guardCheck 热保护 + 自适应 cooldown 散热

修复 guardCheck 全仓库零调用的 dead code；executeQwenTagging 每张后
按 thermal status 自适应 delay（平衡档 800ms/1.5s/3s）。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: 设备端验证 + 文档同步

**Files:**
- Verify on device
- Possibly update: `docs/01-PRODUCT/FEATURES.md`

- [ ] **Step 1: 安装到设备**

Run: `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/polang-debug.apk`
Expected: 安装成功。

- [ ] **Step 2: 触发自动扫描，观察两阶段切换**

Run（开一个终端盯日志）:
```bash
adb logcat -c
adb logcat -s "TagScanOrchestrator:*" "TagGenService:*" "TagGenerationScheduler:*"
```
在设备上进入相册（或用 `/agent-test` 的 `scan_incremental` 触发），确认日志顺序：
1. 先持续出现 `Pass 1` / DBSCAN 相关日志，**不出现** `[Benchmark] Pass 3`。
2. 第一阶段耗尽后出现 `延迟阶段切换: [FACE_DETECTION, DBSCAN] 全量完成 → 进入 [IMAGE_TAGGING]`。
3. 之后才开始 `[Benchmark] Pass 3 (Qwen) done: ...`。

Expected: 两阶段顺序符合预期，Pass1+Pass2 先全量完成。

- [ ] **Step 3: 观察 Pass3 流控与温度**

```bash
# 另开终端，每 10s 采样热状态与温度
adb shell "while true; do dumpsys thermalservice | grep -E 'status|temperature'; sleep 10; done"
```
确认：
- Pass3 期间 `currentThermalStatus` 上升后，logcat 中 `[Benchmark] Pass 3` 的 `durationMs` 间隔变大（cooldown 生效）。
- 温度不再失控爬升（平衡档可接受）。
- 模拟发热（或长时间跑）到 `SEVERE` 时，出现 `[Pass 3] Guard ABORT` + 任务 FAILED 退避（不崩溃）。

- [ ] **Step 4: 检查文档同步**

检查 `docs/01-PRODUCT/FEATURES.md` 是否描述「自动扫描流程 / Pass 顺序」。若有，更新为两阶段表述（Pass1+Pass2 全量 → Pass3 遍历）。

若无需改动，跳过；若有改动：

```bash
git add docs/01-PRODUCT/FEATURES.md
git commit -m "docs: 自动扫描流程更新为两阶段（Pass1+Pass2 → Pass3）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 5: 最终全量验证**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL + 全部单测 PASS。

---

## Self-Review 结论

- **Spec 覆盖**：改动 1→Task 1；改动 2→Task 2+3；改动 3（进度可见性）→ Task 3 的 `logInfo` 已含；改动 4（附带优化）→ Task 3；改动 5（guardCheck 修复）→ Task 5；改动 6（cooldown）→ Task 4+5。全部覆盖。
- **Placeholder**：无 TBD/TODO；每个代码步骤含完整代码。
- **类型一致**：`nextPhasePolicy` 在 Task 2 定义、Task 3 调用，签名一致；`getPass3CooldownMs` 在 Task 4（Service）与 Task 5（scheduler lambda）命名一致。
- **已知边界**：Task 4 中间态不可独立编译（与 Task 5 紧耦合），已在 Task 4 Step 4 注明，可合并执行。
