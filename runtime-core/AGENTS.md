# :runtime-core 模块

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:runtime-core` 模块的实现细节。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。

## 模块定位

`:runtime-core` 是 **PicMe Agent Runtime 核心模块**，为 Android Library（`com.android.library` + `kotlin-compose` 插件），承载 Agent 编排、本地/远程推理管道、Capability 注册、隐私策略、对话记忆、场景管理等能力。

**插件类型**：`com.android.library` + `org.jetbrains.kotlin.plugin.compose`

**语言**：Kotlin

**版本**：1.0
**最后更新**：2026-07-15
**状态**：生效中

**关键职责**：
- `AgentOrchestrator`（`facade`）：应用级 Agent 入口，管理本地/远程两条推理链路
- `CapabilityRegistry`（`runtime.capability`）：Capability 注册、查询、命令分发
- `PrivacyGuard`（`runtime.policy`）：输入内容隐私分级与本地优先策略
- `MemoryManager` / `DataStoreChatMemoryStore`（`platform.storage`）：对话历史管理
- `SceneManager`（`runtime.state`）：页面场景状态管理
- `LocalInferencePipeline`（`inference.local.pipeline`）：本地推理管道
- `RemoteReActAgent`（`inference.remote.react`）：远程 ReAct 推理管道
- 语音交互（`platform.voice`：Sherpa-ONNX ASR / Keyword Spotter）
- 本地 MNN LLM 推理 JNI（`libagent_native.so`）

## 依赖方向

```
:runtime-core
    ├── :agent-core (api)
    ├── :beauty-api
    ├── :mnn-core
    └── Sherpa-ONNX AAR (compileOnly)
```

> 注意：`:runtime-core` **不**应被 `:beauty-engine` 依赖。MNN 资源管理已下沉到独立模块 `:mnn-core`，`:beauty-engine` 通过 `:mnn-core` 共享 MNN 资源。

## 核心组件位置

所有 Agent Runtime 组件位于 `runtime-core/src/main/java/com/mamba/picme/agent/core/` 下。

### 核心组件与文件分布（61 个文件，9 个一级子包）

| 组件 | 职责 | 包路径 |
|------|------|--------|
| `AgentOrchestrator` | 应用级单例，统一入口，管理本地/远程两条独立推理链路 | `agent.core.facade` |
| `AgentConfigurator` | Agent 配置管理（Local/Remote 实例与运行模式） | `agent.core.facade` |
| `CapabilityRegistry` | Capability 注册/查询/命令分发，跨页面命令队列 | `agent.core.runtime.capability` |
| `CommandExecutor` | 命令执行器（超时+异常） | `agent.core.runtime.capability` |
| `CrossPageCommandQueue` | 跨页面命令队列（TTL+重试） | `agent.core.runtime.capability` |
| `Capability<T,C,P,A>` | 泛型 Capability 接口 | `agent.core.capability` |
| `CapabilityHost` | Capability 宿主绑定 | `agent.core.capability` |
| `FaceDetectionProvider` | 人脸检测结果提供 | `agent.core.capability` |
| `PrivacyGuard` | 输入内容隐私分级 | `agent.core.runtime.policy` |
| `MemoryManager` | 对话历史管理 | `agent.core.platform.storage` |
| `DataStoreChatMemoryStore` | DataStore 持久化的 ChatMemory 存储 | `agent.core.platform.storage` |
| `SceneManager` | 页面场景状态管理 | `agent.core.runtime.state` |
| `LocalInferencePipeline` | 本地推理链路：L1 Cache + L2 Batch（自定义 JSON 数组协议） | `agent.core.inference.local.pipeline` |
| `LocalPromptBuilder` | 本地模型 System prompt 构建（精简结构化） | `agent.core.inference.local.prompt` |
| `LocalCommandParser` | LLM 响应解析为 AgentCommand | `agent.core.inference.local.parser` |
| `LocalLlmEngine` | 本地 Qwen3.5-2B MNN-LLM 推理封装 | `agent.core.inference.local.llm` |
| `LlmModelManager` | 本地 LLM 模型管理 | `agent.core.inference.local.llm` |
| `MnnLlmClient` | MNN LLM 客户端 | `agent.core.inference.local.llm` |
| `RemoteReActAgent` | 远程 ReAct Agent（标准 OpenAI Chat Completions + tool_calls） | `agent.core.inference.remote.react` |
| `RemoteReActAgentConfig` | ReAct 配置 | `agent.core.inference.remote.react` |
| `RemotePromptBuilder` | 远程模型 Tool Schema + ChatRequest 构建 | `agent.core.inference.remote.prompt` |
| `ToolCallCommandParser` | tool_calls 命令解析器（name + arguments → AgentCommand） | `agent.core.inference.remote.parser` |
| `PicMeToolService` | 远程推理 @Tool 注解工具集 | `agent.core.inference.remote.tool` |
| `RemoteModelConfig` / `RemoteModelFactory` | 远程模型配置与工厂 | `agent.core.remote.config` |
| `Logger` | 日志接口 | `agent.core.platform.logging` |
| `ThreadPoolManager` | 线程池管理 | `agent.core.platform.thread` |
| `MnnResourceManager` / `MnnGlobalReleaseLock` | MNN 资源管理 | `:mnn-core`（已下沉） |
| `ExecutionEngine` / `ExecutionReporter` / `ExecutionState` / `InferenceResult` | 执行引擎与执行状态 | `agent.core.runtime.execution` |
| `AgentCommands` / `AgentModels` / `AiAgentConfig` / `MediaAsset` / `PageContext` / `SceneContext` / `ExecutionPlan` | 数据模型 | `agent.core.model.*` |
| `AsrEngine` / `AudioRecorder` / `VadDetector` / `SherpaOnnxAsrEngine` / `KeywordSpotterEngine` | 语音交互（Sherpa-ONNX） | `agent.core.platform.voice` |
| `LlmChatLanguageModel` / `StreamingLlmChatLanguageModel` 等 | LangChain4j 风格本地对话模型接口 | `agent.core.local.llm` |

### 子包

| 子包 | 内容 | 说明 |
|------|------|------|
| `capability/` | `Capability`, `CapabilityHost`, `FaceDetectionProvider` | 泛型 Capability 接口与宿主绑定 |
| `facade/` | `AgentOrchestrator`, `AgentConfigurator` | 应用级入口与配置 |
| `inference/` | `local/...`, `remote/...` | 本地/远程推理管道（pipeline、llm、parser、prompt、react、tool） |
| `local/` | `llm/ChatModel`, `StreamingChatModel`, `ChatMessage`, `ChatRequest`, `ChatResponse`, 等 | 与 LangChain4j API 对齐的自定义纯 Kotlin 模型层（为本地/远程推理提供标准化接口） |
| `model/` | `command/`, `config/`, `context/`, `plan/` | 数据模型 |
| `platform/` | `logging/`, `storage/`, `thread/`, `voice/` | 平台能力：日志、存储、线程、语音 |
| `remote/` | `config/` | 远程模型配置与工厂 |
| `runtime/` | `cache/`, `capability/`, `execution/`, `policy/`, `state/` | 运行时能力：缓存、Capability、执行、隐私策略、场景状态 |
| `tool/` | `accessibility/`, `perception/`, `CameraToolHelper` | Agent 工具与辅助功能 |

> **2026-06-15 架构更新（ADR-005）**：
> - 移除 `InferenceRouter`（拆分为 `LocalInferencePipeline` + `RemoteReActAgent`）
> - 移除 `ToolCallingChatLanguageModel`、`ToolCallingOutputParser`、`ToolPromptBuilder`、`ToolCallingMode`、`ToolCallingConfig`（远程直用 OpenAI 原生协议，无需 Prompt 注入模拟）
> - 移除 `AdaptiveStrategySelector`（本地不再需要策略分级）
> - 拆分 `PromptBuilder` 为 `LocalPromptBuilder` + `RemotePromptBuilder`
> - 移除所有不再需要的 Tool Calling 包装层代码，共 ~1500 行
>
> **2026-06-19 DeepSeek tool_calls 适配**：
> - `RemotePromptBuilder`：移除 Prompt 中的具体 tool_calls JSON 示例，避免模型将 JSON 输出到 content 字段
> - `RemoteReActAgent`：
>   - DeepSeek 模型自动禁用 thinking 模式（`thinking: {"type": "disabled"}`）
>   - ToolSpec 自动添加 `additionalProperties: false` 以兼容 strict 模式
>   - `tool_choice: REQUIRED` 正确映射为 `"required"`（之前错误映射为 `"auto"`）
>   - 增强 content fallback 解析，支持从 content 字段回退提取 tool_calls JSON
> - 空字符串处理统一使用 `isNotBlank()`
> - 远程推理通过 `:agent-core` 消费标准 OpenAI 协议
> - 新增 `ToolCallCommandParser`：标准 tool_calls 解析器
> - 远程推理支持 L2 Batch / L3 Plan / L4 ReAct Chat 分层模式

## 设计原则

- **零业务依赖**：不直接依赖 `BeautySettings`、`FilterType`、`MediaType`、`ExecutionPlan`（业务）等业务类型，通过泛型 `<T, C, P, A>` 让业务模块注入具体类型。
- **Android Library**：使用 `com.android.library` + `kotlin-compose` 插件，允许 Android 相关能力（如 AAR 依赖、JNI），但保持业务无关性。
- **本地优先隐私**：敏感输入优先走本地推理；必须远程时由 `PrivacyGuard` 分级并显式授权。

## 与 App 模块的关系

```
:runtime-core (Agent Runtime 核心)
    ↑ 被依赖
:app (业务实现)
    - AgentCommand 密封类（含 BeautySettings 等）
    - Capability 接口（特化为 AgentCommand/AgentContext/PageContext/AgentAction）
    - CameraCapability / GalleryCapability / SettingsCapability
```

## 文件清单

### `capability/`
- `Capability.kt` — 泛型 Capability 接口
- `CapabilityHost.kt` — Capability 宿主绑定
- `FaceDetectionProvider.kt` — 人脸检测结果提供

### `facade/`
- `AgentConfigurator.kt` — Agent 配置管理（Local/Remote 实例与运行模式）
- `AgentOrchestrator.kt` — 应用级单例编排器

### `inference/local/llm/`
- `LlmGenerationMetrics.kt` — 生成指标
- `LlmModelManager.kt` — 本地 LLM 模型管理
- `LocalLlmEngine.kt` — 本地 LLM 推理引擎
- `MnnLlmClient.kt` — MNN LLM 客户端

### `inference/local/parser/`
- `LocalCommandParser.kt` — 本地命令解析器

### `inference/local/pipeline/`
- `LocalInferencePipeline.kt` — 本地推理链路

### `inference/local/prompt/`
- `LocalPromptBuilder.kt` — 本地 Prompt 构建

### `inference/remote/parser/`
- `ToolCallCommandParser.kt` — tool_calls 命令解析器

### `inference/remote/prompt/`
- `RemotePromptBuilder.kt` — 远程 Prompt / Tool Schema 构建

### `inference/remote/react/`
- `RemoteReActAgent.kt` — 远程 ReAct Agent
- `RemoteReActAgentCallback.kt` — ReAct 回调
- `RemoteReActAgentConfig.kt` — ReAct 配置

### `inference/remote/tool/`
- `PicMeToolService.kt` — 远程推理 @Tool 注解工具集

### `local/llm/`
- `ChatResponseMetadata.kt` — 响应元数据
- `LlmChatLanguageModel.kt` — 同步对话模型接口
- `LlmChatRequest.kt` — 对话请求
- `LlmChatResponse.kt` — 对话响应
- `StreamChatResult.kt` — 流式结果
- `StreamingChatResponseHandler.kt` — 流式响应回调
- `StreamingLlmChatLanguageModel.kt` — 流式对话模型接口

### `model/command/`
- `AgentCommands.kt` — 命令定义

### `model/config/`
- `AiAgentConfig.kt` — Agent 配置数据

### `model/context/`
- `AgentModels.kt` — Agent 模型
- `MediaAsset.kt` — 媒体资产
- `PageContext.kt` — 页面上下文
- `SceneContext.kt` — 场景上下文

### `model/plan/`
- `ExecutionPlan.kt` — 执行计划

### `platform/logging/`
- `Logger.kt` — 日志接口

### `platform/storage/`
- `DataStoreChatMemoryStore.kt` — DataStore ChatMemory 存储
- `MemoryManager.kt` — 记忆管理

### `platform/thread/`
- `ThreadPoolManager.kt` — 线程池管理

### `platform/voice/`
- `AsrEngine.kt` — ASR 引擎接口
- `AudioRecorder.kt` — 音频录制器
- `KeywordSpotterEngine.kt` — ONNX KWS 唤醒词检测
- `SherpaOnnxAsrEngine.kt` — Sherpa-ONNX ASR 引擎（当前主力）
- `VadDetector.kt` — VAD 检测器

### `remote/config/`
- `RemoteModelConfig.kt` — 远程模型配置
- `RemoteModelFactory.kt` — 远程模型工厂

### `runtime/cache/`
- `IntentCache.kt` — 意图缓存
- `L1CacheSettings.kt` — L1 缓存设置

### `runtime/capability/`
- `CapabilityRegistry.kt` — 注册表（应用级单例）
- `CommandExecutor.kt` — 命令执行器
- `CrossPageCommandQueue.kt` — 跨页面命令队列

### `runtime/execution/`
- `ExecutionEngine.kt` — 执行引擎
- `ExecutionReporter.kt` — 执行报告器
- `ExecutionState.kt` — 执行状态
- `InferenceResult.kt` — 推理结果

### `runtime/policy/`
- `PrivacyGuard.kt` — 隐私守卫

### `runtime/state/`
- `SceneManager.kt` — 场景管理器

### `tool/`
- `CameraToolHelper.kt` — 相机工具辅助

### `tool/accessibility/`
- `AccessibilityActionPerformer.kt` — 无障碍动作执行
- `AccessibilityNodeDumper.kt` — 无障碍节点导出
- `AccessibilityServiceHolder.kt` — 无障碍服务持有

### `tool/perception/`
- `UiObservationFormatter.kt` — UI 观察格式化
- `ViewHierarchyExtractor.kt` — 视图层级提取

> **已移除（ADR-005 + 2026-06 清理）**：
> - `InferenceRouter.kt`, `AdaptiveStrategySelector.kt`, `ToolCallingChatLanguageModel.kt`
> - `ToolCallingOutputParser.kt`, `ToolPromptBuilder.kt`, `ToolCallingConfig.kt`, `ToolCallingMode.kt`, `ToolOrchestrator.kt`
> - `UnifiedRemoteClient.kt`, `LangChain4jOpenAiClient.kt`, `RemoteCameraTools.kt`（功能已整合入 `RemoteReActAgent` + `PicMeToolService`）
> - `SherpaMnnAsrEngine.kt`, `MnnAsrClient.kt`, `com.k2fsa.sherpa.mnn.*`（已迁移至 Sherpa-ONNX）
> - `ToolCallParser.kt`（合并入 `ToolCallCommandParser.kt`）
> - 累计清理 ~2,600 行冗余代码

## 编译验证

```bash
./gradlew :runtime-core:assembleDebug
```

## 相关 ADR

- `docs/02-ARCHITECTURE/ADR/ADR-005-local-remote-inference-split.md` — 本地/远程推理协议分离（移除包装层 · 清除冗余代码 · 产品重心迁移）
