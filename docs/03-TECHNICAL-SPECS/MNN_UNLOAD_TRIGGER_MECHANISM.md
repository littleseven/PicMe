# MNN 模型卸载触发机制文档

> **文档编号**: TECH-SPEC-MNN-UNLOAD-001
> **关联模块**: `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
> **最后更新**: 2026-06-08

---

## 1. 触发源总览

| 触发源 | 触发条件 | 动作 | 延迟 | 优先级 |
|--------|----------|------|------|--------|
| **App 后台** | 最后一个 Activity `onStopped()` | `onAppBackground()` | 30s softTrim → 60s safeUnload | 中 |
| **CameraScreen 生命周期** | `ON_PAUSE` | `onAppBackground()` | 同上 | 中 |
| **内存压力** | `onTrimMemory(RUNNING_MODERATE)` | `notifySoftTrim()` | 立即 | 低 |
| **内存压力** | `onTrimMemory(RUNNING_LOW)` | `notifySafeUnload()` | 立即 | 高 |
| **内存压力** | `onTrimMemory(RUNNING_CRITICAL)` | `notifySafeUnload()` | 立即 | 紧急 |
| **内存压力** | `onTrimMemory(UI_HIDDEN)` | `onAppBackground()` | 30s → 60s | 中 |
| **内存压力** | `onTrimMemory(COMPLETE)` | `notifySafeUnload()` | 立即 | 紧急 |
| **页面退出** | `VoiceCommandCoordinator.release()` | `releaseAsr()` → softRelease | 立即 | 中 |
| **文字聊天结束** | `AgentOrchestrator.unloadModel()` | `releaseLlm()` → softRelease | 立即 | 低 |
| **模型切换** | `loadModel()` 加载新模型 | `unload()` 旧模型 | 立即 | 中 |

---

## 2. 引用计数协调机制

### 2.1 核心规则

```
acquireLlm()  → llmRefCount++
acquireAsr()  → asrRefCount++

releaseLlm()
    ├─ asrRefCount == 0 → onSafeUnload()  → 真正 nativeDestroy()
    └─ asrRefCount  > 0 → onSoftRelease() → trimMemory()（保留模型）

releaseAsr()
    ├─ llmRefCount == 0 → onSafeUnload()  → 真正 recognizer.release()
    └─ llmRefCount  > 0 → onSoftRelease() → stopStreaming()（保留模型）
```

### 2.2 状态流转图

```
                    ┌─────────────────────────────────────┐
                    │         MNN_RESOURCE_STATE          │
                    └─────────────────────────────────────┘

    ┌──────────┐    load()    ┌──────────┐   both agree   ┌──────────┐
    │  IDLE    │ ───────────→ │  SHARED  │ ─────────────→ │ UNLOADED │
    │(无模型)   │              │(LLM+ASR) │   unload()     │(已释放)   │
    └──────────┘              └────┬─────┘                └──────────┘
         ↑                         │
         │              ┌──────────┴──────────┐
         │              │                     │
         │         trim()                asr_stop()
         │              ↓                     ↓
         │       ┌──────────┐          ┌──────────┐
         └───────│ LLM_ONLY │          │ ASR_ONLY │
                 │(仅LLM常驻)│          │(仅ASR常驻)│
                 └──────────┘          └──────────┘
```

---

## 3. 各触发源详细说明

### 3.1 App 前后台切换

**触发位置**: `PicMeApplication.ActivityTracker`

```kotlin
override fun onActivityStarted(activity: Activity) {
    if (activityCount == 0) {
        MnnResourceManager.getInstance(this@PicMeApplication).onAppForeground()
    }
    activityCount++
}

override fun onActivityStopped(activity: Activity) {
    activityCount--
    if (activityCount == 0) {
        MnnResourceManager.getInstance(this@PicMeApplication).onAppBackground()
    }
}
```

**时序**:
```
用户按 Home 键
    │
    ▼
最后一个 Activity onStopped()
    │
    ▼
onAppBackground()
    │
    ▼
调度协程：delay 30s
    │
    ▼
30s 后检查：仍后台 + 无引用？
    │
    ├── 是 → notifySoftTrim()
    │           ├── LocalLlmEngine.onSoftTrim() → trimMemory()
    │           └── SherpaMnnAsrEngine.onSoftTrim() → stopStreaming()
    │
    ▼
delay 再 30s（累计 60s）
    │
    ▼
60s 后检查：仍后台 + 无引用？
    │
    ├── 是 → notifySafeUnload()
    │           ├── LocalLlmEngine.onSafeUnload() → performUnload()
    │           └── SherpaMnnAsrEngine.onSafeUnload() → performUnload()
    │
    ▼
┌─────────────────────────────────────┐
│            IDLE 状态                │
│     LLM + ASR 完全释放              │
└─────────────────────────────────────┘
```

**关键参数**:
- `BACKGROUND_UNLOAD_DELAY_MS = 30000L`
- `BACKGROUND_FORCE_UNLOAD_DELAY_MS = 60000L`

---

### 3.2 CameraScreen 生命周期

**触发位置**: `CameraScreen.kt`

```kotlin
val cameraLifecycleOwner = LocalLifecycleOwner.current
val resourceManager = remember { MnnResourceManager.getInstance(context) }
DisposableEffect(cameraLifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> resourceManager.onAppForeground()
            Lifecycle.Event.ON_PAUSE -> resourceManager.onAppBackground()
            else -> {}
        }
    }
    cameraLifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
        cameraLifecycleOwner.lifecycle.removeObserver(observer)
    }
}
```

**注意**: CameraScreen 的 ON_PAUSE 与 ActivityTracker 的 onActivityStopped 可能同时触发，但 `backgroundUnloadScheduled` 的 `AtomicBoolean` 确保只调度一次。

---

### 3.3 系统内存压力

**触发位置**: `MnnResourceManager.init()` 注册的 `ComponentCallbacks2`

```kotlin
private fun handleMemoryPressure(level: Int) {
    when (level) {
        TRIM_MEMORY_RUNNING_MODERATE -> notifySoftTrim()
        TRIM_MEMORY_RUNNING_LOW,
        TRIM_MEMORY_RUNNING_CRITICAL -> notifySafeUnload()
        TRIM_MEMORY_UI_HIDDEN -> {
            if (!isAnyRequested) onAppBackground()
        }
        TRIM_MEMORY_COMPLETE -> notifySafeUnload()
    }
}
```

**Android 内存压力级别说明**:

| 级别 | 含义 | 本系统响应 |
|------|------|-----------|
| `RUNNING_MODERATE` | 系统内存略有压力 | softTrim（清理 KV Cache） |
| `RUNNING_LOW` | 系统内存紧张 | safeUnload（完全释放） |
| `RUNNING_CRITICAL` | 系统内存极度紧张 | safeUnload（完全释放） |
| `UI_HIDDEN` | 应用 UI 不可见 | 如果无引用，调度后台卸载 |
| `COMPLETE` | 系统内存耗尽 | safeUnload（完全释放） |

---

### 3.4 页面级释放

**触发位置**: `VoiceCommandCoordinator.release()`

```kotlin
fun release() {
    stopWakeWordListening()
    stopPushToTalk()
    taskChannel.close()
    (asrEngine as? SherpaMnnAsrEngine)?.release()
    Logger.d(tag, "VoiceCommandCoordinator released")
}
```

**调用链**:
```
CameraScreen DisposableEffect onDispose
    │
    ▼
VoiceCommandCoordinator.release()
    │
    ▼
SherpaMnnAsrEngine.release()
    │
    ▼
resourceManager.releaseAsr(owner, onSafeUnload, onSoftRelease)
    │
    ├─ llmRefCount == 0 → performUnload() → recognizer?.release()
    └─ llmRefCount  > 0 → softRelease() → stopStreaming()
```

---

### 3.5 模型切换

**触发位置**: `LocalLlmEngine.loadModel()`

```kotlin
suspend fun loadModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
    engineMutex.withLock {
        if (client.isLoaded && currentModelId == modelId) {
            return@withLock Result.success(Unit)
        }
        if (client.isLoaded) {
            client.unload()  // ← 直接卸载旧模型
            currentModelId = null
        }
        // ... 加载新模型
    }
}
```

**注意**: 模型切换时的 `unload()` 不走 `MnnResourceManager`，因为这是**同类型资源替换**，不是**引用释放**。

---

## 4. 防重复机制

### 4.1 后台卸载防重调度

```kotlin
private val backgroundUnloadScheduled = AtomicBoolean(false)

fun onAppBackground() {
    if (backgroundUnloadScheduled.compareAndSet(false, true)) {
        scope.launch {
            delay(BACKGROUND_UNLOAD_DELAY_MS)
            // ... softTrim
            delay(BACKGROUND_FORCE_UNLOAD_DELAY_MS - BACKGROUND_UNLOAD_DELAY_MS)
            // ... safeUnload
            backgroundUnloadScheduled.set(false)
        }
    }
}

fun onAppForeground() {
    cancelBackgroundUnload()  // AtomicBoolean.set(false)
}
```

### 4.2 引用计数防负值

```kotlin
fun releaseLlm(owner: String, onSafeUnload: () -> Unit, onSoftRelease: () -> Unit) {
    val count = llmRefCount.decrementAndGet()
    if (count <= 0) {
        llmRefCount.set(0)  // 防负值
        // ...
    }
}
```

---

## 5. 日志追踪

### 5.1 关键日志标签

| 标签 | 来源 | 关键日志模式 |
|------|------|-------------|
| `MnnResourceManager` | `MnnResourceManager` | `LLM acquired/released by X, refCount=N` |
| `MnnResourceManager` | `MnnResourceManager` | `App entered foreground/background` |
| `MnnResourceManager` | `MnnResourceManager` | `Memory pressure: LEVEL, action` |
| `LocalLlmEngine` | `LocalLlmEngine` | `LLM fully unloaded` / `LLM memory trimmed` |
| `SherpaMnnAsr` | `SherpaMnnAsrEngine` | `ASR fully unloaded` / `ASR soft released` |
| `VoiceCommand` | `VoiceCommandCoordinator` | `VoiceCommandCoordinator released` |

### 5.2 ADB 日志过滤命令

```bash
# 查看所有 MNN 资源管理日志
adb logcat -s "MnnResourceManager:*" "LocalLlmEngine:*" "SherpaMnnAsr:*" "VoiceCommand:*" -v time

# 只看引用计数变化
adb logcat -s "MnnResourceManager:*" -v time | grep -E "acquired|released|refCount"

# 只看卸载事件
adb logcat -s "MnnResourceManager:*" "LocalLlmEngine:*" "SherpaMnnAsr:*" -v time | grep -E "unloaded|trimmed|soft released"
```

---

## 6. 调试 API

### 6.1 内存统计

```kotlin
val stats = MnnResourceManager.getInstance(context).getMemoryStats()
Log.d("Debug", """
    LLM ref: ${stats.llmRefCount}
    ASR ref: ${stats.asrRefCount}
    Foreground: ${stats.appInForeground}
    Heap: ${stats.javaHeapUsedMB}MB
    Available: ${stats.availableMemoryMB}MB
    LowMemory: ${stats.lowMemory}
""".trimIndent())
```

### 6.2 强制触发（调试用）

```kotlin
// 强制 softTrim
MnnResourceManager.getInstance(context).apply {
    // 通过反射或直接调用内部方法（需添加 @VisibleForTesting）
}

// 模拟内存压力
(context as? Application)?.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
```

---

## 7. 相关文件

| 文件 | 说明 |
|------|------|
| `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt` | 协调管理器核心实现 |
| `agent-core/src/main/java/com/picme/agent/core/LocalLlmEngine.kt` | LLM 引擎，接入 ResourceManager |
| `app/src/main/java/com/picme/features/camera/voice/SherpaMnnAsrEngine.kt` | ASR 引擎，接入 ResourceManager |
| `app/src/main/java/com/picme/features/camera/voice/VoiceCommandCoordinator.kt` | 语音协调器，触发 ASR 释放 |
| `app/src/main/java/com/picme/features/camera/CameraScreen.kt` | 相机页面，生命周期绑定 |
| `app/src/main/java/com/picme/PicMeApplication.kt` | 应用入口，前后台追踪 |
