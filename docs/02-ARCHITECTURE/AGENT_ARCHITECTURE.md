# langchain4android Agent 架构设计

> **版本**：4.0（合并版）  
> **状态**：已实施 / 迭代中  
> **最后更新**：2026-07-08  
> **主要维护者**：[RD] 全栈工程师  
> **历史合并说明**：本文档由 `AGENT_ARCHITECTURE.md` 与 `REMOTE_INFERENCE_ARCHITECTURE.md` 合并而成。远程推理相关的 OpenAI 协议、langchain4j 标准化、DeepSeek 适配、四层模型、性能成本与验收标准已并入“推理模式选型”与“远程推理”章节，原 `REMOTE_INFERENCE_ARCHITECTURE.md` 已删除。

> **边界声明（Boundary Statement）**
> - 本文档定义 Agent 的运行时架构、Capability 模型与推理模式选型。
> - 产品目标与验收口径以 [`../01-PRODUCT/FEATURES.md`](../01-PRODUCT/FEATURES.md) 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 [`AGENTS.md`](../../AGENTS.md) 为准。
> - **重要：`:agent-core` 是 Java 基础库**（ChatModel、Tool、AiServices），Agent 编排层（AgentOrchestrator、CapabilityRegistry、PrivacyGuard、MemoryManager、SceneManager 等）在 `:runtime-core` 模块的 `runtime-core/src/main/java/com/mamba/picme/agent/core/` 目录下。详见 [`MODULE_ARCHITECTURE.md`](MODULE_ARCHITECTURE.md)。

**模块定位**: AI Agent 运行时架构与推理模式选型（基础库 langchain4android + Demo 工程 PoLang）  
**阅读对象**: RD、AI Agent

---

## 目录

1. [核心产品逻辑](#1-核心产品逻辑-core-product-logic)
2. [架构图](#2-架构图)
3. [核心组件设计](#3-核心组件设计)
4. [推理模式选型](#4-推理模式选型)
5. [命令扩展](#5-命令扩展)
6. [数据模型](#6-数据模型)
7. [执行规约](#7-执行规约)
8. [常见陷阱检查清单](#8-常见陷阱检查清单)
9. [验收标准](#9-验收标准)
10. [远程推理任务拆分](#10-远程推理任务拆分-agent-task)
11. [架构演进路线图](#11-架构演进路线图)
12. [附录：参考文档](#12-附录参考文档)

---

## 1. 核心产品逻辑 (Core Product Logic)

### 1.1 红线约束

| 约束 | 定义 | 验证方式 |
|------|------|----------|
| **[PRIVACY]** | 敏感数据优先本地推理；确需云端处理时，必须获得用户授权且不得留存 | PrivacyGuard 拦截数据流 + 授权流程审计 |
| **[PERF]** | 交互反馈 < 100ms，LLM 推理后台完成 | 端侧 Qwen3.5-2B 首 token 目标 < 600ms |
| **[I18N]** | System Prompt 及用户可见回复禁止硬编码中文 | 接入 string 资源 |
| **[OFFLINE]** | 本地模型未下载时提供明确引导 | 非静默失败 |
| **[TYPE_SAFE]** | AgentCommand / AgentAction 必须使用 Sealed Class | 禁止字符串魔法值 |

### 1.2 当前架构模式（ADR-005 后）

**本地链路**：单轮 Function Calling（自定义 JSON 数组协议）  
**远程链路**：标准 OpenAI Chat Completions API（原生 tool_calls + 流式 + 多轮对话）

两条链路完全独立，无共享路由逻辑。

```
用户输入 → AgentOrchestrator.dispatch() → 模式选择
    ├── LOCAL → LocalInferencePipeline → LocalLlmEngine → JSON 数组解析 → Capability 执行
    └── REMOTE → RemoteInferencePipeline → RemoteOrchestrator → OpenAI tool_calls → Capability 执行
```

**核心组件状态**: 

| 组件 | 职责 | 状态 |
|------|------|------|
| `AgentOrchestrator` | 统一入口，管理本地/远程两条独立推理链路 | ✅ 已落地 |
| `LocalInferencePipeline` | 本地推理链路：L1 Cache + L2 Batch（自定义 JSON 数组协议） | ✅ 已落地 |
| `RemoteInferencePipeline` | 远程推理链路：OpenAI Chat Completions API（tool_calls·流式·多轮） | ✅ 已落地 |
| `LocalLlmEngine` | 封装 MNN-LLM 客户端，Qwen3.5-2B 本地推理 | ✅ 已落地 |
| `RemoteOrchestrator` | 远程推理编排：:agent-core OpenAiChatModel + ChatMemory | ✅ 已落地 |
| `:agent-core` (模块) | Java Android Library，提供 LangChain4j 风格 API：ChatModel、@Tool、AiServices、ChatMemory、OpenAiChatModel、OkHttp SSE 流式客户端 | ✅ 已落地 |
| `CapabilityRegistry` | Capability 注册与命令分发 | ✅ 已落地 |
| `PrivacyGuard` | 输入内容隐私分级与本地优先约束 | ✅ 已落地 |
| `MemoryManager` | DataStore 持久化对话历史，按 session 隔离 | ✅ 已落地 |
| `KeywordSpotterEngine` | KWS 常驻低功耗唤醒词检测（Sherpa-ONNX，~14MB） | ✅ 已落地 |
| `SherpaOnnxAsrEngine` | ASR 按需加载语音转录（Sherpa-ONNX，~282MB） | ✅ 已落地 |
| `FeishuRemoteChannel` | 飞书 WebSocket 直连，IM 远程控制入口 | 🔄 迭代中 |
| `IntentCache` | L1 远程意图缓存，高频指令直接返回 | ✅ 已落地 |

**已移除组件（ADR-005/006，2026-06）**：
- `InferenceRouter` — 拆分为 `LocalInferencePipeline` + `RemoteInferencePipeline` 两条独立链路
- `ToolCallingChatLanguageModel` — 远程直接使用 langchain4j 原生 `OpenAiChatModel`
- `ToolCallingOutputParser` — 远程使用标准 OpenAI `tool_calls` 响应格式
- `ToolPromptBuilder` — 拆分为 `LocalPromptBuilder` + `RemotePromptBuilder`
- `AdaptiveStrategySelector` — 本地不再需要策略分级
- `ToolOrchestrator` — 编排逻辑合并入 `RemoteOrchestrator`
- `SherpaMnnAsrEngine` + `com.k2fsa.sherpa.mnn.*` — 已迁移至 Sherpa-ONNX（`SherpaOnnxAsrEngine`）
- `MnnAsrClient` — 占位死代码，同步移除
- 共清理 ~2,600 行冗余代码

---

## 2. 架构图

### 2.1 系统全景架构

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                               UI Layer (Compose)                               │
│                                                                               │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐               │
│  │   ChatScreen    │  │  GalleryScreen  │  │   CameraScreen  │               │
│  │  🏠 默认首页     │  │  📸 智能相册     │  │  📷 辅助入口     │               │
│  │  AI对话·模型切换  │  │  媒体浏览·AI搜索 │  │  美颜·滤镜·语音  │               │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘               │
│           │                    │                    │                         │
│           └────────────────────┼────────────────────┘                         │
│                                ▼                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐    │
│  │                   GlobalAgentPanel / AiAgentUseCase (Facade)           │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │    │
│  │  │ Chat UI  │ │Voice Btn │ │QuickActs │ │Model Sel │ │StatusBar │   │    │
│  │  │(多线程)   │ │(KWS唤醒) │ │(快捷入口) │ │(本地/远程)│ │(推理状态) │   │    │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘   │    │
│  └──────────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                       Agent Orchestration Layer (:app · Kotlin)                  │
│                                                                               │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │                      AgentOrchestrator (编排器)                          │  │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐          │  │
│  │  │SceneManager│ │PrivacyGuard│ │MemoryManager│ │IntentCache │          │  │
│  │  │(场景感知)   │ │(隐私分级)   │ │(对话持久化)  │ │(L1远程缓存) │          │  │
│  │  └────────────┘ └────────────┘ └────────────┘ └────────────┘          │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                    │                              │                           │
│          LOCAL mode│                              │REMOTE mode                │
│                    ▼                              ▼                           │
│  ┌────────────────────────────┐  ┌──────────────────────────────────────┐   │
│  │  LocalInferencePipeline    │  │  RemoteInferencePipeline              │   │
│  │  ┌──────────────────────┐  │  │  ┌────────────────────────────────┐  │   │
│  │  │ LocalLlmEngine       │  │  │  │ RemoteOrchestrator             │  │   │
│  │  │ Qwen3.5-2B (MNN)     │  │  │  │ :agent-core OpenAiChatModel   │  │   │
│  │  │ L1 Cache → L2 Batch  │  │  │  │ OpenAI Chat Completions API   │  │   │
│  │  └──────────────────────┘  │  │  │ DeepSeek V4 适配               │  │   │
│  └────────────────────────────┘  │  │ L2 Batch / L3 Plan / L4 Chat   │  │   │
│                                  │  └────────────────────────────────┘  │   │
│  ┌──────────────────────────┐    │                                      │   │
│  │   Voice Pipeline (ONNX)  │    │  ┌────────────────────────────────┐  │   │
│  │  ┌────────────────────┐  │    │  │ FeishuRemoteChannel            │  │   │
│  │  │KeywordSpotterEngine│  │    │  │ 飞书 WebSocket 直连             │  │   │
│  │  │ KWS always-on      │  │    │  │ IM消息→AgentCommand            │  │   │
│  │  │ ~14MB · 50mW       │  │    │  │ 拍照回传·设备绑定·确认机制      │  │   │
│  │  └─────────┬──────────┘  │    │  └────────────────────────────────┘  │   │
│  │            │ 唤醒        │    │                                      │   │
│  │  ┌─────────▼──────────┐  │    │  ┌────────────────────────────────┐  │   │
│  │  │SherpaOnnxAsrEngine │  │    │  │ Cloudflare AI Gateway           │  │   │
│  │  │ ASR on-demand      │  │    │  │ API Key 保护 · 速率限制          │  │   │
│  │  │ ~282MB · 按需加载   │  │    │  │ DeepSeek / Claude 多模型路由    │  │   │
│  │  └────────────────────┘  │    │  └────────────────────────────────┘  │   │
│  └──────────────────────────┘    └──────────────────────────────────────┘   │
│                                      │                                       │
│                                      ▼                                       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                    CapabilityRegistry (能力注册表)                      │   │
│  │                                                                       │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │   │
│  │  │ Camera   │ │ Gallery  │ │ Settings │ │ Navigate │ │ Editor   │  │   │
│  │  │拍照/录像  │ │查看/删除  │ │主题/语言  │ │页面切换   │ │图片编辑  │  │   │
│  │  │美颜/滤镜  │ │分享/搜索  │ │模型管理  │ │返回/退出  │ │AI 优化   │  │   │
│  │  │变焦/曝光  │ │批量操作   │ │语音配置  │ │          │ │          │  │   │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘  │   │
│  │  ┌──────────┐                                                        │   │
│  │  │ IMRemote │ 飞书远程控制 · 设备绑定 · 命令确认                        │   │
│  │  └──────────┘                                                        │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                          Domain / Data / Infra                                │
│                                                                              │
│  ┌────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐ │
│  │MediaRepo   │ │SettingsRepo  │ │ BeautyEngine │ │ MNN-LLM (本地模型)    │ │
│  │(Room DB)   │ │(DataStore)   │ │ (OpenGL ES)  │ │ Qwen3.5-2B · 端侧推理 │ │
│  └────────────┘ └──────────────┘ └──────────────┘ └──────────────────────┘ │
│                                                                              │
│  ┌──────────────────────┐ ┌──────────────────────┐ ┌──────────────────────┐ │
│  │ :agent-core (SDK)    │ │LlmModelDownloadManager│ │ FaceDetect Pipeline  │ │
│  │ Java Library          │ │前台服务·断点续传       │ │ MediaPipe·MNN        │ │
│  │ ChatModel·@Tool      │ └──────────────────────┘ └──────────────────────┘ │
│  │ AiServices·SSE       │ ┌──────────────────────┐                           │
│  └──────────────────────┘ │ Network Monitor      │                           │
│                           │ 飞书重连·心跳保持     │                           │
│                           └──────────────────────┘                           │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 推理链路数据流（ADR-005 后）

```
用户输入 "找出去年夏天的照片" / "磨皮50"
        │
        ▼
AgentOrchestrator.dispatch(input)
        │
        ├── PrivacyGuard.assess(input) → 敏感? → 强制 LOCAL
        │
        ├── LOCAL mode (默认端侧模型)
        │   │
        │   ├── IntentCache.lookup(input) → HIT? → 直接返回 (L1)
        │   │
        │   └── LocalLlmEngine.generate(messages)
        │       └── MNN-LLM (Qwen3.5-2B) → JSON 数组解析 → Capability 执行
        │
        └── REMOTE mode (用户手动切换或隐私允许)
            │
            ├── IntentCache.lookup(input) → HIT? → 直接返回 (L1)
            │
            └── RemoteOrchestrator.chat(messages, tools)
                └── :agent-core (Java Library)
                    ├── OpenAiChatModel (ChatLanguageModel)
                    ├── ToolSpecification (tool_calls 构建)
                    └── OkHttp SSE Streaming (流式响应)
                        └── Cloudflare Gateway → DeepSeek / Claude API
                            → tool_calls 解析 → Capability 执行
```

### 2.3 语音交互管线（Sherpa-ONNX 双引擎）

```
[休眠态] Always-on KWS (~14MB · 50mW)
    │
    │ 用户说"小觅拍张照"
    ▼
KWS 检测到唤醒词 → 暂停 KWS
    │
    ▼
加载 ASR (~282MB · ~500mW) → 转录 "拍张照"
    │
    ▼
AgentOrchestrator.dispatch("拍张照") → Capability 执行
    │
    ▼
释放 ASR → 恢复 KWS always-on
```

---

## 3. 核心组件设计

### 3.1 SceneManager 场景管理器

```kotlin
/**
 * 场景管理器
 * 
 * 负责：
 * 1. 跟踪当前活跃场景
 * 2. 根据场景获取对应的 Capability 集合
 * 3. 动态构建场景相关的 system prompt
 */
class SceneManager {
    
    enum class Scene {
        GALLERY,     // 相册首页（默认）
        CHAT,        // AI 对话二级页
        CAMERA,      // 相机页
        SETTINGS,    // 设置页
        EDITOR,      // 编辑页
        DEBUG        // 调试页
    }
    
    private val _currentScene = MutableStateFlow(Scene.GALLERY)
    val currentScene: StateFlow<Scene> = _currentScene.asStateFlow()
    
    fun transitionTo(scene: Scene) {
        _currentScene.value = scene
    }
    
    /**
     * 获取场景对应的 Capability 列表
     */
    fun getCapabilitiesForScene(scene: Scene): List<String> = when (scene) {
        Scene.GALLERY -> listOf("gallery", "editor", "navigation")
        Scene.CHAT -> listOf("chat", "navigation", "gallery", "editor")
        Scene.CAMERA -> listOf("camera", "navigation")
        Scene.SETTINGS -> listOf("settings", "navigation")
        Scene.EDITOR -> listOf("edit", "navigation")
        Scene.DEBUG -> listOf("navigation")
    }
}
```

### 3.2 分层 Prompt 构建器（ADR-005 后）

本地和远程使用独立的 Prompt 构建器：

```kotlin
// 本地 Prompt — 精简、结构化
class LocalPromptBuilder {
    fun buildSystemPrompt(capabilities: List<Capability>, context: AgentContext): String = """
        你是 PoLang AI 助手，运行在端侧设备。
        请根据用户输入，输出 JSON 数组格式的命令。

        可用命令：
        ${capabilities.joinToString("\n") { "- ${it.name}: ${it.description}" }}

        输出格式：
        [{"method":"命令名称","args":{"参数名":值}}]
        如果无法理解，输出：{"method":"text_reply","args":{"text":"回复内容"}}
    """.trimIndent()
}

// 远程 Prompt — 标准 OpenAI 协议格式
// 通过 langchain4j 构建 ChatRequest，SDK 自动序列化为 OpenAI Chat Completions 请求体
class RemotePromptBuilder {
    fun buildChatRequest(
        systemPrompt: String,
        userInput: String,
        capabilities: List<Capability>
    ): ChatRequest {
        val toolSpecs = capabilities.map { cap ->
            ToolSpecification.builder()
                .name(cap.name)
                .description(cap.description)
                .build()
        }
        return ChatRequest.builder()
            .messages(
                SystemMessage(systemPrompt),
                UserMessage(userInput)
            )
            .toolSpecifications(toolSpecs)
            .build()
        // langchain4j 自动序列化为:
        // POST /v1/chat/completions
        // {"model":"...","messages":[...],"tools":[...]}
    }
}
```

### 3.3 Capability 接口扩展

```kotlin
/**
 * Capability 接口 V2
 * 
 * 新增：
 * - 场景绑定：声明该 Capability 活跃的场景
 * - 上下文感知：接收页面特定的上下文数据
 */
interface Capability {
    val name: String
    val description: String
    
    /**
     * 该 Capability 在哪些场景下可用
     */
    fun activeScenes(): List<SceneManager.Scene>
    
    fun supportedCommands(): List<String>
    
    /**
     * 执行命令
     * @param command 解析后的命令
     * @param context 当前上下文
     * @param pageContext 页面特定上下文（如 Gallery 当前选中的照片）
     */
    suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext? = null
    ): Result<AgentAction>
}

/**
 * 页面上下文（页面特定状态）
 */
sealed class PageContext {
    data class GalleryContext(
        val currentMedia: MediaAsset?,
        val selectedItems: List<MediaAsset>,
        val isSelectionMode: Boolean
    ) : PageContext()
    
    data class SettingsContext(
        val currentCategory: String?
    ) : PageContext()
    
    data class EditorContext(
        val editingMedia: MediaAsset,
        val hasUnsavedChanges: Boolean
    ) : PageContext()
    
    object None : PageContext()
}
```

### 3.4 GalleryCapability 示例

```kotlin
/**
 * 相册控制 Capability
 */
class GalleryCapability(
    private val onViewMedia: ((MediaAsset) -> Unit)? = null,
    private val onDeleteMedia: ((List<MediaAsset>) -> Unit)? = null,
    private val onShareMedia: ((List<MediaAsset>) -> Unit)? = null,
    private val onSelectMedia: ((MediaAsset, Boolean) -> Unit)? = null,
    private val onSearch: ((String) -> Unit)? = null,
    private val onSwitchViewMode: ((ViewMode) -> Unit)? = null
) : Capability {
    
    override val name = "gallery"
    override val description = "查看、删除、分享、搜索照片和视频"
    
    override fun activeScenes() = listOf(SceneManager.Scene.GALLERY, SceneManager.Scene.CHAT)
    
    override fun supportedCommands() = listOf(
        "view_media",
        "delete_media",
        "share_media",
        "select_media",
        "search_media",
        "switch_view",
        "text_reply"
    )
    
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val galleryContext = pageContext as? PageContext.GalleryContext
        
        return when (command) {
            is AgentCommand.ViewMedia -> {
                val media = command.mediaId?.let { findMediaById(it) }
                    ?: galleryContext?.currentMedia
                media?.let { onViewMedia?.invoke(it) }
                Result.success(AgentAction.Success(command))
            }
            
            is AgentCommand.DeleteMedia -> {
                val items = command.mediaIds.mapNotNull { findMediaById(it) }
                    .ifEmpty { galleryContext?.selectedItems ?: emptyList() }
                onDeleteMedia?.invoke(items)
                Result.success(AgentAction.Success(command))
            }
            
            else -> Result.success(AgentAction.Error("不支持的命令"))
        }
    }
}
```

### 3.5 NavigationCapability 示例

```kotlin
/**
 * 导航 Capability
 * 
 * 在所有场景都可用，负责页面切换
 */
class NavigationCapability(
    private val onNavigate: (Screen) -> Unit,
    private val onBack: () -> Unit
) : Capability {
    
    override val name = "navigation"
    override val description = "页面导航：切换页面、返回上一页"
    
    override fun activeScenes() = SceneManager.Scene.entries.toList()
    
    override fun supportedCommands() = listOf(
        "navigate_to",
        "go_back",
        "text_reply"
    )
    
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.NavigateTo -> {
                val screen = when (command.destination.lowercase()) {
                    "camera", "相机" -> Screen.Camera
                    "gallery", "相册" -> Screen.Gallery
                    "settings", "设置" -> Screen.Settings
                    "editor", "编辑" -> Screen.Editor
                    "chat", "聊天" -> Screen.Chat
                    else -> return Result.success(AgentAction.Error("未知页面：${command.destination}"))
                }
                onNavigate(screen)
                Result.success(AgentAction.Success(command))
            }
            
            is AgentCommand.GoBack -> {
                onBack()
                Result.success(AgentAction.Success(command))
            }
            
            else -> Result.success(AgentAction.Error("不支持的导航命令"))
        }
    }
}
```

---

## 4. 推理模式选型

### 4.1 本地 vs 远程推理

| 维度 | 本地推理 | 远程推理 |
|------|---------|---------|
| **协议** | 自定义 JSON 数组 | 标准 OpenAI Chat Completions API |
| **Library** | 无第三方依赖 | :agent-core (OpenAiChatModel) |
| **Prompt** | 精简、结构化 | 自然语言 + Tool Schema |
| **输出解析** | 简单 JSON 数组解析 | 标准 JSON 反序列化（tool_calls） |
| **约束方式** | JSON 数组格式 Prompt 约束 | OpenAI 原生协议约束 |
| **聊天/闲聊** | 通过 text_reply 命令兜底 | 原生支持（流式 + 多轮） |
| **Strategy** | L1 Cache / L2 Batch | L2 Batch / L3 Plan / L4 Chat |
| **延迟** | < 600ms | 500ms-2s |
| **隐私** | 敏感数据本地处理，复杂推理可经用户授权后上云 | 非敏感数据允许上云 |

### 4.2 端侧推理模式选型（Qwen3.5-2B）

**不推荐完整 ReAct**，原因：
- 2B 模型 COT（链式思考）能力弱，Thought 质量不稳定
- AI 对话页要求 < 500ms 首字延迟，多轮推理无法满足 `[PERF]` 红线
- 端侧电池/发热敏感

**端侧能力边界（2026-06-12 验证）**：
| 能力 | 端侧支持 | 说明 |
|------|----------|------|
| 单条 Function Calling | 已验证 | 明确意图→JSON 命令，准确率 > 90% |
| 简单 Batch FC（2-3 条） | 部分支持 | JSON 数组格式需强 prompt 约束，偶有格式错误 |
| 复杂组合指令（含条件/延迟） | 不支持 | 超出 2B 模型理解范围，明确走远程 |
| 上下文推理（隐式引用） | 不支持 | "再亮一点"类指令需规则兜底 |
| 开放式闲聊 | 不支持 | 生成质量差，应路由到远程 L4 |

### 4.3 远程推理模式选型

远程模式下**减少 LLM 调用次数**比端侧更重要（RTT 成本主导）。

**推荐分层自适应模式**：

| 层级 | 模式 | 适用场景 | 协议 | 输出格式 | 执行位置 |
|------|------|---------|------|---------|---------|
| Layer 1 | 本地规则缓存 | 高频指令命中缓存 | 不走 LLM | 预定义命令 | 端侧 |
| Layer 2 | Batch Function Calling | 简单连续动作指令（2-3 步） | OpenAI Chat Completions + tool_calls | `ToolExecutionRequest[]` → `AgentCommand[]` | 远程 |
| Layer 3 | Plan-and-Execute | 条件/依赖/多步骤 | OpenAI Chat Completions + tool_calls | `ExecutionPlan` (含 command 字段) | 远程规划 + 本地执行 |
| Layer 4 | 流式 Chat | 开放式对话、闲聊 | OpenAI Chat Completions (stream=true) | 文本流 + 可选 tool_calls | 远程 |

**远程优化策略**：
- 连接池 + Keep-Alive 复用 TCP
- 100ms 防抖窗口合并请求
- 2s 超时降级到本地规则或文本提示
- 常见意图响应缓存（LruCache）
- **隐私分级**：敏感数据（人脸/对话内容）强制本地；非敏感指令（天气/通用闲聊）允许远程

### 4.4 远程推理协议实现

远程推理使用标准 OpenAI Chat Completions API 格式：

**请求格式**：
```json
POST /v1/chat/completions
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "帮我优化这张照片"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "ai_optimize",
        "description": "AI 一键优化图片",
        "parameters": {
          "type": "object",
          "properties": {...},
          "required": [...],
          "additionalProperties": false
        }
      }
    }
  ],
  "tool_choice": "required",
  "stream": false
}
```

**响应格式（tool_calls）**：
```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_xxx",
        "type": "function",
        "function": {
          "name": "ai_optimize",
          "arguments": "{\"image_id\": \"img_123\"}"
        }
      }]
    }
  }]
}
```

**关键规则**：
- `tool_calls` 是 `message` 对象的独立字段，与 `content` 互斥
- 当存在 `tool_calls` 时，`content` 必须为 `null`
- 参数通过 `function.arguments` 传递，为标准 JSON 字符串

**langchain4j 标准化实现**：
```kotlin
class RemoteOrchestrator(config: RemoteModelConfig) {
    private val openAiChatModel = OpenAiChatModel.builder()  // :agent-core
        .baseUrl(config.baseUrl)
        .apiKey(config.apiKey)
        .modelName(config.modelId)
        .temperature(config.temperature)
        .maxTokens(config.maxTokens)
        .build()
    
    fun chat(request: ChatRequest): ChatResponse {
        // 直接使用 :agent-core OpenAiChatModel，支持 tool_calls、流式、多轮
    }
}
```

远程推理直接使用 `:agent-core OpenAiChatModel`，支持所有兼容 OpenAI API 的服务（DeepSeek、通义千问等）；通过 `AiServices` 代理构建器 + `ChatMemory` 实现多轮对话。

**命令解析（ToolCallCommandParser）**：
```kotlin
object ToolCallCommandParser {
    fun parse(request: ToolExecutionRequest, context: AgentContext): AgentCommand {
        val name = request.name()      // 工具名 → 命令类型映射
        val args = request.arguments() // 标准 JSON 参数
        
        return when (name) {
            "switch_filter" -> AgentCommand.SwitchFilter(filterType = parseFilterType(args))
            "adjust_beauty" -> AgentCommand.AdjustBeauty(settings = parseBeautySettings(args))
            // ... 其他命令
            else -> AgentCommand.TextReply("未知命令: $name")
        }
    }
}
```

远程解析器与本地解析器完全隔离：
- 远程：`ToolCallCommandParser` — 解析 `name` + `arguments` → `AgentCommand`
- 本地：`LocalCommandParser` — 解析 `method` + `params` → `AgentCommand`

### 4.5 DeepSeek 适配

| 适配项 | 实现 | 位置 |
|--------|------|------|
| 禁用 thinking | API 请求自动附加 `thinking: {"type": "disabled"}` | `OpenAiChatModel` 内部处理 |
| strict 模式兼容 | ToolSpec 自动添加 `additionalProperties: false` | `OpenAiChatModel` 内部处理 |
| tool_choice 修复 | `REQUIRED` 正确映射为 `"required"`（非 `"auto"`） | `OpenAiChatModel` 内部处理 |
| content 回退解析 | 当 API 未返回 tool_calls 但 content 含 tool_calls JSON 时，正则提取解析 | `RemoteOrchestrator.parseFallbackToolCalls()` |
| Prompt 规范 | 禁止在 Prompt 中提供具体 tool_calls JSON 示例，避免模型输出到 content | `RemotePromptBuilder` |

### 4.6 性能与成本考量

**Token 消耗估算**：

| 模式 | System Prompt | User Input | Output | 单次总 Token |
|------|--------------|-----------|--------|-------------|
| L1 Cache | 0 | 0 | 0 | 0 |
| L2 Batch | ~800 | ~50 | ~200 | ~1050 |
| L3 Plan | ~1000 | ~100 | ~500 | ~1600 |
| L4 Chat | ~800 | ~50 | ~300 | ~1150 |

**延迟估算**：

| 模式 | 网络 RTT | LLM 生成 | 解析 | 总延迟 |
|------|---------|---------|------|-------|
| L1 | 0 | 0 | 0 | < 10ms |
| L2 | 200-500ms | 200-500ms | 50ms | 450-1050ms |
| L3 | 200-500ms | 500-1000ms | 100ms | 800-1600ms |
| L4 (流式) | 200-500ms | 首 token 50-200ms | 50ms | 250-750ms |

**优化策略**：
1. **L1 缓存预热**：启动时预置 50+ 高频意图
2. **L2 默认化**：80% 场景走 L2，保持简单高效
3. **L3 异步执行**：计划生成后异步执行，不阻塞 UI
4. **L4 流式**：首 token 低延迟，提升对话体验
5. **连接池 + Keep-Alive**：复用 TCP 连接

---

## 5. 命令扩展

```kotlin
/**
 * Agent 命令 V2
 * 
 * 新增 Gallery、Settings、Navigation、Edit 相关命令
 */
sealed class AgentCommand {
    // ===== 相机命令（已有）=====
    data class AdjustBeauty(val settings: BeautySettings) : AgentCommand()
    data class SwitchFilter(val filterType: FilterType) : AgentCommand()
    // ... 其他相机命令
    
    // ===== Gallery 命令（新增）=====
    data class ViewMedia(val mediaId: String? = null) : AgentCommand()
    data class DeleteMedia(val mediaIds: List<String> = emptyList()) : AgentCommand()
    data class ShareMedia(val mediaIds: List<String> = emptyList()) : AgentCommand()
    data class SelectMedia(val mediaId: String, val selected: Boolean) : AgentCommand()
    data class SearchMedia(val query: String) : AgentCommand()
    data class SwitchViewMode(val mode: ViewMode) : AgentCommand()
    
    // ===== 设置命令（新增）=====
    data class ChangeTheme(val theme: ThemeMode) : AgentCommand()
    data class ChangeLanguage(val language: AppLanguage) : AgentCommand()
    data class DownloadModel(val modelId: String) : AgentCommand()
    data class SwitchFaceEngine(val engine: FaceDetectionEngineMode) : AgentCommand()
    data class ToggleSetting(val settingKey: String, val enabled: Boolean) : AgentCommand()
    
    // ===== 导航命令（新增）=====
    data class NavigateTo(val destination: String) : AgentCommand()
    object GoBack : AgentCommand()
    
    // ===== 编辑命令（新增）=====
    data class ApplyEdit(val editType: String, val params: Map<String, Any>) : AgentCommand()
    object SaveEdit : AgentCommand()
    object UndoEdit : AgentCommand()
    
    // ===== 通用命令 =====
    data class TextReply(val message: String) : AgentCommand()
    data class Unknown(val raw: String) : AgentCommand()
    data class Error(val reason: String) : AgentCommand()
}
```

### 5.1 功能覆盖矩阵

#### 当前已接入功能（已验证）

| 功能域 | 具体功能 | 命令类型 | Capability | 状态 |
|--------|----------|----------|------------|------|
| **相机控制** | 拍照 | `CapturePhoto` | CameraCapability | 已验证 |
| | 开始/停止录像 | `ToggleRecording` | CameraCapability | 已验证 |
| | 翻转摄像头 | `FlipCamera` | CameraCapability | 已验证 |
| | 变焦调节 | `AdjustZoom` | CameraCapability | 已验证 |
| | 曝光调节 | `AdjustExposure` | CameraCapability | 已验证 |
| | 切换拍摄模式 | `SwitchMode` | CameraCapability | 已验证 |
| **美颜** | 磨皮/美白调节 | `AdjustBeauty` | CameraCapability | 已验证 |
| | 瘦脸/大眼调节 | `AdjustBeauty` | CameraCapability | 已验证 |
| | 唇色/腮红调节 | `AdjustBeauty` | CameraCapability | 已验证 |
| **滤镜/风格** | 切换滤镜 | `SwitchFilter` | CameraCapability | 已验证 |
| | 切换风格特效 | `SwitchStyle` | CameraCapability | 已验证 |
| | 切换场景模式 | `SwitchScene` | CameraCapability | 已验证 |
| | 切换画幅比例 | `SwitchRatio` | CameraCapability | 已验证 |
| **对话** | 文本回复/聊天 | `TextReply` | CameraCapability | 已验证 |
| **远程控制** | 飞书消息处理 | 多种 | RemoteControlCapability | 开发中 |

#### V2 新增功能（开发中）

| 功能域 | 具体功能 | 命令类型 | Capability | 优先级 |
|--------|----------|----------|------------|--------|
| **Gallery** | 查看照片 | `ViewMedia` | GalleryCapability | P0 |
| | 删除照片 | `DeleteMedia` | GalleryCapability | P0 |
| | 分享照片 | `ShareMedia` | GalleryCapability | P1 |
| | 照片搜索 | `SearchMedia` | GalleryCapability | P2 |
| **设置** | 切换主题 | `ChangeTheme` | SettingsCapability | P1 |
| | 切换语言 | `ChangeLanguage` | SettingsCapability | P1 |
| **导航** | 切换页面 | `NavigateTo` | NavigationCapability | P0 |
| | 返回上一页 | `GoBack` | NavigationCapability | P0 |
| **编辑** | 进入编辑 | 预留 | EditorCapability | P2 |

---

## 6. 数据模型

### 6.1 AgentCommand 密封类

见 [第 5 章](#5-命令扩展)。

### 6.2 推理结果包装

```kotlin
sealed class InferenceResult {
    data class Local(val command: AgentCommand) : InferenceResult()
    data class Remote(val commands: List<AgentCommand>) : InferenceResult()
    data class Text(val message: String) : InferenceResult()
    data class Plan(val plan: ExecutionPlan) : InferenceResult()
    data class Error(val reason: String) : InferenceResult()
}
```

### 6.3 ExecutionPlan（L3 Plan 模式）

```json
{
  "plan_id": "uuid",
  "steps": [
    {
      "step": 1,
      "condition": "currentCamera == BACK",
      "command": {
        "name": "flip_camera",
        "arguments": "{}"
      },
      "description": "切换到前置摄像头"
    }
  ]
}
```

### 6.4 AiAgentUseCase（Facade）

```kotlin
class AiAgentUseCase(
    context: Context,
    agentMode: AiAgentMode = AiAgentMode.REMOTE, // 默认 REMOTE（远程优先）
    privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    localModelId: String = "qwen3_5_2b",
    remoteConfig: RemoteModelConfig? = null,
    forceRemote: Boolean = false
) {
    private val orchestrator = AgentOrchestrator(
        localPipeline = LocalInferencePipeline(...),
        remotePipeline = RemoteInferencePipeline(...)
    )
    
    suspend fun processInput(userInput: String, context: AgentContext): InferenceResult {
        return when (configurator.getAgentMode()) {
            AiAgentMode.LOCAL -> localPipeline.process(input)
            AiAgentMode.REMOTE -> remotePipeline.process(input)
            AiAgentMode.OFF -> Result.failure(AgentDisabledException())
        }
    }
}
```

### 6.5 IM 远程控制集成

飞书远程控制复用同一 `RemoteOrchestrator` 和 `RemoteInferencePipeline`：

```
飞书消息 → FeishuChannelHandler → RemoteCommandDispatcher
    → LLM 解析意图（复用 RemoteOrchestrator，独立 System Prompt）
    → CapabilityRegistry.dispatch()
    → 结果 → FeishuChannelHandler.sendMessage/sendImage
```

---

## 7. Agent 执行规约 (Execution Rules)

- **JSON 解析**: 必须使用 `kotlinx.serialization.json`，严禁正则提取字段
- **System Prompt**: 禁止硬编码在 `AgentOrchestrator` 内，需抽象为 `PromptBuilder` 策略接口
- **Capability 注册**: 新增 Capability 必须同步更新 `CapabilityRegistry` 的命令映射，禁止遗漏
- **Memory 持久化**: `appendConversation` 需引入内存缓存 + 批量刷盘，禁止每条消息 2 次 DataStore IO
- **ChatML 格式**: `LocalLlmEngine` 禁止硬编码 Qwen ChatML，需抽象 `ChatFormat` 接口按模型注册
- **线程安全**: `AgentOrchestrator` 的 `agentMode` / `currentModelId` 需同步控制，禁止并发修改
- **模型加载**: 快速连续调用需加并发锁，避免触发多次加载
- **隐私拦截**: `PrivacyGuard` 必须接入 LLM 输入输出流和 Capability 执行链路，禁止仅做断言
- **日志规范**: 统一使用 `PicMe:[Module]` 前缀，禁止各组件标签不一致
- **协议隔离**: 远程 tool_calls 与本地 method/params 必须彻底隔离，禁止混用

---

## 8. 常见陷阱检查清单 (Checklist)

- [ ] JSON 解析是否使用了正则？（必须用 kotlinx.serialization，正则无法处理嵌套/转义）
- [ ] System Prompt 是否硬编码在类内？（需按场景插件化，违反 OCP）
- [ ] 新增 AgentCommand 子类后是否同步更新了 CapabilityRegistry 的映射？（易遗漏）
- [ ] MemoryManager 是否存在 IO 放大问题？（每条消息 2 次 DataStore 读写需优化）
- [ ] ChatML 格式是否与模型耦合？（换模型如 Llama/Gemma 需改代码）
- [ ] CameraCapability 回调是否为 null？（11 个可选回调需 Builder 模式或统一接口）
- [ ] 模型加载是否有并发控制？（快速连续调用可能触发多次加载）
- [ ] PrivacyGuard 是否实际拦截了数据流？（当前仅断言，未接入 LLM 和 Capability）
- [ ] 用户可见文案是否硬编码中文？（需接入 strings.xml 支持多语言）
- [ ] AgentAction.Success 是否携带了语义冗余？（应携带执行结果数据，而非原命令）
- [ ] 远程 Prompt 是否包含 tool_calls JSON 示例？（会导致模型输出到 content 字段）
- [ ] content 字段是否处理了空字符串陷阱？（需使用 `isNotBlank()` 而非 `isNullOrEmpty()`）
- [ ] DeepSeek 请求是否禁用了 thinking 模式？（V4 系列必须禁用）

---

## 9. 验收标准

| ID | 验收项 | 优先级 |
|----|--------|--------|
| AC-1 | 远程模式下，"磨皮 60 然后拍照" 能解析为两个 tool_calls 并依次执行 | P0 |
| AC-2 | 远程模式下，"如果是后置就切前置再拍" 能正确执行条件判断 | P0 |
| AC-3 | 本地模式下，所有现有功能保持 100% 兼容 | P0 |
| AC-4 | L1 缓存命中率 > 60%（高频指令） | P1 |
| AC-5 | 远程推理平均延迟 < 1.5s | P1 |
| AC-6 | 支持对话式记忆（多轮上下文） | P2 |
| AC-7 | DeepSeek 模型 tool_calls 成功率 > 95% | P0 |
| AC-8 | 流式聊天首 token 延迟 < 500ms | P1 |

---

## 10. 远程推理任务拆分 [agent-task]

### Phase 1: 基础设施 (RD) — 已完成
- [x] `agent-task:remote-infra-001` 实现 `RemoteInferencePipeline`（标准 OpenAI 协议）
- [x] `agent-task:remote-infra-002` 引入 :agent-core `OpenAiChatModel` 标准化
- [x] `agent-task:remote-infra-003` 实现 `ToolCallCommandParser`（tool_calls → AgentCommand）
- [x] `agent-task:remote-infra-004` 删除 `InferenceRouter`、`AdaptiveStrategySelector` 等冗余组件

### Phase 2: L2 Batch 模式 (RD) — 已完成
- [x] `agent-task:remote-l2-001` 实现 `RemoteOrchestrator.processBatch()`（tool_calls 解析）
- [x] `agent-task:remote-l2-002` 设计 `RemotePromptBuilder`（ToolSpecification 格式）
- [x] `agent-task:remote-l2-003` UI 层适配批量命令串行执行

### Phase 3: L3 Plan 模式 (RD) — 已完成
- [x] `agent-task:remote-l3-001` 实现 `ExecutionEngine` 执行引擎
- [x] `agent-task:remote-l3-002` 更新 Plan 格式为标准 tool_calls（command 字段）
- [x] `agent-task:remote-l3-003` 条件求值器 (`evaluateCondition`)

### Phase 4: L4 流式 Chat (RD) — 已完成
- [x] `agent-task:remote-l4-001` 实现流式聊天（StreamingChatResponseHandler）
- [x] `agent-task:remote-l4-002` ChatMemory 历史管理（DataStoreChatMemoryStore）

### Phase 5: DeepSeek 适配 (RD) — 已完成
- [x] `agent-task:remote-ds-001` 禁用 thinking 模式
- [x] `agent-task:remote-ds-002` strict 模式兼容（additionalProperties: false）
- [x] `agent-task:remote-ds-003` content 回退解析（fallback tool_calls 提取）
- [x] `agent-task:remote-ds-004` Prompt 移除 tool_calls JSON 示例

### Phase 6: 集成与测试 (QA)
- [ ] `agent-task:remote-qa-001` 端到端测试（多指令、条件、降级）
- [ ] `agent-task:remote-qa-002` 性能基准（延迟、Token 消耗）
- [ ] `agent-task:remote-qa-003` DeepSeek 工具调用成功率测试

---

## 11. 架构演进路线图

```
┌─────────────────────────────────────────────────────────────────┐
│                        端侧推理演进路线                           │
├─────────────────────────────────────────────────────────────────┤
│  P0（已完成）:                                                    │
│  1. JSON 解析改用 kotlinx.serialization                         │
│  2. System Prompt 提取为 LocalPromptBuilder / RemotePromptBuilder │
│  3. MemoryManager 引入内存缓存 + 批量刷盘                         │
│  4. 本地/远程协议彻底分离（ADR-005）                              │
│  5. 远程推理引入 langchain4j 标准化                               │
│  6. DeepSeek 适配（thinking 禁用、strict 兼容）                   │
│  7. Sherpa-MNN 语音栈清理，迁至 Sherpa-ONNX                      │
│  8. 唤醒词引擎 Phase 1 完成（21 词 + 动态轮询 + VAD 稳定性）       │
│                                                                 │
│  P1（进行中）:                                                   │
│  9. KWS always-on 迁移（Sherpa-ONNX KWS，Phase 2）               │
│  10. 支持 Batch Function Calling（tool_calls 数组）               │
│  11. ChatFormat 抽象，支持多模型切换                              │
│  12. Capability 命令映射改为注解驱动或属性声明                      │
│  13. IM 远程控制（飞书 WebSocket）全链路打通                       │
│                                                                 │
│  P2（中期）:                                                    │
│  14. Plan-and-Execute 规则模板引擎（端侧不用 LLM 做规划）           │
│  15. Token 预算管理与上下文压缩                                    │
│  16. 隐私分级路由完善：敏感强制本地，非敏感允许远程                    │
│                                                                 │
│  P3（远期）:                                                    │
│  17. 记忆摘要（长期对话不丢失上下文）                               │
│  18. 多会话管理                                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 12. 附录：参考文档

- [AGENTS.md](../../AGENTS.md) — 顶层治理规则
- [FEATURES.md](../01-PRODUCT/FEATURES.md) — 功能交互细节
- [COMMAND_REFERENCE.md](../04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md) — 命令参考手册
- [AI_OPTIMIZATION.md](../03-TECHNICAL-SPECS/AI_OPTIMIZATION.md) — AI 一键优化
- [TAG_GENERATION.md](../03-TECHNICAL-SPECS/TAG_GENERATION.md) — TAG 生成
- [MNN_LLM_OPERATIONS.md](../03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md) — MNN LLM 运维
- [VOICE_STACK.md](../03-TECHNICAL-SPECS/VOICE_STACK.md) — 语音栈
- [IM_REMOTE_CONTROL_TECH_SPEC.md](../03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md) — IM 远程控制技术规范
- `runtime-core/src/main/java/com/mamba/picme/agent/core/` — 源码目录（Agent 编排层：AgentOrchestrator、CapabilityRegistry、PrivacyGuard、MemoryManager、SceneManager 等）
- `agent-core/src/main/java/com/mamba/` — 源码目录（Java 基础库：ChatModel、OpenAiChatModel、Tool、AiServices 等）
- `app/src/main/java/com/mamba/picme/domain/usecase/AiAgentUseCase.kt` — Facade 桥接层
