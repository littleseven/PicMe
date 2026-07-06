# 技术文档：帧同步美妆系统（Frame-Sync Makeup System）

> **版本**: 1.0  
> **状态**: 生效中  
> **最后更新**: 2026-07-06  
> **维护者**: RD Agent  


**版本**：1.1
**状态**：🔄 部分实现（核心组件 FrameSyncManager / MotionTracker / FrameSyncBridge 已落地；预测补偿算法与 hide 降级策略待收尾）
> ⚠️ **审计备注（2026-06）**：`DetectionQueue` 和 `FaceDetectionWorker` 为**设计期概念**，从未实际落地（对应 .kt 文件不存在）。当前使用同步检测路径（CameraFrameAnalyzer 直接调用 faceDetector.detect()）。本文档 Section 3.2 及 10(代码变更清单) 中关于 DetectionQueue 的内容均为设计方案，非已落地代码。
**依赖**：`BIG_BEAUTY_TECH_SPEC.md`（已落地）
**最后更新**：2026-05-24

---

## 1. 架构目标

将当前"异步松散耦合"的人脸检测-渲染链路，升级为"准同步帧匹配"架构：

- **精确对齐**：渲染帧使用对应相机帧的人脸检测结果
- **可预测**：检测缺失时，基于运动轨迹预测补偿
- **可降级**：预测不可信时，隐藏妆容而非错误渲染
- **零侵入**：`FaceMakeupPass` 等下游组件只消费同步后的数据，不感知同步逻辑

---

## 2. 系统架构

### 2.1 整体数据流

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CameraX 预览层                                  │
│  ┌─────────────────┐                                                       │
│  │ SurfaceTexture  │  updateTexImage() 时生成 FrameId                       │
│  │   (帧源)        │                                                       │
│  └────────┬────────┘                                                       │
│           │                                                                  │
│           ▼ FrameId + ImageProxy                                            │
│  ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐       │
│  │  📋 DetectionQueue │────▶│ FaceDetector    │────▶│ DetectionResult │       │
│  │  (设计概念，未落地) │     │ (MediaPipe/MNN) │     │ (106点+FrameId) │       │
│  │   深度=2,超时丢帧 │     │                 │     │                 │       │
│  │  > 注：未实现，当前 │     │                 │     │                 │       │
│  │    使用同步检测路径 │     │                 │     │                 │       │
│  └─────────────────┘     └─────────────────┘     └────────┬────────┘       │
│                                                           │                  │
│                                                           ▼                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                      FrameSyncManager (单例)                            ││
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                   ││
│  │  │ ResultStore  │  │ MatchEngine  │  │ Predictor    │                   ││
│  │  │ (时序存储)   │  │ (帧ID匹配)   │  │ (运动预测)   │                   ││
│  │  └──────────────┘  └──────────────┘  └──────────────┘                   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                              │                                               │
│                              ▼ FrameSyncResult                                │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         渲染线程 (GL Thread)                            ││
│  │  ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐   ││
│  │  │ CameraPreview   │────▶│  BeautyRenderer │────▶│ FaceMakeupPass  │   ││
│  │  │   Renderer      │     │                 │     │ (消费同步顶点)  │   ││
│  │  └─────────────────┘     └─────────────────┘     └─────────────────┘   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| FrameId 生成位置 | `SurfaceTexture.updateTexImage()` 时 | 与相机帧严格绑定，避免多线程竞争 |
| 队列存储位置 | CPU 侧（非 GL 线程） | 避免阻塞渲染，检测线程直接写 |
| 预测计算位置 | CPU 侧，`FrameSyncManager` | GPU 只做渲染，CPU 做轻量预测 |
| 同步结果传递 | 拷贝到 `FaceMakeupPass` writeBuffer | 保持现有双缓冲机制，替换数据来源 |
| 严格缺失阈值 | 3 帧（默认，可配置） | 60fps 下约 50ms，用户无感知 |

---

## 3. 核心组件设计

### 3.1 FrameId 体系

```kotlin
/**
 * 全局帧标识符
 * - 单调递增，从 1 开始
 * - 与相机帧生命周期绑定
 */
@JvmInline
value class FrameId(val value: Long) : Comparable<FrameId> {
    companion object {
        val INVALID = FrameId(0L)
        private val counter = AtomicLong(0L)
        fun next(): FrameId = FrameId(counter.incrementAndGet())
    }
    override fun compareTo(other: FrameId): Int = value.compareTo(other.value)
}
```

**绑定点**：

```kotlin
// CameraPreviewRenderer 渲染循环
surfaceTexture?.updateTexImage()
val currentFrameId = FrameId.next()  // ← 帧ID在此生成

// 1. 将帧ID与SurfaceTexture当前帧绑定
frameSyncManager.bindFrameId(currentFrameId, surfaceTextureTimestamp)

// 2. 查询该帧对应的人脸同步结果
val syncResult = frameSyncManager.query(currentFrameId)

// 3. 将同步结果传递给 BeautyRenderer
beautyRenderer.applyFrameSyncResult(syncResult)
```

### 3.2 DetectionQueue（检测输入队列）

> **⚠️ 设计概念（未落地）**：以下 DetectionQueue 为设计方案，当前代码库中 `DetectionQueue.kt` 不存在。同步检测路径仍在 CameraFrameAnalyzer 中直接调用 `faceDetector.detect()`。保留本节供未来异步检测改造参考。

```kotlin
/**
 * 带帧ID的检测任务队列
 * - 深度限制：2（防止检测线程积压）
 * - 超时策略：任务入队后 > 200ms 未消费则丢弃
 */
class DetectionQueue(
    private val maxDepth: Int = 2,
    private val timeoutMs: Long = 200L
) {
    data class Task(
        val frameId: FrameId,
        val bitmap: Bitmap,
        val rotationDegrees: Int,
        val lensFacing: Int,
        val enqueueTimeMs: Long
    )

    private val queue = ArrayBlockingQueue<Task>(maxDepth)

    fun offer(task: Task): Boolean {
        // 队列满时丢弃最旧的任务
        if (queue.remainingCapacity() == 0) {
            queue.poll()
        }
        return queue.offer(task)
    }

    fun poll(): Task? {
        val task = queue.poll() ?: return null
        // 超时丢弃
        return if (SystemClock.elapsedRealtime() - task.enqueueTimeMs > timeoutMs) {
            task.bitmap.recycle()
            null
        } else {
            task
        }
    }
}
```

**检测线程改造**：

> **⚠️ 设计概念（未落地）**：以下检测线程改造为设计方案，当前仍使用同步检测路径。

```kotlin
// FaceDetectorManager.detect() 改为消费队列
detectionThread = Thread {
    while (isRunning) {
        val task = detectionQueue.poll() ?: continue
        
        // 执行检测，结果携带 FrameId
        val result = detectInternal(task.bitmap, task.rotationDegrees, task.lensFacing)
        
        result?.let {
            frameSyncManager.storeResult(
                FrameSyncResult(
                    frameId = task.frameId,
                    landmarks106 = it.landmarks,
                    detectionSource = it.source,
                    detectionLatencyMs = SystemClock.elapsedRealtime() - task.enqueueTimeMs
                )
            )
        }
        
        task.bitmap.recycle()
    }
}.apply { name = "FaceDetectionWorker" }
```

### 3.3 FrameSyncManager（时序对齐核心）

```kotlin
/**
 * 帧同步管理器
 * - 线程安全：ResultStore 使用 ConcurrentHashMap + 环形缓冲区
 * - 轻量级：查询操作 O(1)，无锁读（使用 volatile + copy-on-write）
 */
class FrameSyncManager(
    private val config: FrameSyncConfig = FrameSyncConfig.DEFAULT
) {
    data class FrameSyncConfig(
        val maxStoredResults: Int = 10,        // 保留最近 10 个检测结果
        val missingThresholdFrames: Int = 3,    // 缺失 3 帧后隐藏妆容
        val predictionMaxRatio: Float = 1.5f,   // 预测位移不超过上一帧 150%
        val syncMode: SyncMode = SyncMode.STRICT
    ) {
        companion object {
            val DEFAULT = FrameSyncConfig()
        }
    }

    enum class SyncMode {
        STRICT,      // 精确匹配 + 缺失隐藏
        SMOOTH,      // 历史回退 + 预测补偿
        OFF          // 关闭帧同步（保持当前行为）
    }

    data class FrameSyncResult(
        val frameId: FrameId = FrameId.INVALID,
        val landmarks106: FloatArray? = null,
        val detectionSource: FaceDetectionSource = FaceDetectionSource.NONE,
        val syncStatus: SyncStatus = SyncStatus.MISSING,
        val detectionLatencyMs: Long = 0L,
        val predictedOffsetPx: Float = 0f
    )

    enum class SyncStatus {
        EXACT_MATCH,      // 精确匹配
        HISTORICAL_FALLBACK, // 历史回退（使用最近旧结果）
        PREDICTED,        // 预测补偿
        MISSING           // 缺失（无可用结果）
    }

    // ─── 内部存储 ───
    private val resultStore = ConcurrentHashMap<FrameId, DetectionResult>()
    private val frameHistory = ConcurrentLinkedQueue<FrameId>()
    private val motionTracker = MotionTracker()

    // ─── 公共 API ───

    /**
     * 绑定当前渲染帧的 FrameId（由渲染线程调用）
     */
    fun bindFrameId(frameId: FrameId, timestampNs: Long) {
        // 记录帧时间戳，用于计算检测延迟
    }

    /**
     * 存储检测结果（由检测线程调用）
     */
    fun storeResult(result: DetectionResult) {
        resultStore[result.frameId] = result
        frameHistory.offer(result.frameId)
        motionTracker.update(result.frameId, result.landmarks106)
        trimOldResults()
    }

    /**
     * 查询帧同步结果（由渲染线程调用，每帧一次）
     */
    fun query(currentFrameId: FrameId): FrameSyncResult {
        if (config.syncMode == SyncMode.OFF) {
            return FrameSyncResult(syncStatus = SyncStatus.MISSING)
        }

        // 1. 精确匹配
        resultStore[currentFrameId]?.let {
            return FrameSyncResult(
                frameId = currentFrameId,
                landmarks106 = it.landmarks106,
                detectionSource = it.detectionSource,
                syncStatus = SyncStatus.EXACT_MATCH,
                detectionLatencyMs = it.detectionLatencyMs
            )
        }

        // 2. 查找最近历史结果
        val historicalResult = findNearestHistoricalResult(currentFrameId)
            ?: return FrameSyncResult(syncStatus = SyncStatus.MISSING)

        val frameDiff = currentFrameId.value - historicalResult.frameId.value

        // 3. 严格模式：超过阈值直接隐藏
        if (config.syncMode == SyncMode.STRICT && frameDiff > config.missingThresholdFrames) {
            return FrameSyncResult(syncStatus = SyncStatus.MISSING)
        }

        // 4. 平滑模式：预测补偿
        if (config.syncMode == SyncMode.SMOOTH) {
            val predicted = motionTracker.predict(
                fromFrameId = historicalResult.frameId,
                toFrameId = currentFrameId,
                maxRatio = config.predictionMaxRatio
            )
            return FrameSyncResult(
                frameId = historicalResult.frameId,
                landmarks106 = predicted,
                detectionSource = historicalResult.detectionSource,
                syncStatus = SyncStatus.PREDICTED,
                detectionLatencyMs = historicalResult.detectionLatencyMs,
                predictedOffsetPx = calculateOffset(predicted, historicalResult.landmarks106)
            )
        }

        // 5. 严格模式且未超阈值：使用历史结果（无预测）
        return FrameSyncResult(
            frameId = historicalResult.frameId,
            landmarks106 = historicalResult.landmarks106,
            detectionSource = historicalResult.detectionSource,
            syncStatus = SyncStatus.HISTORICAL_FALLBACK,
            detectionLatencyMs = historicalResult.detectionLatencyMs
        )
    }

    private fun findNearestHistoricalResult(currentFrameId: FrameId): DetectionResult? {
        // 从 currentFrameId 向前查找最近的有结果的帧
        return frameHistory.asReversed()
            .firstOrNull { it <= currentFrameId && resultStore.containsKey(it) }
            ?.let { resultStore[it] }
    }

    private fun trimOldResults() {
        while (frameHistory.size > config.maxStoredResults) {
            val oldId = frameHistory.poll() ?: break
            resultStore.remove(oldId)
        }
    }
}
```

### 3.4 MotionTracker（运动预测）

```kotlin
/**
 * 轻量级运动跟踪器
 * 基于速度外推的预测算法（Phase 1），后续可替换为 Kalman Filter
 */
class MotionTracker {
    data class FrameState(
        val frameId: FrameId,
        val landmarks106: FloatArray,
        val timestampMs: Long
    )

    private val history = ArrayDeque<FrameState>(3)  // 保留最近 3 帧

    fun update(frameId: FrameId, landmarks106: FloatArray) {
        history.addLast(FrameState(frameId, landmarks106.clone(), SystemClock.elapsedRealtime()))
        if (history.size > 3) history.removeFirst()
    }

    /**
     * 预测目标帧的人脸关键点位置
     * @return 预测后的 FloatArray(212)，如果无法预测则返回历史结果
     */
    fun predict(fromFrameId: FrameId, toFrameId: FrameId, maxRatio: Float): FloatArray {
        if (history.size < 2) {
            return history.lastOrNull()?.landmarks106 ?: FloatArray(212)
        }

        val latest = history.last()
        val previous = history[history.size - 2]

        // 计算帧间速度：velocity = (latest - previous) / (latestFrameId - previousFrameId)
        val frameDiff = (latest.frameId.value - previous.frameId.value).coerceAtLeast(1L)
        val targetDiff = (toFrameId.value - fromFrameId.value).coerceAtLeast(0L)

        val predicted = FloatArray(latest.landmarks106.size)
        for (i in latest.landmarks106.indices) {
            val velocity = (latest.landmarks106[i] - previous.landmarks106[i]) / frameDiff
            val rawPredicted = latest.landmarks106[i] + velocity * targetDiff

            // 约束：预测位移不超过上一帧位移的 maxRatio 倍
            val actualDiff = rawPredicted - latest.landmarks106[i]
            val maxDiff = kotlin.math.abs(velocity * frameDiff * maxRatio)
            val clampedDiff = actualDiff.coerceIn(-maxDiff, maxDiff)

            predicted[i] = latest.landmarks106[i] + clampedDiff
        }

        return predicted
    }
}
```

---

## 4. 渲染管线改造

### 4.1 CameraPreviewRenderer 改造点

```kotlin
// 新增成员
private val frameSyncManager = FrameSyncManager.getInstance()
private var currentFrameId: FrameId = FrameId.INVALID

// 渲染循环改造
while (isRendering && !Thread.interrupted()) {
    if (!frameAvailable) { /* ... */ }

    surfaceTexture?.updateTexImage()
    frameAvailable = false

    // ─── 帧同步核心 ───
    currentFrameId = FrameId.next()
    val syncResult = frameSyncManager.query(currentFrameId)
    applySyncResultToRenderer(syncResult)
    // ────────────────

    beautyRenderer.onRender()
    // ...
}

private fun applySyncResultToRenderer(result: FrameSyncManager.FrameSyncResult) {
    when (result.syncStatus) {
        FrameSyncManager.SyncStatus.EXACT_MATCH,
        FrameSyncManager.SyncStatus.HISTORICAL_FALLBACK,
        FrameSyncManager.SyncStatus.PREDICTED -> {
            result.landmarks106?.let {
                // 直接更新 FaceMakeupPass 的 writeBuffer
                // 替换原有的 updateFacePoints106 路径
                beautyRenderer.updateSyncedFacePoints106(it)
            }
            beautyRenderer.setHasFace(true)
        }
        FrameSyncManager.SyncStatus.MISSING -> {
            beautyRenderer.setHasFace(false)
        }
    }

    // 调试指标透传
    latestPerfStats = latestPerfStats.copy(
        detectionLatencyMs = result.detectionLatencyMs,
        syncStatus = result.syncStatus.name,
        predictedOffsetPx = result.predictedOffsetPx
    )
}
```

### 4.2 BeautyRenderer 新增接口

```kotlin
class BeautyRenderer(private val context: Context) : GLRenderer() {
    // 新增：接收帧同步后的 106 点
    fun updateSyncedFacePoints106(landmarks106: FloatArray) {
        // 直接透传给 FaceMakeupPass，跳过旧的插值路径
        faceMakeupPass.updateFaceLandmarksSynced(landmarks106)
    }

    fun setHasFace(hasFace: Boolean) {
        this.hasFace = if (hasFace) 1f else 0f
    }
}
```

### 4.3 FaceMakeupPass 改造

```kotlin
class FaceMakeupPass(private val context: Context) {
    // 新增：帧同步入口（替换旧的双缓冲插值路径）
    fun updateFaceLandmarksSynced(landmarks106: FloatArray) {
        synchronized(bufferLock) {
            // 直接写入 writeBuffer，不再做时间插值
            // 帧同步已由 FrameSyncManager 完成
            writeBuffer.clear()
            writeBuffer.put(landmarks106)
            writeBuffer.flip()
            hasNewLandmarks = true
        }
    }

    // 保留旧接口用于降级模式
    fun updateFaceLandmarks(landmarks106: FloatArray) { /* ... */ }
}
```

---

## 5. 拍照与录制链路

### 5.1 拍照后处理（PhotoProcessorImpl）

拍照为单帧场景，帧同步退化为"有无人脸判断"：

```kotlin
fun processPhoto(imageProxy: ImageProxy): Bitmap {
    val bitmap = imageProxy.toBitmap()
    val frameId = FrameId.next()

    // 同步检测（拍照场景允许阻塞）
    val detectionResult = faceDetector.detectPhoto(bitmap, lensFacing)

    return if (detectionResult != null) {
        // 有人脸：正常渲染妆容
        frameSyncManager.storeResult(
            DetectionResult(frameId, detectionResult.landmarks, detectionResult.source)
        )
        gpuRenderer.renderWithSync(frameId)
    } else {
        // 无人脸：跳过妆容 Pass
        gpuRenderer.renderWithoutMakeup(bitmap)
    }
}
```

### 5.2 视频录制

视频录制复用预览同一套渲染管线，`CameraPreviewRenderer` 的帧同步逻辑自动覆盖录制输出。

**关键约束**：录制帧率固定 30fps，渲染到 `recordingWindowSurface` 时必须与预览帧使用相同的 `FrameSyncResult`。

---

## 6. 性能指标与监控

### 6.1 新增性能指标

```kotlin
data class BeautyPerfStats(
    val fps: Float = 0f,
    val processingMs: Int = 0,
    val delayMs: Int = 0,
    val cpuUsage: Float = 0f,
    val nullFrames: Int = 0,
    val errorCategory: String = "",
    val errorReason: String = "",
    // ─── 帧同步新增 ───
    val detectionLatencyMs: Long = 0L,      // 检测滞后时间
    val syncStatus: String = "",            // 同步状态
    val predictedOffsetPx: Float = 0f       // 预测补偿像素量
)
```

### 6.2 调试浮层展示

```
┌─────────────────────────────┐
│ FPS: 58.3  |  GPU: 4.2ms   │
│ Latency: 45ms | Sync: PRED  │  ← 新增
│ Offset: 3.2px | Face: ✓     │  ← 新增
└─────────────────────────────┘
```

### 6.3 日志字段

```
[FrameSync] frameId=1024, status=EXACT_MATCH, latency=32ms, source=MEDIAPIPE
[FrameSync] frameId=1025, status=PREDICTED, latency=48ms, offset=5.1px, framesSinceDetection=2
[FrameSync] frameId=1026, status=MISSING, hidden=true, framesSinceDetection=4
```

---

## 7. 线程安全模型

| 组件 | 所属线程 | 线程安全策略 |
|------|----------|-------------|
| `FrameId.next()` | 渲染线程 | AtomicLong，无锁 |
| `FrameSyncManager.bindFrameId()` | 渲染线程 | 无需同步 |
| `FrameSyncManager.storeResult()` | 检测线程 | ConcurrentHashMap.put |
| `FrameSyncManager.query()` | 渲染线程 | volatile + copy-on-write |
| `MotionTracker.update()` | 检测线程 | synchronized (history) |
| `MotionTracker.predict()` | 渲染线程 | synchronized (history) |

**关键保证**：
- `query()` 与 `storeResult()` 可并发执行，无需互斥
- `MotionTracker` 的 `update` 与 `predict` 需互斥（synchronized）
- 渲染线程每帧只读，检测线程只写，无死锁风险

---

## 8. 风险与降级策略

### 8.1 风险评估

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 检测队列积压 | 中 | 检测延迟增加 | 队列深度限制 + 超时丢弃 |
| 预测算法不稳定 | 低 | 妆容抖动 | 位移约束 + 可关闭预测 |
| 内存泄漏（Bitmap） | 低 | OOM | DetectionQueue 超时自动 recycle |
| 低端机性能 | 中 | 帧率下降 | 支持关闭帧同步（SyncMode.OFF） |

### 8.2 降级路径

```
FrameSyncManager 初始化失败
    └── 降级为 SyncMode.OFF
        └── 恢复当前双缓冲插值行为

检测线程崩溃
    └── FrameSyncManager 接收不到新结果
        └── query() 持续返回 MISSING
            └── BeautyRenderer 设置 hasFace=false
                └── 妆容隐藏，其他美颜正常

预测结果超出约束
    └── clamp 到最大允许位移
        └── 视觉上表现为"妆容慢半拍"，但不跳变
```

---

## 9. 实现顺序（建议）

### Step 1：FrameId 体系（1 天）
- 定义 `FrameId` value class
- 在 `CameraPreviewRenderer` 中生成并传递

### Step 2：FrameSyncManager 骨架（2 天）
- 实现 `ResultStore` + `MatchEngine`
- 实现 `query()` 的精确匹配 + 历史回退 + 缺失隐藏
- 单元测试覆盖

### Step 3：检测线程改造（2 天）
- `DetectionQueue` 实现
- `FaceDetectorManager` 改为消费队列
- 检测结果携带 FrameId

### Step 4：渲染管线对接（1 天）
- `CameraPreviewRenderer` 调用 `query()`
- `BeautyRenderer` 新增 `updateSyncedFacePoints106()`
- `FaceMakeupPass` 新增 `updateFaceLandmarksSynced()`

### Step 5：预测补偿（2 天）
- `MotionTracker` 实现速度外推
- 位移约束 + 参数调优

### Step 6：调试与验收（2 天）
- 调试浮层指标接入
- 多机型真机测试
- A/B 对比（开启/关闭帧同步）

---

## 10. 代码变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `beauty-engine/.../FrameId.kt` | 新增 | 全局帧 ID |
| `beauty-engine/.../FrameSyncManager.kt` | 新增 | 时序对齐核心 |
| `beauty-engine/.../MotionTracker.kt` | 新增 | 运动预测 |
| `beauty-engine/.../DetectionQueue.kt` | 新增（⏳ 设计中，未落地） | 检测任务队列 |
| `CameraPreviewRenderer.kt` | 修改 | 集成 FrameSyncManager |
| `BeautyRenderer.kt` | 修改 | 新增同步接口 |
| `FaceMakeupPass.kt` | 修改 | 新增同步入口 |
| `FaceDetectorManager.kt` | 修改（⏳ 设计中，未落地） | 改为消费队列 |
| `BeautyPerfStats.kt` | 修改 | 新增帧同步指标 |
| `GlBeautyPreviewProvider.kt` | 修改 | 透传帧同步配置 |

---

## 11. 相关文档

- 产品需求文档：`docs/01-PRODUCT/FEATURES.md`
- 美颜引擎架构：`docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`
- 产品交互规范：`docs/01-PRODUCT/FEATURES.md` Section 1.3
- 模块实现约束：`beauty-engine/AGENTS.md`
