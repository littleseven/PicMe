# :shared 模块技术实现规范 (Shared KMP Module)

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:shared` KMP 模块的实现细节（target 结构、source set 分层、依赖方向、平台坑位）。
> - 顶层治理规则（全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`。

**模块定位**：`:shared` 是 PoLang Agent 编排层的 Kotlin Multiplatform 模块（Phase 4 自 `:runtime-core` 整体抽取，后者已于 2026-08-08 删除）。承载 Agent 编排、远程推理（Koog）、JS 引擎无关层、命令/能力模型、隐私守卫等**引擎无关逻辑**与**Android 平台实现**，供 `:androidApp` 消费；iOS target 为骨架级（Phase 5 扩展）。

**主要维护者**：项目开发者

**阅读对象**：项目开发者、AI Agent

---

## 1. Target 与 source set 结构

```
shared/src/
├── commonMain/    ← 引擎无关层（91 个 Kotlin 文件，见 §2）
├── androidMain/   ← Android 平台实现（VLM/语音/DataStore/dispatcher actual，12 文件）
├── iosMain/       ← iOS actual 骨架（AgentIdGenerator/DispatcherProvider/KoogHttpClientFactoryProvider）
├── jvmMain/       ← JVM actual（同 iosMain 三项）
├── commonTest/    ← 多平台测试（kotlin.test，21 文件）
└── jvmTest/       ← JVM-only 测试（@Tool 反射清单/prompt golden/守卫扫描，5 文件）
```

Gradle target：`android`（KMP android library 插件）+ `jvm()` + `iosX64()` + `iosArm64()` + `iosSimulatorArm64()`。

**分层规则**：

- **commonMain**：只依赖 Koog `koog-agents`（排除 `serialization-jackson`，jackson-module-kotlin 需 API 26，minSdk 24 下 D8 拒绝 dex）、kotlinx-coroutines/serialization/datetime。🔴 禁止 `import android.*` / `java.*`（iOS 编译会炸；Task 15 复验零泄漏）。
- **androidMain**：VLM 引擎（`inference/local/llm/`：LocalLlmEngine/MnnLlmClient/LlmModelManager）、语音（`platform/voice/`：SherpaOnnxAsrEngine/KeywordSpotterEngine/AudioRecorder）、DataStore 存储（`platform/storage/`：KoogMessageMemoryStore/MemoryManager）、`DispatcherProvider`/`AgentIdGenerator`/`KoogHttpClientFactoryProvider` actual。依赖 `:engines:mnn-core` + `:engines:agent-native`（VLM JNI `.so` 经 AAR 传递至 androidApp）。
- **jvmTest vs commonTest**：涉及 `@Tool` 元数据反射展开（`asToolsByClass`，Koog JVM-only API）、prompt 逐字节 golden、java.io 文件扫描（隐私守卫）的测试放 jvmTest；纯 common 逻辑放 commonTest（经 jvmTest 运行）。

## 2. commonMain 核心组件（`agent/core/`）

| 子包 | 内容 |
|------|------|
| `facade/` | `AgentOrchestrator`（initialize(AgentDependencies) + 无参 getInstance）、`AgentConfigurator`、`AgentDependencies`（9 字段注入契约）、`LocalModelService` |
| `inference/remote/` | `KoogChatAgent`/`KoogReActAgent`/`KoogReActStrategy`（koog/）、`ChatToolService`/`CameraToolService`/`ToolInventory`/`MemoryContextProvider`（tool/）、`RemotePromptBuilder`（prompt/）、`RemoteChatEngine`、`LlmCallRecorder` |
| `inference/local/` | `ImageInferenceEngine` 接口（端侧 VLM 抽象）、`LocalModelService` |
| `js/` | JS 引擎无关层（JsEngine/JsValue/JsBridge/JsRuntime/NativeHandler/BuiltInHandlers/GallerySummaryJs） |
| `runtime/` | `CapabilityRegistry`/`CommandExecutor`/`CrossPageCommandQueue`（capability/）、`PrivacyGuard`（policy/）、`SceneManager`（state/）、`ExecutionEngine`（execution/） |
| `model/` | `AgentCommands`/`CommandRisk`/`EditParams`（command/）、`AiAgentConfig`/`AiAgentMode`（config/）、`AgentContext`/`GallerySummary`/`SearchIntent`/`MediaAsset`（context/） |
| `platform/` | `DispatcherProvider`/`ChatMemoryStore`/`KoogMessageMemoryCodec`/`Logger`/`AsrEngine` 等接口与 expect |
| `remote/config/` | `RemoteModelFactory`/`RemoteModelConfig`/`KoogHttpClientFactoryProvider`（DeepSeek `thinking.type=disabled` 注入点） |
| `tool/` | `CameraToolHelper`、`perception/UiObservationFormatter` |

另有 `beauty/api/`（BeautySettings/FilterType/StyleFilter，供 beauty-api 经 `api(project(":shared"))` 透出）、`domain/`（UserPreferences/MediaRepository/StructuredFilter/DuplicateGroup/tag 聚类纯算法）。

## 3. 依赖方向

```
:androidApp ──→ :shared ──→ Koog（外部）/ kotlinx-*
                  └── androidMain ──→ :engines:mnn-core、:engines:agent-native（implementation）
:engines:beauty-api ──→ :shared（api，BeautySettings 等公开 API 面需要）
```

Android 组合根：`androidApp/agent/AndroidAgentComposition.kt`（平台实现唯一直构点，`AgentOrchestrator.initialize(AgentDependencies)` 注入；commonMain 无 `getInstance(context)` 旧签名）。

## 4. 编译与测试验证

```bash
# JVM 单测（commonTest + jvmTest 一起跑，107 用例 @2026-08-08）
JITPACK=true ./gradlew :shared:jvmTest

# 整体编译门槛（含 android AAR + iOS 三 target metadata，坑位④类问题只有这里能暴露）
JITPACK=true ./gradlew :shared:assemble

# Android 侧编译
./gradlew :shared:compileAndroidMain
```

## 5. 平台坑位（Phase 4 实证，后续改动必读）

1. **无 androidUnitTest source set**：KMP android library 插件（`com.android.kotlin.multiplatform.library`）不产生该 source set；Android 侧单测一律留 `:androidApp`（经 `:shared` 依赖解析符号），勿迁 shared。
2. **Android 编译任务名是 `:shared:compileAndroidMain`**（非传统 AGP 的 `compileDebugKotlinAndroid`）。
3. **无 `assembleDebug`**（KMP 单 variant）：整体验证用 `:shared:assemble`。
4. **commonMain 禁用裸 `@Volatile`**（kotlin.jvm 包不自动导入）：用 `@kotlin.concurrent.Volatile`；只有 `:shared:assemble`/metadata 编译能暴露此类问题，jvmTest/compileAndroidMain 发现不了——验证门槛必须含 `:shared:assemble`。
5. **构建一律加 `JITPACK=true`**：阿里云镜像对 Koog iOS metadata jar 间歇 404 且不穿透到 mavenCentral；settings.gradle.kts 内置开关走 google/mavenCentral/jitpack。

## 6. 编码约定

- `System.currentTimeMillis()` → `kotlin.time.Clock`（纯 stdlib，未用 kotlinx-datetime 做时间戳）。
- 单例 `synchronized` → `lazy(SYNCHRONIZED)`；共享可变状态 → 协程 `Mutex`（访问点 suspend 化）或 StateFlow。
- `t.javaClass.simpleName` → `t::class.simpleName ?: "unknown"`。
- 反射 `@Tool` 展开只能在 Android/JVM 侧（组合根 `asToolsByClass()`），commonMain 注入 `ToolRegistry`/描述清单。

---

> **维护者**：项目开发者
> **最后更新**：2026-08-08
> **状态**：生效中
