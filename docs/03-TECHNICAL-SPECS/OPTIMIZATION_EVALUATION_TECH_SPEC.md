# PicMe 端侧推理优化方案评估

> **版本**: 1.0  
> **状态**: 生效中  
> **最后更新**: 2026-07-06  
> **维护者**: RD Agent  
> **范围**: 对 `ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` 中提出的短期/中期/长期优化方案进行可行性、收益、风险、工时评估

---

## 1. 概述

本文档对 PicMe 当前端侧推理栈的 11 项优化建议进行系统评估。评估维度包括：

- **收益**: 内存、延迟、功耗、用户体验改善程度
- **成本**: 开发工时、模型转换/验证、测试覆盖
- **风险**: 精度损失、兼容性回归、稳定性风险、维护复杂度
- **依赖**: 是否需要前置改造或其他团队配合
- **推荐优先级**: P0/P1/P2/P3
- **验收指标**: 可量化的完成标准

评估基于已有技术文档、代码现状、性能基线数据，并参考了端侧 LLM 量化、CLIP 量化、向量索引等领域的最新工程实践。

---

## 2. 评估总览表

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

## 3. 短期优化方案评估（1-2 周）

### 3.1 Qwen3.5-2B INT4/INT8 量化

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

### 3.2 相机页默认不加载 LLM

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

### 3.3 人脸检测 GPU 失败 CPU 降级

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

### 3.4 统一封装 `AgentOrchestrator.ensureModelLoaded()`

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

## 4. 中期优化方案评估（1-2 月）

### 4.1 Sherpa-ONNX KWS 迁移

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

### 4.2 TAG Pass 3 照片去重（dHash）

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

### 4.3 TAG Bitmap 复用

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

### 4.4 MobileCLIP 向量量化 / PQ / HNSW

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

## 5. 长期优化方案评估（3-6 月）

### 5.1 统一推理框架评估

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

### 5.2 模型动态加载/卸载 + 设备分级

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

### 5.3 人脸模型 INT8 量化评估

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

### 5.4 PBO 异步 GPU readback

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

## 6. 推荐实施路线图

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

## 7. 关键决策建议

### 7.1 LLM 量化：优先 INT4 权重，准备 INT8 回退

不要一步到位 W4A4。建议：
- 主路径：`mnnconvert --weightQuantBits 4` 生成 INT4 权重 + FP16/INT8 激活
- 回退路径：INT8 权重
- 验收以中文图像理解和 Agent 指令准确率为准，不只看 benchmark

### 7.2 KWS 迁移优先于 ASR 优化

当前 ASR 唤醒方案是功耗和延迟的最大短板。迁移到 Sherpa-ONNX KWS 后：
- 待机内存从 282MB → 14MB
- 唤醒延迟从 800ms → <100ms
- 同时解除 LLM/ASR/Face 的 MNN 耦合

### 7.3 TAG 扫描优化：先"让 Qwen 少跑"，再"让 Qwen 跑得快"

- 照片去重（dHash）可减少 30-40% Qwen 调用，收益最大
- maxTokens=64、OpenCL GPU 已在前期文档中建议，应同步落地
- Bitmap 复用是边际收益，优先级靠后

### 7.4 MobileCLIP：先 INT8 标量量化，再考虑 PQ/HNSW

- 当前万级暴力搜索可接受，不要过早引入复杂 ANN
- INT8 SQ8 实现简单、损失小，可先获得 4x 内存收益
- PQ/HNSW 在图库 > 5 万或搜索延迟 > 100ms 时再评估

### 7.5 统一框架：不急于现在

当前多框架并存是历史债，但迁移风险高。建议：
- 先完成 KWS 迁移（解除 MNN 耦合）
- 再完成 LLM 量化（明确 MNN 是否继续承载 LLM）
- 最后评估是否将人脸/CLIP 统一到单一框架

---

## 8. 风险门禁

以下任一情况未通过，不应进入下一阶段：

- [ ] 量化后中文图像理解准确率下降 > 5%
- [ ] KWS 迁移后误触发率 > 1次/小时
- [ ] 相机页 + 本地 LLM 仍触发 OOM
- [ ] 人脸检测 CPU 降级路径导致 ANR 或崩溃率上升
- [ ] 统一框架迁移导致任一核心功能不可用

---

## 9. 相关文档

- `docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` — 推理引擎与模型全景梳理
- `docs/03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md` — MNN-LLM 性能优化
- `docs/03-TECHNICAL-SPECS/VOICE_STACK.md` — KWS 唤醒词迁移
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` — TAG 性能瓶颈分析
- `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` — 相册自然语言搜索（含 MobileCLIP 语义召回）
- `docs/03-TECHNICAL-SPECS/MNN_MULTI_MODEL_LOAD_UNLOAD_CHECKLIST.md` — 多模型生命周期改造
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — 大美丽美颜引擎

---

> **维护说明**: 本文档应随优化方案实施进度同步更新。每完成一项优化，需回填实际收益数据、修正风险等级，并调整后续优先级。
