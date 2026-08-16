# PoLang iOS 实现参考·产品规格

> **文档性质**：iOS 端功能对齐的产品侧唯一参考。以 **Android `main` 分支实际代码为唯一事实来源**（非 `PRODUCT.md` / `FEATURES.md`），逐功能项核验落地状态与产品行为。
>
> **日期**：2026-08-09（2026-08-10 整合审计回写） · **基线**：app v1.0.34 · **iOS 对齐状态截至**：2026-08-10（Phase 6.x）
>
> **设计依据**：`docs/superpowers/specs/2026-08-09-ios-product-reference-design.md`

---

## §0 文档说明

### 0.1 目的

PoLang 正在做 KMP 跨端改造（Phase 5 iOS 骨架已落地，Phase 6 功能对齐进行中）。本文档为 iOS 实现者提供一份**从产品视角**、**以 Android 现有代码为准**、**结构化**的功能规格参考：知道「Android 上到底有哪些已上线功能、每个功能的产品行为是什么、iOS 侧当前状态与落点」。

### 0.2 使用方式

1. 按 §1.3 能力地图定位功能域与 iOS 状态；
2. 翻到 §2 对应域，按统一 8 子节模板（定位→入口→功能项清单→状态流程→UX 规则→数据模型→隐私降级→iOS 落点）获取产品行为契约；
3. 跨切面契约见 §3（路由表 / Capability 路由 / 数据持久化 / 隐私 / i18n / 设计系统 / 性能）；
4. iOS 落地优先级与缺口全览见 §4 矩阵；
5. 文档与代码不符处（漂移）见 §5，**以代码为准**。

### 0.3 状态图例

| 标记 | 含义 |
|------|------|
| ✅ | 已落地（main 代码可运行，行为完整） |
| 🔄 | 部分落地（核心路径在，但有缺口/退化） |
| 📋 | 规划中（文档/注释提及，代码未实现） |
| ❌ | 已移除（代码已删除，仅历史参考，如端侧文本 LLM） |

iOS 侧附加标记：`iOS 已有` / `iOS 待对齐` / `iOS 缺口` / `iOS 不对齐`（平台无等价能力）。

### 0.4 与既有文档关系

- `PRODUCT.md` / `FEATURES.md`：仅作交叉比对，**不作事实来源**。两者混杂已落地与规划项、引用大量 Android 专有实现、未按 iOS 视角组织。冲突时以代码为准，差异记入 §5。
- `docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md`：技术改造路线图，非产品功能规格。
- 技术细节 SSOT（TAG / 搜索 / 美颜引擎 / JS 沙盒）本文只给摘要 + 链接，不复制全文。

### 0.5 代码核验原则

- 功能项状态由 `androidApp/` / `shared/` / `engines/` / `iosApp/` 的 main 分支代码判定，每一项附 `文件路径:行号` 证据；
- Capability 注册以 `CapabilityRegistry` 实际 `registerCapability(...)` 调用为准；
- 持久化表以 `AppDatabase`（Room）实际 `@Entity` + 版本号为准；
- iOS 状态以 `iosApp/PoLang/Features/` 实际 Swift 文件为准；
- 📋 规划项必须能在代码注释/commit 找到出处，否则不收录。

---

## §1 产品全景

### 1.1 产品命题与形态

PoLang（破浪相册）是一个 **Agent 驱动的智能相册**实验场，核心命题是「当端侧/远程 AI Agent 成为应用中枢时，App 架构与交互如何演进」。不追求商业化，价值在技术探索与工程实践。

**实际产品形态（代码核验）**：

- **相册为默认首页**：`MainActivity` NavHost `startDestination = Screen.Main.route`，主页面 `HorizontalPager` 的 `initialPage = MAIN_PAGE_GALLERY`。应用启动直达相册。✅
- **AI 对话为核心助手能力**：聊天为主页面 Pager 第 3 页（`MAIN_PAGE_CHAT=2`），经远程 OpenAI 兼容 tool_calls 编排。✅
- **相机为辅助**：相机为 Pager 第 1 页（`MAIN_PAGE_CAMERA=0`），仅当前页激活时才绑定相机/加载语音与本地模型（`MainPagerHost` `isActivePage` 门控）。✅
- **底部悬浮 Tab 聚合**相机/聊天/打标/人物入口，设置位于相册顶部栏。✅
- **三大技术轴同库**：① On-device Agent Runtime + 本地/远程推理；② 智能相册与图片编辑；③ 自研 OpenGL ES + EGL 美颜/滤镜引擎 + 自建 Ktor 网关。
- **隐私优先**：用户图片/视频**文件**不上传远程推理服务器；人脸检测/OCR/分类/打标等媒体处理 100% 端侧；文本/元数据/相册摘要可走远程（chat 默认远程）。详见 §3.4。

> **iOS 落点**：`iosApp/PoLang/Features/Main/MainTabView.swift` 已设相册为初始页，命题一致；✅ **跟手横滑 + 4 页常驻已对齐**（`e8582301`，`TabView(.page)` 替换 ZStack 条件渲染）。`iOS 已有`（首页选择 + 跟手 Pager）。

### 1.2 应用骨架与导航拓扑

**主页面容器**：`MainPagerHost` 以 `HorizontalPager` 承载 4 页，**线性顺序，无循环回绕**：

| 页索引 | 常量 | 页面 | 屏幕组件 |
|---|---|---|---|
| 0 | `MAIN_PAGE_CAMERA` | 相机 | `CameraScreen` |
| 1 | `MAIN_PAGE_GALLERY` | 相册（默认首页） | `GalleryScreen` |
| 2 | `MAIN_PAGE_CHAT` | 聊天 | `ChatScreen` |
| 3 | `MAIN_PAGE_PEOPLE` | 人物 | `PersonScreen` |

证据：`features/main/MainPagerHost.kt:29-33`（常量）、`:91-156`（页面分发）。

**关键行为（代码核验）**：

- **全屏横滑跟手切换**：`HorizontalPager` + `userScrollEnabled`，4 页全常驻（`beyondViewportPageCount = MAIN_PAGE_COUNT - 1`），相册滚动/搜索状态滑走不丢。✅
- **底部 Tab 切页为瞬时跳转**（无横滑动画）：`switchMainPage` 用 `pagerState.scrollToPage(index)`（非 `animateScrollToPage`）。✅
- **返回键语义**：非相册页按返回回到相册页。✅
- **局部禁用外层滑动**：相册（详情/多选）与聊天（全屏预览）经 `onHorizontalSwipeEnabledChange` 上报禁用 Pager 滑动。✅
- **场景管理**：跟随 `pagerState.settledPage` 同步 `SceneManager`（CAMERA/GALLERY/CHAT），人物页沿用进入前场景。✅

**导航拓扑（ASCII）**：

```
                        NavHost (startDestination = "main")
                        │
            ┌───────────┴───────────────────────────────────┐
            ▼                                             ▼
   ┌─────────────────────┐                     二级页（push，slide+fade 400ms）
   │ Screen.Main         │              ┌──────────┬──────────┬──────────┬─────────┐
   │ HorizontalPager ×4  │              │ Settings │ModelCenter│PhotoEditor│ IDPhoto │
   │ (beyondViewport=3)  │              │ /{cat}   │ /{tag}   │ /{uri}   │ /{uri}  │
   │                     │              │          │          │?recipe   │         │
   │ [0]Camera [1]Gallery│              │ DataPriv │TagControl│ &autoOpt │ Debug   │
   │      ▲    ▲  [2]Chat│              │MemFacts  │TagViewer │          │ JsBridge*│
   │      │    │  [3]People│            │CommChan  │Duplicate │          │SearchTest*│
   │      │    │     │    │             │People(切页)│Manager │          │LlmLog   │
   │      │    └────initialPage=1(相册) │          │          │          │Sent.Piece*│
   │      └──────────┘                  └──────────┴──────────┴──────────┴─────────┘
   │   FloatingBottomTab(相册首页底部，多选时隐藏)
   │   [Camera] [Chat] [Tag] [People]   ← 4 项，无 Gallery（本身即首页）
   │
   └─ 顶部栏最右：Settings 入口
```

`*` 仅 DEBUG 构建。

> **iOS 落点**：`MainTabView.swift` + `FloatingBottomTab.swift` 已实现悬浮 Tab + 相册默认首页，4 Tab 顺序与图标语义一致（camera/chat/tag/person，Material Icons）。**iOS 主页面为 ZStack + 条件渲染，已有 swipe 切页手势**（`MainTabView.swift:78-88` `simultaneousGesture`，水平主导滑动切页），但**非真正跟手 drag-tracking Pager**——无页面常驻、无物理吸附。`iOS 待对齐`：跟手 drag-tracking + 4 页常驻。

### 1.3 功能能力地图

> 全量已落地功能汇总（✅/🔄）。📋 规划项仅在「功能项」列点出，不展开规格。iOS 状态对照 `iosApp/` 实际 Swift 代码。

| 功能域 | 功能项 | Android | iOS 状态 | shared 契约 | 详见 |
|---|---|---|---|---|---|
| **导航/骨架** | 相册默认首页 + 4 页 Pager（相机/相册/聊天/人物） | ✅ | iOS 已有（默认页）；待对齐（跟手 Pager） | — | §1.2 |
| 导航/骨架 | 悬浮底部 Tab（相机/聊天/打标/人物，纯图标无文字） | ✅ | iOS 已有 | — | §1.2 |
| 导航/骨架 | 设置入口（相册顶部栏最右） | ✅ | iOS 已有 | — | §1.2 / §2.9 |
| 导航/骨架 | 二级页导航（Settings/ModelCenter/PhotoEditor/IDPhoto/Debug…） | ✅ | iOS 部分（Settings/ModelCenter 已实现；PhotoEditor/IDPhoto/Tag 等缺口） | `NavigationCapability` | §3.1 |
| **相册/浏览** | 等比方块网格（Adaptive 110dp）+ 人脸感知对齐 | ✅ | iOS 已有（`GalleryGridView`） | `MediaAsset` | §2.1 |
| 相册/浏览 | 分组菜单下拉（日期/有脸/无脸/人物/风景/地点） | ✅ | iOS 待对齐 | — | §2.1 |
| 相册/浏览 | 媒体查看器（双指缩放 1-4x + 横滑翻页） | ✅ | iOS 已有（`MediaPagerView`） | — | §2.1 |
| 相册/浏览 | 媒体信息浮层（日期/位置/美学评分/人脸/AI标签/OCR） | ✅ | iOS 缺口 | — | §2.1 |
| 相册/浏览 | OCR 结果浮层 / 图像理解浮层 | ✅ | iOS 缺口 | — | §2.1 |
| 相册/浏览 | 多选拖拽 + 批量删除/分享/全选 | ✅ | iOS 待对齐（拖拽缺） | — | §2.1 |
| 相册/浏览 | 重复照片检测（端侧） | ✅ | iOS 缺口 | `DuplicateGroup` | §2.1 |
| 相册/浏览 | 备份与恢复（SAF JSON v5，18 段） | ✅ | iOS 缺口 | — | §2.1 / §2.9 |
| 相册/浏览 | 自定义相册 / 时间线视图 / 年视图 / 收藏 | ❌ 未实现 | — | — | §2.1（漂移） |
| **自然语言搜索** | 显式优先搜索管道（时间/地点显式交集短路） | ✅ | iOS 缺口（整链路在 androidApp） | `StructuredFilter`/`SearchIntent` | §2.2 |
| 自然语言搜索 | 时间/地点/内容(物体场景)/OCR/人物 多维 | ✅ | iOS 缺口 | — | §2.2 |
| 自然语言搜索 | 人物关系检索（KinshipLexicon→图谱→人脸簇） | ✅ | iOS 缺口 | — | §2.2 |
| 自然语言搜索 | 语义搜索（MobileCLIP Top-50）+ 融合排序 | ✅ | iOS 缺口 | — | §2.2 |
| 自然语言搜索 | 以图搜图 / 找相似（MobileCLIP embedding） | ✅ | iOS 缺口 | — | §2.2 |
| 自然语言搜索 | 搜索历史 / 视频·收藏筛选 | ❌ 未实现 | — | — | §2.2（漂移） |
| **图片编辑** | 静态美颜编辑（磨皮/美白/瘦脸/大眼/唇色/腮红，默认全 0） | ✅ | iOS 缺口（相机侧仅 MVP） | `BeautySettings` | §2.3 |
| 图片编辑 | 裁剪 / 调色（6 滑块） / 滤镜（色调9+风格5）/ 涂鸦标记 | ✅ | iOS 缺口 | `FilterType`/`StyleFilter` | §2.3 |
| 图片编辑 | 智能抠图（U2NETP/MODNET/FUSION/SELFIE_SEG） | ✅ | iOS 缺口 | — | §2.3 |
| 图片编辑 | 证件照（4 尺寸国标 + 3 色 + 智能构图 + 手动修复） | ✅ | iOS 缺口 | — | §2.3 |
| 图片编辑 | AI 一键优化（抽卡：4 候选 + NIMA 评分 + 技术护栏） | ✅ | iOS 缺口 | — | §2.3 |
| 图片编辑 | 对话式编辑（`edit_image` recipe 后台渲染回图） | ✅ | iOS 缺口 | `ImageEditCapability` | §2.3 |
| 图片编辑 | 局部美颜 / 智能消除（AI 填充） / 专业调色（曲线/HSL） | 📋 未实现 | — | — | §2.3 |
| **AI 对话** | 流式回复 + 节奏控制器（逐字吐 50ms） | ✅ | iOS 已有（`ChatAgentBridge`） | `ChatStreamEvent` | §2.4 |
| AI 对话 | 11 种消息类型（文本/图/媒体结果/图表/抽卡…） | ✅ | iOS 部分（仅 media_results） | `ChatUiActionDto` | §2.4 |
| AI 对话 | 语音输入（Sherpa-ONNX 端侧 ASR + Push-to-Talk） | ✅ | iOS 缺口 | — | §2.4 |
| AI 对话 | 多会话管理（侧边栏，新建/重命名/删除） | ✅ | iOS 部分（单会话 JSON） | — | §2.4 |
| AI 对话 | 端侧 JS 沙箱取数 + JS 画图（SVG） | ✅ | iOS 缺口 | — | §2.4 |
| AI 对话 | 媒体反馈（👍👎🔄 + 重排序） | ✅ | iOS 缺口 | `FeedbackAction` | §2.4 |
| AI 对话 | AI 工程师模式（claude-tunnel） | ✅ | iOS 缺口 | — | §2.4 |
| AI 对话 | 悬浮聊天气泡（系统悬浮窗） | ✅ | iOS 不对齐（平台无等价） | — | §2.4 |
| **Agent 编排** | AgentOrchestrator + CapabilityRegistry（commonMain） | ✅ | iOS 已有（shared 跨平台） | `:shared` 全 commonMain | §2.5 |
| Agent 编排 | 14 个已注册 Capability（11 app/chat + 2 activity + 1 page） | ✅ | iOS 部分（仅 1 个） | `Capability` | §2.5 |
| Agent 编排 | 远程 OpenAI tool_calls 编排（Koog） | ✅ | iOS 已有（chat 链路） | `KoogChatAgent`/`RemoteChatEngine` | §2.5 |
| Agent 编排 | 飞书/Telegram 远程控制（跨应用 a11y RPA） | ✅ | iOS 不对齐（平台无 a11y） | — | §2.5 |
| **人物/记忆** | 人物命名 / 全局唯一「我」标记 | ✅ | iOS 部分（UI 骨架有，后端未接） | `PersonRelationCapability` | §2.6 |
| 人物/记忆 | 人物关系图谱（23 谓词封闭枚举 + 幂等覆盖） | ✅ | iOS 缺口 | — | §2.6 |
| 人物/记忆 | 事实记忆（remember/forget/recall） | ✅ | iOS 缺口 | `MemoryCapability` | §2.6 |
| 人物/记忆 | 人物封面美学选择（NIMA + eDifFIQA 加权） | ✅ | iOS 缺口 | — | §2.6 |
| **自动标签** | TAG 3-Pass 流水线（检测+聚类+VLM 打标） | ✅ | iOS 部分（Pass1 已移植） | `ChatStartTagScanCapability` | §2.7 |
| 自动标签 | 人脸检测 RetinaFace + R100 embedding + MobileCLIP | ✅ | iOS 缺口 | — | §2.7 |
| 自动标签 | VLM 内容打标（Florence-2 默认 / Qwen3-VL 备选） | ✅ | iOS 缺口 | — | §2.7 |
| 自动标签 | 中英双字段 + MT 汉化（ControlledVocab + Opus-MT） | ✅ | iOS 缺口 | — | §2.7 |
| **相机** | 美颜系统（8 项默认 0 + 总开关联动三分支） | ✅ | iOS 已有（MVP 美颜） | `BeautySettings` | §2.8 |
| 相机 | 滤镜（色调 8 + 风格 5，**互斥**） | ✅ | iOS 部分（色调有） | `FilterType`/`StyleFilter` | §2.8 |
| 相机 | 拍照（GPU 离屏 + 80ms 黑场，无按钮缩放） | ✅ | iOS 已有（拍照离屏美颜） | — | §2.8 |
| 相机 | 录像（美颜录制 + 原生降级） | ✅ | iOS 缺口 | — | §2.8 |
| 相机 | 人脸十字星对焦（时序显隐） | ✅ | iOS 待对齐 | — | §2.8 |
| 相机 | 场景识别（NIGHT/MOON，**手动**非自动） | ✅ | iOS 待对齐 | — | §2.8 |
| 相机 | 语音入口（默认隐藏 + Push-to-Talk/WakeWord） | ✅ | iOS 缺口 | — | §2.8 |
| **设置/账号** | 设置主页（Hero 账号卡 + 主题/语言 + 10 网格 + 7 子页） | ✅ | iOS 已有（骨架） | — | §2.9 |
| 设置/账号 | 模型中心（Chip Pager + 下载/进度/删除 + 16 模型） | ✅ | iOS 已有（骨架） | `RemoteModelConfigs` | §2.9 |
| 设置/账号 | 远程推理额度展示（quota，默认 100） | ✅ | iOS 缺口 | — | §2.9 |
| 设置/账号 | 邮箱验证码登录 / Guest（deviceId） | ✅ | iOS 缺口（仅占位） | — | §2.9 |
| 设置/账号 | WiFi 静默预下载（推荐模型，默认开） | ✅ | iOS 缺口 | — | §2.9 |
| 设置/账号 | 通信通道（飞书 / Telegram 自配置） | ✅ | iOS 部分（Telegram 有） | `RemoteChannelType` | §2.9 |

---

## §2 功能域详解

> 每域统一 8 子节模板：① 功能定位（含 iOS 落点）② 入口与导航 ③ 功能项清单（逐项代码核验）④ 核心状态与流程 ⑤ 关键 UX 规则 ⑥ 数据模型概要 ⑦ 隐私/降级边界 ⑧ iOS 对齐要点。

---

## 2.1 相册与浏览

### 1. 功能定位

相册是核心产品场景与应用默认首页。用户在此浏览、搜索、管理照片，并经悬浮底部 Tab 进入相机/聊天/打标/人物。媒体源为 Android `MediaStore`（全量查询→Room 同步，仅插新不覆盖 TAG 字段）。

> **iOS 落点**：Phase 5 已建 `GalleryGridView` / `AlbumListView` / `MediaPagerView` / `GalleryPermissionStore`（PhotoKit，含 limited 四态）。媒体源为 `PHFetchResult`，无需手动 sync 本地 DB（即时查询），但需处理 limited 下增量重查。

### 2. 入口与导航

- **默认首页**：应用启动即进入相册。
- **主页面横滑**：相机/相册/聊天/人物 4 页由外层 `HorizontalPager` 承载，线性顺序无循环；照片详情/多选态禁用外层横滑（`GalleryScreen.kt:250-252`）。
- **悬浮底部 Tab**（仅网格态显示，详情打开时隐藏）：相机 / 聊天 / 打标控制 / 人物（`GalleryScreen.kt:842-870`）。
- **顶栏入口**（非选择态）：模型中心 / 开始·暂停扫描 / 搜索 / 分组菜单 / 设置（`GalleryTopBar.kt:96-114`）。

### 3. 功能项清单

| 功能项 | 状态 | 核验结论 |
|--------|------|----------|
| **网格视图** | ✅ | `LazyVerticalGrid` + `GridCells.Adaptive(110.dp)`，等比方块（`aspectRatio(1f)`），**非瀑布流、非固定 3 列**。间距 2dp，圆角 2dp。缩略图用人脸感知纵向对齐（`faceAwareVerticalAlignment`）。Coil `size(360)` + `crossfade(false)` 防 recycled bitmap 崩溃。预加载可视区 ±3 页。 |
| 时间线视图 / 年视图 | ❌ | 代码不存在多种视图模式；仅网格 + 分组头。 |
| **分组模式（下拉菜单）** | 🔄 | 无 FEATURES 所述横向 chips 栏（全部/人物/地点/事物/收藏/截图/视频）。实际为顶栏下拉 `GroupingMenu`：无分组 / 日期 / 有脸 / 无脸 / 人物 / 风景 / 地点（`GroupingMode` 8 值，SWIMWEAR/SEXY 被 filter 掉）。默认 DATE。 |
| **媒体查看器 — 缩放/翻页** | ✅ | `ZoomableImage` 双指缩放 1x~4x；缩放态禁翻页；单击切换工具栏显隐；长按进编辑器 + 触感反馈。 |
| 媒体查看器 — 左右切换 | ✅ | `HorizontalPager`，`pageSpacing = 16.dp`。 |
| **媒体查看器 — 工具栏** | 🔄 | 顶栏：返回+日期 / 信息 / 更多（图像理解 / OCR / 人脸关键点[仅 debug]）。底栏（仅照片）：发送(分享) / 编辑 / 证件照 / 删除。**无** AI 编辑按钮、无收藏按钮。 |
| 视频播放 | ✅ | ExoPlayer + PlayerView，`playWhenReady` 跟随 settledPage；底栏仅照片显示。 |
| **媒体信息浮层** | 🔄 | `PhotoInfoDialog` 显示：文件名 / 类型 / 拍摄日期 / 时长 / 来源 / 位置(地名+坐标可点跳地图) / 美学评分 / 人脸信息(人物分组/质量) / AI 标签(scene/activity/objects/tags) / OCR 文本。**无** 分辨率/文件大小/设备型号/EXIF 原始数据。支持「重新打标」。 |
| OCR 结果浮层 | ✅ | 独立 overlay，识别文字 + 字数 + 复制/分享。 |
| 图像理解浮层 | ✅ | `VisionResultOverlay`，Markdown 渲染 + 复制/分享。 |
| **多选模式** | 🔄 | 长按进入；支持**拖拽选择**（滑过批量勾选/取消）。批量操作仅：**全选/反选**、**删除**、**分享**。**无** 收藏、移动至相册、批量美颜。 |
| 自定义相册 | ❌ | 完全不存在（无相册表、无创建/命名/封面/增删/智能相册）。 |
| **相似/重复照片检测** | ✅ | `DuplicateManagerRoute` 独立页，入口在设置页「相册功能」卡片。端侧 `DuplicateImageDetector`；按组展示，保留首张删其余/批量删/预览确认。仅照片，不含视频。 |
| 存储管理（总数/空间/回收站/大文件） | ❌ | 除重复检测外均未实现；无 30 天恢复、无大文件扫描、无空间统计。 |
| **备份与恢复** | ✅ | `BackupRestoreActivity`，SAF JSON 导出/导入。格式 **v5**，18 个数据段。 |

### 4. 核心状态与流程

**权限态**（Android 二态：Denied / Full；无 Limited）。iOS 为 PhotoKit 四态（full/limited/addOnly/denied + notDetermined）。

**多选流程**：长按缩略图 → `isSelectionMode=true` → 单击 toggle / 拖拽 join-leave / 全选(仅搜索结果集) / 删除(`createDeleteRequest` API30+，需二次确认) / 分享(`ACTION_SEND_MULTIPLE`)。BackHandler 清空选中退出。

**备份恢复流程**：
```
导出:  Room 全量(18 表) → 流式 JSON → SAF output → 用户选定 URI
       (以 media uri 为跨安装稳定键; tag 以 name 重建; embedding Base64)
导入:  SAF input → 临时文件 → 流式 JSON → 建映射(uri→mediaId / oldPersonId→new / word→wordId)
       → 单事务批量写入(标签/关联/扫描任务[重置PENDING]/人物/关系/embedding/OCR/地理/反馈/聊天/记忆/配方)
       → DataStore 偏好(事务外恢复) → 汇总 RestoreResult
```

### 5. 关键 UX 规则

- 缩放态（scale > 1.02）禁翻页 + 隐藏顶/底栏。
- 照片详情/多选态禁用外层主页 Pager 横滑。
- 搜索输入防抖 300ms；媒体库变更后搜索结果防抖 300ms 自动刷新。
- 缩略图 `crossfade(false)` 防 recycled bitmap 崩溃。
- 冷启动占位用名人格言（非「未找到媒体」闪烁），`rememberSaveable` 跨 Activity 重建稳定。
- 删除走系统 `MediaStore.createDeleteRequest`（API30+）或 recoverable（API29），需用户二次确认。

### 6. 数据模型概要

- **媒体索引来源**：`MediaStore.Images/Video.Media`（非 Photo Picker）→ Room `media_assets`，仅插新不覆盖 TAG。
- **MediaAsset**（`shared/.../MediaAsset.kt`）：id / uri / type(PHOTO|VIDEO|DOCUMENT) / captureDate / fileName / duration / hasFace / faceId / source / labels / ocrText / lat / lon / locationName / city / indexedAt / faceFocusY / aestheticScore / faceQualityScore。**无** favorite/screenshot/分辨率/文件大小/设备型号 字段。
- **分组**：`GroupingMode`(8 值, UI 仅显 6) × `GroupTitleType`(10 值) → `GroupedMedia`。
- **自定义相册表**：不存在。
- **备份格式**：`TagDataBackup` version=5，18 段。

### 7. 隐私 / 降级边界

- 媒体文件不上云；重复检测/OCR 全部端侧（ML Kit OCR 经 `OcrProcessor`）。
- 无权限时显示 `GalleryPermissionMessage` + 授权按钮。
- 首次安装/夜间充电自动触发 TAG 扫描；模型未下载时跳过 + 蜂窝网络弹窗提醒。
- HyperOS 后台冻结检测：扫描前 `BackgroundScanGuard.diagnose()` 弹引导加白名单。
- 备份/恢复纯本地 SAF，不经网络。

### 8. iOS 对齐要点

- **权限范式差异**：Android 二态；iOS PhotoKit 四态（limited 一等公民，仅显已选 + 常驻「管理可访问照片」，`presentLimitedLibraryPicker` 已实现）。
- **媒体源**：iOS = PhotoKit `PHFetchResult`（`PhMediaBridge.swift`），无需手动 sync 本地 DB，但需处理 limited 增量重查。
- **Phase 5 已落地**：`GalleryGridView` / `AlbumListView` / `MediaPagerView` / `GalleryPermissionStore` 四态 / `ThumbnailView`+`ThumbnailLoader` / `GroupHeaderView` / `SplashPlaceholder` / `ShareSheet` / `GalleryFaceDebug` 人脸关键点调试 overlay（debug 门控，`MediaPagerView.swift:123-318`）。
- **缺口（待补）**：分组模式下拉菜单 / 拖拽多选 / 重复照片检测 / 备份恢复（v5 JSON → `UIDocumentPicker` + 同 schema）/ OCR 与图像理解浮层 / 自定义相册与存储管理（双端均缺）。

---

## 2.2 自然语言搜索

### 1. 功能定位

用自然语言搜索本地相册照片（时间/地点/内容/人物/OCR 文字），是智能相册核心交互能力。**媒体处理与检索 100% 端侧**（VLM 标签、MobileCLIP 语义、人脸聚类、OCR、SQL 全在本地），仅 Chat 场景的意图解析可走远程 LLM（仅传文本，不传图片）。

> **iOS 落点**：📋 **完全缺口**。整条搜索链路（`QueryParser` / `QuerySegmenter` / `SearchVocabulary` / `ExplicitFirstSearchPipeline` / `MediaSearchEngine` / `SemanticSearchEngine`）全部位于 `androidApp`，iOS 无实现。仅 `StructuredFilter` / `SearchIntent` / `TimeRange` 三个数据模型在 `shared/commonMain` 可共享。

### 2. 入口与导航

两个产品入口，共用同一个 `MediaSearchEngine`：

| 入口 | 触发 | 链路 | 状态 |
|------|------|------|------|
| **相册首页搜索框** | 顶部栏搜索图标 → `SearchField`（占位 `gallery_search_hint`） | 纯本地：`Segmenter → Parser → ExplicitFirstPipeline → SemanticEngine → 融合排序`，**不经 LLM** | ✅ |
| **Agent / Chat** | 远程 LLM 输出 `search_media` / `refine_media_search` tool_call | LLM 意图标准化 → `StructuredFilter` → `MediaSearchEngine.search(filter)` | ✅ |

### 3. 功能项清单

| 功能项 | 状态 | 关键行为 |
|--------|------|----------|
| **ExplicitFirstSearchPipeline「显式优先」** | ✅ | 若存在**收窄型显式约束**（时间/地点）则短路：显式约束取**交集** → 候选集内内容匹配 → 命中即返回。纯人物/概念查询不短路，回落 SQL+语义融合避免丢 MobileCLIP 召回。 |
| └ 时间维度 | ✅ | 相对年月（去年3月）/ 绝对年月（2024年3月）/ 独立中文月（五月）/ 季节（春天）/ 整年 / 上个月 / 本周/上周 / 昨天/今天 / 近 N 个月（近半年/近一年）。 |
| └ 地点维度 | ✅ | 内置城市/地标/室内外词表，匹配 `locationName` + GPS 反查。 |
| └ 内容维度 | ✅ | `SCENE/OBJECT/ACTIVITY` 词表 + 自由关键词，经 `TagTranslator` 跨语言扩展后命中 `labels`(VLM 中文 canonical) / `fileName`。 |
| └ OCR 文字维度 | ✅ | `OCR` 词表 + 自由词，命中 `ocrText` 与 `ocr_words` 倒排表。 |
| └ 人物维度（含共现） | ✅ | 通用触发词（人脸/自拍/合影）→ `hasFaces=true`；具体人物（宝宝）→ 人脸聚类；命中 ≥2 人物 → **同框共现查询**。 |
| └ **类型（视频）维度** | ❌ | 未实现。`StructuredFilter` 无 mediaType 字段；词表无「视频」词。 |
| └ **收藏维度** | ❌ | 未实现。无 favorite 字段/索引。 |
| **人物关系检索** | ✅ | `PersonQueryResolver` 接入：自定义称呼（二儿子）> 已命名人物 > 亲属称谓（`KinshipLexicon` 40+ 称谓）→ 人脸簇 ID。「我女儿的照片」/「我和妈妈的合照」可解析。 |
| 搜索结果展示 | ✅ | 复用 `MediaGrid`；长按进选择 → 批量分享/删除；全选仅限当前结果集；删除后自动刷新。 |
| **搜索历史** | ❌ | 未实现。Gallery 搜索框无历史；无 `SearchHistory` 表。 |
| 以图搜图 / 找相似 | ✅（Chat 内部） | `SemanticSearchEngine.searchByImage()` MobileCLIP Top-K，由 Chat「找相似」调用，非搜索框入口。 |
| 搜索诊断页（Debug） | ✅ | `SearchTestScreen` 展示各召回维度命中数/耗时/融合分。 |

**典型查询**：`去年3月` / `近半年小孩的照片` / `北京公园里的小孩` / `上周发票截图` / `海边日落`（语义） / `我女儿的照片`（人物关系）。

### 4. 核心状态与流程

**Gallery 搜索框路径**（纯本地）：
```
用户输入 → snapshotFlow{searchQuery}.debounce(300ms)
  → QuerySegmenter.segment()（TIME/LOCATION/PERSON/SCENE/OBJECT/ACTIVITY/OCR 分段）
  → hasNarrowingExplicit(时间/地点)?
      ├─[是]→ ExplicitFirstSearchPipeline（显式交集 → 候选集内内容匹配 → 命中返回）
      └─[否]→ QueryParser.parse() → StructuredFilter
                ├─→ executeFilter (SQL: 时间∩地点∩人脸∩标签/OCR/人物)
                └─→ SemanticSearchEngine.searchByText (MobileCLIP Top-50)
                → Layer3 mergeAndRank (SQL×0.25 + 语义×0.65 + 时间衰减×0.1)
                → MediaGrid（按融合分降序，长按选择→分享/删除）
```

Chat 路径绕过分词/规则：LLM `SearchIntent` → `StructuredFilter` → `search(filter, limitToIds)`（多轮细化用 `limitToIds` 在上轮结果集内 in-set 过滤）。

### 5. 关键 UX 规则

- 搜索防抖 300ms；语义召回含模型初始化可达秒级。
- 长按进选择 → 批量分享/删除；全选仅针对当前结果集；顶部栏显示命中张数。
- 媒体库变化（删除/授权）经 debounce(300) 自动重跑当前查询。

### 6. 数据模型概要

`StructuredFilter`（`shared/commonMain`）——搜索唯一结构化中间表示：`timeRange` / `keywords` / `ocrKeywords` / `locationKeywords` / `personName` / `hasFaces` / `needsLlm`。**无 video/favorite 字段**。语义索引 `semanticEmbedding`(MobileCLIP 512d)。

### 7. 隐私 / 降级边界

- Gallery 搜索框 **100% 本地**（无 LLM）；Chat 场景意图解析走远程 LLM（仅传文本，符合 ADR-008）。
- 降级链：显式约束短路 → 规则解析 →（Chat）LLM 意图标准化 → 全字段模糊兜底；语义召回 OOM/失败 → 退化为纯 SQL。
- 人物关系未就绪：`personQueryResolver==null` → 退化为 `personName` LIKE 匹配。

### 8. iOS 对齐要点

- **缺口**：整条搜索链路在 `androidApp`，iOS 无实现。
- **前置依赖**：iOS 必须先就位 ① VLM 标签（→ `labels`）② 人脸检测+聚类（→ `persons`）③ MobileCLIP 语义编码 ④ OCR ⑤ Room/SQLite 等价存储与索引表。无索引只能做时间/GPS 过滤。
- **shared 可共享**：`StructuredFilter` / `SearchIntent` / `TimeRange`（commonMain，KMP 直接复用）。
- **可迁移但当前未迁**：`QueryParser` / `QuerySegmenter` / `SearchVocabulary` / `KinshipLexicon` / `PersonQueryResolver` 均为**纯 Kotlin 无 Android 依赖**，可整体下沉 `shared/commonMain`。注意：`QueryParser` 用了 `java.util.Calendar`（需替换为 `kotlinx-datetime`）；DAO 需抽象为 `expect/actual`。
- **iOS 退化建议**：首版可只做「规则解析 + SQL 召回」，语义召回与人物关系作后续阶段。

---

## 2.3 图片编辑

> 两套编辑器并存——**全功能编辑器** `PhotoEditorScreen`（裁剪/调色/美颜/滤镜/标记 + 抠图 + AI 抽卡）与**轻量涂鸦编辑器** `ImageEditScreen`（仅涂鸦/马赛克，相册快捷入口）。下文「编辑器」默认指全功能 `PhotoEditorScreen`。

### 1. 功能定位

提供静态美颜精修、专业调色、滤镜/风格、智能抠图、证件照、AI 一键优化、对话式编辑等图片处理能力。**全链路 100% 端侧 GPU**（`[PRIVACY]` 红线），结果存为新文件不覆盖原图，Recipe 另存非破坏性。

> **iOS 落点**：iOS 缺口。Phase 5 骨架 + Phase 6 聊天/设置，编辑器整体（静态美颜/抠图/证件照/AI 优化）**全部缺口**，相机侧仅有 MVP 美颜预览。Phase 6+ 迁移。

### 2. 入口与导航

- **全功能编辑器**：相册详情底栏「编辑」/ Chat 对话式编辑（`edit_image` recipe）→ `photo_editor/{sourceUri}?recipeUri&autoOptimize`。
- **证件照**：相册详情底栏「证件照」/ Chat `id_photo` 命令 → `id_photo/{sourceUri}`。
- **轻量涂鸦**：相册快捷入口 → `ImageEditScreen`。

### 3. 功能项清单

#### 3.1 静态美颜编辑 ✅

全功能编辑器 BEAUTY tab 复用相机的 `BeautyPanel`（双 tab：面部精修 FACE / 妆容调整 MAKEUP）。

| 美颜项 | 字段 | 范围 | 默认 | 所在 tab |
|--------|------|------|------|----------|
| 磨皮 | `smoothing` | 0..100 | 0 | FACE |
| 美白 | `whitening` | 0..100 | 0 | FACE |
| 瘦脸 | `slimFace` | **-50..50** | 0 | FACE |
| 大眼 | `bigEyes` | 0..100 | 0 | FACE |
| 唇色强度 | `lipColor` | 0..100 | 0 | MAKEUP |
| 唇色色号 | `lipColorIndex` | 整数索引 | 0 | MAKEUP |
| 腮红颜色族 | `blushColorFamily` | 整数索引 | 0 | MAKEUP |
| 腮红强度 | `blush` | 0..100 | 0 | MAKEUP |

> **眉毛：未实现**（`BeautySettings` 无 eyebrow 字段，UI 无滑块）。`bodyEnhancement`/`legExtension` 字段存在但编辑器未暴露。

- 预览 200ms debounce 刷新；保存为新文件（`EDITED_<ts>`，透明→PNG、其余 JPEG 95）；Recipe 另存非破坏性。
- **对比模式**：编辑中**长按**预览显原图、松开回当前效果；透明抠图显棋盘格底。
- **撤销/重做**：`EditHistory`，**上限 30 步**（非 20），push 时截断 redo 分支。
- 缩放 1x–4x，双击复位。编辑器 5 底栏 tab：`CROP / ADJUST / BEAUTY / FILTER / MARKUP`。

#### 3.2 智能抠图 / 背景去除 ✅

四后端枚举 `MaskSource { U2NETP, MODNET, SELFIE_SEGMENTATION, FUSION }`，实际路由两层：

| 路径 | 后端选择 | 用途 |
|------|----------|------|
| 编辑器「去背景」 | `MattingRouter.choose(hasFace)`：有人脸→MODNET，无人脸→U2NETP | 通用抠图 |
| 证件照 | 固定 `FUSION`（MediaPipe SelfieSeg + MODNet 逐像素 max） | 人像精修 |

> **漂移**：`MattingRouter.choose()` **只路由 U2NETP/MODNET 两后端**；MediaPipe 走 FUSION 或直接 SELFIE_SEGMENTATION，不经 MattingRouter。

背景模式 `CutoutRecipe.BgMode { TRANSPARENT, COLOR, BLUR }`（编辑器默认 TRANSPARENT）。边缘后处理 `MaskPostProcessor`：二值化 / 上采样 / 羽化 / Alpha 锐化 / 腐蚀 / 扩张（纯函数可 JVM 单测）。模型按需下载。

#### 3.3 证件照 ✅

独立页面 `IDPhotoScreen` + `IDPhotoViewModel`，4 tab：`BG_COLOR / SIZE / EDGE / REPAIR`。

**尺寸预设**（@300dpi 国标，`IDPhotoSpecs.kt`）：1寸(295×413/25×35mm)、2寸(413×579/35×49mm)、小1寸(260×378)、小2寸(354×472)。**无签证尺寸**（漂移）。
**颜色预设**：标准蓝 `#438EDB`、标准红 `#D9001B`、白 `#FFFFFF`。

能力：进入即 FUSION 自动抠图换背景；**智能构图**（头顶留白 8% 防砍头）；合规裁剪预览（主体感知 cover-crop + 拖拽 + 缩放 1x–4x）；EDGE 边缘参数滑块；REPAIR 笔刷手动涂抹（RESTORE/ERASE + 撤销重做）；一键保存 `IDPHOTO_<ts>_<w>x<h>.jpg`。

#### 3.4 AI 一键优化 ✅

**抽卡闭环**（`PhotoEditorViewModel.aiOptimize` → `AiOptimizeUseCase.optimizeWithGacha` → `OptimizeGachaEngine`）：

| 环节 | 参数 |
|------|------|
| 候选采样 | 采样 **4 张**（锚点 + 方向池 ±30% 抖动） |
| 候选渲染 | 长边降至 **512px** 小图 |
| NIMA 评分 | MobileNet V1，分数 ∈[1,10] |
| 技术护栏 | 高光裁剪增量 >0.05 淘汰；亮度漂移 >15% 淘汰 |
| 退化守卫 | 有效卡 <2 → Unavailable；最优卡提升 ≤0.05 → KeepOriginal |
| 场景识别 | 端侧启发式 256px 像素统计，8 场景（SELFIE/PORTRAIT/GROUP/FOOD/LANDSCAPE/LOW_LIGHT/DOCUMENT/GENERAL） |

**交互闭环**：`Selected` → 推荐卡只大图预览（不入历史），候选条 4 卡横滑点选 → 点「应用」才入历史 + 落库；「换一组」去重重抽；「关闭」回退原图 + 落 dismiss 反馈。`KeepOriginal` → 预览原图。`Unavailable` → 退回固定预设直接应用。

**降级链**：`fastOptimize`（无网无授权）走 `optimize()` 固定预设路径，端侧场景识别→本地预设直应用，零网络。NIMA 模型未下载→抽卡整体跳过退回 fast。

> **`ai_optimize` 对话命令走 `optimize()` 固定预设，不走抽卡**。**smartOptimize（远程 VLM）📋 未实现**（仅 Phase 2 规划）。

#### 3.5 对话式编辑 ✅

`edit_image` 远程 tool_calls（`ImageEditCapability`，activeScene=CHAT）：

- LLM 返回结构化 `EditParams`（Absolute/Delta/Unchanged）→ `ChatEditRecipeBuilder` 映射为 `EditRecipe`。
- **Delta 步进限幅**（防模糊请求放大）：瘦脸 ±5、美颜类 ±10、亮度/曝光 ±15、对比度/饱和度 ±15、色温 ±500、色调 ±15。
- **多轮 delta**：按 `memorySessionId` 隔离的 `ChatEditStateHolder` 累加。
- 渲染由 `ChatEditProcessor` 执行：仅 crop + GPU 美颜/调色/滤镜（**不含抠图、不含标记**），结果写 `ChatImageStore` 私有缓存（**不进相册**），以 `AGENT_EDIT_RESULT` 消息类型回渲染。
- 不支持意图：LLM 标 `[unsupported:erase]`/`[unsupported:local_beauty]` → 友好文本回复。

#### 3.6 未实现项 📋

- **精准局部美颜**：无左眼/右眼独立字段；`[unsupported:local_beauty]` 返回友好文案。
- **智能消除（AI 填充）**：无圈选/涂抹 AI 填充；`[unsupported:erase]` 返回「in development」。现有「消除」仅为 MARKUP tab 马赛克涂抹（PIXEL/BLUR）。
- **专业调色**：`AdjustPanel` 仅 6 滑块（brightness/exposure/contrast/saturation/temperature/tint），无曲线/HSL/分区调色。`AdjustmentRecipe.vignette` 字段存在但 UI 未暴露。

### 4. 核心状态与流程

```
load(sourceUri, recipeUri?)
  → decodePreview (长边≤2047) → ensureFaceDetectionPipeline → detectFace → FaceData(106pt)
  → history.reset(loadedRecipe) → State.Ready → processPreview(recipe) 首帧

用户调滑块 → updateRecipe → history.push + _recipeChanges
  → debounce(200ms) → processPreview:
      applyCrop → applyGpuEffects(GPU, 黑屏→CPU 滤镜兜底) → applyCutout → applyMarkup → previewBitmap 更新

save → decodeFullBitmap(原图全尺寸) → 同 pipeline → MediaStore(新文件) + recipeRepository.save
```

**抽卡优化**：`optimizeWithGacha` → `gachaEngine==null`/NIMA 未就绪/有效卡<2 → `Unavailable` → fast 固定预设；否则采样 4 卡→护栏→打分→`Selected`(提升>0.05) / `KeepOriginal` → 对比模式（应用才入历史，换一组重抽，关闭回退）。

### 5. 关键 UX 规则

| 规则 | 值 |
|------|----|
| 预览 debounce | 200ms |
| 撤销/重做上限 | **30 步** |
| 对比模式（编辑中） | 长按预览显原图 |
| 先预览后应用（抽卡） | 推荐卡只预览不入历史，点「应用」才提交 |
| 缩放范围 | 1x–4x，双击复位 |
| 保存格式 | 透明→PNG(100)，其余→JPEG(95)；路径 `Pictures/PoLang`，`EDITED_<ts>` |

### 6. 数据模型概要

- **EditRecipe**（RECIPE_VERSION=2）：`sourceUri` + `crop: CropRecipe` + `adjustments: AdjustmentRecipe` + `beauty: BeautySettings` + `colorFilter: FilterType` / `styleFilter: StyleFilter` / `filterIntensity(默认1.0)` + `markup: List<MarkupAction>` + `cutout: CutoutRecipe?`。
- **FilterType**（9）：NONE/LEICA_CLASSIC/LEICA_VIBRANT/LEICA_BW/FILM_GOLD/FILM_FUJI/VINTAGE/COOL/WARM（ColorMatrix）。
- **StyleFilter**（6）：NONE/TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH（GPU Shader）。
- 抠图蒙版 `MattingResult(alpha: FloatArray, w, h)`，FUSION=selfie 与 modnet 逐像素 max。

### 7. 隐私 / 降级边界

- 编辑全链路 100% 端侧 GPU（`RecipeApplier.applyGpuEffects` EGL 单线程）。
- GPU 黑屏兜底：采样检测全黑→CPU ColorMatrix 滤镜（仅保滤镜，美颜/调色不可复刻）。
- NIMA/抠图/场景识别全端侧；fast 优化无网无授权可用；对话式编辑渲染结果写私有缓存不进相册。

### 8. iOS 对齐要点

- **编辑器整体缺口**：静态美颜编辑器、智能抠图、证件照、AI 一键优化全部缺口。
- **GPU 离屏 → Metal**：Android 编辑器离屏 FBO 渲染（`PhotoProcessorImpl`）需翻译为 Metal MSL；`BeautyParams`/`FilterType`/`StyleFilter` 在 `shared commonMain` 已跨平台可复用。
- **抠图后端 iOS 落地**：U2Netp/MODNet 走 ONNX Runtime（iOS 可用）；MediaPipe Selfie Segmentation 需 iOS framework；FUSION 融合（逐像素 max）纯数组运算可移植 `shared`。
- **证件照合成**：`IDPhotoComposer`/`MaskPostProcessor`/`BackgroundComposer`/`CutoutComposer` 均纯数组/Bitmap 逻辑，可 JVM 单测，移植成本低。
- **NIMA 评分**：ONNX 模型跨平台通用，iOS 用 onnxruntime-objc。
- 落地节奏：Phase 6+。

---

## 2.4 AI 对话

### 1. 功能定位

对话是 PoLang 智能相册的**核心助手能力**：以自然语言驱动相册搜索、对话式修图、AI 优化、相册盘点/统计画图、记忆管理与系统设置。对话页是主页 `HorizontalPager` 第 3 页（索引 2）。推理链路 **chat 默认且仅走远程**（端侧文本 LLM 已于 2026-08 移除）：`ChatViewModel.sendMessage` → `AgentOrchestrator.remoteChatEngine` → `RemoteChatEngine.streamChat` → `KoogChatAgent.runChat`（Koog AIAgent + ChatMemory，OpenAI 兼容 tool_calls）。

> **iOS 落点**：Phase 6.2 已打通基础链路——`shared/iosMain/.../remote/ChatAgentBridge.kt` 暴露 `sendMessage/watchUiActions/clearHistory/cancelCurrent`，经 `remoteChatEngine.streamChat` 复用与 Android 同源的 `ChatStreamEvent`。`ChatViewModel.swift`（148 行）+ `ChatView.swift`（517 行）实现文本气泡 + thinking 指示器 + 流式光标 + 媒体结果卡 + 空态示例词。**缺口**：语音/JS画图/会话侧边栏/图片附件/问题上报/反馈均为占位或缺失（详见 §8）。

### 2. 入口与导航

| 维度 | 实际代码 |
|------|----------|
| 页面位置 | `MAIN_PAGE_CHAT = 2`，主页 Pager 第 3 页，4 页全常驻 |
| 横滑门控 | `chatSwipeEnabled` 由 ChatScreen 上报（全屏预览时禁外层滑动） |
| 输入区 | `ChatInputArea`，白色 24dp 圆角阴影卡片；文字行 `BasicTextField`(maxLines=5) + 右侧发送键 |
| 语音入口 | 文字模式右侧 `KeyboardVoice` 图标切 VOICE 模式；VOICE 模式按住大麦克风 Push-to-Talk |
| 会话侧边栏 | `ChatThreadSidebar`——左侧 280dp 抽屉，含搜索框 + 会话列表 + 新建/重命名/删除 |
| 模型切换 | `ModelCapsuleButton` 下拉，**仅 `hasUserKey` 时显示**；未配 Key 只用默认远程 |
| 顶部栏 | 返回 + 菜单(侧边栏) + AI Engineer toggle + 上报问题 + 新建会话 + 清空 |

### 3. 功能项清单

**推理与流式**

| # | 状态 | 功能 | 行为 | 关键参数 |
|---|------|------|------|----------|
| 3.1 | ✅ | 流式回复 | `KoogChatAgent.runChat` 逐 token → `ChatStreamEvent.TextSnapshot`（**本轮累计全文快照，非 delta**），UI 整体替换气泡 | 超时 `120_000L` ms；流式瞬态走内存，**不落 Room** |
| 3.2 | ✅ | 节奏控制器 | `StreamingPacingController` 按字符延迟吐字 | `BASE_CHAR_MS=50`、`PUNCT_MS=100`、`LINE_MS=200`、`CHUNK_CJK=2`、`IDLE_POLL_MS=50` |
| 3.3 | ✅ | 思考态指示 | 首 token 前气泡显三点 typing indicator（`"正在思考..."`） | 首到 token `isThinking=false` |
| 3.4 | ✅ | 工具调用态 | `ChatStreamEvent.ToolCallStarted` → `pacingController.reset()` + 切文案 | — |
| 3.5 | ✅ | 打字光标 | `BlinkCursor`，吐字中显；`finish()` 追平全文后隐藏 | — |

**消息气泡类型**（`ChatMessageType` 枚举，共 **11 值**）：`user_text` / `agent_text` / `user_image` / `user_image_text` / `agent_image` / `agent_edit_result` / `command` / `plan_preview` / `media_results` / `chart` / `optimize_candidates`。

**输入与媒体**

| # | 状态 | 功能 | 行为 |
|---|------|------|------|
| 3.6 | ✅ | 语音输入（ASR） | `SherpaOnnxAsrEngine`（端侧 Sherpa-ONNX）；不可用回退系统 ASR；页内本地态，不持久化 |
| 3.7 | ✅ | Push-to-Talk | 按住录音、松开识别、手指移出取消丢弃；需 `RECORD_AUDIO` 权限 |
| 3.8 | ✅ | 媒体选择上下文 | `ChatPhotoPickerSheet` 单选相册图 → 暂存 → 选 `ImageIntent`（UNDERSTAND/FIND_SIMILAR/EDIT）→ 发送带图上下文；Claude 模式禁图选 |
| 3.9 | ✅ | 找相似 | `more_like_this` / `ImageIntent.FIND_SIMILAR` → MobileCLIP 语义召回 |
| 3.10 | ✅ | JS 沙箱 | `run_gallery_script` 端侧 QuickJS 取数（只读 handler）；写操作经 `WriteConfirmationController` 弹窗确认 |
| 3.11 | ✅ | JS 画图 | `draw_chart`（bar/line/pie）→ 端侧渲染 SVG → `CHART` 消息类型 + `ChartSvgImage` 内联渲染，支持全屏预览（1x~5x）；仅用户**明确要求**才调用 |

**记忆与会话**

| # | 状态 | 功能 | 行为 | 关键参数 |
|---|------|------|------|----------|
| 3.12 | ✅ | 多轮记忆 | Koog ChatMemory + `KoogMessageMemoryStore`（DataStore `chat_memory`，键 `koog_memory_$sessionId`）；三不变式 | `MAX_MESSAGES=10` |
| 3.13 | ✅ | 会话管理 | `chat_sessions`/`chat_messages` 双表；侧边栏列会话（按 `updatedAt` DESC） | 新建/切换/重命名/删除（级联清消息/缓存/记忆） |
| 3.14 | ✅ | 自动标题 | 首条消息后 `ChatTitleGenerator` 据内容生成 | `MAX_AUTO_TITLE_LENGTH=20` |
| 3.15 | ✅ | 持久化恢复 | 进程重建从 DataStore 读上次会话 | 上限 `MAX_MESSAGES=500` |

**交互与反馈**

| # | 状态 | 功能 | 行为 |
|---|------|------|------|
| 3.16 | ✅ | 长按消息 | **仅复制到剪贴板** + Toast；**无分享/删除/重生成菜单** |
| 3.17 | ✅ | 媒体反馈 | `MediaResultsCarousel` 每卡 👍LIKE / 👎DISLIKE / 🔄MORE_LIKE_THIS，落 `media_feedback` 表并重排序 |
| 3.18 | ✅ | 排除约束 | `exclude_constraint` 工具 → 后续搜索排除某类 |
| 3.19 | ✅ | 上报问题 | `ReportIssueDialog`（分类 + 标题 + 描述）→ `POST /v1/report-issue` |
| 3.20 | ✅ | 空态引导 | 中央 logo + 欢迎语 + 6 示例词芯片；Guest 额外显注册引导卡 |
| 3.21 | ✅ | AI 优化抽卡 | `ai_optimize` → 抽候选组 → 选/换一组/就用这张；内存态，重建后降级只读 |
| 3.22 | ✅ | AI 工程师模式 | toggle → `claudeChatClient.chat`（SSE）→ claude-tunnel → 云机 Claude Code |

**模型与额度**

| # | 状态 | 功能 | 行为 |
|---|------|------|------|
| 3.23 | ✅ | 模型选择 | `ChatModelOption` 仅 `Remote`（label `"远程"`，蓝点 `#2196F3`）；`hasUserKey` 时显切换胶囊 |
| 3.24 | ❌ | 额度展示 | chat 页**不展示额度**；Guest `quota_exceeded` 显注册引导 |
| 3.25 | ❌ | OFF 模式 | chat 无 OFF 模式，端侧文本 LLM 已移除 |
| 3.26 | ✅ | 悬浮聊天气泡 | `FloatingChatBubbleService`（前台 Service，`TYPE_APPLICATION_OVERLAY`），可拖拽 + 内嵌对话面板，复用同一 `ChatViewModel`（仅 Android） |

### 4. 核心状态与流程

```
用户输入（文字 / 选图+指令 / 语音识别结果）
  │
  ▼ ChatViewModel.sendMessage(text, imageUri?)
  ├─① 持久化用户消息（首条触发 ChatTitleGenerator 自动命名）
  ├─② 创建流式占位气泡（content="正在思考...", isThinking=true）+ pacingController.start()
  ├─③ 构建 AgentContext(scene=CHAT, memorySessionId, recentSearchResults, lastUserImageUri, gallerySummary, traceId)
  ├─④ orchestrator.remoteChatEngine.streamChat(input, ctx, onEvent)
  │     ├─ onEvent=TextSnapshot → pacingController.onTextSnapshot(全文) → 占位 isThinking=false
  │     ├─ onEvent=ToolCallStarted → pacingController.reset() → 占位切"正在调用工具"
  │     └─ KoogChatAgent.runChat（withTimeout 120s）
  │         └─ tool_call → ChatToolService.@Tool → dispatchCommand → CapabilityRegistry
  │             └─ uiActions flow → handleAgentAction 渲染卡片/回图
  ▼ streamChat 返回 Result
  ├─ onSuccess → pacingController.finish() → 拒绝类搜索？回 MediaResults 兜底
  │             → commands 非空？dispatch → handleAgentAction；纯文本→insertAgentMessage
  ├─ onFailure → quota_exceeded+Guest→注册引导；其他→错误气泡
  └─ finally: _isProcessing=false
```

### 5. 关键 UX 规则

| 规则 | 值 |
|------|----|
| 流式跟手性 | 节奏器逐字吐（50ms/字），标点 +100ms、换行 +200ms；非 delta 而是累计全文快照整体替换 |
| 推理超时 | 120s |
| 写操作确认（Tier A） | JS `capability.dispatch` 写操作经 `WriteConfirmationController` 弹预览确认；拒绝/超时 reject |
| 写操作确认（Tier B） | 顶层 `@Tool` 直调写操作（如 `delete_media`）不经应用内确认，由系统 MediaStore 授权框兜底 |
| 图表默认不画 | 统计类默认文字总结，仅用户明确要求才 `draw_chart` |
| 多轮窄化 | 上一轮结果上加条件必须 `refine_media_search`（保约束），换新主题才 `search_media` |
| 降级提示 | Guest 额度耗尽→注册引导；推理失败→错误气泡（modelUsed=`"error"`） |

### 6. 数据模型概要

- **`chat_sessions`**：`sessionId`(PK) / `title`(默认 `"New Chat"`) / `createdAt` / `updatedAt`。
- **`chat_messages`**：`id`(PK) / `sessionId` / `type`(11 值) / `content` / `timestamp` / `modelUsed`(`"remote_deepseek"`/`"error"`/`"gallery_search"`) / `metadata`(JSON 扩展)。
- **`ChatStreamEvent`**（commonMain sealed）：`TextSnapshot(text)`（累计全文）/ `ToolCallStarted`。
- **`media_feedback`**：`media_id` / `feedback_type` / `query_text` / `session_id`，复合索引 `(media_id, query_text, feedback_type)`。
- **`chat_image_cache`**：`filePath`(PK) / `sessionId` / `status`(ACTIVE/SAVED/EVICTED)，文件存 `filesDir/chat_edit_cache/`（Migration 14→15）。
- **Koog 记忆**：DataStore `chat_memory`，键 `koog_memory_$sessionId`，`MAX_MESSAGES=10`。

### 7. 隐私 / 降级边界

| 边界 | 约束 |
|------|------|
| 文本默认远程 | chat 文本推理 100% 远程（ADR-008 决策1）；端侧文本 LLM 已移除 |
| 图片不上传远程 | `ChatToolService` 运行在远程链路，但 `ai_optimize`/`edit_image`/打标/人脸均**端侧**完成；返回模型的仅纯文本 observation |
| 结果图私有缓存 | 对话编辑/优化结果写 `filesDir/chat_edit_cache/`，登记 `chat_image_cache`，**不进系统相册**；主动保存才入库 |
| claude 模式禁图选 | AI 工程师模式隐藏相册胶囊（媒体不上传远程） |
| ASR 端侧 | Sherpa-ONNX；不可用回退系统 ASR |
| 飞书例外 | `sessionId="feishu"` 经用户自配 IM 回传媒体给本人，非模型推理上传 |

### 8. iOS 对齐要点

**已有（Phase 6.2）**：shared 契约 `ChatAgentBridge`（`sendMessage`/`watchUiActions`/`clearHistory`/`cancelCurrent`）；流式文本（thinking→首token退出→流式光标→完成）；工具调用态文案；媒体结果卡（`ChatUiActionDto(kind="media_results")`，PHAsset 本地取图）；单会话历史（`ChatHistoryStore` JSON）；空态示例；清空/新建；`IosChatPrompt.build`（8 工具裁剪版）；隐私契约（DTO 不含文件路径/GPS/base64）。

**缺口 / 占位**：会话侧边栏、图片附件、语音输入、问题上报（均显示「敬请期待」）。

**完全缺失**：`text_reply`/`success`/`error` 三种 kind 被 `default:break` 静默丢弃；停止生成 UI（`cancelCurrent` 未被调用）；JS 画图（`CHART` 渲染）；反馈 UI（`media_feedback`）；多会话管理；`chat_image_cache`；AI 优化抽卡；Claude 工程师模式；语义/人脸搜索（prompt 声明不可用）。

**平台注意**：KMP shared 契约已就绪，iOS 侧主要是 **UI 消费完备性** + **新工具/消息类型** + **持久化升级到多会话** 的 gap。

---

## 2.5 Agent 编排与能力

### 1. 功能定位

Agent 编排层把用户自然语言映射为设备可执行能力（Capability），核心基础设施位于 `:shared` KMP 模块 `commonMain`（引擎无关层：`AgentOrchestrator` / `CapabilityRegistry` / `KoogChatAgent` / `KoogReActAgent` / `RemoteChatEngine` / `PrivacyGuard` / `ExecutionEngine`），Android 平台实现在 `androidMain`。包名 `com.mamba.picme.agent.core.*`。端侧文本 LLM 已移除，相机/chat 推理统一走远程 OpenAI 兼容 tool_calls（经 Koog 编排）；端侧 MNN-LLM 仅保留 VLM 打标。

> **iOS 落点**：`:shared` Agent 编排层 Phase 4 KMP 抽取后已跨平台就绪。iOS 组合根 `shared/iosMain/.../IosAgentComposition.kt` Phase 6.2 落地，镜像 Android 接线但用「手工清单」替代 JVM 反射（K/N 无 `asToolsByClass()`）。当前 iOS 仅注册 `IosChatGalleryCapability`。✅ shared 就绪 / 🔄 iOS chat 链路消费中。

### 2. 入口与导航

四个产品可见入口均汇聚到 `AgentOrchestrator`：

| 入口 | 方法 | 推理链路 | 场景 | 超时 |
|------|------|----------|------|------|
| 相册/Chat 文字输入 | `RemoteChatEngine.streamChat` → `KoogChatAgent.runChat` | 远程 ReAct（流式） | CHAT | 120s |
| 相册/Chat 语音输入 | ASR 端侧转写 → 同上 | 远程 ReAct | CHAT | 120s |
| 相机 AI 指令 | `processCameraInput` → `KoogReActAgent` | 远程少轮 tool_calls | CAMERA | 60s |
| IM 远程控制（飞书） | `processRemoteImInput` → `KoogReActAgent`(Feishu agent) | 远程 ReAct + 应用内 a11y | 跨场景 | 120s |

OFF 模式直接返回「AI Agent 已关闭」，不发远程调用。

### 3. 功能项清单（实际注册核验）

**判定规则**：在组合根找到 `registerCapability(...)` 调用的才算 ✅。

#### app/chat-scoped（11，`PoLangApplication.kt:716-753`，启动期注册、永不注销）

| Capability | 职责（命令） | 状态 |
|------------|------|------|
| `GalleryCapability` | 相册导航/视图/查看（`view_media`/`switch_view_mode`/`search_media`） | ✅ |
| `SettingsCapability` | 主题/语言/开关（`change_theme`/`change_language`/`toggle_setting`） | ✅（2026-07-29 补注册） |
| `ChatSearchCapability` | CHAT 搜索 + 多轮细化（`search_media`/`refine_media_search`/`more_like_this`/`exclude_constraint`） | ✅ |
| `ChatGallerySummaryCapability` | 相册聚合摘要（`get_gallery_summary`） | ✅ |
| `ChatRunScriptCapability` | 端侧 QuickJS + 画图（`run_gallery_script`/`draw_chart`） | ✅ |
| `ChatStartTagScanCapability` | 触发打标（`start_tag_scan`） | ✅ |
| `ChatMediaWriteCapability` | 媒体写汇聚（`delete_media`/`favorite_media`/`select_media`） | ✅ |
| `AiOptimizeCapability` | AI 一键优化（`ai_optimize`） | ✅ |
| `ImageEditCapability` | 对话式编辑（`edit_image`） | ✅ |
| `PersonRelationCapability` | 人物关系（`remember`/`forget`/`list_person_relations`） | ✅ |
| `MemoryCapability` | 事实记忆（`remember_fact`/`forget_fact`/`recall_memory`） | ✅ |

> 注：`share_media`/`record_feedback`/`download_model`/`switch_face_engine`/`adjust_image` 等 chat @Tool 为 inline handler（不经 CapabilityRegistry 分发，无独立 Capability）。

#### activity-scoped（2，`MainActivity.kt:189-190`，随 composition 生命周期）

| Capability | 职责 | 状态 |
|------------|------|------|
| `NavigationCapability` | 应用内页面导航（`navigate_to`/`go_back`） | ✅ |
| `SystemCapability` | 启动应用 / 系统设置（**仅 2 命令**：`launch_app`/`open_system_settings`） | ✅ |

#### page-scoped（1，`CameraScreen.kt:1046-1047`，随相机屏 register/unregister）

| Capability | 职责 | 状态 |
|------------|------|------|
| `CameraCapability` | 拍照/录像/翻转/美颜/滤镜/风格/场景/画幅/曝光/变焦（12 命令） | ✅ |

#### 存在但未注册（❌，永不进 main 注册表）

| Capability | 现状 |
|------------|------|
| `AutoTagCapability` | 未注册；实际打标走 CHAT 场景的 `ChatStartTagScanCapability` |
| `RemoteControlCapability` | 未注册；IM 远程控制走 `RemoteControlToolService` 多通道路径 |
| `BeautyCapability` | 未注册；测试/程序化 API |

#### 关联组件核验

- **PrivacyGuard 隐私分级**：`classify(input)` 分 PUBLIC / SENSITIVE / RESTRICTED 三级（坐标/`关键点`/`face_data`→RESTRICTED；`我的照片`/`人脸坐标`/`OCR结果`→SENSITIVE）。端侧文本 LLM 移除后，文本推理全远程，本类仅保留输入分级；`isRemoteAllowed()` 为遗留死代码（仅测试调用）。
- **MemoryManager**：多轮上下文已迁 Koog 记忆层（`KoogMessageMemoryStore` + 三不变式）；`MemoryManager` 仅剩 `clearHistory(sessionId)`。
- **KoogChatAgent（chat） vs KoogReActAgent（相机/飞书）**：chat 用 `KoogChatAgent`（纯 suspend `runChat`，流式逐 token 快照）；相机/飞书用 `KoogReActAgent`（Observe→Think→Act→Verify 回调循环）。

### 4. 核心状态与流程

**Chat tool_calls 链路**：
```
用户文字/语音输入
  → RemoteChatEngine.streamChat → KoogChatAgent.runChat（远程 tool_calls，流式）
       │  system prompt = chat 能力清单 + 画图/记忆/编辑行为规则 + 当前日期
       │  多轮记忆 = Koog ChatMemory (koog_memory_ 键空间)
       ▼
  远程模型返回 tool_call → ChatToolService.@Tool (dispatchCommand 薄封装)
  → CapabilityRegistry.dispatch(command, context, pageContext)
       │  按 currentScene + supportedCommands 匹配 Capability
       │  场景不匹配/delegate 未绑定 → 跨页命令队列排队 (CrossPageCommandQueue)
       ▼
  Capability.execute (端侧) → 结果回传模型做自然语言总结 → UI 气泡流式刷新
```

**跨页命令队列**：目标 Capability 场景不匹配或 delegate 未绑定时命令入队，目标页激活时执行，回复「正在为您切换到对应页面执行操作...」。

### 5. 关键 UX 规则（Agent 反馈规范）

- **确认**：写操作（删除/收藏）经确认弹窗才执行，拒绝/超时如实告知「操作已取消」。
- **澄清**：意图不明回复「没理解具体意图，请再描述一下」。
- **建议**：统计类默认不画图，末尾可顺带「想看分布或趋势的话，我可以画成图」。
- **错误**：工具返回 Error 如实告知，不重试同一失败操作；超时回「处理超时（X秒），请稍后重试」。
- **响应延迟**：相机指令每次请求工具调用 ≤3 次；相机 60s 超时、chat/飞书 120s。
- **多轮窄化**：已有搜索结果时加条件必须用 `refine_media_search`（保约束）。

### 6. 数据模型概要

- **Capability 接口**：`name`(唯一) / `description` / `supportedCommands()` / `activeScenes()` / `isAvailable()` / `execute(command, context, pageContext)`。`BaseCapability` 默认全场景。
- **ToolSpec schema**：Koog `@Tool(customName="snake_case")` + `@LLMDescription`；ChatToolService 暴露 36 @Tool，CameraToolService 13 @Tool。
- **AgentCommand** sealed：`TextReply`/`Unknown`/`Error`/`BatchExecute`(原子回滚)/`Delay`/`ExecutePlan` + 各业务命令。
- **日志**：`tool_call_log`（含未路由/入队黑洞场景）；`llm_call`（DEBUG 全文 / release 纯指标，双模式隐私）。

### 7. 隐私 / 降级边界

- 文本远程、媒体端侧（ADR-008）：chat/相机推理走远程，但图片/视频 100% 端侧，远程只收文本 observation。
- PrivacyGuard 三级分级仍保留（识别敏感内容）。
- 防回归守卫：`RemoteInferenceNoMediaUploadGuardTest` 静态扫描 `inference/remote/**`，断言无媒体上传符号。
- 网络不可用降级：远程调用失败/超时返回自然语言错误提示；端侧 VLM 打标不依赖网络。

### 8. iOS 对齐要点

- **shared 已就绪直接消费**：`AgentOrchestrator`/`CapabilityRegistry`/`KoogChatAgent`/`RemoteChatEngine` 全在 commonMain，iOS 经 `IosAgentComposition` 接线。
- **iOS 当前能力面**：仅注册 `IosChatGalleryCapability`（CHAT）；chat 工具用手工清单 `ChatToolManifest`（替代 JVM 反射）；相机 AI / 飞书 RPA / 端侧 VLM 均 stub 或空。
- **场景同步已接入**：`MainTabView.swift:60-66` 已在 onAppear + onChange(of: currentPage) 调 `IosAgentComposition.shared.onMainPageChanged(page:)` 同步 SceneManager（不再为 gap）。
- **不可移植项**：iOS 无 Android AccessibilityService，跨应用 a11y 自动操作（`RemoteControlToolService` 的 click/scroll/input/get_screen_info）不可移植 → 飞书 RPA 链路 iOS 不可用。`SystemCapability` 的 `launch_app`/`open_system_settings` 在 iOS 受沙盒限制，能力远弱于 Android。
- **语音**：ASR/VAD/唤醒词均为 Android 平台实现，iOS 需单独实现（Phase 6+）。

---

## 2.6 人物记忆与关系图谱

### 1. 功能定位

围绕「人脸聚类 → 命名 → 关系声明 → 记忆/检索」构建的人物层：对人脸簇命名、标记全局唯一「我」、声明「X 是我的 Y」关系（含亲属与非亲属）、并支撑「我女儿的照片」式自然语言人物检索。另含独立的事实记忆。整体 100% 端侧（人脸特征/embedding 不出端）。

**能力状态**：**已注册 main 并完整实现**（`PoLangApplication.kt:742/744` 注册 PersonRelationCapability / MemoryCapability）。

> **iOS 落点**：🔄 UI 骨架已建（`iosApp/PoLang/Features/Person/` 1311 行：`PersonView`/`PersonInfoView`/`PersonViewModel`/`PersonStore`，commit `02806687`）；但**后端未接**——关系图谱/封面美学/事实记忆均未消费 shared `PersonRelationCapability`/`MemoryCapability`（iOS 组合根 `IosAgentComposition` 仅注册 `IosChatGalleryCapability`）。命名/关系/「我」待接 shared，依赖人脸聚类（Phase 6.1 Pass2）先行。

### 2. 入口与导航

- **人物页**：主页 `HorizontalPager` 第 4 页（相机/相册/聊天/**人物**），横滑或点底部图标瞬时跳转。
- **事实记忆页**：设置页 `MemoryFactsScreen`（查看/删除/清空事实）。
- **聊天触发**：Chat 场景声明关系（「小宝是我女儿」→ `remember_person_relation`）或记忆事实（「帮我记住…」→ `remember_fact`）。

### 3. 功能项清单

| 功能 | 状态 | 说明 |
|------|------|------|
| 人物命名 | ✅ | 对人脸簇命名（`PersonRepository.rename`）。 |
| 全局唯一「我」标记 | ✅ | `setSelf`：先清所有 `is_self=0` 再置当前，保证全局唯一；关系图谱以「我」为默认 object 端。 |
| 人物页封面网格 | ✅ | `PersonScreen` 封面网格；封面 = `coverMediaId` 整图 + `faceFocusY` 人脸感知纵向对齐不砍头。 |
| 点封面改名/标关系/标「我」 | ✅ | `PersonInfoScreen` 重命名对话框内集成关系选择 +「这是我」开关。 |
| 人物封面美学选择 | ✅ | `CoverSelector`：NIMA 美学分 + eDifFIQA 人脸质量分加权（`W_FACE=0.6`/`W_AESTHETIC=0.4`），后台 `AestheticScoreWorker`（NNAPI，CPU 回退）。 |
| 人物关系图谱 | ✅ | 声明「subject 是我的 predicate」；`person_relations` 表，唯一索引幂等覆盖、FK CASCADE 级联删除。 |
| 亲属称谓词表 KinshipLexicon | ✅ | 中文称谓 ↔ 关系谓词；**查询侧与声明侧共用唯一词表**，查询按谓词族扩展。 |
| 自然语言人物检索 | ✅ | 「我女儿的照片」→ 称谓词表 → 关系图谱 → 人脸簇（`PersonQueryResolver`）。 |
| 事实记忆 | ✅ | 「帮我记住…」→ `memory_facts` 表；来源 `CHAT_TOOL` + `JS_DISPATCH`。 |
| 人物删除（UI） | ❌ | UI 无删除人物入口；仅可清空关系（「不设置」）。级联删除为 DB 层 FK 保证。 |

> **谓词实际范围**：`RelationPredicate` 共 **23 项**（远超文档所列 7 类），含非亲属：`PARTNER(恋人)`/`FRIEND(朋友)`/`CLASSMATE(同学)`/`COLLEAGUE(同事)`/`IDOL(偶像)`/`OTHER`，及具体化谓词（`SON/DAUGHTER/FATHER/MOTHER/ELDER_BROTHER/.../GRANDFATHER/GRANDMOTHER/GRANDCHILD`）。每谓词带 zh/en/ja 三语标签。

### 4. 核心状态与流程

**命名 + 标关系流程**：
```
人物页封面 → 点封面 → PersonInfoScreen → 重命名对话框
  ├─ 输入名字 → rename(personId, name)
  ├─ 关系选择 → declareRelation(subject, predicate, source=RENAME_DIALOG, customLabel?)
  │   ├─ 谓词归一（RelationPredicate.fromStored / KinshipLexicon.predicateFor；不匹配→OTHER+customLabel）
  │   ├─ 前置：「我」未标记 → SelfNotDeclared 引导先开「这是我」
  │   └─ 幂等覆盖（唯一索引 upsert）
  └─ 「这是我」开关 → setSelf（全局唯一）
```

**封面选择后台流程**：人脸聚类/TAG 完成 → `AestheticScoreWorker.runUntilDone()` → 逐候选 NIMA(1-10) + eDifFIQA(0-1) → `CoverSelector` 加权 → 写 `coverMediaId`（+ `media_assets.faceFocusY`）。

### 5. 关键 UX 规则

- **封面不砍头**：整图 + `faceFocusY` 人脸感知纵向对齐。
- **关系幂等**：重复声明同一三元组 = 纠错覆盖，不产生重复边。
- **关系归一兜底**：无法归一的称谓原话存 `customLabel`、谓词记 `OTHER`，不报错。
- **前置「我」**：声明关系前必须先标记某人物为「我」。
- **事实清空确认**：`MemoryFactsScreen` 一键清空带确认弹窗；单条删除无二次确认。

### 6. 数据模型概要

- **`persons` / `PersonEntity`**：`personId` / `name?` / `coverMediaId?` / `faceCount` / `is_self`(默认 0) / `createdAt/updatedAt`。
- **`person_relations` / `PersonRelationEntity`**：`relationId` / `subjectPersonId` / `objectPersonId` / `predicate`(枚举名) / `source` / `customLabel?` / `confidence`。FK CASCADE 双向；唯一索引 `(subjectPersonId, predicate, objectPersonId)`。`RelationSource` 仅 `RENAME_DIALOG`/`CHAT_DECLARATION`（**无 JS 源**）。
- **`memory_facts` / `MemoryFactEntity`**：`MemorySource`：`CHAT_TOOL`/`JS_DISPATCH`。
- **`face_embeddings` / `FaceEmbeddingEntity`**：`embedding`(R100 512d ByteArray) / `mediaId` / `personId`(FK `SET_NULL`)。
- AppDatabase 版本 = **20**。

### 7. 隐私 / 降级边界

- 人脸特征/embedding/检测 100% 端侧，不上传远程。
- 关系图谱与事实记忆为本地结构化数据；关系快照可备份导出/恢复（备份格式 v5，`RelationSnapshotRestorer` 按 `name+isSelf` 键化，跨设备重聚类后可重聚）。

### 8. iOS 对齐要点

- iOS 🔄 UI 骨架已有（`PersonView`/`PersonInfoView` 1311 行，commit `02806687`）；**后端缺口**：命名/关系/「我」、封面美学选择、KinshipLexicon、PersonQueryResolver、MemoryRepository 均未接 shared（iOS 组合根仅注册 1 Capability）。
- 依赖：人脸聚类（Phase 6.1 TAG Pass2 产物）+ 人物命名/关系为上层。
- Room → SQLDelight；NNAPI（NIMA/eDifFIQA）→ CoreML/Metal；`CoverSelector` 纯算法可共享。
- `KinshipLexicon` / `PersonQueryResolver` 纯 Kotlin，宜下沉 `shared/commonMain` 双端复用。
- 阶段：Phase 6+。

---

## 2.7 自动标签生成（TAG 3-Pass）

### 1. 功能定位

端侧多模型 3-Pass 自动打标流水线：为人脸（检测+embedding+聚类）、内容（场景/物体/活动 VLM 打标）、语义（MobileCLIP 向量）三类维度生成结构化标签，支撑自然语言搜索召回与人物分组。全程端侧。前台 Service 驱动，可对话/手动触发、可中断、进度可见。

> **iOS 落点**：✅ Pass1 基建于 main（`Pass1Pipeline.swift`/`FaceAlignment`/`MobileClipEncoder`/`TagDatabase`，`25414e12`）。✅ **Pass2 聚类 + Pass3 VLM + 控制页/编排 已合入 main**（`b78d7081`；Pass3 Florence-2 真机验证 5 图打标成功 `ab95c3b7`）。🔴 **聚类质量阻塞**：main embedder 走 MNN，MNN3.5 Apple bug 致 embedding 退化→聚类塌成 1 类；分支 `feat/ios-106-to-5-embedding` 用 ONNX+106→5 修复中。❌ MetalGuardian（新设计，推迟 SP-A）/ 后台扫描（iOS ~30s→增量/手动）。关键差异：MNN Metal 后端（precision 档位锁定坑）、ForegroundService→BGTaskScheduler、MetalGuardian 新设计。

### 2. 入口与导航

- **对话触发**：Chat 场景 `ChatStartTagScanCapability`（工具名 `start_tag_scan`）。注：LLM 直面工具当前**仅 query**（查状态），start/pause/resume/cancel 走命令规划路径。
- **TAG 控制页**：`TagGenerationControlScreen`（路由 `Screen.TagControl`），从设置页进入。
- **相册悬浮入口**：相册顶栏 播放/暂停 图标一键增量扫描。
- **模型中心**：相册顶栏云下载图标 → `ModelCenterScreen` 下载/管理打标模型。

### 3. 功能项清单

| 功能 | 状态 | 说明 |
|------|------|------|
| 3-Pass 链路 | ✅ | Pass1 人脸检测+embedding+语义 / Pass2 聚类 / Pass3 VLM 打标。 |
| 人脸检测 RetinaFace | ✅ | Det10G（MNN+OpenCL FORCE_GPU）+ 2D106 关键点→ArcFace5 对齐。 |
| 人脸 embedding R100 | ✅ | Glint360K R100 512 维（MNN），写 `face_embeddings`。 |
| MobileCLIP 语义编码 | ✅ | MobileCLIP-S2（ORT+NNAPI），512 维语义向量内联进 Pass1。 |
| VLM 内容打标 | ✅ | **Florence-2-base 默认**（ORT，`<OD>`+`<MORE_DETAILED_CAPTION>`）/ Qwen3-VL-2B 备选（MNN），由用户偏好+模型可用性决定。 |
| MobileCLIP 分类器 | 🔄(旁路) | `MobileClipTagClassifier` 存在但 Pass3 实际**已旁路**（质量问题），仅贡献 Pass1 语义向量。 |
| 中英双字段 + MT 汉化 | ✅ | 英文原标 → 中文（ControlledVocab 平行词表 + BilingualVocab + Opus-MT 兜底）。三列：`labelsEn`/`labelsZh`/`labels`(=zh 别名)。 |
| TAG 控制页 | ✅ | 状态：空闲/扫描中/暂停中/已暂停/取消中/已取消/完成；失败为 per-task 计数 + 条件「重试」。 |
| 前台服务 | ✅ | `TagGenerationService`，`dataSync` 类型，常驻通知（silent/ongoing）。 |
| 可中断 | ✅ | 暂停/恢复/取消/重试失败（Intent 驱动 companion builder）。 |
| 对话触发 | ✅(部分) | Capability 支持 start/pause/resume/cancel/query；LLM 工具仅 query。 |
| JS 触发 | ❌ | JS 仅 `tag.scan_status` 只读；**JS 不能触发扫描**。 |
| 标签查看页 | ✅ | `TagViewerTestScreen`，按 scene/objects/tags 聚合、关键词过滤、不喜欢反馈。 |

> **VLM 选型漂移**：CLAUDE.md 称「Qwen3-VL-2B 端侧 VLM 打标」—— 实际 Florence-2 为默认、Qwen3-VL-2B 为备选。

### 4. 核心状态与流程

**3-Pass 状态机**（`TagGenerationPipeline` + `TagScanOrchestrator`）：
```
会话调度（两阶段）: Phase1[FACE_DETECTION, DBSCAN] | Phase2[IMAGE_TAGGING]
 ▼ Pass1 FACE_DETECTION（逐图，mediaId）
 │  ├─ RetinaFace Det10G ROI 检测（MNN+OpenCL FORCE_GPU）
 │  ├─ 2D106 关键点 → ArcFace5 对齐
 │  ├─ Glint360K R100 512 维 embedding（MNN）
 │  ├─ MobileCLIP-S2 语义向量（ORT+NNAPI，内联）
 │  ├─ 写 face_embeddings / media_assets(faceRoiResult, semanticEmbedding, lastTagScanPasses[1])
 │  └─ 流式聚类：每 20 张人脸照触发 runStreamingClusterBatch
 ▼ Pass2 自适应聚类（全局，mediaId=-1）
 │  ├─ 默认自适应 k-NN 连通分量（Plan B，非纯 DBSCAN）
 │  ├─ preserveNamedPersons（cos≥0.65 保留已命名簇）
 │  └─ 写 persons / media_assets.faceId
 ▼ Pass3 IMAGE_TAGGING（逐图）
    ├─ VLM：Florence-2（默认）或 Qwen3-VL-2B（备选）
    ├─ TagNormalizer + ControlledVocab 规范化
    ├─ LabelSinicizer 汉化（词表→MT 兜底）
    └─ 写 media_assets(labelsEn, labelsZh, labels)

状态：IDLE→RUNNING→(PAUSING→PAUSED→RUNNING)→(CANCELLING→CANCELLED)/COMPLETED
失败：单任务指数退避 5min·(attempt+1)，max 3 重试；decode 失败写哨兵值；
     OpenCL 超时→CPU 重试；guard ABORT(电≤5%/热SEVERE)、PAUSE(电≤15%/热MODERATE)
完成：session COMPLETED → 去重后 fire-and-forget aestheticScoreWorker.runUntilDone()
```

### 5. 关键 UX 规则

- **进度可见**：会话卡含 `CircularProgressIndicator` + `LinearProgressIndicator`（已处理/总数 + 当前 Pass）+ ETA（滚动中位数估）+ 最近消息。
- **可中断**：扫描中可暂停/恢复/取消；失败计数 >0 时出现「重试」。
- **后台扫描通知**：前台 Service 常驻通知（silent/ongoing/百分进度）；Android 14+ `onTimeout` 处理 dataSync 时限。
- **后台保活引导**：HyperOS/MIUI 冻结保护引导对话框 + Banner。
- **精细重标**：按类别 chip（人脸/场景/活动/物体/标签/摘要）+ 时间范围预设 + 全量重标/仅补缺失。
- **模型未下载不自动跳转**：任务 FAILED→控制页失败计数 + 重试；需自行点顶栏云下载去模型中心。

### 6. 数据模型概要

- **`media_assets` / `MediaEntity`**：标签三列 `labels`/`labelsEn`/`labelsZh`（JSON）；人脸 `hasFace`/`faceId`/`faceRoiResult`/`faceFocusY`；质量 `aestheticScore`(NIMA 1-10)/`faceQualityScore`(eDifFIQA)；语义 `semanticEmbedding`(MobileCLIP 512d Base64)；扫描簿记 `lastTagScanPasses`(JSON `{"1":ts,"2":ts,"3":ts}`)。
- **`face_embeddings`**：`embedding`(R100 512d) / `mediaId` / `personId`(FK `SET_NULL`)。
- **`tag_scan_tasks`**（活跃队列）：`sessionId`/`mediaId`/`pass`/`status`/`priority`/`attemptCount`/`scheduledAt`(退避)。`TagScanPass`：FACE_DETECTION/DBSCAN/IMAGE_TAGGING/MOBILE_CLIP_ENCODING(遗留)。
- **遗留表**：`tags`/`media_tag_cross_ref`（旧 ML Kit 归一化表），现主存储为 `MediaEntity` 统一 JSON。
- **模型来源**：全部运行时下载至 `files/llm_models/`（不内置，仅 `controlled_vocab.json` 在 assets）。模型 ID：`mobileclip-onnx`/`florence2_base`/`qwen3_vl_2b`/`opus-mt-en-zh`。

### 7. 隐私 / 降级边界

- 100% 端侧：VLM/检测/分类/聚类/embedding 均不上传远程。
- **OpenClGuardian**：Pass3 前 warmup（20s 超时）+ 单次推理 30s 超时；连续 3 次失败→降级 CPU；24h 冷却 + 设备黑名单持久化。
- NNAPI（MobileCLIP）+ CPU 回退；Florence-2/Opus-MT CPU-only；R100/检测 MNN+OpenCL（受 Guardian 守护）。
- 模型未下载：任务 FAILED + 控制页失败计数引导（无自动跳转）。

### 8. iOS 对齐要点

- iOS 🔄 Pass1 已移植（`25414e12`），Pass2/Pass3/MetalGuardian/控制页未组装。MNN 推理接入（Phase 2.1：arm64 + Metal 后端 XCFramework）。
- **MetalGuardian 新设计**（替代 OpenClGuardian，iOS 无 OpenCL）：Metal kernel warmup 超时检测、Metal→CPU 降级（含模型卸载重载）、MTLDevice 丢失处理、黑名单持久化。
- **precision 档位锁定坑**（Phase 2.1 已知）：默认 `Precision_Normal`(fp16) 数值全错（cos≈-0.5），须显式锁定 `Precision_High` 或 `Precision_Low`。
- **Qwen3-VL-2B 真机未验证**：内存峰值/Metal 算子/首 token 风险仍在案；失败则 iOS TAG VLM 需重开选型（MLX 不支持 iOS、CoreML LLM 支持有限）。
- **ForegroundService → BGTaskScheduler**：iOS 限 ~30s，无法等价替代 —— 全量扫描需改「充电+锁屏增量扫」或「手动触发」，**属双端功能差异**。
- Room 表 → SQLDelight 或 Room KMP。4 模型顺序加载内存峰值须 Instruments 验证。

---

## 2.8 相机（辅助入口）

### 1. 功能定位

相机为**辅助入口/内容采集**，非默认首页。拍得成片经 GPU 离屏美颜渲染后写入 MediaStore 并刷新左下角缩略图——**实际不自动跳转编辑页**。相机页同时是 Agent 自然语言控制与语音交互的承载页。

> **iOS 落点**：iOS 已有 MVP（Phase 5.4），Phase 6 增量补全。

### 2. 入口与导航

- 相机是主页 4 页之一（`MAIN_PAGE_CAMERA=0`），由 `HorizontalPager` 承载，可全屏横滑跟手切换；底部悬浮 Tab 点击瞬时跳转。
- 相机门控：仅当前页为相机页时才绑定相机/加载语音与本地模型（`isActivePage`）。

### 3. 功能项清单

#### 3.1 美颜系统 ✅（参数默认值与 FEATURES 不符）

面板分两 Tab：**面部精修**（FACE）+ **美妆**（MAKEUP），半屏底部滑出。

| 功能 | 范围 | 代码实际默认 | 状态 |
|------|------|------|------|
| 磨皮 smoothing | 0–100 | **0** | ✅ |
| 美白 whitening | 0–100 | **0** | ✅ |
| 瘦脸 slimFace | -50–+50 | **0** | ✅ |
| 大眼 bigEyes | 0–100 | **0** | ✅ |
| 唇色 lipColor | 0–100（+色板 lipColorIndex） | **0** | ✅ |
| 腮红 blush | 0–100（+色族 blushColorFamily） | **0** | ✅ |
| 眉毛 | — | **不存在** | ❌ |

> **所有美颜参数真实默认值均为 0**，FEATURES 表中的 35/25/20/40/20 在代码中不存在（漂移）。「眉毛」在整个美颜数据模型/UI 中**完全不存在**。

#### 3.2 美颜总开关 ✅

`resolveNextBeautySettings` 三分支语义：① 仅开关本身翻转→维持用户值；② 调参后 `hasAnyEffect()` 为真→强制 `enabled=true`；③ 参数全归零→强制 `enabled=false`。默认关闭→进相机**不跑人脸检测**（`shouldEnableFaceDetection = beautySettings.enabled`，关闭时 `imageProxy.close()` 跳过整帧分析）。

#### 3.3 滤镜 ✅（互斥，非叠加）

统一选择器 5 列网格：**色调（ColorMatrix）8 项 + NONE** + **风格特效（GPU Shader）5 项 + NONE**。**互斥**：选色调清空风格，选风格清空调色。选中项有 1.08× 缩放动画。

#### 3.4 拍摄 / 快门 🔄

三位一体反馈：触感（`LONG_PRESS`，系统决定时长）+ 音效（`CLICK`）+ 黑场闪烁（`shutterFlashAlpha` `snapTo(0.6f)`→`animateTo(0f, tween(**80ms**))`）。**实际 80ms，非 FEATURES 所称 50ms**。**按钮缩放：未实现**（`ShutterButton` 静态 76dp 圆环，无 press scale；仅 500ms 防抖）。拍照走 GPU 离屏 FBO 渲染（预览/出片一致）。

#### 3.5 人脸十字星 ✅

`FaceFocusCrosshair`：四角 L 型标记 + 中心十字（16dp）+ 中心点，外框 100dp，主色 `#00E5FF`，弹簧动画。显隐时序：未锁定→220ms 淡出；锁定+运动→160ms 淡入全显；锁定+稳定→延迟 320ms 后 420ms 淡出。

#### 3.6 录像 ✅

`handleCaptureClick` 含完整录像路径（非冻结），模式选择器有 `VIDEO` 档：美颜录制 `BeautyVideoRecorder` + GL Provider（短边对齐 1080p），输出 `Movies/PoLang`；降级 CameraX 原生 `Recorder`（无美颜）。录制中快门变红 + 中心方形停止图标。

#### 3.7 语音入口 ✅

仅相机页、**默认隐藏**（`voiceEntryEnabled` DataStore 默认 `false`）；由设置「沙盒与权限 → 设备访问 → 语音控制入口」开关控制（2026-08-16 自「相机与美颜」迁入）。FAB 用 `RecordVoiceOver`（区别于 Chat 的 `KeyboardVoice`）。模式 `VoiceCommandMode` 默认 `DISABLED`，三档：DISABLED / PUSH_TO_TALK / WAKE_WORD。**Push-to-Talk 无模型基线**（系统 ASR）；**WakeWord(KWS) 需下载模型，不默认下载**。

#### 3.8 场景识别 🔄（仅 Agent 链路，无手动入口）

`ScenePreset = { NONE, NIGHT, MOON }`。**无自动光照检测**：`CameraFrameAnalyzer` 不含亮度/lux/夜景判定逻辑。手动场景选择器 UI 已于 2026-08-15 相机页改版时下线；NIGHT/MOON 仅由 Agent/语音命令（`switch_scene`）设置。

#### 3.9 对焦 / 变焦 / 翻转 ✅

点击对焦（AF+AE，2s 自动取消）；变焦预设 0.6x/1x/2x/3.2x（设备支持时）；前后切 `nextLensFacing`。

### 4. 核心状态与流程

```
相册首页(默认) → 底部 Tab/横滑 → 相机页 (isActivePage 绑定)
  │  beautySettings = BeautySettings(enabled=false) ← 全 0、人脸检测关闭、SKIPPED
  ├─ 调任一美颜参数 → resolveNextBeautySettings → enabled=true (hasAnyEffect) → 人脸检测启动/实时预览 <100ms
  ├─ 选色调/风格 → 互斥清空对方
  ├─ 按快门 → 触感+音效+黑场(80ms) → GPU 离屏渲染 → MediaStore + insertMedia → 状态机 Capturing→Previewing → 缩略图刷新(❌ 不自动跳编辑)
```

### 5. 关键 UX 规则

- 实时预览参数生效延迟 < 100ms（`[PERF]` 红线）。
- 快门捕获延迟 < 50ms（`[PERF]` 红线）。
- 面板结构（2026-08-15 改版）：美颜为底部抽屉（最大高度约 **0.40** 屏高，覆盖预览、不顶起其他 UI）；比例/辅助线/滤镜/专业面板从顶部工具栏下方**内联滑出**（`InlineControlPanel`，非半屏 Sheet）。主面板互斥（开一个关其他）。

### 6. 数据模型概要

类型已迁入 `:shared` commonMain（`beauty/api/`）：`BeautySettings`（`enabled/smoothing/whitening/slimFace/bigEyes/lipColor(+lipColorIndex)/blush(+blushColorFamily)` + 调色组 + `colorFilter:FilterType` + `styleFilter:StyleFilter`；`hasAnyEffect()` 判总开关联动）；`FilterType`（9 值）；`StyleFilter`（6 值）。渲染实现细节见 `BEAUTY_ENGINE_TECH_SPEC.md`。

### 7. 隐私 / 降级边界

- 预览零拷贝 GPU 管线（CameraX → SurfaceTexture → OpenGL ES → SurfaceView），预览路径禁 `glReadPixels`。
- 美颜/人脸检测/形变 100% 端侧（MediaPipe/MNN），不上传任何图片帧。
- 美颜关→跳过 `ImageAnalysis` 整帧人脸检测省性能。
- GL Provider 未就绪→PreviewView 降级；录像美颜不可用→CameraX 原生 Recorder 降级。

### 8. iOS 对齐要点

Phase 5.4 已落地：AVFoundation + Metal 4-pass（yuv→smoothing→lut→beauty）+ MediaPipe 468→106 warp 形变（+ MNN 2d106 双引擎运行时切换，`FaceEngineRouter`/`MnnFaceLandmarkService`，`9cb910e1`）+ 美颜 MVP（磨皮/美白/瘦脸/大眼）+ 9 ColorMatrix LUT + 5 风格占位 + 对焦/变焦/曝光 + 拍照离屏美颜存 PHPhotoLibrary。

**Phase 6 增量补全**：美颜默认值统一为 **0**（勿照搬历史版本的 35/25/20/40/20）+ 唇色/腮红色板；总开关三分支语义复刻；滤镜色调/风格**互斥**；快门反馈触感+音效+黑场（**80ms**，按钮缩放可补齐）；十字星时序；场景模式仅 **Agent/语音指令**（手动入口已下线）；录像线；语音入口默认隐藏。

---

## 2.9 设置与账号

### 1. 功能定位

设置页是全局二级页，入口为主页右上角设置图标，路由到 `SettingsScreen`（`SettingsCategory` 枚举驱动九子页切换：MAIN/ACCOUNT/GALLERY/CAMERA/SYSTEM/REMOTE_MODEL/LOCAL_MODEL/SANDBOX/DEVELOPER；2026-08-16 `CAMERA_BEAUTY` 更名 `CAMERA`，承载相机状态记忆与重置）。设置主页 = 账号置顶 Hero 卡 + 主题/语言快选卡 + 2 列功能网格（10 项 + 解锁后的开发者选项）。模型中心、数据与隐私、通信通道、AI 记忆均为独立子页/独立 Activity。

> **iOS 落点**：🔄 `SettingsScreen.swift` + `SettingsSubPages.swift` + `ModelCenterView.swift` + `ModelDownloadCenterView.swift` + `ModelConfigStore.swift` 骨架已有（Phase 5/6.3）；账号邮箱验证登录、server quota 接入尚未落地。

### 2. 入口与导航

设置主页网格（2 列 chunked，baseItems 10 项）：① AI 记忆 → ② 人物 → ③ 通信通道 → ④ 相册功能 → ⑤ 远程模型 → ⑥ 本地模型 → ⑦ 模型中心 → ⑧ 沙盒与权限 → ⑨ 数据与隐私 → ⑩ 相机；开发者选项为解锁后附加项（`developerOptionsUnlocked`）。顶部账号 Hero 卡 + 主题/语言快选卡。

> **漂移**：**模型中心是独立网格卡**；相似/大文件去重入口在「相册功能」子页（`gallery_features` → `manage_duplicates`）；**无「关于」卡片**（无版本号展示，`BuildConfig` 仅 DEBUG 判定）；备份与恢复已并入「数据与隐私」页，非独立网格项；**无「AI 助手」网格项**（远程/本地模型配置已拆为一级入口）。

### 3. 功能项清单

**模型中心**（`ModelCenterScreen`）：

- 分类页：`HorizontalPager` + `ScrollableCategoryTabs`（Chip 风格，可横滑）。固定顺序 = `must-have, recommended, photo-tagging, beauty-camera, chat`，空分类自动隐藏。
- **必须模型（Tier 1，7 个）**：`face-det-retina500m-mnn`、`face-landmark-2d106-mnn`、`face-embedding-glint360k-r100-mnn`、`florence2_base`、`mobileclip-onnx`、`opus-mt-zh-en`、`opus-mt-en-zh`。`MustHaveHeaderCard` 展示「N 必须，M 未下载」+「下载全部缺失」。
- **推荐模型（Tier 2）**：`CHAT_MODEL_IDS`（仅 `sherpa-onnx-zipformer-zh-en` ASR）+ `modnet-onnx`/`u2netp-onnx`/`mediapipe-face-landmarker`/`ediffiqa-face-quality-onnx`/`nima-aesthetic-onnx`。`RecommendedHeaderCard` 顶部含 WiFi 静默预下载开关。
- 下载/删除/进度：`ModelCardWithBadge`（下载/暂停/继续/取消/删除 + `LinearProgressIndicator` + 状态徽章 + 长按弹模型属性 JSON）。状态机 `DownloadStatus{PENDING,DOWNLOADING,PAUSED,COMPLETED,FAILED,CANCELLED}`。
- 模型清单来源：本地 `res/raw/llm_models.json`（16 模型，全 ModelScope）。**端侧文本 LLM `qwen3_5_2b` 已移除**（不在清单）。

**Wi-Fi 默认静默预下载**（推荐 Tab 可关）：`RecommendedModelAutoDownloader`，条件 = 设置开启（默认 `true`）+ WiFi + 有缺失推荐模型。不可重入，单模型失败不中断，不自动重试。**不预下载 KWS 唤醒词**。

**扫描前提醒**：进入相册 + **蜂窝网络** + Tier 1 缺失 → 弹下载对话框（「下载全部」/「以后再说」）。WiFi 由启动期静默下载。自动扫描触发条件：首次安装 **或**（充电 AND 夜间 23-7 点）；Tier 1 未下载则跳过并改弹蜂窝提醒。

**远程推理额度（客户端侧）**：`PoLangAuthClient.kt`，`baseUrl = https://api.polang.net`，`X-Platform: android` header。端点：`POST /auth/email/send`、`POST /auth/email/verify`（返回 token + `llmCallsUsed` + `llmCallsLimit`，默认 100）、`GET /auth/quota`、`DELETE /auth/account`、`DELETE /guest/device`。额度展示 = `QuotaCard`（`progress≥0.9` 转 error 色）。

**账号（Guest vs 邮箱注册）**：Guest = 未登录（`serverAuthToken` 空），由 `DeviceIdProvider` 生成 deviceId（默认即 Guest，无显式「以访客登录」）。邮箱注册：`EmailCodeAuthForm` → 发码 → 验码 → `repo.updateServerAuth`。已登录态：账户头像+邮箱、额度卡、刷新/登出、危险区「清除访客数据」+「删除账号」二次确认。

**数据与隐私页**（`DataPrivacyScreen`）：说明页（`PrivacySection` 文本块 + 隐私政策链接 `https://polang.net/privacy-policy/`）+ **备份与恢复入口行**（点击跳 `BackupRestoreActivity`，SAF 导出/导入 TAG JSON；2026-08 并入本页，不再是独立网格项）。**无 cloud_optimize 开关**。

**通信通道**（`CommunicationChannelScreen`）：单通道选择 `RemoteChannelType{FEISHU, TELEGRAM, NONE}`（FilterChip）+ 双通道凭据输入 + 连接状态文案。Telegram 区显示安全提示（error 色）。属用户自配置 IM 通道，不在隐私红线内。

**记忆事实页**（`MemoryFactsScreen`）：查看/编辑/删除/清空全部（二次确认）`memory_facts`。来源标签 `CHAT_TOOL`/`JS_DISPATCH`。

**上报问题**：`IssueReportClient`（`POST /v1/report-issue`），由 ChatScreen 调用，**非设置页独立入口**。

### 4. 核心状态与流程：设置导航树

```
SettingsScreen (MAIN)
├── [Hero] 账号卡 ──────────► ACCOUNT (EmailCodeAuthForm / QuotaDisplay)
├── [快选] 主题 / 语言
└── [Grid 2 列]
    ├── AI 记忆 ──────────► MemoryFactsScreen (memory_facts CRUD)
    ├── 人物 ─────────────► People (人物/关系图谱)
    ├── 通信通道 ─────────► CommunicationChannelScreen (飞书/Telegram/NONE)
    ├── 相册功能 ─────────► GALLERY (TAG 控制/查看器/去重/打标模型选择/GPU 加速)
    ├── 远程模型 ─────────► REMOTE_MODEL (远程模型配置/Agent 模式)
    ├── 本地模型 ─────────► LOCAL_MODEL (人脸检测引擎等本地模型配置)
    ├── 模型中心 ─────────► ModelCenterScreen (Pager: must-have/recommended/photo-tagging/beauty-camera/chat)
    ├── 沙盒与权限 ───────► SANDBOX (设备访问[含语音入口开关]/JS 沙盒权限)
    ├── 数据与隐私 ───────► DataPrivacyScreen (纯说明 + 隐私政策链接 + 备份与恢复)
    ├── 相机 ─────────────► CAMERA (相机状态记忆与重置[二次确认]；2026-08-16 由 CAMERA_BEAUTY 更名)
    └── 开发者选项 ───────► DEVELOPER (解锁后附加；调试浮层/诊断[LLM日志]/测试工具[debug]/日志模块配置)
```

### 5. 关键 UX 规则

- 模型扫描前提醒：仅蜂窝网络下弹「必须模型下载」对话框；WiFi 静默下载不打扰。
- Wi-Fi 预下载可控：模型中心「推荐」Tab 顶部卡开关，默认开。
- 下载生命周期独立于页面：`enqueueDownload` 由 Manager 托管，页面退出后继续；前台 Service `ModelDownloadForegroundService` 防进程被杀。
- 额度临近耗尽视觉警示：`used/limit ≥ 0.9` 进度条转 error 色。
- 删除账号 / 清空记忆：均二次确认对话框。

### 6. 数据模型概要

- **UserPreferences/DataStore**（`name = "user_preferences"`）：主题/语言、AI Agent（`ai_agent_mode` 默认 REMOTE、远程模型配置、`auto_execute_plans` 默认 true）、语音（`voice_command_mode` 默认 DISABLED、`voice_entry_enabled` 默认 false）、TAG 生成（`tag_generation_use_opencl` 默认 true、`tagger_model_key` 默认 AUTO、`auto_download_recommended_on_wifi` 默认 true）、相机记忆（`camera_memory_*` ~30 键）、Chat（`chat_input_mode`/`chat_current_session_id`）、远程通道（飞书/Telegram 凭据）、服务端认证（`server_auth_token`/`server_auth_email`）。详见 §3.3。
- **账号 Token**：`server_auth_token` / `server_auth_email`。Guest 标识 = `DeviceIdProvider.get()`（非 DataStore 键）。
- **模型清单**：`res/raw/llm_models.json`（16 模型，全 ModelScope）；不内置，运行时下载到 `filesDir/llm_models/<modelId>/`。
- **远程模型配置**：`ai_agent_remote_model_configs_v2`（JSON 多供应商）+ `ai_agent_selected_remote_model`（默认 `deepseek-v4-flash`）。

### 7. 隐私 / 降级边界

- **设置敏感开关**：`AiAgentPrivacyLevel{STRICT,PERMISSIVE}` 存在但**未在 UI 暴露**（代码默认 STRICT）。DataPrivacyScreen 纯说明页，无开关。
- **Guest 限制**：Guest 以 deviceId 计费；「清除访客数据」`DELETE /guest/device`。
- **额度降级**：客户端无「降级模式」，仅 HTTP 错误码；额度 ≥90% 视觉警示。
- **隐私红线**：用户图片/视频文件不上传远程；飞书/Telegram 属用户自配置 IM 回传（不在此列）。

### 8. iOS 对齐要点

- **已有（Phase 5/6.3）**：`SettingsScreen.swift`（Hero+网格骨架）、`ModelCenterView.swift`/`ModelDownloadCenterView.swift`（分类 Tab + `MustHaveHeaderCard`）、`ModelConfigStore.swift`（消费 KMP shared `RemoteModelConfigs`，`UserDefaults` 持久化，默认 `deepseek-v4-flash`）、`ModelCatalog.swift`（`ModelEntry` Codable 镜像 + `groupedByCategory()`）、`SettingsSubPages.swift`（Telegram `@AppStorage`）、`PrivacyInfo.xcprivacy`。
- **待对齐**：① server 账号接入（邮箱验证码登录 + quota + 删除账号 + 清除访客数据）；② Privacy Manifest（`NSPhotoLibraryUsageDescription` 等用途描述）；③ Apple Sign In（若上架且启用第三方登录则 Apple 强制）；④ `X-Platform: ios` header；⑤ App Store 2.5.2 合规——JS 下发代码需声明且不可用于改变核心功能；⑥ WiFi 静默预下载 + 扫描前提醒；⑦ 备份恢复（iOS 用 Files App / `UIDocumentPicker`）。

---

## §3 跨切面契约（附录）

### 3.1 导航路由表

来源：`Screen.kt:3-80`（路由全集）+ `MainActivity.kt:210-548`（NavGraph 注册）。`Main` 为首页容器，其余为 push 二级页。所有二级页 `fadeIn/fadeOut(tween 400ms) + slideInto/OutOfContainer`。

| route | 目标 Screen / 组件 | 进入方式 | 首页 | 参数 | 证据 |
|---|---|---|---|---|---|
| `main` | `MainPagerHost`（内含 4 页 Pager） | startDestination | ✅ | — | `Screen.kt:5`、`MainActivity.kt:212,240` |
| `chat` / `camera` / `gallery` / `people` | （声明；实由 Pager 第 1-4 页承载） | Pager 切页 | — | gallery 带 `?query&personId` | `Screen.kt:6-23` |
| `tag_control` | `TagGenerationControlScreen` | 设置/底部 Tab「打标」 | 否 | — | `MainActivity.kt:313` |
| `settings` / `settings/{category}` | `SettingsScreen(MAIN/category)` | 相册顶栏设置按钮 | 否 | `category:String` | `MainActivity.kt:333,387` |
| `model_center/{categoryTag}` | `ModelCenterScreen` | 设置/相册→模型中心 | 否 | `categoryTag:String=""` | `Screen.kt:41-49`、`MainActivity.kt:455` |
| `photo_editor/{sourceUri}?recipeUri&autoOptimize` | `PhotoEditorScreen` | 相册/聊天→编辑 | 否 | `sourceUri`(必)、`recipeUri?`、`autoOptimize=false` | `Screen.kt:51-72`、`MainActivity.kt:257` |
| `id_photo/{sourceUri}` | `IDPhotoScreen` | 聊天→证件照 | 否 | `sourceUri`(必) | `Screen.kt:74-79`、`MainActivity.kt:296` |
| `data_privacy` | `DataPrivacyScreen` | 设置→数据隐私 | 否 | — | `MainActivity.kt:478` |
| `memory_facts` | `MemoryFactsScreen` | 设置→记忆事实 | 否 | — | `MainActivity.kt:501` |
| `communication_channel` | `CommunicationChannelScreen` | 设置→IM 通道 | 否 | — | `MainActivity.kt:481` |
| `tag_viewer` / `duplicate_manager` | `TagViewerTestScreen` / `DuplicateManagerRoute` | 设置→标签查看/重复管理 | 否 | — | `MainActivity.kt:324,327` |
| `debug` / `jsbridge` / `search_test` / `sentencepiece_test` | Debug 类页 | 设置→Debug | 否 | — | `MainActivity.kt:513,527,532,537`（jsbridge/search_test/sentencepiece_test **仅 DEBUG**） |
| `llm_log` | `LlmCallLogScreen` | 设置→LLM 调用日志 | 否 | — | `MainActivity.kt:544`（release 仅指标，无消息内容） |

> iOS 落点：路由表为 Android NavGraph 概念；iOS 已实现主页面 4 页（人物页 UI 骨架 1311 行，非占位），二级页部分已实现（Settings/ModelCenter），PhotoEditor/IDPhoto/Tag 等仍缺口。

### 3.2 Capability → 意图路由表

> 执行层级：远程 tool_calls（模型决策）→ 端侧 Capability 执行（数据不出端）。所有 chat @Tool 均为 `ChatToolService.dispatchCommand` 薄封装。SSOT：`docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`。

**CHAT 场景**（21 个意图样本）：

| 意图示例 | 触发工具(@Tool) | 路由 Capability |
|---------------|-----------------|-----------------|
| 「找去年夏天的照片」 | `search_media` | ChatSearchCapability |
| 「只要 4 月的」(多轮窄化) | `refine_media_search` | ChatSearchCapability |
| 「类似的再多来点」 | `more_like_this` | ChatSearchCapability |
| 「盘点一下我的相册」 | `get_gallery_summary` | ChatGallerySummaryCapability |
| 「每月拍照趋势画成图」 | `run_gallery_script` + `draw_chart` | ChatRunScriptCapability |
| 「开始打标」 | `start_tag_scan` | ChatStartTagScanCapability |
| 「把截图标签的照片删了」 | `delete_media` | ChatMediaWriteCapability |
| 「收藏这张」 | `favorite_media` / `select_media` | ChatMediaWriteCapability |
| 「一键优化这张图」 | `ai_optimize` | AiOptimizeCapability |
| 「调亮一点」 | `adjust_image` | (inline handler，无 Capability) |
| 「磨皮调到 50」 | `edit_image` | ImageEditCapability |
| 「小宝是我女儿」 | `remember_person_relation` | PersonRelationCapability |
| 「记住我对花生过敏」 | `remember_fact` | MemoryCapability |
| 「换成深色主题」/「切换语言」 | `change_theme` / `change_language` | SettingsCapability |
| 「去相册」/「返回」 | `navigate_to` / `go_back` | NavigationCapability |
| 「打开微信」/「打开 wifi 设置」 | `launch_app` / `open_system_settings` | SystemCapability |

**CAMERA 场景**（page-scoped，`CameraCapability`）：`delay`+`capture` / `flip_camera` / `adjust_beauty` / `switch_filter`/`switch_style` / `toggle_recording`。

**飞书 RPA**（跨场景，**不进 CapabilityRegistry**，走 `RemoteControlToolService` 跨应用 a11y）：`click` / `scroll` / `input_text` / `get_screen_info`。iOS 不可移植。

### 3.3 数据与持久化概要

**两个独立 Room 数据库**：

| 库 | DB 文件 | schema 版本 | 实体数 | 迁移策略 |
|---|---|---|---|---|
| `AppDatabase` | `picme_database` | **v20** | **18** | 显式 Migration 2→20 链（`exportSchema=false`） |
| `LlmLogDatabase` | `polang_llm_log.db` | **v4** | 3 | `fallbackToDestructiveMigration(true)` |

**AppDatabase 全部 18 表**：`media_assets`（中央媒体索引）/ `chat_messages` / `chat_sessions` / `persons`（人脸聚类）/ `face_embeddings`（R100 512d）/ `photo_edit_recipes` / `tags`（遗留 ML Kit）/ `media_tag_cross_ref`（遗留）/ `ocr_words` / `ocr_word_occurrences` / `location_hierarchy` / `media_locations` / `tag_scan_tasks`（3 阶段任务）/ `media_feedback`（搜索反馈）/ `person_relations`（关系图谱）/ `memory_facts`（事实记忆）/ `chat_image_cache`（编辑结果图缓存）/ `optimize_feedback`（抽卡反馈）。

**LlmLogDatabase 3 表**：`llm_call_log`（推理指标）/ `tool_call_log`（动作指标，**不含参数/内容-隐私**）/ `js_run_log`（JS 沙箱事件，`script`/`resultPreview` 仅 DEBUG）。

> **无自定义相册（custom/smart album）表**。`album` grep 命中均为 MediaStore bucketId 查询字段或「保存到系统相册」动作。

**DataStore 主要键**（`name = "user_preferences"`，分类摘要，全量见 `UserPreferencesRepository.kt:56-164`）：主题/语言（`theme_mode`/`app_language` 默认 SYSTEM）、AI Agent（`ai_agent_mode` 默认 REMOTE、`ai_agent_remote_model_configs_v2`、`ai_agent_selected_remote_model` 默认 `deepseek-v4-flash`、`auto_execute_plans` 默认 true）、语音（`voice_command_mode` 默认 DISABLED、`voice_entry_enabled` 默认 false）、TAG（`tag_generation_use_opencl` 默认 true、`tagger_model_key` 默认 AUTO、`auto_download_recommended_on_wifi` 默认 true）、相机记忆（`camera_memory_*` ~30 键）、Chat（`chat_input_mode`/`chat_current_session_id`）、远程通道（飞书/Telegram 凭据）、服务端认证（`server_auth_token`/`server_auth_email`）、引擎（`gl_engine_recovery_available_at_ms`、`beauty_strategy` 默认 BIG_BEAUTY）。

**shared commonMain DTO（iOS 可直接消费的契约面）**：`MediaAsset` / `UserPreferences`（`ThemeMode`/`AppLanguage`/`AiAgentMode` 等）/ `RemoteChannelType` / `VoiceCommandMode` / `StructuredFilter` / `DuplicateGroup` / Agent 编排契约（`AgentContext`/`AgentAction`/`ExecutionPlan`/`AiAgentConfig`）/ `RemoteModelConfig`/`RemoteModelConfigs`（**iOS `ModelConfigStore.swift` 已直接消费**）/ `ChatStreamEvent`。iOS 契约面结论：`:shared` 编译为 XCFramework，commonMain 类型经 K/N 直接消费，互操作链路已验证畅通。

**模型文件清单**（不内置，运行时下载到 `filesDir/llm_models/<id>/`，全 ModelScope）：见 §2.9 / §2.7。共 16 模型，Tier 1 必须 7 个、Tier 2 推荐 6 个 + `qwen3_vl_2b`(1.4GB VLM 备选) + KWS(4MB)。

### 3.4 隐私红线与端云边界

> 引 ADR-008（2026-07-28 决策1）：**禁止向远程大模型/推理服务器上传用户图片/视频文件**；人脸检测/OCR/分类/打标等媒体处理必须 100% 端侧。文本、元数据、相册聚合摘要等非媒体数据可走远程推理（chat 默认远程）。豁免：飞书/Telegram 等用户自配置 IM 通道回传媒体给用户本人不属红线。

**100% 端侧清单（媒体文件与敏感感知数据，禁止出端）**：

- 人脸检测（MediaPipe 468→106 / MNN 2D106）
- OCR（图片文字识别）
- 图片分类/打标（Florence-2 默认 / Qwen3-VL-2B 备选，`LocalLlmEngine.imageInference`）
- 抠图/证件照/背景消除
- 美颜/滤镜/风格 OpenGL ES 渲染（含 `edit_image`/`adjust_image`/`ai_optimize` 实际像素处理）
- 人脸特征向量/聚类（`face.cluster` 端侧 QuickJS 沙箱）
- 相册媒体元数据查询（`gallery.query/summary/tags`，端侧 DB）
- 语音识别 ASR（Sherpa-ONNX 端侧，16kHz PCM）+ 唤醒词检测（端侧）

**可远程清单（文本/元数据/相册摘要，非媒体文件）**：

- chat 对话文本（用户输入 + 模型回复）
- 搜索意图/查询词（自然语言→tool_calls 决策）
- 相册聚合统计摘要（张数/趋势/标签分布等数值，非图片）
- 人物关系/事实记忆的文本内容
- tool_call 执行后的文本 observation（如「找到 12 张」，不含图片）

**防护机制**：① 防回归守卫 `RemoteInferenceNoMediaUploadGuardTest`（静态扫描远程推理源码，断言无媒体上传符号）；② iOS 组合根注入 `IosUnavailableImageInferenceEngine`（stub，`imageInference` 返回空串），确保 chat 链路无多模态上传；③ 多模态看图对话未来若要做必须改走端侧 VLM，不可把图喂给远程模型。

### 3.5 i18n 三语规范

**Android 三语结构（代码核验）**：

| 资源限定符 | key 数 | 角色 |
|---|---|---|
| `values/`（默认=EN） | 981 | 英文基准 |
| `values-zh-rCN/` | 981 | 简体中文（与 EN 完全对齐） |
| `values-zh-rTW/` | 963 | 繁体中文（缺 18 key，未完全对齐） |
| `values-zh/` | 679 | **遗留通用 zh 目录**（7 成覆盖，陈旧冗余，建议清理） |

**命名约定**：实际为 **snake_case 小写**（非小驼峰），形如 `tag_scan_control`、`gallery_people_entry`。运行时语言切换（`attachBaseContext` + `setLocale`，EN / 简中 / 繁中 / 跟随系统，切换触发 `recreate()`）。

**iOS 对齐现状**：`Localizable.xcstrings` **main = 417 key**（vs Android 981，覆盖仍不足，持续补）；三语 `en`/`zh-Hans`/`zh-Hant` 均就绪（zh-Hant 于 2026-08-10 补齐，commit `4de9221b`/`da2b78ae`）；xcstrings 以英文文案为 key（非 snake_case id），双端键对齐需建立映射。`iOS 缺口`（key 覆盖率）。

### 3.6 设计系统

**Token SSOT**：`shared/src/commonMain/resources/design-tokens.json`（v1.0.0），Android `core/designsystem/*` 与 iOS `DesignSystem/DesignTokens.swift` 各自镜像同步。

- **间距 Spacing**：xs/sm/md/lg/xl/xxl = 4/8/12/16/24/32。
- **圆角 Radius**：panel=24 / card=12 / button=10 / small=8 / thumbnail=2。底部悬浮 Tab 容器圆角为 **28dp**（独立硬编码，iOS 已对齐）。
- **色彩**：**主题色板 = Material 3 基线 + 动态取色（Material You），非 HyperOS 自定义品牌色**。Light primary `#6750A4`、Dark primary `#D0BCFF`；`dynamicColor = true`（Android S+）。固定功能色：focusRing `#00E5FF`、panelBackground `#CC000000`、shutterRing `#FFFFFF`、vibrantGreen `#00E676`/Blue `#2979FF`/Orange `#FF9100`/Pink `#FF4081`。模型中心专属色：mustHave `#E53935` 等。
- **字体**：仅显式定义 `bodyLarge`（16sp/24lh），其余回落 Material 3 默认。
- **iOS 对齐**：`DesignTokens.swift` 已 1:1 镜像全部 token，`iOS 已有`。但 iOS 尚未建立 Material You 动态取色等价体系（用 SwiftUI `.accentColor` + `.ultraThinMaterial` 毛玻璃）。

### 3.7 性能红线（NFR 摘要）

来源：`docs/01-PRODUCT/NFR_SPEC.md` v1.1。所有指标为硬红线（Hard Limit）。

| 维度 | 红线 | 目标 |
|---|---|---|
| 冷启动 → 首帧预览 | ≤ 500ms | ≤ 400ms |
| 预览帧率（高/低端机） | ≥ 30fps | ≥ 55/30fps |
| 单帧处理耗时 | ≤ 16ms | ≤ 12ms |
| 参数调节 → 画面变化 | ≤ 100ms | ≤ 50ms |
| 拍照 1080p/4K GPU 处理 | ≤ 300/800ms | ≤ 200/600ms |
| 相册滚动（1000+ 照片）帧率 | ≥ 120fps | ≥ 120fps |
| 快门延迟（按下→反馈） | ≤ 50ms | ≤ 30ms |
| 美颜引擎内存 / CPU | ≤ 30MB / ≤ 15% | ≤ 25MB / ≤ 10% |

**稳定性**：整体崩溃率 ≤ 0.1%、美颜引擎崩溃率 ≤ 0.05%、ANR ≤ 0.1%、降级恢复成功率 ≥ 95%。

**缺口（NFR_SPEC 未含，建议补全）**：LLM 首 token 延迟 / 端到端命令延迟；包体积上限。

> iOS 落点：iOS 侧尚无对等性能门禁；上述红线作为实现目标参考，相机/美颜相关依赖 Metal 管线达成。

---

## §4 iOS 实现对齐总览

> 截至 2026-08-09（Phase 6.3）。按落地状态与优先级分层。iOS 状态判定依据 `iosApp/PoLang/Features/` 实际 Swift 文件。

### 4.1 已落地（Phase 5 / 6.2 / 6.3）

| 功能 | iOS 实现证据 | 对齐度 |
|------|-------------|--------|
| 相册默认首页 + 悬浮 4 Tab | `MainTabView.swift` / `FloatingBottomTab.swift` | iOS 已有（默认页 + 4 Tab）；待对齐跟手 Pager |
| 相册网格 / 媒体查看器 / 权限四态 | `GalleryGridView` / `MediaPagerView` / `GalleryPermissionStore`（含 limited） | iOS 已有 |
| 相机 MVP（美颜 + 拍照离屏 + 对焦变焦） | AVFoundation + Metal 4-pass + MediaPipe 468→106 | iOS 已有（美颜 4 项 + 9 LUT；风格/录像待补） |
| Chat 流式文本链路 | `ChatAgentBridge.kt` + `ChatViewModel.swift` + `ChatView.swift` | iOS 已有（文本/媒体卡/空态）；多消息类型/多会话待补 |
| Agent 编排（shared） | `IosAgentComposition.kt`（commonMain 全复用） | iOS 已有（编排层）；仅注册 1 Capability |
| 设置主页 + 模型中心骨架 | `SettingsScreen.swift` / `ModelCenterView.swift` / `ModelConfigStore.swift`（消费 KMP `RemoteModelConfigs`） | iOS 已有（骨架）；账号/quota/预下载待补 |
| 设计系统 token 镜像 | `DesignTokens.swift`（1:1） | iOS 已有 |
| 场景同步（onMainPageChanged） | `MainTabView.swift:60-66`（onAppear + onChange(of: currentPage) 调 `IosAgentComposition`） | iOS 已有 |

### 4.2 进行中 / 待对齐（Phase 6.x）

| 功能 | 缺口 | 平台注意 |
|------|------|----------|
| ~~跟手横滑 Pager + 4 页常驻~~ | ✅ 已对齐（`e8582301`，`TabView(.page)` 跟手物理吸附 + 4 页常驻） | — |
| chat 富消息类型 | 流式文本经 `onText` 逐字吐已 live；`success`/`error` 工具确认仍缺；富消息（图片/图表/表格/代码）需重构 `ChatMessage` | 重构消息模型 + 补确认反馈 |
| iOS i18n | xcstrings 417 key（vs 981，覆盖仍不足）；三语就绪（zh-Hant 已补） | 扩 key 集 |
| Telegram 通信通道 | 飞书未接入 | 用户自配置 IM，非推理上传 |

### 4.3 缺口（Phase 6+，需新建）

| 功能域 | 缺口功能 | 关键平台替换 |
|--------|----------|-------------|
| 搜索 | 整条搜索链路（Parser/Segmenter/Vocabulary/Pipeline/Engine） | 前置依赖 VLM 标签+人脸聚类+MobileCLIP+OCR+SQLite；`QueryParser` 用 `java.util.Calendar` 需换 `kotlinx-datetime` |
| TAG | 3-Pass 流水线（RetinaFace/R100/MobileCLIP/Florence-2） | MNN Metal 后端（**precision 档位锁定坑**）+ **MetalGuardian 新设计** + FGS→BGTaskScheduler（**iOS ~30s 限制，需改增量/手动**） |
| 编辑 | 静态美颜编辑器/抠图/证件照/AI 优化抽卡 | FBO→Metal MSL；`BeautyParams`/`FilterType`/`StyleFilter` 已 commonMain；ONNX Runtime iOS 可用；FUSION 纯数组可移植 |
| 人物/记忆 | 人物关系图谱/封面美学/事实记忆**后端**（UI 骨架 1311 行已建，未接 shared） | Room→SQLDelight；NNAPI→CoreML/Metal；`KinshipLexicon`/`PersonQueryResolver` 纯 Kotlin 宜下沉 shared |
| 相机 | 录像（美颜录制）/ 十字星 / 风格特效 / 语音入口 | Metal 美颜录制；语音 Sherpa-ONNX iOS 单独实现 |
| 设置 | 账号登录 / quota / WiFi 静默预下载 / 备份恢复 | `PoLangAuthClient` 等价层 + `X-Platform: ios` + `UIDocumentPicker` + App Store 2.5.2（JS 下发声明） |

### 4.4 平台不对齐（iOS 无等价能力，不计划复刻）

| 功能 | 原因 |
|------|------|
| 飞书/Telegram 远程控制（跨应用 a11y RPA） | iOS 无 AccessibilityService 等价，`RemoteControlToolService` click/scroll/input 不可移植 |
| 悬浮聊天气泡 | iOS 无系统悬浮窗（`TYPE_APPLICATION_OVERLAY`）等价 |
| `launch_app`/`open_system_settings` 强能力 | iOS 沙盒限制，SystemCapability 能力远弱于 Android |
| HyperOS 后台冻结检测 | iOS 无厂商冻结问题，`BackgroundScanGuard` 无意义 |

### 4.5 已移除（iOS 勿复刻）

端侧文本 LLM（`qwen3_5_2b`，2026-08 移除）；GPUPixel（自研引擎替代）；InsightFace ONNX/NCNN（MediaPipe+MNN 替代）；langchain4j fork（Koog 替代）；`PrivacyGuard.isRemoteAllowed()` 死代码；shared 侧 `AiAgentMode.LOCAL`（已删，仅 app 层 `UserPreferences.AiAgentMode` 保留 LOCAL 遗留枚举值，iOS 勿复刻）。

---

## §5 附录

### 5.1 文档漂移清单（全量汇总）

> 逐项以代码核验。**冲突时以代码为准**。严重度：高=产品行为/数据模型实质不符；中=参数/字段/范围不符；低=命名/措辞/遗留。

**导航 / 骨架 / 跨切面**

| # | 文档声称 | 代码实际 | 严重度 |
|---|----------|----------|--------|
| N1 | （任务前提）设计系统 HyperOS 风格 `#00E5FF`/`#FF4081`/`#121212` | 主题色板 = Material 3 紫色基线（`#6750A4`/`#D0BCFF`）+ 动态取色；`#00E5FF`/`#FF4081` 仅为功能色；暗色背景 `#1C1B1F`（非 `#121212`） | 高 |
| N2 | i18n 命名「`[feature]_[desc]` 小驼峰」 | 实际 snake_case 小写（`tag_scan_control`） | 低 |
| N3 | CLAUDE.md「三语：values/values-zh-rCN/values-zh-rTW」 | 额外存在 `values-zh/`（679 key，陈旧冗余） | 中 |
| N4 | 繁中对齐 | values-zh-rTW 仅 963 key（差 18），未完全对齐 | 中 |
| N5 | iOS 三语双端对齐 | iOS xcstrings 417 key（vs 981）+ 三语就绪（zh-Hant 2026-08-10 补齐）；~~仅 en/zh-Hans 无 zh-Hant~~（已修正） | 中（降级：仅余 key 覆盖缺口） |
| N6 | NFR 含 LLM 首 token<2s、命令<3s、包体积<150MB | NFR_SPEC.md 无此三项 | 中 |

**相册与浏览（02）**

| # | FEATURES 声称 | 代码实际 | 严重度 |
|---|---------------|----------|--------|
| G1 | §1.2 分类标签栏 [全部][人物][地点][事物][收藏][截图][视频] | 不存在；实为顶栏 GroupingMenu 下拉（无分组/日期/有脸/无脸/人物/风景/地点） | 高 |
| G2 | §1.2 瀑布流 | 等比方块网格 Adaptive 110dp + aspectRatio 1f | 中 |
| G3 | §1.2 视图模式 网格/时间线/年视图 | 仅网格一种 | 高 |
| G4 | §1.2 自定义相册：用户创建命名 | 完全未实现（无表无 UI） | 高 |
| G5 | §1.3 工具栏 关闭/编辑/OCR/AI编辑/信息/分享/删除/收藏 | 顶栏=返回+日期/信息/更多(Vision+OCR)；底栏=分享/编辑/证件照/删除。无 AI 编辑、无收藏 | 高 |
| G6 | §1.3 信息浮层 分辨率/文件大小/设备型号/EXIF | 无此四项；实际显文件名/类型/日期/时长/来源/位置/美学评分/人脸/标签/OCR | 高 |
| G7 | §1.6 批量 收藏/移动至相册/应用美颜滤镜 | 仅删除/分享/全选 | 高 |
| G8 | §1.6 存储管理 总数/空间/最近删除30天/大文件 | 仅重复检测；无回收站/大文件/空间统计 | 高 |
| G9 | §1.2 底部导航 三入口图标+文字标签 | 悬浮纯图标 4 Tab，无文字 | 中 |

**自然语言搜索（03）**

| # | 文档声称 | 代码实际 | 严重度 |
|---|----------|----------|--------|
| S1 | 支持「视频/收藏」筛选 | `StructuredFilter` 无 mediaType/favorite 字段，词表无词 | 中 |
| S2 | 搜索历史 ≤20 条 | 完全未实现（无 `SearchHistory` 表；`R.string.search_history` 仅 Chat 侧栏用） | 中 |

**图片编辑（04）**

| # | 文档声称 | 代码实际 | 严重度 |
|---|----------|----------|--------|
| E1 | 撤销/重做 ≤20 步 | **30 步** | 中 |
| E2 | MattingRouter 三后端(U2Netp/ModNet/MediaPipe) | `choose()` **只路由 U2NETP/MODNET**；MediaPipe 走 FUSION/SELFIE_SEGMENTATION | 中 |
| E3 | 证件照含「签证」 | **仅 4 种**（1寸/2寸/小1寸/小2寸） | 中 |
| E4 | 美颜含「眉毛」 | **无 eyebrow** 字段/UI | 高 |
| E5 | smartOptimize 远程 VLM | **未实现**，仅 Phase 2 规划 | 中 |
| E6 | `ai_optimize` 走抽卡 | 实际走 `optimize()` 固定预设 | 中 |
| E7 | 背景模式 透明/纯色/自定义换背景 | 三模式 TRANSPARENT/COLOR/BLUR；编辑器 UI 默认仅透明 | 低 |
| E8 | vignette 调节项 | 数据模型有字段，AdjustPanel UI 未暴露 | 低 |
| E9 | 保存后对比模式 | 保存后直接返回；对比仅编辑中长按 | 低 |

**AI 对话（07）**

| # | 文档声称 | 代码实际 | 严重度 |
|---|----------|----------|--------|
| C1 | 单会话消息上限 1000 条 | `MAX_MESSAGES = 500` | 中 |
| C2 | 图片消息长按「保存/分享」 | 仅复制到剪贴板，无保存/分享菜单 | 中 |
| C3 | 消息类型 6 类 | 实际 11 值（缺 user_image_text/media_results/chart/agent_edit_result/optimize_candidates） | 中 |
| C4 | `QuickActionBar` 核心组件 | 文件存在但 ChatScreen **未引用**（grep 零命中） | 低 |
| C5 | `ModelOption.Remote` label `"远程模型"` | 实际 `"远程"` | 低 |
| C6 | `MessageList.kt`/`ChatInputBar.kt` 独立文件 | 内联在 `ChatScreen.kt` | 低 |
| C7 | log tag `PoLang:[ModuleName]` | `ChatViewModel` TAG = `"ChatViewModel"`（未带前缀） | 低 |
| C8 | 输入框左侧固定显「当前模式 ▼」 | `ModelCapsuleButton` 仅 `hasUserKey` 时显示 | 低 |

**Agent 编排（05）**

| # | 文档声称 | 代码实际 | 严重度 |
|---|----------|----------|--------|
| A1 | CLAUDE.md SystemCapability「跨应用 a11y」 | 跨应用 a11y 实由飞书 RPA 的 `RemoteControlToolService` 承担；SystemCapability 仅 `launch_app`/`open_system_settings` 两命令 | 中 |
| A2 | `PrivacyGuard.isRemoteAllowed()` 在用 | 死代码，仅单元测试调用（ADR-008 §5 自述未删） | 低 |
| A3 | `AiAgentMode` 唯一定义 | `UserPreferences.kt:150`(OFF/LOCAL/REMOTE) 与 `AiAgentConfig.kt:11`(OFF/REMOTE/FEISHU) 重复，前者疑似遗留 | 低 |
| A4 | CameraCapability 命令数 | 12 命令（与 CAPABILITY_REGISTRY.md 一致，无漂移） | — |

**人物记忆 / TAG（06）**

| # | 文档声称 | 代码实际 | 严重度 |
|---|----------|----------|--------|
| P1 | PRODUCT/FEATURES「人物关系 🔄 开发中未合并 main」 | **已注册 main 并完整实现**（`PoLangApplication.kt:742/744`） | 高 |
| P2 | AppDatabase v13 | 实际 **v20** | 高 |
| P3 | 谓词 7 类（配偶/子女/父母/兄弟姐妹/祖辈/孙辈/其他） | 实际 23 项枚举，含非亲属（恋人/朋友/同学/同事/偶像） | 高 |
| P4 | 人物页「相册顶栏 + 设置一级入口直达（`Screen.People`）」 | 实为底部 Pager Tab + 悬浮图标；`Screen.People` 路由未用；设置无人物入口 | 中 |
| P5 | 关系来源含 JS | 实际仅 `RENAME_DIALOG`/`CHAT_DECLARATION` 两源（JS 仅可写事实记忆） | 低 |
| T1 | CLAUDE.md「Qwen3-VL-2B 端侧 VLM 打标」 | **Florence-2 默认 / Qwen3-VL-2B 备选** | 中 |
| T2 | TAG `MobileClipTagClassifier` 分类 | Pass3 实际**已旁路**（仅 Pass1 语义向量在用） | 中 |
| T3 | Pass2「DBSCAN」 | 默认实为自适应 k-NN 连通分量（Plan B） | 中 |
| T4 | `tags`/`media_tag_cross_ref` 主存储 | 遗留 ML Kit 表；现主存储为 `media_assets.labelsEn/Zh/labels` JSON | 中 |
| T5 | JS 沙盒可触发扫描 | JS 仅 `tag.scan_status` 只读，不能触发 | 中 |

**相机（08）**

| # | FEATURES 声称 | 代码实际 | 严重度 |
|---|---------------|----------|--------|
| M1 | §4.1 美颜默认 磨皮35/美白25/大眼20/唇色40/腮红20 | **全为 0** | 高 |
| M2 | §4.1 含「眉毛」0-100 d15 | **无此字段/滑块** | 高 |
| M3 | §4.3 快门 50ms 黑场 + 按钮缩放 | 黑场 **80ms**；**无按钮缩放** | 中 |
| M4 | §4.2 滤镜「风格特效可与色调叠加」 | **互斥**（选一清另一） | 中 |
| M5 | §4.1「长按预览区查看原图」 | 相机页**未实现** | 中 |
| M6 | §4「拍照后自动进入相册编辑流程」 | 仅存图+缩略图刷新，**不自动跳转** | 中 |

**设置 / 账号（09）**

| # | 文档声称 | 代码实际 | 严重度 |
|---|----------|----------|--------|
| SE1 | 主页含「关于」卡片 + 版本号 | 无「关于」卡片（`BuildConfig` 仅 DEBUG 判定） | 中 |
| SE2 | 模型中心是「AI 助手卡片第一项」 | 独立网格卡 | 中 |
| SE3 | 相似/大文件去重在主页网格独立卡 | 在「相册功能」子页 | 低 |
| SE4 | DataPrivacyScreen 含「隐私开关」/「云端 AI 优化开关」 | **纯说明页**，无开关；全代码库无 cloud_optimize 字符串 | 高 |
| SE5 | `AiAgentMode.LOCAL` 在 UI 展示 | 仅 REMOTE 可选（端侧文本 LLM 已移除），枚举保留作离线兜底 | 低 |
| SE6 | token 前缀 `picme_at_*` | 服务端约定，客户端代码无此前缀逻辑 | 低 |

> **漂移根因**：FEATURES.md / PRODUCT.md 为「规划即文档」式维护，未随代码演进同步；CLAUDE.md 部分能力描述停留在历史架构。建议以本文档为 iOS 对齐基线，FEATURES.md/PRODUCT.md 另行修订（不在本文档范围）。

### 5.2 相关文档索引

**产品层**：
- `PRODUCT.md`（仓库根）— 产品目标与路线图（What）
- `docs/01-PRODUCT/FEATURES.md` — 交互与 UX 细节（How；**含漂移，见 §5.1**）
- `docs/01-PRODUCT/NFR_SPEC.md` — 非功能规格（性能/稳定性/隐私红线，v1.1）

**架构层（`docs/02-ARCHITECTURE/`）**：
- `ADR/ADR-005-*` — 本地/远程协议分离
- `ADR/ADR-008-*` — 隐私红线（端云边界）
- `AGENT_ARCHITECTURE.md` — Agent 运行时架构

**技术规格（`docs/03-TECHNICAL-SPECS/`）**：
- `BEAUTY_ENGINE_TECH_SPEC.md` — 渲染管线/降级/恢复
- `FACE_DETECTION_ENGINE_ARCHITECTURE.md` — MediaPipe + MNN 双引擎

**Agent 能力（`docs/04-AGENT-CAPABILITIES/`）**：
- `CAPABILITY_REGISTRY.md` — Command→Capability 路由 SSOT

**iOS 改造（`docs/superpowers/`）**：
- `specs/2026-08-09-ios-product-reference-design.md` — 本文档设计说明
- `plans/2026-08-07-polang-kmp-ios-transformation.md` — KMP/iOS 技术改造路线图（Phase 划分 SSOT）

### 5.3 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-09 | 初版。全量核验 Android main 分支 9 功能域 + 7 跨切面契约，产出 iOS 产品规格基线。iOS 状态截至 Phase 6.3。 |
| 2026-08-10 | 漂移回写（D1/D4）：人物页 UI 骨架已落地 1311 行（原记「0 文件」）；i18n zh-Hant 已补齐、key 191→239（原记「无 zh-Hant」）。状态同步至代码实况。 |

---

> **核验方法**：分域并行 subagent 逐项对照源码（Read/Grep/Glob），漂移只记录不修文档。每功能项附 `文件路径:行号` 证据，证据全集见各域原核验记录。所有功能状态以 Android `main` 分支 v1.0.34 为准。

