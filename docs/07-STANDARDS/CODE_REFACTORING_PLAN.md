# PoLang 代码拆分与架构重构计划

> ⚠️ **历史方案（2026-08-03 标注；2026-08-16 更新）**：本文将 Agent Runtime 归于 `:agent-core`（`com.picme`）的表述已被取代——Agent Runtime 现位于 `:shared` KMP 模块（`shared/src/commonMain/kotlin/com/mamba/picme/agent/core/`）；`:runtime-core`（已并入 `:shared`）与 `:agent-core`（langchain4j 适配层，已被 Koog 取代）均已删除。本文仅作历史参考。

> 本文档基于 2026-06-07 的代码扫描结果，识别项目中超过 1000 行的大文件，分析架构问题，并给出拆分建议。
> 重点关注 Agent 模块的独立库提取可行性。

---

## 一、超大文件扫描结果（>1000 行）

| 排名 | 文件路径 | 行数 | 模块 | 严重度 |
|------|----------|------|------|--------|
| 1 | `beauty-engine/.../BeautyRenderer.kt` | 1687 | 美颜引擎 | 🔴 高 |
| 2 | `app/.../camera/CameraScreen.kt` | 1505 | 相机页面 | 🔴 高 |
| 3 | `app/.../download/LlmModelDownloadManager.kt` | 1326 | 模型下载 | 🔴 高 |
| 4 | `app/.../camera/CameraDebugOverlay.kt` | 1148 | 相机调试 | 🟡 中 |

### 500~1000 行文件（需关注）

| 文件路径 | 行数 | 模块 | 严重度 |
|----------|------|------|--------|
| `app/.../gallery/components/MediaPager.kt` | 991 | 图库 | 🟡 中 |
| `app/.../common/chat/AiChatScreen.kt` | 987 | 聊天 UI | 🟡 中 |
| `app/.../core/image/GpuBeautyProcessor.kt` | 962 | 图像处理 | 🟡 中 |
| `app/.../core/image/ImageProcessor.kt` | 944 | 图像处理 | 🟡 中 |
| `app/.../preferences/UserPreferencesRepository.kt` | 867 | 数据层 | 🟡 中 |
| `app/.../agent/CapabilityRegistry.kt` | 768 | Agent 核心 | 🟡 中 |
| `app/.../settings/LlmModelManagerScreen.kt` | 756 | 设置 UI | 🟡 中 |
| `app/.../settings/SettingsViewModel.kt` | 749 | 设置 VM | 🟡 中 |
| `app/.../camera/CameraPreviewContent.kt` | 683 | 相机 UI | 🟢 低 |
| `app/.../settings/SettingsScreen.kt` | 681 | 设置 UI | 🟢 低 |
| `app/.../camera/components/CameraOverlays.kt` | 674 | 相机 UI | 🟢 低 |
| `app/.../usecase/AiAgentUseCase.kt` | 618 | Agent 用例 | 🟢 低 |
| `app/.../agent/AgentOrchestrator.kt` | 601 | Agent 核心 | 🟢 低 |
| `app/.../agent/AgentCommandParser.kt` | 596 | Agent 核心 | 🟢 低 |
| `app/.../debug/SampleDataGenerator.kt` | 594 | 调试工具 | 🟢 低 |
| `app/.../settings/SettingsAiAgent.kt` | 526 | 设置 UI | 🟢 低 |
| `app/.../common/chat/AgentChatComponents.kt` | 514 | 聊天 UI | 🟢 低 |
| `app/.../testing/agent/bridge/AgentTestBroadcastReceiver.kt` | 532 | 测试框架 | 🟢 低 |
| `app/.../testing/agent/engine/AgentTestEngine.kt` | 506 | 测试框架 | 🟢 低 |

---

## 二、逐文件拆分建议

### 2.1 BeautyRenderer.kt（1687 行）- beauty-engine 模块

**问题分析：**
- 职责混杂：同时管理参数状态、多 Pass 渲染管线、Shader uniform 设置、错误处理、拍照离屏渲染
- 包含 4 个渲染 Pass 的逻辑（CopyPass / BeautyUnitPass / FaceMakeupPass / StyleEffectPass）
- 两种渲染路径（预览 onRender / 拍照 renderBeautyMultiPass）耦合在一起
- 50+ 个 uniform location 字段堆积

**拆分方案：**

```
beauty-engine/src/main/java/com/picme/beauty/render/
├── BeautyRenderer.kt              # 保留：主入口 + 状态管理（~400 行）
├── pass/
│   ├── BeautyPassPipeline.kt      # 新增：多 Pass 管线编排（~350 行）
│   ├── CopyPassExecutor.kt        # 新增：Pass 0 执行器（~80 行）
│   ├── BeautyUnitPassExecutor.kt  # 新增：Pass 1 执行器（~120 行）
│   └── FaceMakeupPassExecutor.kt  # 新增：Pass 4 执行器（~200 行）
├── shader/
│   └── UniformBinder.kt           # 新增：uniform 绑定辅助类（~150 行）
└── photo/
    └── PhotoRenderPipeline.kt     # 新增：拍照离屏渲染（~300 行）
```

**预期收益：**
- 每个文件控制在 400 行以内
- 渲染 Pass 可独立单元测试
- 新增 Pass 时无需修改主 Renderer

---

### 2.2 CameraScreen.kt（1505 行）- app 模块

**问题分析：**
- 典型的 "God Composable"：包含权限管理、相机绑定、AI Agent 初始化、语音协调器、状态机、传感器监听、内存持久化等
- `CameraContent` 内部声明了 50+ 个 `remember` 状态
- AI Agent 相关逻辑（~400 行）与相机核心逻辑耦合
- 命令处理（BatchExecute / Delay）直接写在 Composable 中

**拆分方案：**

```
app/src/main/java/com/picme/features/camera/
├── CameraScreen.kt                # 保留：权限壳 + 路由（~150 行）
├── CameraContent.kt               # 已存在，需进一步瘦身
├── state/
│   └── CameraStateHolder.kt       # 新增：提取所有 remember 状态（~300 行）
├── agent/
│   ├── CameraAgentInitializer.kt  # 新增：AI Agent / UseCase / 语音初始化（~250 行）
│   └── CameraAgentCommandExecutor.kt # 新增：BatchExecute/Delay 执行逻辑（~120 行）
├── memory/
│   └── CameraMemoryManager.kt     # 新增：DataStore 读写 + 状态恢复（~150 行）
└── sensor/
    └── CameraSensorManager.kt     # 新增：加速度计监听 + 稳定状态（~80 行）
```

**关键重构动作：**
1. 将 `CameraContent` 中的状态提取为 `CameraStateHolder`（`remember` 聚合类）
2. AI Agent 初始化逻辑（`aiAgentUseCase` / `voiceCoordinator` / `asrEngine`）移至 `CameraAgentInitializer`
3. `CameraAgentCommandHandler` 已独立，但 Composable 中的命令执行逻辑仍需进一步剥离

---

### 2.3 LlmModelDownloadManager.kt（1326 行）- app 模块

**问题分析：**
- 混合了多种职责：模型市场数据获取、本地模型配置解析、下载任务管理、断点续传、文件校验、Service 状态同步
- 包含 8 组硬编码的文件列表常量（LLM/ASR/TTS/人脸检测等）
- 下载逻辑（`downloadModel` / `resumeDownload`）存在大量重复代码

**拆分方案：**

```
app/src/main/java/com/picme/data/download/
├── LlmModelDownloadManager.kt     # 保留：对外 API + 任务调度（~350 行）
├── model/
│   ├── ModelFileRegistry.kt       # 新增：文件列表常量 + 模型类型判断（~150 行）
│   └── ModelMarketDataSource.kt   # 新增：市场数据获取 + 缓存（~200 行）
├── download/
│   ├── DownloadEngine.kt          # 新增：核心下载逻辑（~250 行）
│   └── ResumeDownloadEngine.kt    # 新增：断点续传逻辑（~200 行）
└── service/
    └── DownloadServiceBridge.kt   # 新增：ForegroundService 状态同步（~80 行）
```

---

### 2.4 CameraDebugOverlay.kt（1148 行）- app 模块

**问题分析：**
- 纯 UI 绘制文件，包含 4 种完全不同的绘制逻辑：
  - 人脸关键点 overlay（106 点）
  - ROI 矩形框
  - 瘦脸控制点 + 方向箭头
  - 腮红椭圆区域
- 大量数学计算（椭圆、三角函数、几何变换）与 Compose Canvas 绘制混在一起

**拆分方案：**

```
app/src/main/java/com/picme/features/camera/debug/
├── CameraDebugOverlay.kt          # 保留：入口分发（~150 行）
├── draw/
│   ├── FaceLandmarkDrawer.kt      # 新增：106 点绘制（~200 行）
│   ├── RoiRectDrawer.kt           # 新增：ROI 矩形 + 角标（~120 行）
│   ├── ThinFaceDebugDrawer.kt     # 新增：瘦脸控制点（~250 行）
│   ├── BigEyeDebugDrawer.kt       # 新增：大眼控制点（~150 行）
│   └── BlushDebugDrawer.kt        # 新增：腮红椭圆（~200 行）
└── math/
    └── FaceGeometryUtils.kt       # 新增：几何计算工具（~100 行）
```

---

## 三、Agent 模块深度分析与独立库提取方案

### 3.1 当前 Agent 模块结构

```
app/src/main/java/com/picme/domain/agent/           # ~~~7939 行~~~ → 已迁移至 :agent-core（~5000 行），当前仅剩 Facade/AiAgentUseCase/CapabilityRegistry 等桥接层
agent-core/src/main/java/com/picme/agent/core/        # Agent Runtime 核心（从 domain/agent/ 迁移）
├── AgentCommandParser.kt       # 596 行 - 命令解析
├── AgentOrchestrator.kt        # 601 行 - 编排器（单例）
├── CapabilityRegistry.kt       # 768 行 - 能力注册表 + 队列
├── ExecutionEngine.kt          # 401 行 - 执行引擎
├── InferenceRouter.kt          # 153 行 - 推理路由
├── LocalLlmEngine.kt           # 391 行 - 本地 LLM 引擎
├── MemoryManager.kt            # 252 行 - 对话记忆
├── MnnResourceManager.kt       # 317 行 - MNN 资源管理
├── PrivacyGuard.kt             # 112 行 - 隐私守卫
├── PromptBuilder.kt            # 323 行 - 提示词构建
├── CapabilityHost.kt           # 172 行 - 能力宿主
├── model/                      # 数据模型
│   ├── AgentCommands.kt        # 345 行
│   ├── AgentModels.kt          # 280 行
│   ├── SceneManager.kt         # 150 行
│   └── ...
├── capability/                 # 能力实现
│   ├── Capability.kt           # 121 行
│   ├── CameraCapability.kt     # 286 行
│   ├── GalleryCapability.kt    # 278 行
│   ├── SettingsCapability.kt   # 248 行
│   └── NavigationCapability.kt # 172 行
└── remote/                     # 远程推理
    ├── RemoteOrchestrator.kt   # 481 行
    ├── RemoteInferenceEngine.kt # 392 行
    ├── IntentCache.kt          # 287 行
    └── ...
```

### 3.2 架构问题诊断

| 问题 | 影响 | 优先级 |
|------|------|--------|
| **CapabilityRegistry 过大**（768 行） | 注册表 + 队列管理 + 命令分发 + 重试逻辑全部耦合 | P0 |
| **AgentOrchestrator 职责过重** | 同时管理模型生命周期、推理路由、对话历史、场景策略 | P0 |
| **remote/ 包与本地引擎耦合** | `AgentOrchestrator` 直接依赖 `LocalLlmEngine` 和 `RemoteOrchestrator` | P1 |
| **Capability 实现散落在 app 模块** | Camera/Gallery/Settings Capability 在 domain 层却依赖 features 层 | P1 |
| **PromptBuilder 与业务耦合** | 提示词构建硬编码了相机/美颜等业务概念 | P2 |
| **MnnResourceManager 在 Agent 包** | 资源管理是通用基础设施，不应属于 Agent 域 | P2 |

### 3.3 `:agent-core` 模块定位与边界

**定位：** `:agent-core` 是一个**内聚的 Agent 运行时模块**，不是过度抽象的框架，而是 PoLang Agent 系统的核心载体。

**设计原则：**
- **不为了抽象而抽象**：直接使用具体类型（`AgentCommand`、`AgentContext` 等），而非泛型接口
- **允许业务依赖**：可以依赖 `beauty-engine`，可以定义相机/美颜相关的数据结构
- **允许平台依赖**：可以包含 MNN-LLM、Android 导航等特定平台内容
- **代码简单明了**：优先可读性和可维护性，而非极致解耦

**模块边界：**

```
:agent-core                    # android-library，依赖 beauty-engine
├── Capability.kt              # Capability 接口 + BaseCapability 基类
├── CommandExecutor.kt         # 命令执行器（超时 + 异常处理）
├── CrossPageCommandQueue.kt   # 跨页面命令队列（TTL + 重试）
├── SceneManager.kt            # 场景管理单例
├── AgentLogger.kt             # 日志抽象接口
├── model/                     # Agent 核心数据模型
│   ├── AgentCommands.kt       # AgentCommand 密封类
│   ├── AgentModels.kt         # AgentContext / AgentAction / PageContext
│   ├── ExecutionState.kt      # 执行状态
│   ├── InferenceResult.kt     # 推理结果
│   ├── MediaAsset.kt          # 媒体资源模型
│   ├── PageContext.kt         # 页面上下文
│   └── SceneContext.kt        # 场景上下文
└── remote/                    # 远程推理相关
    └── ExecutionPlan.kt       # 执行计划
```

**已迁移文件（从 app 模块下沉）：**

| 文件 | 原位置 | 新位置 | 说明 |
|------|--------|--------|------|
| `Capability.kt` | `app/.../domain/agent/capability/` | `:agent-core` | 接口 + BaseCapability |
| `CommandExecutor.kt` | `app/.../domain/agent/executor/` | `:agent-core` | 命令执行器 |
| `CrossPageCommandQueue.kt` | `app/.../domain/agent/queue/` | `:agent-core` | 跨页面队列 |
| `AgentCommands.kt` | `app/.../domain/agent/model/` | `:agent-core/model/` | 命令密封类 |
| `AgentModels.kt` | `app/.../domain/agent/model/` | `:agent-core/model/` | 核心模型 |
| `ExecutionState.kt` | `app/.../domain/agent/model/` | `:agent-core/model/` | 执行状态 |
| `InferenceResult.kt` | `app/.../domain/agent/model/` | `:agent-core/model/` | 推理结果 |
| `MediaAsset.kt` | `app/.../domain/model/` | `:agent-core/model/` | 媒体资源 |
| `PageContext.kt` | `app/.../domain/agent/model/` | `:agent-core/model/` | 页面上下文 |
| `SceneContext.kt` | `app/.../domain/agent/model/` | `:agent-core/model/` | 场景上下文 |
| `ExecutionPlan.kt` | `app/.../domain/agent/remote/` | `:agent-core/remote/` | 执行计划 |

**留在 app 模块的文件：**

| 文件 | 位置 | 说明 |
|------|------|------|
> **迁移状态（2026-06 审计）**：以上迁移均已落地。`Capability.kt`, `CommandExecutor.kt`, `CrossPageCommandQueue.kt`, `AgentCommands.kt`, `AgentModels.kt`, `ExecutionState.kt`, `InferenceResult.kt`, `MediaAsset.kt`, `PageContext.kt`, `SceneContext.kt`, `ExecutionPlan.kt` 均已在 `agent-core/` 中。`CapabilityRegistry.kt`, `AgentOrchestrator.kt`, `AgentConfigurator.kt` 仍在 app/ 中（Facade/策略路由层）。

| `CapabilityRegistry.kt` | `app/.../domain/agent/` | 使用 `:agent-core` 的 `CommandExecutor` + `CrossPageCommandQueue` |
| `AgentOrchestrator.kt` | `app/.../domain/agent/` | 使用 `:agent-core` 的模型和接口 |
| `AgentConfigurator.kt` | `app/.../domain/agent/` | 平台特定组件配置 |
| `CameraCapability.kt` | `app/.../features/camera/capability/` | 业务 Capability |
| `GalleryCapability.kt` | `app/.../features/gallery/capability/` | 业务 Capability |
| `SettingsCapability.kt` | `app/.../features/settings/capability/` | 业务 Capability |
| `NavigationCapability.kt` | `app/.../domain/agent/capability/` | 通用导航 Capability |

**依赖关系：**

```
:app
├── :agent-core                 # Agent 运行时核心（已包含模型 + 基础设施）
├── :beauty-engine              # 美颜引擎（已有）
└── app-features/
    ├── camera/                 # CameraCapability + 相机页面
    ├── gallery/                # GalleryCapability + 图库页面
    └── settings/               # SettingsCapability + 设置页面
```

---

## 四、其他值得关注的问题

### 4.1 包结构不合理

| 问题 | 当前状态 | 建议 |
|------|----------|------|
| `domain.agent.capability` 包含 UI 相关 Capability | CameraCapability 在 domain 层却操作相机状态 | 将业务 Capability 下放到 `features.xxx.agent` |
| `features.common.chat` 被多处引用 | AiChatScreen 在 common 包，但包含语音/ASR 初始化逻辑 | 拆分为 `ui-chat`（纯 UI）和 `chat-integration`（业务绑定） |
| `data.preferences` 过大 | UserPreferencesRepository 867 行，管理 40+ 个 DataStore Key | 按领域拆分为 `CameraPreferences`、`AgentPreferences`、`AppearancePreferences` |
| `testing.agent` 与生产代码混置 | 测试框架在生产源码目录 | 考虑拆分为 `:testing` 独立模块 |

### 4.2 重复代码

| 位置 | 重复内容 | 建议 |
|------|----------|------|
| `CameraScreen.kt` + `AgentChatComponents.kt` | ASR 引擎初始化逻辑（~60 行）几乎相同 | 提取到 `AsrEngineFactory` |
| `ImageProcessor.kt` + `GpuBeautyProcessor.kt` | `createFaceMaskBitmap` 方法重复 | 提取到通用工具类 |
| `AgentOrchestrator.kt` + `AiAgentUseCase.kt` | 都包含远程/本地推理路由逻辑 | `AiAgentUseCase` 应完全委托给 `AgentOrchestrator`，删除重复逻辑 |

---

## 五、实施优先级与阶段规划

### Phase 1：Agent 核心重构（已完成 ✅）

| 任务 | 文件 | 状态 | 说明 |
|------|------|------|------|
| 拆分 CapabilityRegistry | `CapabilityRegistry.kt` | ✅ | 拆出 `CommandExecutor` + `CrossPageCommandQueue`，注册表降至 470 行 |
| 拆分 AgentOrchestrator | `AgentOrchestrator.kt` | ✅ | 拆出 `AgentConfigurator`，编排器降至 515 行 |
| Capability 位置调整 | `capability/*.kt` | ✅ | Camera/Gallery/Settings 移至 `features.xxx.capability` |
| 创建 `:agent-core` 模块 | 新建模块 | ✅ | android-library，依赖 beauty-engine，已迁移 11 个文件 |

**Phase 1 关键成果：**
- `:agent-core` 模块已激活，包含 Agent 核心运行时（接口 + 模型 + 基础设施）
- `Capability` 接口统一在 `:agent-core`，app 模块直接复用
- `CommandExecutor` + `CrossPageCommandQueue` 统一在 `:agent-core`，删除 app 重复实现
- 业务 Capability（Camera/Gallery/Settings）已下放到 `features` 层
- 所有模块编译通过

### Phase 2：大文件拆分

| 任务 | 文件 | 预计工作量 | 风险 |
|------|------|------------|------|
| 拆分 BeautyRenderer | `BeautyRenderer.kt` | 3d | 高：渲染管线复杂，需视觉回归测试 |
| 拆分 CameraScreen | `CameraScreen.kt` | 2d | 中：状态管理重构 |
| 拆分 LlmModelDownloadManager | `LlmModelDownloadManager.kt` | 2d | 低：逻辑清晰 |
| 拆分 CameraDebugOverlay | `CameraDebugOverlay.kt` | 1d | 低：纯 UI 拆分 |

### Phase 3：架构清理

| 任务 | 范围 | 预计工作量 | 风险 |
|------|------|------------|------|
| 拆分 UserPreferencesRepository | `data/preferences/` | 2d | 中：DataStore Key 分散 |
| 提取 ASR 工厂 | `CameraScreen.kt` / `AgentChatComponents.kt` | 0.5d | 低 |
| 合并 ImageProcessor 重复代码 | `core/image/` | 1d | 中：需测试拍照路径 |
| 测试框架独立模块 | `testing/` | 1d | 低 |

---

## 六、验收标准

- [ ] 所有文件行数控制在 500 行以内（除数据模型/常量定义文件）
- [ ] `:agent-core` 模块可独立编译，不依赖 `:app` 任何代码
- [ ] `:agent-core` 单元测试覆盖率 > 60%
- [ ] 原有功能零回归（通过 Agent Test V2 自动化测试验证）
- [ ] 文档同步更新（AGENTS.md / 模块 AGENTS.md）

---

> **维护者**：项目开发者
> **最后更新**：2026-06-07
> **状态**：Phase 1 已完成，Phase 2 待启动
