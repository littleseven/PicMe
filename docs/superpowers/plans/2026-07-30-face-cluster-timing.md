# 人物聚类时机改进（Pass1 流式攒批 + DBSCAN 周期精修）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让大相册里人物聚类在 Pass1 期间就尽早可见——Pass1 每累计 ~20 张含人脸图即触发一次流式增量聚类（复用 `FaceClusterEngine`），DBSCAN 降级为按阈值触发的周期精修（而非每批 50 强制跑）。

**Architecture:** Pass1（`executeFaceDetection`→`stage1WithEmbeddings`）已把 embedding 以 `personId=null` 存入 `face_embeddings`。新增 `FaceClusterEngine.assignStoredEmbeddings()` 读这些未分配行，用 `matchCluster`/`assignEmbedding`（改派而非重插，同步质心缓存）流式归簇。`TagScanOrchestrator` 加会话级计数器 `StreamingClusterAccumulator`，Pass1 每张含脸图后计数，达 `STREAMING_CLUSTER_BATCH` 触发一次。DBSCAN 任务改为仅在累计处理 embedding 数达 `RE_CLUSTER_THRESHOLD` 或末批时才入队。

**Tech Stack:** Kotlin + Coroutines + Room；纯 JVM 单测 JUnit4（决策逻辑）+ 设备验证（聚类链路）。

**Spec:** `docs/superpowers/specs/2026-07-30-face-cluster-experience-design.md`（本计划对应 §3.1 + §3.2；A→C→B 三拆分中的 A）

**验证命令（本环境真门槛=编译+JVM单测）:**
- 编译：`./gradlew :app:assembleDebug`
- 单测：`./gradlew :app:testDebugUnitTest`
- 单个测试类：`./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.scan.*"`

**约定:**
- 禁止 `com.mamba.picme.*` 全限定名（用 import）；禁止 wildcard import；lambda 参数显式命名；4 空格缩进。
- 每个任务末尾提交一次（Conventional Commits）。commit message 结尾加 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- Log tag 用 `PoLang:FaceCluster` / `PoLang:ScanOrchestrator`。

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `domain/tag/ClusteringConfig.kt` | 新增 `STREAMING_CLUSTER_BATCH` 常量 | 改 |
| `domain/tag/scan/StreamingClusterAccumulator.kt` | 纯逻辑：计数达阈值返回触发信号 | 新 |
| `domain/tag/scan/StreamingClusterAccumulatorTest.kt` | 单测 | 新 |
| `domain/tag/FaceClusterEngine.kt` | 新增 `assignStoredEmbeddings()` 流式改派已存 embedding | 改 |
| `domain/tag/TagGenerationScheduler.kt` | `executeFaceDetection` 返回 hasFace；新增 `runStreamingClusterBatch()` | 改 |
| `domain/tag/scan/DbscanRefinementPolicy.kt` | 纯逻辑：是否该跑 DBSCAN 精修 | 新 |
| `domain/tag/scan/DbscanRefinementPolicyTest.kt` | 单测 | 新 |
| `domain/tag/scan/TagScanOrchestrator.kt` | 接线：Pass1 触发流式；DBSCAN 按阈值入队 | 改 |

---

### Task 1: StreamingClusterAccumulator（流式触发计数器，纯逻辑 TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/tag/scan/StreamingClusterAccumulator.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/tag/scan/StreamingClusterAccumulatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.domain.tag.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingClusterAccumulatorTest {
    @Test
    fun doesNotTriggerBeforeBatchSize() {
        val acc = StreamingClusterAccumulator(batchSize = 20)
        repeat(19) { idx -> assertFalse("call #$idx", acc.onFacePhoto()) }
    }

    @Test
    fun triggersExactlyAtBatchSizeAndResets() {
        val acc = StreamingClusterAccumulator(batchSize = 20)
        repeat(19) { acc.onFacePhoto() }
        assertTrue("20th call triggers", acc.onFacePhoto())
        // reset 后需再攒 20 张才再次触发
        repeat(19) { idx -> assertFalse("after reset #$idx", acc.onFacePhoto()) }
        assertTrue("40th call triggers", acc.onFacePhoto())
    }

    @Test
    fun manualResetClearsPending() {
        val acc = StreamingClusterAccumulator(batchSize = 20)
        repeat(10) { acc.onFacePhoto() }
        acc.reset()
        repeat(19) { idx -> assertFalse("post-reset #$idx", acc.onFacePhoto()) }
        assertTrue(acc.onFacePhoto())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.scan.StreamingClusterAccumulatorTest"`
Expected: FAIL — `StreamingClusterAccumulator` 未定义（编译错误）。

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.mamba.picme.domain.tag.scan

import com.mamba.picme.domain.tag.ClusteringConfig

/**
 * 流式攒批聚类计数器：Pass1 每处理一张含人脸图调用 [onFacePhoto]，
 * 累计达到 [batchSize] 时返回 true（触发一次流式聚类）并自动清零。
 *
 * 纯逻辑、无副作用，便于 JVM 单测；状态由持有方（[TagScanOrchestrator]）跨批次保留。
 */
class StreamingClusterAccumulator(
    private val batchSize: Int = ClusteringConfig.STREAMING_CLUSTER_BATCH
) {
    private var pending: Int = 0

    /** 记录一张含人脸图；返回 true 表示已达阈值、应触发流式聚类（触发后自动清零）。 */
    fun onFacePhoto(): Boolean {
        pending++
        if (pending >= batchSize) {
            pending = 0
            return true
        }
        return false
    }

    /** 手动清零（全量重扫等场景）。 */
    fun reset() {
        pending = 0
    }
}
```

- [ ] **Step 4: Add config constant**

Modify `app/src/main/java/com/mamba/picme/domain/tag/ClusteringConfig.kt` — 在 `RE_CLUSTER_THRESHOLD` 之后新增：

```kotlin
    /** Pass1 流式攒批聚类：每累计多少张「含人脸图」触发一次增量归类。
     *  20：大相册里人物在远小于「整轮 Pass1」的时间内即可出现。 */
    const val STREAMING_CLUSTER_BATCH = 20
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.scan.StreamingClusterAccumulatorTest"`
Expected: PASS（3 个用例）。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/scan/StreamingClusterAccumulator.kt \
        app/src/test/java/com/mamba/picme/domain/tag/scan/StreamingClusterAccumulatorTest.kt \
        app/src/main/java/com/mamba/picme/domain/tag/ClusteringConfig.kt
git commit -m "feat(cluster): 流式攒批聚类计数器 StreamingClusterAccumulator + STREAMING_CLUSTER_BATCH

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: FaceClusterEngine.assignStoredEmbeddings()（流式改派已存 embedding）

> 说明：`FaceClusterEngine` 内部自建 `personDao`（非注入），不便隔离单测；本方法是对已验证的 `matchCluster`/`createCluster`/`addToCluster` 在「已存 embedding」语义下的编排，依赖编译 + Task 6 设备验证。质心缓存更新与 `addToCluster`（同文件 340-363 行）保持一致。

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/FaceClusterEngine.kt`（在 `addToCluster` 之后、`mergeClusters` 之前插入）

- [ ] **Step 1: Add assignStoredEmbeddings()**

在 `FaceClusterEngine.kt` 的 `addToCluster(...)` 方法之后插入：

```kotlin
    /**
     * 流式增量聚类（扫描链路用）：将已存储但未分配（personId=null）的 embedding 逐个归类。
     *
     * 与 [createCluster]/[addToCluster] 的区别：Pass1 已把 embedding 写入 face_embeddings，
     * 这里用 [PersonDao.assignEmbedding] 改派 personId（不重新插入行），并同步质心缓存。
     *
     * @return 本次处理的 embedding 数（0 表示无未分配项）
     */
    suspend fun assignStoredEmbeddings(): Int {
        val unassigned = personDao.getUnassignedEmbeddings()
        if (unassigned.isEmpty()) return 0

        var processed = 0
        for (entity in unassigned) {
            val feature = byteArrayToFloatArray(entity.embedding)
            processed++
            // 跳过零向量（模型缺失时 stage1 产出的占位）
            if (feature.all { value -> value == 0f }) continue

            val matchedId = matchCluster(feature)
            if (matchedId != null) {
                personDao.assignEmbedding(entity.embeddingId, matchedId)
                personDao.incrementFaceCount(matchedId)
                // 增量质心，与 addToCluster 一致
                centroidCache[matchedId]?.let { (oldCentroid, oldCount) ->
                    val newCount = oldCount + 1
                    val newCentroid = FloatArray(EMBEDDING_DIM)
                    for (i in 0 until EMBEDDING_DIM) {
                        newCentroid[i] = (oldCentroid[i] * oldCount + feature[i]) / newCount
                    }
                    centroidCache[matchedId] = newCentroid to newCount
                }
            } else {
                val personId = personDao.insertPerson(
                    PersonEntity(faceCount = 1, coverMediaId = entity.mediaId)
                )
                personDao.assignEmbedding(entity.embeddingId, personId)
                centroidCache[personId] = feature.clone() to 1
            }
        }
        Log.i(TAG, "Streaming-assigned $processed stored embeddings (unassigned were ${unassigned.size})")
        return processed
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

> 用到的 `byteArrayToFloatArray`、`cosineSimilarity`（经 matchCluster）、`EMBEDDING_DIM`、`centroidCache`、`personDao` 均为同文件已有成员；`PersonEntity` 已 import。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/FaceClusterEngine.kt
git commit -m "feat(cluster): FaceClusterEngine.assignStoredEmbeddings 流式改派已存 embedding

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: TagGenerationScheduler 暴露流式聚类 + executeFaceDetection 返回 hasFace

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt`

- [ ] **Step 1: 改 executeFaceDetection 返回 Boolean（是否检测到有效人脸）**

定位 `suspend fun executeFaceDetection(mediaId: Long) {`（约 1118 行），把签名改为返回 `Boolean`，并在末尾 `return hasValidFace`。即：

```kotlin
    suspend fun executeFaceDetection(mediaId: Long): Boolean {
        val dao = db.mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return false

        val result = pipeline.stage1WithEmbeddings(
            uri = entity.uri,
            lensFacing = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
            mediaId = entity.id
        )

        currentCoroutineContext().ensureActive()

        val hasValidFace = result.faceRoiJson != null && result.embeddings.isNotEmpty()
        if (result.faceRoiJson != null) {
            dao.updateFaceRoiResult(entity.id, result.faceRoiJson, hasValidFace)
        } else if (entity.type == MediaType.PHOTO) {
            Log.w(TAG, "[Pass 1] Photo decode failed; writing sentinel for mediaId=${entity.id}")
            dao.updateFaceRoiResult(entity.id, DECODE_FAILURE_ROI_JSON, false)
        }
        val faceFocusY = result.faceFocusY
        if (faceFocusY != null) {
            dao.updateFaceFocusY(entity.id, faceFocusY)
        }

        personDao.deleteEmbeddingsByMedia(entity.id)
        if (result.embeddings.isNotEmpty()) {
            val embeddingEntities = result.embeddings.map { embedding ->
                com.mamba.picme.data.local.entity.FaceEmbeddingEntity(
                    mediaId = entity.id,
                    personId = null,
                    embedding = floatArrayToByteArray(embedding)
                )
            }
            personDao.insertEmbeddings(embeddingEntities)
        }

        val semanticEmbedding = result.semanticEmbedding
        if (semanticEmbedding != null) {
            try {
                dao.updateSemanticEmbedding(entity.id, semanticEmbedding)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist semantic embedding for media ${entity.id}: ${e.message}")
            }
        }

        return hasValidFace
    }
```

> `executeFaceDetection` 的其它调用方（如有）忽略返回值不受影响（Kotlin 允许丢弃 Boolean）。编译期会报出任何需适配处。

- [ ] **Step 2: 新增 runStreamingClusterBatch()**

在 `executeDbscan(...)` 之前新增（内部吞异常，避免拖垮 Pass1 任务）：

```kotlin
    /**
     * Pass1 流式攒批聚类：把当前未分配的 embedding 流式归类，返回本次归类的 embedding 数。
     * 异常被吞并记录日志，绝不影响 Pass1 主流程（DBSCAN 末批会兜底）。 */
    suspend fun runStreamingClusterBatch(): Int {
        return try {
            faceClusterEngine.assignStoredEmbeddings()
        } catch (e: Exception) {
            Log.w(TAG, "Streaming cluster batch failed (will rely on DBSCAN): ${e.message}")
            0
        }
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/TagGenerationScheduler.kt
git commit -m "feat(cluster): scheduler 暴露 runStreamingClusterBatch；executeFaceDetection 返回 hasFace

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 在 Pass1 接线流式攒批触发

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt`

- [ ] **Step 1: 新增累加器字段**

在 `TagScanOrchestrator` 字段区（`fullRescanPasses` 附近，约 191 行）新增：

```kotlin
    /** Pass1 流式攒批聚类累加器（跨批次保留；全量重扫时复位）。 */
    private val streamingAccumulator = StreamingClusterAccumulator()
    /** 自上次 DBSCAN 精修以来流式归类的 embedding 数（供 DBSCAN 周期触发判定）。 */
    private var embeddingsSinceDbscan = 0
    /** 标记：流式累计已达阈值，下一批应入队一次 DBSCAN 精修。 */
    private var dbscanRefinementDue = false
```

- [ ] **Step 2: 在 executeTask 的 FACE_DETECTION 分支触发流式聚类**

定位 `executeTask(task)` 的 `when (task.pass)`（约 678 行），把 `FACE_DETECTION` 分支改为：

```kotlin
                TagScanPass.FACE_DETECTION -> {
                    val hasFace = scheduler.executeFaceDetection(task.mediaId)
                    if (hasFace && streamingAccumulator.onFacePhoto()) {
                        val assigned = scheduler.runStreamingClusterBatch()
                        embeddingsSinceDbscan += assigned
                        if (DbscanRefinementPolicy.shouldRunRefinement(
                                embeddingsSinceDbscan,
                                isFinalBatch = false
                            )
                        ) {
                            dbscanRefinementDue = true
                        }
                    }
                }
```

> `DbscanRefinementPolicy` 在 Task 5 创建；本步先写好调用，Task 5 后整体编译通过。若想分步可编译，可先临时把 `DbscanRefinementPolicy...` 三行注释，Task 5 再启用——但建议两任务连续完成。

- [ ] **Step 3: 全量重扫时复位累加器**

定位 `schedulePass` 里 `if (mode == ScanMode.FULL)` 块（约 350 行），在 `fullRescanPasses += pass` 之后补：

```kotlin
            if (pass == TagScanPass.FACE_DETECTION) {
                streamingAccumulator.reset()
                embeddingsSinceDbscan = 0
                dbscanRefinementDue = false
            }
```

- [ ] **Step 4: Commit（与 Task 5 合并提交，或先不编译-checkpoint）**

> 本任务依赖 Task 5 的 `DbscanRefinementPolicy` 才能编译通过。完成 Task 5 后一并编译 + 提交；或在此处先 `git add -A` 暂存不提交。

---

### Task 5: DbscanRefinementPolicy（DBSCAN 周期精修触发判定，纯逻辑 TDD）

**Files:**
- Create: `app/src/main/java/com/mamba/picme/domain/tag/scan/DbscanRefinementPolicy.kt`
- Test: `app/src/test/java/com/mamba/picme/domain/tag/scan/DbscanRefinementPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mamba.picme.domain.tag.scan

import com.mamba.picme.domain.tag.ClusteringConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DbscanRefinementPolicyTest {
    @Test
    fun runsOnFinalBatchRegardlessOfCount() {
        assertTrue(DbscanRefinementPolicy.shouldRunRefinement(0, isFinalBatch = true))
        assertTrue(
            DbscanRefinementPolicy.shouldRunRefinement(5, isFinalBatch = true)
        )
    }

    @Test
    fun doesNotRunBeforeThresholdMidScan() {
        val under = ClusteringConfig.RE_CLUSTER_THRESHOLD - 1
        assertFalse(DbscanRefinementPolicy.shouldRunRefinement(under, isFinalBatch = false))
    }

    @Test
    fun runsAtThresholdMidScan() {
        assertTrue(
            DbscanRefinementPolicy.shouldRunRefinement(
                ClusteringConfig.RE_CLUSTER_THRESHOLD,
                isFinalBatch = false
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.scan.DbscanRefinementPolicyTest"`
Expected: FAIL — `DbscanRefinementPolicy` 未定义。

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.mamba.picme.domain.tag.scan

import com.mamba.picme.domain.tag.ClusteringConfig

/**
 * DBSCAN 周期精修触发判定（纯逻辑）。
 *
 * 流式聚类已让人物尽早可见，DBSCAN 降级为「按需精修」：
 * - 末批（整轮扫描结束）必跑一次，保证最终质量；
 * - 中途仅当自上次精修以来流式归类的 embedding 数达 [ClusteringConfig.RE_CLUSTER_THRESHOLD] 才跑。
 */
object DbscanRefinementPolicy {
    fun shouldRunRefinement(embeddingsSinceDbscan: Int, isFinalBatch: Boolean): Boolean {
        if (isFinalBatch) return true
        return embeddingsSinceDbscan >= ClusteringConfig.RE_CLUSTER_THRESHOLD
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.domain.tag.scan.DbscanRefinementPolicyTest"`
Expected: PASS（3 个用例）。

- [ ] **Step 5: 末批触发精修**

在 `TagScanOrchestrator.scheduleAutoScan` 里，候选耗尽且无延迟阶段（约 245 行 `logInfo(sessionId, "没有需要增量扫描的媒体")` 之前）插入末批精修：

```kotlin
            // 末批：整轮扫描结束，按策略跑一次 DBSCAN 精修（纠正流式碎片/错分）
            if (DbscanRefinementPolicy.shouldRunRefinement(embeddingsSinceDbscan, isFinalBatch = true) &&
                policy.passes.contains(TagScanPass.DBSCAN)
            ) {
                logInfo(sessionId, "末批 DBSCAN 精修")
                scheduler.executeDbscan(preserveNamedPersons = true, isFullRescan = false)
                embeddingsSinceDbscan = 0
                dbscanRefinementDue = false
            }
```

- [ ] **Step 6: DBSCAN 任务改为按阈值入队**

在 `createTasks`（约 520 行）把无条件入队改为条件入队：

```kotlin
        // Pass 2: 全局 DBSCAN 任务——仅在「周期精修到期」时入队（mediaId = -1 标记）
        if (passes.contains(TagScanPass.DBSCAN) && dbscanRefinementDue) {
            tasks += TagScanTaskEntity(
                sessionId = sessionId,
                mediaId = -1L,
                pass = TagScanPass.DBSCAN,
                tagCategories = categoriesJson,
                status = TagScanTaskStatus.PENDING,
                priority = 1,
                createdAt = System.currentTimeMillis()
            )
            dbscanRefinementDue = false
            embeddingsSinceDbscan = 0
        }
```

- [ ] **Step 7: 编译 + 全量单测**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，全部单测 PASS（含 Task 1/5 新增）。

- [ ] **Step 8: Commit（Task 4 + Task 5 一并）**

```bash
git add app/src/main/java/com/mamba/picme/domain/tag/scan/TagScanOrchestrator.kt \
        app/src/main/java/com/mamba/picme/domain/tag/scan/DbscanRefinementPolicy.kt \
        app/src/test/java/com/mamba/picme/domain/tag/scan/DbscanRefinementPolicyTest.kt
git commit -m "feat(cluster): Pass1 接线流式攒批聚类 + DBSCAN 降级周期精修

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 文档同步 + 设备验证

**Files:**
- Modify: `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`（扫描阶段：标注双层聚类）
- Modify: `PRODUCT.md`（人物章节状态注脚，若涉及时机）

- [ ] **Step 1: 文档同步**

在 `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` 的扫描/聚类章节补一段：Pass1 流式攒批聚类（每 `STREAMING_CLUSTER_BATCH=20` 张含脸图触发，`FaceClusterEngine.assignStoredEmbeddings`）+ DBSCAN 周期精修（`RE_CLUSTER_THRESHOLD` 或末批触发，保留命名/关系）。引用 spec `2026-07-30-face-cluster-experience-design.md` §3.1/§3.2。

- [ ] **Step 2: 设备验证（用户实地，非自动化门槛）**

装包后用大相册（或 adb 注入照片）观察 `adb logcat -s "PoLang:FaceCluster"`：
- 预期：Pass1 进行中即可见 `Streaming-assigned N stored embeddings` 日志，且相册「按人物分组」在远早于「整轮 Pass1 完成」时出现人物。
- 预期：DBSCAN 仅在累计达 100 或末批时触发（`末批 DBSCAN 精修` / DBSCAN 任务日志），而非每批 50。

> 本任务不涉及用户可见新文案，无需 i18n。

- [ ] **Step 3: Commit**

```bash
git add docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md PRODUCT.md
git commit -m "docs: 同步双层聚类（Pass1 流式 + DBSCAN 周期精修）架构说明

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 自审（Self-Review）

- **Spec 覆盖**：§3.1 流式攒批 → Task 1/2/3/4；§3.2 DBSCAN 周期精修 → Task 5；文档 → Task 6。无遗漏。
- **占位符**：无 TBD/TODO；每步含完整代码或确切命令。
- **类型一致**：`executeFaceDetection: Boolean`（Task 3 改）→ Task 4 用 `val hasFace`；`runStreamingClusterBatch(): Int`（Task 3）→ Task 4 用 `assigned`；`assignStoredEmbeddings(): Int`（Task 2）→ Task 3 调用；`StreamingClusterAccumulator.onFacePhoto(): Boolean`、`DbscanRefinementPolicy.shouldRunRefinement(Int, Boolean): Boolean` 全程命名一致。`FaceEmbeddingEntity.embeddingId`（非 id）已对齐。
- **已知集成风险**：`FaceClusterEngine`/`executeDbscan` 的全量重聚类与流式结果共存——由 `preserveNamedPersons=true` 快照逻辑保留命名（既有）；身份重排为预期（spec「允许精修重排」）。
