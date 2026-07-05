# 相册自动 Tag 生成技术方案

> **状态**: 已实施  
> **最后更新**: 2026-06-30  
> **维护者**: RD Agent  
> **相关文档**: `GALLERY_SEARCH.md`（相册搜索 SSOT）、`TAG_DATABASE_SCHEMA.md`、`TAG_I18N_DESIGN.md`、`TAG_SCAN_STATE_MACHINE.md`、`TAG_GENERATION_PERFORMANCE_ANALYSIS.md`

---

## 1. 概述

### 1.1 目标

为相册中每张照片自动生成多维度标签（Tag），支撑 `MediaSearchEngine` 的自然语言搜索召回：

- **人脸维度**：照片中出现了「谁」（通过人脸检测 + 人脸 Embedding + DBSCAN 聚类）
- **内容维度**：照片的场景、物体、活动（通过 Qwen 图像理解）
- **语义维度**：MobileCLIP 图像-文本对齐 embedding（用于语义搜索）
- **ML Kit 快速标签**：英文物体/场景标签（用于补充召回）
- **时间/地点维度**：已有的 EXIF 元数据

### 1.2 核心模块

| 模块 | 文件 | 职责 |
|------|------|------|
| `TagGenerationScheduler` | `domain/tag/TagGenerationScheduler.kt` | 模型加载、单张处理、DBSCAN 聚类、OpenCL 守护 |
| `TagGenerationPipeline` | `domain/tag/TagGenerationPipeline.kt` | 单张照片的 5-Pass 原子处理 |
| `TagScanOrchestrator` | `domain/tag/scan/TagScanOrchestrator.kt` | 持久化任务队列、会话状态机、暂停/恢复/取消/重试 |
| `TagScanTaskEntity` | `data/local/entity/TagScanTaskEntity.kt` | 任务持久化实体及 `TagScanPass` 枚举 |
| `TagCategory` | `domain/tag/TagCategory.kt` | 用户可见类别与 Pass 阶段映射 |
| `OpenClGuardian` | `domain/tag/OpenClGuardian.kt` | Pass 3 前 warmup 与 OpenCL → CPU 降级 |
| `MobileClipEngine` | `domain/tag/MobileClipEngine.kt` | MobileCLIP-S0 语义编码 |
| `MlKitTagExtractor` | `domain/tag/MlKitTagExtractor.kt` | ML Kit Image Labeler 英文标签提取 |
| `FaceClusterEngine` | `domain/tag/FaceClusterEngine.kt` | Glint360K R100 Embedding + DBSCAN 聚类 |
| `TagNormalizer` / `ControlledVocab` | `domain/tag/TagNormalizer.kt` | Qwen 输出规范化与受控词表映射 |
| `TagGenerationService` | `service/tag/TagGenerationService.kt` | 前台 Service，驱动 Orchestrator 并暴露进度 |
| `TagGenerationControlScreen` | `features/gallery/components/TagGenerationControlScreen.kt` | 3-Pass 控制与按类别/时间范围重新生成 UI |

### 1.3 5-Pass 管道总览

```
照片入库 / 用户触发
        │
        ▼
┌─────────────────────────────────────────────┐
│ Pass 1: FACE_DETECTION                       │
│ • 人脸 ROI 检测 + 106 关键点                 │
│ • Glint360K R100 512 维人脸 Embedding         │
│ • MobileCLIP 语义 Embedding（Base64）        │
│ 写入: faceRoiResult / face_embeddings        │
│       semanticEmbedding / lastTagScanPasses  │
└─────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────┐
│ Pass 2: DBSCAN                               │
│ • 全局人脸聚类 → persons / faceId            │
└─────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────┐
│ Pass 3: QWEN_TAGGING                         │
│ • Qwen 3.5-2B 图像理解                       │
│ • 中文标签 + ControlledVocab 规范化          │
│ 写入: labels JSON                            │
└─────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────┐
│ Pass 5: ML_KIT_TAGGING                       │
│ • ML Kit Image Labeler 英文标签              │
│ 写入: mlKitLabels JSON                       │
└─────────────────────────────────────────────┘

Pass 4: MOBILE_CLIP_ENCODING（保留枚举值，用于兼容历史任务/单独重编码场景；
        常规扫描已将 MobileCLIP 内联合并到 Pass 1）
```

---

## 2. TAG 分类体系

| 层级 | 来源 | 存储字段 / 表 | 示例 |
|------|------|---------------|------|
| L1 人脸 | FaceDetector + FaceClusterEngine | `faceRoiResult` JSON、`face_embeddings` 表、`persons` 表、`media_assets.faceId` | `hasFace=true`, `faceCount=2`, `personId=3` |
| L2 内容 | Qwen 3.5-2B | `media_assets.labels` JSON | `scene=户外`, `activity=旅行`, `tags=["风景","山"]` |
| L3 元数据 | EXIF / MediaStore / 逆地理编码 | `captureDate`, `locationName`, `source`, `latitude`/`longitude` | `time:afternoon`, `location:北京` |
| L4 ML Kit 标签 | ML Kit Image Labeler | `media_assets.mlKitLabels` JSON | `["Outdoor","Food","Plant"]` |
| L5 语义向量 | MobileCLIP-S0 | `media_assets.semanticEmbedding` Base64 | 512 维 L2 归一化向量 |

### 2.1 L1 人脸标签

| 标签 | 来源 | 示例值 |
|------|------|--------|
| `has_face` | ROI 检测 | `true` / `false` |
| `face_count` | ROI 检测 | `1` / `2` / `3+` |
| `person:{name}` | 人脸聚类 + 用户命名 | `person:张三` |
| `group_photo` | `face_count >= 2` | `true` |
| `selfie` | `face_count == 1` | `true` |

### 2.2 L2 内容标签（Qwen 产出）

Qwen 输出经 `TagNormalizer` 映射到 `ControlledVocab` 后写入 `labels` JSON：

| 类别 | 字段 | 示例 |
|------|------|------|
| 场景 | `scene` | `户外`、`办公室`、`海边` |
| 活动 | `activity` | `旅行`、`吃饭`、`运动` |
| 物体 | `objects` | `["山","天空"]` |
| 标签 | `tags` | `["风景","旅行","户外"]` |
| 摘要 | `summary` | `一家人在公园野餐的温馨场景` |

> **语言策略**：Qwen 统一输出中文；未命中受控词表的原始词保留在 `nonStandard` 中，仍然可被 LIKE 搜索命中。

### 2.3 L3 元数据标签

| 标签 | 来源 | 示例值 |
|------|------|--------|
| `time:morning/afternoon/evening/night` | `captureDate` | `time:afternoon` |
| `season:spring/summer/autumn/winter` | `captureDate` | `season:summer` |
| `source:{source}` | `MediaAsset.source` | `source:wechat` |
| `location:{name}` | `locationName` | `location:北京` |

### 2.4 L4 ML Kit 英文标签

- ML Kit `ImageLabeler` 输出英文标签，按置信度过滤后写入 `mlKitLabels` JSON 数组。
- 不随存储翻译，避免引入额外延迟与翻译错误。
- 跨语言搜索由 `QuerySegmenter` / `MediaSearchEngine` 的 LLM 语义解析层处理，或依赖独立翻译映射（见 `TAG_I18N_DESIGN.md`）。

---

## 3. 执行管道（Pipeline）设计

### 3.1 Pass 1：人脸检测 + Embedding + MobileCLIP 语义编码

**输入**: 照片 URI  
**输出**: `Stage1WithEmbeddingsResult`

```kotlin
suspend fun stage1WithEmbeddings(
    uri: String,
    lensFacing: Int,
    mediaId: Long
): Stage1WithEmbeddingsResult
```

**处理步骤**:

1. 加载 Bitmap（最长边限制，避免 OOM）
2. 人脸 ROI 检测 → `Stage1Result`（`hasFace` / `faceCount` / `roiRects`）
3. 对每张人脸：
   - 用 106 关键点做仿射对齐 → 112×112 ROI
   - `FaceClusterEngine.extractFeature()` → 512 维 Glint360K R100 embedding
   - 写入 `face_embeddings` 表
4. `MobileClipEngine.encodeImage()` → 512 维语义向量 → Base64
5. 写入 `media_assets.faceRoiResult` / `semanticEmbedding` / `hasFace`

### 3.2 Pass 2：DBSCAN 全局聚类

- 读取所有未分配 `personId` 的 `face_embeddings`
- 余弦距离 + DBSCAN 参数：`DBSCAN_EPS`、`DBSCAN_MIN_PTS`
- 生成 `persons` 记录，更新 `face_embeddings.personId` 与 `media_assets.faceId`
- 对仅含一张照片的人脸直接新建单簇

### 3.3 Pass 3：Qwen 图像理解标签生成

**模型**: Qwen 3.5-2B（MNN-LLM 运行时）  
**输入**: 照片 URI + `faceRoiResult` 人脸上下文  
**输出**: `QwenTags` → `TagNormalizer` → `UnifiedTagResult` JSON

**Prompt 约束**:
- 要求输出 JSON：`scene` / `activity` / `objects` / `tags` / `summary`
- 所有字段使用中文（专有名词除外）
- `tags` 3-5 个中文关键词

**OpenCL 守护**:
- `OpenClGuardian.warmup()` 在 Pass 3 推理前执行
- 单次推理带超时；连续失败/超时后标记设备降级 CPU，黑名单持久化到 DataStore

### 3.4 Pass 4：MOBILE_CLIP_ENCODING（保留兼容）

- `TagScanPass.MOBILE_CLIP_ENCODING` 保留枚举值
- 常规扫描已将 MobileCLIP 编码内联合并到 Pass 1，避免重复解码
- 仅用于：
  - 兼容历史任务记录
  - 单独对旧数据补全语义 embedding 的场景

### 3.5 Pass 5：ML Kit 英文标签提取

- `MlKitTagExtractor.extract(uri)` → 英文标签列表
- 置信度阈值默认 `0.5`，最大数量 `5`
- 结果写入 `media_assets.mlKitLabels` JSON 数组

---

## 4. 队列编排与生命周期

### 4.1 TagScanOrchestrator

负责将一次扫描拆分为可持久化的原子任务，支持：

- `scheduleAutoScan(policy)` — 自动增量扫描
- `scheduleRegenerate(mediaIds, categories, mode)` — 对指定媒体重新生成指定类别
- `scheduleRegenerateByQuery(query, categories, mode)` — 按时间范围查询批量生成
- `schedulePass(pass, query, mode, policy)` — 执行单个 Pass 阶段
- `pause()` / `resume()` / `cancel()` — 会话生命周期控制

### 4.2 任务持久化

```kotlin
@Entity(tableName = "tag_scan_tasks")
data class TagScanTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val mediaId: Long,
    val pass: TagScanPass,                 // FACE_DETECTION / DBSCAN / QWEN_TAGGING / MOBILE_CLIP_ENCODING / ML_KIT_TAGGING
    val tagCategories: String? = null,     // 目标类别 JSON 数组
    val status: TagScanTaskStatus = PENDING,
    val priority: Int = 0,
    val attemptCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val scheduledAt: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null
)
```

### 4.3 增量去重策略

- 通过 `media_assets.lastTagScanPasses` JSON 判断已覆盖的 Pass
- `ScanQueuePolicy.skipRecentlyTaggedMs` 控制避重窗口（默认 24h）
- `ScanQueuePolicy.order` 支持 `OLDEST_FIRST` / `NEWEST_FIRST`
- 启动时自动将异常中断的 `RUNNING` 任务重置为 `PENDING`

### 4.4 类别到 Pass 映射

| TagCategory | 映射 Pass |
|-------------|-----------|
| `FACE` | `FACE_DETECTION` + `DBSCAN` |
| `SCENE` / `ACTIVITY` / `OBJECTS` / `TAGS` / `SUMMARY` | `QWEN_TAGGING` |
| `ML_KIT_LABELS` | `ML_KIT_TAGGING` |

---

## 5. OpenCL 超时与 CPU 降级

```kotlin
class OpenClGuardian(
    context: Context,
    engine: LocalLlmEngine,
    prefs: UserSettingsRepository
)
```

- **Warmup**: Pass 3 开始前执行一次短推理，确认 OpenCL 可用
- **超时**: 单次 Qwen 推理带超时，防止 OpenCL 挂起
- **降级**: 连续失败/超时后标记设备降级 CPU，黑名单写入 DataStore
- **恢复**: 用户可在设置中手动重置，或每周自动尝试一次
- **模型加载**: `TagGenerationScheduler.ensureModelLoaded()` 按 Guardian 策略自动选择 OpenCL / CPU

---

## 6. 数据模型

当前数据库版本：**6**（Room `@Database(version = 6)`）。

核心表与字段详见 `TAG_DATABASE_SCHEMA.md`，本方案涉及的关键字段：

| 字段 | 表 | 说明 |
|------|-----|------|
| `labels` | `media_assets` | Qwen 中文标签 JSON |
| `mlKitLabels` | `media_assets` | ML Kit 英文标签 JSON |
| `faceRoiResult` | `media_assets` | 人脸 ROI 检测 JSON |
| `semanticEmbedding` | `media_assets` | MobileCLIP 512 维向量 Base64 |
| `lastTagScanAt` / `lastTagScanPasses` | `media_assets` | 增量去重 |
| `face_embeddings.embedding` | `face_embeddings` | Glint360K R100 512 维 ByteArray |
| `persons.name` / `faceCount` | `persons` | 人物簇 |
| `tag_scan_tasks.*` | `tag_scan_tasks` | 扫描任务队列 |

---

## 7. 性能预算

| Pass | 单张耗时 | 可并行 | 备注 |
|------|----------|--------|------|
| Pass 1 人脸检测 | ~30-80ms | ✅ | 依赖 FaceDetector 后端 |
| Pass 1 MobileCLIP | ~50-100ms | ✅ | 已内联合并，无额外解码 |
| Pass 1 Embedding | ~30-60ms | ✅ | Glint360K R100 512 维 |
| Pass 2 DBSCAN | ~5-20ms/对比 | ❌ | 全局依赖 |
| Pass 3 Qwen | ~2-8s | ❌ | 独占调度器，OpenCL 优先 |
| Pass 5 ML Kit | ~50-200ms | ✅ | 首次可能触发模型下载 |

**优化措施**:
- 每处理 `BATCH_SIZE=10` 张强制冷却 `BATCH_COOLDOWN_MS=15s`
- 增量扫描单次上限 `INCREMENTAL_MAX_PHOTOS=50`
- Pass 3 串行执行，避免多实例 GPU 冲突

---

## 8. 与搜索集成

Tag 生成是 `MediaSearchEngine` 的底层索引层：

- **显式召回**：`ExplicitFirstSearchPipeline` 读取 `labels`、`mlKitLabels`、`faceRoiResult`、`captureDate`、`locationName`、`ocrText` 等字段构造 SQLite 查询
- **语义召回**：`SemanticSearchEngine` 读取 `semanticEmbedding` 做余弦相似度匹配
- **融合排序**：`MediaSearchEngine.mergeAndRank()` 将显式结果与语义结果融合，显式命中优先

完整搜索链路见 `GALLERY_SEARCH.md`。

---

## 9. 风险与 Mitigation

| 风险 | 影响 | Mitigation |
|------|------|------------|
| Qwen 推理耗时过长 | 全量扫描慢 | 后台 Service + 可暂停/取消 + 批次冷却 |
| OpenCL 兼容性问题 | 部分设备挂起 | `OpenClGuardian` warmup + 超时降级 CPU + DataStore 黑名单 |
| 人脸聚类误判 | 同一人分成多簇 | 支持用户手动合并/重命名；DBSCAN 参数调优 |
| 标签质量不可靠 | 搜索召回差 | 受控词表规范化 + 只生成场景级粗粒度标签 + 用户可触发重生成 |
| 电量消耗 | 后台扫描费电 | 仅充电+Wi-Fi 时自动全量扫描；运行时电池/热状态守卫 |
| ML Kit 英文标签与中文 Query 语言不一致 | 中文搜不到英文标签 | `QuerySegmenter` / LLM 语义解析层负责跨语言桥接；不存储翻译 |

---

## 10. 关键接口定义

### 10.1 TagGenerationScheduler（简化）

```kotlin
class TagGenerationScheduler(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    guard: suspend () -> GuardResult = { GuardResult.ALLOW }
) {
    val isScanning: StateFlow<Boolean>
    val progress: StateFlow<TagScanProgress?>
    val lastScanMessage: StateFlow<String?>

    suspend fun processSingle(uri: String, mediaId: Long)
    suspend fun ensureModelLoaded(): Boolean

    enum class GuardResult { ALLOW, PAUSE, ABORT }
}
```

> 旧版 `scanAll()` / `scanIncremental()` / `scanPass1/2/3()` 已标记 `@Deprecated`，请使用 `TagScanOrchestrator` 替代。

### 10.2 TagScanOrchestrator（简化）

```kotlin
class TagScanOrchestrator(
    context: Context,
    scheduler: TagGenerationScheduler
) {
    val progress: StateFlow<TagScanSessionProgress?>

    suspend fun scheduleAutoScan(policy: ScanQueuePolicy = ScanQueuePolicy()): String
    suspend fun scheduleRegenerate(mediaIds, categories, mode, policy): String
    suspend fun scheduleRegenerateByQuery(query, categories, mode): String
    suspend fun schedulePass(pass, query, mode, policy): String
    fun pause()
    fun resume()
    fun cancel()
}
```

---

## 11. 后续迭代方向

| 方向 | 说明 |
|------|------|
| 标签质量提升 | 优化 Qwen Prompt、扩充 `ControlledVocab`、引入用户反馈修正 |
| 人物簇管理 | UI 支持合并、拆分、忽略误检簇 |
| ML Kit 跨语言 | 完善英文标签 → 中文的翻译映射或语义桥接 |
| 语义召回增强 | 尝试更大 MobileCLIP 变体或端侧多模态模型 |
| 自动化测试 | 增加端到端 Tag 生成回归测试与性能基线 |

---

> **维护者**：RD Agent  
> **状态**：已实施  
> **最后更新**：2026-06-30
