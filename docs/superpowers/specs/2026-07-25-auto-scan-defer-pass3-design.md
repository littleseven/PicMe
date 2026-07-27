# 自动扫描两阶段链式 + Pass3 流控设计

- **日期**: 2026-07-25
- **状态**: 已批准（待实现）
- **范围**: `:app` 模块，`domain/tag/scan/` + `domain/tag/` + `service/tag/`
- **关联**: 记忆 `batch-mlkit-on-demand-summary-progress`（Pass3 治发热历史方案）

## 背景

### 问题一：Pass3 拖慢每批，Pass1+Pass2 结果迟迟不可见

用户进入应用后，`GalleryScreen`（首次启动 或 充电且 23:00–06:00 夜间）触发 `intentScanIncremental` → `TagGenerationService` → `TagScanOrchestrator.scheduleAutoScan(ScanQueuePolicy())`。

当前 `ScanQueuePolicy.passes` 默认含 `[FACE_DETECTION, DBSCAN, IMAGE_TAGGING]`，`createTasks` 给三者打 priority `0/1/2`，`runSession` 用 `pollNextPendingBySession` 按 priority 轮询。结果是**每批 50 张媒体内**顺序跑 Pass1 → Pass2 → Pass3。Pass3（Qwen/SmolVLM，`DEFAULT_PASS_DURATION_MS` 估 ~7s/张）使每批耗时约 350s，且阻塞下一批的 Pass1。

同样走 `scheduleAutoScan` 的入口还有：相册页手动「扫描」按钮、`TagGenerationControlScreen`「全量扫描」按钮、Agent 的 `AutoTagCapability`。

### 问题二：Pass3 连续跑严重发热，且热保护机制当前是 dead code

`TagGenerationScheduler.guardCheck()`（消费 `checkGuard` 的 ABORT/PAUSE 决策与 `getAdaptiveThrottleMs` 节流）定义在 `TagGenerationScheduler.kt:308`，但**全仓库无任何调用点**——迁移到 Orchestrator 链路时丢失。因此 Pass1/Pass2/Pass3 全程没有热/电量保护。`executeQwenTagging` 内部既无 `guardCheck`、也无任务间 cooldown，仅靠 `runSession.POLL_INTERVAL_MS=100ms` 散热，对 GPU 密集的视觉模型推理远远不够。

## 目标

1. Pass1（人脸检测 + MobileCLIP 内联编码）+ Pass2（DBSCAN 聚类）**快速全量完成**，让用户尽快看到人脸与聚类结果。
2. Pass3 **延后**到「Pass1+Pass2 全量完成后」再遍历。
3. 所有 `scheduleAutoScan` 入口统一行为。
4. Pass3 阶段加入**自适应流控**（散热 + 功耗），并**修复失效的 guardCheck**，控温、省电。

## 非目标

- 不改 `scheduleRegenerate` / `schedulePass` / 手动单 Pass 独立按钮。
- 不改 `maxBatchSize`（保持 50）。
- 不改 Pass3 推理实现（仍是 Qwen/SmolVLM，走 `scheduler.executeQwenTagging`）。
- Pass1/Pass2 暂不接 guardCheck（单张快，靠 `POLL_INTERVAL` 散热；见 `executeFaceDetection` 既有注释）。
- 不新增用户可见字符串 / 设置项（流控纯自动，thermal 驱动）。

## 设计

### 改动 1：`ScanQueuePolicy` 新增 `deferredPasses`（`scan/ScanQueuePolicy.kt`）

```kotlin
/** 本次扫描第一阶段立即执行的 Pass */
val passes: List<TagScanPass> = listOf(
    TagScanPass.FACE_DETECTION,
    TagScanPass.DBSCAN
),

/** 第一阶段全量完成后才执行的 Pass（延迟阶段）。
 *  阶段切换由 scheduleAutoScan 在第一批次链式耗尽时自动触发。 */
val deferredPasses: List<TagScanPass> = listOf(
    TagScanPass.IMAGE_TAGGING
),
```

`conservative()` / `overnight()` 预设同步：`passes = [FACE_DETECTION, DBSCAN]`、`deferredPasses = [IMAGE_TAGGING]`。

**兼容出口**：显式 `passes = [FACE_DETECTION, DBSCAN, IMAGE_TAGGING]`、`deferredPasses = emptyList()` 即恢复旧的「混合三 pass 每批」行为（当前无入口需要）。

### 改动 2：`scheduleAutoScan` 阶段切换（`scan/TagScanOrchestrator.kt`，约 10 行）

在 `media.isEmpty()` 分支：

```kotlin
if (media.isEmpty()) {
    if (policy.deferredPasses.isNotEmpty()) {
        val nextPolicy = policy.copy(
            passes = policy.deferredPasses,
            deferredPasses = emptyList()
        )
        logInfo(sessionId, "延迟阶段切换: ${policy.passes} 全量完成 → 进入 ${nextPolicy.passes}")
        return scheduleAutoScan(nextPolicy)
    }
    // 真正全部完成（现有行为不变）
    logInfo(sessionId, "没有需要增量扫描的媒体")
    _progress.value = TagScanSessionProgress(
        sessionId = sessionId,
        state = ScanSessionState.COMPLETED,
        messages = listOf(ScanMessage(level = MessageLevel.INFO, text = "没有需要扫描的媒体"))
    )
    return sessionId
}
```

- `requestedPassNumbers` 仍按 `policy.passes` 计算；`deferredPasses` 不参与本批去重。
- 递归仅一层：第二阶段 `deferredPasses = emptyList()`，再遇空不再切换 → 不会死循环。
- `createTasks` / `runSession` / 链式调度 / `finalizeSession` 完全不改。

### 改动 3：阶段切换进度可见性

阶段切换经 `logInfo` → `addMessage` 留一条 INFO。第二阶段是新 `sessionId`；`TagGenerationService.isScanning` 两阶段都为 `RUNNING`，UI 连续显示扫描中。`progress.processed/total` 按新会话工作集重新计数（符合预期）。通知文案不变。

### 改动 4（附带优化）：移除多余的完整 `MediaEntity` 加载

`scheduleAutoScan` 现状为判空加载完整 `MediaEntity`（含 `faceRoiResult`/`semanticEmbedding` 大字段），但 `createTasks` 只用 id。改为直接用 `filteredIds.isEmpty()` 判空、`filteredIds` 传入 `createTasks`，移除 `getMediaByIds` 与 `sortedBy`。降低每批 Heap 峰值，为将来加大 `maxBatchSize` 留余地。

### 改动 5（bug 修复）：接回 `guardCheck`（`TagGenerationScheduler.kt`）

`executeQwenTagging` 开头增加守卫检查，让已有的 ABORT（热 SEVERE / 电量危机）/ PAUSE（热 MODERATE / 电量低）真正生效：

```kotlin
suspend fun executeQwenTagging(mediaId: Long) {
    if (!guardCheck()) {
        // ABORT：热严重/电量危机。抛异常 → 任务 FAILED → handleTaskFailure 退避重试（自带散热窗口）
        throw IllegalStateException("[Pass 3] Guard ABORT (thermal/battery) mediaId=$mediaId")
    }
    // ...既有逻辑不变...
}
```

- PAUSE 分支已在 `guardCheck` 内部 `delay(getThrottleMs())`，不抛异常，仅拉长节流后继续。
- ABORT 抛异常 → `executeTask` catch → `handleTaskFailure` → `markFailed` + `RETRY_BACKOFF_BASE_MS*(attempt+1)` 退避（5min 起），自然散热。

### 改动 6：Pass3 自适应 cooldown（`TagGenerationScheduler.kt` + `TagGenerationService.kt`）

**平衡档**：每张 Pass3 推理后主动散热，间隔随 `PowerManager.currentThermalStatus` 递增。

`TagGenerationScheduler` 构造函数新增 lambda：

```kotlin
private val getPass3CooldownMs: () -> Long = { PASS3_COOLDOWN_DEFAULT },
```

`executeQwenTagging` 末尾：

```kotlin
// Pass3 连续执行发热严重：每张推理后自适应散热（热状态越高间歇越长）。
// SEVERE 及以上已由 guardCheck ABORT 兜底，不会走到这里。
delay(getPass3CooldownMs())
```

`TagGenerationService` 新增（与 `getAdaptiveThrottleMs` 同级）：

```kotlin
companion object {
    // Pass3 流控：每张推理后的散热间歇（毫秒），随热状态递增（平衡档）。
    val PASS3_COOLDOWN_BY_THERMAL: Map<Int, Long> = mapOf(
        PowerManager.THERMAL_STATUS_NONE to 800L,
        PowerManager.THERMAL_STATUS_LIGHT to 1_500L,
        PowerManager.THERMAL_STATUS_MODERATE to 3_000L,
    )
    const val PASS3_COOLDOWN_DEFAULT = 800L
}

private fun getPass3CooldownMs(): Long {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val pm = getSystemService(PowerManager::class.java)
        return PASS3_COOLDOWN_BY_THERMAL[pm.currentThermalStatus] ?: PASS3_COOLDOWN_DEFAULT
    }
    return PASS3_COOLDOWN_DEFAULT
}
```

构造 scheduler 时注入：`getPass3CooldownMs = { getPass3CooldownMs() }`。

单张 Pass3 端到端估算：凉机 ~7s 推理 + 0.8s cooldown + 0.1s poll ≈ 7.9s；MODERATE 时 guardCheck PAUSE(3s) + 推理 + 3s cooldown，且 SoC 自动降频，吞吐进一步下降；SEVERE 由 ABORT 接管退避重试。

## 不变的部分

- `createTasks` / `createTasksForSinglePass` / `runSession` / `finalizeSession` / ETA 估算 / `recordDuration` / wakeLock / `maybeResumeOnStartup`。
- DBSCAN（Pass2）仍是每批一个全局任务（`mediaId = -1`, priority 1）。
- `executeFaceDetection` / `executeMobileClipEncoding` 不接 guardCheck、不加 cooldown（维持性能；范围外）。
- Service 的 `checkGuard` / `getAdaptiveThrottleMs` 既有逻辑不变（改动 5 只是让它们真正被消费）。

## 边界与风险

| 场景 | 行为 |
|---|---|
| 阶段切换递归 | 递归一层；第二阶段 `deferredPasses` 空 → 不再切。安全。 |
| 第二阶段会话暂停/取消 | 各会话独立，`pause`/`cancel`/`retryFailed` 按现有机制作用；`delay` 中收到取消会抛 `CancellationException` 并正确传播。 |
| Service 被杀重建 | `maybeResumeOnStartup` 恢复 PENDING 会话；两阶段各自独立会话，恢复语义不变。 |
| 候选为空（图库已全量打标） | 第一阶段即空且 `deferredPasses` 非空 → 切第二阶段；第二阶段也空 → 真完成。 |
| Pass3 ABORT 退避 | 任务 FAILED + 5min 起退避；`retryFailed` 可手动提前。退避期间该媒体不计入主动散热队列，有助降温。 |
| cooldown 拉长 Pass3 总时长 | 平衡档可接受（用户已选）。常量集中在 `PASS3_COOLDOWN_BY_THERMAL`，便于后续调参或暴露为设置项。 |

## 测试（JVM 单测，无需设备）

新增 `app/src/test/.../TagScanOrchestratorTwoPhaseTest.kt`：
1. **两阶段全流程**：mock 图库含 N 张未打标媒体，`maxBatchSize=2`。断言先耗尽 Pass1+Pass2（多批链式）→ 切 Pass3 → 耗尽 Pass3 → `COMPLETED`；断言第二阶段 policy 的 `deferredPasses` 为空（防死循环回归）。
2. **兼容**：`deferredPasses = emptyList()` 时不切阶段。
3. **空图库**：两阶段都无候选 → 直接 `COMPLETED`。

新增/扩展 `TagGenerationSchedulerPass3ThrottleTest.kt`（复用现有 scheduler 测试基础设施；若无可 mock `guard` / `getPass3CooldownMs` lambda）：
4. **guardCheck 接入**：`guard = { ABORT }` 时 `executeQwenTagging` 抛 `IllegalStateException`；`guard = { PAUSE }` 时内部 `delay` 被调用后正常返回；`ALLOW` 时不抛。
5. **cooldown**：`getPass3CooldownMs = { 1234 }` 时，`executeQwenTagging` 末尾 `delay(1234)`（用虚拟时间或 mock 验证调用）。

> 设备端实测（非单测）：连续 Pass3 下观察 `adb shell dumpsys thermalservice` 温度曲线与 logcat `[Benchmark] Pass 3` 耗时，确认降温生效、吞吐在可接受范围。

## 实现涉及的文件

- `app/src/main/java/com/mamba/picme/domain/tag/scan/ScanQueuePolicy.kt`（改：`passes` 默认值 + 新增 `deferredPasses` + 预设）
- `app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt`（改：`scheduleAutoScan` 阶段切换 + 附带优化）
- `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt`（改：构造函数新增 `getPass3CooldownMs`；`executeQwenTagging` 接 `guardCheck` + 末尾 `delay`）
- `app/src/main/java/com/mamba/picme/service/tag/TagGenerationService.kt`（改：新增 `getPass3CooldownMs()` + `PASS3_COOLDOWN_BY_THERMAL` 常量 + 构造 scheduler 注入）
- `app/src/test/.../TagScanOrchestratorTwoPhaseTest.kt`（新增）
- `app/src/test/.../TagGenerationSchedulerPass3ThrottleTest.kt`（新增/扩展）

## 文档同步（实现时检查）

- `docs/01-PRODUCT/FEATURES.md`：若描述「自动扫描流程」的阶段顺序，更新为两阶段表述。
- `docs/03-TECHNICAL-SPECS/`：若存在打标管线热保护相关 spec，补充「guardCheck 接入 Pass3 + 自适应 cooldown」。
- 不触发 `PRODUCT.md`（产品边界不变）。
- 不触发 i18n（流控无新增用户可见字符串）。
