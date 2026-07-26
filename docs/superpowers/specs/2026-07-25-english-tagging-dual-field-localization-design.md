# 多模型英文打标 + 双字段汉化 设计稿

- 日期：2026-07-25（2026-07-26 更新：SmolVLM 出局 + 新增 Qwen3.5-0.8B / Florence-2）
- 状态：**实现中（DB v12 + 管道 + 搜索/展示/备份已落地；模型层迭代中）**
- 范围：相册打标改为「多模型可切换出英文 → 离线汉化 → 中英双字段存储 → 按语言直查」，设置页四选一切换 tagger。

## 1. 背景与目标

### 1.1 为什么要改

原「按 UI 语言路由模型」方案（中文→Qwen3-VL-2B / 英文→SmolVLM-500M）有两个代价：中文用户跑 2B 重模型（发热）、搜索依赖运行时翻译。本设计把**生成语言与展示/检索语言解耦**：tagger 始终出英文统一规格 → 离线汉化到中文 → 双字段存储 → 搜索按语言直查。

### 1.2 SmolVLM-500M 评估结论（已出局）

SmolVLM-500M 经 transformers + MNN 双路径实测 + 官方文档核对，**不适合结构化打标**：
- ✅ 自由描述（caption）6/6 准确（"A woman with dark hair and red lipstick"）。
- ❌ 结构化 JSON / 键值对 / 固定数量标签 → 照抄示例、拒答或残缺。
- 官方定位：图像描述 + 文档问答 + VQA（自由生成），**不是结构化提取模型**。
- MNN 设备上还有额外的视觉注入问题（libMNN.so 缺 `smolvlmVisionProcess` / 模板写死 ChatML）。

**结论：SmolVLM-500M 作为 tagger 出局。** summary 6/6 强但 tags 不稳，无法满足打标需求。

### 1.3 三个候选 tagger（可切换）

| 模型 | 参数 | 格式 | 打标方式 | 功耗 | 集成状态 |
|---|---|---|---|---|---|
| **Qwen3-VL-2B** | 2B | MNN | JSON schema（严格结构化） | ★★ | ✅ 已跑通 |
| **Qwen3.5-0.8B-MNN** | 0.8B(4bit) | MNN | 对话式（关键词+描述，不走 JSON） | ★★★★ | 待集成（同 MNN 桥） |
| **Florence-2-base** | 231M | ONNX/ORT | `<OD>` + `<CAPTION>` 任务 prompt | ★★★★★ | 待集成（新 ORT 路径） |

### 1.4 成功标准

1. 三个 tagger 可在设置页切换，分别出英文统一规格（或可解析为统一规格的）输出。
2. `labelsEn`（英）/ `labelsZh`（中）双字段落库；中文由英文离线派生。
3. 搜索三字段 OR 直查（labels/labelsEn/labelsZh），无运行时翻译依赖。
4. DB v12 平滑迁移，老数据懒回填。
5. Tag Viewer 按 UI 语言展示对应字段。

## 2. 现状（已落地的模型无关基础设施）

- **DB v12**：`MediaEntity` 加 `labelsEn`/`labelsZh`（ADD COLUMN migration）✅
- **DAO**：`updateLabelsEn/Zh` + `searchLabelsAllFieldsInIds`（三字段 OR）✅
- **LabelSinicizer**：英文 `UnifiedTagResult` → 中文（ControlledVocab 平行数组 + BilingualVocab 兜底 + opus-mt-en-zh summary）✅
- **搜索**：三字段 OR 直查（`ExplicitFirstSearchPipeline`）✅
- **展示**：转换点按语言取 `labelsForLanguage`（`MediaRepositoryImpl` + `ExplicitFirstSearchPipeline` + `TagViewerViewModel`）✅
- **备份**：`BackupMediaTagMetadata` + `updateTagMetadataFromBackup` 含双字段 ✅
- **en→zh MT**：`opus-mt-en-zh` 已上传 ModelScope（`budaoshou/OPUS-MT-En-Zh-ONNX-INT8`），catalog + `OpusMtTranslator` 参数化方向 ✅
- **JNI 修复**：`llm_jni_bridge.cpp` prompt 模板按模型 llm_config 拼装（不再写死 ChatML）；图片尺寸按模型 `image_size` 缩放 ✅

## 3. 模型层设计

### 3.1 总体架构

```
图片 → Tagger（三选一，设置页切换）
         ├── Qwen3-VL-2B (2B, MNN)     → JSON schema → UnifiedTagResult
         ├── Qwen3.5-0.8B-MNN (0.8B)   → 对话式输出 → 后处理解析 → UnifiedTagResult
         └── Florence-2-base (231M, ORT) → <OD>+<CAPTION> → 后处理解析 → UnifiedTagResult
                                        ↓
                              labelsEn（英文统一规格 JSON）
                                        ↓
                              LabelSinicizer → labelsZh（中文派生）
                                        ↓
                              labels（=labelsZh 别名，过渡兼容）

搜索：三字段 OR（labels / labelsEn / labelsZh）
展示：按 UI 语言读 labelsForLanguage(lang)
```

### 3.2 Qwen3-VL-2B（现有，已完成）

- MNN 桥 + ChatML 模板 + JSON schema prompt（`DefaultTagPromptProvider.userPrompt`）。
- 直接出 `{scene,activity,objects,tags[8],summary}` JSON → `parseQwenResponse` → `UnifiedTagResult`。
- 7 张图设备实测通过（英文 labelsEn + 汉化 labelsZh）。

### 3.3 Qwen3.5-0.8B-MNN（阶段一，低工作量）

- **架构兼容**：`llm_config.json` = ChatML + Qwen vision tokens（`<|vision_start|><|image_pad|>` + `num_grid_per_side:48`），`image_size:420`，`is_visual:true`。与 qwen3_vl_2b 同架构，MNN 桥直接跑（走 `qwen2VisionProcess` 分支）。
- **prompt 策略**：对话式（不要求 JSON）。参考 SmolVLM 实测结论——开放式 prompt 对小模型更有效：
  - V1 关键词："What keywords describe this image? List them separated by commas." → tags。
  - 描述："Describe this photo in one sentence." → summary。
  - 从 summary 里抽 scene/activity（NLP 后处理）。
- **后处理**：V1 关键词按逗号切分 → tags；描述句 → summary + scene/activity 抽取。组装 `UnifiedTagResult`。
- **集成**：catalog 加 `MNN/Qwen3.5-0.8B-MNN` + ModelPathConfig + 下载管理。`TaggerModelSelector` 加为可选项。
- **风险**：0.8B LLM 骨干比 SmolVLM-500M 的 SmolLM2-360M 大，follow 指令应更强——但**需设备实测确认**。

### 3.4 Florence-2-base（阶段二，高工作量）

- **231M 最轻**，Microsoft 出品，FLD-5B（50 亿标注）训练，**专为结构化视觉任务设计**。
- **任务 prompt 驱动**（不是对话式，是指令式）：
  - `<OD>` → 物体检测，输出标签+bbox → objects + 核心 tags。
  - `<MORE_DETAILED_CAPTION>` → 详细描述 → summary + 从中抽 scene/activity/atmosphere。
  - Python 实测：caption 6/6 准，OD 4/6 可用。
- **部署**：ONNX 格式，走 **ORT（不走 MNN）**。参考 `OpusMtTranslator` 的 encoder-decoder ORT 模式 + 加视觉编码器。三组件：vision encoder + text encoder + text decoder。
- **后处理**：`<OD>` 结果正则提取标签 → objects；`<MORE_DETAILED_CAPTION>` 第一句 → summary；从描述中抽 scene/activity 关键词。组装 `UnifiedTagResult`。
- **关键优势**：结构化输出（OD 永远出标签，不照抄/不拒答），231M 最省电，走 ORT 避开 MNN 视觉坑。

### 3.5 设置页

「打标模型」行扩展为**四选一切换**：自动 / Qwen3-VL-2B / Qwen3.5-0.8B / Florence-2。`TaggerModelSelector` 保留手动覆盖 + 可用性回退。

## 4. Prompt 策略（按模型分发）

| 模型 | prompt 风格 | 示例 | 输出 → 后处理 |
|---|---|---|---|
| Qwen3-VL-2B | 严格 JSON schema | "Output ONLY {scene,activity,objects,tags[8],summary}" | 直接解析 JSON |
| Qwen3.5-0.8B | 对话式（开放） | "What keywords describe this image?" + "Describe in one sentence." | 切词 + 抽 scene/activity |
| Florence-2 | 任务 prompt | `<OD>` + `<MORE_DETAILED_CAPTION>` | OD 标签 + caption 抽取 |

`DefaultTagPromptProvider` 扩展为**按 tagger 模型选择 prompt 策略**（新增对话式 + 任务 prompt 变体）。

## 5. 迁移与回填策略

v12 迁移：ADD COLUMN `labelsEn`/`labelsZh`（已完成 ✅）。老 `labels` 保留作 labelsZh 别名。新扫描的图：tagger 出 labelsEn + LabelSinicizer 派生 labelsZh + labels 别名。老数据懒回填（重打标入口）。

## 6. 分阶段计划

### 阶段一：Florence-2-base（用户优先，高工作量）
1. Florence-2 ONNX 导出（vision encoder + text encoder + text decoder）或找预导出版。
2. catalog + ORT 推理实现（Kotlin，参考 OpusMtTranslator）。
3. OD 解析 + caption 关键词提取 → UnifiedTagResult。
4. 设置页加切换项。

### 阶段二：Qwen3.5-0.8B-MNN（低工作量，后做）
1. catalog + ModelPathConfig + 下载管理。
2. 设备下载 → 设为 tagger → 对话式 prompt 扫描 → 验证质量。
3. 质量过关 → 对话式 prompt provider + 后处理解析 → 接入双字段。
4. 设置页加切换项。

### 清理（SmolVLM 相关）
- `TaggerModelSelector` 去掉 SmolVLM 优选（改回 Qwen3-VL-2B 默认）。
- catalog `smolvlm_500m` 标为已下线（或保留但不在推荐列表）。
- `DefaultTagPromptProvider` 去掉 SmolVLM 恒英文逻辑（改为按模型分发）。

## 7. 取舍与风险

| 项 | 评估 |
|---|---|
| Qwen3.5-0.8B 对话式标签 | 0.8B 比 SmolVLM 大，但**仍需设备实测**确认 follow 指令够不够 |
| Florence-2 ORT 集成 | 新推理路径（vision encoder + decoder loop），工作量最大 |
| 三模型 prompt 差异大 | 需要三套后处理逻辑（JSON / 切词 / OD 解析） |
| SmolVLM 已出局 | 设备上已下载的 smolvlm_500m 可保留（未来微调或许可用）但不在推荐 tagger 列表 |

## 8. 开放问题（评审定）

1. Florence-2 的 ORT 推理是否复用 opus-mt 的 KV-cache decoder loop？还是用 Florence-2 自己的 BART decoder？（需看 ONNX 导出结构）
2. Qwen3.5-0.8B 对话式输出不稳定时（1 个词/跑题），是否有降级到 Qwen3-VL-2B 的自动回退？
3. 三模型并发可用性回退策略：首选不可用时回退顺序？
