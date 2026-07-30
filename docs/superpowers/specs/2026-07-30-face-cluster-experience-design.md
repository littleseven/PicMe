# 人物聚类体验改进（时机 + 入口 + 命名）设计

> **状态**：设计稿，待写实现计划
> **日期**：2026-07-30
> **目标**：① 大相册里人物聚类尽早可见（Pass1 期间攒批流式聚类，不再等 Pass1 全部跑完）；② 聚类命名轻量、入口显眼（统一「人物」页，按人脸封面直命名；相册有直达入口；设置有一级入口）。

## 1. 背景

用户反馈两点：

1. **聚类太晚**：自动扫描是 3-Pass 结构。Pass1（`executeFaceDetection` → `stage1WithEmbeddings`）只做人脸检测 + embedding 提取并把 embedding 存表，**不聚类**；Pass2（DBSCAN）在 `createTasks` 里是 `mediaId=-1` 的单个全局任务、`priority=1`，即**每批 50 张 Pass1 跑完才触发一次**。大相册用户看到人物归类出现得很晚。
   - 关键发现：单张新照片路径 `processSingle` 里其实有现成的「边检测边聚类」(`stage2FaceCluster` → `FaceClusterEngine.match/add/create`)，但**没接到批量扫描**——可直接复用。
2. **入口隐晦 + 命名难**：
   - 没有独立「人物」页。唯一能重命名聚类的地方：相册切到「按人物分组」→ 点分组标题 → 弹对话框。多图时入口隐晦、要在分组里翻找。
   - 设置 → AI 记忆里只有"已声明的关系"文本行（`PersonRelationRow`），**不能改名、无代表图**；未声明关系的人物聚类不显示。
   - 数据层 `PersonEntity.coverMediaId` 已存在，但当前取值是 `mediaIds.firstOrNull()`（基本随机），未按人脸清晰度/美学挑选，也没在任何列表里露出用于命名。

## 2. 决策摘要（已锁定）

| 维度 | 决策 |
|---|---|
| 聚类时机 | **双层**：Pass1 流式攒批聚类（默认每 ~20 张含人脸图触发）+ DBSCAN 周期精修 |
| 身份稳定性 | **允许精修重排**：DBSCAN 周期全量精修追求最优聚类，接受人物身份偶发重排；已命名人物/关系用现有快照逻辑保留 |
| 人物页结构 | **独立「人物」页** + 相册直达入口 + 设置新一级「人物」入口（从 AI 记忆拆出，AI 记忆专注事实记忆） |
| v1 范围 | **仅命名 + 浏览**（改名/标关系/标"我" + 看该人照片）；不含手动合并/拆分 |
| 美学评分时机 | **A1 后台独立打分器**（不拖慢 Pass1，分数可复用） |
| NIMA 模型交付 | **B1 模型中心"推荐"层 + WiFi 静默预下载**（APK 不增大；未下载时封面回退旧逻辑） |
| 封面展示 | **人脸裁剪缩略图**（复用 `faceRoiResult` 的 ROI） |

## 3. 设计

### 3.1 Pass1 流式攒批聚类（G1）
- 触发点：`TagScanOrchestrator.executeTask` 的 `FACE_DETECTION` 分支。Pass1 每完成一张**含人脸**的媒体，计入会话内计数器；累计达到 `ClusteringConfig.STREAMING_CLUSTER_BATCH`（默认 **20**，可调）触发一次增量聚类。
- 聚类动作：复用 `FaceClusterEngine.matchCluster/addToCluster/createCluster`（即 `processSingle.stage2FaceCluster` 与 `FaceClusteringWorker.doStreamingCluster` 已验证逻辑），对本批新 embedding 匹配/归簇/建簇。
- 计数口径：只统计"本批新产生且未归簇"的 embedding；空批/无脸不计。
- 结果：Pass1 期间人物即落库可见，不必等 Pass2。

### 3.2 DBSCAN 降级为周期精修（G1）
- Pass2 的 DBSCAN **不再每批 50 强制跑**，触发条件改为：① 距上次精修超过阈值；② 流式新簇数超阈值；③ 空闲时。仍走 `executeDbscan` 对全量 embedding 重聚类，纠正流式的碎片/错分（允许身份重排），通过 `buildNamedPersonSnapshots` + `buildRelationSnapshots` 保留命名与关系。
- 流式与 DBSCAN 共用 `FaceClusterEngine` + `face_embeddings` 表，不重复造轮子。

### 3.3 NIMA 美学打分 + 封面选择（A1 + B1）
- 模型：**NIMA (Neural Image Assessment)** + MobileNetV2，**int8 量化 TFLite ≈ 3.5–4 MB**。无官方现成 `.tflite`，需由 SavedModel（`titu1994/neural-image-assessment` / `idealo/image-quality-assessment` 权重）一次性转换。推理 ~10–20ms/张 @224。
- 交付（B1）：接入现有模型中心"推荐"层，走 `RecommendedModelAutoDownloader` WiFi 静默预下载（与 tagger 同模式）。
- 打分时机（A1）：**独立低优先级后台打分器**，聚类后给簇内成员打分、缓存分数、刷新封面。不阻塞 Pass1；分数可复用（未来"精选/最佳照片"）。
- 封面选择：某人物 `coverMediaId` = 该簇成员中 `aestheticScore` 最高者（且含脸、人脸尺寸达标）。
- 展示：人物页封面用 `faceRoiResult` 的 ROI 做人脸裁剪缩略图，便于"看脸认人"。

### 3.4 人物页 UI（G2）
- 新建 `features/person/PersonScreen.kt` + `PersonViewModel`。
- 封面网格：`LazyVerticalGrid`，每格 = 人脸裁剪缩略图 + 名字（无名显示"人物 #id"）+ 照片数角标。
- 点封面 → 复用现有重命名对话框（名字 + `PersonRelationPicker`「TA 是我的…」+「这是我」）。把 `GalleryScreen` 里那份对话框抽成公共组件复用。
- 点进看照片：复用 `GroupingMode.PERSON` 既有能力，按 `personId` 过滤网格。
- 排序：已命名优先 / 按照片数 / 按最近出现。

### 3.5 入口与导航（G2）
- 相册：`GalleryTopBar` 加「人物」图标 action → 导航到 `PersonScreen`。新增 `Screen.Person` 路由。
- 设置：`SettingsScreen` 新增一级项「人物」（`SettingsBaseComponents.CategoryGridItem`，与"AI 记忆"并列）。`MemoryFactsScreen` 移除人物关系 section，专注事实记忆；人物关系编辑迁入人物页重命名对话框（已含 Picker）。

## 4. 数据模型变更（Room v18 → v19）

- `media_assets` 新增 `aestheticScore: Float?` 列（默认 null）。迁移 v18 → v19：`ALTER TABLE media_assets ADD COLUMN aestheticScore REAL`。
- `persons.coverMediaId` 复用（已存在）；新增封面刷新逻辑由后台打分器写入。
- 评估是否需要在 `persons` 上缓存 `coverFaceRoi`（裁剪坐标）以加速网格渲染——若 `faceRoiResult` 解析成本低则不缓存，保持 YAGNI。

## 5. 数据流

```
Pass1（人脸检测+embedding）
   │ 每累计 ~20 张含脸图
   ▼
流式攒批聚类 (FaceClusterEngine.match/add/create)  →  人物尽早可见
   │
   ▼
DBSCAN 周期精修 (executeDbscan，保留命名/关系快照)  →  纠偏/合并，允许重排
   │
   ▼
后台美学打分器 (NIMA TFLite)  →  media_assets.aestheticScore  →  persons.coverMediaId = 簇内 max
   │
   ▼
人物页：人脸裁剪封面网格（coverMediaId + faceRoiResult ROI）→ 点封面改名/标关系/标"我"
```

## 6. 错误处理 / 降级
- NIMA 模型未下载/失败 → 封面回退 `mediaIds.firstOrNull()`，人物页照常可用（封面不优）。
- 流式聚类异常 → try/catch 降级，不影响 Pass1 主流程，留给 DBSCAN 兜底。
- DBSCAN 重排致已命名人物短暂消失 → 现有快照恢复机制兜底；UI 用 `personId` 稳定 key 避免列表跳动。
- 美学分数缺失 → 该成员不计入封面竞选。

## 7. 文件清单（预估）

| 文件 | 职责 |
|---|---|
| `domain/tag/scan/TagScanOrchestrator` | Pass1 攒批计数 + 触发流式聚类；DBSCAN 触发节流 |
| `domain/tag/ClusteringConfig` | 新增 `STREAMING_CLUSTER_BATCH`、DBSCAN 精修阈值 |
| `data/indexing/AestheticScoreWorker`（新） | 后台 NIMA 打分 + 刷新 coverMediaId |
| `domain/aesthetic/NimaScorer`（新） | TFLite NIMA 推理封装 |
| `data/download/RecommendedModelAutoDownloader` | 注册 NIMA 为"推荐"层模型 |
| `data/local/entity/MediaEntity` + `AppDatabase` | 新增 `aestheticScore` 列，v18→v19 迁移 |
| `features/person/PersonScreen` + `PersonViewModel`（新） | 人物封面网格 + 重命名/关系/我 + 看照片 |
| `features/common/PersonRenameDialog`（抽公共） | 从 GalleryScreen 抽出复用 |
| `features/gallery/components/GalleryTopBar` | 加「人物」入口 action |
| `features/settings/SettingsScreen` + `MemoryFactsScreen` | 新增一级「人物」；AI 记忆移除人物关系 section |
| `MainActivity` + `Screen` | 新增 `Screen.Person` 路由 |

## 8. 测试策略
- **JVM 单测**：攒批计数器达阈触发（用现有 `coroutines-test` 帧驱动经验：`advanceTimeBy` 后 `runCurrent`、默认 StandardTestDispatcher）；封面选择 = max aesthetic 且含脸；DBSCAN 精修触发节流条件；命名/关系写入幂等。
- **隔离单测**：`FaceClusterEngine` 流式归簇与 DBSCAN 结果一致性（不回归现有聚类）。
- **设备验证**：大相册观察人物出现时点；人物页命名/入口用 `/ui-driver`（accessibility 驱动，非截图/录屏）。

## 9. 文档与 i18n（强制同步）
- 三层文档：`PRODUCT.md`（人物章节状态）、`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`（扫描阶段双层聚类）、`docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`（若有 person 相关能力）。
- i18n：所有新文案同步 **4 套** `values/`、`values-zh/`、`values-zh-rCN/`、`values-zh-rTW/`。
- 红线：NIMA 100% 端侧，符合隐私（不上传图片）。

## 10. 不做（v1 out of scope）
- 手动合并/拆分聚类（错分交给 DBSCAN 精修自动纠正）。
- 关系图谱可视化。
- 跨设备同步。
- 美学评分用于搜索/精选等其他场景（仅先存分数，不展开功能）。
