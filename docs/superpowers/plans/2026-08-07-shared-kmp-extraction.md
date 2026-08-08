# Phase 4：shared KMP 模块抽取 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **上游路线图**：`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` Phase 4。
> **核心输入（必读）**：`docs/superpowers/specs/2026-08-07-runtime-core-platform-coupling-inventory.md`（平台耦合点清单，本文档简称「清单」）。清单给出每个文件的 PURE / SEAM / ANDROID_ONLY 判定，本文档的迁移批次直接引用之。

**Goal:** 新建 `shared/` KMP 模块，将 runtime-core 引擎无关逻辑与 app 领域模型迁入 `commonMain`，Android 专有组件归位 `shared/androidMain` 或 `androidApp/`，最终删除 `:runtime-core`，Android 端零回归，shared JVM 单测覆盖核心逻辑。

**Architecture:** KMP 逻辑共享 + UI 双端原生。包名全线保留（`com.mamba.picme.*`），迁移用 `git mv` 保历史、零 import 变更。平台耦合收敛为约 4 个 expect 声明 + 一组注入式接口（非 expect），Android 实现为 actual/impl，iOS 实现属 Phase 5/6。Room/DataStore/Service/MediaStore 等 Android 组件不动，仅将其接口契约上提 commonMain。

**Tech Stack:** Kotlin 2.3.10 · AGP 9.1.0 · Koog 1.1.1（KMP，Phase 2.3 spike 已验证 iOS）· kotlinx-coroutines 1.10.2 · kotlinx-serialization 1.10.0 · kotlinx-datetime（新增）· kotlin.test

---

## 前置条件（开工前逐条核实，不满足则停）

- [ ] **P0** Phase 1 已合并：`git log main --oneline | grep -m1 "agent-core"` 可见 `1cbe9353`（删除 :agent-core）在 main 历史中；`agent-core/` 目录在 main 上不存在
- [ ] **P1** Phase 3 已完成：根目录存在 `androidApp/`、`engines/beauty-api/`、`engines/mnn-core/`、`engines/sentencepiece/`，不存在 `app/`、`beauty-api/`（根级）；`./gradlew assembleDebug` 绿
- [ ] **P2** 隔离工作区：`.worktrees/shared-kmp-extraction/` + 分支 `refactor/shared-kmp-extraction`（遵循 using-git-worktrees skill）
- [ ] **P3** 基线绿：`./gradlew :runtime-core:testDebugUnitTest`（23 个 JVM 测试）+ `./gradlew :androidApp:assembleDebug` + `./scripts/ai-gate.sh` 全过；安装冒烟（相机/相册/Chat/TAG）通过并记录
- [ ] **P4** 本计划所有路径按 Phase 3 后的结构书写：`androidApp/`（原 app/）、`engines/beauty-api/`（原 beauty-api/）、`runtime-core/`（Phase 3 故意留根级，本 Phase 消亡）。若 Phase 3 结果与此不符，先修正路径再执行

## 决策锁定（来自清单「关键架构发现」，不再重新讨论）

| # | 决策 | 依据 |
|---|------|------|
| D1 | expect 声明只设 4 个：`DispatcherProvider`、`AgentIdGenerator`、`createKoogHttpClientFactory`、（可选）`ModelFileProvider`；其余全部用**注入式接口**（非 expect） | 清单发现 #2：expect 越少，iosMain stub 越少 |
| D2 | 时间 API 统一 **kotlinx-datetime**（`Clock.System.now().toEpochMilliseconds()`），不设时间类 expect | 清单 ⑤⑦：`java.time`/`System.currentTimeMillis()` 共 5 处 |
| D3 | JSON 统一 **kotlinx.serialization**；进 commonMain 的文件禁 `org.json`（留 androidMain 的可保留） | 清单发现 #4 |
| D4 | JVM 阻塞并发（`future{}.get(timeout)`/`runBlocking`/`CountDownLatch`）进 commonMain 前必须改写为 `suspend` + `withTimeout` | 清单发现 #3；Koog @Tool 支持 suspend 函数 |
| D5 | Android 侧 Room 不动；`LlmCallRecord`/`LlmCallRecorder`/`CommandExecutionRecorder`/`JsRunRecorder` 纯契约上提即完成日志链路共享；iOS 存储 Phase 6 再定 | 清单 ①；零回归红线 |
| D6 | beauty-api 拆分线：`BeautySettings`/`FilterType`/`StyleFilter` 迁 commonMain（已核实 PURE）；`Face.kt`/`facedetect/*` 留 `engines/beauty-api`（Android） | 清单 ⑦ |
| D7 | 组合根（composition root）模式：facade 构造改为接口注入，Android 端 wiring 集中在 `androidApp/`；不设 `PlatformContext` expect | 清单 ⑦ Context 条目 |
| D8 | iOS target 从骨架第一天就声明（`iosX64/iosArm64/iosSimulatorArm64`），iosMain actual 给最小可用实现；voice/媒体/存储的 iOS 实现留 Phase 5/6（接口在 commonMain 即可，无需 actual） | 路线图 4.1/4.5；Phase 2.3 spike 已验证 iOS 消费链 |
| D9 | 迁移全程 Android 行为零变更：每批次迁移后 `:androidApp:assembleDebug` + 相关 JVM 测试必须绿；不改任何业务逻辑（D4 的 suspend 改写除外，其超时语义保持不变：5s/超时异常→TimeoutCancellationException） | 路线图出口标准 |

## 迁移地图（源 → 目标，详细判定见清单）

| 源（Phase 3 后路径） | 目标 | 批次 |
|----------------------|------|------|
| `runtime-core/.../model/**`（9 PURE + 3 SEAM） | `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/` | Task 5 |
| `runtime-core/.../runtime/**`（10 PURE）+ `capability/`（2 PURE） | `shared/src/commonMain/.../agent/core/{runtime,capability}/` | Task 5 |
| `runtime-core/.../inference/remote/**`（koog/react/log/prompt/tool） | `shared/src/commonMain/.../agent/core/inference/remote/` | Task 6–7 |
| `runtime-core/.../platform/logging/Logger.kt` | `shared/src/commonMain/.../platform/logging/` | Task 2 |
| `runtime-core/.../platform/thread/ThreadPoolManager.kt` | 拆为 expect `DispatcherProvider`（commonMain 声明 + androidMain actual） | Task 2 |
| `runtime-core/.../platform/storage/*` | commonMain `ChatMemoryStore` 接口 + androidMain DataStore actual | Task 8 |
| `runtime-core/.../facade/*` | `shared/src/commonMain/.../facade/`（接口注入化改造） | Task 9 |
| `runtime-core/.../js/*`（10 PURE + JsRuntime SEAM） | `shared/src/commonMain/.../js/` | Task 10 |
| `runtime-core/.../platform/voice/{AsrEngine,VadDetector}.kt` | `shared/src/commonMain/.../platform/voice/` | Task 11 |
| `runtime-core/.../platform/voice/{AudioRecorder,KeywordSpotterEngine,SherpaOnnxAsrEngine}.kt` | `shared/src/androidMain/kotlin/.../platform/voice/` | Task 11 |
| `runtime-core/.../inference/local/**`（VLM 引擎）+ `runtime-core/src/main/cpp/` | `shared/src/androidMain/`（含 CMake JNI） | Task 12 |
| `runtime-core/.../tool/accessibility/*`、`tool/perception/ViewHierarchyExtractor.kt`、`inference/remote/tool/RemoteControlToolService.kt` | `androidApp/src/main/java/com/mamba/picme/agent/`（包名不变，仅换模块） | Task 13 |
| `runtime-core/.../tool/{CameraToolHelper,perception/UiObservationFormatter}.kt` | `shared/src/commonMain/.../tool/` | Task 7 / Task 5 |
| `androidApp/.../domain/model/`（12 PURE）+ `domain/repository/{UserSettingsRepository,MediaRepository}.kt`（接口） | `shared/src/commonMain/kotlin/com/mamba/picme/domain/` | Task 3 |
| `androidApp/.../domain/tag/` 15 个 PURE 策略/配置/i18n/florence2 纯逻辑文件 | `shared/src/commonMain/.../domain/tag/` | Task 3 |
| `engines/beauty-api/.../api/{BeautySettings,FilterType,StyleFilter}.kt` | `shared/src/commonMain/kotlin/com/mamba/picme/beauty/api/` | Task 4 |
| `runtime-core/src/test/**`（23 个 JVM 测试，随被测类分批迁） | `shared/src/commonTest/`（kotlin.test 化）或 `shared/src/jvmTest/` | 随各批次 |

> **包名不变原则**：所有迁移保持原 package 声明与目录层级，仅换模块根。`git mv` 后不需要改任何 import（同 FQN），只需调整 Gradle 依赖。

---

## Task 1：shared 模块骨架与构建接入（路线图 4.1）✅ 已完成（2026-08-08，commit `89485605`，双审通过）

**Files:**
- Create: `shared/build.gradle.kts`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/shared/SharedPlaceholder.kt`
- Create: `shared/src/commonTest/kotlin/com/mamba/picme/shared/SharedPlaceholderTest.kt`
- Create: `shared/src/androidMain/AndroidManifest.xml`
- Create: `shared/src/iosMain/kotlin/.gitkeep`
- Modify: `settings.gradle.kts`（追加 include）
- Modify: `gradle/libs.versions.toml`（新增 kotlinx-datetime + KMP 插件别名）
- Modify: `androidApp/build.gradle.kts`（追加 shared 依赖）

- [x] **Step 1: 版本目录新增条目**

`gradle/libs.versions.toml` 在 `[versions]` 段追加：

```toml
kotlinxDatetime = "0.7.1"
```

`[libraries]` 段追加：

```toml
kotlinx-datetime = { group = "org.jetbrains.kotlinx", name = "kotlinx-datetime", version.ref = "kotlinxDatetime" }
```

`[plugins]` 段追加：

```toml
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
android-kmp-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
```

- [x] **Step 2: 验证 kotlinx-datetime 版本可解析**

Run: `./gradlew help --refresh-dependencies > /dev/null && curl -s "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml" | grep -o "<latest>[^<]*" | head -1`
Expected: 输出 `<latest>0.7.1` 或更高。若 `0.7.1` 不存在，改为 latest 显示的最新稳定版（不用 SNAPSHOT/RC）。

- [x] **Step 3: 创建 shared 模块构建脚本**

`shared/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidLibrary {
        namespace = "com.mamba.picme.shared"
        compileSdk = 36
        minSdk = 24
    }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.koog.agents)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // androidMain.dependencies / iosMain.dependencies 在后续 Task 按需追加
    }
}
```

> 注：`com.android.kotlin.multiplatform.library` 是 AGP 9 的 KMP Android 插件（替代旧 `androidTarget {}` 写法）。若执行时 AGP 9.1.0 不接受 `androidLibrary {}` 块，改用 `androidTarget { ... }` + `com.android.library` 旧式组合（二者不可混用），并在本计划勾选处记录实际用法。

`shared/src/commonMain/kotlin/com/mamba/picme/shared/SharedPlaceholder.kt`：

```kotlin
package com.mamba.picme.shared

/** 模块骨架冒烟占位，Task 2 第一批真实代码迁入后删除。 */
object SharedPlaceholder {
    fun ping(): String = "pong"
}
```

`shared/src/commonTest/kotlin/com/mamba/picme/shared/SharedPlaceholderTest.kt`：

```kotlin
package com.mamba.picme.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedPlaceholderTest {
    @Test
    fun pingReturnsPong() {
        assertEquals("pong", SharedPlaceholder.ping())
    }
}
```

`shared/src/androidMain/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

`shared/src/iosMain/kotlin/.gitkeep`：空文件。

- [x] **Step 4: 接入 settings 与 androidApp**

`settings.gradle.kts` 追加（保持字母序插入位置即可）：

```kotlin
include(":shared")
```

`androidApp/build.gradle.kts` 的 `dependencies {}` 内追加：

```kotlin
implementation(project(":shared"))
```

- [x] **Step 5: 三端构建 + JVM 测试验证骨架**

Run: `./gradlew :shared:jvmTest`
Expected: `BUILD SUCCESSFUL`，`SharedPlaceholderTest > pingReturnsPong PASSED`

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: `BUILD SUCCESSFUL`

Run: `./gradlew :shared:compileKotlinIosSimulatorArm64`
Expected: `BUILD SUCCESSFUL`（macOS + Xcode 环境；首次 Kotlin/Native 编译可能耗时数分钟，属一次性成本——Phase 2.3 spike 实测 Release 全量 3m54s）

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`（零回归：app 尚未消费 shared 任何类，仅多一个空依赖）

- [x] **Step 6: ai-gate 接线检查**

Run: `grep -n "runtime-core" scripts/ai-gate.sh`
Expected: 输出 ai-gate 中显式列举模块的行。若 ai-gate 按模块逐个跑测试，在同一位置追加 `:shared:jvmTest` 调用；若它全量跑 `./gradlew test`，无需改动。

- [x] **Step 7: Commit**

```bash
git add shared/ settings.gradle.kts gradle/libs.versions.toml androidApp/build.gradle.kts scripts/ai-gate.sh
git commit -m "feat(shared): Phase 4.1 KMP shared 模块骨架（android/jvm/ios 三 target + kotlinx-datetime）"
```

---

## Task 2：平台原语层（expect 声明 + Logger 迁移）✅ 已完成（2026-08-08，commit `6d0255b7` + 审查修复 `63eb17f1`，双审通过）

所有后续批次的公共地基。先建 expect 声明，后续迁移直接引用。

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/platform/thread/DispatcherProvider.kt`
- Create: `shared/src/androidMain/kotlin/com/mamba/picme/agent/core/platform/thread/DispatcherProvider.android.kt`
- Create: `shared/src/iosMain/kotlin/com/mamba/picme/agent/core/platform/thread/DispatcherProvider.ios.kt`
- Create: `shared/src/jvmMain/kotlin/com/mamba/picme/agent/core/platform/thread/DispatcherProvider.jvm.kt`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/AgentIdGenerator.kt`
- Create: `shared/src/androidMain/kotlin/com/mamba/picme/agent/core/model/context/AgentIdGenerator.android.kt`
- Create: `shared/src/iosMain/kotlin/com/mamba/picme/agent/core/model/context/AgentIdGenerator.ios.kt`
- Create: `shared/src/jvmMain/kotlin/com/mamba/picme/agent/core/model/context/AgentIdGenerator.jvm.kt`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/remote/config/KoogHttpClientFactoryProvider.kt`
- Create: `shared/src/androidMain/kotlin/com/mamba/picme/agent/core/remote/config/KoogHttpClientFactoryProvider.android.kt`
- Create: `shared/src/iosMain/kotlin/com/mamba/picme/agent/core/remote/config/KoogHttpClientFactoryProvider.ios.kt`
- Create: `shared/src/jvmMain/kotlin/com/mamba/picme/agent/core/remote/config/KoogHttpClientFactoryProvider.jvm.kt`
- Create: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/platform/thread/DispatcherProviderTest.kt`
- Move: `runtime-core/src/main/java/com/mamba/picme/agent/core/platform/logging/Logger.kt` → `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/platform/logging/Logger.kt`
- Delete: `shared/src/commonMain/kotlin/com/mamba/picme/shared/SharedPlaceholder.kt`（含测试）

- [x] **Step 1: expect `DispatcherProvider`（4 命名 dispatcher，语义与现 `ThreadPoolManager` 一致）**

commonMain 声明：

```kotlin
package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher

/** 平台命名 dispatcher 提供者。语义对齐旧 ThreadPoolManager 的 4 个隔离线程池。 */
expect class DispatcherProvider() {
    val ioDispatcher: CoroutineDispatcher
    val dataStoreDispatcher: CoroutineDispatcher
    val modelDispatcher: CoroutineDispatcher
    val orchestratorDispatcher: CoroutineDispatcher
    fun shutdown()
}
```

androidMain actual（逻辑原样搬自 `runtime-core/.../platform/thread/ThreadPoolManager.kt`，仅类名替换；迁移时以该文件实际内容为准逐行对应）：

```kotlin
package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

actual class DispatcherProvider actual constructor() {
    private val ioPool = Executors.newSingleThreadExecutor { r -> Thread(r, "PoLang-IO-Thread").apply { isDaemon = true } }
    private val dataStorePool = Executors.newSingleThreadExecutor { r -> Thread(r, "PoLang-DataStore-Thread").apply { isDaemon = true } }
    private val modelPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "PoLang-Model-Thread").apply { isDaemon = true } }
    private val orchestratorPool = Executors.newSingleThreadExecutor { r -> Thread(r, "PoLang-Orchestrator-Thread").apply { isDaemon = true } }

    actual val ioDispatcher: CoroutineDispatcher = ioPool.asCoroutineDispatcher()
    actual val dataStoreDispatcher: CoroutineDispatcher = dataStorePool.asCoroutineDispatcher()
    actual val modelDispatcher: CoroutineDispatcher = modelPool.asCoroutineDispatcher()
    actual val orchestratorDispatcher: CoroutineDispatcher = orchestratorPool.asCoroutineDispatcher()

    actual fun shutdown() {
        listOf(ioPool, dataStorePool, modelPool, orchestratorPool).forEach { it.shutdown() }
    }
}
```

> 迁移动作：执行时先 Read 现有 `ThreadPoolManager.kt` 逐字段核对线程池类型/数量/线程名，与上方骨架不一致处以现有文件为准修正 actual，然后**删除 runtime-core 中的 ThreadPoolManager.kt**，全局替换引用 `ThreadPoolManager.getInstance().xxxDispatcher` → 注入或单例的 `DispatcherProvider().xxxDispatcher`（现有调用点：`KoogMessageMemoryStore`、`MemoryManager`、`LocalModelService`、`AgentOrchestrator`，逐个 grep 确认）。

jvmMain actual：与 androidMain 完全相同（JVM 平台，供 `:shared:jvmTest` 使用）。

iosMain actual（最小可用，Phase 6 可按需细化隔离度）：

```kotlin
package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual class DispatcherProvider actual constructor() {
    actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
    actual val dataStoreDispatcher: CoroutineDispatcher = Dispatchers.Default
    actual val modelDispatcher: CoroutineDispatcher = Dispatchers.Default
    actual val orchestratorDispatcher: CoroutineDispatcher = Dispatchers.Default
    actual fun shutdown() { /* Dispatchers.Default 无需关闭 */ }
}
```

- [x] **Step 2: expect `AgentIdGenerator`（替代 `AgentModels.kt` 内的 JVM AtomicInteger）**

commonMain：

```kotlin
package com.mamba.picme.agent.core.model.context

/** Agent 会话 ID 生成器（进程内单调递增）。替代 java.util.concurrent.atomic.AtomicInteger。 */
expect object AgentIdGenerator {
    fun nextId(): Int
}
```

androidMain / jvmMain actual（内容相同）：

```kotlin
package com.mamba.picme.agent.core.model.context

import java.util.concurrent.atomic.AtomicInteger

actual object AgentIdGenerator {
    private val counter = AtomicInteger(1)
    actual fun nextId(): Int = counter.getAndIncrement()
}
```

iosMain actual：

```kotlin
package com.mamba.picme.agent.core.model.context

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

@OptIn(ExperimentalAtomicApi::class)
actual object AgentIdGenerator {
    private val counter = AtomicInt(1)
    actual fun nextId(): Int = counter.fetchAndIncrement()
}
```

> 注：`kotlin.concurrent.atomics` 在 Kotlin 2.1+ 提供；若 2.3.10 的 API 名与上面不一致（该包仍在演进），以编译器报错提示修正 import，语义保持「从 1 开始单调递增」。

- [x] **Step 3: expect `createKoogHttpClientFactory`（双端均显式构造，绕 Koog ServiceLoader 缺陷）**

commonMain：

```kotlin
package com.mamba.picme.agent.core.remote.config

import ai.koog.prompt.executor.clients.KoogHttpClient

/**
 * 创建 Koog HTTP 客户端工厂。双端均显式构造（绕开 Koog 1.1.1 ServiceLoader 缺陷，
 * 见路线图 Phase 1 已知坑与 Phase 2.3 spike iOS 验证结论）。
 * @param extraHeaders 注入到每个请求的附加头（如网关鉴权头）
 */
expect fun createKoogHttpClientFactory(extraHeaders: Map<String, String> = emptyMap()): KoogHttpClient.Factory
```

androidMain / iosMain / jvmMain actual（三端内容相同——迁移时以 `RemoteModelFactory.kt` 中现有 `HeaderInjectingHttpClientFactory` 实现为准，把它提升为 commonMain 内部类后在此引用）：

```kotlin
package com.mamba.picme.agent.core.remote.config

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.KoogHttpClient

actual fun createKoogHttpClientFactory(extraHeaders: Map<String, String>): KoogHttpClient.Factory =
    HeaderInjectingHttpClientFactory(KtorKoogHttpClient.Factory(), extraHeaders)
```

> 迁移动作：`RemoteModelFactory.kt` 内的 `HeaderInjectingHttpClientFactory`（现约 L157）若为纯 Koog API 实现（无 Android 依赖），原样移入 commonMain `KoogHttpClientFactoryProvider.kt` 同文件；若含平台依赖，先剥离再移。

- [x] **Step 4: Logger 迁移（PURE，git mv）**

```bash
mkdir -p shared/src/commonMain/kotlin/com/mamba/picme/agent/core/platform/logging
git mv runtime-core/src/main/java/com/mamba/picme/agent/core/platform/logging/Logger.kt \
       shared/src/commonMain/kotlin/com/mamba/picme/agent/core/platform/logging/Logger.kt
```

- [x] **Step 5: 删除占位文件 + 写第一个真正 commonTest**

```bash
git rm shared/src/commonMain/kotlin/com/mamba/picme/shared/SharedPlaceholder.kt \
       shared/src/commonTest/kotlin/com/mamba/picme/shared/SharedPlaceholderTest.kt
```

`shared/src/commonTest/kotlin/com/mamba/picme/agent/core/platform/thread/DispatcherProviderTest.kt`：

```kotlin
package com.mamba.picme.agent.core.platform.thread

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class DispatcherProviderTest {
    @Test
    fun providesFourDistinctDispatchers() {
        val provider = DispatcherProvider()
        assertIs<CoroutineDispatcher>(provider.ioDispatcher)
        assertNotSame(provider.ioDispatcher, provider.modelDispatcher)
        assertNotSame(provider.dataStoreDispatcher, provider.orchestratorDispatcher)
        provider.shutdown()
    }
}
```

- [x] **Step 6: 验证**

Run: `./gradlew :shared:jvmTest :shared:compileDebugKotlinAndroid :shared:compileKotlinIosSimulatorArm64`
Expected: `BUILD SUCCESSFUL`；`DispatcherProviderTest PASSED`

Run: `./gradlew :runtime-core:assembleDebug :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`（Logger 包名未变，引用方零改动；`ThreadPoolManager` 若本步已删，则其引用点替换必须同批完成，否则编译会红——红则按 Step 1 迁移动作清单补齐）

- [x] **Step 7: Commit**

```bash
git add shared/ runtime-core/ androidApp/
git commit -m "feat(shared): Phase 4 平台原语层——DispatcherProvider/AgentIdGenerator/KoogHttpClientFactory expect + Logger 迁移"
```

---

## Task 3：领域与网络层迁移（路线图 4.2 前半）

app 侧 27 个 PURE 文件 + 2 个领域接口迁入 shared。包名不变，纯搬家。

**Files（全部 git mv，源路径前缀 `androidApp/src/main/java/com/mamba/picme/`，目标前缀 `shared/src/commonMain/kotlin/com/mamba/picme/`）:**
- Move: `domain/model/` 11 个 PURE 文件（`AiAgentCommand.kt`、`ChatEditRecipeBuilder.kt`、`DuplicateGroup.kt`、`GalleryQuery.kt`、`LlmProviderConfig.kt`、`LogModuleConfig.kt`、`MediaGrouping.kt`、`MediaType.kt`、`RemoteChannelType.kt`、`StructuredFilter.kt`、`VoiceCommandMode.kt`）→ 同路径；第 12 个 `UserPreferences.kt` 因依赖 beauty-api 三类型推迟至 Task 4
- Move: `domain/repository/UserSettingsRepository.kt` → 同路径
- Move: `domain/tag/` 15 个 PURE 文件（`scan/ScanQueuePolicy.kt`、`scan/TagScanQuery.kt`、`scan/StreamingClusterAccumulator.kt`、`scan/DbscanRefinementPolicy.kt`、`TagCategory.kt`、`TaggerModelSelector.kt`、`ClusteringConfig.kt`、`MergeDecision.kt`、`ImageDescriptionStrategy.kt`、`i18n/LabelSinicizer.kt`、`i18n/TagTranslator.kt`、`prompt/DefaultTagPromptProvider.kt`、`prompt/TagPromptProvider.kt`、`florence2/Florence2Preprocess.kt`、`florence2/Florence2ResultParser.kt`）→ 同路径

- [ ] **Step 1: 迁移前纯净度复验（防止清单漂移）**

Run: `grep -rln "^import android\|^import java\.\|^import org\.json" androidApp/src/main/java/com/mamba/picme/domain/model/ androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt`
Expected: 无输出（若有输出，该文件从本批剔除并记录原因，不得带入 commonMain）

Run: `grep -rln "beauty\.api" androidApp/src/main/java/com/mamba/picme/domain/model/ androidApp/src/main/java/com/mamba/picme/domain/tag/`
Expected: 仅 `domain/model/UserPreferences.kt` 命中——它依赖的 beauty 三类型 Task 4 才进 commonMain，**本 Task 跳过，Task 4 Step 2 随迁**（其余命中文件同样剔除并记录）

Run: `for f in scan/ScanQueuePolicy.kt scan/TagScanQuery.kt scan/StreamingClusterAccumulator.kt scan/DbscanRefinementPolicy.kt TagCategory.kt TaggerModelSelector.kt ClusteringConfig.kt MergeDecision.kt ImageDescriptionStrategy.kt i18n/LabelSinicizer.kt i18n/TagTranslator.kt prompt/DefaultTagPromptProvider.kt prompt/TagPromptProvider.kt florence2/Florence2Preprocess.kt florence2/Florence2ResultParser.kt; do grep -l "^import android\|^import java\.\|^import org\.json" "androidApp/src/main/java/com/mamba/picme/domain/tag/$f"; done`
Expected: 无输出（同上规则）

- [ ] **Step 2: git mv 批量迁移（`UserPreferences.kt` 按 Step 1 结论明确排除）**

```bash
mkdir -p shared/src/commonMain/kotlin/com/mamba/picme/domain/model \
         shared/src/commonMain/kotlin/com/mamba/picme/domain/repository \
         shared/src/commonMain/kotlin/com/mamba/picme/domain/tag/{scan,i18n,prompt,florence2}

for f in AiAgentCommand.kt ChatEditRecipeBuilder.kt DuplicateGroup.kt GalleryQuery.kt \
         LlmProviderConfig.kt LogModuleConfig.kt MediaGrouping.kt MediaType.kt \
         RemoteChannelType.kt StructuredFilter.kt VoiceCommandMode.kt; do
  git mv "androidApp/src/main/java/com/mamba/picme/domain/model/$f" \
         "shared/src/commonMain/kotlin/com/mamba/picme/domain/model/$f"
done

git mv androidApp/src/main/java/com/mamba/picme/domain/repository/UserSettingsRepository.kt \
       shared/src/commonMain/kotlin/com/mamba/picme/domain/repository/

cd androidApp/src/main/java/com/mamba/picme/domain/tag
for f in scan/ScanQueuePolicy.kt scan/TagScanQuery.kt scan/StreamingClusterAccumulator.kt \
         scan/DbscanRefinementPolicy.kt TagCategory.kt TaggerModelSelector.kt ClusteringConfig.kt \
         MergeDecision.kt ImageDescriptionStrategy.kt i18n/LabelSinicizer.kt i18n/TagTranslator.kt \
         prompt/DefaultTagPromptProvider.kt prompt/TagPromptProvider.kt \
         florence2/Florence2Preprocess.kt florence2/Florence2ResultParser.kt; do
  git mv "$f" "../../../../../../../../../shared/src/commonMain/kotlin/com/mamba/picme/domain/tag/$f"
done
cd -
```

- [ ] **Step 3: 验证编译（app 引用零改动——包名未变）**

Run: `./gradlew :shared:compileDebugKotlinAndroid :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`。若 androidApp 编译报 `unresolved reference` 指向被迁类：说明该类有传递依赖未随迁，被依赖文件同批补迁（禁止在 androidApp 侧新建重复类型绕路）

- [ ] **Step 4: Commit**

```bash
git add shared/ androidApp/
git commit -m "refactor(shared): Phase 4.2 领域模型与 TAG 纯逻辑迁 commonMain（包名不变，git mv 保历史）"
```

---

## Task 4：beauty 纯类型迁移 + 相册访问能力接口（路线图 4.2 后半）

**Files:**
- Move: `engines/beauty-api/src/main/java/com/mamba/picme/beauty/api/BeautySettings.kt` → `shared/src/commonMain/kotlin/com/mamba/picme/beauty/api/BeautySettings.kt`
- Move: `engines/beauty-api/src/main/java/com/mamba/picme/beauty/api/FilterType.kt` → 同目录
- Move: `engines/beauty-api/src/main/java/com/mamba/picme/beauty/api/StyleFilter.kt` → 同目录
- Move: `androidApp/.../domain/model/UserPreferences.kt` → `shared/src/commonMain/kotlin/com/mamba/picme/domain/model/`（Task 3 推迟项）
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/domain/repository/AccessState.kt`
- Move（若存在）: `androidApp/.../domain/repository/MediaRepository.kt` → `shared/src/commonMain/kotlin/com/mamba/picme/domain/repository/MediaRepository.kt`
- Modify: `engines/beauty-api/build.gradle.kts`（追加 shared 依赖，供 Face/facedetect 继续引用同包类型）

- [ ] **Step 1: 纯净度终验（清单已核，迁移日再核一次防漂移）**

Run: `grep -n "^import" engines/beauty-api/src/main/java/com/mamba/picme/beauty/api/{BeautySettings,FilterType,StyleFilter}.kt`
Expected: 仅 Kotlin 标准库 / kotlinx import；无 `android.*`。若出现 android import，停止本 Task，回到清单修订拆分线

- [ ] **Step 2: git mv 三类型 + UserPreferences**

```bash
mkdir -p shared/src/commonMain/kotlin/com/mamba/picme/beauty/api
for f in BeautySettings.kt FilterType.kt StyleFilter.kt; do
  git mv "engines/beauty-api/src/main/java/com/mamba/picme/beauty/api/$f" \
         "shared/src/commonMain/kotlin/com/mamba/picme/beauty/api/$f"
done
git mv androidApp/src/main/java/com/mamba/picme/domain/model/UserPreferences.kt \
       shared/src/commonMain/kotlin/com/mamba/picme/domain/model/UserPreferences.kt
```

- [ ] **Step 3: beauty-api 模块接 shared 依赖**

`engines/beauty-api/build.gradle.kts` 的 `dependencies {}` 追加：

```kotlin
implementation(project(":shared"))
```

> beauty-api 其余文件（`Face.kt`/`facedetect/*`/`BeautyProcessor.kt` 等）与迁走的三类型同包（`com.mamba.picme.beauty.api`），FQN 不变，加上模块依赖后引用继续解析。

- [ ] **Step 4: `AccessState` 密封枚举（双端相册权限范式抽象，路线图 4.2 指定交付物）**

`shared/src/commonMain/kotlin/com/mamba/picme/domain/repository/AccessState.kt`：

```kotlin
package com.mamba.picme.domain.repository

/**
 * 相册访问授权状态（双端范式统一抽象）。
 * Android: Photo Picker / READ_MEDIA_IMAGES；iOS: Full / Limited / AddOnly。
 * 权限请求流程留各端 UI 层，shared 只消费状态。
 */
sealed interface AccessState {
    /** 完整访问（Android 授权 / iOS Full Access） */
    data object Full : AccessState

    /** 受限访问（iOS Limited Access / Android 部分照片授权） */
    data object Limited : AccessState

    /** 已拒绝 */
    data object Denied : AccessState

    /** 仅可添加（iOS AddOnly，Android 无此态） */
    data object AddOnly : AccessState
}
```

- [ ] **Step 5: MediaRepository 接口迁移（即路线图 4.2 的「PhotoLibraryProvider」角色）**

> **命名决策**：路线图 4.2 写「相册访问抽象为能力接口 `PhotoLibraryProvider` + `AccessState`」。app 侧已存在 `MediaRepository` 领域接口承担同一角色，直接复用并追加 `AccessState`，不新造 `PhotoLibraryProvider` 类型（避免双接口并存的概念漂移）。执行时若发现 `MediaRepository` 接口方法面与「相册提供」语义差距过大，再按路线图原名新建。

Run: `ls androidApp/src/main/java/com/mamba/picme/domain/repository/`
Expected: 确认 `MediaRepository.kt`（接口）存在。

- 若存在：`git mv` 到 `shared/src/commonMain/kotlin/com/mamba/picme/domain/repository/`，并将其 import 的 Android 类型逐一替换为领域类型（接口若有 `android.net.Uri` 参数，改为 `String`（uri 字面值）——逐个方法签名处理，**修改接口签名时同步修改 `MediaRepositoryImpl` 与全部调用点**，此为本 Task 唯一允许的非机械改动）
- 在接口中追加 `val accessState: kotlinx.coroutines.flow.Flow<AccessState>` 声明；`MediaRepositoryImpl` 的 Android 实现：有 `READ_MEDIA_IMAGES`→`Full`，`READ_MEDIA_VISUAL_USER_SELECTED`→`Limited`，否则→`Denied`（`AddOnly` 仅 iOS）

- [ ] **Step 6: 验证**

Run: `./gradlew :shared:jvmTest :shared:compileKotlinIosSimulatorArm64 :engines:beauty-api:assembleDebug :androidApp:assembleDebug`
Expected: 全部 `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add shared/ engines/beauty-api/ androidApp/
git commit -m "refactor(shared): Phase 4.2 beauty 纯类型 + AccessState/MediaRepository 相册能力接口迁 commonMain"
```

---

## Task 5：model/ + runtime/ + capability/ 编排核心迁移（路线图 4.3 前半）

runtime-core 最纯净的一批：19 个 PURE + 3 个 SEAM。SEAM 的解锁动作（EditParams/AgentIdGenerator/AgentCommands）在本批内完成。

**Files（源前缀 `runtime-core/src/main/java/com/mamba/picme/agent/core/`，目标前缀 `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/`）:**
- Move: `model/command/CommandRisk.kt`、`model/command/FeedbackAction.kt`、`model/config/AiAgentConfig.kt`、`model/context/GallerySummary.kt`、`model/context/MediaAsset.kt`、`model/context/PageContext.kt`、`model/context/SceneContext.kt`、`model/context/SearchIntent.kt`、`model/plan/ExecutionPlan.kt`
- Move: `model/command/EditParams.kt`（SEAM，Step 2 改造）
- Move: `model/command/AgentCommands.kt`（SEAM，beauty 类型已在 Task 4 就位，随迁即可）
- Move: `model/context/AgentModels.kt`（SEAM，Step 3 改造）
- Move: `runtime/capability/{CapabilityRegistry,CommandExecutionRecorder,CommandExecutor,CrossPageCommandQueue}.kt`
- Move: `runtime/execution/{ExecutionEngine,ExecutionReporter,ExecutionState,InferenceResult}.kt`
- Move: `runtime/policy/PrivacyGuard.kt`、`runtime/state/SceneManager.kt`
- Move: `capability/{Capability,FaceDetectionProvider}.kt`
- Move: `tool/perception/UiObservationFormatter.kt`（+ 测试 `runtime-core/src/test/.../UiObservationFormatterTest.kt` → `shared/src/commonTest/`，JUnit4→kotlin.test）
- Move: 对应单测 `runtime-core/src/test/.../model/command/{EditParamsTest,MemorySummaryTest,AgentCommandsFeedbackTest,CommandRiskTest}.kt` 等 → `shared/src/commonTest/` 同包路径（JUnit4→kotlin.test 改写：`org.junit.Test`→`kotlin.test.Test`，`org.junit.Assert.*`→`kotlin.test.assert*`）

- [ ] **Step 1: PURE 批 git mv（19 文件）**

```bash
SRC=runtime-core/src/main/java/com/mamba/picme/agent/core
DST=shared/src/commonMain/kotlin/com/mamba/picme/agent/core
mkdir -p $DST/model/{command,config,context,plan} $DST/runtime/{capability,execution,policy,state} $DST/capability $DST/tool/perception

for f in model/command/CommandRisk.kt model/command/FeedbackAction.kt model/config/AiAgentConfig.kt \
         model/context/GallerySummary.kt model/context/MediaAsset.kt model/context/PageContext.kt \
         model/context/SceneContext.kt model/context/SearchIntent.kt model/plan/ExecutionPlan.kt \
         model/command/AgentCommands.kt \
         runtime/capability/CapabilityRegistry.kt runtime/capability/CommandExecutionRecorder.kt \
         runtime/capability/CommandExecutor.kt runtime/capability/CrossPageCommandQueue.kt \
         runtime/execution/ExecutionEngine.kt runtime/execution/ExecutionReporter.kt \
         runtime/execution/ExecutionState.kt runtime/execution/InferenceResult.kt \
         runtime/policy/PrivacyGuard.kt runtime/state/SceneManager.kt \
         capability/Capability.kt capability/FaceDetectionProvider.kt \
         tool/perception/UiObservationFormatter.kt; do
  git mv "$SRC/$f" "$DST/$f"
done
```

- [ ] **Step 2: `EditParams` 去 org.json（D3）**

`git mv` `model/command/EditParams.kt` 后，改造其 `fromJson`（现约 L43-71，参数 `org.json.JSONObject`，用 `opt/optDouble` 逐字段读取）：

```kotlin
// 改前签名：fun fromJson(obj: org.json.JSONObject): EditParams
// 改后签名 + 实现（kotlinx.serialization，容错语义对齐 org.json.opt*：缺字段用默认值）
fun fromJson(jsonString: String): EditParams {
    val obj = Json.parseToJsonElement(jsonString).jsonObject
    // 逐字段：obj["smooth"]?.jsonPrimitive?.intOrNull ?: 默认值
    // 字段清单与默认值以现有 fromJson 实现为准逐行对应，不得遗漏
}
```

同步修改调用方（`ChatToolService.kt:228` 等处，`JSONObject(edits)` → 直接传 `edits` 字符串）。

- [ ] **Step 3: `AgentModels.kt` 去 AtomicInteger（Task 2 产物替换）**

`git mv` 后：删除文件内 `object AgentIdGenerator`（约 L112-117，`AtomicInteger` 实现）与 `import java.util.concurrent.atomic.AtomicInteger`，引用点改用 Task 2 的 `expect object AgentIdGenerator`（同包 `model.context`，FQN 相同则零调用点变更）。

- [ ] **Step 4: 测试迁移与 kotlin.test 化**

对每个随迁测试文件执行：`git mv` 到 `shared/src/commonTest/kotlin/<同包路径>`，然后：

```kotlin
// import 替换
import org.junit.Test              → import kotlin.test.Test
import org.junit.Assert.assertEquals → import kotlin.test.assertEquals
// assertTrue/assertFalse/assertNull/assertNotNull 等同理
// @Test(expected = X::class) → assertFailsWith<X> { ... }
```

- [ ] **Step 5: 验证**

Run: `./gradlew :shared:jvmTest`
Expected: `BUILD SUCCESSFUL`，随迁测试全部 PASSED（数量 ≥ 迁移前 runtime-core 对应测试数）

Run: `./gradlew :runtime-core:assembleDebug :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`（包名不变零 import 改动；若报 unresolved，检查是否有漏迁的同包依赖）

- [ ] **Step 6: Commit**

```bash
git add shared/ runtime-core/ androidApp/
git commit -m "refactor(shared): Phase 4.3 model/runtime/capability 编排核心迁 commonMain（EditParams 去 org.json）"
```

---

## Task 6：Koog 推理层迁移（路线图 4.3 中段：koog/react/log/prompt）

**Files（源前缀同 Task 5）:**
- Move: `inference/remote/koog/{KoogChatAgent,KoogMessageMemory,KoogReActStrategy,KoogSessionHistoryProvider}.kt`（4 PURE）
- Move: `inference/remote/react/{RemoteReActAgentCallback,RemoteReActAgentConfig}.kt`（2 PURE）
- Move: `inference/remote/log/{LlmCallRecord,LlmCallRecorder,TraceIdHolder}.kt`（3 PURE）
- Move: `inference/remote/{ChatStreamEvent,StreamChatResult}.kt`（2 PURE）
- Move: `inference/remote/prompt/RemotePromptBuilder.kt`（SEAM，Step 2 改造）
- Move: `remote/config/RemoteModelConfig.kt`（PURE）
- Move: `inference/remote/koog/KoogReActAgent.kt`（SEAM，Step 3 改造）
- Move: `remote/config/RemoteModelFactory.kt`（SEAM，Step 4 改造）
- Move: 对应单测（`inference/remote/koog/ComposeSystemPromptTest.kt`、`RemoteInferenceNoMediaUploadGuardTest.kt` 等）→ commonTest

- [ ] **Step 1: PURE 批 git mv（11 文件）**

按 Task 5 Step 1 的模式执行，清单见上。迁移前复验：

Run: `grep -rn "^import android\|^import java\.time\|future\|org\.json" runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/koog/ runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/react/ runtime-core/src/main/java/com/mamba/picme/agent/core/inference/remote/log/`
Expected: 无输出（KoogReActAgent 除外——它属 SEAM 在 Step 3 处理，先不迁）

- [ ] **Step 2: `RemotePromptBuilder` 去 java.time（D2）**

`git mv` 后，L138-160 的 `ZoneId.systemDefault()`/`LocalDate.now()`/`ZonedDateTime`/`LocalTime.now()` 全部替换为 kotlinx-datetime：

```kotlin
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

// ZoneId.systemDefault()          → TimeZone.currentSystemDefault()
// LocalDate.now()                 → Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
// ZonedDateTime.of(...).toInstant().toEpochMilli()
//                                 → LocalDate(y, m, d).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
// LocalTime.now().hour            → Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour
```

逐处对照现有实现替换，语义（本地时区、毫秒时间戳）不变。若某处换算无直接等价物，以现有测试（`RemotePromptBuilder` 相关单测，若无则本步补一个日期区间计算的 commonTest）锁行为后再改。

- [ ] **Step 3: `KoogReActAgent` 改造迁移**

`git mv` 后三处处理：

1. `Dispatchers.IO`（约 L117）→ 构造注入 `dispatcherProvider: DispatcherProvider`，用 `dispatcherProvider.ioDispatcher`；调用方（facade）传 Task 2 产物
2. `windowManager: WindowManager?`（约 L63）+ `RemoteControlToolService(windowManager!!)`（约 L72）→ **从构造器删除**。飞书 RPA 工具集改为可选注入：

```kotlin
class KoogReActAgent(
    private val config: RemoteReActAgentConfig,
    callback: RemoteReActAgentCallback,
    dispatcherProvider: DispatcherProvider,
    additionalToolSets: List<ai.koog.agents.core.tools.ToolSet> = emptyList(),  // 飞书 RPA 等 Android 专有工具集由组合根注入
    // ... 其余参数以现有构造器为准
)
```

`RemoteControlToolService`（Task 13 沉 androidApp 后）在 androidApp 组合根处作为 `additionalToolSets` 传入；`ChatToolService`/`CameraToolService` 在 Task 7 完成后由 commonMain 路径传入。

3. `KoogMessageMemoryStore(ctx)`（约 L88-89）→ 改为注入 `memoryStore: ChatMemoryStore`（Task 8 定义的接口；本步先用接口类型声明，Task 8 完成实现——**Task 6 与 Task 8 可互换顺序，先完成者定义接口**）

- [ ] **Step 4: `RemoteModelFactory` 改造迁移**

`git mv` 后：`KtorKoogHttpClient.Factory()` 直构点（约 L104）替换为 Task 2 的 `createKoogHttpClientFactory(...)`；`HeaderInjectingHttpClientFactory` 按 Task 2 Step 3 迁移动作处理；删除已无引用的 langchain4j 期残留（若有）。

- [ ] **Step 5: 测试迁移 + 验证**

Run: `./gradlew :shared:jvmTest :shared:compileKotlinIosSimulatorArm64 :runtime-core:assembleDebug :androidApp:assembleDebug`
Expected: 全部 `BUILD SUCCESSFUL`；`RemoteInferenceNoMediaUploadGuardTest` PASSED（**[PRIVACY] 红线护栏测试，必须随迁且保持绿**）

- [ ] **Step 6: Commit**

```bash
git add shared/ runtime-core/ androidApp/
git commit -m "refactor(shared): Phase 4.3 Koog 推理层迁 commonMain（java.time→kotlinx-datetime，WindowManager 解耦）"
```

---

## Task 7：ToolService suspend 化改写（路线图 4.3 难点，D4）

三个 SEAM 文件。这是 Phase 4 侵入性最强的改造：**只允许并发模型改写，禁止动工具名/@LLMDescription 文本/业务语义**（ToolInventory 确定性，DeepSeek 上下文缓存依赖——见 runtime-core AGENTS.md 2026-08-07 条目）。

**Files:**
- Move: `runtime-core/.../inference/remote/tool/{CameraToolService,ChatToolService}.kt` → commonMain 同路径
- Move: `runtime-core/.../inference/remote/tool/{GalleryToolDocs,MemoryContextProvider}.kt` → commonMain 同路径（2 PURE）
- Move: `runtime-core/.../inference/remote/tool/ToolInventory.kt` → commonMain 同路径（Step 3 处理反射）
- Move: `runtime-core/.../tool/CameraToolHelper.kt` → commonMain 同路径（SEAM）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/tool/ToolPromptDeterminismTest.kt`（新建）

- [ ] **Step 1: 先写确定性护栏测试（防 prompt 漂移）**

迁移前，在 runtime-core 记录现状基线：

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*ToolInventory*"`（若现有 ToolInventory 测试存在）
Expected: PASSED，且把三个 ToolService 的 `ToolInventory` 生成的工具清单段文本原样拷贝为 golden 文本

新建 `ToolPromptDeterminismTest.kt`：对 `ChatToolService`/`CameraToolService` 各生成一次工具清单，与 golden 文本 `assertEquals`（逐字节）。改写完成后此测试必须绿。

- [ ] **Step 2: suspend 化改写（`CameraToolService`/`ChatToolService`/`CameraToolHelper` 同一模式）**

逐文件执行（改写点行号以迁移日实际文件为准，清单给出的参考点：`CameraToolService` 的 `future{}.get` 调用、`ChatToolService:215 runBlocking` 与 `:228 org.json`、`CameraToolHelper:171,175,212,216 future.get/TimeoutException` 与 `:187,198 System.currentTimeMillis()`）：

```kotlin
// 改前（JVM 阻塞桥）
dispatchScope.future { registry.dispatch(command) }.get(NAVIGATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
// 改后（纯 KMP）
withTimeout(NAVIGATION_TIMEOUT_MS) { registry.dispatch(command) }

// 改前
catch (e: java.util.concurrent.TimeoutException)
// 改后
catch (e: TimeoutCancellationException)

// 改前
System.currentTimeMillis()
// 改后
Clock.System.now().toEpochMilliseconds()

// 改前（ChatToolService）
org.json.JSONObject(edits)
// 改后（配合 Task 5 Step 2 的新 fromJson 签名）
EditParams.fromJson(edits)  // edits: String

// 改前：同步 @Tool 方法内 runBlocking { ... }
// 改后：@Tool 方法直接声明 suspend（Koog 1.1.1 支持 suspend 工具函数），runBlocking 删除
```

- [ ] **Step 3: `ToolInventory` 去反射**

现有实现用 `java.lang.reflect.Method` 扫描 @Tool 注解（约 L6）。迁移时处理：

1. 先 Read 现有实现，确认 langchain4j 注解分支（`com.mamba.tool.Tool` import）是否仅剩测试 fixture——若是，该分支与对应 fixture 随删
2. Koog 分支若依赖 JVM 反射：改用 Koog 自带的工具描述 API（Koog 1.1.1 `ToolSet`/`ToolDescriptor` 为 KMP 类型），从注入的 ToolSet 列举生成清单段
3. **Step 1 的确定性测试是本步验收**：生成文本必须与 golden 逐字节一致

- [ ] **Step 4: git mv + 接线**

四个文件 `git mv` 到 commonMain 后：`ChatToolService` 的 `beautySettingsProvider` 等注入点保持现状（接口已在 commonMain）；facade 组合根在 Task 9 统一收口。

- [ ] **Step 5: 验证**

Run: `./gradlew :shared:jvmTest`
Expected: `BUILD SUCCESSFUL`；`ToolPromptDeterminismTest PASSED`（golden 逐字节）

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`

安装冒烟（相机 AI 指令走 `CameraToolService` 链路）：

Run: `./scripts/auto-dev-loop.sh`（或手动安装后相机页发一条 AI 指令，如「美白调到 50」）
Expected: 指令解析执行成功，logcat 无 `TimeoutCancellationException` 未捕获堆栈

- [ ] **Step 6: Commit**

```bash
git add shared/ runtime-core/ androidApp/
git commit -m "refactor(shared): Phase 4.3 ToolService suspend 化迁 commonMain（future.get→withTimeout，org.json→kotlinx.serialization）"
```

---

## Task 8：记忆存储 seam（路线图 4.3：`ChatMemoryStore` 接口 + DataStore actual）

**Files:**
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/platform/storage/ChatMemoryStore.kt`
- Create: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/platform/storage/KoogMessageMemoryCodec.kt`
- Move: `runtime-core/.../platform/storage/KoogMessageMemoryStore.kt` → `shared/src/androidMain/kotlin/com/mamba/picme/agent/core/platform/storage/KoogMessageMemoryStore.kt`（改造 implements 接口）
- Move: `runtime-core/.../platform/storage/MemoryManager.kt` → `shared/src/androidMain/kotlin/com/mamba/picme/agent/core/platform/storage/MemoryManager.kt`
- Move: `runtime-core/src/test/.../platform/storage/KoogMessageMemoryCodecTest.kt` → `shared/src/commonTest/` 同包（kotlin.test 化）

- [ ] **Step 1: commonMain 接口定义**

```kotlin
package com.mamba.picme.agent.core.platform.storage

import ai.koog.prompt.message.Message

/** 对话记忆持久化抽象。Android actual = DataStore；iOS actual 属 Phase 6（NSUserDefaults/文件）。 */
interface ChatMemoryStore {
    suspend fun load(sessionId: String): List<Message>
    suspend fun save(sessionId: String, messages: List<Message>)
    suspend fun clear(sessionId: String)
}
```

- [ ] **Step 2: 编解码纯函数拆出（清单 ② 指定动作）**

`KoogMessageMemoryStore.kt` 底部（约 L121-125）的 `encodeKoogMessages`/`decodeKoogMessages` 两顶层函数原样 `git mv` 内容到新文件 `KoogMessageMemoryCodec.kt`（kotlinx.serialization，已 PURE）。`KoogMessageMemoryCodecTest` 随迁 commonTest，无需改动即可跑（它本来就不依赖 Android）。

- [ ] **Step 3: Android actual 改造**

`KoogMessageMemoryStore.kt` `git mv` 到 androidMain 后：

```kotlin
class KoogMessageMemoryStore(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) : ChatMemoryStore {
    override suspend fun load(sessionId: String): List<Message> = // 现有实现原样（DataStore .data.map{}.first()）
    override suspend fun save(sessionId: String, messages: List<Message>) = // 现有实现原样
    override suspend fun clear(sessionId: String) = // 现有实现原样
}
```

`MemoryManager.kt`（仅剩 `clearHistory`）同样 `git mv` 到 androidMain，保持 DataStore 实现不变。`shared/build.gradle.kts` 的 androidMain sourceSet 追加：

```kotlin
androidMain.dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
}
```

- [ ] **Step 4: 调用点收口**

`RemoteChatEngine`、`KoogReActAgent` 中 `KoogMessageMemoryStore(configurator.getContext())` 直构点（清单：`RemoteChatEngine.kt:285`、`KoogReActAgent.kt:88-89`）全部改为构造注入 `ChatMemoryStore`；`KoogSessionHistoryProvider` 构造参数类型从具体类改为接口。直构只剩一处：Task 9 的 Android 组合根。

- [ ] **Step 5: 验证**

Run: `./gradlew :shared:jvmTest :shared:compileDebugKotlinAndroid :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`；`KoogMessageMemoryCodecTest PASSED`

- [ ] **Step 6: Commit**

```bash
git add shared/ runtime-core/ androidApp/
git commit -m "refactor(shared): Phase 4.3 ChatMemoryStore seam——接口 commonMain + DataStore actual androidMain"
```

---

## Task 9：facade 迁移与 Android 组合根收口（路线图 4.3 收尾）

**Files:**
- Move: `runtime-core/.../facade/AgentOrchestrator.kt` → commonMain 同路径（改造）
- Move: `runtime-core/.../facade/AgentConfigurator.kt` → commonMain 同路径（改造）
- Move: `runtime-core/.../inference/local/LocalModelService.kt` → commonMain 同路径（SEAM，依赖 `ImageInferenceEngine` 接口，Task 9 Step 1 定义）
- Create: `androidApp/src/main/java/com/mamba/picme/agent/AndroidAgentComposition.kt`（Android 组合根）
- Modify: `androidApp/` 内 `AgentOrchestrator.getInstance(context)` 调用点

- [ ] **Step 1: `ImageInferenceEngine` 接口（VLM 引擎抽象，Task 12 的 Android 实现挂此接口）**

Create `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/local/ImageInferenceEngine.kt`：

```kotlin
package com.mamba.picme.agent.core.inference.local

/** 端侧 VLM 图像推理引擎抽象（TAG 打标 / 图像理解）。Android actual = LocalLlmEngine(MNN JNI)；iOS actual 属 Phase 6.1。 */
interface ImageInferenceEngine {
    val isLoaded: Boolean
    suspend fun loadModel(modelId: String, useOpencl: Boolean): Result<Unit>
    suspend fun unload()
    suspend fun imageInference(imageBytes: ByteArray, prompt: String): Result<String>
    suspend fun imageInferenceWithTimeout(imageBytes: ByteArray, prompt: String, timeoutMs: Long): Result<String>
}
```

> 方法签名以现有 `LocalLlmEngine` 公开 API 为准逐项对应（Bitmap 参数统一改为 `ByteArray`，Bitmap→ByteArray 的编码在 Android actual 内完成）。

- [ ] **Step 2: facade 接口注入化改造 + git mv**

`AgentConfigurator`：构造器 `context: Context` 删除，改为接收各组件工厂/实例（`chatMemoryStore: ChatMemoryStore`、`dispatcherProvider: DispatcherProvider`、`imageEngineProvider: () -> ImageInferenceEngine`）；`getContext()` 删除；`getFeishuAgent(windowManager, ...)` 的 WindowManager 参数删除（RPA 工具集经 Task 6 Step 3 的 `additionalToolSets` 注入）。

`AgentOrchestrator`：`getInstance(context)` 单例改为 `getInstance()` + `fun initialize(deps: AgentDependencies)`（`AgentDependencies` data class 收拢全部注入项）；`Dispatchers.IO` → `dispatcherProvider.ioDispatcher`。

`LocalModelService`：构造中 `LocalLlmEngine` 直构改为 `ImageInferenceEngine` 注入。

- [ ] **Step 3: Android 组合根**

`AndroidAgentComposition.kt`（androidApp）：

```kotlin
package com.mamba.picme.agent

import android.content.Context
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.agent.core.platform.storage.KoogMessageMemoryStore
import com.mamba.picme.agent.core.platform.thread.DispatcherProvider

/** Android 组合根：所有平台实现的唯一直构点。 */
object AndroidAgentComposition {
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val dispatcherProvider = DispatcherProvider()
        AgentOrchestrator.initialize(
            AgentDependencies(
                dispatcherProvider = dispatcherProvider,
                chatMemoryStore = KoogMessageMemoryStore(appContext, dispatcherProvider),
                imageEngineProvider = { LocalLlmEngine(appContext) },
                // 飞书 RPA 工具集（RemoteControlToolService，Task 13 后在本模块）在 processRemoteImInput 路径按需注入
            ),
        )
    }
}
```

应用启动处（`Application.onCreate` 或现 `AgentOrchestrator.getInstance(context)` 首调点）改调 `AndroidAgentComposition.initialize(context)`；全部 `AgentOrchestrator.getInstance(context)` 调用点改为 `AgentOrchestrator.getInstance()`。用 grep 兜底：

Run: `grep -rn "AgentOrchestrator.getInstance(" androidApp/src/main/java/ | grep -v "getInstance()"`
Expected: 无输出

- [ ] **Step 4: 验证**

Run: `./gradlew :shared:jvmTest :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`

安装冒烟：Chat 发一条消息、相机发一条 AI 指令（两条链路分别经 `KoogChatAgent`/`KoogReActAgent`，组合根 wiring 错误的最早暴露点）
Expected: 双链路正常回包

- [ ] **Step 5: Commit**

```bash
git add shared/ runtime-core/ androidApp/
git commit -m "refactor(shared): Phase 4.3 facade 接口注入化迁 commonMain + Android 组合根收口"
```

---

## Task 10：JS 引擎抽象迁移（路线图 4.4）

**Files:**
- Move: `runtime-core/.../js/` 10 个 PURE 文件（`NativeHandler`、`JsValue`、`JsCallback`、`JsBridgeException`、`JsEngine`、`JsRunEvent`、`JsRunRecorder`、`BuiltInHandlers`、`GallerySummaryJs`、`JsBridge`）→ commonMain 同路径
- Move: `runtime-core/.../js/JsRuntime.kt` → commonMain 同路径（Step 2 改造）
- Move: `runtime-core/src/test/.../js/{JsRuntimeObservabilityTest,JsValueTest,JsBridgeTest,GallerySummaryJsTest}.kt` → commonTest（kotlin.test 化）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/chat/js/QuickJsEngine.kt`（`Closeable`→`JsClosable`）

- [ ] **Step 1: PURE 批 git mv + 复验**

Run: `grep -rn "^import android\|^import java\.\|^import org\.json" runtime-core/src/main/java/com/mamba/picme/agent/core/js/`
Expected: 仅 `JsRuntime.kt` 命中（`java.io.Closeable`）；其余文件无输出

- [ ] **Step 2: `JsRuntime` 去 JVM API（清单 js/ 条目指定动作）**

```kotlin
// 新增（commonMain，可放 JsEngine.kt 同文件）
interface JsClosable { fun close() }

// JsEngine 接口：: JsEngine, AutoCloseable → 删除 AutoCloseable，close() 默认空实现已有
// JsRuntime 类签名：: JsEngine, AutoCloseable → : JsEngine, JsClosable
// L137 (engine as? Closeable)?.close() → (engine as? JsClosable)?.close()
// L93/111/127 System.currentTimeMillis() → Clock.System.now().toEpochMilliseconds()
```

`QuickJsEngine.kt`（androidApp）：`java.io.Closeable` 改 implements `JsClosable`（方法签名不变）。

- [ ] **Step 3: 验证**

Run: `./gradlew :shared:jvmTest :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`；js/ 4 个随迁测试 PASSED

- [ ] **Step 4: Commit**

```bash
git add shared/ runtime-core/ androidApp/
git commit -m "refactor(shared): Phase 4.4 JS 引擎无关层迁 commonMain（JsClosable 去 JVM API）"
```

---

## Task 11：语音引擎抽象（路线图 4.5）

**Files:**
- Move: `runtime-core/.../platform/voice/{AsrEngine,VadDetector}.kt` → commonMain 同路径（2 PURE）
- Move: `runtime-core/.../platform/voice/{AudioRecorder,KeywordSpotterEngine,SherpaOnnxAsrEngine}.kt` → `shared/src/androidMain/kotlin/` 同路径
- Move: `runtime-core/src/test/.../platform/voice/KeywordSpotterEngineTest.kt` → `shared/src/androidUnitTest/`（其依赖 sherpa AAR，留 Android 侧）
- Move: `runtime-core/libs/sherpa-onnx-1.13.3.aar` → `shared/libs/sherpa-onnx-1.13.3.aar`（compileOnly 引用随迁）
- Modify: `shared/build.gradle.kts`（androidMain 追加 sherpa compileOnly + AAR 依赖约束说明）

- [ ] **Step 1: 迁移 + 构建脚本**

commonMain 两文件 git mv；androidMain 三文件 git mv。`shared/build.gradle.kts` androidLibrary 块内补 sherpa 约束（与现 runtime-core 相同模式）：

```kotlin
// androidMain.dependencies 内追加
compileOnly(files("libs/sherpa-onnx-1.13.3.aar"))
```

androidApp 对 sherpa AAR 的直接依赖保持不变（runtime-core AGENTS.md 记录的「compileOnly + app 直接依赖」规避模式原样平移：runtime-core 删除后，app 的 `files("...")` 路径指向改为 `../shared/libs/`）。

- [ ] **Step 2: 验证**

Run: `./gradlew :shared:compileDebugKotlinAndroid :shared:jvmTest :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`

语音冒烟（相机页语音指令，走 SherpaOnnxAsrEngine）：正常识别
Expected: 识别结果正常，无 `UnsatisfiedLinkError`

- [ ] **Step 3: Commit**

```bash
git add shared/ runtime-core/ androidApp/
git commit -m "refactor(shared): Phase 4.5 语音抽象——AsrEngine/VadDetector 进 commonMain，Sherpa 实现进 androidMain"
```

---

## Task 12：VLM 引擎与 JNI 归位 androidMain（路线图 4.3 local 残余，Task 9 接口的实现侧）

**Files:**
- Move: `runtime-core/.../inference/local/llm/{LlmGenerationMetrics,LlmModelManager,LocalLlmEngine,MnnLlmClient}.kt` → `shared/src/androidMain/kotlin/` 同路径（`LlmGenerationMetrics.kt` 虽 PURE，但仅服务 VLM 引擎，随引擎包安置——清单 ANDROID_ONLY 表决议）
- Move: `runtime-core/.../inference/local/LocalModelService.kt` 已在 Task 9 迁 commonMain（本 Task 不动）
- Move: `runtime-core/src/main/cpp/` → `shared/src/androidMain/cpp/`
- Modify: `shared/build.gradle.kts`（androidLibrary 块接入 externalNativeBuild + ndk 配置 + mnn-core/beauty-api 依赖）

- [ ] **Step 1: 四文件 git mv + LocalLlmEngine implements 接口**

`LocalLlmEngine` 声明实现 `ImageInferenceEngine`（Task 9 Step 1），Bitmap 公开入参收敛：`imageInference(Bitmap,...)` 保留为 Android 侧便捷重载，接口方法（`ByteArray` 入参）内部 `BitmapFactory.decodeByteArray` 解码后复用现有逻辑。

- [ ] **Step 2: CMake JNI 迁移**

`shared/build.gradle.kts` 的 `androidLibrary {}` 块内追加（与现 runtime-core/build.gradle.kts 的 android 块逐项对应）：

```kotlin
ndkVersion = "28.2.13676358"
defaultConfig {
    ndk { abiFilters += listOf("arm64-v8a") }
    externalNativeBuild {
        cmake {
            arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_PLATFORM=android-24")
        }
    }
}
externalNativeBuild {
    cmake {
        path = file("src/androidMain/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}
```

`git mv runtime-core/src/main/cpp shared/src/androidMain/cpp`，CMakeLists.txt 内相对路径（如 mnn-core 头文件/库路径）按新模块位置修正。

androidMain.dependencies 追加：

```kotlin
implementation(project(":engines:mnn-core"))
implementation(project(":engines:beauty-api"))
```

> **降级预案**（仅当 AGP 9 KMP 插件的 androidLibrary 块不接受 externalNativeBuild 时启用）：VLM 引擎四文件 + cpp 改沉 `androidApp/`（包名不变），`ImageInferenceEngine` 接口仍在 commonMain，组合根 wiring 不受影响。判定标准：`./gradlew :shared:compileDebugKotlinAndroid` 报 externalNativeBuild 相关 DSL 错误且查阅 AGP 9.1 文档确认不支持。启用预案则在本 Task 勾选处记录。

- [ ] **Step 3: 验证**

Run: `./gradlew :shared:assembleDebug`
Expected: `BUILD SUCCESSFUL`，`shared/build/intermediates` 下产出 `libagent_native.so`（arm64-v8a）

TAG 冒烟：触发一次打标（TAG 控制页手动扫小范围）
Expected: `imageInference` 正常返回，logcat 无 JNI 加载错误

- [ ] **Step 4: Commit**

```bash
git add shared/ runtime-core/ androidApp/
git commit -m "refactor(shared): VLM 引擎 + CMake JNI 归位 shared/androidMain（LocalLlmEngine implements ImageInferenceEngine）"
```

---

## Task 13：Android 专有组件沉入 androidApp（路线图 4.6）

**Files（包名不变，仅换模块）:**
- Move: `runtime-core/.../tool/accessibility/` 全部 4 文件（含 `src/debug/` 变体 `PicMeAccessibilityService.kt`，debug sourceSet 对应迁移）→ `androidApp/src/main|debug/java/com/mamba/picme/agent/core/tool/accessibility/`
- Move: `runtime-core/.../tool/perception/ViewHierarchyExtractor.kt` → `androidApp/.../agent/core/tool/perception/`
- Move: `runtime-core/.../inference/remote/tool/RemoteControlToolService.kt` → `androidApp/.../agent/core/inference/remote/tool/`
- Modify: `androidApp/build.gradle.kts`（如 accessibility 相关 manifest 声明在 runtime-core，随迁）

- [ ] **Step 1: git mv（含 debug sourceSet 与 manifest 片段）**

```bash
SRC=runtime-core/src/main/java/com/mamba/picme/agent/core
APP=androidApp/src/main/java/com/mamba/picme/agent/core
mkdir -p $APP/tool/accessibility $APP/tool/perception $APP/inference/remote/tool
git mv $SRC/tool/accessibility/*.kt $APP/tool/accessibility/
git mv $SRC/tool/perception/ViewHierarchyExtractor.kt $APP/tool/perception/
git mv $SRC/inference/remote/tool/RemoteControlToolService.kt $APP/inference/remote/tool/
# debug 变体
mkdir -p androidApp/src/debug/java/com/mamba/picme/agent/core/tool/accessibility
git mv runtime-core/src/debug/java/com/mamba/picme/agent/core/tool/accessibility/PicMeAccessibilityService.kt \
       androidApp/src/debug/java/com/mamba/picme/agent/core/tool/accessibility/
```

`runtime-core/src/main/AndroidManifest.xml` 中 accessibility service 声明（`<service android:name=".agent.core.tool.accessibility.PicMeAccessibilityService" ...>` 含 `BIND_ACCESSIBILITY_SERVICE` 权限与 meta-data）剪贴到 `androidApp/src/main/AndroidManifest.xml`（或 debug manifest，与原来 sourceSet 一致）；`res/xml/` 下 accessibility service config 资源文件随迁。

- [ ] **Step 2: 组合根接线核查**

Task 9 组合根中飞书 RPA 路径的 `additionalToolSets` 注入点现在可以引用本模块的 `RemoteControlToolService`（同模块可直构），确认 wiring 完整：

Run: `grep -rn "RemoteControlToolService" androidApp/src/main/java/ | grep -v "^.*RemoteControlToolService.kt"`
Expected: 至少组合根一处引用；无指向 runtime-core 的残留

- [ ] **Step 3: 验证**

Run: `./gradlew :androidApp:assembleDebug :androidApp:assembleRelease`
Expected: 全部 `BUILD SUCCESSFUL`（release 验证 proguard 无 accessibility 类缺失警告）

飞书远程控制冒烟（若环境可连飞书）：`go_back`/点击等 RPA 指令正常
Expected: 指令执行正常（环境不可连时记录为「静态验证通过，功能验证待连调」）

- [ ] **Step 4: Commit**

```bash
git add androidApp/ runtime-core/
git commit -m "refactor(app): Phase 4.6 accessibility/perception/RemoteControlToolService 沉入 androidApp"
```

---

## Task 14：runtime-core 消亡（路线图 4.7）

- [ ] **Step 1: 残余清点（必须为空才能删模块）**

Run: `find runtime-core/src -name "*.kt" -o -name "*.java" -o -name "*.cpp" -o -name "*.h" | grep -v build/`
Expected: 无输出。若有残余文件：逐个对照清单判定去向（commonMain / androidMain / androidApp），补办迁移后回到本步

Run: `grep -rn "project(\":runtime-core\")" --include="*.gradle.kts" --include="*.gradle" .`
Expected: 仅 `settings.gradle.kts` 的 include 与 androidApp 的依赖声明两处

- [ ] **Step 2: 移除模块**

`settings.gradle.kts` 删 `include(":runtime-core")`；`androidApp/build.gradle.kts` 删 `implementation(project(":runtime-core"))`；`git rm -r runtime-core/`；`scripts/` 与 `.github/` 中 runtime-core 引用 grep 清理（参照 Phase 3 的批量更新模式）：

Run: `grep -rln "runtime-core" scripts/ .github/ 2>/dev/null`
Expected: 逐个文件修正为 shared/androidApp 对应路径后无残留

- [ ] **Step 3: 全量验证**

Run: `./gradlew clean :androidApp:assembleDebug :shared:jvmTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(shared): Phase 4.7 :runtime-core 模块删除（全部代码已迁 shared/androidApp）"
```

---

## Task 15：出口验证与文档同步（路线图 4.8 + [DOC-SYNC] 红线）

- [ ] **Step 1: Android 全功能零回归**

Run: `./scripts/ai-gate.sh`
Expected: 全绿

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`；shared 侧测试数 ≥ 原 runtime-core 23 个（随迁）+ 新增（DispatcherProvider/ToolPromptDeterminism/EditParams 等）

安装冒烟矩阵（逐项人工/自动确认）：相机（预览/快门/AI 指令）、相册（浏览/搜索）、Chat（流式/工具调用/JS 图卡）、TAG（打标/控制页）、设置、飞书远程控制（可连环境下）
Expected: 与 P3 基线记录行为一致

- [ ] **Step 2: shared JVM 单测覆盖核查（4.8 出口标准：核心逻辑 JVM 可复现可调试）**

Run: `./gradlew :shared:jvmTest --continue && find shared/src/commonTest shared/src/jvmTest -name "*.kt" | wc -l`
Expected: ≥ 20 个测试文件；`CapabilityRegistry`/`CommandExecutor`/`PrivacyGuard`/`JsBridge`/`KoogMessageMemory`/`EditParams`/`RemotePromptBuilder`（日期区间）/`ToolInventory`（确定性）有对应测试。缺口项补测试后重跑

- [ ] **Step 3: iOS 侧消费验证（骨架级）**

Run: `./gradlew :shared:assembleSharedReleaseXCFramework`（或 Phase 2.3 spike 的 XCFramework 任务名）
Expected: 产出 XCFramework；用 Phase 2.3 spike 工程（`tmp/kmp-koog-spike/`）替换新 framework 后 iOS 真机跑一次 `SharedPlaceholder` 级调用（此时为某个 commonMain PURE API，如 `PrivacyGuard` 分级）
Expected: iOS 真机调用成功（此步是 Phase 5 的提前排雷，失败不阻塞 Phase 4 收口但必须记录）

- [ ] **Step 4: 文档同步（[DOC-SYNC]）**

- 根 `AGENTS.md`「架构说明」段：`:runtime-core` 消亡、`shared/` KMP 模块三 target 结构、引擎 actual 分布（VLM/语音 androidMain、iOS Phase 5/6）
- 删 `runtime-core/AGENTS.md`，新建 `shared/AGENTS.md`（模块定位/依赖方向/核心组件位置/编译验证 `./gradlew :shared:jvmTest :shared:assembleDebug`）
- `androidApp/AGENTS.md`：accessibility/perception/RPA 组件迁入说明
- 路线图 Phase 4 全部 `- [ ]` 勾选为 `- [x]`，变更记录追加一行
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` 涉及 runtime-core 的段落路径更新

- [ ] **Step 5: review + 收尾**

按全局纪律：派 review 子 agent（GLM）审全量 diff；闭环验证（编译→安装→测试→日志）记录入 PR 描述。

```bash
git add -A
git commit -m "docs(shared): Phase 4.8 出口验证记录 + 文档同步（runtime-core 消亡）"
```

---

## 风险与降级

| 风险 | 触发点 | 降级 |
|------|--------|------|
| AGP 9 KMP 插件不支持 externalNativeBuild | Task 12 Step 2 | VLM 引擎 + cpp 沉 androidApp（接口仍 commonMain），记录后续 |
| kotlinx-datetime 版本解析失败 | Task 1 Step 2 | 用 maven-metadata 实查的最新稳定版 |
| Koog 1.1.1 某 API 非 KMP（编译 iosMain 报错） | Task 6/7 | 该调用点收敛进 expect 函数，androidMain 保持现状，iosMain 抛 `TODO("Phase 5")`；记录到路线图风险登记册 |
| ToolService suspend 化行为漂移 | Task 7 Step 5 冒烟 | 以 `ToolPromptDeterminismTest` + 相机指令冒烟双护栏定位，禁止带病合并 |
| 迁移中 androidApp 编译红（漏迁传递依赖） | 各 Task 验证步 | 被依赖文件同批补迁；禁止在 androidApp 侧新建重复类型绕路 |

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-07 | 初版：基于耦合点清单（specs/2026-08-07-runtime-core-platform-coupling-inventory.md）与路线图 Phase 4 编写；执行前置 = Phase 1 合并 + Phase 3 完成 |
