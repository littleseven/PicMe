# 统一三个图像理解入口（模型同源 + 单张/批量一致）

- **日期**: 2026-07-27
- **状态**: 设计已确认，待写实现计划
- **分支**: `feat/chat-preview-swipe-and-llmlog-traceid`（当前 worktree）

## 1. 背景与问题

当前有三个涉及「图像理解」的入口，彼此模型来源与提示词过程不一致：

| # | 入口 | 现状代码路径 | 模型 | 提示词 | 产物 |
|---|------|-------------|------|--------|------|
| 1 | TAG 生成页 Pass 3 批量扫描 | `TagGenerationService` → `TagGenerationScheduler.executeQwenTagging` | 读 `taggerModelKey`（Florence-2 ORT / Qwen） | `stage3SystemPrompt` + 结构化 JSON | 结构化标签 → DB |
| 2 | 图像预览页「图像理解」 | `MediaPager.onStartVisionClick` | **写死 `qwen3_5_2b`，忽略设置** | 「你是一个图像理解助手…」（自由文本） | 自由文本描述 → 浮层（不持久化） |
| 3 | 照片信息弹框「重新打标」 | `onReTag` → `TagGenerationScheduler.processSingleSync` → `TagGenerationPipeline.processPhoto` | 读 `taggerModelKey`（**但 processPhoto 恒走 Qwen，无 Florence-2 分支**） | 与 #1 相同（仅 Qwen 路径） | 结构化标签 → DB |

### 两个关键缺陷

- **缺陷 A（模型不同源）**：entry 2 写死 `qwen3_5_2b`，完全绕过设置项。当默认设置为 Florence-2 时，entry 1/3 用 Florence-2、entry 2 用 `qwen3_5_2b` —— 三者模型不一。
- **缺陷 B（单张/批量不一致）**：entry 1（`executeQwenTagging`）按 `taggerModelKey` 分流 Florence-2/Qwen；entry 3（`processSingleSync` → `processPhoto`）的 Stage-3 **恒调 `stage3QwenTagging`，无 Florence-2 分支**。因此当设置 = `florence2_base`（**默认值**）时，批量用 Florence-2、单张 retag 用 Qwen —— 同一张图两个入口模型与提示词都不同。代码中「与集中扫描同源」的注释是愿景，并非实际。

### 模型全景（本次确认）

- `florence2_base` —— Florence-2 ONNX 打标器（默认）。`<OD>` + `<MORE_DETAILED_CAPTION>` 双任务，`summary` 即英文详细描述段落。
- `qwen3_vl_2b` —— **Qwen3-VL-2B-Instruct-MNN**（`res/raw/llm_models.json` 注册，4bit，Florence-2 的备选打标模型），VL 能力，中英文均强。**本次后唯一的 Qwen 图像理解模型。**
- `qwen3_5_2b` —— 聊天/文本 LLM（`MODEL_ID_LLM`）。**本次后退出图像理解（三个入口范围内）。** 注意：`qwen3_vl_2b` 与 `qwen3_5_2b` 是**不同模型**。

## 2. 目标

- **目标 A（模型同源）**：三个入口使用**同一个模型**，来源为设置页「内容标签模型」项（`taggerModelKey`：`AUTO` / `florence2_base` / `qwen3_vl_2b`）。`qwen3_5_2b` 在这三个入口中不再参与图像理解。
- **目标 B（单张/批量一致）**：同一张图，无论从「Pass 3 批量」还是「重新打标」进入，**模型 + 提示词 + 过程**一致（人脸上下文属数据差异，非过程差异）。
- **目标 C（entry 2 语言适配）**：entry 2 的提示词按模型的中英文能力分别处理（Florence-2 中文弱 → 走翻译；Qwen3-VL 中英文均强 → 直接按 UI 语言出提示词）。

## 3. 非目标（Out of Scope）

- **聊天「发图 → 图像理解」（`ChatViewModel.sendImageMessage`）不在本次范围**，仍用 `qwen3_5_2b`（聊天体验的一部分，语义不同）。本次「`qwen3_5_2b` 退出图像理解」仅约束上述三个入口。
- 不重构人脸 Stage 1/2（检测/聚类）管线；只统一 Stage-3 的模型分流。
- 不改默认设置值（仍 `AUTO` → Florence-2）。
- 不新增独立「描述模型」偏好（避免破坏「三者同模型」）。

## 4. 设计

### 4.1 模型解析（单一事实源，零新增层）

`TagGenerationScheduler` 在构造时已将 `taggerModelKey` 解析为最终 key（`TaggerModelSelector.resolve(raw, isAvailable)`：`AUTO` → 首选 `florence2_base`，按下载可用性兜底到 `qwen3_vl_2b`）。三个入口**统一只读这一个字段**，entry 2 不再绕过它。不新增解析层。

> 已知限制（非本次修复）：`taggerModelKey` 在 scheduler 构造时一次性读取（容器单例），用户运行中改设置后需重启才生效。属既有行为，不在本次范围。

### 4.2 Section 1 —— `runStage3Unified`：统一 entry 1 ↔ entry 3（修缺陷 B）

新增私有原语，集中「模型 + 提示词」分流（这是 1↔3 一致的决定性环节）：

```kotlin
// TagGenerationScheduler（私有）
private suspend fun runStage3Unified(uri: String, faceRoiJson: String?): UnifiedTagResult
```

行为：
- 按 `taggerModelKey` 分流：
  - `florence2_base` → `florence2Tagger.tag(bitmap)`（按 `Florence2Tagger.IMAGE_SIZE` 解码 bitmap）
  - 否则（`qwen3_vl_2b`）→ `ensureModelLoaded()` + `pipeline` 的 Qwen Stage-3（`runQwenFull` / `stage3QwenTagging`，同一 `stage3SystemPrompt` + `promptProvider.userPrompt`，按 512px 解码）
- 返回 `UnifiedTagResult`（tag 字段；face 字段由调用方按各自的人脸上下文填充）

两个调用方都改走它：
- **entry 1 批量**（`executeQwenTagging`）：删除自带的 Florence-2/Qwen 分流段与重复 bitmap 加载，Stage-3 改调 `runStage3Unified`；保留读持久化 `entity.faceRoiResult`、散热冷却（`getPass3CooldownMs()`）、`persistUnifiedTags` 外壳。
- **entry 3 retag**（`processPhoto` 的 Stage-3 调用点）：将 `stage3QwenTagging(...)` 替换为 `runStage3Unified(...)`。**← 缺陷 B 修复点**：retag 从此尊重 Florence-2，与批量同模型同提示词。人脸 Stage 1/2 不变（retag 仍重检测刷新 personIds）。

> bitmap 尺寸细节：Florence-2 需 768px、Qwen 用 512px，由 `runStage3Unified` 内部按所选模型决定解码尺寸，调用方传 `uri` 即可。retag 的 Stage-3 由原先「复用 Stage1/2 已解码 bitmap」改为「`runStage3Unified` 自行解码」——单张 retag 多一次解码，可接受（Stage-3 本就是耗时主体）。

### 4.3 Section 2 —— `describeImage`：entry 2（模型同源 + 语言适配）

新增（挂在 scheduler 上）：

```kotlin
// TagGenerationScheduler
suspend fun describeImage(uri: String): String?
```

- `lang` 内部读 `userSettingsRepository.getAppLanguageBlocking()`（与 `persistUnifiedTags` 选 en/zh JSON 同模式），无需调用方传。
- 复用已解析的 `taggerModelKey` → 与 entry 1/3 同模型（保证 A）。
- 解码 bitmap 后分流：
  - **Florence-2** → `florence2Tagger.tag(bitmap).summary`（英文 caption 段落）
    - `lang = zh`（zh-CN / zh-TW）→ `enToZhTranslator.translate(summary)`
    - `lang = en` → 原文直出
  - **Qwen3-VL-2B** → `ensureModelLoaded()` + `llmEngine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens = 256)`，提示词按 UI 语言直接给（Qwen3-VL 中英文均强）
- 返回描述字符串；失败返回 null。

**语言 × 模型 提示词矩阵（落实目标 C）**：

| 模型 | UI = 中文（zh-CN / zh-TW） | UI = 英文（en） |
|---|---|---|
| Florence-2 | caption → `enToZhTranslator` 译中（Florence-2 中文弱，不自驱） | caption 原文直出 |
| Qwen3-VL-2B | 中文系统提示词直出（「你是一个图像理解助手。请用简洁的中文描述这张图片的内容，包括主要对象、场景、颜色和氛围。」+ 「请描述这张图片」） | 英文系统提示词直出（"You are an image understanding assistant. Briefly describe this image in concise English, covering the main objects, scene, colors and mood." + "Describe this image"） |

两套提示词同义（简洁描述主要对象/场景/颜色/氛围），仅语言不同。

> 设计取舍：提示词与打标**刻意不同** —— entry 2 是「描述」（自由文本），entry 1/3 是「打标」（结构化 JSON）。按「仅统一模型」决策，只有模型同源，提示词各自合理。entry 2 默认设置（Florence-2）下产出 = Florence-2 caption（中文 UI 下译中），与现状（Qwen 描述）不同 —— 用户已确认接受，以换取「三者严格同模型」。

### 4.4 Section 3 —— 改造点 / 错误处理 / 清理 / 测试 / i18n

**改造点（接线）**
- `TagGenerationScheduler`：新增 `runStage3Unified` 与 `describeImage`；改 `executeQwenTagging`、`processPhoto` 走 `runStage3Unified`。
- `MediaPager.onStartVisionClick`：删除写死的 `orchestrator.withModelLoaded(modelId = "qwen3_5_2b")` + 内联 prompt；改调 `scheduler.describeImage(asset.uri.toString())`。经 `onStartVision` 回调从 `GalleryScreen` 注入 scheduler（与 `onReTag` 同模式，`GalleryScreen` 已能访问 `app.container.tagGenerationScheduler`）。`visionResult` / `isVisionLoading` 等 UI 状态不变。

**可用性 / 错误处理（复用现有，不造新轮子）**
- Florence-2 未下载/未 init、Qwen 未下载 → `florence2Tagger` 可用性判定 / `ensureModelLoaded()`（含 OpenCL→CPU 兜底）失败 → 返回 null；UI 显示既有错误文案（`vision_*`）。
- bitmap 解码失败 → null。

**清理（消除第三个不一致源头）**
- `ImageTagIndexingWorker.reTagSingle` + `TAGGING_SYSTEM_PROMPT` / `TAGGING_USER_PROMPT` 已不被相册 retag 调用（相册走 `processSingleSync`）。计划删除这套陈旧独立实现及仅它使用的 `parseLabels`；实现时先 grep 确认无其他调用方（`ChatScreen` 相关注释为 stale，实际走全量扫描）。

**测试（JVM 单测，无设备）**
- `runStage3Unified` 分流：`taggerModelKey = florence2_base` → Florence2Tagger 路径；`= qwen3_vl_2b` → Qwen 路径（fake llmEngine / fake tagger）。
- `describeImage` 语言矩阵：Florence-2×zh → 译中、Florence-2×en → 原文、Qwen×zh → 中文 prompt、Qwen×en → 英文 prompt。
- 一致性回归：同 bitmap + 同 `taggerModelKey`，经 entry 1 入口与 entry 3 入口产出相同。
- `AUTO` 解析 → `florence2_base`（可用时）。

**i18n**
- 描述文本是模型产出，不硬编码；entry 2 错误文案复用既有 `vision_loading` / `vision_result_title` 等。若新增「模型未下载」类提示，须同步 `values/` / `values-zh-rCN/` / `values-zh-rTW/strings.xml`。

## 5. 决策日志（brainstorming 阶段）

1. **entry 2 形态**：选「仅统一模型」——保留自由文本描述形态，只把模型改为来自设置；不并入结构化打标管道。
2. **设置源**：选现有「内容标签模型」（`taggerModelKey`），非 AI 助手本地模型。
3. **架构方案**：选 A —— `TagGenerationScheduler` 作为单一入口（已 owning 模型解析、`Florence2Tagger`、Qwen engine、`enToZhTranslator`、bitmap 加载、持久化）。
4. **模型身份**：`qwen3_vl_2b` ≠ `qwen3_5_2b`；本次后 `qwen3_5_2b` 退出三个入口的图像理解。
5. **聊天发图范围**：不迁，仍 `qwen3_5_2b`。
6. **entry 2 模型**：跟设置（含 Florence-2）—— 三者严格同模型；zh-TW 复用 zh 译文。

## 6. 风险与取舍

- **entry 2 默认体验变化**：默认 Florence-2 下，entry 2 产出由「Qwen 描述」变为「Florence-2 caption 译中」。已确认接受。
- **retag 多一次 bitmap 解码**：Stage-3 改由 `runStage3Unified` 自解码，单张 retag 多一次解码，可忽略。
- **Florence-2 描述质量**：caption 比专为描述微调的 Qwen 提示词略生硬；换取模型同源。
- **运行时改设置不即时生效**：`taggerModelKey` 构造时一次性读取的既有限制，本次不修。

## 7. 验收标准

- 同一张图、默认设置（Florence-2）下：entry 1 批量、entry 3 retag 产出的结构化标签一致（模型 + 提示词 + 过程同源）。
- entry 2 模型随「内容标签模型」设置变化：Florence-2 时出 caption（中文 UI 译中），Qwen3-VL 时出对应语言描述；不再出现 `qwen3_5_2b`。
- entry 2 输出语言跟随 UI `AppLanguage`。
- `qwen3_5_2b` 在三个入口的代码路径中被移除（聊天发图路径保留）。
- JVM 单测覆盖分流、语言矩阵、一致性回归。
