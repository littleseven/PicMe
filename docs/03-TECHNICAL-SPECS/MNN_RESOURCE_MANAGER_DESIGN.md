# MNN 共享资源协调管理器设计文档

> **文档编号**: TECH-SPEC-MNN-RM-001
> **关联模块**: `agent-core/src/main/java/com/picme/agent/core/mnn/` (MnnResourceManager), `agent-core/src/main/java/com/picme/agent/core/voice/` (SherpaMnnAsrEngine)
> **最后更新**: 2026-06-17

---

## 1. 背景与问题

### 1.1 当前架构

PicMe 使用 MNN 3.5.0 统一构建的 `libMNN.so`，同时承载两个独立子系统：

| 子系统 | MNN API | 内存占用 | 生命周期 |
|--------|---------|----------|----------|
| **LLM** | `MNN::Transformer::Llm` | Qwen3.5-2B 约 1.5-2.5GB | 加载后常驻，无自动卸载 |
| **ASR** | `MNN::Express::Module` (via Sherpa-MNN) | Zipformer 约 100-300MB | 相机页初始化，页面退出不释放 |

### 1.2 核心冲突

`MNN::Transformer::Llm::destroy()` 在释放 LLM 独占内存时，会触及 MNN 全局内存分配器状态，导致**同一进程内仍在运行的 Sherpa-MNN ASR 崩溃**。

现有规避方案（`AgentOrchestrator.applySceneDrivenModelPolicy`）：

```kotlin
// 相机场景：不卸载模型，仅 trimMemory 清理 KV Cache
localLlmEngine.trimMemory()
```

这导致 LLM 模型在相机场景下**无法真正释放**，后台内存占用极高。

### 1.3 资源泄漏

`VoiceCommandCoordinator.release()` 未调用 `SherpaMnnAsrEngine.release()`，每次进入相机页创建新的 ASR 实例，旧实例持续占用内存。

---

## 2. 设计目标

| 目标 | 度量 |
|------|------|
| **安全共享** | LLM 与 ASR 可共存，释放时互不破坏 |
| **自动卸载** | App 后台 60s 后完全释放，无需人工干预 |
| **内存压力响应** | `onTrimMemory(CRITICAL)` 时紧急释放 |
| **零泄漏** | 页面退出时 ASR 实例 100% 释放 |
| **低延迟恢复** | 前台恢复时模型重新加载 < 3s |

---

## 3. 核心设计

### 3.1 引用计数协调

引入 `MnnResourceManager` 作为**唯一协调者**，LLM 和 ASR 分别持有独立引用计数：

```
LLM 请求加载  →  acquireLlm()   → llmRefCount++
ASR 请求加载  →  acquireAsr()   → asrRefCount++

LLM 请求释放  →  releaseLlm()
    ├─ asrRefCount == 0 → onSafeUnload()  → 真正 unload()
    └─ asrRefCount  > 0 → onSoftRelease() → trimMemory()

ASR 请求释放  →  releaseAsr()
    ├─ llmRefCount == 0 → onSafeUnload()  → 真正 release()
    └─ llmRefCount  > 0 → onSoftRelease() → stopStreaming()
```

### 3.2 联合状态机

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

### 3.3 生命周期触发矩阵

| 当前状态 | 触发事件 | LLM 动作 | ASR 动作 | 结果状态 |
|----------|----------|----------|----------|----------|
| IDLE | 用户文字输入 | `loadModel()` | 无 | LLM_ONLY |
| IDLE | 进入相机页 + 语音开启 | `trimMemory()`（如已加载） | `tryInitRecognizer()` | SHARED / ASR_ONLY |
| LLM_ONLY | 进入相机页 + 语音开启 | 保持 | `tryInitRecognizer()` | SHARED |
| ASR_ONLY | 用户文字输入 | `loadModel()` | 保持 | SHARED |
| SHARED | 离开相机页 | `trimMemory()` | `release()` → softRelease | LLM_ONLY |
| SHARED | 文字聊天结束 | `unload()` → softRelease | 保持 | ASR_ONLY |
| SHARED | 后台 30s | `trimMemory()` | `stopStreaming()` | SHARED (soft) |
| SHARED | 后台 60s / 内存压力 | `unload()` | `release()` | IDLE |
| * | `onTrimMemory(CRITICAL)` | `unload()` | `release()` | IDLE |

---

## 4. 组件职责

### 4.1 MnnResourceManager

- **位置**: `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
- **职责**: 引用计数管理、生命周期监听、内存压力响应、事件分发
- **线程安全**: 所有计数器使用 `AtomicInteger`，监听器使用 `CopyOnWriteArrayList`
- **单例**: 进程级唯一实例

### 4.2 LocalLlmEngine

- **位置**: `agent-core/src/main/java/com/picme/agent/core/LocalLlmEngine.kt`
- **变更**:
  - `loadModel()` 成功后调用 `resourceManager.acquireLlm()`
  - `unload()` 改为调用 `resourceManager.releaseLlm()`，传入 `performUnload` 和 `trimMemory` 回调
  - 注册 `SoftTrimListener` 和 `SafeUnloadListener` 响应全局事件

### 4.3 SherpaMnnAsrEngine

- **位置**: `features/camera/voice/SherpaMnnAsrEngine.kt`
- **变更**:
  - `tryInitRecognizer()` 成功后调用 `resourceManager.acquireAsr()`
  - `release()` 改为调用 `resourceManager.releaseAsr()`，传入 `performUnload` 和 `softRelease` 回调
  - 新增 `softRelease()`：仅停止流式识别，保留 recognizer

### 4.4 VoiceCommandCoordinator

- **位置**: `features/camera/voice/VoiceCommandCoordinator.kt`
- **变更**:
  - `release()` 中新增 `(asrEngine as? SherpaMnnAsrEngine)?.release()`
  - 修复 ASR 资源泄漏

### 4.5 CameraScreen

- **位置**: `features/camera/CameraScreen.kt`
- **变更**:
  - 新增 `LifecycleEventObserver` 监听 ON_RESUME / ON_PAUSE
  - 联动 `MnnResourceManager.onAppForeground()` / `onAppBackground()`

### 4.6 PicMeApplication

- **位置**: `PicMeApplication.kt`
- **变更**:
  - `ActivityTracker` 增加 `activityCount` 计数
  - `onActivityStarted()` 第一个 Activity 启动时调用 `onAppForeground()`
  - `onActivityStopped()` 最后一个 Activity 停止时调用 `onAppBackground()`

---

## 5. 关键时序

### 5.1 正常相机场景流程

```
用户打开相机页
    │
    ▼
CameraScreen 创建 SherpaMnnAsrEngine
    │
    ▼
ASR tryInitRecognizer() 成功
    │
    ▼
acquireAsr() → asrRefCount = 1
    │
    ▼
用户语音唤醒 → ASR 识别 → 送入 LLM
    │
    ▼
LLM loadModel() 成功
    │
    ▼
acquireLlm() → llmRefCount = 1
    │
    ▼
┌─────────────────────────────────────┐
│           SHARED 状态               │
│  LLM 推理 + ASR 监听 同时运行        │
└─────────────────────────────────────┘
    │
    ▼
用户离开相机页
    │
    ▼
VoiceCommandCoordinator.release()
    │
    ▼
SherpaMnnAsrEngine.release()
    │
    ▼
releaseAsr() → asrRefCount = 0, llmRefCount = 1
    │
    ▼
onSoftRelease() → 停止流式识别，保留 recognizer
    │
    ▼
CameraScreen DisposableEffect onDispose
    │
    ▼
（LLM 保持 LLM_ONLY 状态，等待下次使用或后台超时卸载）
```

### 5.2 后台自动卸载流程

```
用户按 Home 键
    │
    ▼
最后一个 Activity onStopped()
    │
    ▼
MnnResourceManager.onAppBackground()
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

---

## 6. 线程安全

| 组件 | 关键操作 | 同步机制 |
|------|----------|----------|
| MnnResourceManager | 引用计数 | `AtomicInteger` |
| MnnResourceManager | 监听器列表 | `CopyOnWriteArrayList` |
| MnnResourceManager | 后台调度 | `AtomicBoolean` 防重复 |
| LocalLlmEngine | load/unload/generate | `Mutex` |
| SherpaMnnAsrEngine | recognizer 创建/释放 | `synchronized(initLock)` |
| SherpaMnnAsrEngine | 流式识别状态 | `AtomicBoolean` |

---

## 7. 调试与观测

### 7.1 日志标签

| 标签 | 来源 | 关键日志 |
|------|------|----------|
| `MnnResourceManager` | `MnnResourceManager` | refCount 变化、前后台切换、内存压力级别 |
| `LocalLlmEngine` | `LocalLlmEngine` | load/unload/trimMemory 状态转换 |
| `SherpaMnnAsr` | `SherpaMnnAsrEngine` | init/release/softRelease 状态转换 |

### 7.2 内存统计 API

```kotlin
val stats = MnnResourceManager.getInstance(context).getMemoryStats()
// stats.llmRefCount
// stats.asrRefCount
// stats.appInForeground
// stats.javaHeapUsedMB
// stats.availableMemoryMB
// stats.lowMemory
```

---

## 8. 风险与回退

| 风险 | 缓解措施 |
|------|----------|
| MNN destroy 仍影响 ASR | 引用计数确保不会单方 destroy；若仍有问题，可进一步在 JNI 层加全局锁 |
| 后台 60s 卸载导致下次加载慢 | 保留 trimMemory 作为中间态；前台恢复时预加载可由场景策略触发 |
| 计数器泄漏导致永不释放 | 所有 acquire/release 配对都有明确调用点，可通过日志审计追踪 |
| 多线程竞争 | `AtomicInteger` + `Mutex` + `synchronized` 三层防护 |

---

## 9. 验收标准

- [ ] `MnnResourceManager` 单例可正常获取，引用计数正确增减
- [ ] LLM 加载后 `llmRefCount == 1`，卸载后 `llmRefCount == 0`
- [ ] ASR 初始化后 `asrRefCount == 1`，释放后 `asrRefCount == 0`
- [ ] 双方同时存在时，单方 release 只触发 softRelease
- [ ] 双方均 release 后触发真正的 native unload
- [ ] App 后台 30s 触发 softTrim，60s 触发 safeUnload
- [ ] `onTrimMemory(CRITICAL)` 立即触发 safeUnload
- [ ] 离开相机页后 ASR 不再泄漏
- [ ] 编译通过，无 lint 错误

---

## 10. 关联文档

- `AGENTS.md` — Agent First 研发流程与协作规范
- `docs/03-TECHNICAL-SPECS/CAMERA_PREVIEW_TECH_SPEC.md` — 相机预览技术规范
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — 渲染管线规范
