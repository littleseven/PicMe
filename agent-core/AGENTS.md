:agent-core 模块

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:agent-core` 模块的实现细节。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 模块改造历史详见 [`LANGCHAIN4J_MIGRATION.md`](./LANGCHAIN4J_MIGRATION.md)。

**模块定位**：Android Java Library，提供 LangChain4j 风格的 ChatModel、@Tool、AiServices、ChatMemory 等 API  
**主要维护者**：[RD] 全栈工程师  
**阅读对象**：RD、AI Agent  
**版本**：1.0  
**最后更新**：2026-07-06  
**状态**：生效中  

---

## 1. 模块概述

`:agent-core` 是 langchain4android 项目的 **基础库模块**（Java Android Library，非 Kotlin）。它将 langchain4j 的 `core`、`open-ai`、`http-client-okhttp` 三个模块合并为单个 Android Library，并做以下适配：

- 包名从 `dev.langchain4j` 迁移到 `com.mamba`
- 删除与 OpenAI 远程推理无关的模块（RAG、向量存储、分类、文档解析、链式调用等）
- 保留 OpenAI 远程推理所需核心类和接口
- 适配 Android 兼容性：`minSdk = 24`，移除 JDK 11+ 专属 API

---

## 2. 核心 API 清单

| API | 包路径 | 说明 |
|-----|--------|------|
| `ChatLanguageModel` | `com.mamba.model.chat` | 聊天模型接口 |
| `StreamingChatLanguageModel` | `com.mamba.model.chat` | 流式聊天模型接口 |
| `OpenAiChatModel` | `com.mamba.model.openai` | OpenAI 协议聊天模型实现 |
| `OpenAiStreamingChatModel` | `com.mamba.model.openai` | OpenAI 协议流式聊天模型实现 |
| `ToolSpecification` | `com.mamba.agent.tool` | Tool 规格定义 |
| `ChatMessage` / `AiMessage` / `SystemMessage` / `UserMessage` | `com.mamba.data.message` | 聊天消息类型 |
| `ServiceHelper` | `com.mamba.spi` | SPI 工厂加载工具 |
| `Json` / `JsonCodecFactory` | `com.mamba.internal` / `com.mamba.spi.json` | JSON 编解码 |

---

## 3. 关键约束

### 3.1 模块边界

- `:agent-core` **严禁引入 `:app` 或 `:runtime-core` 的业务类型**
- 仅保留通用模型接口、OpenAI 协议实现、HTTP 客户端、JSON 工具
- 业务相关的 `AgentCommand`、`Capability` 特化类型应在 `:app` 或 `:runtime-core` 中定义

### 3.2 公开 API 管理

- 新增公开类/接口必须补充到本 AGENTS.md 的「核心 API 清单」
- 删除公开 API 必须在 `LANGCHAIN4J_MIGRATION.md` 中记录
- ProGuard 规则有变化时同步更新 `consumer-rules.pro`

### 3.3 依赖管理

- JSON：Gson 为主，Jackson 保留用于 OpenAI DTO
- HTTP：OkHttp 4.12.0 + logging-interceptor + okhttp-sse
- 日志：SLF4J API（调用方桥接）
- Android Lifecycle：lifecycle-viewmodel-ktx、lifecycle-common、lifecycle-runtime-ktx

---

## 4. 使用方式

在 consumer 模块中引入：

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":agent-core"))
}
```

```java
// Java/Kotlin
import com.mamba.model.openai.OpenAiChatModel;

OpenAiChatModel chatModel = OpenAiChatModel.builder()
    .apiKey(apiKey)
    .modelName("gpt-4o")
    .build();

String response = chatModel.chat("Hello");
```

---

## 5. 相关文档

- [`LANGCHAIN4J_MIGRATION.md`](./LANGCHAIN4J_MIGRATION.md) — 模块合并与改造历史
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` — Agent 运行时架构
- `docs/03-TECHNICAL-SPECS/REMOTE_INFERENCE_ARCHITECTURE.md` — 远程推理架构

---

> **维护者**：RD Agent
> **最后更新**：2026-07-06
