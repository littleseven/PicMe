# PicMe 端侧推理引擎与模型全景梳理

> **版本**: 1.2  
> **状态**: 生效中  
> **最后更新**: 2026-07-06  
> **维护者**: RD Agent  
> **范围**: `:app`、`:runtime-core`、`:beauty-engine`、`:beauty-api`、`:sentencepiece` 模块中所有本地推理引擎、模型、量化策略、tokenizer 及运行时瓶颈

---

## 1. 概述

PicMe（觅影相册）当前在端侧同时运行 **7 套推理框架**、**14+ 个模型**，覆盖美颜预览、人脸检测、语音识别、关键词唤醒、大语言模型、图像理解、语义搜索、OCR、机器翻译等场景。由于历史演进和实验性质，推理栈呈现**多框架并存、量化程度不一、生命周期耦合、资源竞争复杂**的特点。

本文档按**页面/场景**梳理当前推理引擎、模型、作用及方案，指出运行时性能瓶颈与设计不合理之处，为后续模型精简、量化统一、生命周期解耦提供决策依据。

---

## 2. 推理引擎总览

| 引擎 | 版本/包 | 运行模块 | 主要用途 | 原生库 |
|------|---------|----------|----------|--------|
| **MNN** | 3.5.0 | `:runtime-core`、`:beauty-engine` | LLM（Qwen）、人脸 ROI/关键点/Embedding、视觉编码器 | `libMNN.so`、`libMNN_CL.so` |
| **MNN-LLM** | 内置于 MNN | `:runtime-core` | Qwen3.5-2B/0.8B 多模态 LLM | `libMNN.so` + `libmnn_llm.so` |
| **ONNX Runtime** | 1.24.3 | `:app`、`:runtime-core` | MobileCLIP、OPUS-MT、Sherpa-ONNX ASR/KWS | `libonnxruntime.so` |
| **Sherpa-ONNX** | 1.13.3 | `:runtime-core` | 流式 ASR、关键词唤醒（KWS） | 通过 ONNX Runtime 运行 |
| **MediaPipe Tasks Vision** | 0.10.26 | `:app`、`:beauty-engine` | 人脸 468 点 Landmark（默认路径） | `face_landmarker.task` |
| **ML Kit** | 多个 | `:app` | 人脸检测、图像标注、OCR | Google Play Services / 内置 TFLite |
| **SentencePiece** | 项目本地 | `:sentencepiece` | OPUS-MT 分词（`source.spm` / `target.spm`） | `libsentencepiece_android.so` |

> **ABI 过滤**: 仅 `arm64-v8a`。`app/build.gradle.kts` 中使用 `pickFirsts` 解决 `libonnxruntime.so` 冲突。

---

## 3. 按页面/场景的推理方案矩阵

### 3.1 相机预览页（CameraScreen）

| 功能 | 引擎/模型 | 作用 | 生命周期 | 备注 |
|------|-----------|------|----------|------|
| 实时美颜渲染 | **大美丽（BIG_BEAUTY）** 自研 OpenGL ES | 磨皮、美白、大眼、瘦脸、唇色、腮红、风格滤镜 | 相机页常驻 | 零拷贝 GPU 管线，目标 30-60fps |
| 人脸检测（默认） | **MediaPipe Face Landmarker** 468 点 | 输出 468 点 → 映射为 106 点 | 相机页初始化 | 首选路径，零拷贝 `ImageProxy` |
| 人脸检测（备选） | **MNN RetinaFace det_500m** + **2D106 landmark** | ROI + 106 点 | 相机页初始化 | OpenCL GPU 优先 |
| 语音唤醒词 | **KwakeWordKwsEngine**（Sherpa-ONNX KeywordSpotter） | 检测 "小觅" 等唤醒词 | 相机页常驻监听 | Sherpa-ONNX KWS 已落地；VAD+ASR 方案保留为 KWS 不可用时回退 |
| 语音指令识别 | **Sherpa-ONNX Zipformer ASR** (INT8) | 唤醒后转录指令 | 唤醒后按需加载 | 与 LLM 分时复用 |
| Agent 指令执行 | **Remote LLM**（默认）/ **Qwen3.5-2B-MNN**（本地降级） | 解析并执行语音/文字指令 | 跨页面保活 | 默认远程优先策略 |

**当前方案问题**：
- 人脸检测双引擎并存（MediaPipe 默认 + MNN 备选），配置较复杂；`FaceDetectorManager` 在 `updatePipelineConfig()` 前返回 `null` 导致静默失败。
- `MnnRoiDetector` 写死 `requireGpu=true`，GPU 初始化失败时无 CPU 降级路径。
- 语音唤醒已迁移到 Sherpa-ONNX KWS（~14MB INT8），相机页常驻监听；原 VAD+ASR 文本匹配方案保留为 KWS 模型缺失时的回退。

### 3.2 拍照/图片编辑页

| 功能 | 引擎/模型 | 作用 | 备注 |
|------|-----------|------|------|
| 拍照美颜处理 | **大美丽 GPU 离屏渲染** | 预览与拍照复用同一 Shader 管线 | 效果一致性 ≥ 99% |
| 拍照降级 | **GpuBeautyProcessor**（CPU Canvas） | GPU 失败时兜底 | 正向映射，已废弃但保留 |
| 人脸检测 | 同相机页 MediaPipe/MNN | 复用预览阶段缓存 | `FaceDetectionCache` 减少差异 |

### 3.3 相册页 / GalleryScreen

| 功能 | 引擎/模型 | 作用 | 备注 |
|------|-----------|------|------|
| 语义搜索 | **MobileCLIP-S2-ONNX** + **OPUS-MT Zh→En** | 图文跨模态相似度匹配 | MobileCLIP 文本/图像编码 512 维 |
| 人脸聚类 | **Glint360K R100** + DBSCAN | 提取 512 维 face embedding | MNN CPU 推理 |
| 图像理解 | **Qwen3.5-2B-MNN** | 相册单张图像理解 | `MediaPager` 已修复加载检查 |
| 标签/元数据 | **ML Kit Image Labeler**、**ML Kit Text Recognition** | 英文标签、OCR 文字 | 英文标签与 Qwen 中文标签混用 |

### 3.4 TAG 生成后台任务（TagGenerationService）

采用 **4-Pass 管道**（实际执行顺序已优化）：

| Pass | 引擎/模型 | 作用 | 单张耗时 | 是否量化 |
|------|-----------|------|----------|----------|
| **Pass 1** | MNN RetinaFace + Glint360K R100 + MobileCLIP-S2-ONNX | 人脸 ROI + 106 关键点 + 人脸/图像 512 维 Embedding；**MobileCLIP 无论是否有人脸都执行** | ~80-180ms | 否 |
| **Pass 2** | DBSCAN / 增量余弦匹配 | 人脸聚类 → `personId` | ~5-20ms/对比 | — |
| **Pass 3** | Qwen3.5-2B-MNN | 图像理解生成中文标签 | ~2-8s | 否 |

**调度策略**：单线程 Foreground Service + `singleThreadDispatcher` + 节流（Pass 1 已移除，Pass 3 保留 100ms）。

### 3.5 聊天页（ChatScreen）

| 功能 | 引擎/模型 | 作用 | 备注 |
|------|-----------|------|------|
| 文字/多模态对话 | **Remote LLM**（默认）/ **Qwen3.5-2B-MNN** | Agent 推理 | `agentMode` 默认 REMOTE |
| 语音输入 | **Sherpa-ONNX Zipformer ASR** | 语音转文字 | INT8 量化 |
| 本地敏感数据处理 | **Qwen3.5-2B-MNN** | `PrivacyGuard` 路由敏感数据本地推理 | 隐私红线 |

### 3.6 设置页 / ModelCenterScreen

| 功能 | 引擎/模型 | 作用 | 备注 |
|------|-----------|------|------|
| 模型下载管理 | 所有上述模型 | 从 ModelScope 下载到 `files/llm_models/{modelId}/` | 按服务功能分类：必须/聊天/相册打标/美颜相机 |
| Agent 模式切换 | 远程/本地 LLM | 本地/远程/关闭 | 默认远程优先 |

### 3.7 IM 远程控制（飞书）

| 功能 | 引擎/模型 | 作用 | 备注 |
|------|-----------|------|------|
| 远程指令处理 | Remote LLM + Qwen3.5-2B-MNN 降级 | 120s 超时处理飞书消息 | 本地模型未加载时自动加载 |

---

## 4. 模型清单及量化状态

### 4.1 大语言模型（LLM）

| 模型 | 引擎 | 大小 | 量化 | 运行时内存 | 用途 |
|------|------|------|------|------------|------|
| **Qwen3.5-2B-MNN** | MNN-LLM | 1.32GB (weight ~1.8GB) | **未量化**（FP16/FP32） | ~4.2GB | 默认本地 LLM，聊天/图像理解/Tag Pass 3 |

> 问题：2B 模型未做 INT4 量化，内存占用过大，与相机美颜叠加后易 OOM。

### 4.2 语音模型

| 模型 | 引擎 | 大小 | 量化 | 用途 |
|------|------|------|------|------|
| **Sherpa-ONNX Zipformer 中英双语 ASR** | ONNX Runtime / Sherpa-ONNX | 280MB | **INT8** | 流式语音识别 |
| **Sherpa-ONNX KWS Zipformer** | ONNX Runtime / Sherpa-ONNX | 14MB | **INT8** | 唤醒词检测（已落地） |

### 4.3 人脸模型

| 模型 | 引擎 | 大小 | 量化 | 用途 |
|------|------|------|------|------|
| **RetinaFace Det10G** (MNN) | MNN | 16.9MB | 否 | ROI 检测（历史） |
| **RetinaFace Det500M** (MNN) | MNN | 1.26MB | 否 | ROI 检测（当前默认 must-have） |
| **2D106 Landmark** (MNN) | MNN | 4.98MB | 否 | 106 点关键点 |
| **Glint360K R100** (MNN) | MNN | 248MB | 否 | 人脸 512 维 Embedding（聚类/识别） |

> 问题：所有人脸模型均未量化；Det10G 与 Det500M 同时存在，后者已替代前者为默认，但前者模型仍作为可选保留。

### 4.4 图像理解与语义搜索模型

| 模型 | 引擎 | 大小 | 量化 | 用途 |
|------|------|------|------|------|
| **MobileCLIP-S2-ONNX** | ONNX Runtime | 397MB | **FP32**（fp16 在 CPU 上 NaN/Inf） | 图像/文本编码，语义搜索 |
| **OPUS-MT Zh→En** | ONNX Runtime | 70MB | **INT8 量化** | 中文查询翻译 |
| **Qwen3.5-2B visual encoder** | MNN-LLM | 含于 LLM | 否 | 图像理解视觉编码 |

### 4.5 其他模型

| 模型 | 引擎 | 大小 | 量化 | 用途 |
|------|------|------|------|------|
| **MediaPipe Face Landmarker** | MediaPipe TFLite | 约 8MB task 文件 | TFLite 内置（通常为 INT8/FP16） | 468 点人脸关键点 |
| **ML Kit Face Detector** | ML Kit TFLite | Google 内置 | Google 内置 | 人脸检测备选 |
| **ML Kit Image Labeler** | ML Kit TFLite | Google 内置 | Google 内置 | 图像标签（英文） |
| **ML Kit Text Recognition** | ML Kit TFLite | Google 内置 | Google 内置 | OCR（含中文） |

### 4.6 量化状态汇总

| 模型类别 | 已量化 | 未量化 | 说明 |
|----------|--------|--------|------|
| LLM | — | Qwen3.5-2B | 最大内存瓶颈，INT4 量化待实施 |
| ASR/KWS | Sherpa-ONNX ASR、KWS | — | 已 INT8 量化 |
| 人脸检测/关键点 | — | MNN RetinaFace、2D106 | 模型小，量化收益有限 |
| 人脸 Embedding | — | Glint360K R100 | 248MB，量化收益有限 |
| CLIP | — | MobileCLIP-S2 | fp16 在 CPU 上不稳定，强制 fp32 |
| 翻译 | OPUS-MT | — | INT8 量化 |

---

## 4.7 Tokenizer 现状与分工

当前端侧同时存在两套 tokenizer 实现，分别服务不同模型，**不存在互相替代关系**。

| Tokenizer | 实现位置 | 服务模型 | 输入文件 | 输出 | 说明 |
|---|---|---|---|---|---|
| **SentencePiece** | `:sentencepiece` (`SentencePieceProcessor`) | OPUS-MT Zh→En | `source.spm` / `target.spm` | `IntArray` (SP piece IDs) | C++ JNI 实现，负责文本 ↔ SP pieces 的编解码 |
| **Hugging Face BPE** | `:app` (`MobileClipTokenizer`) | MobileCLIP-S2-ONNX | `tokenizer.json`（回退 `vocab.txt` + `merges.txt`） | `LongArray` (HF token IDs) | 自研 Kotlin 实现，解析 Hugging Face BPE 格式 |

### OPUS-MT 为什么同时需要 `.spm` 和 `tokenizer.json`

OPUS-MT 原始训练基于 SentencePiece，但导出的 ONNX 模型输入/输出 IDs 是 Hugging Face tokenizer 的 ID 空间。因此运行时链路为：

1. `source.spm` 把中文文本编码成 SP piece IDs；
2. `tokenizer.json` 把 SP pieces 映射为 HF token IDs，作为 encoder 输入；
3. Decoder 输出 HF IDs 后，再通过 `tokenizer.json` 反向映射为 SP pieces；
4. `target.spm` 把 SP pieces 解码为英文句子。

所以 `tokenizer.json` 在这里只是 **ID 映射表**，真正的分词/解码仍由 SentencePiece 完成。没有 `:sentencepiece` 模块，OPUS-MT 链路无法运转。

### 是否应统一

- **算法层不可统一**：CLIP BPE 与 Marian/SentencePiece 词表不同，模型权重已绑定各自 tokenizer，重新训练或重新导出成本过高。
- **接口层可统一**：如果未来再接入第三种 tokenizer，建议抽象 `Tokenizer` 接口，由 `SentencePieceTokenizer`、`HuggingFaceBpeTokenizer` 等实现，上层只依赖接口。
- 当前仅两个模型、两个独立入口，直接抽象的收益有限，暂不作为优先项。

---

## 5. 运行时性能瓶颈

### 5.1 内存瓶颈（P0）

| 瓶颈 | 现象 | 根因 | 影响 |
|------|------|------|------|
| **Qwen3.5-2B 常驻内存** | Native Heap ~3.6-4.2GB | 模型 weight 1.8GB + KV Cache + 激活值 | 与相机美颜（~2GB）叠加后 PSS 达 6.24GB，触发 LMK OOM Kill |
| **LLM 与 ASR/人脸模型叠加** | 峰值 3.03GB Native | KWS/ASR/LLM/FaceDetect 同时加载 | 仅在 12GB+ 设备安全 |
| **Swap PSS 暴涨** | 2.33GB Swap | 内存压力触发系统换页 | 渲染卡顿、发热 |
| **CameraX ImageReader 缓冲** | Native Heap 1.72GB 基线 | 1280×720 多帧缓冲 + GPU 纹理 | 即使无 LLM 也占用偏高 |

> 实测：`06-QA/perf_trace_2026-06-06_ncnn_llm_comparison.md`（历史文件名，含 NCNN 基线）显示，开启本地 LLM 后 Native Heap 从 1.72GB → 3.61GB，Swap 从 54MB → 2.33GB，Janky frames 从 0.89% → 18.42%。

### 5.2 计算瓶颈（P1）

| 瓶颈 | 现象 | 根因 | 影响 |
|------|------|------|------|
| **LLM 单线程串行** | 所有 load/generate/unload 排队 | `PicMe-LLM-Model-Thread` + `engineMutex` | 长生成阻塞模型切换和 Tag 管道 |
| **MNN 全局释放锁** | create/destroy/reset 串行 | `MnnGlobalReleaseLock` 保护 MNN 全局状态 | LLM/人脸检测互相阻塞 |
| **TAG Pass 3 Qwen 推理** | 2-8s/张 | CPU 解码 128 tokens ≈ 3.8s | 9000 张全量扫描约 13 小时 |
| **MediaPipe 主线程初始化** | 首帧延迟 | `Dispatchers.Main` 强制初始化 | 启动时掉帧 |

### 5.3 渲染瓶颈（P1）

| 瓶颈 | 现象 | 根因 | 影响 |
|------|------|------|------|
| **GPU photo readback** | 拍照保存延迟 | `PhotoProcessorImpl` 同步 `glReadPixels` | 高分辨率照片 CPU-GPU 同步开销大 |
| **Recording surface 切换** | 录制时掉帧 | 每帧重新绑定 EGL 上下文 | 渲染管线额外开销 |
| **OpenCL 编译延迟/失败** | Tag Pass 3 首次推理慢或超时 | GPU kernel 编译 + 设备兼容性问题 | `OpenClGuardian` 5s warmup + 30s 超时 |

### 5.4 调度瓶颈（P2）

| 瓶颈 | 现象 | 根因 | 影响 |
|------|------|------|------|
| **TAG 扫描单线程** | 全量扫描慢 | `singleThreadDispatcher` + `engineMutex` | 无法并行 Pass 1/1.5/2/3 |
| **Bitmap 双重解码** | Pass 1 和 Pass 3 各加载一次 | 未复用 Bitmap | 每张浪费 20-50ms |
| **DBSCAN O(n²)** | 人脸聚类随数量增长 | 朴素实现 | 当前 3000 人脸约 2s，未来需优化 |

---

## 6. 当前问题与不合理之处

### 6.1 架构层问题

1. **多框架并存，维护成本高**
   - 同时维护 MNN、ONNX Runtime、Sherpa-ONNX、MediaPipe、ML Kit 五套推理栈，JNI 桥接、模型下载、生命周期管理重复。
   - 示例：`app/build.gradle.kts` 需要 `pickFirsts` 解决 `libonnxruntime.so` 冲突；`MNN-source/`  vendored 但未作为运行时依赖。

2. **MNN 全局状态耦合**
   - LLM、ASR（旧 Sherpa-MNN）、人脸检测（MNN 路径）共享 `libMNN.so`，被迫引入 `MnnGlobalReleaseLock` 和 `MnnResourceManager` 复杂引用计数。
   - 即使 KWS/ASR 已迁移到 Sherpa-ONNX，MNN 仍被人脸检测和 LLM 共享，释放顺序和线程安全仍是隐患。

3. **LLM 单例与多入口加载责任分散**
   - ~~8 处 `loadModel()` 调用点自行处理加载检查~~ 已统一封装到 `AgentOrchestrator.ensureModelLoaded()` / `withModelLoaded()`，所有 `imageInference()` / `generate()` / `chat()` 入口均走统一加载检查，消除遗漏风险。

### 6.2 模型层问题

4. **LLM 未量化**
   - Qwen3.5-2B 为 FP16/FP32，weight 1.8GB，运行时 ~4.2GB。INT4 量化可减少 65% 内存到 ~600MB/1.5GB，已规划但未实施。

5. **MobileCLIP fp16 不稳定**
   - ModelScope 远程仅提供 fp16，但 fp16 在 ONNX Runtime Android CPU 上产生 NaN/Inf，被迫使用 fp32，模型增大且推理速度下降。

6. **人脸模型冗余**
   - Det10G 与 Det500M 同时存在；Det10G 已非默认，但仍作为可选模型保留，增加模型中心复杂度和下载体积。

7. **量化策略不统一**
   - 只有 ASR/KWS 和 OPUS-MT 明确 INT8；LLM、人脸、MobileCLIP 均未量化或无法量化，难以建立统一的性能/精度权衡策略。

### 6.3 运行时问题

8. **GPU 失败无 CPU 降级**
   - `MnnRoiDetector` 写死 `requireGpu=true`，OpenCL 初始化失败时检测器为 `null`，导致整帧无人脸。
   - Qwen 视觉编码器虽有 `OpenClGuardian` CPU 降级，但人脸检测缺少等价机制。

9. **TAG 扫描物理瓶颈**
   - Pass 3 Qwen 2-8s/张是物理上限，9000 张需 13 小时。当前优化（移除 Pass 1 节流、maxTokens=64、OpenCL GPU、照片去重）理论上可压缩到 2-3 小时，但去重等方案尚未落地。

10. **线程过度串行化**
    - LLM `engineMutex` + `MnnGlobalReleaseLock` 双重串行，牺牲了本可并行的能力（如 MobileCLIP 与 Qwen 不共享 GPU，本可并发）。

11. **Native 内存阈值固定**
    - `MnnResourceManager` 使用固定阈值（2GB/2.56GB/3.07GB）触发 trim/unload，未按设备 RAM 分级，低端机可能太晚，高端机可能过早。

### 6.4 工程层问题

12. **模型 ID 不一致（已修复）**
    - `ModelPathConfig.MODEL_ID_LLM` 已统一为 `"qwen3_5_2b"`，`MODEL_ID_ASR` 已统一为 `"sherpa-onnx-zipformer-zh-en"`，与 `LlmModelManager` 注册表及 `llm_models.json` 保持一致。

13. **ML Kit 英文标签与 Qwen 中文标签混用**
    - `MetadataExtractor` 输出英文标签（如 "Outdoor"），Qwen 输出中文标签（如 "户外"），`LIKE` 搜索无法跨语言命中，依赖 LLM Agent 做同义词扩展。

14. **WakeWord 当前方案低效（已部分解决）**
    - KWS 已迁移到 Sherpa-ONNX KeywordSpotter（~14MB INT8），相机页默认启用低功耗唤醒；原 VAD+ASR 方案仅在 KWS 模型不可用时回退。

---

## 7. 优化方向与建议

### 7.1 短期（1-2 周）

| 优化项 | 收益 | 优先级 |
|--------|------|--------|
| Qwen3.5-2B INT4 量化 | 内存 4.2GB → ~1.5GB | P0 |
| 相机页默认不加载 LLM | 避免 OOM | P0 |
| 人脸检测 GPU 失败 CPU 降级 | 提升兼容性 | P0 |
| 统一封装 `AgentOrchestrator.ensureModelLoaded()` | 避免遗漏加载 | P1 |

### 7.2 中期（1-2 月）

| 优化项 | 收益 | 优先级 |
|--------|------|--------|
| ~~完成 Sherpa-ONNX KWS 迁移~~ | 唤醒功耗 ↓ 80%，延迟 ↓ 60% | 已完成 |
| TAG Pass 3 照片去重（dHash） | 减少 30-50% Qwen 调用 | P1 |
| TAG Bitmap 复用 | 减少二次解码 | P1 |
| MobileCLIP 向量量化/PQ 或 HNSW | 大图库搜索加速 | P2 |

### 7.3 长期（3-6 月）

| 优化项 | 收益 | 优先级 |
|--------|------|--------|
| 评估统一推理框架（MNN 或 ONNX Runtime） | 降低多框架维护成本 | P2 |
| 模型动态加载/卸载 + 设备分级 | 低端机用远程/小模型 | P2 |
| 人脸模型 INT8 量化评估 | 进一步降低人脸链路内存 | P3 |
| PBO 异步 GPU readback | 拍照保存加速 | P3 |

---

## 8. 关键性能基线

| 指标 | 目标值 | 当前基线 | 状态 |
|------|--------|----------|------|
| 美颜预览帧率 | ≥ 30fps（低端）/ ≥ 55fps（高端） | 高端机流畅，低端机待压测 | ⚠️ |
| 美颜单帧处理延迟 | ≤ 16ms | 通常 < 10ms | ✅ |
| 快门延迟 | < 50ms | GPU 路径达标 | ✅ |
| TAG Pass 1 人脸 ROI | ~30-80ms | 已达成 | ✅ |
| TAG Pass 1.5 MobileCLIP | ~50-100ms | 已达成 | ✅ |
| TAG Pass 3 Qwen | ~2-8s | 物理瓶颈 | ⚠️ |
| 本地 LLM 运行时内存 | < 2GB | ~4.2GB (2B FP16) | ❌ |
| 相机+LLM 共存 | 不 OOM | 高性能手机也被 LMK Kill | ❌ |
| ASR 唤醒延迟 | < 100ms (KWS) | ~50ms (Sherpa-ONNX KWS) | ✅ |

---

## 9. 相关文档

- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — 大美丽美颜引擎
- `docs/03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md` — 人脸检测三引擎架构
- `docs/03-TECHNICAL-SPECS/MNN_LLM_PERFORMANCE_OPTIMIZATION.md` — MNN-LLM 性能优化
- `docs/03-TECHNICAL-SPECS/MNN_LLM_MULTI_INSTANCE_RESEARCH.md` — LLM 单例与加载点
- `docs/03-TECHNICAL-SPECS/MNN_RESOURCE_MANAGER_DESIGN.md` — MNN 资源管理
- `docs/03-TECHNICAL-SPECS/MNN_MULTI_MODEL_LOAD_UNLOAD_CHECKLIST.md` — 多模型生命周期改造
- `docs/03-TECHNICAL-SPECS/AUTO_TAG_GENERATION_SPEC.md` — TAG 生成 5-Pass 管道
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION_PERFORMANCE_ANALYSIS.md` — TAG 性能瓶颈分析
- `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` — 相册自然语言搜索（含 MobileCLIP 语义召回）
- `docs/03-TECHNICAL-SPECS/TAG_I18N_DESIGN.md` — TAG 国际化与 OPUS-MT 翻译回退
- `docs/06-QA/research/OPUS_MT_TRANSLATION_VALIDATION.md` — OPUS-MT 端侧推理验证记录
- `docs/03-TECHNICAL-SPECS/KWS_MIGRATION_TECH_SPEC.md` — KWS 唤醒词迁移
- `docs/06-QA/perf_trace_2026-06-06_ncnn_llm_comparison.md` — LLM 开启前后性能对比（历史文件名，含 NCNN 基线）
- `app/src/main/res/raw/llm_models.json` — 模型清单与下载配置
- `app/src/main/java/com/mamba/picme/features/settings/AGENTS.md` — 模型中心与设置

---

> **维护说明**: 本文档应随模型新增/删除、量化策略变更、生命周期重构同步更新。新增模型必须在 `llm_models.json` 中注册，并在本文档第 4 节补充量化与用途说明。
