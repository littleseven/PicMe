# 模型下载 / 相册扫描性能与功耗优化评估

> **版本**：1.0
> **日期**：2026-09-04
> **状态**：评估稿（未实施；记录现状事实 + 优化方案 + 落地批次，供排期决策）
> **关联文档**：`docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` §7（性能分析）、`docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` §10（优化方案评估）/§11（多模型生命周期改造清单）、`docs/03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md`
> **动机**：模型下载耗时长是新用户的使用门槛；TAG 全量扫描为小时级任务；两者都必须同时满足功耗与发热约束。

---

## 1. 结论摘要

- **下载侧**最大的结构性问题不是带宽，而是：全部 16 个模型只走 `modelscope.cn` 单源（海外用户直连国内 CDN）、批量下载**模型间/文件间均串行**、Android 并行下载器**无分块级断点续传**、失败后无自动重试。
- **扫描侧**大头在 Pass 3（图像打标，约占 87%），且文档已定案的 **P1 优化「照片去重跳过 Pass 3」未落地**（测算节省 30-50% VLM 调用）；默认打标器 Florence-2 是纯 CPU 路径（2-8s/张），而 OpenCL 可用的设备上 Qwen3-VL GPU 路径约 1s/张。
- **功耗/热侧**最大盲区：电池/热守卫（`guardCheck`）**只挂在 Pass 3**，Pass 1 的 FORCE_GPU 连续推理无任何守卫与冷却；热管理为被动轮询 `currentThermalStatus`（事后刹车），未用 `getThermalHeadroom` 趋势预测；OpenCL 黑名单为永久降级（文档承诺的「每周重试」未实现）。

---

## 2. 现状事实

### 2.1 模型下载链路

| 事实 | 位置 |
|------|------|
| 16 个模型全部源自 ModelScope，URL 形如 `modelscope.cn/models/{repo}/resolve/master/{file}`；HF/hf-mirror 备用源路径存在但为死代码 | `androidApp/.../data/download/LlmModelDownloadManager.kt`（`downloadModel` 只取 `sources["ModelScope"]`）；清单 `androidApp/src/main/res/raw/llm_models.json`、iOS `Resources/llm_models.json` |
| Tier 1 必需集约 **1.61GB**（face-det500m + 2d106 landmark + Glint360K embedding 248MB + Florence-2 266MB + MobileCLIP 381MB + opus-mt 双向 656MB）；Qwen3-VL-2B（1.37GB）不在 Tier 1 | `LlmModelDownloadManager.kt` Tier1/Tier2 常量；iOS `ModelCatalog.swift` 同构 |
| 单文件 >32MB 走 4 段分块并发；但**模型内文件串行、批量模型串行**（`startBatchDownload`、`RecommendedModelAutoDownloader` 均为 for 循环） | `LlmModelDownloadManager.kt`、`ParallelFileDownloader.kt`、`SettingsViewModel.kt` |
| Android 并行下载**取消即删档重来，无分块续传**；暂停恢复退化为单连接；失败无重试直接 FAILED。iOS 反有 `.part.meta` 分块级续传 | `ParallelFileDownloader.kt`、`LlmModelDownloadManager.kt:772-928`（resumeDownload）；iOS `ParallelFileDownloader.swift` |
| 无压缩/解压步骤；校验 = size + SHA256（SHA256 取自 ModelScope 文件列表 API） | `LlmModelDownloadManager.kt` `verifyDownloadedFile` |
| 预下载：进 App 即 WiFi 静默下载 Tier1+Tier2（串行）；`autoDownloadRecommendedOnWifi` 默认 true；**无「WiFi+充电」组合条件**；iOS 无 WiFi 判断与预下载 | `MainActivity.kt`、`PoLangApplication.kt`、`RecommendedModelAutoDownloader.kt`、`UserPreferencesRepository.kt` |
| 模型清单内置 APK 资源，**无 version 字段、无差分更新**，改清单需发版；远端 market 拉取代码已死 | `LlmModelDownloadManager.kt:245-293` |
| 服务端不参与模型分发（COS 仅用于 APK/IPA 分发，香港 VPS） | `server/.../routes/DownloadRoute.kt`、`CosService.kt` |
| 体积水分：face-embedding 为 248MB **未量化** MNN；MobileCLIP 为 381MB **fp32**（运行时 fp16 因 CPU NaN 被禁）；opus-mt 双向 656MB 有更小型化替代 | `llm_models.json`、`MobileClipOnnxBackend.kt:63-96` |

### 2.2 TAG 扫描流水线

| 事实 | 位置 |
|------|------|
| 基线：9000 张约 **13h**，Pass 3 占 87% | `TAG_GENERATION.md` §7 |
| Pass 1（人脸 ROI + 106 点 + Embedding + MobileCLIP 内联）GPU 路径 ~100-300ms/张，ROI/landmark 均 `FORCE_GPU`；一次解码最长边 640px 复用 | `TagGenerationScheduler.kt:244-252`、`TagGenerationPipeline.kt:73` |
| Pass 3 默认 **Florence-2-base：768×768 输入、纯 CPU ORT 4 线程（无 NNAPI/GPU）**，2-8s/张；备选 Qwen3-VL-2B OpenCL ~1s/张（输入 512px） | `Florence2Tagger.kt:60,118-123`、`TagGenerationPipeline.kt:76,296-334` |
| 前台 Service（dataSync FGS）+ `PARTIAL_WAKE_LOCK` 全程持有；**无 WorkManager**；Android 14+ FGS 超时靠 AlarmManager 15min 续跑 | `TagScanOrchestrator.kt:180-198`、`TagGenerationService.kt:84-85,536-543` |
| 自动扫描入口：「首次启动 或 充电+夜间（0-6/≥23 点）」；默认增量（4h 避重窗口 + 按 Pass 缺失过滤）、每批 50 张链式调度；第一阶段只跑 Pass1+DBSCAN，Pass 3 延后 | `GalleryScreen.kt:429-471`、`ScanQueuePolicy.kt`、`TagScanOrchestrator.kt:240-290` |
| 节流：轮询固定 100ms；Pass 3 每张后冷却 NONE→800ms / LIGHT→1500ms / MODERATE→3000ms；guard PAUSE 退避 50/300/3000ms | `TagGenerationService.kt:92-99,590-612`、`TagGenerationScheduler.kt:1527-1529` |
| **守卫仅 Pass 3 生效**：`guardCheck`（非充电 ≤5% ABORT / ≤15% PAUSE；热 MODERATE PAUSE / SEVERE ABORT）唯一调用点在 `executeImageTagging` | `TagGenerationService.kt:614-636`、`TagGenerationScheduler.kt:1490-1494,520-534` |
| 热状态为**轮询** `PowerManager.currentThermalStatus`（3 处），未用 `getThermalHeadroom`、未注册热监听 | `TagGenerationService.kt:593,609,625` |
| OpenClGuardian：warmup 20s / 推理超时 30s / 连续失败 3 次降级 / 冷却 24h；**黑名单永久生效，每周自动重试未实现** | `OpenClGuardian.kt:34-44,191-246`（文档 `TAG_GENERATION.md:239` 与实现不一致） |
| 扫描完成后 `AestheticScoreWorker.runUntilDone()` 循环排空 NIMA + eDifFIQA 打分（NNAPI），**无充电/电量门控** | `TagGenerationService.kt:349-363` |
| 未落地已知项：Pass 3 照片去重 dHash（P1，§10.4.2，测算省 30-50% VLM 调用，~3-4 天）；Bitmap 双解码合并（P2）；P1-5 功耗感知动态降级（§11 未完成） | `TAG_GENERATION.md` §7.5、`ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` §10/§11.6.2 |
| pHash 工具现成：`PerceptualHash.kt`（32×32 灰度 DCT，64-bit），目前仅服务于独立去重功能 | `domain/dedup/DedupScanner.kt`、`PerceptualHash.kt` |

---

## 3. 优化方案 A：模型下载（按 ROI 排序）

1. **多源分发 + 竞速/回退（P0，结构性）**：清单配多源——海外走 HuggingFace（复活死代码）或 COS 香港/海外镜像，国内走 ModelScope；下载时按实测速度选源。服务端已有香港 VPS + COS 通路可复用。
2. **模型级/文件级并行（P0）**：批量入口从串行改为 2~3 模型并行 + 模型内文件并行，叠加已有 4 段分块，总墙钟时间约除以并行度。改动集中在 `startBatchDownload` 与 `downloadModel` 的串行循环。
3. **Android 分块级续传（P0）**：对齐 iOS `.part.meta` 侧车方案；恢复时保持 4 段并发，取消退化单连接的逻辑。消除「1.4GB 下到 90% 断掉重来」的最差体验。
4. **模型瘦身（P1，需模型工程）**：Glint360K embedding INT8 量化（248MB → ~1/4）；MobileCLIP 导出侧量化（解决运行时 fp16 NaN 只能 fp32 的问题）；opus-mt 换更小型化版本或降级为按需下载、移出 Tier 1。**目标：Tier 1 从 1.61GB 压到 800MB 以内**——比任何传输优化都管用。
5. **下载时机与清单治理（P1）**：静默预下载加「WiFi+充电」组合档，Qwen3-VL 级大模型仅在该档预下载；模型清单挪服务端下发并加 version 字段，摆脱发版耦合。补失败自动重试（当前直接 FAILED）。

## 4. 优化方案 B：扫描提速（按 ROI 排序）

1. **Pass 3 去重跳过（P1，文档已定案，~3-4 天）**：Pass 1 顺带算 dHash（~1ms/张），相似命中直接复用标签不进 VLM；文档按 40% 重复率估算省 2.8h。复用现成 `PerceptualHash.kt`。（`ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` §10.4.2）
2. **Florence-2 后端升级 / 双引擎竞速（P1）**：接 NNAPI（MobileCLIP/NIMA 已有 `addNnapi()` 先例）；或按设备实测在打标器间选择——OpenCL 可用走 Qwen3-VL GPU（~1s/张），否则 Florence-2 CPU，把写死的优先级改为运行时基准选择。GPU 路径同时更省电。
3. **冷却参数回调（P2，近零成本）**：热 NONE 档 Pass 3 冷却 800ms → 100-300ms（`TAG_GENERATION.md` §7.5 测算省 ~75min），与方案 C-2 的 headroom 调速联动。
4. **零散项（P2）**：Bitmap 双重解码合并（每张省 20-50ms）；DBSCAN O(n²) 暂不动，人脸 >5 万再引入 ANN。

## 5. 优化方案 C：功耗与发热

1. **守卫全覆盖（P0，小改动）**：把 `guardCheck` + 自适应冷却从 Pass 3 提升到 orchestrator 统一轮询层，Pass 1/2/3 全部受电池与热约束。Pass 1 FORCE_GPU 连续推理恰是积热最快的阶段。
2. **headroom 调速替代状态刹车（P1）**：用 `PowerManager.getThermalHeadroom()`（API 29+）取 0~1 热余量趋势，触顶前提前线性加大冷却间隔，消除「全速→过热→ABORT→退避重试」锯齿功耗曲线；注册热状态监听替代轮询。即 §11 未完成的 **P1-5 功耗感知动态降级**的落点。
3. **OpenCL 黑名单周期重试（P1）**：补上文档承诺的每周自动重试。GPU 单位推理能耗远低于 CPU 4 线程满载，恢复 GPU 路径本身即功耗优化（避免「偶发过热 → 永久 CPU 慢车道 → 更耗电更热」恶性循环）。
4. **重负载调度对齐充电（P1）**：全量扫描/美学补分等小时级任务引入 JobScheduler/WorkManager `setRequiresCharging` + 闲时约束（同时替代 dataSync FGS 超时靠 AlarmManager 续命的补丁逻辑）；至少先给 `AestheticScoreWorker` 加充电门控。
5. **NNAPI/NPU 下放（P2）**：Florence-2、MobileCLIP 走 NNAPI（DSP/NPU）后单位能耗可降一个量级；MNN `backend_type` 注释提到 npu 未启用，可在 Snapdragon 机型灰度。

---

## 6. 落地批次建议

> 依据版本优先级原则（功能 > UI > 性能）：下载门槛属**功能可用性**（模型未下载完 = 功能不可用），优先于纯性能项。

| 批次 | 事项 | 性质 |
|------|------|------|
| 第一波 | A-2 下载并行化、A-3 Android 分块续传、C-1 守卫全覆盖、B-3 冷却回调 | 纯代码、无模型工程，直接砍墙钟时间 |
| 第二波 | A-1 多源分发、B-1 Pass 3 去重跳过、C-3 OpenCL 周期重试、C-4 美学打分充电门控 | 已有设计/先例，工程量明确 |
| 第三波 | A-4 模型瘦身 + 清单服务端化、C-2 headroom 调速、C-5 NNAPI 下放 | 需模型工程与实测验证 |

## 7. 顺手记录的「文档 ≠ 实现」清单（修复时同步）

- 增量避重窗口：代码默认 **4h**（`TagScanOrchestrator`），`TAG_GENERATION.md:388` 写 24h。
- 自动全量扫描门控：文档写「仅充电+Wi-Fi」，实现是「首次启动 或 充电+夜间」，无 WiFi 条件（`GalleryScreen.kt:429-471` vs `TAG_GENERATION.md:1231`）。
- OpenCL 黑名单「每周自动尝试一次」：文档有（`TAG_GENERATION.md:239`），代码未实现。

## 8. 验收与度量（实施时回填）

- 下载：Tier 1 全量墙钟时间（分国内/海外网络）、续传恢复后完成率、失败自动重试成功率。
- 扫描：9000 张基线总耗时（当前 ~13h，目标按 `TAG_GENERATION.md` §7.8 的 2-3h 终极形态逼近）、Pass 3 跳过率、单张 P50/P95。
- 功耗：扫描全程温升曲线、`getThermalHeadroom` 均值、ABORT 次数、单位照片能耗（mAh/张）。
