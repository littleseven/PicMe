# runtime-core 平台耦合点清单（Phase 1.5 交付物）

> **用途**：Phase 4（shared KMP 模块抽取）expect/actual 拆分的指定输入，见 `docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` Phase 1.5 / Phase 4。
>
> **审计基线**：`.worktrees/feat-koog-migration/`（分支 `feat/koog-migration`，HEAD `1cbe9353`，即 Koog 迁移完成、`:agent-core` 已删除后的状态）。审计日 2026-08-07。
>
> **审计方法**：5 路并行只读审计，逐文件核对 import 与 API 调用。runtime-core 79 个源文件全覆盖；另按路线图 Phase 4 耦合点六类要求，扩展审计 `:app` 模块热点（Room / DataStore / Foreground Service / MediaStore / 领域模型）。
>
> **纯净度定义**：
> - **PURE**：无 Android/JVM 平台依赖，可直接进 `shared/commonMain`（kotlinx.coroutines / kotlinx.serialization 等 KMP 库不算平台依赖）
> - **SEAM**：逻辑可共享，少量平台调用需收敛为 expect 接口或替换为 KMP 等价物
> - **ANDROID_ONLY**：本质是平台组件，留 `shared/androidMain` 或沉入 `androidApp/`

---

## 0. 总体画像

| 范围 | PURE | SEAM | ANDROID_ONLY | 合计 |
|------|------|------|--------------|------|
| runtime-core（79 文件） | 44 | 17 | 18 | 79 |
| :app 扩展热点（56 文件） | 27 | 17 | 12 | 56 |

**核心结论**：Koog 迁移的副产品是编排层已高度纯净——`model/`、`runtime/`、`capability/`、`js/`、`inference/remote/koog/` 近乎全 PURE。真正的迁移工作量集中在：① 少量 SEAM 文件的 JVM 残留（`Dispatchers.IO`、`java.time`、`org.json`、`CompletableFuture`）；② facade 层的 `Context`/`WindowManager` 注入；③ storage 的 DataStore 双类；④ Android 专有组件（无障碍 RPA、MNN JNI、Sherpa 语音、Service）归位。

---

## ① Room/SQLite（全部位于 `:app`，runtime-core 无 Room 依赖）

| 文件 | 纯净度 | 耦合点 |
|------|--------|--------|
| `app/src/main/java/com/mamba/picme/data/local/llmlog/LlmLogDatabase.kt` | ANDROID_ONLY | `Room.databaseBuilder(context)`、`fallbackToDestructiveMigration` |
| `app/.../data/local/llmlog/LlmCallLogEntity.kt` | SEAM | 仅 `@Entity`/`@PrimaryKey` 注解；字段全 primitive |
| `app/.../data/local/llmlog/ToolCallLogEntity.kt` | SEAM | 同上 |
| `app/.../data/local/llmlog/JsRunLogEntity.kt` | SEAM | 同上 |
| `app/.../data/local/llmlog/LlmCallLogDao.kt` / `ToolCallLogDao.kt` / `JsRunLogDao.kt` | ANDROID_ONLY | `@Dao`/`@Query`/`@Insert` |
| `app/.../data/local/AppDatabase.kt` | ANDROID_ONLY | 18 个 Entity、version=20、18 条 Migration（`SupportSQLiteDatabase.execSQL`） |
| `app/.../data/local/entity/FaceEmbeddingEntity.kt` / `PersonEntity.kt` / `TagScanTaskEntity.kt` | SEAM | Room 注解 + `System.currentTimeMillis()` 默认值；`TagScanPass`/`TagScanTaskStatus` 枚举本身 PURE |
| `app/.../data/local/dao/PersonDao.kt` / `TagScanTaskDao.kt` | ANDROID_ONLY | `@Transaction reconcilePersons()` 跨表 JOIN 等复杂 SQL |

**处置决策（Phase 4 锁定）**：Android 侧 Room 不动（零回归红线）。跨平台共享点不在 Entity/Dao，而在 runtime-core 已有的纯契约：`LlmCallRecord`（PURE data class）+ `LlmCallRecorder` / `CommandExecutionRecorder` / `JsRunRecorder`（PURE fun interface）——:app 的 Room 实现即 Android actual，注入即可。iOS 侧等价存储（SQLDelight/GRDB）推迟到 Phase 6，不进 Phase 4 范围。

## ② DataStore

| 文件 | 纯净度 | 耦合点 |
|------|--------|--------|
| `runtime-core/.../platform/storage/KoogMessageMemoryStore.kt` | ANDROID_ONLY（Store 本体） | `Context` + `preferencesDataStore("chat_memory")` 全套；**底部 `encodeKoogMessages`/`decodeKoogMessages` 两顶层纯函数（kotlinx.serialization）应先拆出进 commonMain** |
| `runtime-core/.../platform/storage/MemoryManager.kt` | ANDROID_ONLY | `Context` + `preferencesDataStore("agent_memory")`；Koog 迁移后仅剩 `clearHistory(sessionId)` 一个方法，极薄 |
| `app/.../data/preferences/UserPreferencesRepository.kt` | ANDROID_ONLY | 1000+ 行 DataStore Preferences 重度使用；TAG/OpenCL 持久化键：`TAG_GENERATION_USE_OPENCL`/`OPENCL_DEGRADED_DEVICES`/`TAGGER_MODEL_KEY`（L85-87、L601-680）；含 `BuildConfig`、`runBlocking` |
| `app/.../domain/repository/UserSettingsRepository.kt` | **PURE** | 纯接口（Flow + domain 枚举），**是 DataStore 的天然跨平台抽象出口**，直接迁 commonMain |

**处置决策**：commonMain 定义 `interface ChatMemoryStore { suspend fun load/save/clear }`；Android actual = 现有 DataStore 实现（`KoogMessageMemoryStore` 改造 implements），iOS actual Phase 6 用 NSUserDefaults/文件实现。`UserSettingsRepository` 接口迁 commonMain，实现类留 androidApp。

## ③ Foreground Service（全部位于 `:app`）

| 文件 | 纯净度 | 耦合点 |
|------|--------|--------|
| `app/.../service/tag/TagGenerationService.kt` | ANDROID_ONLY | `Service` + Notification 全家桶 + `BroadcastReceiver`（电池）+ `PowerManager`（热状态/ WakeLock）+ Intent 驱动 + `onTimeout`（API 35+） |
| `app/.../service/tag/TagScanRescheduleReceiver.kt` | ANDROID_ONLY | `BroadcastReceiver` + `AlarmManager.setExactAndAllowWhileIdle` |
| `app/.../domain/tag/scan/TagScanOrchestrator.kt`（1135 行） | SEAM | Room DAO、`PowerManager.WakeLock`、`android.util.Log`、`org.json`、`Dispatchers.IO`；**状态机/ETA 中位数估算/批量调度算法纯净** |
| `app/.../domain/tag/TagGenerationScheduler.kt`（1594 行） | ANDROID_ONLY（本体） | Context + Bitmap + Room 事务 + CameraX + MNN/ONNX JNI 引擎群；**DBSCAN/k-NN/余弦距离/质心计算（L861-992）纯数学可抽** |
| `app/.../domain/tag/OpenClGuardian.kt` | SEAM | `Build.MANUFACTURER/MODEL/HARDWARE/BOARD` 设备指纹、`Bitmap`（warmup 输入）、`LocalLlmEngine`（JNI）、prefs 黑名单读写；**连续失败计数/冷却期/降级状态机可共享** |

**处置决策**：Service 本体留 androidApp，iOS 无等价物（BGTaskScheduler 限 30 秒——TAG 全量扫描 iOS 端功能差异须在 Phase 6 显式标注，沿用路线图结论）。纯算法（聚类、ETA、Guardian 状态机）抽 commonMain 属 Phase 4 可选增益项，非阻塞。

## ④ ContentResolver/MediaStore（全部位于 `:app`）

| 文件 | 纯净度 | 耦合点 |
|------|--------|--------|
| `app/.../data/repository/MediaRepositoryImpl.kt`（587 行） | ANDROID_ONLY | `MediaStore.Images/Video.Media.*` 查询、`contentResolver.query/delete`、`RecoverableSecurityException` + `IntentSender`、权限检查（`READ_MEDIA_IMAGES` 等，SDK 33 分支）、coil |
| `app/.../data/indexing/MediaStoreObserver.kt` | ANDROID_ONLY | `ContentObserver` + `Handler(Looper.getMainLooper())`；`MediaChangeEvent`/`ChangeType` 数据类 PURE 可抽 |

**处置决策**：对应路线图 4.2——commonMain 定义 `PhotoLibraryProvider` + `AccessState` 密封枚举（`Full / Limited / Denied / AddOnly(iOS)`），Android actual = 现有 MediaStore 实现，iOS actual = Phase 5 PhotoKit。权限流程留各端 UI。

## ⑤ Handler/Looper/线程

runtime-core 内的线程耦合（:app 的见 ③）：

| 文件 | 耦合点 | 处置 |
|------|--------|------|
| `platform/thread/ThreadPoolManager.kt` | `Executors.newSingleThreadExecutor`/`newFixedThreadPool` + `asCoroutineDispatcher()`（JVM） | **SEAM** → `expect class DispatcherProvider`（4 个命名 dispatcher + shutdown）；androidMain 现状原样，iosMain 用 DispatchQueue |
| `facade/AgentOrchestrator.kt`、`inference/remote/RemoteChatEngine.kt`、`inference/remote/koog/KoogReActAgent.kt`、`app/.../TagScanOrchestrator.kt` | `Dispatchers.IO`（JVM/Android 专属，commonMain 无） | 统一收敛为 `DispatcherProvider.ioDispatcher` 或 `Dispatchers.Default` 替换（逐个评估） |
| `inference/remote/tool/CameraToolService.kt` / `ChatToolService.kt`、`tool/CameraToolHelper.kt` | `kotlinx.coroutines.future.future` + `.get(timeout, TimeUnit)`（kotlinx-coroutines-jdk8 JVM 桥）、`runBlocking`、`java.util.concurrent.TimeoutException` | **必须改写**为 `suspend` + `withTimeout` + `TimeoutCancellationException`（纯 KMP，改写后不再需要 expect） |
| `inference/remote/tool/RemoteControlToolService.kt`、`tool/accessibility/AccessibilityServiceHolder.kt` | `Looper`/`Handler`/`runOnUiThread` | ANDROID_ONLY，随 RPA 组件留 androidMain/androidApp |
| `platform/voice/SherpaOnnxAsrEngine.kt` | `Dispatchers.IO`/`Dispatchers.Main`/`CountDownLatch` | ANDROID_ONLY（语音引擎本体） |
| `service/tag/TagGenerationService.kt` | `Executors.newSingleThreadExecutor`、`Dispatchers.Main` | ANDROID_ONLY |

## ⑥ JNI/原生库

| 文件 | 纯净度 | 耦合点 |
|------|--------|--------|
| `runtime-core/.../inference/local/llm/MnnLlmClient.kt` | ANDROID_ONLY | 全部 `private external fun native*()`（L516-564）、`System.loadLibrary("agent_native")`、Bitmap 参数、`org.json` |
| `runtime-core/.../inference/local/llm/LocalLlmEngine.kt` | ANDROID_ONLY | `MnnResourceManager`/`MnnGlobalReleaseLock`（:mnn-core JNI）、`Bitmap` 推理输入、Context |
| `runtime-core/.../inference/local/llm/LlmModelManager.kt` | SEAM | `context.filesDir`/`context.assets`、java.io.File → `interface ModelFileProvider` |
| `runtime-core/.../platform/voice/KeywordSpotterEngine.kt`、`SherpaOnnxAsrEngine.kt`、`AudioRecorder.kt` | ANDROID_ONLY | sherpa-onnx JNI 绑定（`com.k2fsa.sherpa.onnx.*`）、AudioRecord/蓝牙 SCO/AEC/NS、java.io/nio |
| `app/.../domain/tag/` 推理引擎群（`MobileClipEngine`、`MobileClipOnnxBackend`、`florence2/Florence2Tagger`、`i18n/OpusMtTranslator`、`FaceClusterEngine` 等 7 文件） | SEAM（重） | MNN/ONNX Runtime JNI + Bitmap + Context + java.io |

**处置决策**：桥接路径已被 Phase 2 spike 验证（MNN iOS Metal 后端、sentencepiece/QuickJS ObjC++ 直链）。Phase 4 只定义 commonMain 接口——`interface ImageInferenceEngine`（VLM 打标）、`AsrEngine`/`KeywordSpotterEngine`（已 PURE 的接口直接迁）；Android 实现随 `shared/androidMain` 走现有 JNI；iOS actual 属 Phase 5/6。

## ⑦ 其他平台耦合

| 耦合类型 | 涉及文件（runtime-core 除注明外） | 处置 |
|----------|--------------------------------|------|
| **Context** | `facade/AgentConfigurator.kt`、`facade/AgentOrchestrator.kt`、`inference/local/llm/*`、`platform/storage/*` | 收敛为构造注入的 `expect class PlatformContext` 或消除（多数 Context 仅为 DataStore/文件路径服务，随接口下沉而消除） |
| **WindowManager**（飞书 RPA 屏幕感知） | `facade/AgentConfigurator.kt:202`、`facade/AgentOrchestrator.kt:242`、`inference/remote/koog/KoogReActAgent.kt:63,72` | 抽 `interface ScreenInspector`；仅飞书 RPA 路径使用，androidMain 扩展注入，commonMain 主路径不传 |
| **org.json** | `model/command/EditParams.kt:43`、`inference/remote/tool/ChatToolService.kt:228`、`inference/local/llm/MnnLlmClient.kt`、`tool/accessibility/AccessibilityNodeDumper.kt`、`tool/perception/ViewHierarchyExtractor.kt` | 进 commonMain 的一律改 **kotlinx.serialization**（`EditParams.fromJson` 参数从 `JSONObject` 改为 `String`）；留 androidMain 的可保留 org.json |
| **java.time** | `inference/remote/prompt/RemotePromptBuilder.kt:138-160`、`inference/remote/RemoteChatEngine.kt:274` | 统一替换 **kotlinx-datetime**（新增依赖） |
| **JVM 并发/IO** | `model/context/AgentModels.kt:112`（`AtomicInteger` AgentIdGenerator）、`js/JsRuntime.kt`（`java.io.Closeable`/`AutoCloseable`、`System.currentTimeMillis()`）、`tool/CameraToolHelper.kt`（`System.currentTimeMillis()`） | `expect object AgentIdGenerator`；`JsRuntime` 自定义 `interface JsClosable { fun close() }`；时间统一 `Clock.System.now()`（kotlinx-datetime，免 expect） |
| **java.lang.reflect** | `inference/remote/tool/ToolInventory.kt:6` | 迁移时评估用 Koog ToolDescriptor 替代；langchain4j 分支已仅剩测试 fixture，可随删 |
| **String.format / java.util.Random / UUID** | `ViewHierarchyExtractor`、`TagGenerationScheduler:938`、`TagScanOrchestrator:32` 等 | 多在 ANDROID_ONLY 文件内，不构成阻碍；进 commonMain 的改用 Kotlin 等价物 |
| **跨模块 :beauty-api 类型** | `model/command/AgentCommands.kt:7-9`、`inference/remote/tool/CameraToolService.kt`、`tool/CameraToolHelper.kt`、`app/.../UserPreferences.kt` | **已核实（2026-08-07）**：`BeautySettings.kt`/`FilterType.kt`/`StyleFilter.kt` 无 android import（PURE），可迁 commonMain；`Face.kt`/`facedetect/*` 依赖 `android.graphics.PointF/RectF/Bitmap`，留 Android 侧 |
| **android.util.Log** | `TagScanOrchestrator`、`TagGenerationScheduler`、`TagNormalizer`、`AdaptiveFaceClusterer` 等 :app 文件 | runtime-core 内零直接 Log 依赖（`platform/logging/Logger` 已是注入式 PURE 范本）；:app SEAM 文件迁移时改走 `Logger` |

---

## PURE 清单（runtime-core 44 文件，可直接进 commonMain）

| 子包 | 文件 |
|------|------|
| `model/`（9） | `command/CommandRisk.kt`、`command/FeedbackAction.kt`、`config/AiAgentConfig.kt`、`context/GallerySummary.kt`、`context/MediaAsset.kt`、`context/PageContext.kt`、`context/SceneContext.kt`、`context/SearchIntent.kt`、`plan/ExecutionPlan.kt` |
| `runtime/`（10） | `capability/CapabilityRegistry.kt`、`capability/CommandExecutionRecorder.kt`、`capability/CommandExecutor.kt`、`capability/CrossPageCommandQueue.kt`、`execution/ExecutionEngine.kt`、`execution/ExecutionReporter.kt`、`execution/ExecutionState.kt`、`execution/InferenceResult.kt`、`policy/PrivacyGuard.kt`、`state/SceneManager.kt` |
| `capability/`（2） | `Capability.kt`、`FaceDetectionProvider.kt` |
| `inference/remote/koog/`（4） | `KoogChatAgent.kt`、`KoogMessageMemory.kt`、`KoogReActStrategy.kt`、`KoogSessionHistoryProvider.kt` |
| `inference/remote/`（3+3） | `ChatStreamEvent.kt`、`StreamChatResult.kt`、`react/RemoteReActAgentCallback.kt`、`react/RemoteReActAgentConfig.kt`、`log/LlmCallRecord.kt`、`log/LlmCallRecorder.kt`、`log/TraceIdHolder.kt` |
| `inference/remote/tool/`（2） | `GalleryToolDocs.kt`、`MemoryContextProvider.kt` |
| `js/`（10） | `NativeHandler.kt`、`JsValue.kt`、`JsCallback.kt`、`JsBridgeException.kt`、`JsEngine.kt`、`JsRunEvent.kt`、`JsRunRecorder.kt`、`BuiltInHandlers.kt`、`GallerySummaryJs.kt`、`JsBridge.kt` |
| `platform/`（3） | `logging/Logger.kt`、`voice/AsrEngine.kt`、`voice/VadDetector.kt` |
| `tool/`（1） | `perception/UiObservationFormatter.kt`（+其测试迁 commonTest 换 kotlin.test） |

:app 侧额外 PURE（Phase 4.2 输入，27 文件）：`domain/model/` 全部 12 文件（`UserPreferences`、`LlmProviderConfig`、`StructuredFilter`、`GalleryQuery`、`MediaGrouping`、`MediaType`、`DuplicateGroup`、`AiAgentCommand`、`ChatEditRecipeBuilder`、`LogModuleConfig`、`RemoteChannelType`、`VoiceCommandMode`）；`domain/tag/` 策略配置 15 文件（`ScanQueuePolicy`、`TagScanQuery`、`StreamingClusterAccumulator`、`DbscanRefinementPolicy`、`TagCategory`、`TaggerModelSelector`、`ClusteringConfig`、`MergeDecision`、`ImageDescriptionStrategy`、`LabelSinicizer`、`TagTranslator`、`DefaultTagPromptProvider`、`TagPromptProvider`、`Florence2Preprocess`、`Florence2ResultParser`）；`domain/repository/UserSettingsRepository.kt`（接口）。

## SEAM 清单（runtime-core 17 文件）

| 文件 | 关键耦合 | expect / 改写方案 |
|------|----------|-------------------|
| `facade/AgentConfigurator.kt` | Context、WindowManager | `PlatformContext` 注入；`ScreenInspector` 接口（仅飞书 RPA） |
| `facade/AgentOrchestrator.kt` | Context、WindowManager、Dispatchers.IO | 同上 + `DispatcherProvider` |
| `inference/local/LocalModelService.kt` | 间接 JNI/线程 | 依赖 `ImageInferenceEngine` 接口抽象后可直接进 commonMain |
| `inference/local/llm/LlmModelManager.kt` | filesDir/assets/File | `interface ModelFileProvider` |
| `inference/remote/RemoteChatEngine.kt` | Dispatchers.IO、java.time、Context(→MemoryStore) | `DispatcherProvider` + kotlinx-datetime + `ChatMemoryStore` 接口 |
| `inference/remote/koog/KoogReActAgent.kt` | WindowManager、Context、Dispatchers.IO | 同 facade；commonMain 主路径不持 WindowManager |
| `inference/remote/prompt/RemotePromptBuilder.kt` | java.time（L138-160） | kotlinx-datetime 全量替换 |
| `inference/remote/tool/CameraToolService.kt` | coroutines.future、beauty-api 类型 | suspend + withTimeout 改写；beauty 纯类型迁 commonMain |
| `inference/remote/tool/ChatToolService.kt` | 同上 + runBlocking + org.json | 同上 + kotlinx.serialization |
| `inference/remote/tool/ToolInventory.kt` | java.lang.reflect | Koog ToolDescriptor 评估；langchain4j 残留分支随删 |
| `model/command/EditParams.kt` | org.json（`fromJson`） | `fromJson(jsonString: String)` + kotlinx.serialization |
| `model/command/AgentCommands.kt` | :beauty-api 三类型 | 类型迁 commonMain 后即 PURE |
| `model/context/AgentModels.kt` | AtomicInteger（AgentIdGenerator） | `expect object AgentIdGenerator { fun nextId(): Int }` |
| `remote/config/RemoteModelFactory.kt` | Ktor HTTP 引擎工厂 | `expect fun createKoogHttpClientFactory(headers): KoogHttpClient.Factory`（双端均显式构造，绕 ServiceLoader） |
| `js/JsRuntime.kt` | java.io.Closeable、System.currentTimeMillis | 自定义 `JsClosable` 接口 + kotlinx-datetime |
| `platform/thread/ThreadPoolManager.kt` | java.util.concurrent 线程池 | `expect class DispatcherProvider` |
| `tool/CameraToolHelper.kt` | future.get(timeout)、System.currentTimeMillis、beauty-api | suspend + withTimeout 改写 + kotlinx-datetime |

## ANDROID_ONLY 清单（runtime-core 18 文件）

| 去向 | 文件 |
|------|------|
| `shared/androidMain`（引擎 actual） | `inference/local/llm/LocalLlmEngine.kt`、`inference/local/llm/MnnLlmClient.kt`、`platform/storage/KoogMessageMemoryStore.kt`（改造为 `ChatMemoryStore` actual）、`platform/storage/MemoryManager.kt`、`platform/voice/AudioRecorder.kt`、`platform/voice/KeywordSpotterEngine.kt`、`platform/voice/SherpaOnnxAsrEngine.kt` |
| `androidApp/`（纯 Android 架构，iOS 无等价物——对应路线图 4.6） | `tool/accessibility/` 全部 4 文件（`AccessibilityActionPerformer`、`AccessibilityNodeDumper`、`AccessibilityServiceHolder`、debug 变体 `PicMeAccessibilityService`）、`tool/perception/ViewHierarchyExtractor.kt`、`inference/remote/tool/RemoteControlToolService.kt`（飞书 RPA，依赖 Accessibility + View 树 + MotionEvent + Looper + Activity） |
| 保留 androidMain 的局部依赖 | `inference/local/llm/LlmGenerationMetrics.kt` 为 PURE 但仅服务 VLM 引擎，随引擎包安置 |

## 关键架构发现（expect/actual 设计输入）

1. **Koog 迁移已天然完成大部分解耦**：koog/ 包 4 文件全 PURE，`LlmCallRecord`/`LlmCallRecorder`/`CommandExecutionRecorder`/`JsRunRecorder`/`UserSettingsRepository`/`Logger` 等注入式契约早已平台无关——Phase 4 大量工作是「搬家」而非「改造」。
2. **新增 expect 仅约 6 处**：`DispatcherProvider`、`AgentIdGenerator`、`createKoogHttpClientFactory`、`PlatformContext`、`ScreenInspector`、`ModelFileProvider`；时间类统一 kotlinx-datetime 后不需要 expect。侵入面很小。
3. **`coroutines.future.future` 是隐形 JVM 锁**（3 个 ToolService/Helper 共用）：必须改写 suspend + withTimeout，这是 ToolService 进 commonMain 的唯一实质改造。
4. **org.json 是隐蔽陷阱**：进 commonMain 的文件（`EditParams`、`ChatToolService`）改 kotlinx.serialization；留 androidMain 的（accessibility/perception）可不动。
5. **beauty-api 拆分线已核实**：`BeautySettings`/`FilterType`/`StyleFilter` PURE 可迁；`Face`/`facedetect/*`（PointF/RectF/Bitmap）留 Android。
6. **语音包分两半**：`AsrEngine`/`VadDetector` PURE 进 commonMain；Sherpa/AudioRecorder/KWS 三实现 ANDROID_ONLY，iOS actual（AVAudioEngine + sherpa-onnx Swift 桥）属 Phase 6。
7. **结构化日志三表零迁移成本**：Room Entity/DAO 即 Android actual，runtime-core 纯契约已就位，iOS 存储 Phase 6 再定。
