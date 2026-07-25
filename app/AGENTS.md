# App 模块技术实现规范 (App Module Implementation)

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:app` 主应用模块的实现细节（架构、组件、导航、依赖注入）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 美颜引擎实现细节见 `beauty-engine/AGENTS.md`；Agent Runtime 实现细节见 `runtime-core/AGENTS.md`。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：`:app` 是 PoLang 的主 Android 应用模块，承载 Compose UI、页面导航、依赖注入、数据持久化、网络请求和功能集成。作为最外层模块，`:app` 负责将 `:runtime-core`、`:beauty-api`、`:beauty-engine`、`:sentencepiece` 四个独立库组装为完整应用（`:agent-core` 的能力已下沉到 `:runtime-core` 中，由 `:runtime-core` 以 `api` 方式透出）。

**主要维护者**：[RD] 全栈工程师

**阅读对象**：CO、PM、RD、CR、QA、AI Agent

---

## 1. 核心架构 (Core Architecture)

### 1.1 分层架构

```
features/                 ← Compose UI + ViewModel（用户可见页面）
    ↓
domain/usecase/           ← 业务逻辑编排（纯 Kotlin，无 Android 依赖）
domain/repository/        ← 仓储接口定义
    ↓
data/                     ← 仓储实现、Room DB、DataStore、Retrofit
    ↓
di/                       ← AppContainer 手动 DI（无 Hilt/Dagger）
```

### 1.2 页面导航（7 屏，Gallery 为默认首页）

| Screen | Route | 定位 |
|--------|-------|------|
| `Gallery` | `gallery` | **默认首页** — 智能相册、媒体浏览、AI 搜索、分类管理；底部悬浮 Tab 以纯图标聚合 Camera/Chat/ModelCenter 入口；设置入口在顶部栏最右侧 |
| `Chat` | `chat` | 二级页 — AI 对话主页，模型切换；顶部栏提供返回相册按钮 |
| `Camera` | `camera` | 辅助入口 — 拍照、美颜预览、语音控制 |
| `Editor` | `editor` | 图片编辑 — 美颜调节、滤镜、风格特效（当前未注册在 NavHost，从相册/MediaPager 进入） |
| `Settings` | `settings` | 设置 — 主菜单，展示 6 个分类入口 |
| `SettingsCategory` | `settings/{category}` | 设置二级分类页 — `personalization`、`ai_agent`、`gallery`、`camera_beauty`、`system`、`developer` |
| `DuplicateManager` | `duplicate_manager` | 相册功能子页 — 重复/相似照片扫描与删除，从 Settings「相册功能」卡片进入 |
| `Debug` | `debug` | 开发工具 — 日志、截图、样本数据生成 |

> **2026-06 产品重心转移**：Gallery 为默认首页，Camera/Chat/ModelCenter 作为纯图标入口从 Gallery 底部悬浮 Tab 进入，Settings 从顶部栏进入；设置页已拆分为 6 个二级分类页，主菜单保持一屏可见；Model Center 内置于 Settings 的 AI 助手卡片第一项，分类按服务功能（必须/聊天/相册打标/美颜相机）重排，聊天分类聚合文字与语音模型，并提供必须模型一键下载；重复照片管理内置于 Settings 的相册功能卡片；Camera 页已移除设置入口。
> - **16 KB 适配**：MediaPipe tasks-vision 升级至 0.10.26，sherpa-onnx 升级至 1.13.3（内置 ONNX Runtime 1.24.3），`onnxruntime-android` 同步升级至 1.24.3，上述 native lib 均已 16 KB 对齐，满足 Google Play Android 15+ 要求。详见 `PRODUCT.md`。

### 1.3 关键入口文件

| 文件 | 职责 |
|------|------|
| `PoLangApplication.kt` | Application 初始化：DI 容器、Native 库加载、AgentOrchestrator 预配置 |
| `MainActivity.kt` | 单 Activity：Compose NavHost、主题/语言管理、CapabilityHost、模型下载弹窗 |
| `navigation/Screen.kt` | sealed class 定义所有路由 |

---

## 2. 子包结构 (Package Structure)

基础包：`com.mamba.picme`

### 2.1 功能层 (`features/`)

| 功能 | 路径 | 核心文件 | 说明 |
|------|------|---------|------|
| **Agent** | `features/agent/` | `GlobalAgentPanel.kt` | 全局悬浮 Agent 面板 |
| **Chat** | `features/chat/` | `ChatScreen`, `ChatViewModel`, `ChatThreadSidebar`, `ChatTitleGenerator` | AI 对话二级页，从相册首页进入，支持多线程；首条消息自动生成会话标题 |
| **Chat JS** | `features/chat/js/` | `QuickJsEngine`, `QuickJsConverter`, `GalleryScriptHandlers`, `GalleryJs`, `ChartJs`, `CapabilityDispatchHandler` | QuickJS 沙箱引擎与 JSBridge 应用层（见下方 JS Engine 说明） |
| **Camera** | `features/camera/` | `CameraScreen`, `CameraPreviewContent`, `CameraAgentCommandHandler` | 相机预览、美颜实时渲染、Agent 命令处理 |
| **Common** | `features/common/chat/` | `AgentChatComponents`, `AgentMessage`, `AiChatScreen` | Chat UI 共享组件库（Camera/Gallery 复用） |
| **Gallery** | `features/gallery/` | `GalleryScreen`, `MediaViewModel` | 智能相册浏览、AI 搜索 |
| **Editor** | `features/editor/` | `ImageEditScreen` | 图片编辑（美颜/滤镜/风格） |
| **Settings** | `features/settings/` | `SettingsScreen`, `SettingsViewModel`, `ModelCenterScreen` | 设置与模型管理 |
| **Debug** | `features/debug/` | `DebugScreen`, `LogOverlay`, `ScreenshotUtil` | 开发调试工具 |

> **2026-07-25 JS Engine（QuickJS 沙箱，`features/chat/js/`）**：
> - `QuickJsEngine` / `QuickJsConverter`：dokar3 quickjs-kt 1.0.5 引擎适配器（唯一生产引擎实现，QuickJS 依赖仅 `:app` 引入），实现 `:runtime-core` 引擎无关的 `JsEngine` 接口；eval 带超时（默认 5s），bridge 经 `__bridgeCall`/`__bridgeCallAsync` 绑定 + bootstrap JS 注入
> - `GalleryScriptHandlers.registerGalleryHandlers`：gallery/media/face/tag **10 个只读取数 handler 的唯一注册点**（全部 async，JS 侧必须 `await bridge.callAsync`），ChatViewModel 持久 JsRuntime 与 Debug 页 JsBridgeDemo 共用，新增/修改 handler 只改这里
> - `GalleryJs`：JS ↔ 查询模型字段转换（parseQueryFilter / toResultJsValue 等），media.meta 白名单不回 uri/GPS/ocrText/embedding
> - `ChartJs` + `assets/js/chart_bootstrap.js`：Chart.bar/line/pie/timeline → SVG，图卡落库为 CHART 消息，summary 回传 LLM
> - `CapabilityDispatchHandler`：`capability.dispatch` 写通路（delete_media/favorite_media/select_media/get_gallery_summary 白名单），按 `CommandRisk` 分级，写操作经 `WriteConfirmationController`（`features/chat/`）弹确认框——缩略图预览、120s 超时按拒绝、并发确认互斥串行、脚本结束在途确认失效；落点 `ChatMediaWriteCapability`（CHAT 场景，删除走 MediaStore 授权，favorite/select 会话级无持久化）
> - 完整规格见 `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`

### 2.2 领域层 (`domain/`)

| 子包 | 内容 | 说明 |
|------|------|------|
| `usecase/` | `AiAgentUseCase`, `FindDuplicateMediaUseCase`, `GetGroupedMediaUseCase`, `OcrUseCase` | 业务用例：Agent Facade、去重、分组、OCR |
| `repository/` | `MediaRepository`, `UserPreferencesRepository`, `UserSettingsRepository` 等接口 | 仓储抽象 |
| `model/` | `AiAgentCommand`, `LlmProviderConfig`, `MediaAsset`, `UserPreferences` 等 | 领域数据模型 |
| `search/` | `MediaSearchEngine`, `ExplicitFirstSearchPipeline`, `QuerySegmenter`, `QueryParser`, `SegmentedQuery`, `ExplicitFilter`, `ContentFilter` | 自然语言图片搜索：显式约束优先分段检索 + 规则/LLM/语义混合排序；Chat 场景由 `SearchIntent` 直接驱动 `MediaSearchEngine.search(filter)` |
| `tag/` | `TagGenerationScheduler`, `TagScanOrchestrator`, `OpenClGuardian`, `TagCategory`, `MlKitTagExtractor` | TAG 生成编排、OpenCL 守护、类别定义、ML Kit 英文标签提取 |
| `preview/` | `BeautyPreviewProvider` | 美颜预览提供者接口 |

### 2.3 数据层 (`data/`)

| 子包 | 内容 | 说明 |
|------|------|------|
| `local/` | `AppDatabase`（Room）、`MediaDao`、`ChatMessageDao`、`ChatSessionDao` | Room 数据库 + DAO |
| `remote/openai/` | OpenAI API 客户端（Retrofit） | 远程 LLM 网络层 |
| `remote/anthropic/` | Anthropic/Claude API 客户端（Retrofit） | 备用远程 LLM |
| `download/` | `LlmModelDownloadManager`、`ModelDownloadForegroundService` | LLM 模型下载管理 + 前台服务 |
| `repository/` | 仓储接口的 Room/DataStore/Network 实现 | 数据源实现 |
| `preferences/` | DataStore Preferences | 用户偏好持久化 |

### 2.4 基础设施 (`core/`、`di/`、`service/`)

| 子包 | 内容 | 说明 |
|------|------|------|
| `di/` | `AppContainer`、`AppContainerImpl` | 手动 DI（无 Hilt/Dagger） |
| `core/common/` | `Logger`、`DuplicateImageDetector` | 共享工具 |
| `core/designsystem/` | `Color`、`Theme`、`Typography` | Compose 设计系统 |
| `core/image/` | `CoilConfig`、`GpuBeautyProcessor`、`ImageProcessor` | 图片加载与处理 |
| `service/chat/` | `FloatingChatBubbleService` | 悬浮聊天气泡 |
| `service/accessibility/` | `PoLangAccessibilityService` | Agent 自动化辅助服务 |
| `testing/` | Agent 自动化测试框架 | 测试基础设施 |

---

## 3. 模块集成 (Module Integration)

### 3.1 依赖关系

```
:app
 ├── :runtime-core    ← Agent Runtime 核心（编排、推理、语音、远程；内部包含 :agent-core 基础 API）
 ├── :beauty-api      ← 美颜 API 契约
 ├── :beauty-engine   ← 美颜引擎实现
 └── :sentencepiece   ← SentencePiece tokenizer
```

### 3.2 关键集成点

| 集成场景 | 入口类 | 说明 |
|----------|--------|------|
| Agent 交互 | `AiAgentUseCase` → `AgentOrchestrator` | Facade 模式，委托给 :runtime-core 的 `AgentOrchestrator` |
| 美颜预览 | `BeautyPreviewProvider` → `BeautyPreviewEngine` | 通过 beauty-api 接口调用 |
| 人脸检测 | `FaceDetector`（beauty-api 接口） | MediaPipe/MNN 双引擎 |
| 远程推理 | `RemoteReActAgent`（:runtime-core） | OpenAI Chat Completions API + tool_calls |
| TAG 生成 | `TagGenerationService` → `TagScanOrchestrator` | 3-Pass 混合管道 + 独立 ML Kit 英文标签 Pass，`mlKitLabels` 字段与 Qwen `labels` 字段解耦，OpenCL 超时自动降级 CPU；人脸对齐采用方案 B（2D106 关键点替换 RetinaFace 5 点），ROI/2D106/ArcFace R100 均优先走 MNN OpenCL GPU；ETA 按 Pass 独立统计、取中位数并设冷启动默认值 |
| 自然语言搜索 | `GallerySearchBar` → `MediaSearchEngine`<br>`ChatViewModel` → `ChatSearchCapability` → `MediaSearchEngine` | **Gallery 入口**：Layer 0.5 QuerySegmenter → Layer 1 QueryParser → Layer 2 显式召回 → Layer 2.5 MobileCLIP 语义 → Layer 3 融合排序。<br>**Chat 入口**：本地/远程 LLM 输出 `AgentCommand.SearchMedia(query, intent)`，`ChatViewModel` 将 `SearchIntent` 转为 `StructuredFilter` 后直接调用 `MediaSearchEngine.search(filter)`；多轮细化走 `RefineMediaSearch` 并在上一轮结果集内过滤。`QueryParser` 新增近半年/近 N 个月规则作为兜底。 |
| JS 沙箱脚本 | `ChatRunScriptCapability` → `ChatViewModel.onRunScript/onDrawChart` → `JsRuntime`（QuickJS） | LLM tool_call（run_gallery_script/draw_chart）经 CapabilityRegistry（CHAT 场景）落入持久 JsRuntime；`jsEvalMutex` 串行 eval，超时 5s（含 capability.dispatch 写脚本放宽至 180s）；写操作经 CommandRisk 分级 + 用户确认 → `ChatMediaWriteCapability` |

---

## 4. 编码规范 (Code Conventions)

### 4.1 全局强制规则

- **包名**：禁止 `com.mamba.picme.*` 完全限定名（使用 import）
- **导入**：禁止通配符导入（`import com.mamba.picme.*`）
- **Lambda**：参数必须显式命名（禁止 `it`）
- **日志标签**：格式 `PoLang:[FeatureName]`（如 `PoLang:Camera`、`PoLang:Chat`）
- **缩进**：Kotlin 4 空格；XML/JSON/MD 2 空格

### 4.2 I18N（强制）

- 禁止硬编码用户可见字符串
- 所有字符串资源在 `values/strings.xml`（EN）、`values-zh-rCN/strings.xml`（简中）、`values-zh-rTW/strings.xml`（繁中）三语同步

### 4.3 红线（不可突破）

| 红线 | 检查方式 |
|------|----------|
| `[PRIVACY]` 敏感数据本地推理 | 权限清单扫描、网络抓包 |
| `[PERF]` 交互 < 100ms，快门 < 50ms | 性能测试 |
| `[I18N]` 三语同步，禁止硬编码 | 资源文件检查 |
| `[AGENT-FIRST]` 遵循 Agent First 原则（显式、枚举、自描述） | CR 审查 |

---

## 5. 已有子模块 AGENTS.md

以下子模块已有独立 AGENTS.md，本文档不重复其内容：

- `features/camera/AGENTS.md` — 相机模块实现规范
- `features/gallery/AGENTS.md` — 相册模块实现规范
- `features/common/chat/AGENTS.md` — Chat UI 共享组件规范（Camera/Gallery 复用）
- `features/editor/AGENTS.md` — 图片编辑模块
- `features/settings/AGENTS.md` — 设置模块
- `features/debug/AGENTS.md` — 调试工具模块
- `core/AGENTS.md` — 核心工具
- `core/designsystem/AGENTS.md` — 设计系统
- `data/AGENTS.md` — 数据层
- `di/AGENTS.md` — 依赖注入
- `domain/agent/capability/AGENTS.md` — Agent Capability 实现

---

## 6. 常见变更检查清单

- [ ] 新增 Feature 页面已注册到 `Screen.kt` 和 `MainActivity.kt` NavHost
- [ ] 新增数据表已更新 `AppDatabase.kt` + DAO + 版本迁移
- [ ] 新增依赖已通过 `libs.versions.toml` 管理
- [ ] UI 字符串已三语同步
- [ ] 日志标签遵循 `PoLang:[FeatureName]` 格式
- [ ] 不跨层引用：features 不直接引用 data 实现类
- [ ] 跨模块调用使用接口（`beauty-api` / `agent-core` 公开 API）

---

> **维护者**：[RD] 全栈工程师
> **最后更新**：2026-07-25
> **状态**：生效中
