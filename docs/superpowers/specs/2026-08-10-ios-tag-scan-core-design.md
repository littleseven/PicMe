# iOS TAG 扫描核心（SP-B）设计

> 📜 **SP-B 历史设计**。已实现并扩展到 SP-C/SP-D（Pass2/Pass3），🔄 **in-flight 分支 `feat/ios-tag-scan-core` 待合并**（Pass3 待真机验证 266MB 模型）。⚠️ §17-21「Pass2/3 各自独立 spec」、§8.2/§11「控件置灰」非目标已不成立。现行状态见 `IOS_TASK_STATUS.md` §6.1。归类见 `docs/01-PRODUCT/IOS_DOC_INDEX.md` §2.3。


- **日期**：2026-08-10
- **状态**：待评审
- **平台**：iOS（`iosApp/`）
- **子项目**：SP-B（4 子项目计划中的第 1 个）
- **关联**：完整 TAG 扫描对标 Android 的第 1 步；后续 SP-A（Metal）/SP-C（Pass2 聚类）/SP-D（Pass3 打标）各自独立 spec

---

## 1. 背景与目标

iOS 侧人脸检测（Pass 1：RetinaFace + 2D106 + Glint360K 嵌入 + MobileCLIP）已在 CPU 上跑通（`Pass1Pipeline`），但**没有任何调用方**、**没有扫描页**、**没有任务系统**，扫描按钮目前弹「Coming Soon」。Android 侧的 TAG 扫描（`TagGenerationService` + `TagScanOrchestrator` + Room 任务队列 + `TagGenerationControlScreen`）已全量上线。

**SP-B 目标**：在 iOS 上把 Pass 1 跑遍整个相册，配一个扫描页 + 后台任务系统，对标 Android 的扫描体验与数据模型。

**SP-B 非目标**（明确推迟）：
- Metal/GPU 加速 → SP-A
- Pass 2（DBSCAN 人物聚类）功能 → SP-C
- Pass 3（图像内容打标 Florence-2/Qwen）功能 → SP-D
- `BGTaskScheduler` 后台续扫、视频扫描、`mlKitLabels`/`ocrText`/地理列回填 → 后续迭代
- 注：扫描页 **UI 完全复刻 Android**（含 Pass2/Pass3 控件），但依赖 Pass2/Pass3 的控件在 SP-B 置灰，功能随 SP-C/SP-D 激活。

## 2. 关键决策

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| D1 | 平台 | iOS | 人脸检测刚在 iOS 跑通；Android 侧已上线 |
| D2 | 范围 | 完整对标 Android（4 子项目分期） | 用户选择 |
| D3 | Metal 路线 | 重验精度（SP-A），SP-B 用 CPU | Metal 曾因精度异常被禁用，需独立复验 |
| D4 | **mediaId 模型** | **Int64 + `media_assets` 映射表，完全对齐 Android** | **面向 LLM 一套统一的数字 mediaId 模型**（能力层/tool schema 跨平台一致） |
| D5 | 数据库 | 扫描列并入 `media_assets`（Android 式单表），废弃 `media_tags` | 用户要求「数据库完全与 Android 对齐」 |
| D6 | 后台策略 | 前台优先（SP-B）；`BGTaskScheduler` 推迟 | CPU 上先跑通可控；后台续扫另开 |
| D7 | 入口 | 复用 `MainTabView` 的 TAG tab + 相册扫描图标 secondary | TAG tab 当前是死占位，正好激活 |

## 3. 现状基线（iOS ground truth）

- `Pass1Pipeline.swift`：单例，`process(_ image: UIImage, mediaId: Int64) -> Pass1Result` **单图同步**，非线程安全（MNN 要求单线程串行）；写 `face_embeddings` + `media_tags`。**无任何调用方**。
- `TagDatabase.swift`：raw sqlite3（非 GRDB），单例，串行 `DispatchQueue`。三表：`face_embeddings`、`media_tags`、`persons`。**无任务队列表**。
- 后台执行：**完全缺失**（无 `BGTaskScheduler`、无 `beginBackgroundTask`、无 `UIBackgroundModes`、无 AppDelegate；纯 SwiftUI `@main`）。
- 扫描按钮：`GalleryGridView.swift:112-115` 弹「Coming Soon」toast，不导航不调管线。
- 导航：自定义 `ZStack` 页面切换（`MainTabView`），无 `NavigationStack`/Router；全屏推送用 `.fullScreenCover`。
- PhotoKit：`PhMediaBridge.fetchAllMedia()` 返回 `[IosMediaItem]`（含 `localIdentifier: String`，newest-first）；`ThumbnailLoader.shared.thumbnail(for:size:)` 是 `@MainActor`。
- `mediaId` 现状割裂：`TagDatabase` 用 Int64，PhotoKit/`PersonStore` 用 String `localIdentifier`，无映射表。

## 4. 架构总览

```
TAG tab / 相册扫描图标
        ↓
TagScanScreen (SwiftUI)  ←观察→  TagScanViewModel (@ObservableObject)
                                        ↓ start/pause/resume/cancel
                                TagScanOrchestrator (状态机 + 运行循环)
                                   │            ↑ 协作式 pause/cancel 标志
                                   │            │
                        ┌──────────┘            └── 单一后台 Task(.utility)
                        ↓
                   TagDatabase (sqlite3)
                   ├ media_assets      ← get-or-create(localIdentifier→Int64) + 扫描列
                   ├ tag_scan_tasks    ← 任务队列 (poll/mark*)
                   ├ face_embeddings   (不变)
                   └ persons           (不变)
                        ↓ pollNextPendingBySession(mediaId)
                Pass1Pipeline.process(image, mediaId:)   ← 非主线程图片加载
                        ↓
                   写回 media_assets + face_embeddings
```

四层：**数据层（扩 TagDatabase）→ 编排层（TagScanOrchestrator）→ 后台/线程（前台优先）→ UI/接入（TagScanScreen）**。

## 5. 数据层设计（完全对齐 Android）

### 5.1 新建 `media_assets` 表（对齐 `MediaEntity`，扫描列并入，废弃 `media_tags`）

```sql
CREATE TABLE media_assets (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,   -- 全局数字主键，面向 LLM
  uri               TEXT NOT NULL,                       -- iOS: 存 PhotoKit localIdentifier
  type              TEXT NOT NULL,                       -- 'IMAGE' | 'VIDEO'（对齐 MediaType）
  captureDate       INTEGER NOT NULL,
  fileName          TEXT NOT NULL,
  duration          INTEGER,                             -- 视频时长 ms；图片 NULL
  hasFace           INTEGER DEFAULT 0,                   -- 0/1
  faceId            TEXT,
  source            TEXT,
  labels            TEXT,                                -- JSON 数组（中文别名）
  labelsEn          TEXT,                                -- JSON（tagger 英文原语；SP-D 回填）
  labelsZh          TEXT,                                -- JSON（离线汉化；SP-D 回填）
  mlKitLabels       TEXT,                                -- iOS 无对应，列保留对齐，SP-B 不写
  mlKitLabelsZh     TEXT,
  ocrText           TEXT,                                -- SP-B 不写
  latitude          REAL,
  longitude         REAL,
  locationName      TEXT,
  city              TEXT,
  indexedAt         INTEGER,
  faceRoiResult     TEXT,                                -- Pass1 写
  faceFocusY        REAL,                                -- Pass1 写
  aestheticScore    REAL,
  faceQualityScore  REAL,
  semanticEmbedding TEXT,                                -- Pass1 写（MobileCLIP Base64）
  lastTagScanAt     INTEGER,
  lastTagScanPasses TEXT,                                -- Pass1 写 {"1":ts}
  localIdentifier   TEXT UNIQUE                          -- 冗余唯一索引，加速 get-or-create（= uri，显式列出便于查询）
);
CREATE INDEX idx_media_assets_captureDate ON media_assets(captureDate);
CREATE INDEX idx_media_assets_hasFace ON media_assets(hasFace);
```

> 说明：`uri` 与 `localIdentifier` 存同一值（localIdentifier）。保留 `uri` 列名是为列级对齐 Android；额外加 `localIdentifier UNIQUE` 是为 get-or-create 的唯一约束与查询。二者冗余但语义清晰、对齐优先。

**映射策略（get-or-create，SP-B 最简版）**：扫描启动时 `PhMediaBridge.fetchAllMedia()` 全量取列表，逐条 `INSERT OR IGNORE` 再 `SELECT id WHERE localIdentifier=?` 拿 Int64。`PHPhotoLibraryChangeObserver` 增量同步**留到后续**——个人相册每次启动全量 upsert 几千行足够快。

**废弃 `media_tags`**：其列（hasFace/faceRoiResult/faceFocusY/semanticEmbedding/labels_en/labels_zh/last_scan_passes）已全部并入 `media_assets`。`TagDatabase.updateMediaTags(...)` 改写为 `updateMediaAssets(mediaId:hasFace:faceRoiResult:faceFocusY:semanticEmbedding:lastTagScanPasses:)`。`Pass1Pipeline.process` 的写入目标随之改为 `media_assets`。

### 5.2 新建 `tag_scan_tasks` 表（对齐 `TagScanTaskEntity`）

```sql
CREATE TABLE tag_scan_tasks (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  sessionId     TEXT NOT NULL,
  mediaId       INTEGER NOT NULL,
  pass          TEXT NOT NULL,                -- 'FACE_DETECTION'|'DBSCAN'|'IMAGE_TAGGING'|'MOBILE_CLIP_ENCODING'
  tagCategories TEXT,                         -- JSON；SP-B 全 null
  status        TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING|RUNNING|PAUSED|COMPLETED|FAILED|CANCELLED
  priority      INTEGER NOT NULL DEFAULT 0,
  attemptCount  INTEGER NOT NULL DEFAULT 0,
  createdAt     INTEGER NOT NULL,
  scheduledAt   INTEGER,
  startedAt     INTEGER,
  completedAt   INTEGER,
  errorMessage  TEXT
);
CREATE INDEX idx_tasks_sched ON tag_scan_tasks(status, priority, scheduledAt);
CREATE INDEX idx_tasks_media ON tag_scan_tasks(mediaId, pass, status);
CREATE INDEX idx_tasks_session ON tag_scan_tasks(sessionId, status);
```

`pass` / `status` 存 TEXT（枚举 case 名），可读性优于 ordinal。SP-B 仅产生 `FACE_DETECTION` 任务。

DAO 方法（对齐 Android `TagScanTaskDao`）：`pollNextPendingBySession(sessionId)`, `markRunning/markCompleted/markFailed`, `pauseSession/resumeSession/cancelSession`, `resetRunningToPending`, `countByStatus[AndPass]`, `cleanupOldCompleted`。

### 5.3 统计查询

新增 `getStats() -> ScanDbStats`（对齐 `TagScanDbStats`）：`totalMedia, withFace, withSemantic, faceEmbeddingCount, remainingForPass1`（SP-B 只用到这些；`withLabels/remainingForPass3/personCount` 字段保留返回 0/占位，SP-C/D 再填）。

### 5.4 不变

`face_embeddings`、`persons` 表结构不变（已用 Int64 `media_id` 外键，新映射后自动正确）。

> 已知遗留：`TagDatabase`（raw sqlite3, `polang_tag.db`）与 `PersonStore`（GRDB, `polang_person.sqlite`）是两个独立库文件。SP-B 不统一这两库，仅在 `polang_tag.db` 内扩展。

## 6. 编排层（新 `TagScanOrchestrator.swift`）

镜像 Android `TagScanOrchestrator`，Swift 原生并发实现。

- **状态机 `ScanSessionState`**：`idle → running → (pausing → paused → resume → running) → (cancelling → cancelled) | completed`。终态（cancelled/completed）不可被非终态覆盖。
- **扫描模式**（对齐 Android SCAN_ALL / SCAN_INCREMENTAL，二选一，由扫描页入口决定）：
  - `incremental`（默认）：只扫 `lastTagScanPasses` 不含 `"1"` 的图片（去重）。
  - `full`：忽略覆盖位，所有图片重排为 PENDING（重扫）。`media_assets` 已有数据被覆盖更新。
- **会话运行循环**：
  1. 生成 `sessionId`（`"tag-<uuid8>"`）。
  2. 按上述模式计算待扫 mediaId 集合。
  3. 批量 enqueue `FACE_DETECTION` PENDING 任务。
  4. 循环：`pollNextPendingBySession` → `markRunning` → 加载图片 → `Pass1Pipeline.process(image, mediaId:)` → 成功：`markCompleted` + 写 `media_assets` + 累计耗时；失败：`markFailed` + 指数退避（`scheduledAt = now + BASE * (attempt+1)`）。
  5. 任务间 `pollInterval ≈ 100ms`（对齐 Android），并在每轮顶部检查 pause/cancel 标志。
- **ETA**：Pass1 滑动窗口最近 20 次耗时的**中位数** × 剩余（pending+failed）任务数；过滤 >30min 异常值；冷启动默认 800ms/img。
- **pause/resume/cancel/retry**：协作式——置标志位，运行循环在**每张图之间**响应（单图 MNN 亚秒级，响应亚秒级）。cancel 立即置 `cancelled`，不等 JNI 返回（对齐 Android）。retryFailed：FAILED → 重置 PENDING 再 start。
- **中断恢复**：Orchestrator init 时 `resetRunningToPending()` + 检测未完成 session `maybeResumeOnStartup()`（对齐 Android）。注意：前台优先策略下，恢复仅在 App 重新前台 + 用户进入扫描页触发，不自动后台续跑。

## 7. 线程与后台策略（前台优先）

- **运行循环单一后台 `Task(priority: .utility)`**：串行调 `Pass1Pipeline`（MNN 单线程串行约束天然满足）。控制操作（pause/cancel/状态查询）走 `@MainActor`，通过标志位与运行循环通信，不被 JNI 阻塞。
- **前台优先**：扫描只在 App 前台跑。`scenePhase` → `.background` 时：协作式 pause（下个任务边界）+ `UIApplication.shared.beginBackgroundTask` 给一小段宽限期 flush 状态到 DB（对齐 Android onTimeout 的「持久化后停」语义）。回前台不自动续跑，由用户在扫描页点恢复。
- **`BGTaskScheduler`/`BGProcessingTask` 不进 SP-B**（需 `UIBackgroundModes` entitlement + 时机不可控）。
- **非主线程图片加载**：`ThumbnailLoader` 现为 `@MainActor`，后台扫描每张图 hop 主线程不可接受。新增 off-main 图片请求路径（`PHImageManager.requestImage`，`isNetworkAccessAllowed=false`，`deliveryMode=.opportunistic`），扫描循环走它；请求长边 ~1024（覆盖 Pass1 检测的 640 + 嵌入对齐裁剪 + MobileCLIP 编码所需），不必拉全分辨率。

## 8. UI 与接入

### 8.1 `TagScanViewModel: ObservableObject`（对齐 Android companion StateFlow）
`@Published`：`isScanning: Bool`、`sessionProgress: TagScanSessionProgress?`（state/currentPass/processed/total/pending/failed/estimatedRemainingMs/messages）、`dbStats: ScanDbStats`。方法：`start/pause/resume/cancel/retryFailed/refreshStats`。

### 8.2 `TagScanScreen`（SwiftUI，**完全复刻** `TagGenerationControlScreen` 全部结构）

逐 section 对齐 Android 控制页（顺序一致）：
1. **后台扫描守护横幅**（对应 `BackgroundScanGuardBanner`）：iOS 适配为电量/热态提示（`ProcessInfo.thermalState` 等）；SP-B 显示静态提示即可，不做白名单引导（iOS 无 HyperOS/MIUI 自启白名单概念）。
2. **活动任务进度卡**：状态文字、`ProgressView`（processed/total）、processed/total/pending/failed 计数、ETA（estimatedRemainingMs）、最近消息。
3. **会话控制条**：暂停·恢复（running/paused 状态相关显隐）/ 取消 / 重试失败（failed>0）。
4. **统计卡**（StatsCard）：totalMedia / withFace / withLabels / withSemantic / personCount / namedPersonCount / embeddingCount / remainingPass1 / remainingPass3——对齐 `TagScanDbStats` 全字段；SP-B 未实现的列（withLabels/personCount/remainingPass3 等）显示 0。
5. **管线概览**：3 步只读状态（人脸检测 / 聚类 / 内容打标）；Pass1 ✅ 可用，Pass2/Pass3 标「后续阶段」。
6. **快捷操作**（idle 态）：「全量扫描」+「增量扫描」两个按钮。
7. **逐 Pass 独立控制**（4 张 PassControlCard）：Pass1(人脸) / Pass2(聚类) / Pass3(内容) / 美学评分，每张 incremental + full 按钮。**SP-B 仅 Pass1 卡可点；Pass2/Pass3/美学卡渲染但置灰 +「后续阶段」徽标**（SP-C/SP-D 落地后激活）。
8. **精细控制**：分类 chips（FACE/SCENE/ACTIVITY/OBJECTS/TAGS/SUMMARY）、时间范围预设（ALL/7d/30d/90d）、fullRegenerate 开关、「按选择重新生成」按钮。**SP-B 仅渲染结构；FACE 类别对 Pass1 生效，其余类别及「按选择重生成」置灰 +「后续阶段」**（依赖 Pass3）。

**中断恢复提示**：进入扫描页时 ViewModel 检测是否存在未完成 session（有 PENDING/RUNNING/PAUSED 任务但 state≠completed/cancelled）；有则在顶部提示「上次扫描未完成」+「恢复」按钮（调 `resume`）。

**复刻原则**：视觉/结构 100% 对齐 Android；功能上 SP-B 只打通 Pass1 路径，依赖 Pass2/Pass3 的控件一律置灰并标注「后续阶段（SP-C/SP-D）」——不留 dead button，点击给 toast「该功能在后续版本」。

### 8.3 入口
- `MainTabView` 的 TAG tab（当前 `PlaceholderPage`）→ 渲染 `TagScanScreen`。
- `GalleryGridView` 扫描图标（`comingSoonFeature` 闭包，line 114）→ 改为 secondary 入口打开同一屏（`.fullScreenCover`，沿用现有模式）。

### 8.4 接线
- `AppContainer` 增加 `tagScanOrchestrator` / `tagScanViewModel`。
- `Pass1Pipeline.process` 写入目标改 `media_assets`（见 5.1）。

## 9. i18n

`TagScanScreen` 所有用户可见文案走 xcstrings 三语（en / zh-Hans / zh-Hant），键命名与 Android 对应字符串对齐；沿用 iOS 现有三语标准。因 UI 完全复刻 Android，需新增的键覆盖全部 8 个 section：扫描页标题、后台守护横幅文案、状态文案（扫描中/已暂停/已完成/已取消/重试中）、控制按钮（暂停/恢复/取消/重试失败）、ETA 与统计标签（全字段）、管线概览三步名称、快捷操作（全量/增量）、4 张 PassControlCard 标题与 incremental/full 按钮、分类 chips（6 类）、时间范围预设（4 档）、fullRegenerate 开关、「按选择重新生成」、以及「后续阶段」徽标与「该功能在后续版本」toast。

## 10. 风险与待解

| 风险 | 应对 |
|---|---|
| 全量 `media_assets` upsert 在超大相册（1万+）启动慢 | 实测；必要时改增量（change observer）提前 |
| MNN 单图耗时不明确（iOS 无 perf 日志） | SP-B 先加 per-image 耗时埋点，标定 ETA 冷启动值 |
| 前台优先→长相册需保持 App 前台 | 明确为 SP-B 取舍；后台续扫留后续 |
| `media_assets` 列数多（28），部分 SP-B 不写（labels/geo/ocr/aesthetic） | 列保留对齐，nullable，SP-B 不回填即可 |

## 11. 验收标准（SP-B）

1. 从 TAG tab 或相册扫描图标进入扫描页。
2. 点「开始」后，Pass1 跑遍相册所有图片（人脸检测 + 嵌入 + MobileCLIP），进度/ETA 实时更新。
3. 暂停 / 恢复 / 取消 / 重试失败 均生效（亚秒级响应）。
4. App 进后台自动 pause 并持久化；杀进程重启后能在扫描页恢复未完成 session。
5. 扫描产出落库：`media_assets.hasFace/faceRoiResult/faceFocusY/semanticEmbedding/lastTagScanPasses` + `face_embeddings`。
6. `media_assets` schema 与 Android `MediaEntity` 列对齐；`tag_scan_tasks` 与 Android `TagScanTaskEntity` 对齐。
7. 扫描页 **UI 结构完全复刻** `TagGenerationControlScreen`（8 个 section 齐全）；Pass2/Pass3/美学/非 FACE 类别等控件置灰并标「后续阶段」，点击给 toast；文案三语齐全，无硬编码字符串。

## 12. 后续子项目（不在本 spec）

- **SP-A**：Metal 精度复验 + GPU 守护（让 MNN 模型跑 Metal，CPU 黄金基线对比，带 fallback）。
- **SP-C**：Pass 2 DBSCAN 人物聚类（复用 `:shared` commonMain `StreamingClusterAccumulator`/`DbscanRefinementPolicy`，写 `persons`）。
- **SP-D**：Pass 3 内容打标（iOS 引入 Florence-2 ORT；复用 commonMain `Florence2Preprocess`/`Florence2ResultParser`；离线汉化；回填 `labelsEn/labelsZh`）。
