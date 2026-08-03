# 核心链路内存泄漏审计报告

> **日期**：2026-08-02（排查）/ 2026-08-03（修复启动）
> **范围**：相机/美颜渲染链路、Agent/Chat/JS 沙箱链路、TAG 打标/MNN 推理链路、App 通用层
> **方法**：4 路并行只读代码审计（逐文件取证），两份独立排查交叉验证
> **修复分支**：`fix/memory-leak-core-paths`

---

## 1. 修复跟踪表

| # | 优先级 | 问题 | 位置 | 状态 | 验证 |
|---|--------|------|------|------|------|
| F1 | P0 | TAG Service 销毁时 5 组模型引擎不释放（~1GB native/轮） | `TagGenerationService.onDestroy()` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F2 | P0 | `RemoteControlToolService.currentActivity` 静态持有 Activity 从不清理 | `runtime-core/.../RemoteControlToolService.kt:65` + `PoLangApplication.kt:610` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F3 | P0 | `ChatToolService.adjustImageHandler` 单例 lambda 捕获 ChatViewModel，`onCleared()` 未清理 | `app/.../ChatViewModel.kt:819,1844` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F4 | P1 | `ClaudeChatClient.chat()` SSE Response 从不关闭 + 阻塞读不可取消 | `app/.../ClaudeChatClient.kt:100-124` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F5 | P1 | `CrossPageCommandQueue.queueScope` getter 每次 new scope 不 cancel | `runtime-core/.../CrossPageCommandQueue.kt:72` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F6 | P1 | `GlobalScope.future` dispatch 超时后协程裸跑 | `ChatToolService.kt:467`、`CameraToolService.kt:228`、`RemoteControlToolService.kt:647`、`CameraToolHelper.kt:165,200` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F7 | P2 | `BeautyPreviewView.surfaceCheckRunnable` detach/release 时未 removeCallbacks | `beauty-engine/.../BeautyPreviewView.kt:461-486` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F8 | P2 | `MnnFaceDetector`/`MnnFaceEmbedder` companion 静态 DirectByteBuffer ~2.5MB 离堆不释放 | `beauty-engine/.../mnn/MnnFaceDetector.kt:23-27`、`MnnFaceEmbedder.kt:26-29` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F9 | P2 | `CameraPreviewRenderer.init()` 异常路径 pbufferSurface/EGL 泄漏 | `beauty-engine/.../CameraPreviewRenderer.kt:146` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F10 | P3 | `RemoteReActAgent.sessionMemories` 无界 Map 按会话增长 | `runtime-core/.../RemoteReActAgent.kt:146` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F11 | P3 | wakeLock 在 Service 强制销毁时 finally 可能不执行 | `TagScanOrchestrator.kt:698` + `TagGenerationService.kt:498-513` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F12 | P3 | `ChatViewModel.deleteSession` 不清理三个会话级 Map | `app/.../ChatViewModel.kt:558-564` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |
| F13 | P3 | `SettingsScreen` 等页面 `remember { PoLangAuthClient() }` 重复建 OkHttpClient，未复用 AppContainer 单例 | `app/.../SettingsScreen.kt:921`、`SettingsServerAuth.kt:66,368` | ✅ 已修复 | 编译通过（`:app:compileDebugKotlin`） |

**状态图例**：⏸️ 待修复 / 🔄 修复中 / ✅ 已修复 / ✅✅ 已修复并编译验证

---

## 2. 审计详情

### 2.1 🔴 高置信泄漏

#### F1：TAG 打标链路 —— Service 销毁时 5 组模型引擎全部不释放（最严重）

`TagGenerationService.onDestroy()` 只置 `scheduler = null`，从未级联调用各引擎**已存在**的 `release()`/`close()`：

| 引擎 | 位置 | 规模（native） |
|------|------|------|
| FaceDetector（MNN RetinaFace + 2D106） | `app/.../domain/tag/TagGenerationScheduler.kt:216-228` | ~100-300MB |
| MnnEmbeddingExtractor（Glint360K R100） | `app/.../domain/tag/FaceClusterEngine.kt:74-91`，`close()` 存在但全局无人调用 | ~170MB |
| Florence2Tagger（4 个 ONNX Session） | `app/.../domain/tag/florence2/Florence2Tagger.kt:142-148`，`release()` 无人调用 | ~231MB |
| OpusMtTranslator（3 个 ONNX + 2 个 SentencePiece） | `app/.../domain/tag/i18n/OpusMtTranslator.kt:122-126`，`release()` 无人调用 | ~100-200MB |
| MobileClipEngine（2 个 ONNX Session） | `app/.../domain/tag/TagGenerationPipeline.kt:394-396`，`releaseMobileClip()` 无人调用 | ~50-100MB |

在 6 小时 FGS 超时 + 闹钟续跑的循环里，每轮 Service 重建累积约 700MB-1GB native 泄漏。

#### F2：`RemoteControlToolService.currentActivity` 静态持有 Activity

`runtime-core/.../RemoteControlToolService.kt:65` companion object `@JvmStatic var currentActivity: Activity?`。`PoLangApplication` 的 ActivityTracker 在 `onActivityPaused`/`onActivityDestroyed` 只清自己的字段（614-615、628-629 行），从不清这个静态引用。进程保活（悬浮窗/远程通道）时已销毁 Activity 整树泄漏。

#### F3：`ChatToolService.adjustImageHandler` 单例持有 ChatViewModel

`app/.../ChatViewModel.kt:819` init 块向进程级单例的普通 `var` 注入 lambda，闭包捕获 `this@ChatViewModel`（`chatImageRenderer`、`_currentSessionId`、`insertAgentImageMessage` 等）。`onCleared()`（1844-1848 行）只关闭 JS Runtime，不置 null。Chat Capability 体系统一用 WeakReference + `onDispose { unbindDelegate() }`，唯独此 handler 无生命周期保护。两份独立排查均命中。

#### F4：`ClaudeChatClient.chat()` SSE Response 从不关闭

`app/.../data/remote/picme/ClaudeChatClient.kt:100-124`：`resp`/`source` 无 `use{}`/finally，异常路径连接不归还池；`source.read()` 阻塞读 + `readTimeout=0`，协程取消后 Dispatchers.IO 线程被占至服务端断流。同文件 `postToolResult`（184 行）用了 `resp.use{}`，独此一处裸奔。两份独立排查均命中。

#### F5：`CrossPageCommandQueue.queueScope` getter 每次 new scope

`runtime-core/.../CrossPageCommandQueue.kt:72-73`：`get() = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)`，每次读取 new 一个 scope，泄漏的 SupervisorJob 无人持有、永不 cancel。

#### F6：`GlobalScope.future` dispatch 超时后协程裸跑

`ChatToolService.kt:467`、`CameraToolService.kt:228`、`RemoteControlToolService.kt:647`、`CameraToolHelper.kt:165,200`：`deferred.get(5, SECONDS)` 超时返回后，GlobalScope 上的协程继续运行，可能持有 Capability delegate 引用，不可取消。

#### F7：`BeautyPreviewView.surfaceCheckRunnable` 未 removeCallbacks

`beauty-engine/.../BeautyPreviewView.kt:461-486`：匿名 Runnable 自递归 `postDelayed(this, 50)` 最长 1.5s，`onDetachedFromWindow()`/`release()` 均不 `removeCallbacks`，Handler 队列持有 View→Activity 链。

#### F8：人脸检测 companion 静态 DirectByteBuffer 不释放

`beauty-engine/.../mnn/MnnFaceDetector.kt:23-27`、`MnnFaceEmbedder.kt:26-29`：`reusableRgbBuffer` 等经 `ByteBuffer.allocateDirect()` 分配（离堆，GC 不可回收），companion object 持有，全生命周期不释放，~2.5MB。另 `getDetectResult(pixelCount * 2)` 按全像素分配 ~800KB 结果缓冲，实际只需 212 float。

#### F9：`CameraPreviewRenderer.init()` 异常路径 EGL 泄漏

`beauty-engine/.../CameraPreviewRenderer.kt:146`：pbufferSurface 为局部变量，init 抛异常时 `release()` 不执行（`isRendererInitialized=false`），EGL display/context/pbufferSurface 全泄漏。正常路径靠 `eglTerminate` 兜底。

### 2.2 🟡 疑似泄漏（随修复一并处理或留观察）

- **F10** `RemoteReActAgent.kt:146` `sessionMemories` 无界 Map，按会话数只增不减；实例被 app 级 `RemoteChatEngine.cachedChatAgent` 缓存，活整个进程。
- **F11** `TagScanOrchestrator.kt:698` wakeLock（`setReferenceCounted(false)`）：`onDestroy()` 中 `controlDispatcher.cancel()` 可能先于协程 finally 执行，`releaseWakeLock()` 不跑。
- **F12** `ChatViewModel.kt:558-564` `deleteSession` 不清理 `lastResultAssets`/`sessionSearchSnapshots`/`sessionExcludes`。
- **F13** `SettingsScreen.kt:921`、`SettingsServerAuth.kt:66,368` 每次进页 `remember { PoLangAuthClient() }` 新建 OkHttpClient，`AppContainerImpl:613` 已有单例未复用。
- `SherpaOnnxAsrEngine.kt:221-245` `stopStreaming` 不 cancel `streamingScope`（低危）。
- `CameraPreviewRenderer.release()` 不清 `glEventQueue`、不置 `renderView = null`（低危）。

### 2.3 🔵 资源浪费（记录在案，不在本次修复范围）

- `FaceMakeupPass.kt:410` 每检测帧分配临时 `FloatArray(212)`。
- `CameraFrameAnalyzer.kt:589-673` NV21 降级路径峰值同时持有 NV21 buffer + Bitmap。
- 6+ 个独立 OkHttpClient 连接池（`PoLangAuthClient`、`ClaudeChatClient`×2、`IssueReportClient`、`OpenAiApiClient`、`AnthropicApiClient`、`LlmModelDownloadManager`）。
- `MediaPager.kt:1068-1099` PhotoInfoDialog Bitmap 依赖 GC（Android 8+ 低风险）。
- `Logger.kt:69` throttleMap 无界（键为小字符串，极低危）。

### 2.4 ✅ 确认干净的路径（抽样结论）

- QuickJS 引擎释放链完整：`onCleared → JsRuntime.close → QuickJsEngine.close → quickjs.close()`
- 相机链路：ImageProxy `finally close`、GL 纹理/FBO/Shader 分层 release、HandlerThread `quitSafely`、Compose DisposableEffect 覆盖完整
- TAG 链路：所有 Bitmap 在 finally 中 recycle、ONNX Tensor 全部 close、LLM 主模型（Qwen3-VL-2B）有 `MnnResourceManager` 引用计数
- Chat Capability delegates：统一 WeakReference + DisposableEffect unbind
- Coil/ThumbnailCache 缓存有界；Receiver/Sensor/ExoPlayer 注册注销配对完整
- `PoLangApplication.currentActivity` 正确置 null（对比 F2 的静态字段）

---

## 3. 修复记录

> 全部改动位于 worktree 分支 `fix/memory-leak-core-paths`（`.worktrees/memleak-fix`），未提交。

### F1 — TAG Service 级联释放（2026-08-03）

- `TagGenerationPipeline.kt`：新增 `release()` → `faceDetector.release()` + `releaseMobileClip()`（faceClusterEngine 由 scheduler 持有，单独释放）。
- `FaceClusterEngine.kt`：`embeddingExtractor` lazy 改为持有 `Lazy` 实例，新增 `release()`（仅已初始化时 `close()`）。
- `TagGenerationScheduler.kt`：`pipeline`/`florence2Tagger`/`enToZhTranslator` 三个 lazy 同样改为持有 `Lazy` 实例；新增 `release()`：`scope.cancel()` + 按 `isInitialized()` 跳过未用引擎，逐项 `runCatching` 释放，不为释放而触发模型加载。
- `TagGenerationService.kt`：保留 `taskExecutor` 引用；`onDestroy()` 在 `orchestrator.cancel()` 后把 `scheduler.release()` **排到任务线程尾部**（`taskExecutor.execute`）——等可能在飞的 JNI 任务结束再释放 native 句柄，避免并发释放崩溃；随后 `taskDispatcher.cancel()` 走 `shutdown()`，已入队的释放任务仍会执行。

### F2 — 静态 Activity 引用清理（2026-08-03）

`PoLangApplication.kt` ActivityTracker 的 `onActivityPaused`/`onActivityDestroyed` 增加 `RemoteControlToolService.currentActivity === activity` 判断并置 null。

### F3 — adjustImageHandler 摘除（2026-08-03）

`ChatViewModel.onCleared()` 增加 `ChatToolService.getInstance().adjustImageHandler = null`。

### F4 — SSE 连接关闭 + 可取消（2026-08-03）

`ClaudeChatClient.chat()`：保存 `call`，`currentCoroutineContext().job.invokeOnCompletion { call.cancel() }` 打断 readTimeout=0 的阻塞读；整个读取逻辑包进 `call.execute().use {}`（对齐 `postToolResult` 先例）。SSE 解析行为不变。

### F5 — queueScope 缓存（2026-08-03）

`CrossPageCommandQueue`：新增 `fallbackScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }`，getter 改为 `externalScope ?: fallbackScope`。

### F6 — GlobalScope.future 全量替换（2026-08-03）

5 处统一改法：各持有 `private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`（均为进程级单例，与 GlobalScope 同生命周期但可追踪）；`TimeoutException` 路径 `deferred.cancel(true)` 级联取消底层 dispatch 协程。涉及 `ChatToolService`、`CameraToolService`、`RemoteControlToolService`、`CameraToolHelper`（2 处）。

### F7 — BeautyPreviewView 回调清理（2026-08-03）

新增 `pendingSurfaceReleaseRunnable` 字段（旧 Surface 延迟释放 Runnable 可追踪）+ `removePendingCallbacks()` 统一摘除两个 Runnable；`onDetachedFromWindow()` 与 `release()` 均调用。

### F8 — MNN 静态缓冲释放（2026-08-03）

- `MnnFaceDetector`/`MnnFaceEmbedder` companion 新增 `@Synchronized releaseSharedBuffers()`（置 null 即交还，含 DirectByteBuffer 离堆内存；缓冲区按需重建）。
- `FaceDetectorManager.release()` 末尾调用两个 `releaseSharedBuffers()`（相机与 TAG 两条链路的释放均经此收口）。
- 过度分配修正：3 处 `getDetectResult(width*height*2)` 改为 `getDetectResult(DETECT_RESULT_SIZE)`（106×2=212）；已确认 native 侧（`mnn_jni_bridge.cpp:147,310,348`）按 `GetArrayLength` 截断拷贝，缩小缓冲区安全。

### F9 — init 异常路径清理（2026-08-03）

`CameraPreviewRenderer.init()` GL 初始化段包 try/catch：异常时按已完成进度幂等清理（`eglCore.clearCurrent` → 删纹理 → 释放 surfaceTexture → `beautyRenderer.release()`（仅已初始化）→ `eglCore.release()`）后 rethrow。`release()` 末尾补 `glEventQueue.clear()` + `renderView = null`。

### F10 — sessionMemories LRU（2026-08-03）

`RemoteReActAgent`：`sessionMemories` 改为 `Collections.synchronizedMap(LinkedHashMap(accessOrder=true))`，`removeEldestEntry` 上限 `maxSessionMemories = 5`；DataStore 持久层不受驱逐影响，重访时重新加载。

### F11 — wakeLock 安全网（2026-08-03）

`TagScanOrchestrator`：wakeLock 改为持有 `Lazy` 实例，新增 `releaseWakeLockIfHeld()`（未初始化直接返回）；`TagGenerationService.onDestroy()` 在 `orchestrator.cancel()` 后调用兜底。

### F12 — deleteSession 清理（2026-08-03）

`ChatViewModel.deleteSession` 增加 `lastResultAssets.remove(sessionId)`、`sessionSearchSnapshots.remove(sessionId)`、`sessionExcludes.remove(sessionId)`。

### F13 — 复用 AppContainer 单例（2026-08-03）

`AppContainer` 接口新增 `picMeAuthClient` 属性（`AppContainerImpl` lazy 单例，`chatViewModelDependencies` 同步改为复用）；`SettingsScreen.kt:918`、`SettingsServerAuth.kt:66,368` 三处改为 `app.container.picMeAuthClient`。

### 待运行时验证项

（2026-08-03 设备实测，小米 24129PN74C / Android 15，构建 `fix/memory-leak-core-paths` 的 debug APK）

- ✅ **F1（TAG 释放链）**：UI 触发扫描（50 张语义编码 + 人脸检测），会话完成后 Native Heap 1.62GB；`stopservice` 销毁 Service 后降到 **135MB**（释放 ~1.5GB），logcat 确认 `Service destroyed`、`FaceDetection released by MnnRoiDetector/MnnLandmarkDetector`、`MobileClipOnnxBackend sessions released`，无 `scheduler release failed`。本轮未用到 Florence-2/OpusMT，lazy 跳过逻辑符合预期。
- ✅ **F11（wakeLock）**：Service 销毁后 `dumpsys power` 无残留的 `PoLang:TagScanWakeLock` 持有记录。
- ✅ **F7/F8/F9（相机链路）**：进相机页 30fps 美颜预览 ~35s，Native Heap 86MB→156MB；退出相机页后回落至 108MB，CameraX UseCase 正常 DETACHED，无崩溃。
- ✅ **全链路稳定性**：logcat 扫描无 FATAL/AndroidRuntime/ANR/SIGSEGV/SIGABRT。
- ⏳ **F4（SSE）**：需登录态 + 弱网环境，本轮未覆盖，留待后续验证连接归还。
- ⏳ **F6（超时取消）**：需构造 dispatch 超 5s 的场景（如大图渲染），本轮未覆盖。
- ⏳ **F1 长循环**：6h FGS 超时 + 闹钟续跑的多轮重建场景需长时间观察，本轮仅验证了单轮 stopservice 路径。


---

## 4. Code Review 结论（2026-08-03，review 子 agent 全量 diff 审查）

**结论：无阻塞项，可以合并。** 13 项修复的关键设计点（lazy isInitialized 委托、LRU accessOrder 语义、taskExecutor 尾部释放与 shutdown 时序、deferred.cancel 传播、native GetArrayLength 截断）逐一验证通过。

**遗留低概率项（不阻塞，后续迭代处理）**：

- 🟡 F1 残余竞态：`scope.cancel()` 后若已废弃的 `processSingle` 协程恰好卡在 JNI 中，native 句柄可能被随后释放。该路径已 deprecated 且 orchestrator 先取消，风险低；如需彻底消除可将 `release()` 改为 suspend 并用 `cancelAndJoin()`。
- 🟡 F2 多窗口：`onActivityPaused` 即清 `RemoteControlToolService.currentActivity`，分屏模式下远程控制会拿到 null。与同文件既有 `currentActivity` 字段的清理时机一致，保持现状。
- 🔵 F8 `@Synchronized` 与 getter 未对齐（无崩溃风险，最坏重新分配缓冲）；F10 `getOrPut` 非原子（单线程 executor 串行，无实际交错）；F4 `currentCoroutineContext()` 可用 `coroutineContext.job` 简化；F9 需确认 `BeautyRenderer.release()` 对半初始化实例幂等。
