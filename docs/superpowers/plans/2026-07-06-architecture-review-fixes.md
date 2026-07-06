# 架构审查修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复架构审查中发现的 P0/P1/P2 级问题，包括文档错位、依赖版本冲突、Native SO 冗余打包、API 泄露，以及最终拆分 `:mnn-core` 解除 `:beauty-engine -> :runtime-core` 反向依赖。

**Architecture:** 文档先行修正事实错误；然后通过 Gradle 依赖/打包配置修复依赖冲突与 SO 冗余；最后将共享的 MNN 资源管理能力下沉到新模块 `:mnn-core`，由 `:runtime-core` 和 `:beauty-engine` 共同依赖，彻底解除反向依赖。

**Tech Stack:** Gradle (Kotlin DSL), Android Library, CMake, NDK, Native SO packaging, Version Catalog

**Worktree:** `/Users/guoshuai/AndroidStudioProjects/langchain4android/.worktrees/arch-review-fixes-20260706`

---

## 执行顺序说明

按以下顺序串行执行。任务 8（拆分 `:mnn-core`）放在最后，因为它依赖前面 SO/依赖的清理结果。

1. Task 1: 修复文档错位
2. Task 2: 解决 `libonnxruntime.so` 冲突
3. Task 3: 删除未使用的 `libncnn.so`
4. Task 4: 统一依赖版本到 Version Catalog
5. Task 5: 锁定 NDK 版本
6. Task 6: 减少 `:agent-core` 的 `api` 泄露
7. Task 7: 补齐 `:sentencepiece` AGENTS.md
8. Task 8: 拆分 `:mnn-core` 并解除 `:beauty-engine -> :runtime-core`

---

## Task 1: 修复文档错位

**Files:**
- Modify: `runtime-core/AGENTS.md`
- Modify: `AGENTS.md`
- Modify: `app/AGENTS.md`
- Modify: `beauty-api/AGENTS.md`
- Modify: `agent-core/AGENTS.md`

**Context:**
- `runtime-core/AGENTS.md` 标题与内容完全错位，写成“Agent Core 模块”，并声称是纯 Kotlin / 零 Android 依赖。
- 顶层 `AGENTS.md:315` 错误声明 Agent 编排层在 `:app` 模块。
- `app/AGENTS.md:116-121` 依赖图漏掉 `:runtime-core`。
- `beauty-api/AGENTS.md:68-73` 错误把 `:agent-core` 列为 `FaceDetector` 消费者。
- `agent-core/AGENTS.md:33-34` API 名称过时（`ChatLanguageModel` 应为 `ChatModel`）。

- [ ] **Step 1: 重写 `runtime-core/AGENTS.md`**

  将文件开头改为：

  ```markdown
  # :runtime-core 模块

  > **边界声明（Boundary Statement）**
  > - 本文档仅承载 `:runtime-core` 模块的实现细节。
  > - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
  > - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。

  ## 模块定位

  `:runtime-core` 是 **PicMe Agent Runtime 核心模块**，为 Android Library（`com.android.library` + `kotlin-compose` 插件），承载 Agent 编排、本地/远程推理管道、Capability 注册、隐私策略、对话记忆、场景管理等能力。

  **插件类型**：`com.android.library` + `org.jetbrains.kotlin.plugin.compose`

  **语言**：Kotlin

  **关键职责**：
  - `AgentOrchestrator`：应用级 Agent 入口，管理本地/远程两条推理链路
  - `CapabilityRegistry`：Capability 注册、查询、命令分发
  - `PrivacyGuard`：输入内容隐私分级与本地优先策略
  - `MemoryManager`：对话历史管理
  - `SceneManager`：页面场景状态管理
  - `LocalInferencePipeline` / `RemoteInferencePipeline`：本地/远程推理管道
  - 语音交互（Sherpa-ONNX ASR / Keyword Spotter）
  - 本地 MNN LLM 推理 JNI（`libagent_native.so`）

  ## 依赖方向

  ```
  :runtime-core
      ├── :agent-core (api)
      ├── :beauty-api
      └── Sherpa-ONNX AAR (compileOnly)
  ```

  > 注意：`:runtime-core` **不**应被 `:beauty-engine` 依赖。共享的 MNN 资源管理能力由 `:mnn-core` 提供。

  ## 核心组件位置

  所有 Agent Runtime 组件位于 `runtime-core/src/main/java/com/mamba/picme/agent/core/` 下。

  ## 编译验证

  ```bash
  ./gradlew :runtime-core:assembleDebug
  ```
  ```

  保留原有文件中关于 ADR、历史移除记录等仍有价值的内容，或根据实现状态更新。

- [ ] **Step 2: 修正顶层 `AGENTS.md:315` 编排层位置**

  将 `AGENTS.md:315` 的引用从 `app/src/main/java/com/mamba/picme/domain/` 改为 `runtime-core/src/main/java/com/mamba/picme/agent/core/`。

  修改后文本：
  > - **Agent 编排层在 `:runtime-core` 模块**（Kotlin）：`AgentOrchestrator`、`CapabilityRegistry`、`PrivacyGuard`、`MemoryManager`、`SceneManager` 等均位于 `runtime-core/src/main/java/com/mamba/picme/agent/core/`

- [ ] **Step 3: 修正 `app/AGENTS.md` 依赖图**

  将 `app/AGENTS.md:116-121` 的依赖图改为：

  ```
  :app
   ├── :agent-core      ← 通过 :runtime-core 传递获得，LLM 基础 API
   ├── :runtime-core    ← Agent Runtime 核心（编排、推理、语音、远程）
   ├── :beauty-api      ← 美颜 API 契约
   ├── :beauty-engine   ← 美颜引擎实现
   └── :sentencepiece   ← SentencePiece tokenizer
  ```

- [ ] **Step 4: 修正 `beauty-api/AGENTS.md` 消费者列表**

  将 `beauty-api/AGENTS.md:68-73` 的模块依赖图改为：

  ```
  :app  ──────────────→ beauty-api ←────────────── :runtime-core
    │                       ↑                            │
    └──→ :beauty-engine ────┘                            │
             (实现 beauty-api 接口)                       │
             (消费 beauty-api 类型)                       │
  ```

  将 `beauty-api/AGENTS.md:79-81` 消费者表格中 `:agent-core` 一行删除，只保留 `:app` 和 `:beauty-engine`。

- [ ] **Step 5: 修正 `agent-core/AGENTS.md` API 名称**

  将 `agent-core/AGENTS.md:33-34` 的表格改为：

  | API | 包路径 | 说明 |
  |-----|--------|------|
  | `ChatModel` | `com.mamba.model.chat` | 聊天模型接口 |
  | `StreamingChatModel` | `com.mamba.model.chat` | 流式聊天模型接口 |

- [ ] **Step 6: 运行文档一致性校验**

  Run:
  ```bash
  grep -n "ChatLanguageModel\|StreamingChatLanguageModel" agent-core/AGENTS.md
  grep -n "Agent Core 模块\|java-library\|kotlin(\"jvm\")" runtime-core/AGENTS.md
  grep -n "app/src/main/java/com/mamba/picme/domain" AGENTS.md
  grep -n ":agent-core.*FaceDetector\|FaceDetectionResult" beauty-api/AGENTS.md
  grep -n ":runtime-core" app/AGENTS.md
  ```

  Expected: 上述错位描述均已消失或被正确替换。

- [ ] **Step 7: 提交**

  ```bash
  git add runtime-core/AGENTS.md AGENTS.md app/AGENTS.md beauty-api/AGENTS.md agent-core/AGENTS.md
  git commit -m "docs: fix module responsibility mismatches in AGENTS.md files"
  ```

---

## Task 2: 解决 `libonnxruntime.so` 冲突

**Files:**
- Modify: `app/build.gradle.kts`

**Context:**
`app/build.gradle.kts:151-162` 对 `libonnxruntime.so` 使用 `pickFirsts`，因为 `sherpa-onnx-1.13.3.aar` 与 `onnxruntime-android:1.24.3` 都携带该 SO。当前两个来源版本一致（1.24.3）。

⚠️ **方案调整**：原计划拟直接移除 `onnxruntime-android` 坐标，但 `:app` 中 `MobileClipOnnxBackend.kt` 和 `OpusMtTranslator.kt` 直接使用 `ai.onnxruntime.*` Java API，这些类由 `onnxruntime-android` 提供，Sherpa-ONNX AAR 并不 transitively 暴露。因此保留 `onnxruntime-android` 坐标，改为：
- 仅对实际使用的 ABI（`arm64-v8a`）保留 `pickFirsts`；
- 更新注释说明冲突原因与版本约束；
- 移除其他未使用 ABI 的 `pickFirsts` 声明。

- [ ] **Step 1: 精简 `pickFirsts` 并更新注释**

  将 `app/build.gradle.kts:151-162` 的 `packaging.jniLibs` 块改为：

  ```kotlin
  packaging {
      jniLibs {
          useLegacyPackaging = true
          // `:app` 直接使用 ONNX Runtime Java API（MobileCLIP / OPUS-MT），需要保留 `onnxruntime-android` 坐标。
          // Sherpa-ONNX AAR 同时内置同名 `libonnxruntime.so`，导致打包冲突。
          // 当前两个来源均为 ONNX Runtime 1.24.3，ABI 兼容；仅支持 arm64-v8a，故只保留该 ABI 的 pickFirst。
          // 升级任一依赖时，必须确保 `libonnxruntime.so` 版本一致，否则会出现 UnsatisfiedLinkError。
          pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
      }
      resources {
          excludes += "/META-INF/DEPENDENCIES"
          excludes += "/META-INF/LICENSE"
          excludes += "/META-INF/LICENSE.txt"
          excludes += "/META-INF/NOTICE"
          excludes += "/META-INF/NOTICE.txt"
      }
  }
  ```

- [ ] **Step 2: 编译验证**

  Run:
  ```bash
  ./gradlew :app:assembleDebug --no-daemon
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交**

  ```bash
  git add app/build.gradle.kts
  git commit -m "build(app): scope onnxruntime pickFirst to arm64-v8a and document dual-source constraint"
  ```

---

## Task 3: 删除未使用的 `libncnn.so`

**Files:**
- Delete: `beauty-engine/src/main/jniLibs/arm64-v8a/libncnn.so`
- Modify: `beauty-engine/AGENTS.md`（若其中声明 NCNN 已移除）

**Context:**
`beauty-engine/src/main/jniLibs/arm64-v8a/libncnn.so` 大小 81.3 MB（含 debug_info），代码与 CMake 均未引用，APK 中打包后占 9.8 MB。`beauty-engine/AGENTS.md` 已声明 NCNN 完全移除，但 SO 文件仍在。

- [ ] **Step 1: 删除 SO 文件**

  ```bash
  git rm beauty-engine/src/main/jniLibs/arm64-v8a/libncnn.so
  ```

- [ ] **Step 2: 确认 CMake 未引用 ncnn**

  Run:
  ```bash
  grep -R "ncnn" beauty-engine/src/main/cpp/ beauty-engine/build.gradle.kts || echo "No ncnn references found"
  ```

  Expected: 无引用。

- [ ] **Step 3: 编译验证**

  Run:
  ```bash
  ./gradlew :beauty-engine:assembleDebug --no-daemon
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

  ```bash
  git add beauty-engine/AGENTS.md  # 如已修改
  git commit -m "build(beauty-engine): remove unused libncnn.so to reduce APK/repo size"
  ```

---

## Task 4: 统一依赖版本到 Version Catalog

**Files:**
- Modify: `agent-core/build.gradle`
- Modify: `gradle/libs.versions.toml`
- Modify: `runtime-core/build.gradle.kts`（可选，移除重复 Jackson）

**Context:**
`:agent-core` 在 `build.gradle` 中硬编码了多个版本号，与 Version Catalog 不一致：
- OkHttp 4.12.0 vs catalog 4.10.0
- Kotlin Coroutines 1.9.0 vs catalog 1.10.2
- Room 2.6.1 vs catalog 2.7.0
- Gson 2.11.0（catalog 无定义）
- Jackson 2.14.3（catalog 无定义）
- SLF4J 2.0.16（catalog 无定义）
- JSpecify 1.0.0（catalog 无定义）
- Lifecycle 2.8.7（catalog 有定义）
- desugar_jdk_libs 2.1.4（catalog 无定义）

由于 `:agent-core` 是 Groovy DSL 的 `build.gradle`，不能直接引用 `libs.xxx`，但可以通过 `libs.findLibrary(...)` 或统一版本常量。为了保持模块独立（未来作为 JitPack 库发布），在 `agent-core/build.gradle` 中继续使用显式版本，但将版本号统一为与 catalog 一致，避免冲突。

版本统一策略：
- OkHttp: 4.10.0（与 catalog 一致）
- Coroutines: 1.10.2（与 catalog 一致）
- Room: 2.7.0（与 catalog 一致）
- Lifecycle: 2.8.7（catalog 已有）
- 其他无 catalog 定义的保留原版本（Gson 2.11.0、Jackson 2.14.3、SLF4J 2.0.16、JSpecify 1.0.0、desugar 2.1.4）

- [ ] **Step 1: 更新 `agent-core/build.gradle` 依赖版本**

  修改以下行：
  - `com.squareup.okhttp3:okhttp:4.12.0` → `4.10.0`
  - `com.squareup.okhttp3:logging-interceptor:4.12.0` → `4.10.0`
  - `com.squareup.okhttp3:okhttp-sse:4.12.0` → `4.10.0`
  - `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7` 保持
  - `androidx.lifecycle:lifecycle-common:2.8.7` 保持
  - `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` 保持
  - `androidx.room:room-runtime:2.6.1` → `2.7.0`
  - `androidx.room:room-ktx:2.6.1` → `2.7.0`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0` → `1.10.2`

- [ ] **Step 2: 移除 `runtime-core/build.gradle.kts` 重复 Jackson**

  删除 `runtime-core/build.gradle.kts:75-78`：

  ```kotlin
  // 强制降级 Jackson 到 2.14.3，避免 Android 上 Java 17 API 兼容问题
  api("com.fasterxml.jackson.core:jackson-databind:2.14.3")
  api("com.fasterxml.jackson.core:jackson-core:2.14.3")
  api("com.fasterxml.jackson.core:jackson-annotations:2.14.3")
  ```

  同时删除 `runtime-core/build.gradle.kts:47-52` 的 `resolutionStrategy` 中对 Jackson 的 force（因为唯一来源已是 `:agent-core` 的 2.14.3）。

- [ ] **Step 3: 编译验证**

  Run:
  ```bash
  ./gradlew :agent-core:assembleDebug :runtime-core:assembleDebug --no-daemon
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

  ```bash
  git add agent-core/build.gradle runtime-core/build.gradle.kts
  git commit -m "build: align agent-core and runtime-core dependency versions with catalog"
  ```

---

## Task 5: 锁定 NDK 版本

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `runtime-core/build.gradle.kts`
- Modify: `beauty-engine/build.gradle.kts`
- Modify: `sentencepiece/build.gradle.kts`

**Context:**
当前没有任何模块声明 `ndkVersion`，AGP 9.1.0 使用默认/最新可用 NDK。需要在所有包含 Native 构建的模块中显式锁定 NDK 版本。使用 `28.2.13676358`（当前常见稳定版本；若本地未安装，Gradle 会自动下载）。

- [ ] **Step 1: 为各模块添加 `ndkVersion`**

  在每个模块的 `android { }` 块中添加：

  ```kotlin
  android {
      ndkVersion = "28.2.13676358"
      ...
  }
  ```

  涉及文件：
  - `app/build.gradle.kts:60`
  - `runtime-core/build.gradle.kts:6`
  - `beauty-engine/build.gradle.kts:5`
  - `sentencepiece/build.gradle.kts:5`

- [ ] **Step 2: 验证 NDK 版本配置被识别**

  Run:
  ```bash
  ./gradlew :runtime-core:assembleDebug :beauty-engine:assembleDebug :sentencepiece:assembleDebug --no-daemon 2>&1 | tail -20
  ```

  Expected: `BUILD SUCCESSFUL`（Gradle 可能自动下载 NDK，耗时较长）。

- [ ] **Step 3: 提交**

  ```bash
  git add app/build.gradle.kts runtime-core/build.gradle.kts beauty-engine/build.gradle.kts sentencepiece/build.gradle.kts
  git commit -m "build: pin ndkVersion to 28.2.13676358 across native modules"
  ```

---

## Task 6: 减少 `:agent-core` 的 `api` 泄露

**Files:**
- Modify: `agent-core/build.gradle`

**Context:**
`:agent-core` 将大量依赖声明为 `api`，导致所有消费者被迫继承这些外部 API。应将非公开 API 的依赖改为 `implementation`，仅保留真正需要消费者可见的类型所在库为 `api`。

需要保留为 `api` 的：
- `org.jspecify:jspecify:1.0.0`（注解出现在公共 API 中）
- `org.slf4j:slf4j-api:2.0.16`（Logger 接口可能暴露）

应改为 `implementation` 的：
- `com.google.code.gson:gson:2.11.0`
- `com.squareup.okhttp3:okhttp:4.10.0`
- `com.squareup.okhttp3:logging-interceptor:4.10.0`
- `com.squareup.okhttp3:okhttp-sse:4.10.0`
- `com.fasterxml.jackson.core:jackson-databind:2.14.3`
- `com.fasterxml.jackson.core:jackson-core:2.14.3`
- `com.fasterxml.jackson.core:jackson-annotations:2.14.3`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7`
- `androidx.lifecycle:lifecycle-common:2.8.7`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`
- `androidx.room:room-runtime:2.7.0`
- `androidx.room:room-ktx:2.7.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`

- [ ] **Step 1: 将非必要 `api` 改为 `implementation`**

  修改 `agent-core/build.gradle:38-69`，仅保留 `org.jspecify` 和 `org.slf4j` 为 `api`，其余改为 `implementation`。

- [ ] **Step 2: 编译验证**

  Run:
  ```bash
  ./gradlew :agent-core:assembleDebug :runtime-core:assembleDebug :app:assembleDebug --no-daemon
  ```

  Expected: `BUILD SUCCESSFUL`。若 `:runtime-core` 或 `:app` 因依赖不可见而编译失败，说明它们此前依赖了 `:agent-core` 传递暴露的库，需要在对应模块中显式添加 `implementation`。

- [ ] **Step 3: 修复因 `api` 改 `implementation` 导致的编译错误（如有）**

  根据编译错误，在 `:runtime-core` 或 `:app` 的 `build.gradle.kts` 中补充缺失的 `implementation` 依赖。

- [ ] **Step 4: 提交**

  ```bash
  git add agent-core/build.gradle runtime-core/build.gradle.kts app/build.gradle.kts
  git commit -m "build(agent-core): convert transitive api dependencies to implementation"
  ```

---

## Task 7: 补齐 `:sentencepiece` AGENTS.md

**Files:**
- Create: `sentencepiece/AGENTS.md`

**Context:**
`:sentencepiece` 是唯一没有 `AGENTS.md` 的核心模块。需要补充治理文档，说明其职责、JNI 边界、消费者、版本与 Native 依赖。

- [ ] **Step 1: 创建 `sentencepiece/AGENTS.md`**

  内容：

  ```markdown
  # :sentencepiece 模块

  > **边界声明（Boundary Statement）**
  > - 本文档仅承载 `:sentencepiece` 模块的实现细节。
  > - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
  > - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。

  ## 模块定位

  `:sentencepiece` 是 **SentencePiece tokenizer 的 Android JNI 封装模块**，为 Android Library（`com.android.library` 插件）。

  它将 Google SentencePiece C++ 库编译为 `libsentencepiece_android.so`，并通过 JNI 暴露给 Kotlin/Java：
  - `SentencePieceProcessor`
  - `nativeLoadModel` / `nativeEncode` / `nativeEncodeAsPieces` / `nativeDecode`
  - `nativeVocabSize` / `nativeIdToPiece` / `nativePieceToId` / `nativeClose`

  ## 源码结构

  - Java/Kotlin JNI 封装：`sentencepiece/src/main/java/...`（如存在）
  - Native 源码与 CMake：`sentencepiece/src/main/cpp/`
  - CMake 目标：`libsentencepiece_android.so`
  - 预编译库目录：`sentencepiece/src/main/jniLibs/`（当前为空，SO 由 CMake 构建产出）

  ## 依赖方向

  ```
  :app
      └── :sentencepiece
  ```

  `:sentencepiece` 不依赖任何项目模块，仅依赖：
  - `androidx.core:core-ktx`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android`

  ## Native 构建约束

  - ABI：`arm64-v8a`
  - minSdk：24
  - STL：`c++_shared`
  - CMake：3.22.1
  - ndkVersion：28.2.13676358

  ## 编译验证

  ```bash
  ./gradlew :sentencepiece:assembleDebug
  ```

  ## 消费者

  - `:app`：OPUS-MT 翻译模型的本地分词/解码。
  ```

- [ ] **Step 2: 验证文件存在且格式正确**

  Run:
  ```bash
  ls -la sentencepiece/AGENTS.md
  head -20 sentencepiece/AGENTS.md
  ```

- [ ] **Step 3: 提交**

  ```bash
  git add sentencepiece/AGENTS.md
  git commit -m "docs(sentencepiece): add module AGENTS.md"
  ```

---

## Task 8: 拆分 `:mnn-core` 并解除 `:beauty-engine -> :runtime-core`

**Files:**
- Create: `mnn-core/build.gradle.kts`
- Create: `mnn-core/src/main/java/com/mamba/picme/mnn/MnnResourceManager.kt`
- Create: `mnn-core/src/main/java/com/mamba/picme/mnn/MnnGlobalReleaseLock.kt`
- Create: `mnn-core/src/main/jniLibs/arm64-v8a/.gitkeep`
- Create: `mnn-core/AGENTS.md`
- Modify: `settings.gradle.kts`
- Modify: `runtime-core/build.gradle.kts`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/inference/local/llm/MnnLlmClient.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/platform/storage/MemoryManager.kt`（如有 MNN 引用）
- Modify: `beauty-engine/build.gradle.kts`
- Modify: `beauty-engine/src/main/java/com/mamba/picme/beauty/internal/facedetect/mnn/MnnRoiDetector.kt`
- Modify: `beauty-engine/src/main/java/com/mamba/picme/beauty/internal/facedetect/mnn/MnnFaceDetector.kt`
- Modify: `beauty-engine/src/main/java/com/mamba/picme/beauty/internal/facedetect/mnn/MnnFaceEmbedder.kt`
- Modify: `runtime-core/src/main/jniLibs/arm64-v8a/`（将 `libMNN.so` 和 `libOpenCL.so` 移至 `mnn-core`）
- Modify: `runtime-core/src/main/cpp/CMakeLists.txt`
- Modify: `beauty-engine/src/main/cpp/CMakeLists.txt`
- Modify: `app/build.gradle.kts`（如有需要）

**Context:**
`:beauty-engine` 反向依赖 `:runtime-core` 仅为了使用 `MnnResourceManager` 和 `MnnGlobalReleaseLock`。这两个类以及 `libMNN.so`、`libOpenCL.so` 应该下沉到独立的 `:mnn-core` 模块。`:runtime-core` 和 `:beauty-engine` 都依赖 `:mnn-core`。

### 8.1 准备 `:mnn-core` 模块

- [ ] **Step 1: 创建 `mnn-core/build.gradle.kts`**

  ```kotlin
  plugins {
      alias(libs.plugins.android.library)
  }

  android {
      namespace = "com.mamba.picme.mnn"
      compileSdk = 36
      ndkVersion = "28.2.13676358"

      defaultConfig {
          minSdk = 24
          consumerProguardFiles("consumer-rules.pro")

          ndk {
              abiFilters += listOf("arm64-v8a")
          }
      }

      compileOptions {
          sourceCompatibility = JavaVersion.VERSION_17
          targetCompatibility = JavaVersion.VERSION_17
      }
  }

  dependencies {
      implementation(libs.androidx.core.ktx)
      implementation(libs.kotlinx.coroutines.android)
  }
  ```

- [ ] **Step 2: 创建 `mnn-core/src/main/java/com/mamba/picme/mnn/MnnResourceManager.kt`**

  将 `runtime-core/src/main/java/com/mamba/picme/agent/core/mnn/MnnResourceManager.kt` 的内容完整复制到新路径，并将包名改为 `com.mamba.picme.mnn`。

- [ ] **Step 3: 创建 `mnn-core/src/main/java/com/mamba/picme/mnn/MnnGlobalReleaseLock.kt`**

  将 `runtime-core/src/main/java/com/mamba/picme/agent/core/mnn/MnnGlobalReleaseLock.kt` 的内容完整复制到新路径，并将包名改为 `com.mamba.picme.mnn`。

- [ ] **Step 4: 移动 `libMNN.so` 和 `libOpenCL.so` 到 `:mnn-core`**

  ```bash
  mkdir -p mnn-core/src/main/jniLibs/arm64-v8a
  git mv runtime-core/src/main/jniLibs/arm64-v8a/libMNN.so mnn-core/src/main/jniLibs/arm64-v8a/libMNN.so
  git mv runtime-core/src/main/jniLibs/arm64-v8a/libOpenCL.so mnn-core/src/main/jniLibs/arm64-v8a/libOpenCL.so
  ```

- [ ] **Step 5: 创建 `mnn-core/AGENTS.md`**

  内容：

  ```markdown
  # :mnn-core 模块

  > **边界声明（Boundary Statement）**
  > - 本文档仅承载 `:mnn-core` 模块的实现细节。
  > - 顶层治理规则以根目录 `AGENTS.md` 为准。

  ## 模块定位

  `:mnn-core` 是 **MNN 推理运行时共享模块**，为 Android Library。它集中管理 MNN 预编译库（`libMNN.so`、`libOpenCL.so`）和 MNN 资源加载/释放锁，供 `:runtime-core`（本地 LLM）和 `:beauty-engine`（人脸检测/嵌入）共同依赖。

  该模块的独立避免了 `:beauty-engine` 因使用 MNN 而反向依赖 `:runtime-core`。

  ## 提供的 API

  - `MnnResourceManager`：MNN 模型资源路径管理
  - `MnnGlobalReleaseLock`：MNN 资源释放全局锁

  ## Native 库

  - `libMNN.so`
  - `libOpenCL.so`

  ## 依赖方向

  ```
  :runtime-core  ───→ :mnn-core ←─── :beauty-engine
  ```

  ## 编译验证

  ```bash
  ./gradlew :mnn-core:assembleDebug
  ```
  ```

### 8.2 更新 `:runtime-core`

- [ ] **Step 6: 修改 `runtime-core/build.gradle.kts` 依赖**

  将 `runtime-core/build.gradle.kts:55-58` 中的：

  ```kotlin
  implementation(project(":beauty-api"))
  implementation(libs.kotlinx.coroutines.android)
  ```

  改为：

  ```kotlin
  implementation(project(":beauty-api"))
  implementation(project(":mnn-core"))
  implementation(libs.kotlinx.coroutines.android)
  ```

- [ ] **Step 7: 删除 `runtime-core` 中的旧 MNN 资源文件**

  ```bash
  git rm runtime-core/src/main/java/com/mamba/picme/agent/core/mnn/MnnResourceManager.kt
  git rm runtime-core/src/main/java/com/mamba/picme/agent/core/mnn/MnnGlobalReleaseLock.kt
  rmdir runtime-core/src/main/java/com/mamba/picme/agent/core/mnn 2>/dev/null || true
  ```

- [ ] **Step 8: 更新 `runtime-core` 源码中的 import**

  查找并替换 `runtime-core/src/main/java/` 下所有引用 `com.mamba.picme.agent.core.mnn.MnnResourceManager` 和 `com.mamba.picme.agent.core.mnn.MnnGlobalReleaseLock` 的 import 为 `com.mamba.picme.mnn.MnnResourceManager` 和 `com.mamba.picme.mnn.MnnGlobalReleaseLock`。

  Run:
  ```bash
  grep -R "com\.mamba\.picme\.agent\.core\.mnn" runtime-core/src/main/java/ || echo "No old imports found"
  ```

- [ ] **Step 9: 更新 `runtime-core/src/main/cpp/CMakeLists.txt`**

  将其中对 `libMNN.so` 的 `IMPORTED_LOCATION` 路径从 `runtime-core/src/main/jniLibs/arm64-v8a/libMNN.so` 改为 `mnn-core/src/main/jniLibs/arm64-v8a/libMNN.so`。

  由于 CMake 跨模块引用路径复杂，更推荐在 `:mnn-core` 中通过 `exportedHeaders` 或 `prefab` 暴露，但为最小改动，可先在 `runtime-core` CMake 中通过相对路径 `../../mnn-core/src/main/jniLibs/arm64-v8a/libMNN.so` 引用。

  更推荐方案：让 `:mnn-core` 在 `sourceSets.main.jniLibs` 中暴露，`:runtime-core` 不再自己链接 MNN，而是依赖 `:mnn-core` 的 AAR 中打包的 SO。`:runtime-core` 的 CMake 中删除 `libMNN` target，改在 Gradle 依赖中通过 `:mnn-core` 传递。

  具体做法：
  - 删除 `runtime-core/src/main/cpp/CMakeLists.txt` 中关于 `libMNN` 的 `add_library(IMPORTED)` 和 `target_link_libraries` 引用。
  - 在 `runtime-core/build.gradle.kts` 中 `implementation(project(":mnn-core"))`，这样 `libMNN.so` 会自动打包到最终 APK。
  - `runtime-core` 的 C++ 代码若直接调用 MNN C API，需要头文件。暂时保留 MNN 头文件在 `runtime-core/src/main/cpp/` 中，或通过 `:mnn-core` 暴露。

  如果 `runtime-core` 的 `llm_jni_bridge.cpp` 直接链接 `libMNN.so`，则必须保留链接。此时可通过 `find_library` 在运行时由系统 linker 解析，CMake 中只需指定 `-lMNN`。

  简化方案：在 `runtime-core/src/main/cpp/CMakeLists.txt` 中，将：

  ```cmake
  set(MNN_LIB_PATH "${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libMNN.so")
  ```

  改为：

  ```cmake
  set(MNN_LIB_PATH "${CMAKE_SOURCE_DIR}/../../mnn-core/src/main/jniLibs/arm64-v8a/libMNN.so")
  ```

  同时确保 `libOpenCL.so` 路径同步修改。

### 8.3 更新 `:beauty-engine`

- [ ] **Step 10: 修改 `beauty-engine/build.gradle.kts` 依赖**

  将：

  ```kotlin
  dependencies {
      implementation(project(":beauty-api"))
      implementation(project(":runtime-core"))
      implementation(libs.androidx.core.ktx)
      implementation(libs.mediapipe.face.landmarker)
      implementation(libs.kotlinx.coroutines.android)
      ...
  }
  ```

  改为：

  ```kotlin
  dependencies {
      implementation(project(":beauty-api"))
      implementation(project(":mnn-core"))
      implementation(libs.androidx.core.ktx)
      implementation(libs.mediapipe.face.landmarker)
      implementation(libs.kotlinx.coroutines.android)
      ...
  }
  ```

- [ ] **Step 11: 更新 `beauty-engine` 源码中的 import**

  将 `beauty-engine/src/main/java/com/mamba/picme/beauty/internal/facedetect/mnn/MnnRoiDetector.kt`、`MnnFaceDetector.kt`、`MnnFaceEmbedder.kt` 中引用 `com.mamba.picme.agent.core.mnn.MnnResourceManager` / `MnnGlobalReleaseLock` 的 import 改为 `com.mamba.picme.mnn.MnnResourceManager` / `com.mamba.picme.mnn.MnnGlobalReleaseLock`。

- [ ] **Step 12: 更新 `beauty-engine/src/main/cpp/CMakeLists.txt`**

  类似 Step 9，将 `libMNN.so` 的引用路径改为指向 `mnn-core/src/main/jniLibs/arm64-v8a/libMNN.so`。

  同时，由于 `:beauty-engine` 不再自己打包 `libMNN.so`，需要排除重复。可在 `beauty-engine/build.gradle.kts` 的 `android.packaging.jniLibs` 中保留 `excludes += "lib/arm64-v8a/libMNN.so"` 以防万一，但更推荐让 `:mnn-core` 作为唯一来源。

  实际上因为 `:beauty-engine` 依赖 `:mnn-core` 后，`:mnn-core` 的 AAR 会携带 `libMNN.so`，`:beauty-engine` 自身不应再放置 `libMNN.so`。当前 `beauty-engine/src/main/jniLibs/` 下没有 `libMNN.so`（只有之前删除的 `libncnn.so`），所以无需额外排除。

### 8.4 更新项目设置

- [ ] **Step 13: 在 `settings.gradle.kts` 中注册 `:mnn-core`**

  在 `include(...)` 列表中加入 `"mnn-core"`。

- [ ] **Step 14: 更新 `app/build.gradle.kts`（如需要）**

  `:app` 已依赖 `:runtime-core` 和 `:beauty-engine`，`:mnn-core` 会通过 transitive 传递，通常无需显式声明。但为避免 `:app` 打包多份 `libMNN.so`，检查 `app/build.gradle.kts` 是否需要调整 `packaging.jniLibs.excludes`。

  由于 `:mnn-core` 是唯一来源，`:app` 无需额外排除。

### 8.5 验证

- [ ] **Step 15: 验证模块依赖图**

  Run:
  ```bash
  ./gradlew projects --no-daemon
  ```

  Expected: 项目列表包含 `:mnn-core`。

- [ ] **Step 16: 编译验证**

  Run:
  ```bash
  ./gradlew :mnn-core:assembleDebug :runtime-core:assembleDebug :beauty-engine:assembleDebug :app:assembleDebug --no-daemon
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 17: 验证 `:beauty-engine` 不再依赖 `:runtime-core`**

  Run:
  ```bash
  ./gradlew :beauty-engine:dependencies --configuration releaseRuntimeClasspath --no-daemon 2>&1 | grep "runtime-core" || echo "No runtime-core dependency"
  ```

  Expected: 无输出（即无 `:runtime-core` 依赖）。

- [ ] **Step 18: 提交**

  ```bash
  git add mnn-core/ settings.gradle.kts runtime-core/ beauty-engine/ app/build.gradle.kts
  git commit -m "build: extract :mnn-core module and remove beauty-engine -> runtime-core dependency"
  ```

---

## 最终验证（所有任务完成后）

- [ ] **Run full build**

  ```bash
  ./gradlew :app:assembleDebug --no-daemon
  ```

  Expected: `BUILD SUCCESSFUL`

- [ ] **Run architecture dependency check**

  ```bash
  ./gradlew :beauty-engine:dependencies --configuration releaseRuntimeClasspath --no-daemon 2>&1 | grep -E "runtime-core|mnn-core"
  ```

  Expected: 仅出现 `:mnn-core`，不再出现 `:runtime-core`。

- [ ] **Check git status for unexpected changes**

  ```bash
  git status --short
  ```

  Expected: 所有变更均为计划内文件。
