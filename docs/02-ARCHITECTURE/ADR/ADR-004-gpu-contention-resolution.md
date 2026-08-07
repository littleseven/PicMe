# ADR-004: Adreno GPU 争抢问题分析与解决方案

> **状态**: 已接受（历史记录） · **优先级**: P0 · **最后更新**: 2026-08-03

---

> ## ⛔ 状态更新（2026-08-03）：三方 GPU 争抢场景不再适用，本决策保留为历史记录
>
> 本 ADR 分析的 GPU 争抢三方——OpenGL ES 渲染、ncnn Vulkan（人脸检测）、ggml/llama.cpp（端侧文本 LLM）——其中两方已移除：**ncnn Vulkan 已于 2026-07 整删**（人脸检测改走 MediaPipe / MNN），**ggml/llama.cpp 端侧文本 LLM 已于 2026-08-02 整体移除**（文本推理全远程，`LocalLlmEngine` 仅存 Qwen3-VL-2B 端侧 VLM 打标 `imageInference`）。
> 正文（§1-§3）中以当前时态描述的「两个/三个 GPU 消费者」争抢场景不再代表现状，**全部保留为历史记录**，用于理解决策脉络与 Adreno GPU 争抢的排查方法论。

## 1. 背景：PoLang 的 GPU 消费者

PoLang 是一个强视觉应用，同时使用 GPU 进行**实时美颜渲染**和**端侧 LLM 推理**。当前应用中有两个 GPU 消费者：

```
┌──────────────────────────────────────────────────────────────────────┐
│                          GPU 消费者分布                                │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. OpenGL ES 渲染管线 (beauty-engine)                               │
│     ├── Camera Preview → SurfaceView (30fps 实时)                    │
│     ├── BeautyRenderer 多 Pass（磨皮/美白/美型/妆容/滤镜）           │
│     ├── PhotoProcessorImpl 离屏渲染（拍照）                          │
│     └── 线程: 独立渲染线程 + EGL 上下文                              │
│                                                                      │
│  2. LLM 推理 (runtime-core)                                          │
│     ├── 引擎: MNN-LLM / ggml                                         │
│     ├── 后端选择: OpenCL (当前) / CPU (降级)                          │
│     └── 线程: LLM-Model-Thread (专用线程)                            │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

> **历史说明**：2026-07-05 前曾有第三个消费者 `ncnn Vulkan`（人脸检测），NCNN 路径已按激进策略完全移除。当前人脸检测走 MediaPipe / MNN，均不占用 Vulkan compute 队列。本 ADR 的争抢分析框架仍适用于 OpenGL ES + OpenCL 双 GPU 消费者场景。

**关键约束**：两者运行在**同一块 Adreno GPU** 上，且 Android 的 GPU 调度器 (kgsl) 不支持跨 API 的细粒度优先级抢占。

---

## 2. 事件记录

### 事件 1: MNN Vulkan 与 OpenGL ES 冲突（~2026-05）

#### 现象
MNN-LLM 使用 Vulkan 后端进行 LLM 推理时，出现 `vk::DeviceLostError`，与当前事件 2 类似。

#### 当时的环境
| 组件 | GPU 使用 | 备注 |
|------|----------|------|
| OpenGL ES 渲染管线 | ✅ 持续占用 | Camera preview + Beauty Shader |
| ncnn Vulkan | ✅ 持续占用 | 人脸检测 |
| MNN Vulkan (LLM) | ✅ 新增 | 旧版 LLM 引擎，后迁移至 llama.cpp |
| ggml-vulkan | ❌ 未引入 | llama.cpp 尚未接入 |

#### 当时的应对
在 `CMakeLists.txt` 中以注释记录：
```cmake
# ggml-vulkan 已禁用 — 与 MNN 的 GPU 上下文冲突 (Adreno 830)
```

后续 MNN 整体从仓库移除（包括 MNN-LLM 和 MNN 人脸检测），团队认为移除 MNN 后 GPU 冲突问题会自然解决。

---

### 事件 2: ggml-vulkan + ncnn Vulkan + EGL 三方争抢（2026-06-15）

#### 触发条件
MNN 移除后，重新为 llama.cpp 启用 `GGML_VULKAN=ON`，编译了 `libggml-vulkan.so`（79MB）。

#### 崩溃日志关键信息
```
19:07:01.726 Fence: waitForever: fence 226 didn't signal in 3000 ms        ← GPU fence 超时
19:07:01.726 Fence: Throttling EGL Production: fence 225 didn't signal      ← EGL 渲染被节流
19:07:04.001 HWUI: Davey! duration=5279ms                                   ← 主线程卡顿 5 秒
19:07:04.008 Choreographer: Skipped 255 frames!                             ← UI 丢帧 255 帧
19:07:04.027 libggml-vulkan: vk::Queue::submit: ErrorDeviceLost             ← Vulkan 设备丢失
19:07:04.028 libc: Fatal signal 6 (SIGABRT)                                 ← 进程崩溃
```

#### 崩溃链路
```
ncnn Vulkan compute 持续运行 (每帧人脸检测)
        +
OpenGL ES 渲染持续运行 (30fps 预览 + 美颜)
        +
ggml-vulkan submit LLM 计算任务
        ↓
GPU 过载 → fence 超时 (3s)
        ↓
kgsl 驱动重置 GPU
        ↓
vk::Queue::submit 返回 VK_ERROR_DEVICE_LOST
        ↓
libggml-vulkan C++ 异常 → SIGABRT
```

#### 根因分析
| 因素 | 说明 |
|------|------|
| **直接原因** | `vk::Queue::submit: ErrorDeviceLost`，Vulkan 设备被 GPU 驱动重置 |
| **根本原因** | Adreno 830 (Xiaomi 15) 的 kgsl 驱动无法同时协调 **3 个 GPU 上下文**（EGL + ncnn Vulkan + ggml Vulkan） |
| **触发条件** | LLM 推理（ggml-vulkan）在渲染 + 人脸检测的 GPU 负载高峰时提交计算任务 |
| **本质问题** | 移动端 GPU 的多个上下文（OpenGL ES + Vulkan 多实例）并发访问同一硬件时，Adreno 驱动缺乏可靠的时间片调度机制 |

---

## 3. 决策：LLM 推理使用 CPU-only，GPU 仅用于渲染管线

### 3.1 决策内容

> **所有 LLM 推理（llama.cpp）强制使用 CPU 后端，禁止启用任何 GPU 加速后端。**
> GPU 资源完全留给 beauty-engine 的渲染管线（OpenGL ES + ncnn Vulkan）。

### 3.2 决策理由

| 理由 | 说明 |
|------|------|
| **稳定性优先** | GPU 争抢导致的 DeviceLost 是致命的（进程崩溃），而 CPU 推理只是速度稍慢 |
| **Qwen2.5 0.5B 模型小** | 0.5B 参数量在 CPU 上 + KleidiAI + NEON DOTPROD 已足够流畅 |
| **渲染不可降级** | 美颜渲染的实时性（30fps + <100ms 交互延迟）比 LLM 推理速度更关键 |
| **APK 体积收益** | 移除 `libggml-vulkan.so`（79MB），APK 减少 ~79MB |
| **ncnn Vulkan 不可降级** | 人脸检测是每帧必需的，切换到 CPU 会显著增加推理耗时 |

### 3.3 不采纳方案

| 方案 | 拒绝原因 |
|------|---------|
| **让 ncnn 用 CPU** | 人脸检测每帧都要跑，CPU 推理耗时翻倍，影响 30fps 实时性 |
| **分时使用 GPU** | 需要跨进程/跨 API 同步，Android 无标准方案，复杂度高 |
| **多个 Vulkan 实例共享 VkDevice** | ncnn 和 ggml 各自管理自己的 VkDevice，无法共享 |
| **降低 ggml-vulkan 优先级** | Adreno 驱动不支持 Vulkan 队列优先级控制 |

---

## 4. 技术实现

### 4.1 编译配置（CPU-only + NEON）

```bash
# llama.cpp Android NDK 编译（Vulkan=OFF）
cmake \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DGGML_VULKAN=OFF \              # ← 关键：禁用 Vulkan
    -DGGML_CPU=ON \
    -DGGML_CPU_KLEIDIAI=ON \         # KleidiAI 矩阵乘优化
    -DGGML_CPU_REPACK=ON \
    -DGGML_LLAMAFILE=ON \            # 快速 token 生成
    -DGGML_OPENMP=OFF \
    -DCMAKE_C_FLAGS="-march=armv8.2-a+dotprod" \
    -DCMAKE_CXX_FLAGS="-march=armv8.2-a+dotprod"
```

### 4.2 CMakeLists.txt 变更

```cmake
# 移除 ggml_vulkan 导入（原样）
- set(GGML_VULKAN_SO_PATH "...")
- add_library(ggml_vulkan SHARED IMPORTED)
- target_link_libraries(agent_native ggml_vulkan)
- target_compile_definitions(agent_native GGML_USE_VULKAN=1)

# 保留 CPU 优化
target_compile_definitions(agent_native PRIVATE
    GGML_USE_CPU_KLEIDIAI=1
    GGML_USE_LLAMAFILE=1
)
```

### 4.3 jniLibs 产物变更

| 文件 | 大小 (旧) | 大小 (新) | 变化 |
|------|----------|----------|------|
| `libllama.so` | 34MB | 34MB | — |
| `libggml-vulkan.so` | **79MB** | **已删除** | 📉 |
| `libggml-cpu.so` | 5.2MB | 5.2MB | — |
| `libggml-base.so` | 5.3MB | 5.3MB | — |
| `libggml.so` | 477KB | 476KB | — |
| **合计** | **~124MB** | **~45MB** | **-64%** |

---

## 5. 后果分析

### 正面影响
- ✅ 彻底消除 GPU DeviceLost 崩溃（P0）
- ✅ APK 体积减少 ~79MB
- ✅ LLM 推理稳定性达到 100%（不再因 GPU 争抢崩溃）
- ✅ 渲染管线保持 30fps 流畅

### 负面影响
- ⚠️ LLM 推理速度下降（CPU 推理 vs GPU 推理 ~2-3x 差距）
- ⚠️ 纯 CPU 推理增加电池消耗（但 Qwen2.5 0.5B 模型较小，影响有限）

### 性能预期

| 指标 | GPU (Vulkan) | CPU (NEON+KleidiAI) | 差异 |
|------|-------------|---------------------|------|
| Prefill (首 token) | ~200ms | ~500ms | ~2.5x |
| Decode (生成) | ~15 tokens/s | ~6-8 tokens/s | ~2x |
| 稳定性 | ❌ DeviceLost 崩溃 | ✅ 无崩溃 | 决定性差异 |

---

## 6. 后续演进

### 6.1 如果未来需要 GPU LLM 推理

如果后续需要更大的模型（如 7B+），CPU 推理无法满足性能要求时，需采用以下方案之一：

| 方案 | 可行性 | 说明 |
|------|--------|------|
| **独占 GPU 模式** | 低 | LLM 推理时暂停美颜渲染 → 用户体验不可接受 |
| **Vulkan VkDevice 共享** | 中 | 让 ncnn 和 ggml 共享同一个 VkDevice 和 VkQueue |
| **升级 Adreno 驱动** | 低 | 厂商驱动更新不可控 |
| **NPU/专用推理芯片** | 未来 | 等待高通 AI Engine 的标准化移动端 LLM SDK |
| **远程推理** | 已实现 | 回退到 Cloudflare Gateway → Kimi API，已有完整链路 |

### 6.2 监控建议

- 如果后续尝试重新启用任何 GPU 加速后端，**必须先在 Adreno 830 上做 30 分钟压力测试**
- 监控指标：`vk::Queue::submit` 返回码、GPU fence 超时日志、EGL 生产节流日志
- 上述三个指标任何一个出现，都意味着 GPU 争抢问题复现

---

## 7. 相关文件

| 文件 | 说明 |
|------|------|
| ~~`agent-core/src/main/cpp/CMakeLists.txt`~~ | ⚠️ 已失效（随端侧文本 LLM 移除，`agent-core` 现为纯 Java 模块，无 `cpp/` 目录） |
| ~~`agent-core/src/main/jniLibs/arm64-v8a/`~~ | ⚠️ 已失效（同上，编译产物目录已删除） |
| ~~`engines/beauty-engine/src/main/cpp/ncnn_face_detector.cpp`~~ | ⚠️ 已失效（ncnn 整删，源码不存在；原 ncnn Vulkan 配置 `net_.opt.use_vulkan_compute = true`） |
| ~~`docs/03-TECHNICAL-SPECS/LLM_ENGINE_MIGRATION_MNN_TO_LLAMACPP.md`~~ | ⚠️ 已失效（文档已删除，原 LLM 引擎迁移技术规范） |
| `ADR/ADR-001-beauty-engine-architecture.md` | 美颜引擎架构（含 EGL 渲染线程设计） |

---

## 8. 变更记录

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|---------|------|
| 2026-06-15 | v1.0 | 初始版本，记录事件 1 + 事件 2 两次 GPU 争抢问题及决策 | RD |
| 2026-08-03 | v1.1 | 添加状态更新块：ncnn Vulkan 与 ggml/llama.cpp 端侧文本 LLM 均已移除，争抢场景不再适用，保留为历史记录；修正 §7 失效文件引用 | AI Agent |

---

> **维护者**：项目开发者、AI Agent
> **最后更新**：2026-08-03
> **状态**：已接受（历史记录）
