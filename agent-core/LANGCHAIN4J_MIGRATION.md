# agent-core 模块：LangChain4j 合并改造记录

> 文档定位：技术留底，记录 agent-core 模块如何从 langchain4j 多模块合并为单模块，以及关键改动点。
> 创建时间：2026-06-19
> 关联模块：`:agent-core`

---

## 1. 改造目标

将 langchain4j 的以下三个模块合并为 PicMe 项目的单个 Android Library 模块 `:agent-core`：

| 原模块 | 来源 | 合并方式 |
|--------|------|----------|
| `langchain4j-core` | langchain4j 1.13.0 | 源码复制 + 精简 |
| `langchain4j-open-ai` | langchain4j 1.13.0 | 源码复制 |
| `langchain4j-http-client-okhttp` | langchain4j 1.13.0 | 源码复制 |

**包名迁移**：`dev.langchain4j` → `com.mamba`（去掉冗余的 `.agent` 层级）

---

## 2. 模块结构

```
agent-core/
├── build.gradle                          # Android Library 构建配置
├── consumer-rules.pro                    # ProGuard 规则
├── src/main/java/com/mamba/
│   ├── Experimental.java                 # @Experimental 注解
│   ├── Internal.java                     # @Internal 注解
│   ├── agent/
│   │   └── tool/                         # Tool 相关（ToolSpecification 等）
│   ├── data/
│   │   └── message/                      # ChatMessage 类型（AiMessage, SystemMessage 等）
│   ├── exception/
│   │   └── ...                           # 异常类（IllegalConfigurationException 等）
│   ├── http/
│   │   └── client/                       # HTTP 客户端抽象 + OkHttp 实现
│   │       ├── log/                      # 请求/响应日志
│   │       └── okhttp/                   # OkHttpClientBuilder 等
│   ├── internal/                         # 内部工具类
│   │   ├── Json.java                     # JSON 工具（Jackson/Gson 适配）
│   │   ├── JacksonJsonCodec.java         # Jackson JSON 编解码器
│   │   ├── JacksonToolSpecificationJsonCodec.java
│   │   ├── RetryUtils.java               # 重试逻辑
│   │   ├── Utils.java                    # 通用工具
│   │   └── ...
│   ├── model/                            # 模型接口与实现
│   │   ├── batch/                        # BatchPage, BatchPagination
│   │   ├── catalog/                      # ModelDescription, ModelType
│   │   ├── chat/                         # ChatModel, StreamingChatModel 接口
│   │   ├── embedding/                    # EmbeddingModel 接口
│   │   ├── image/                        # ImageModel 接口
│   │   ├── input/                        # Prompt, PromptTemplate
│   │   ├── language/                     # LanguageModel 接口
│   │   ├── moderation/                   # ModerationModel 接口
│   │   ├── openai/                       # OpenAI 实现（核心）
│   │   │   ├── OpenAiChatModel.java
│   │   │   ├── OpenAiStreamingChatModel.java
│   │   │   ├── OpenAiLanguageModel.java
│   │   │   ├── OpenAiStreamingLanguageModel.java
│   │   │   ├── OpenAiAudioTranscriptionModel.java
│   │   │   └── internal/                 # OpenAI DTO、Client、工具类
│   │   └── scoring/                      # ScoringModel 接口
│   └── spi/                              # ServiceLoader SPI 工厂
│       ├── ServiceHelper.java            # loadFactories 工具方法
│       ├── json/JsonCodecFactory.java
│       ├── prompt/PromptTemplateFactory.java
│       └── agent/tool/ToolSpecificationJsonCodecFactory.java
```

---

## 3. 关键改动清单

### 3.1 包名全局替换

- 使用 `sed` 将 `dev.langchain4j` 替换为 `com.mamba`
- 涉及文件：所有从 langchain4j 复制的 `.java` 文件

### 3.2 删除的非核心模块

以下 langchain4j-core 中的模块/目录被删除（与 OpenAI 远程推理无关）：

| 删除目录 | 原因 |
|----------|------|
| `rag/` | RAG（检索增强生成），当前不使用 |
| `store/` | 向量存储，当前不使用 |
| `classification/` | 文本分类，当前不使用 |
| `data/document/` | 文档解析，当前不使用 |
| `service/` | AiServices（代理生成），当前在 app 模块自行实现 |
| `memory/` | ChatMemory 接口，当前不使用 |
| `chain/` | 链式调用，当前不使用 |
| `invocation/` | 调用框架，当前不使用 |
| `embedding/`（实现）| 仅保留接口，删除本地实现 |
| `image/`（实现）| 仅保留接口，删除本地实现 |
| `moderation/`（实现）| 仅保留接口，删除本地实现 |
| `scoring/`（实现）| 仅保留接口，删除本地实现 |
| `tokenizer/` | 分词器，当前不使用 |
| `listener/` | 监听器框架，当前不使用 |
| `guardrail/` | 护栏机制，当前不使用 |
| `observability/` | 可观测性，当前不使用 |

### 3.3 删除的 OpenAI 相关文件

| 删除文件 | 原因 |
|----------|------|
| `OpenAiEmbeddingModel.java` | 当前不使用 Embedding |
| `OpenAiImageModel.java` | 当前不使用 Image 生成 |
| `OpenAiModerationModel.java` | 当前不使用 Moderation |
| `OpenAiModelBuilder.java` | 未使用 |
| `OpenAiChatModelBuilderFactory.java` | 未使用 |
| `OpenAiEmbeddingModelBuilderFactory.java` | 未使用 |
| `OpenAiImageModelBuilderFactory.java` | 未使用 |
| `OpenAiModerationModelBuilderFactory.java` | 未使用 |

### 3.4 新增/修复的文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `BatchPage.java` | 复制 | 从 langchain4j-core 复制，batch 分页 |
| `BatchPagination.java` | 复制 | 从 langchain4j-core 复制 |
| `ModelType.java` | 复制 | 从 langchain4j-core 复制，模型类型枚举 |
| `JacksonJsonCodec.java` | 复制 | 从 langchain4j-core 复制，JSON 编解码 |
| `JacksonToolSpecificationJsonCodec.java` | 复制 | 从 langchain4j-core 复制，Tool JSON 编解码 |
| `JsonCodecFactory.java` | 复制 | 从 langchain4j-core 复制，SPI 工厂 |
| `ToolSpecificationJsonCodecFactory.java` | 复制 | 从 langchain4j-core 复制，SPI 工厂 |
| `PromptTemplateFactory.java` | 复制 | 从 langchain4j-core 复制，SPI 工厂 |
| `ServiceHelper.java` | 复制 | 从 langchain4j-core 复制，ServiceLoader 工具 |
| `Json.java` | 修改 | 添加 `JsonCodecFactory` 导入，使用 `ServiceHelper.loadFactories` |
| `ToolSpecificationJsonUtils.java` | 修改 | 添加 `ToolSpecificationJsonCodecFactory` 导入，使用 `ServiceHelper.loadFactories` |
| `PromptTemplate.java` | 修改 | 添加 `PromptTemplateFactory` 导入 |
| `OpenAiChatModel.java` | 修改 | 添加 `loadFactories` 静态导入 |
| `OpenAiStreamingChatModel.java` | 修改 | 添加 `loadFactories` 静态导入 |
| `OpenAiLanguageModel.java` | 修改 | 添加 `loadFactories` 静态导入 |
| `OpenAiStreamingLanguageModel.java` | 修改 | 添加 `loadFactories` 静态导入 |
| `OpenAiAudioTranscriptionModel.java` | 修改 | 添加 `loadFactories` 静态导入 |
| `consumer-rules.pro` | 新增 | ProGuard 规则，保留公共 API |

### 3.5 编译修复记录

| 问题 | 修复方式 |
|------|----------|
| `loadFactories` 方法找不到 | 添加 `import static com.mamba.spi.ServiceHelper.loadFactories;` 或使用 `ServiceHelper.loadFactories(...)` |
| `BatchPage` 类找不到 | 从 langchain4j-core 复制 `BatchPage.java` 和 `BatchPagination.java` |
| `ModelType` 类找不到 | 从 langchain4j-core 复制 `ModelType.java` |
| `JacksonJsonCodec` 类找不到 | 从 langchain4j-core 复制 `JacksonJsonCodec.java` |
| `JacksonToolSpecificationJsonCodec` 类找不到 | 从 langchain4j-core 复制 `JacksonToolSpecificationJsonCodec.java` |
| `PromptTemplateFactory` 接口冲突 | `PromptTemplate.java` 导入 `spi.prompt.PromptTemplateFactory`，`DefaultPromptTemplateFactory` 实现该接口 |
| `consumer-rules.pro` 缺失 | 创建文件并添加 ProGuard 规则 |

---

## 4. 依赖配置（build.gradle）

```gradle
dependencies {
    // JSON 序列化（Gson 为主，Jackson 保留用于 OpenAI DTO）
    api 'com.google.code.gson:gson:2.11.0'
    api 'com.fasterxml.jackson.core:jackson-databind:2.14.3'
    api 'com.fasterxml.jackson.core:jackson-core:2.14.3'
    api 'com.fasterxml.jackson.core:jackson-annotations:2.14.3'

    // HTTP 客户端
    api 'com.squareup.okhttp3:okhttp:4.12.0'
    api 'com.squareup.okhttp3:logging-interceptor:4.12.0'
    api 'com.squareup.okhttp3:okhttp-sse:4.12.0'

    // 日志
    api 'org.slf4j:slf4j-api:2.0.16'

    // 空安全注解
    api 'org.jspecify:jspecify:1.0.0'

    // Android Lifecycle
    api 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7'
    api 'androidx.lifecycle:lifecycle-common:2.8.7'
    api 'androidx.lifecycle:lifecycle-runtime-ktx:2.8.7'

    // Room（可选）
    api 'androidx.room:room-runtime:2.6.1'
    api 'androidx.room:room-ktx:2.6.1'

    // Coroutines
    api 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0'
}
```

**已删除的依赖**：
- `opennlp-tools`（自然语言处理，当前不使用）
- `java.net.http.HttpClient`（JDK 11+，Android 不支持）

---

## 5. 使用方式

在 app 模块或其他模块中引入：

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":agent-core"))
}
```

```kotlin
// 使用 OpenAI ChatModel
import com.mamba.model.openai.OpenAiChatModel

val chatModel = OpenAiChatModel.builder()
    .apiKey(apiKey)
    .modelName("gpt-4o")
    .build()

val response = chatModel.chat("Hello")
```

---

## 6. 注意事项

1. **ServiceLoader 机制**：`:agent-core` 内部仍使用 `ServiceHelper.loadFactories()` 进行 SPI 加载，但所有 SPI 工厂类已合并到同一模块中。

2. **Jackson 依赖**：OpenAI DTO 的序列化/反序列化仍依赖 Jackson（`jackson-databind`），但项目其他部分优先使用 Gson。

3. **Android 兼容性**：`minSdk = 24`，已移除所有 JDK 11+ 专属 API（如 `java.net.http.HttpClient`）。

4. **精简原则**：当前仅保留 OpenAI 远程推理所需的核心类和接口，其他功能（Embedding、Image、Moderation 等）保留接口但删除实现，需要时可从 langchain4j 重新复制。

---

## 7. 后续维护

- 如需升级 langchain4j 版本，需重新执行以下步骤：
  1. 从新版 langchain4j 复制 `core`、`open-ai`、`http-client-okhttp` 源码
  2. 执行包名替换 `dev.langchain4j` → `com.mamba`
  3. 应用本记录的精简策略（删除非核心模块）
  4. 修复编译错误（参考第 3.5 节）
