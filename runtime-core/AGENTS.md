# :runtime-core 模块

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:runtime-core` 模块的实现细节。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。

## 模块定位

`:runtime-core` 是 **PoLang Agent Runtime 核心模块**，为 Android Library（`com.android.library` + `kotlin-compose` 插件），承载 Agent 编排、远程推理管道、Capability 注册、隐私策略、对话记忆、场景管理、端侧 VLM 打标等能力。

**插件类型**：`com.android.library` + `org.jetbrains.kotlin.plugin.compose`

**语言**：Kotlin

**版本**：1.0
**最后更新**：2026-08-02
**状态**：生效中

**关键职责**：
- `AgentOrchestrator`（`facade`）：应用级 Agent 入口，管理远程推理链路（chat / 相机 / 飞书）与端侧 VLM 生命周期
- `CapabilityRegistry`（`runtime.capability`）：Capability 注册、查询、命令分发
- `PrivacyGuard`（`runtime.policy`）：输入内容隐私分级与远程调用授权策略
- `MemoryManager` / `KoogMessageMemoryStore`（`platform.storage`）：对话历史管理（Koog 版持久化）
- `SceneManager`（`runtime.state`）：页面场景状态管理
- `KoogChatAgent` / `KoogReActAgent`（`inference.remote.koog`）：远程 ReAct 推理管道（Koog 驱动）
- `LocalLlmEngine`（`inference.local.llm`）：端侧 VLM 引擎（TAG 打标 / 图像理解专用）
- 语音交互（`platform.voice`：Sherpa-ONNX ASR / Keyword Spotter）
- 端侧 VLM 推理 JNI（`libagent_native.so`）

## 依赖方向

```
:runtime-core
    ├── ai.koog:koog-agents (api，外部依赖；替代已删除的 :agent-core fork)
    ├── :engines:beauty-api
    ├── :engines:mnn-core
    └── Sherpa-ONNX AAR (compileOnly)
```

> 注意：`:runtime-core` **不**应被 `:engines:beauty-engine` 依赖。MNN 资源管理已下沉到独立模块 `:engines:mnn-core`，`:engines:beauty-engine` 通过 `:engines:mnn-core` 共享 MNN 资源。

## 核心组件位置

所有 Agent Runtime 组件位于 `runtime-core/src/main/java/com/mamba/picme/agent/core/` 下。

### 核心组件与文件分布（78 个文件，9 个一级子包）

| 组件 | 职责 | 包路径 |
|------|------|--------|
| `AgentOrchestrator` | 应用级单例，统一入口：远程推理链路（chat / 相机 / 飞书）+ 端侧 VLM 服务 | `agent.core.facade` |
| `AgentConfigurator` | Agent 配置管理（远程配置、运行模式、VLM 模型参数） | `agent.core.facade` |
| `CapabilityRegistry` | Capability 注册/查询/命令分发，跨页面命令队列 | `agent.core.runtime.capability` |
| `CommandExecutor` | 命令执行器（超时+异常） | `agent.core.runtime.capability` |
| `CrossPageCommandQueue` | 跨页面命令队列（TTL+重试） | `agent.core.runtime.capability` |
| `Capability<T,C,P,A>` | 泛型 Capability 接口 | `agent.core.capability` |
| `FaceDetectionProvider` | 人脸检测结果提供 | `agent.core.capability` |
| `PrivacyGuard` | 输入内容隐私分级 | `agent.core.runtime.policy` |
| `MemoryManager` | 对话历史管理 | `agent.core.platform.storage` |
| `DataStoreChatMemoryStore` | DataStore 持久化的 ChatMemory 存储 | `agent.core.platform.storage` |
| `SceneManager` | 页面场景状态管理 | `agent.core.runtime.state` |
| `LocalLlmEngine` | 端侧 VLM 引擎（**TAG 打标 / 图像理解专用**：`imageInference`/`imageInferenceWithTimeout` + 模型生命周期；文本 chat 面已移除） | `agent.core.inference.local.llm` |
| `LocalModelService` | 端侧 VLM 模型加载服务（打标 Worker / 图像理解经 `getLlmEngine()` 取引擎） | `agent.core.inference.local` |
| `LlmModelManager` | 端侧 VLM 模型管理 | `agent.core.inference.local.llm` |
| `MnnLlmClient` | MNN LLM 客户端（VLM 打标 JNI 桥） | `agent.core.inference.local.llm` |
| `RemoteReActAgentConfig` | ReAct 配置（三链路共用） | `agent.core.inference.remote.react` |
| `RemotePromptBuilder` | 远程模型 Tool Schema + ChatRequest 构建 | `agent.core.inference.remote.prompt` |
| `KoogChatAgent` | chat 链路 Koog Agent（Phase 4） | `agent.core.inference.remote.koog` |
| `KoogReActAgent` | 相机/飞书链路 Koog Agent（Phase 5，回调式 API 对齐旧 RemoteReActAgent） | `agent.core.inference.remote.koog` |
| `ChatToolService` | chat 场域 @Tool 工具集（scene=CHAT） | `agent.core.inference.remote.tool` |
| `CameraToolService` | 相机场域 @Tool 工具集（scene=CAMERA，远程 tool_calls） | `agent.core.inference.remote.tool` |
| `RemoteControlToolService` | IM 远程控制 RPA @Tool 工具集 | `agent.core.inference.remote.tool` |
| `RemoteModelConfig` / `RemoteModelFactory` | 远程模型配置与工厂 | `agent.core.remote.config` |
| `Logger` | 日志接口 | `agent.core.platform.logging` |
| `ThreadPoolManager` | 线程池管理 | `agent.core.platform.thread` |
| `MnnResourceManager` / `MnnGlobalReleaseLock` | MNN 资源管理 | `:engines:mnn-core`（已下沉） |
| `ExecutionEngine` / `ExecutionReporter` / `ExecutionState` / `InferenceResult` | 执行引擎与执行状态 | `agent.core.runtime.execution` |
| `AgentCommands` / `AgentModels` / `AiAgentConfig` / `MediaAsset` / `PageContext` / `SceneContext` / `ExecutionPlan` | 数据模型 | `agent.core.model.*` |
| `SearchIntent` / `TimeRange` | 搜索意图标准化模型（LLM 输出 → 本地结构化过滤） | `agent.core.model.context` |
| `AsrEngine` / `AudioRecorder` / `VadDetector` / `SherpaOnnxAsrEngine` / `KeywordSpotterEngine` | 语音交互（Sherpa-ONNX） | `agent.core.platform.voice` |

### 子包

| 子包 | 内容 | 说明 |
|------|------|------|
| `capability/` | `Capability`, `FaceDetectionProvider` | 泛型 Capability 接口 |
| `facade/` | `AgentOrchestrator`, `AgentConfigurator` | 应用级入口与配置 |
| `inference/` | `local/...`, `remote/...` | 端侧 VLM（`local/llm` + `LocalModelService`，打标专用）/ 远程推理管道（koog、prompt、react、tool） |
| `js/` | `JsEngine`, `JsValue`, `JsBridge`, `JsRuntime`, `NativeHandler`, `BuiltInHandlers`, `JsBridgeException`, `GallerySummaryJs` | JS 沙箱引擎无关层（JsEngine 接口 + bridge 路由 + handler SPI；QuickJS 实现在 `:androidApp`，详见 `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`） |
| `model/` | `command/`, `config/`, `context/`, `plan/` | 数据模型 |
| `platform/` | `logging/`, `storage/`, `thread/`, `voice/` | 平台能力：日志、存储、线程、语音 |
| `remote/` | `config/` | 远程模型配置与工厂 |
| `runtime/` | `capability/`, `execution/`, `policy/`, `state/` | 运行时能力：Capability、执行、隐私策略、场景状态 |
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
>
> **2026-07-20 Chat 相册搜索意图标准化**：
> - 新增 `SearchIntent` / `TimeRange`（`agent.core.model.context`），作为 LLM 与本地搜索之间的结构化桥梁
> - `AgentCommand.SearchMedia` 扩展 `intent: SearchIntent? = null`；新增 `AgentCommand.RefineMediaSearch`
> - `LocalPromptBuilder` / `RemotePromptBuilder` 增加搜索意图 Prompt 与示例（如“近半年小孩的照片”→`intent.time_range`）
> - `LocalCommandParser` / `ToolCallCommandParser` 支持解析 `params.intent` / `arguments.intent`
> - `:app` 层通过 `ChatSearchCapability` 接收命令，`ChatViewModel` 将 `SearchIntent` 转换为 `StructuredFilter` 后执行精确搜索
>
> **2026-07-20 Chat 搜索 Prompt 性能优化（二次）**：
> - `LocalPromptBuilder.buildChatL2StaticPrompt` 压缩 CHAT 场景 L2 Prompt：剔除冗余命令说明与示例，prompt tokens 从 ~3400 降至 ~1400
> - `LocalPromptBuilder.buildStateSection` 对 CHAT/UNKNOWN 场景省略相机/美颜状态，仅保留 `now`、`scene`、`last_user_image_uri`、`gallery_summary` 与最近搜索结果
> - `LocalPromptBuilder.buildSearchResultsSection` 对最近搜索结果每轮最多展示 10 条，降低多轮对话上下文膨胀
> - 静态 Prompt 按 `scene + capabilityNames` 缓存，动态状态每轮拼接（静态+动态变量拼接）
>
> **2026-07-20 远程模型 reasoning_content 空内容防护**：
> - `AgentOrchestrator.streamChatRemote` 在 `response.aiMessage().text()` 为空时，检查 `thinking()`（reasoning_content）
> - 若仅返回 reasoning 内容，抛出带明确描述的 `IllegalStateException`，避免 `onToken(null)` 触发 NPE，UI 显示具体错误信息而非 `unknown`
>
> **2026-07-25 JS Engine（QuickJS 沙箱）**：
> - 新增 `js/` 子包（`agent.core.js`）：引擎无关的 JS 沙箱抽象——`JsEngine` 接口、`JsValue` 值投影、`JsBridge` 路由、`JsRuntime` 门面、`NativeHandler` SPI（Sync/Async，`asyncHandler`/`syncHandler` 工厂）、`BuiltInHandlers`（math.add/string.upper/echo/device.info 演示 handler）、`JsBridgeException`（错误码）
> - 本包**不依赖任何具体 JS 引擎**：QuickJS 实现（`QuickJsEngine`）与 gallery/media 应用 handler 均在 `:app` `features/chat/js/`，引擎由调用方注入 `JsRuntime`
> - 全部取数 handler 为 async（JS 侧 `await bridge.callAsync`；对 async handler 调 `bridge.call` 抛 `HANDLER_NOT_ASYNC_CALLABLE`）
> - `capability.dispatch` 写通路的风险分级表在 `model/command/CommandRisk.kt`（READ_ONLY / REVERSIBLE_WRITE / DESTRUCTIVE）
> - 完整规格见 `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`
>
> **2026-07-27 能力访问链路梳理（tool_call 为主，JS 为内部实现）**：
> - 删除死代码 `AgentOrchestrator.streamChatLocal` / `streamChatRemote`（CHAT 已统一走 `streamChatReAct`，二者无调用方）；同步移除 `StreamingChatResponseHandler` / `LlmChatResponse` 孤儿 import 与 3 处过期注释
> - 上述「2026-07-20 远程模型 reasoning_content 空内容防护」原位于 `streamChatRemote`，随其删除；chat 远程链路现为 ReAct（`processChatReAct`），错误由 `RemoteReActAgent` 回调处理
> - 锁死不变式：本地小模型 L2 prompt 不得暴露 `run_gallery_script` / `draw_chart`（端侧 2B 无法可靠生成 JS）——`LocalPromptBuilderJsIsolationTest`
> - 写操作确认两层策略（Tier A JS `capability.dispatch` 经应用内确认 / Tier B 顶层 `@Tool` 经系统授权）写入 `CommandRisk` SSOT 与 `ChatMediaWriteCapability`/`ChatToolService` 交叉引用
> - `draw_chart` @Tool 描述（两 ToolService 逐字节相同）抽到 `GalleryToolDocs` 共享；其余同名工具按 agent 故意差异化
> - 路由心智模型与 JS 能力表面映射见 `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` §2.4 与 `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md` §1.2
>
> **2026-07-29 Chat 远程流式输出（SSE）**：
> - 新增 `StreamingSyncChatModel`（`agent.core.inference.remote`）：「同步外观、流式内核」的 `ChatModel` 适配器——内部持有 `StreamingChatModel`（SSE），`doChat` 用 CountDownLatch 阻塞返回完整 `ChatResponse`（含组装好的 toolCalls，AiServices 工具循环行为不变），逐 token 增量经可注入 `StreamListener` 旁路（`onTextSnapshot` 本轮累计全文快照 + `onRoundFinished` 轮结束）；未注入监听器时行为与原同步模型一致
> - `RemoteReActAgent.chatModel` 改用 `StreamingSyncChatModel(builder.buildStreaming())`；`RemoteReActAgentCallback` 新增默认空实现 `onPartialText(snapshot)`（不破坏飞书等其他实现方）；一轮流式结束且含 tool_calls 时在工具执行前触发 `onToolCall`
> - `RemoteChatEngine.streamChat` 的 `onToken: (String) -> Unit` 升级为 `onEvent: (ChatStreamEvent) -> Unit`（sealed：`TextSnapshot` 本轮累计全文 / `ToolCallStarted` 进入工具轮）；流式瞬态内容只走 UI 内存轨（`_streamingMessage`），不落 Room
> - `MambaAgentFactory.buildStreaming()` 补齐 `listeners` / `customParameters` 透传（原遗漏会导致 LlmCallRecord 录制与 `thinking=disabled` 在流式路径失效）
> - `AgentConfigurator.createRemoteChatModel` 同步改为流式内核；原"网关不支持 SSE"注释已过时（服务端 AI 网关已支持 SSE 逐 chunk 透传，见 `server/AGENTS.md`）
>
> **2026-08-02 端侧文本 LLM 移除（相机指令改远程 tool_calls）**：
> - 移除端侧文本指令链路：`LocalCameraAgent`、`LocalInferencePipeline`（L1/L2/L3 本地通道）、`LocalCommandParser`、`LocalPromptBuilder`、`IntentCache`/`L1CacheSettings`，以及 `local/llm/` LangChain4j 风格适配层（`LlmChatLanguageModel`/`StreamingLlmChatLanguageModel`/`LlmChatRequest`/`LlmChatResponse`/`ChatResponseMetadata`/`StreamingChatResponseHandler`；`StreamChatResult`/`StreamMetrics` 为远程 chat 链路独占，迁至 `inference.remote`）
> - `LocalLlmEngine` 裁剪为 **VLM 打标专用**：保留 `imageInference()`/`imageInferenceWithTimeout()`/`loadModel()`/`isLoaded`/`isLoadedAs()`/`unload()`/`trimMemory()` 等打标与生命周期方法，移除文本 `chat()` 同步/流式方法及对 `local/llm` 接口的实现
> - `AiAgentMode` 移除 `LOCAL`（保留 OFF/REMOTE/FEISHU）；`AiAgentInferencePreference` 枚举整体删除；`AgentConfigurator`/`AgentOrchestrator` 移除 `localPromptBuilder`/`intentCache`/`getLocalPipeline()`/`getInferencePreference()` 与 `configure()` 的 `inferencePreference` 参数（`modelId`/`localUseOpencl` 保留——打标/图像理解链路仍以其为默认加载参数）
> - 新增相机远程 tool_calls 链路：`CameraToolService`（`inference.remote.tool`，scene=CAMERA @Tool 工具集，经 `ToolCallCommandParser` 解析 + `CapabilityRegistry` 执行）+ `AgentOrchestrator.processCameraInput(input, agentContext, pageContext?, timeoutMs)`（远程单轮/少轮 tool_calls → 循环内直接执行 → `InferenceResult.Chat`；OFF 返回「AI Agent 已关闭」；相机 session 历史经 `MemoryManager` fire-and-forget 回写）
> - 相机 AI 指令协议与 chat 完全一致（标准 OpenAI tool_calls，ADR-005），不新造协议
>
> **2026-08-07 langchain4j → Koog 迁移 Phase 5（相机 + 飞书链路切 Koog）**：
> - 新增 `KoogReActAgent`（`inference.remote.koog`）：相机/飞书共用的回调式 Koog Agent，公开 API 与旧 `RemoteReActAgent` 逐方法对齐（executeTask/cancel/shutdown/isRunning/setSessionId/resetSession/initialize/getLastExecutionMetrics）；CoroutineScope(SupervisorJob()+Dispatchers.IO) 替代单线程 executor；EventHandler → RemoteReActAgentCallback 映射（TextDelta 累积快照 → onPartialText、onToolCallStarting → onToolCall、onLLMCallCompleted → token 累加 + LlmCallRecord，source 区分 "camera-koog"/"feishu-koog"）；记忆复用 Phase 3 产物（KoogSessionHistoryProvider + KoogMessageMemoryStore，键前缀 `koog_memory_`，历史不迁移）
> - `CameraToolService` / `RemoteControlToolService` 迁 Koog 注解（`@Tool(customName=...)` + 方法级/参数级 `@LLMDescription`，implement `ToolSet`），删除 `callTool` 字符串分发；Kotlin 默认参数全部改必填（可选语义用空串/坐标 -1）；LLM-facing 工具名与描述首句迁移前后逐字节一致（ToolInventory 确定性，DeepSeek 上下文缓存依赖）
> - `CameraToolService` 内联消除「拼 argsJson → ToolExecutionRequest → ToolCallCommandParser.parse」往返：@Tool 方法直接构造 AgentCommand（滤镜/风格中文别名解析随迁至本类私有函数）；`ToolCallCommandParser` 及其 3 个单测随删
> - 删除 langchain4j 期死代码：`RemoteReActAgent.kt`、`StreamingSyncChatModel.kt`、`CapturingChatModelListener.kt`（+其单测）、`DataStoreChatMemoryStore.kt`、`RemoteModelFactory.createBuilder`、`AgentConfigurator.createRemoteChatModel`
> - `AgentOrchestrator`（相机 `getCameraAgent`）/ `AgentConfigurator`（飞书 `getFeishuAgent`）仅构造点切 `KoogReActAgent`，两个 process 方法签名与 `RemoteReActAgentCallback`/`InferenceResult` 契约不变
>
> **2026-08-07 langchain4j → Koog 迁移 Phase 6（删除 :agent-core + 清理）**：
> - `settings.gradle.kts` 删 `include(":agent-core")`、`runtime-core/build.gradle.kts` 删 `api(project(":agent-core"))`、`agent-core/` 目录（310 文件 ~3.4 万行 vendored fork）整体删除；`scripts/publish-mamba-agent.sh`（fork 的 maven 发布脚本）随删
> - 护栏验证：`grep "^import com.mamba."`（排除 `com.mamba.picme` 自身命名空间）在 runtime-core/app 源码与测试为零引用
> - 残留编译依赖清理：`Capability.getCommandParameterSchema` 死 API（langchain4j tool-calling 时代，零调用方）从接口与 5 个实现（Memory/PersonRelation/ChatSearch/Camera/AiOptimize）移除；`ToolInventory` 删 langchain4j 注解分支（仅 Koog 扫描）；`MemoryManager` 裁剪到仅剩 `clearHistory`（读/写/裁剪已迁 KoogMessageMemoryStore），`AgentOrchestrator` 对话回写（相机 saveCameraConversation / chat appendConversation）改走 Koog 记忆层 load→拼→save；`clearChatMemory` 新旧两个键空间（memory_ / koog_memory_）并清
> - 测试侧：`ToolInventoryTest` / `ChatToolCapabilityCoverageTest` fixture 迁 Koog 注解；`MemoryManagerTrimTest`（fork 消息 trim 语义）随删——三不变式等价覆盖由 `KoogMessageMemoryTest` / `KoogMessageMemoryCodecTest` 承担；`MobileClipTokenizerTest` 顺手修 createTempDir 弃用（Kotlin 2.3 升级为 error）
> - `androidApp/proguard-rules.pro` 删失效 keep（`PoLangToolService` 类已不存在；Koog 工具集为代码直接引用，无需 langchain4j 式 @Tool 反射 keep）
> - 清理死代码 `RemoteModelFactory.DEFAULT_SOURCE`（无引用）；`RemotePromptBuilder` 早已随端侧文本 LLM 移除（仅历史 ADR 提及）

## 设计原则

- **零业务依赖**：不直接依赖 `BeautySettings`、`FilterType`、`MediaType`、`ExecutionPlan`（业务）等业务类型，通过泛型 `<T, C, P, A>` 让业务模块注入具体类型。
- **Android Library**：使用 `com.android.library` + `kotlin-compose` 插件，允许 Android 相关能力（如 AAR 依赖、JNI），但保持业务无关性。
- **隐私分级**：`PrivacyGuard` 对输入内容分级；媒体处理 100% 端侧（ADR-008），文本/元数据可走远程推理。

## 与 App 模块的关系

```
:runtime-core (Agent Runtime 核心)
    ↑ 被依赖
:androidApp (业务实现)
    - AgentCommand 密封类（含 BeautySettings 等）
    - Capability 接口（特化为 AgentCommand/AgentContext/PageContext/AgentAction）
    - CameraCapability / GalleryCapability / SettingsCapability
```

## 文件清单

### `capability/`
- `Capability.kt` — 泛型 Capability 接口（`CapabilityRegistry` 为唯一注册表；Compose CapabilityHost 已于 2026-07-29 退役）
- `FaceDetectionProvider.kt` — 人脸检测结果提供

### `facade/`
- `AgentConfigurator.kt` — Agent 配置管理（远程配置、运行模式、VLM 模型参数）
- `AgentOrchestrator.kt` — 应用级单例编排器（chat 经 `RemoteChatEngine`；相机经 `processCameraInput`；飞书经 `processRemoteImInput`；VLM 经 `localModelService`）

### `inference/local/`
- `LocalModelService.kt` — 端侧 VLM 模型加载服务（**打标专用**：`ensureModelLoaded`/`withModelLoaded`/`getLlmEngine`）

### `inference/local/llm/`（VLM 打标专用）
- `LlmGenerationMetrics.kt` — 生成指标
- `LlmModelManager.kt` — 端侧 VLM 模型管理
- `LocalLlmEngine.kt` — 端侧 VLM 推理引擎（`imageInference`/`imageInferenceWithTimeout` + 生命周期）
- `MnnLlmClient.kt` — MNN LLM 客户端（VLM 打标 JNI 桥）

### `inference/remote/prompt/`
- `RemotePromptBuilder.kt` — 远程 Prompt / Tool Schema 构建

### `inference/remote/koog/`（Koog 迁移 Phase 3/4/5）
- `KoogChatAgent.kt` — chat 链路 Koog Agent（AIAgent + ChatMemory feature，Phase 4）
- `KoogReActAgent.kt` — 相机/飞书链路 Koog Agent（回调式，公开 API 对齐旧 RemoteReActAgent，Phase 5）
- `KoogSessionHistoryProvider.kt` — Koog ChatHistoryProvider ↔ KoogMessageMemoryStore 桥
- `KoogMessageMemory.kt` — Koog 记忆三不变式（纯函数）

### `inference/remote/react/`
- `RemoteReActAgentCallback.kt` — ReAct 回调（契约冻结）+ `AgentExecutionMetrics`
- `RemoteReActAgentConfig.kt` — ReAct 配置

### `inference/remote/tool/`
- `RemoteControlToolService.kt` — IM 远程控制 RPA @Tool 工具集（飞书/Telegram 通道）
- `ChatToolService.kt` — chat 会话 agent @Tool 工具集（scene=CHAT）
- `CameraToolService.kt` — 相机 agent @Tool 工具集（scene=CAMERA，远程 tool_calls；`beautySettingsProvider` 由 app 注入）
- `ToolInventory.kt` — @Tool 元数据 → system prompt 工具清单段（确定性生成，防手写漂移）
- `GalleryToolDocs.kt` — chat / IM 远程控制两 agent 共享的 @Tool 描述文本

### `inference/remote/`
- `RemoteChatEngine.kt` — chat 远程 ReAct 链路引擎
- `StreamChatResult.kt` — 流式聊天结果（远程 chat 链路；自 `local/llm` 迁入）
- `ChatStreamEvent.kt` — chat 流式事件（TextSnapshot / ToolCallStarted）

### `js/`
- `JsEngine.kt` — 引擎无关 JS 引擎接口（eval / callFunction / installBridge / close）
- `JsValue.kt` — JS 值的 Kotlin 投影（sealed：Null/Bool/Num/Str/Obj/Arr）
- `JsBridge.kt` — JS ↔ Native 路由（register / dispatchSync / dispatchAsync）
- `JsRuntime.kt` — 门面：装配引擎 + bridge + 内置 handler（引擎由调用方注入）
- `NativeHandler.kt` — handler SPI（Sync/Async）与 `syncHandler`/`asyncHandler` 工厂
- `BuiltInHandlers.kt` — 内置演示 handler（math.add / string.upper / echo / device.info）
- `JsBridgeException.kt` — 错误码（HANDLER_NOT_FOUND / HANDLER_ERROR / SCRIPT_TIMEOUT 等）
- `JsCallback.kt` — 异步 handler 完成回调
- `GallerySummaryJs.kt` — GallerySummary → JsValue 转换（gallery.summary handler 用）

### `model/command/`
- `AgentCommands.kt` — 命令定义
- `CommandRisk.kt` — 命令风险分级表（READ_ONLY / REVERSIBLE_WRITE / DESTRUCTIVE，供 capability.dispatch 写通路确认分级）

### `model/config/`
- `AiAgentConfig.kt` — Agent 配置数据

### `model/context/`
- `AgentModels.kt` — Agent 模型
- `MediaAsset.kt` — 媒体资产
- `PageContext.kt` — 页面上下文
- `SceneContext.kt` — 场景上下文
- `SearchIntent.kt` — 搜索意图标准化模型（含 `TimeRange`）

### `model/plan/`
- `ExecutionPlan.kt` — 执行计划

### `platform/logging/`
- `Logger.kt` — 日志接口

### `platform/storage/`
- `KoogMessageMemoryStore.kt` — Koog 版 DataStore 对话历史持久化（键前缀 `koog_memory_`）
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
> - `UnifiedRemoteClient.kt`, `LangChain4jOpenAiClient.kt`, `RemoteCameraTools.kt`（功能已整合入 `RemoteReActAgent` + `RemoteControlToolService`）
> - `SherpaMnnAsrEngine.kt`, `MnnAsrClient.kt`, `com.k2fsa.sherpa.mnn.*`（已迁移至 Sherpa-ONNX）
> - `ToolCallParser.kt`（合并入 `ToolCallCommandParser.kt`）
> - 累计清理 ~2,600 行冗余代码
>
> **已移除（2026-08-02 端侧文本 LLM 链路）**：
> - `inference/local/LocalCameraAgent.kt`, `inference/local/pipeline/LocalInferencePipeline.kt`
> - `inference/local/parser/LocalCommandParser.kt`, `inference/local/prompt/LocalPromptBuilder.kt`
> - `runtime/cache/IntentCache.kt`, `runtime/cache/L1CacheSettings.kt`（L1 意图缓存随本地链路退役）
> - `local/llm/` 整包（`LlmChatLanguageModel`/`StreamingLlmChatLanguageModel`/`LlmChatRequest`/`LlmChatResponse`/`ChatResponseMetadata`/`StreamingChatResponseHandler`；`StreamChatResult.kt` 迁至 `inference/remote/`）
> - `LocalLlmEngine.chat()`（同步/流式）及 `extractText`/`safeExtractUserContent`/`buildPromptFromMessages` 辅助方法
> - `AiAgentMode.LOCAL`、`AiAgentInferencePreference` 枚举、`AgentConfigurator.getLocalPipeline()`/`getInferencePreference()`

## 编译验证

```bash
./gradlew :runtime-core:assembleDebug
```

## 相关 ADR

- `docs/02-ARCHITECTURE/ADR/ADR-005-local-remote-inference-split.md` — 本地/远程推理协议分离（移除包装层 · 清除冗余代码 · 产品重心迁移）
