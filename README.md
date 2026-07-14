<p align="center">
  <img src="https://jitpack.io/v/littleseven/langchain4android.svg" alt="JitPack">
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/minSdk-24-3DDC84" alt="Min SDK">
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
</p>

<h1 align="center">langchain4android</h1>

<p align="center">
  <b>Android 端侧 AI Agent 框架</b><br>
  <i>LangChain4j 风格 API · OpenAI 兼容协议 · 无 SPI 纯显式注入</i>
</p>

<p align="center">
  <a href="#-快速集成">快速集成</a> ·
  <a href="#-核心特性">特性</a> ·
  <a href="#-架构">架构</a> ·
  <a href="#-demo-工程-picme">Demo 工程</a> ·
  <a href="#-agent-first-研发范式">Agent 范式</a>
</p>

---

## 概览

**langchain4android** 是一个面向 Android 平台的 AI Agent 基础库，提供 LangChain4j 风格的 ChatModel / Tool / AiServices API，专为 Android 环境优化——无 SPI（ServiceLoader）、纯显式依赖注入、兼容 Java 标准反射。

库的核心是 `:agent-core` 模块，提供完整的 LLM 交互基础设施：ChatModel 抽象、OpenAI 兼容客户端、Tool 调用框架、ChatMemory 对话记忆、AiServices 代理构建器。开发者基于这些原语构建自己的 Agent 编排层。

> 本仓库同时包含一个接近生产级复杂度的 Demo 工程 **PoLang（破浪相册）**，用于验证框架在真实场景中的可行性。PoLang 的 Agent 编排层（AgentOrchestrator、CapabilityRegistry、PrivacyGuard 等）即基于 `:agent-core` 构建。

---

## 🚀 快速集成

### Step 1. 添加 JitPack 仓库

```groovy
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### Step 2. 添加依赖

```groovy
dependencies {
    implementation 'com.github.littleseven.langchain4android:agent-core:1.0.3'
}
```

### Step 3. 使用

```java
// 1. 创建 ChatModel
ChatModel model = OpenAiChatModel.builder()
    .baseUrl("https://api.openai.com/v1")
    .apiKey("your-api-key")
    .modelName("gpt-4o-mini")
    .build();

// 2. 直接调用
ChatResponse response = model.chat(ChatRequest.builder()
    .messages(UserMessage.from("你好"))
    .build());

// 3. 使用 AiServices 代理（LangChain4j 风格）
interface MyAssistant {
    @SystemMessage("你是一个友好的助手")
    String chat(@UserMessage String message);
}

MyAssistant assistant = AiServices.builder(MyAssistant.class)
    .chatLanguageModel(model)
    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
    .build();

String answer = assistant.chat("今天天气如何？");

// 4. 使用 Tool 调用
class WeatherTool {
    @Tool("查询天气")
    String getWeather(@P("城市") String city) {
        return city + "：晴，25°C";
    }
}

MyAssistant assistantWithTools = AiServices.builder(MyAssistant.class)
    .chatLanguageModel(model)
    .tools(new WeatherTool())
    .build();
```

---

## ✨ 核心特性

### 🤖 ChatModel 抽象

| 接口 | 说明 |
|------|------|
| `ChatModel` | 同步聊天模型，返回 `ChatResponse` |
| `StreamingChatModel` | 流式聊天模型，支持 SSE 实时输出 |
| `OpenAiChatModel` | OpenAI API 实现（兼容 DeepSeek、通义千问等） |
| `OpenAiStreamingChatModel` | OpenAI 流式实现 |

支持 `tool_calls`、`response_format`、`tool_choice`、`logprobs` 等完整 OpenAI API 参数。

### 🔌 Tool 调用框架

- `@Tool` 注解标记方法为可调用的工具
- `@P` 注解标记参数描述
- `ToolSpecification` 自动生成 JSON Schema
- `AiServices` 自动代理 Tool 调用与结果回填
- 支持 `ToolChoice`（auto / required / none）

### 🧠 对话记忆

- `ChatMemory` 接口 + `MessageWindowChatMemory` 实现
- 按 `memoryId` 多会话隔离
- `@MemoryId` 注解标记会话标识参数

### 📦 数据模型

- 完整消息类型：`UserMessage`、`AiMessage`、`SystemMessage`、`ToolExecutionResultMessage`
- `ChatRequest` / `ChatResponse` / `TokenUsage`
- `Embedding` 向量模型接口
- `Document` / `TextSegment` 文档处理

### 🔒 Android 优化

- **无 SPI**：不使用 `ServiceLoader` / `META-INF/services`，所有依赖通过 Builder 显式注入
- **OkHttp 客户端**：内置连接池、超时、重试
- **SSE 流式**：原生 Server-Sent Events 支持
- **coreLibraryDesugaring**：兼容 minSdk 24

---

## 🏗 架构

### agent-core 模块结构

`:agent-core` 是一个 **Java Android Library**，包结构如下：

```
com.mamba
├── model/
│   ├── chat/          # ChatModel / StreamingChatModel 接口
│   │   ├── request/   # ChatRequest, ToolChoice, ResponseFormat
│   │   ├── response/  # ChatResponse
│   │   └── listener/  # ChatModelListener
│   ├── openai/        # OpenAiChatModel / OpenAiStreamingChatModel 实现
│   │   └── internal/  # OpenAI API 请求/响应 DTO
│   ├── embedding/     # EmbeddingModel 接口
│   ├── image/         # ImageModel 接口
│   ├── language/      # LanguageModel 接口
│   └── moderation/    # ModerationModel 接口
├── data/
│   ├── message/       # AiMessage, UserMessage, SystemMessage, ToolExecutionResultMessage
│   ├── document/      # Document
│   ├── segment/       # TextSegment
│   └── embedding/     # Embedding
├── tool/              # @Tool, @P, ToolSpecification, ToolExecutionRequest
├── service/           # AiServices（LangChain4j 风格代理构建器）
├── memory/            # ChatMemory, MessageWindowChatMemory
├── client/
│   ├── okhttp/        # OkHttp 客户端封装
│   └── sse/           # Server-Sent Events 客户端
├── agent/
│   ├── agent/         # Agent 基础
│   ├── http/          # HTTP 工具
│   └── store/memory/  # InMemoryStore
└── spi/               # SPI 工厂接口（JSON codec, PromptTemplate 等）
```

### 与 Demo 工程的分层关系

```
┌─────────────────────────────────────────────────────────────────────┐
│  :app（PoLang Demo 工程 · Kotlin · Jetpack Compose）                  │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ domain/agent/     Agent 编排层（Kotlin）                       │  │
│  │  AgentOrchestrator  LocalInferencePipeline                    │  │
│  │  RemoteInferencePipeline  PrivacyGuard                        │  │
│  │  CapabilityRegistry  MemoryManager  SceneManager              │  │
│  │  AiAgentUseCase (Facade)                                      │  │
│  ├───────────────────────────────────────────────────────────────┤  │
│  │ features/         功能模块（Capability 实现）                   │  │
│  │  CameraCapability  GalleryCapability  SettingsCapability      │  │
│  │  NavigationCapability  SystemCapability  RemoteControlCapability│ │
│  └───────────────────────────────────────────────────────────────┘  │
│                            ↓ 使用                                    │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ :agent-core（Java Library · LLM 基础设施）                     │  │
│  │  ChatModel · OpenAiChatModel · StreamingChatModel              │  │
│  │  @Tool · ToolSpecification · AiServices                       │  │
│  │  ChatMemory · ChatRequest/Response · SSE Client               │  │
│  └───────────────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────────────┤
│  :beauty-api (Kotlin)  :beauty-engine (Android)  :runtime-core      │
└─────────────────────────────────────────────────────────────────────┘
```

### 关键设计决策

- **无 SPI 纯显式注入**：所有依赖（ChatModel、ChatMemory、Tools）通过 Builder 传入，不依赖 ServiceLoader
- **OpenAI 协议兼容**：`OpenAiChatModel` 支持所有兼容 OpenAI API 的服务（DeepSeek、通义千问、Moonshot 等）
- **DeepSeek 适配**：API 请求自动禁用 thinking 模式；ToolSpec 自动添加 `additionalProperties: false`；`tool_choice: REQUIRED` 正确映射
- **Android 兼容反射**：使用 Java 标准动态代理（`java.lang.reflect.Proxy`），避免 Android 不支持的 JVM 特性

---

## 🎮 Demo 工程：PoLang

**PoLang（破浪相册）** 是 langchain4android 的参考实现——一个接近生产级复杂度的 AI 智能相册应用，基于 `:agent-core` 构建了完整的 Agent 编排层。

### Demo 特性

| 功能 | 说明 |
|------|------|
| **自然语言交互** | "帮我把天空调蓝"、"找出去年夏天的照片"、"这张照片磨皮 50" |
| **自然语言相册搜索** | 规则解析 + MobileCLIP 语义召回 + 多维度 SQL 召回，全端侧执行 |
| **Agent 编排** | AgentOrchestrator + CapabilityRegistry + PrivacyGuard 完整架构 |
| **本地/远程双推理** | 本地 MNN-LLM（Qwen） + 远程 OpenAI 标准协议 |
| **自研美颜引擎** | 全自研 OpenGL ES + EGL 渲染管线 |
| **飞书远程控制** | 通过 IM + LLM 实现 App 远程控制 |

### 运行 Demo

```bash
git clone https://github.com/littleseven/langchain4android.git
cd langchain4android

# 构建 Demo APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/picme-debug.apk

# 一键开发闭环
./scripts/auto-dev-loop.sh
```

### 项目模块

| 模块 | 语言 | 说明 |
|------|------|------|
| `:agent-core` | **Java** | **框架核心** — ChatModel、Tool、AiServices、ChatMemory 等 LLM 基础设施 |
| `:app` | Kotlin | **PoLang Demo** — Agent 编排层 + 智能相册 UI，验证框架在真实场景中的可行性 |
| `:beauty-api` | Kotlin | 美颜接口契约层 |
| `:beauty-engine` | C++/Kotlin | 自研 GPU 美颜渲染引擎 |
| `:runtime-core` | C++/Kotlin | 运行时基础设施 |

---

## 📚 文档

| 层级 | 文档 | 内容 |
|------|------|------|
| **导航** | [`docs/00-INDEX.md`](docs/00-INDEX.md) | 完整文档导航索引 |
| **产品** | [`PRODUCT.md`](PRODUCT.md) | 产品定义、核心命题 |
| **架构** | [`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md) | Agent 架构设计 |
| **决策** | [`docs/02-ARCHITECTURE/ADR/`](docs/02-ARCHITECTURE/ADR/) | 架构决策记录（ADR-001 ~ ADR-007） |
| **技术规范** | [`docs/03-TECHNICAL-SPECS/`](docs/03-TECHNICAL-SPECS/) | 相册搜索、TAG 生成、美颜引擎、帧同步、人脸检测、远程推理 |
| **Agent 能力** | [`docs/04-AGENT-CAPABILITIES/`](docs/04-AGENT-CAPABILITIES/) | Capability 实现指南、命令参考 |
| **开发规范** | [`docs/05-DEVELOPMENT/`](docs/05-DEVELOPMENT/) | 工作流、CR 检查清单 |

---

## 🔬 Agent First 研发范式

本项目同时验证「Agent 能否主导软件研发全流程」——让 Agent 通过编排原子化 Tools，从辅助工具进化为研发主导力量。

### 四项架构原则

| 原则 | 效果 |
|------|------|
| **显式优于隐式** | 构造函数即文档，Agent 无需跨文件搜索即可理解组件协作 |
| **枚举优于条件** | Sealed Class 枚举合法状态，Agent 可枚举全部边界情况 |
| **自描述优于注释** | 类型系统即契约，Agent 靠类型推导而非易腐烂的注释 |
| **结构化可观测性** | 结构化事件日志，Agent 可消费、可诊断 |

### 实践关键发现

- **指令格式：自定义优于通用规范** — 端侧小模型对 OpenAI tool_calls 嵌套结构支持不稳定，自定义简洁 JSON（method + params 平铺）可靠性更高
- **GBNF 有限约束力** — 适合格式约束但非银弹，提示词工程仍是兜底主力
- **云端推理缓存** — IntentCache 机制一次成功永久受益，大幅降低延迟与 API 成本
- **多引擎资源隔离** — MNN/NCNN/MediaPipe 共存时 Vulkan/EGL 资源竞争是隐形崩溃源

### 度量指标

> 以下度量为实验性目标，当前基线待重新采集，不以未经验证的数字作为项目承诺。

| 指标 | 说明 |
|------|------|
| Agent 生成代码占比 | 目标 > 80% |
| Self-Heal 成功率 | 目标 > 85% |
| 文档-代码一致性 | 目标 > 98% |
| 人工介入频次 | 目标 < 10% |

---

## 🛠 自动化工具链

| 脚本 | 功能 |
|------|------|
| [`auto-dev-loop.sh`](scripts/auto-dev-loop.sh) | 编译 → 安装 → 截屏 → 日志 → 报告 |
| [`ai-gate.sh`](scripts/ai-gate.sh) | 代码质量门禁 |
| [`publish-mamba-agent.sh`](scripts/publish-mamba-agent.sh) | agent-core 发布到 JitPack |
| [`screenshot-diff.py`](scripts/screenshot-diff.py) | UI 回归像素级对比 |

## 📄 许可

MIT License — 研究、学习、二次开发均可自由使用。

---

<p align="center">
  <b>langchain4android</b> — 为 Android 提供 LangChain4j 风格的 AI Agent 基础设施
</p>
