# iOS Chat 功能（Phase 6.2）落地实现规划

> 2026-08-09 K3 plan agent 产出（只读调研，全部结论经读码验证，worktree refactor/ios-camera-track 分支为准）
> 执行纪律：新 worktree `.worktrees/ios-chat` + 分支 `feat/ios-chat`（从 refactor/ios-camera-track tip 切出）

## 0. 调研结论摘要

- shared commonMain 整条 Koog 聊天栈（`KoogChatAgent`/`RemoteChatEngine`/`ChatToolService`/`CapabilityRegistry`/`AgentOrchestrator`）**已在 iOS target 编译通过**；风险只剩 K/N 运行时行为（Ktor Darwin 引擎 SSE 流式、EventHandler 回调线程、取消级联）。
- commonMain 仅 3 个 expect（`DispatcherProvider`/`createKoogHttpClientFactory`/`AgentIdGenerator`），**iosMain 全部已有 actual**。其余注入点都是接口，靠组合根注入。
- 手工构建 Koog 工具的多平台安全 API：`SimpleTool<TArgs>(argsSerializer, descriptor)` + `ToolDescriptor(...)`（Koog 官方 Chess 示例同款）。
- 服务端**访客通道无需 X-App-Token**：仅 `X-Device-Id`（guestLlmQuota 默认 100 次/设备）。`RemoteModelConfig.PICME_SERVER_DEFAULT`（baseUrl `https://api.polang.net/`，apiKey 空 → 自动落 `"gateway-auth"`）在 `getChatAgent()` 内天然兜底，零配置可用。

## 1. 功能范围切片

### 进第一版
- 远程推理 tool_calls 对话：`RemoteChatEngine.streamChat` → `KoogChatAgent`，网关 `PICME_SERVER_DEFAULT`，流式 TextSnapshot + ToolCallStarted 事件
- 相册工具 8 个：`get_gallery_summary`/`search_media`/`refine_media_search`/`view_media`/`select_media`/`favorite_media`/`delete_media`/`share_media`
- 对话记忆持久化：iosMain `ChatMemoryStore`（NSUserDefaults），复用 `KoogMessageMemory` 三不变式
- 聊天 UI 历史持久化：Swift JSON 文件（Documents），单 session `default`
- 媒体结果卡片：`AgentAction.MediaResults` → Swift 卡片，复用 `ThumbnailLoader`

### 不进第一版
| 能力 | 理由 |
|---|---|
| JS Engine（run_gallery_script/draw_chart/capability.dispatch） | QuickJS iOS 移植+整套 handler 超 Chat 本体工作量；iOS prompt 不提 JS |
| AI 工程师模式 | 独立链路（/v1/claude-chat + chisel + AppToolExecutor）；账号白名单语义 6.3 前不成立 |
| 语音输入 | Sherpa-onnx iOS 移植是独立大件 |
| 修图工具（ai_optimize/adjust_image/edit_image） | 依赖端侧 VLM(6.1)+编辑管线 |
| 记忆工具（remember_fact/recall_memory） | 依赖 MemoryCapability 存储层 |
| TAG 相关（start_tag_scan） | 6.1 未做 |
| 多会话侧栏 | v1 单 session，setSessionId 已支持后续仅加 UI |
| 相机 AI 指令 | 相机页增强；cameraToolRegistry 传空即可 |

**system prompt 关键后果**：`buildChatSystemPrompt` 大量引用 JS/修图/记忆工具，iOS 只注册 8 个工具却沿用该 prompt 会诱使 LLM 调不存在的工具。方案：`AgentDependencies` 加带默认值的 `chatPromptBuilder` 注入点，Android 行为零变化，iOS 注入精简 prompt（§3.3）。

## 2. shared iosMain actual/注入点核对清单

| 契约 | 类型 | iosMain 状态 | 处置 |
|---|---|---|---|
| `expect class DispatcherProvider` | expect | ✅ 已有 | 直接用 |
| `expect fun createKoogHttpClientFactory` | expect | ✅ 已有（KtorKoogHttpClient.Factory + HeaderInjecting） | 直接用 |
| `expect object AgentIdGenerator` | expect | ✅ 已有 | 直接用 |
| `interface ChatMemoryStore` | 组合根注入 | ❌ 缺 | 新建 `IosKoogMessageMemoryStore`（NSUserDefaults） |
| `fun interface ChatHistoryCleaner` | 组合根注入 | ❌ 缺 | no-op lambda |
| `interface ImageInferenceEngine` | 组合根注入 | ❌ 缺 | stub（`isLoaded=false`；AgentConfigurator 构造期 eager 调用，不可省） |
| `chatToolDescriptors/chatToolRegistry` | 组合根注入 | ❌ 缺（K/N 无反射） | 手工清单 `ChatToolManifest`（§3.2） |
| `cameraToolDescriptors/cameraToolRegistry` | 组合根注入 | ❌ 缺 | 空 list + `ToolRegistry {}` |
| `remoteImToolRegistryProvider` | 组合根注入 | ❌ 缺 | `{ ToolRegistry {} }` |
| `MemoryContextProvider` | 可选 | — | v1 不注入 |
| Ktor iOS 引擎 | 依赖 | ⚠️ 未验证 | T0 冒烟；若缺则 iosMain 补 `ktor-client-darwin`（唯一可能的 gradle 改动） |

## 3. 核心设计

### 3.1 总体接线

```
PoLangApp.init → AppContainer.init
  └─ IosAgentComposition.initialize(bridge, deviceId)
       → AgentOrchestrator.initialize(AgentDependencies(
           chatMemoryStore = IosKoogMessageMemoryStore(), chatHistoryCleaner = {},
           imageEngineProvider = { stub },
           chatToolDescriptors/Registry = ChatToolManifest.*,
           cameraTool* = empty, remoteImToolRegistryProvider = { ToolRegistry {} },
           chatPromptBuilder = IosChatPrompt::build))
ChatViewModel (Swift) → ChatAgentBridge (iosMain 非 suspend 回调式)
  ├─ sendMessage(input, onText, onToolCall, onComplete) → streamChat(...)
  ├─ watchUiActions(onAction: (ChatUiActionDto) -> Unit) → ChatToolService.uiActions (FlowWatcher)
  ├─ clearHistory() → orchestrator.clearChatMemory("default")
  └─ cancelCurrent() → job.cancel()
```

### 3.2 ChatToolManifest（无反射替代）

commonMain 新建 `agent/core/inference/remote/tool/ChatToolManifest.kt`：8 个工具各一个 `SimpleTool<TArgs>` 子类包装 `ChatToolService.getInstance()` 的 suspend 方法。**描述/参数名逐字节对齐 @LLMDescription**——守卫测试 `ChatToolManifestConsistencyTest`（jvmTest）：JVM `asToolsByClass()` 反射结果与 manifest 逐项比对（name/description/参数名/类型），模式对齐 `ToolPromptDeterminismTest`。组合根消费：`chatToolDescriptors = ChatToolManifest.buildDescriptors()`、`chatToolRegistry = ToolRegistry { tools(ChatToolManifest.buildTools()) }`。

### 3.3 iOS 专属 prompt（最小侵入）

- `AgentDependencies` 加字段：`val chatPromptBuilder: (List<ToolDescriptor>) -> String = RemoteChatEngine::buildChatSystemPrompt`（默认值 = Android 现状，零行为变化）
- `RemoteChatEngine` 构造参数透传替代直接调用
- 新文件 iosMain `IosChatPrompt.kt`：角色一句 + ToolInventory.build(descriptors) + 规则段（search/refine 多轮窄化保留；删 JS/画图/修图/记忆/设置全部段落）

### 3.4 持久化 actual（3 个新文件，均 iosMain）

- `IosKoogMessageMemoryStore.kt`：NSUserDefaults，键前缀 `koog_memory_`，复用 commonMain `encodeKoogMessages`/`decodeKoogMessages`（internal 同模块可直接用）+ `KoogMessageMemory` 三不变式（withoutSystemMessages/trimToMaxMessages/sanitizeToolPairing）
- `ChatHistoryCleaner { }` no-op
- `IosUnavailableImageInferenceEngine`：stub，遵守「错误路径返回空串、不产生 __ERROR_ 前缀」契约

### 3.5 IosChatGalleryCapability（iosMain，继承 BaseCapability）

- `get_gallery_summary`：`repository.allMedia.first()` 统计 → TextReply（如实标注「iOS 端暂未开启标签/人脸索引」）
- `search_media`：无 TAG 索引的诚实降级——fileName 关键词 + 最新 N 张 → MediaResults
- `refine_media_search`：有 timeRange 按 captureDate 精确交集
- `delete_media`：`repository.deleteMediaByIds` → PHAssetChangeRequest 系统确认窗兜底（Tier B）
- `favorite_media`：需桥扩展（PhMediaBridge 加 `setFavorite`）
- share/view/select：返回 Success action，UI 表现由 Swift 消费 uiActions（ShareSheet 组件已存在）
- 未注册命令走 CapabilityRegistry 兜底 Error(METHOD_NOT_FOUND)，链路安全

### 3.6 ChatAgentBridge（signal 6 纪律：非 suspend、回调闭包、异常不跨边界）

```kotlin
class ChatAgentBridge(orchestrator: AgentOrchestrator, sessionId: String = "default") {
    fun sendMessage(input, onText: (String)->Unit, onToolCall: ()->Unit,
                    onComplete: (summary: String, errorMessage: String?)->Unit): FlowWatcher
    fun watchUiActions(onAction: (ChatUiActionDto)->Unit): FlowWatcher
    fun clearHistory(onDone: ()->Unit): FlowWatcher
    fun isRunning(): Boolean
}
data class ChatUiActionDto(kind: String, message: String = "", query: String = "",
                           totalCount: Long = 0, mediaIds: List<Long> = emptyList())
```

try/catch(Throwable) 全兜（CancellationException 语义保留）；Swift 侧回调 `Task { @MainActor in }` 转主线程（FlowWatchers 同款纪律）。

### 3.7 Swift 侧（Features/Chat/，MV 对齐 GalleryViewModel）

- `ChatMessage.swift`（Identifiable/Codable UI 态模型）
- `ChatHistoryStore.swift`（Documents/chat_history_default.json，UI 展示历史全量；与 Koog 记忆层是两套）
- `ChatViewModel.swift`（send → 占位 assistant isStreaming → 回调更新 → 落 store；watchUiActions → 媒体卡片；持有 FlowWatcher 记得取消）
- `ChatView.swift`（气泡 List + 输入栏 + 流式光标 + 媒体卡片 LazyHGrid + 错误气泡含访客额度文案；替换 MainTabView:32 的占位）
- `AppContainer` 增量：`DeviceIdStore`（identifierForVendor + UserDefaults 持久化）→ `IosAgentComposition.initialize(bridge, deviceId)` → `chatBridge`；PhMediaBridge 实例提升为 AppContainer 持有

## 4. 服务端依赖与鉴权（6.3 前过渡）

- 端点 `https://api.polang.net/`（OpenAI 兼容 SSE）；v1 = 访客模式仅 `X-Device-Id`；超额 401/429 → 错误气泡「访客额度已用完，后续版本支持注册扩容」
- 6.3 衔接：登录后 `orchestrator.updateRemoteRuntimeConfig(...gatewayToken = token)`，配置变更检测自动重建 agent 注入 X-App-Token，客户端零结构改动

## 5. [PRIVACY] 红线适配

1. 工具 observation 只出文本：计数/id/query 回显/fileName；禁止路径/GPS/字节/base64 进返回串（对齐 Android media.meta 口径）
2. 绝不构造多模态请求：无 imageInference、capabilities 不含 Vision；补 jvmTest（对齐 RemoteInferenceNoMediaUploadGuardTest）断言工具返回不含 file://、data:、base64 模式
3. 删除走 PHAssetChangeRequest 系统确认窗（Tier B）
4. 媒体卡片渲染纯端侧

## 6. 任务拆分（依赖序：T0 ∥ T1 ∥ T2 → T3、T4 → T5 → T6 → T7）

| # | 任务 | 验证 |
|---|---|---|
| T0 | **Koog iOS 运行时冒烟**（最高危前置）：最小 AIAgent 无工具跑一次真实网关请求，验 Ktor Darwin + SSE + 取消 | iosTest 或 DebugOverlay 触发，控制台见流式文本 |
| T1 | 持久化 actual 三件套 | iosTest：save→load 往返 + 三不变式 |
| T2 | ChatToolManifest + 一致性守卫 | jvmTest 绿（manifest vs 反射逐项比对） |
| T3 | prompt 注入点 + iOS prompt | jvmTest：Android 默认 prompt golden 逐字节不变；iOS prompt 不含 run_gallery_script |
| T4 | IosChatGalleryCapability + 桥扩展（setFavorite） | iosTest fake bridge 分支覆盖；真机删除弹系统窗 |
| T5 | IosAgentComposition + ChatAgentBridge + DTO | iosTest：initialize 后 orchestrator 可达 |
| T6 | Swift UI 四文件 + AppContainer/MainTabView 接线 | XCTest ViewModel（fake bridge）；模拟器发送/流式/卡片/重启留存 |
| T7 | 闭环 + 红线审计 | 真机四链路（盘点/搜索/窄化/删除）；[PRIVACY] 抓包；[I18N] 三语 |

## 7. 风险点

1. **Koog/Ktor iOS 运行时未验证（最高危）**：T0 前置就是为这个；缺 `ktor-client-darwin` 则补一行 gradle
2. 手工清单漂移 → T2 jvmTest 逐字节守卫；长期把描述提 const val SSOT
3. 搜索质量诚实降级（无 TAG）：capability 如实描述维度，prompt 不承诺语义搜索
4. 访客额度 100 次/设备天花板，错误文案要友好
5. `Dispatchers.Default` 无串行 modelDispatcher 语义：**Swift 侧必须串行发送**（isProcessing 禁发）
6. K/N 回调线程：onText 落 Swift 必须 `Task { @MainActor in }`，漏一处即 UI 线程违规
7. prompt 变更波及 Android：golden test 锁死逐字节不变
8. identifierForVendor 卸载重装变化：设备额度维度重置，语义可接受，文档注明
