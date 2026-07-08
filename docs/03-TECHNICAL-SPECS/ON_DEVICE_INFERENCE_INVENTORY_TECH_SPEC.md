# PicMe 端侧推理引擎与模型全景梳理

> **版本**: 1.3  
> **状态**: 生效中  
> **最后更新**: 2026-07-08  
> **维护者**: RD Agent  
> **范围**: `:app`、`:runtime-core`、`:beauty-engine`、`:beauty-api`、`:sentencepiece` 模块中所有本地推理引擎、模型、量化策略、tokenizer、运行时瓶颈、优化方案评估与多模型生命周期改造清单

---

## 1. 概述

PicMe（觅影相册）当前在端侧同时运行 **7 套推理框架**、**14+ 个模型**，覆盖美颜预览、人脸检测、语音识别、关键词唤醒、大语言模型、图像理解、语义搜索、OCR、机器翻译等场景。由于历史演进和实验性质，推理栈呈现**多框架并存、量化程度不一、生命周期耦合、资源竞争复杂**的特点。

本文档按**页面/场景**梳理当前推理引擎、模型、作用及方案，指出运行时性能瓶颈与设计不合理之处；第 10 章对各项优化方案进行系统评估，第 11 章给出 MNN 多模型加载/卸载改造清单。全文为后续模型精简、量化统一、生命周期解耦提供决策依据。

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
- `docs/03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md` — MNN-LLM 性能优化
- `docs/03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md` — LLM 单例与加载点
- `docs/03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md` — MNN 资源管理
- 「[11. MNN 多模型加载/卸载改造清单](#11-mnn-多模型加载卸载改造清单)」 — 多模型生命周期改造
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` — TAG 生成 5-Pass 管道
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` — TAG 性能瓶颈分析
- `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` — 相册自然语言搜索（含 MobileCLIP 语义召回）
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` — TAG 国际化与 OPUS-MT 翻译回退
- `docs/06-QA/research/OPUS_MT_TRANSLATION_VALIDATION.md` — OPUS-MT 端侧推理验证记录
- `docs/03-TECHNICAL-SPECS/VOICE_STACK.md` — KWS 唤醒词迁移
- `docs/06-QA/perf_trace_2026-06-06_ncnn_llm_comparison.md` — LLM 开启前后性能对比（历史文件名，含 NCNN 基线）
- `app/src/main/res/raw/llm_models.json` — 模型清单与下载配置
- `app/src/main/java/com/mamba/picme/features/settings/AGENTS.md` — 模型中心与设置

---

> **维护说明**: 本文档应随模型新增/删除、量化策略变更、生命周期重构同步更新。新增模型必须在 `llm_models.json` 中注册，并在本文档第 4 节补充量化与用途说明。

---

## 10. 优化方案评估

---

## 10.1. 概述

本文档对 PicMe 当前端侧推理栈的 11 项优化建议进行系统评估。评估维度包括：

- **收益**: 内存、延迟、功耗、用户体验改善程度
- **成本**: 开发工时、模型转换/验证、测试覆盖
- **风险**: 精度损失、兼容性回归、稳定性风险、维护复杂度
- **依赖**: 是否需要前置改造或其他团队配合
- **推荐优先级**: P0/P1/P2/P3
- **验收指标**: 可量化的完成标准

评估基于已有技术文档、代码现状、性能基线数据，并参考了端侧 LLM 量化、CLIP 量化、向量索引等领域的最新工程实践。

---

## 10.2. 评估总览表

| 优化项 | 阶段 | 收益 | 成本 | 风险 | 优先级 | 推荐顺序 |
|--------|------|------|------|------|--------|----------|
| Qwen3.5-2B INT4/INT8 量化 | 短期 | ⭐⭐⭐⭐⭐ | 中 | 中 | **P0** | 1 |
| 相机页默认不加载 LLM | 短期 | ⭐⭐⭐⭐⭐ | 低 | 低 | **P0** | 2 |
| 人脸检测 GPU 失败 CPU 降级 | 短期 | ⭐⭐⭐ | 低 | 低 | **P0** | 3 |
| 统一封装 `ensureModelLoaded()` | 短期 | ⭐⭐ | 低 | 低 | **P1** | 4 |
| Sherpa-ONNX KWS 迁移 | 中期 | ⭐⭐⭐⭐⭐ | 中 | 中 | **P0** | 5 |
| TAG Pass 3 照片去重（dHash） | 中期 | ⭐⭐⭐⭐ | 中 | 低 | **P1** | 6 |
| TAG Bitmap 复用 | 中期 | ⭐⭐ | 低 | 低 | **P2** | 8 |
| MobileCLIP 向量量化/PQ/HNSW | 中期 | ⭐⭐⭐ | 高 | 中 | **P2** | 7 |
| 统一推理框架评估 | 长期 | ⭐⭐⭐ | 高 | 高 | **P2** | 9 |
| 模型动态加载/卸载 + 设备分级 | 长期 | ⭐⭐⭐⭐ | 中 | 中 | **P1** | 10 |
| 人脸模型 INT8 量化评估 | 长期 | ⭐⭐ | 中 | 中 | **P3** | 11 |
| PBO 异步 GPU readback | 长期 | ⭐⭐ | 中 | 低 | **P2** | 12 |

---

## 10.3. 短期优化方案评估（1-2 周）

### 10.3.1 Qwen3.5-2B INT4/INT8 量化

**背景**
- 当前 Qwen3.5-2B 为 FP16/FP32，weight ~1.8GB，运行时 ~4.2GB
- 实测：相机预览 + 本地 LLM 时 Native Heap 3.61GB，总 PSS 6.24GB，触发 LMK OOM Kill

**收益评估**

| 指标 | 当前 | INT4 目标 | INT8 目标 |
|------|------|-----------|-----------|
| 模型文件 | 1.32GB | ~450-600MB | ~900MB |
| 运行时内存 | ~4.2GB | ~1.5GB | ~2.5GB |
| 相机+LLM 共存 | OOM | 8GB 设备可稳定运行 | 8GB 设备临界 |
| 推理速度 | CPU 3-8s/张 | 可能略降或持平 | 可能略升 |

**技术可行性**
- MNN 支持 `weightQuantBits=4` 的 INT4 权重量化（`mnnconvert --weightQuantBits 4 --weightQuantAsymmetric`）。
- 业界实践：Qwen2.5 系列 W4A8 配置在 SpinQuant 等方案下精度损失可控；W4A4 因 channel outliers 存在波动。
- **建议优先尝试 INT4 权重量化 + FP16/INT8 激活**（W4A16 或 W4A8），而非激进 W4A4。
- 如 INT4 精度损失过大，回退到 INT8 权重量化。

**成本**
- 模型转换：1-2 天（需准备校准数据集）
- 精度评估：1-2 天（中文场景、图像理解、Agent 指令执行）
- 集成到 ModelCenter + 下载分流：1 天
- **总计**: 3-5 天

**风险**
- 精度损失：INT4 可能导致中文 OCR、复杂推理任务质量下降，需业务侧验收。
- 推理速度：INT4 在部分 CPU 上反量化开销大，可能不如 INT8 快。
- 设备兼容：低端 CPU 对 INT4 指令支持不佳。

**依赖**
- 需要 MNN 3.5.0 转换工具链
- 需要中文/多模态评估数据集

**验收指标**
- [ ] 2B 模型运行时 Native Heap ≤ 2GB
- [ ] 相机页 + 本地 LLM 连续运行 10 分钟不 OOM
- [ ] 图像理解/Agent 指令准确率相对 FP16 下降 ≤ 5%
- [ ] 首 token 延迟、总生成延迟不劣于 FP16 10%

**结论**: **P0，立即实施**。这是解决当前 OOM 和发热的最高收益单点。

---

### 10.3.2 相机页默认不加载 LLM

**背景**
- 当前 LLM 跨页面保活，相机页也会持有 LLM 引用。
- 实测：仅相机预览（无 LLM）Native Heap 1.72GB；+LLM 后 3.61GB，直接 OOM。

**收益评估**
- 内存释放：~2.5-3GB
- 避免相机场景 OOM Kill
- 发热下降：GPU/CPU 温度可回落 5-10°C
- 对体验影响：相机页 Agent 入口触发时需异步加载 LLM（~2s 冷启动）

**技术可行性**
- 高。`MnnResourceManager` 已支持引用计数和场景策略，只需调整 `AgentOrchestrator.applySceneDrivenModelPolicy`：
  - 进入 `CameraScene` 时调用 `localLlmEngine.trimMemory()` 或 `unloadModel()`
  - 离开相机页后恢复 LLM 到 WARM 状态（可选）
- 需注意：IM 远程控制、语音指令若触发 LLM 需在相机页做异步加载 UI。

**成本**
- 策略调整：0.5 天
- 异步加载 UI：0.5 天
- 回归测试：1 天
- **总计**: 2 天

**风险**
- 低。主要风险是相机页语音命令触发 LLM 时有 1-2s 延迟，可通过加载态提示缓解。

**依赖**
- 依赖 `MnnResourceManager` 的 `ReleaseLevel` 和场景策略已稳定运行
- 需 IM 远程控制链路适配异步加载

**验收指标**
- [ ] 进入相机页后 Native Heap ≤ 2GB
- [ ] 相机页 5 分钟连续预览不 OOM
- [ ] 相机页触发 Agent 语音指令时显示 "Agent 启动中" 并在 3s 内响应

**结论**: **P0，立即实施**。零风险、高收益，与量化方案互补。

---

### 10.3.3 人脸检测 GPU 失败 CPU 降级

**背景**
- `MnnRoiDetector` 当前 `requireGpu=true`，GPU 初始化失败时检测器为 `null`。
- 部分中低端设备不支持 OpenCL 或驱动有 Bug。

**收益评估**
- 提升设备兼容性，避免人脸检测完全失效
- 保证美颜效果在非 GPU 设备上可用
- 性能：CPU 路径延迟约 2-4x GPU 路径，但仍可满足预览 10fps 检测需求

**技术可行性**
- 高。需修改：
  - `MnnRoiDetector.kt` / `MnnLandmarkDetector.kt`：GPU 失败时尝试 CPU 后端
  - `FaceDetectorManager`：当 GPU 检测器为 null 时回退到 CPU 检测器
- MNN 支持 CPU 后端切换。

**成本**
- 代码修改：1 天
- 兼容性测试（不同 GPU 能力设备）：1 天
- **总计**: 2 天

**风险**
- 低。主要风险是 CPU 路径增加功耗，但这是可接受的降级。

**依赖**
- 无

**验收指标**
- [ ] 在关闭 GPU 加速的设备上人脸检测仍可工作
- [ ] GPU 可用时优先使用 GPU，不可用自动降级 CPU
- [ ] 降级事件有结构化日志记录

**结论**: **P0，立即实施**。兼容性红线，成本低。

---

### 10.3.4 统一封装 `AgentOrchestrator.ensureModelLoaded()`

**背景**
- 8 处 `loadModel()` 调用点分散在 `ChatViewModel`、`AiAgentUseCase`、`TagGenerationScheduler`、`OpenClGuardian`、`ImageTagIndexingWorker`、`MediaPager` 等。
- 历史上 `MediaPager` 因未加载直接调用 `imageInference()` 导致空结果。

**收益评估**
- 消除加载遗漏 Bug
- 统一 `useOpencl` 决策，避免各调用方自行决定 GPU/CPU
- 便于集中埋点和监控

**技术可行性**
- 高。已在 [AgentOrchestrator] 中统一封装：
  ```kotlin
  suspend fun ensureModelLoaded(
      modelId: String? = null,
      useOpencl: Boolean? = null,
      caller: String = "unknown"
  ): Result<Unit>

  suspend fun <T> withModelLoaded(
      modelId: String? = null,
      useOpencl: Boolean? = null,
      caller: String = "unknown",
      inferenceBlock: suspend (LocalLlmEngine) -> T
  ): Result<T>
  ```

**成本**
- 封装 API：0.5 天（已完成）
- 替换各调用点：1 天（已完成）
- 单元测试：0.5 天（AgentOrchestrator 为单例且依赖 Context，待后续可测试化重构后补充）
- **总计**: 2 天（主要代码已完成）

**风险**
- 低。需确保异步加载异常处理与原调用方一致。

**依赖**
- 无

**验收指标**
- [x] 所有 `imageInference()` / `generate()` 调用前统一走 `ensureModelLoaded()` / `withModelLoaded()`
- [x] 新增加载调用点日志审计（`[ModelLoadAudit] caller=...`）
- [x] 无回归：聊天、TAG、相册图像理解均正常（编译 + 单元测试通过）

**结论**: **P1（已完成）**。属于工程稳健性改进，已在 AgentOrchestrator 中统一封装，各调用点已迁移。

---

## 10.4. 中期优化方案评估（1-2 月）

### 10.4.1 Sherpa-ONNX KWS 迁移

**背景**
- 当前唤醒词方案：VAD → 录音 → 282MB ASR 全量转录 → 文本匹配 "小觅"。
- 延迟 800-1000ms，每次检测加载大模型，无法真正 always-on。

**收益评估**

| 指标 | 当前 | KWS 目标 |
|------|------|----------|
| 模型大小 | 282MB | 14MB |
| 待机内存 | ~282MB | ~25MB（KWS 独占）/~45MB（含 ONNX RT 基础） |
| 唤醒延迟 | 800-1000ms | < 100ms |
| 待机功耗 | ~100mW | ~40-50mW |
| 误触发率 | 8-10% | 目标 < 1次/小时 |

**技术可行性**
- 高。`KWS_MIGRATION_TECH_SPEC.md` 已给出完整方案：
  - 切换 `sherpa-mnn-jni` → `sherpa-onnx.aar`
  - 新增 `KeywordSpotterEngine.kt`
  - 重写 `WakeWordEngine.kt`、`VoiceCommandCoordinator.kt`
  - 更新 `llm_models.json` 模型配置
- 估算工时 13 小时（约 2 天），属于中等投入。

**成本**
- ASR 引擎重写：2 天
- KWS 引擎实现 + 单元测试：2 天
- 模型下载配置更新：0.5 天
- 全链路集成测试：2 天
- **总计**: 6-7 天

**风险**
- 中。ONNX ASR 模型精度需与当前 MNN ASR 对比测试。
- KWS 误触发率需大量实测调优阈值。
- AAR 54MB 会增大 APK，但 AAB 按架构拆分后实际安装只含 arm64。

**依赖**
- 需 `sherpa-onnx.aar` 1.13.3+ 依赖
- 需 ONNX Runtime 1.24.3 兼容
- 完成迁移后可删除 `MnnGlobalReleaseLock` 和简化 `MnnResourceManager`

**验收指标**
- [ ] KWS 待机 Native Heap < 45MB
- [ ] 唤醒延迟 < 100ms
- [ ] 误触发率 < 1次/小时
- [ ] ASR 转录字符准确率 ≥ 95%（相对当前 MNN ASR）
- [ ] 多次 KWS→ASR→LLM 循环无内存泄漏

**结论**: **P0**。唤醒体验质变，且是解除 LLM/ASR/Face 耦合的关键一步。

---

### 10.4.2 TAG Pass 3 照片去重（dHash）

**背景**
- Qwen 图像理解 2-8s/张，是 TAG 扫描的物理瓶颈。
- 连拍、截图、相似照片可能占 30-50%，标签可复用。

**收益评估**
- 假设 40% 重复，Pass 3 总耗时从 11.25 小时 → 6.75 小时，节省 4.5 小时（9000 张）。
- 减少 Qwen GPU/CPU 占用，降低发热和耗电。

**技术可行性**
- 高。方案已明确：
  1. Pass 1 计算 dHash（差异哈希，64-bit，~1ms/张）
  2. 存储到 `media_assets.perceptual_hash`
  3. Pass 3 前查询复用已有 labels
- 需要 Room migration 新增字段。

**成本**
- Room 表迁移：0.5 天
- dHash 计算实现：1 天
- 复用查询逻辑：1 天
- 准确性验证：1 天
- **总计**: 3-4 天

**风险**
- 低。主要风险是误判不同照片为重复（dHash 对旋转、裁剪敏感），可设置汉明距离阈值 + 时间窗口过滤。

**依赖**
- 需要 `media_assets` 表 schema 变更

**验收指标**
- [ ] 9000 张中 30-40% 跳过 Qwen 推理
- [ ] 误判率（不同照片复用标签）< 1%
- [ ] 全量扫描总耗时下降 ≥ 30%

**结论**: **P1**。收益显著，实现简单，是 TAG 性能优化的核心手段之一。

---

### 10.4.3 TAG Bitmap 复用

**背景**
- Pass 1 加载 640px Bitmap，Pass 3 重新加载 512px Bitmap，两次 `ContentResolver.openInputStream()` + `BitmapFactory.decodeStream()`。

**收益评估**
- 每张节省 20-50ms I/O
- 9000 张节省 ~5 分钟（相对 13 小时占比小）

**技术可行性**
- 中。Pass 1 与 Pass 3 当前在不同调度阶段执行，Bitmap 生命周期管理需要改造：
  - 方案 A：Pass 1 生成 640px Bitmap，Pass 3 复用时缩放至 512px
  - 方案 B：统一加载 640px，Qwen 内部 `preprocessBitmap` 自动缩放到 420px
- 需注意内存峰值：同时缓存 Bitmap 会增加瞬时内存。

**成本**
- 改造 Bitmap 传递与缓存：1-2 天
- 内存峰值测试：0.5 天
- **总计**: 2 天

**风险**
- 中。复用 Bitmap 可能增加 OOM 风险，需控制缓存策略。

**依赖**
- TAG 调度器结构稳定

**验收指标**
- [ ] 单张 I/O 时间减少 20-50ms
- [ ] 全量扫描内存峰值不增加

**结论**: **P2**。边际收益，可在去重之后顺手做。

---

### 10.4.4 MobileCLIP 向量量化 / PQ / HNSW

**背景**
- 当前 MobileCLIP 输出 512 维 float32，每张照片 2KB，万级图库 20MB。
- 当前语义搜索为暴力扫描，万级约 10-50ms，可接受；但随着图库增长会恶化。

**收益评估**

| 方案 | 内存降幅 | 搜索延迟 | 召回损失 | 适用规模 |
|------|----------|----------|----------|----------|
| 暴力扫描（当前） | — | 10-50ms（<1万） | 100% | < 1万 |
| INT8 标量量化（SQ8） | ~75% | 略降 | ~1% | 1-10万 |
| 乘积量化 PQ（8-bit×64） | ~94% | 明显下降 | 2-5% | 1-10万 |
| HNSW + PQ | ~90% | < 10ms | 1-3% | > 10万 |
| 降维 512→256/128 + INT8 | ~87.5% | 4x 提升 | 2-5% | 移动端本地 |

**技术可行性**
- 中。
  - **INT8 标量量化**: 实现最简单，将 float32 压缩为 int8，损失可控。
  - **PQ**: 需引入 Faiss 或自研 PQ 编码/搜索，Android 端集成成本高。
  - **HNSW**: 可引入 hnswlib 等 C++ 库，但构建索引和增量更新复杂。
  - **降维**: 若 MobileCLIP 原生不支持 Matryoshka，需训练降维矩阵，风险高。
- 参考研究：MobileCLIP 混合量化可在 CPU 上减少 53% 模型大小（397MB→186MB）且精度几乎不变；ONNX Runtime 移动端对 full INT8 算子支持有限，**weight-only INT8** 更稳定。

**成本**
- INT8 SQ8 标量量化：2-3 天
- PQ/HNSW 引入：1-2 周
- 精度回归测试：2-3 天
- **总计**: 1-3 周（取决于方案深度）

**风险**
- 中。PQ/HNSW 会引入召回损失，需用真实图库验证。
- 移动端 C++ 库集成增加包体积和 native 依赖。

**依赖**
- 语义搜索 UI 和 `SemanticSearchEngine` 已稳定
- 大图库测试数据集

**验收指标**
- [ ] 万级图库语义搜索 < 50ms
- [ ] 十万级图库语义搜索 < 100ms
- [ ] 相对 float32 召回率 ≥ 95%
- [ ] 语义搜索相关内存占用下降 ≥ 50%

**结论**: **P2**。当前万级暴力搜索可接受，优先做 INT8 标量量化；PQ/HNSW 待图库增长到 5 万+ 再实施。

---

## 10.5. 长期优化方案评估（3-6 月）

### 10.5.1 统一推理框架评估

**背景**
- 当前同时维护 MNN、ONNX Runtime、Sherpa-ONNX、MediaPipe、ML Kit 五套栈。

**候选方案**

| 方案 | 优势 | 劣势 | 适用模型 |
|------|------|------|----------|
| **全部迁移到 MNN** | 统一 `libMNN.so`、团队已有经验、Qwen/VL 原生支持好 | 对 ONNX 模型支持弱、KWS/ASR 需重新适配 | LLM、人脸、MobileCLIP |
| **全部迁移到 ONNX Runtime** | 统一 ONNX 生态、ASR/KWS/CLIP/翻译原生支持 | LLM 大模型 ONNX 部署复杂、端侧经验少 | ASR/KWS、CLIP、翻译、人脸 |
| **保持现状，分层收敛** | 风险低、按模型特点选择最优后端 | 维护成本高、多框架冲突 | 当前策略 |

**收益评估**
- 降低 30-50% 推理相关 JNI/原生代码维护成本
- 减少依赖冲突和包体积
- 统一模型转换、加载、监控管线

**成本**
- 调研 + PoC：2-3 周
- 迁移实施：2-3 月
- 全量回归：2-4 周
- **总计**: 3-5 月

**风险**
- 高。迁移过程中可能引入精度、性能、稳定性回归。
- 某些模型在目标框架上可能无最优后端（如 MNN 对 ONNX CLIP 支持）。

**依赖**
- 需要先完成 KWS 迁移和 LLM 量化，明确未来框架边界

**验收指标**
- [ ] 推理框架数量从 6 个收敛到 2-3 个
- [ ] 包体积减少 ≥ 20%
- [ ] 推理相关崩溃率不上升

**结论**: **P2**。属于战略性技术债，需在完成 P0/P1 后再启动评估，避免在不稳定基础上大动。

---

### 10.5.2 模型动态加载/卸载 + 设备分级

**背景**
- 当前 LLM 跨页面保活，低端设备 OOM；高端设备又可能浪费内存。

**收益评估**
- 低端设备使用远程 LLM，避免本地 OOM
- 中端设备按需加载 0.8B 小模型
- 高端设备使用 2B 模型并保活
- 相机页、后台等场景自动卸载非必需模型

**技术可行性**
- 中。`MnnResourceManager` 已有 `ReleaseLevel` 和引用计数，需扩展：
  - `DeviceTier` 分级（LOW/MID/HIGH）
  - 按场景和内存压力自动选择模型
  - 预加载策略（App 启动后台加载到 WARM）

**成本**
- 设备分级与内存检测：2 天
- 策略引擎改造：3-5 天
- UI 加载态适配：2 天
- 压测与调优：3-5 天
- **总计**: 2-3 周

**风险**
- 中。自动卸载/加载可能增加用户感知延迟，需 Loading UI 兜底。
- 设备分级阈值需大量设备验证。

**依赖**
- 完成 `MnnResourceManager` P0 改造
- 远程 LLM 链路稳定

**验收指标**
- [ ] 低端设备（4GB RAM）不触发本地 LLM OOM
- [ ] 中端设备默认使用 0.8B 模型
- [ ] 高端设备默认使用 2B 模型
- [ ] 任意页面触发 Agent 时，WARM 状态恢复 < 500ms

**结论**: **P1**。与量化、KWS 迁移共同构成端侧可用性的三支柱，建议在 P0 完成后 1 月内实施。

---

### 10.5.3 人脸模型 INT8 量化评估

**背景**
- RetinaFace、2D106、Glint360K R100 均为未量化 float32。

**收益评估**
- 模型本身很小（1-5MB），INT8 后内存降幅有限（< 50%）。
- 推理速度可能提升 1.5-2x（取决于 CPU INT8 指令支持）。
- 对整体 Native Heap 贡献小（人脸运行时 ~50-70MB）。

**成本**
- 三个模型分别转换验证：2-3 天
- 精度验证（landmark 抖动、聚类准确率）：3-5 天
- **总计**: 1-2 周

**风险**
- 中。人脸关键点 INT8 可能引起 landmark 抖动，影响美颜形变精度。
- Glint360K R100 INT8 可能降低聚类准确率。

**依赖**
- 量化工具链（MNN/ONNX Runtime）
- 人脸对齐/聚类评估数据集

**验收指标**
- [ ] 单点像素误差 < 3px
- [ ] 人脸聚类准确率不下降
- [ ] 推理速度提升 ≥ 20%

**结论**: **P3**。模型已足够小，量化收益有限，优先级低于 LLM 和 CLIP 量化。

---

### 10.5.4 PBO 异步 GPU readback

**背景**
- `PhotoProcessorImpl` 当前使用同步 `glReadPixels` 读取高分辨率照片。

**收益评估**
- 高分辨率照片保存延迟降低 30-50%
- 减少 CPU-GPU 同步等待

**技术可行性**
- 中。PBO（Pixel Buffer Object）+ 双缓冲异步回读是标准方案：
  1. `glReadPixels` 到 PBO（非阻塞）
  2. 下一帧 `glMapBufferRange` 读取上一帧数据
- 需处理 EGL 上下文和分辨率变化。

**成本**
- C++ 层 PBO 实现：3-5 天
- Kotlin 层适配：1 天
- 多设备兼容性测试：2-3 天
- **总计**: 1-2 周

**风险**
- 低。PBO 是成熟技术，主要风险是特定 GPU 驱动对 PBO 的支持差异。

**依赖**
- 拍照路径稳定

**验收指标**
- [ ] 4K 照片保存延迟 < 100ms
- [ ] 无照片内容错乱或黑边
- [ ] 低端设备兼容性通过

**结论**: **P2**。拍照体验优化，可在美颜渲染稳定后实施。

---

## 10.6. 推荐实施路线图

### 第一阶段（0-2 周）：止血与红线

1. **Qwen3.5-2B INT4/INT8 量化**（P0）
2. **相机页默认不加载 LLM**（P0）
3. **人脸检测 GPU 失败 CPU 降级**（P0）
4. **统一封装 `ensureModelLoaded()`**（P1，穿插实施）

**阶段目标**: 解决 OOM、发热、兼容性问题，使本地 LLM 在相机页可共存。

### 第二阶段（2-6 周）：体验质变

5. **Sherpa-ONNX KWS 迁移**（P0）
6. **模型动态加载/卸载 + 设备分级**（P1）
7. **TAG Pass 3 照片去重**（P1）

**阶段目标**: 唤醒体验质变，低端设备可用，TAG 扫描提速。

### 第三阶段（6-14 周）：精细优化

8. **MobileCLIP INT8 标量量化**（P2）
9. **PBO 异步 GPU readback**（P2）
10. **TAG Bitmap 复用**（P2）

**阶段目标**: 大图库搜索、拍照保存、TAG 效率进一步优化。

### 第四阶段（14-24 周）：架构收敛

11. **统一推理框架评估**（P2）
12. **人脸模型 INT8 量化评估**（P3）
13. **MobileCLIP PQ/HNSW**（P2，图库 > 5 万时启动）

**阶段目标**: 降低长期维护成本，支撑大规模图库。

---

## 10.7. 关键决策建议

### 10.7.1 LLM 量化：优先 INT4 权重，准备 INT8 回退

不要一步到位 W4A4。建议：
- 主路径：`mnnconvert --weightQuantBits 4` 生成 INT4 权重 + FP16/INT8 激活
- 回退路径：INT8 权重
- 验收以中文图像理解和 Agent 指令准确率为准，不只看 benchmark

### 10.7.2 KWS 迁移优先于 ASR 优化

当前 ASR 唤醒方案是功耗和延迟的最大短板。迁移到 Sherpa-ONNX KWS 后：
- 待机内存从 282MB → 14MB
- 唤醒延迟从 800ms → <100ms
- 同时解除 LLM/ASR/Face 的 MNN 耦合

### 10.7.3 TAG 扫描优化：先"让 Qwen 少跑"，再"让 Qwen 跑得快"

- 照片去重（dHash）可减少 30-40% Qwen 调用，收益最大
- maxTokens=64、OpenCL GPU 已在前期文档中建议，应同步落地
- Bitmap 复用是边际收益，优先级靠后

### 10.7.4 MobileCLIP：先 INT8 标量量化，再考虑 PQ/HNSW

- 当前万级暴力搜索可接受，不要过早引入复杂 ANN
- INT8 SQ8 实现简单、损失小，可先获得 4x 内存收益
- PQ/HNSW 在图库 > 5 万或搜索延迟 > 100ms 时再评估

### 10.7.5 统一框架：不急于现在

当前多框架并存是历史债，但迁移风险高。建议：
- 先完成 KWS 迁移（解除 MNN 耦合）
- 再完成 LLM 量化（明确 MNN 是否继续承载 LLM）
- 最后评估是否将人脸/CLIP 统一到单一框架

---

## 10.8. 风险门禁

以下任一情况未通过，不应进入下一阶段：

- [ ] 量化后中文图像理解准确率下降 > 5%
- [ ] KWS 迁移后误触发率 > 1次/小时
- [ ] 相机页 + 本地 LLM 仍触发 OOM
- [ ] 人脸检测 CPU 降级路径导致 ANR 或崩溃率上升
- [ ] 统一框架迁移导致任一核心功能不可用

---

## 10.9. 相关文档

- `docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` — 推理引擎与模型全景梳理
- `docs/03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md` — MNN-LLM 性能优化
- `docs/03-TECHNICAL-SPECS/VOICE_STACK.md` — KWS 唤醒词迁移
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` — TAG 性能瓶颈分析
- `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` — 相册自然语言搜索（含 MobileCLIP 语义召回）
- 「[11. MNN 多模型加载/卸载改造清单](#11-mnn-多模型加载卸载改造清单)」 — 多模型生命周期改造
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — 大美丽美颜引擎

---

> **维护说明**: 本文档应随优化方案实施进度同步更新。每完成一项优化，需回填实际收益数据、修正风险等级，并调整后续优先级。


---

## 11. MNN 多模型加载/卸载改造清单

## 11.1. 目标

- 对齐 MNN 官方推荐的资源分层：
  - `Interpreter`（模型持有）
  - `Session`（推理数据持有）
  - 分级释放：`releaseSession` / `releaseModel` / `destroy`
- 让 LLM / ASR / 人脸检测支持**独立加载、独立卸载、互不干扰**。
- **Agent First 架构**：LLM 是应用驱动核心（相机/相册/设置/聊天等多页面均有 Agent 入口），LLM 生命周期策略必须与人脸/ASR 差异化——跨页面常驻，非页面级绑定。
- 在场景切换与内存压力下，行为可预测、可观测、可回归测试。

---

## 11.2. 现状问题（结合当前代码）

1. 人脸检测场景切换后自动卸载不稳定（依赖条件不合理，常驻引用导致不触发）。
2. `MnnLandmarkDetector` 的 `requireGpu` 没有完整生效（`useGpu` 传参写死）。
3. `LocalLlmEngine` / `SherpaMnnAsrEngine` 注册了资源监听，但缺少明确反注册生命周期，存在监听器累积风险。
4. `OnlineRecognizer` / `OnlineStream` 采用 `finalize()` 触发释放，不符合现代资源管理最佳实践。
5. 缺少统一"释放等级"抽象（软释放/会话释放/彻底释放），模块间语义不一致。
6. **LLM 与 Face/ASR 混用同一卸载策略**：当前未区分模型使用模式——LLM 需要跨页面常驻（Agent 多入口），但现有逻辑可能跟随页面切换误卸载 LLM，导致 Agent 响应延迟（冷启动 2s+）且增加功耗（反复加载）。

---

## 11.3. 改造清单（P0 / P1 / P2）

## P0（必须先做）

### P0-1 修正人脸检测卸载触发逻辑（场景驱动）
- **改造点**
  - 将人脸检测卸载触发条件从“引用计数”与“场景状态”解耦。
  - 相机离开（`CAMERA -> OTHER/CHAT/SETTINGS`）时可触发卸载，即使仍有 owner 存在。
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
  - `app/src/main/java/com/picme/features/camera/CameraScreen.kt`
- **验收标准**
  - 从相机页切到非相机场景后，5~10s 内触发 face unload 回调。
  - 不依赖手动 `manager.release()` 才能卸载。

### P0-2 增加监听器反注册，避免泄漏
- **改造点**
  - 给 `LocalLlmEngine` / `SherpaMnnAsrEngine` 增加 `close()/releaseAll()` 生命周期收口。
  - 在销毁时调用：
    - `unregisterSoftTrimListener`
    - `unregisterSafeUnloadListener`
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/LocalLlmEngine.kt`
  - `agent-core/src/main/java/com/picme/agent/core/voice/SherpaMnnAsrEngine.kt`
- **验收标准**
  - 反复进入/退出页面、切换 ASR/LLM 100 次后，监听器数量不增长。

### P0-3 移除 `finalize()` 释放依赖
- **改造点**
  - `OnlineRecognizer` / `OnlineStream` 改为 `Closeable/AutoCloseable` + 显式 `close()`。
  - 业务层统一在 `try/finally` 或 `use {}` 中释放。
- **涉及文件**
  - `agent-core/src/main/java/com/k2fsa/sherpa/mnn/OnlineRecognizer.kt`
  - `agent-core/src/main/java/com/k2fsa/sherpa/mnn/OnlineStream.kt`
- **验收标准**
  - 不再依赖 GC 时机回收 native 资源。
  - 长时间运行 native 内存曲线稳定。

### P0-4 统一释放等级 API（跨模块一致）
- **改造点**
  - 统一定义释放等级（建议）：
    - `SOFT`: 清缓存（如 LLM KV cache、ASR stop streaming）
    - `SESSION`: 释放 Session/Tensor（保留模型）
    - `FULL`: 释放权重与解释器（彻底卸载）
  - 人脸、ASR、LLM 都对齐这三档语义。
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
  - `agent-core/src/main/java/com/picme/agent/core/llm/MnnLlmClient.kt`
  - `beauty-engine/src/main/java/com/picme/beauty/internal/facedetect/mnn/MnnFaceDetector.kt`
  - `beauty-engine/src/main/cpp/mnn_jni_bridge.cpp`
- **验收标准**
  - 设置页或调试页可独立触发某模块三档释放，行为一致。

### 差异化生命周期原则（Agent First 架构约束）

三种模型的**使用模式**根本不同，卸载策略必须差异化：

| 模型 | 使用模式 | 作用域 | 卸载触发条件 | 保活等级 |
|------|----------|--------|-------------|----------|
| **LLM** (Qwen3-1.7B) | 跨页面常驻，Agent 驱动核心 | 全局（相机/相册/设置/聊天） | 仅内存压力 | **跨页面保活**，页面切换不触发卸载 |
| **ASR** (Sherpa) | 按需激活，语音场景独占 | 语音交互开启期间 | 语音关闭 + 冷却计时 | 页面级，语音关闭后延迟释放 |
| **Face** (ROI+Landmark) | 场景绑定，相机页独占 | 相机预览期间 | 离开相机页 + 冷却计时 | 页面级，离开即触发卸载 |

**核心规则**：
- `MnnResourceManager.onSceneChanged()` 必须区分模型类型：**LLM 永远不响应场景切换**，仅响应内存压力。
- Face 离开相机页即卸载（保留 P0-1 行为）。
- ASR 语音关闭后延迟释放（冷却时间防止频繁切换）。

---

## P1（建议紧接）

### P1-1 建立模型状态机（每模型独立）
- **改造点**
  - 每个模型维护状态：
    - `UNLOADED` -> `MODEL_LOADED` -> `SESSION_READY` -> `ACTIVE`
  - 将“是否被请求”与“是否已加载”分离。
- **收益**
  - 支持精细化策略（如：保留模型、释放会话）。
- **涉及文件**
  - `MnnResourceManager` + 各模块 wrapper（LLM/ASR/Face）

### P1-2 引入共享 Runtime（多模型串行链路）
- **改造点**
  - 对串行模型（例如 ROI + Landmark）评估 `Interpreter::createRuntime(...)` 共享 runtime。
- **收益**
  - 降低总内存和重复初始化开销。
- **注意**
  - 输入必须使用映射/拷贝填充，避免直接指针写入导致内存重分配风险。

### P1-3 增强缓存策略（GPU 初始化）
- **改造点**
  - 对 GPU 后端增加 cache 文件策略：
    - `setCacheFile`
    - `updateCacheFile`（resize 后更新）
- **收益**
  - 显著降低二次冷启动耗时。

### P1-4 LLM 跨页面保活策略（Agent First 核心）
- **改造点**
  - `MnnResourceManager.onSceneChanged()` 增加模型类型判断：LLM 跳过场景卸载逻辑，仅响应内存压力。
  - 引入 `ModelUsagePattern` 枚举（`CROSS_PAGE_PERSISTENT` / `PAGE_SCOPED` / `SESSION_SCOPED`），各模型注册时声明。
  - LLM 保活等级定义：
    - **HOT**：模型 + Session 全就绪，首 Token 延迟 < 100ms（高功耗 ~800MB+ native）
    - **WARM**：仅保留模型（Interpreter），无 Session，首 Token 延迟 ~500ms（中功耗 ~600MB）
    - **COLD**：模型未加载，首 Token 延迟 ~2s（低功耗 ~0MB native 额外占用）
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
  - `agent-core/src/main/java/com/picme/agent/core/LocalLlmEngine.kt`
  - `agent-core/src/main/java/com/picme/agent/core/model/AgentModels.kt`（新增 `ModelUsagePattern`）
- **验收标准**
  - 相机 → 相册 → 设置 → 聊天，LLM 全程保持 WARM+，不触发 FULL 卸载。
  - Face 离开相机页后正常卸载（不受 LLM 策略影响）。
  - 可通过调试面板查看每个模型的保活等级。

---

## P2（优化项）

### P2-1 结构化可观测性
- 统一埋点：
  - `model_load_start/end`
  - `session_create/release`
  - `model_release`
  - `memory_before/after`
- 输出模块：`LLM` / `ASR` / `FaceROI` / `FaceLandmark`

### P2-2 压测与回归自动化
- 增加场景切换压测脚本：
  - 相机 <-> 设置 <-> 聊天循环
  - 语音开关循环
  - 模型下载后热切换
- 产出：崩溃率、加载耗时、native 内存峰值

### P2-3 LLM 热恢复策略（降低感知延迟）
- **改造点**
  - 当 LLM 处于 WARM 状态（模型已加载，无 Session）时，用户触发 Agent 交互自动创建 Session 并恢复到 HOT，延迟控制在 500ms 内。
  - 当 LLM 处于 COLD 状态时，用户触发 Agent 交互显示 "Agent 启动中..." 加载态，异步加载模型（~2s），加载完成后自动恢复。
  - 预加载策略：App 启动时后台异步加载 LLM 到 WARM，无需等待首次交互。
  - LRU 驱逐：当 native 内存压力达到阈值时，优先 SOFT 降级 LLM（释放 KV Cache），其次 SESSION 降级（释放 Session），最后 FULL 卸载 Face（已离开相机页则直接卸载）。此驱逐顺序由手动或内存压力信号触发，**不依赖电量自动降级**。
- **涉及文件**
  - `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
  - `app/src/main/java/com/picme/features/common/chat/AgentLoadingIndicator.kt`（新增或修改）
  - `app/src/main/java/com/picme/domain/usecase/AiAgentUseCase.kt`
- **验收标准**
  - WARM → HOT 恢复延迟 < 500ms。
  - COLD → HOT 加载期间显示 loading UI，不阻塞主线程。
  - App 启动后 5s 内 LLM 达到 WARM 状态。

---

## 11.4. 验收清单（DoD）

- [ ] 场景切换可稳定触发 face unload，不依赖手动 release。
- [ ] LLM / ASR / Face 可分别执行 SOFT / SESSION / FULL 三档释放。
- [ ] 移除 `finalize` 依赖后，无 native 资源泄漏回归。
- [ ] 连续 30 分钟压力切换测试无崩溃。
- [ ] **Agent First 验收**：
  - [ ] 相机 → 相册 → 设置 → 聊天，LLM 全程保持 WARM+，不触发 FULL 卸载。
  - [ ] 任意页面触发 Agent 交互时，WARM 状态下恢复延迟 < 500ms。
  - [ ] Face 离开相机页正常卸载，不受 LLM 保活策略干扰。
- [ ] 内存指标符合目标（建议）：
  - 离开相机页后 face 相关 native 内存在 10s 内明显下降。
  - 语音关闭后 ASR 在冷却窗口内释放到预期水平。
  - LLM 跨页面切换时 native 内存无明显波动（不触发反复加载/卸载）。

---

## 11.5. 推荐实施顺序（最短路径）

1. `P0-1` 场景卸载逻辑修复（先修 Face 独立卸载）
2. `P0-2` 监听器反注册
3. `P0-3` 去 finalize
4. `P0-4` 统一释放等级 API
5. `P1-4` LLM 跨页面保活（Agent First 核心，依赖 P0-4 释放等级 + P0-1 场景解耦）
6. `P1-1` 模型状态机（依赖 P0-4 + P1-4 策略明确后建模）
7. `P1-2` 共享 Runtime + `P1-3` GPU cache
8. `P2-3` LLM 热恢复（依赖 P1-4 保活等级）
9. `P2-1` 结构化可观测性 + `P2-2` 压测回归

---

## 11.6. 任务化执行表（负责人 / 工时 / 风险）

| ID | 任务 | 负责人 | 预计工时 | 依赖 | 风险等级 | 风险说明 | 缓解措施 |
|---|---|---|---|---|---|---|---|
| P0-1 | 修正 FaceDetection 场景卸载触发逻辑 | RD | 1.5 人天 | 无 | 高 | 场景切换与引用计数耦合，改错会导致误卸载 | 先加日志与单测，灰度开关控制 |
| P0-2 | LLM/ASR 监听器反注册与生命周期收口 | RD | 1 人天 | 无 | 中 | 误删监听导致内存策略不生效 | 引入注册计数日志与回归脚本 |
| P0-3 | 移除 `finalize` 依赖，改 `Closeable` 显式释放 | RD | 1.5 人天 | P0-2 | 高 | 释放时机变化可能触发 native 崩溃 | 全链路压测 + `try/finally` 审计 |
| P0-4 | 统一 SOFT/SESSION/FULL 释放等级 API | RD | 2 人天 | P0-1, P0-2 | 高 | 跨模块语义对齐复杂，易出现行为回归 | 先定义接口契约，再分模块接入 |
| P1-4 | LLM 跨页面保活策略（Agent First 核心） | RD | 2 人天 | P0-4, P0-1 | 高 | 页面切换误触发 LLM 卸载影响 Agent 体验 | 差异化表格先行，开关控制灰度 |
| P1-1 | 建立每模型独立状态机 | RD | 2 人天 | P0-4, P1-4 | 中 | 状态迁移遗漏导致卡死或重复加载 | 状态图 + 穷举迁移单测 |
| P1-2 | 评估并接入共享 Runtime | RD | 2 人天 | P1-1 | 中 | 内存复用导致输入指针失效 | 强制映射/拷贝输入，禁用直接填充 |
| P1-3 | GPU cache 策略（`setCacheFile/updateCacheFile`） | RD | 1 人天 | P1-2 | 低 | cache 脏数据导致初始化异常 | 版本化 cache key + 失败回退 |
| P2-3 | LLM 热恢复策略（预加载 + loading UI） | RD | 1.5 人天 | P1-4 | 中 | COLD 恢复时 UI 卡顿 | 异步加载 + loading 态兜底 |
| P2-1 | 结构化可观测性埋点 | RD | 1.5 人天 | P0-4 | 低 | 指标口径不一致 | 统一事件 schema 与命名规范 |
| P2-2 | 压测与回归自动化脚本 | QA + RD | 2 人天 | P0 全部 | 中 | 用例覆盖不足 | 按场景矩阵维护必测集 |

### 11.6.1 里程碑建议

| 里程碑 | 范围 | 目标产出 | 通过标准 |
|---|---|---|---|
| M1（本周） | P0-1 ~ P0-2 | 卸载触发修复 + 生命周期闭环 | 无监听器增长，Face 场景切换可卸载 |
| M2（下周） | P0-3 ~ P0-4 | 释放语义统一（SOFT/SESSION/FULL） | 三档释放可独立触发且行为一致 |
| M3（第 3 周） | P1-4 | **Agent First 保活** | LLM 跨页面不卸载 |
| M4（第 4 周） | P1-1 ~ P1-3, P2-3 | 状态机 + 共享 Runtime + GPU cache + 热恢复 | WARM → HOT < 500ms，冷启动耗时下降 |
| M5（持续） | P2-1 ~ P2-2 | 可观测性与自动化回归 | 压测 30 分钟稳定无崩溃 |

### 11.6.2 进度跟踪模板（可直接复制到任务系统）

- [x] P0-1 FaceDetection 场景卸载触发逻辑修复
- [x] P0-2 LLM/ASR 监听器反注册
- [x] P0-3 去 `finalize` 显式释放
- [x] P0-4 统一释放等级 API
- [x] P1-4 LLM 跨页面保活策略
- [ ] P1-5 功耗感知动态降级
- [x] P1-1 每模型状态机
- [ ] P1-2 共享 Runtime 接入
- [ ] P1-3 GPU cache 策略
- [ ] P2-3 LLM 热恢复策略
- [ ] P2-1 结构化可观测性
- [ ] P2-2 压测与回归自动化

### 11.6.3 风险门禁（上线前必须满足）

- [ ] 连续场景切换 100 次，无 native crash
- [ ] LLM/ASR/Face 任一模块可单独 FULL 卸载，不影响其余模块
- [ ] 退出相机页后 Face 模型在目标时间窗内释放，LLM 不受影响
- [ ] 语音关闭后 ASR 释放符合预期
- [ ] LLM 跨页面切换时保持 WARM+，不触发 FULL 卸载
- [ ] WARM → HOT 恢复延迟 < 500ms
- [ ] 内存压力下 LRU 驱逐顺序正确：LLM SOFT → LLM SESSION → Face FULL

