# 打标模型收敛 + 模型中心「必须 / 推荐」分层

> **状态**: 设计稿（待评审）
> **日期**: 2026-07-26
> **作者**: RD
> **关联**: `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md`、`docs/03-TECHNICAL-SPECS/ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md`、`docs/superpowers/specs/2026-07-26-florence2-use-cache-branch-bug.md`

---

## 1. 背景与决策

端侧图片打标（TAG）方案历经多轮试验（SmolVLM-500M / Qwen3-VL-2B / LFM2-VL / Florence-2）后结论定型：

- **保留** Florence-2（`florence2_base`，ONNX INT8，~260MB）为**默认打标模型**；
- **保留** Qwen3-VL-2B（`qwen3_vl_2b`，MNN 4bit，~1.4GB）为**备选**（内部 fallback）；
- **移除** SmolVLM-500M（`smolvlm_500m`）；LFM2-VL 此前已下线。

据此收敛打标代码与文档，并借机重整模型中心的优先级分层：在「必须」之外新增「推荐」分层，把美颜/证件照/本地 LLM 等非核心模型放入推荐，支持 WiFi 下静默预下载。

LFM2-VL 已无目录条目，仅在注释/测试/文档中残留引用，本次一并清理。

## 2. 目标 / 非目标

**目标**
1. 移除 SmolVLM-500M 相关目录条目、选择器常量、测试与文档引用。
2. 打标默认翻转为 Florence-2（首选 + 默认），Qwen3-VL-2B 为 fallback。
3. 收敛「必须」模型列表为**单一事实来源**（消除 JSON tag / `REQUIRED_MODEL_IDS` / `GALLERY_REQUIRED_MODEL_IDS` 三处不一致）。
4. 新增「推荐」分层与 `推荐` Tab；推荐模型 WiFi 下静默预下载（默认开启、可关闭）。
5. 同步三层文档（原子提交）。

**非目标**
- 不删除 CLIP 语义搜索链路（`mobileclip-onnx` + `opus-mt-zh-en`）——搜索是仍在使用的独立特性，按用户原则其必需模型保留在「必须」。
- 不做完整的下载调度/重试体系（本期为「最小自动下载」：WiFi 触发一次，失败不自动重试）。
- 不改 Florence-2 推理质量与 `use_cache_branch` 加速（另有专门 spec）。

## 3. 模型分层（最终）

### 3.1 必须（`REQUIRED_MODEL_IDS`，Tier 1，7 个）

相册扫描 / 打标 / 搜索链路所必需。进入相册自动扫描前须全部就绪。

| ID | 用途 |
|----|------|
| `face-det-retina500m-mnn` | 人脸 ROI 检测 |
| `face-landmark-2d106-mnn` | 人脸 2D106 关键点 |
| `face-embedding-glint360k-r100-mnn` | 人脸特征 embedding（聚类） |
| `florence2_base` | **默认打标 tagger**（新晋必须） |
| `opus-mt-en-zh` | 打标 en→zh summary/标签 汉化（新晋必须） |
| `mobileclip-onnx` | 语义搜索 CLIP 编码 |
| `opus-mt-zh-en` | 搜索 zh→en 查询翻译 |

变化：`−qwen3_vl_2b`、`−mediapipe-face-landmarker`、`+florence2_base`。（`opus-mt-en-zh` 原本已在集合内。）

### 3.2 推荐（`RECOMMENDED_MODEL_IDS`，Tier 2，5 个）

非核心、WiFi 下可静默预下载。

| ID | 用途 |
|----|------|
| `sherpa-onnx-zipformer-zh-en` | ASR 语音输入 |
| `sherpa-onnx-kws-zipformer-wenetspeech` | KWS 唤醒词 |
| `modnet-onnx` | 证件照/抠图 |
| `u2netp-onnx` | 证件照/抠图（轻量） |
| `mediapipe-face-landmarker` | 相册人脸标记**预览**（从必须移入） |

注：`CHAT_MODEL_IDS`（已存在）= `{sherpa-zipformer, sherpa-kws}`，可作为推荐集合的子集来源。（`qwen3_5_2b` 已随端侧文本 LLM 移除，不再推荐。）

### 3.3 普通可选（既非必须也非推荐）

| ID | 说明 |
|----|------|
| `qwen3_vl_2b` | 备选 tagger；仅作 `TaggerModelSelector` 内部 fallback，不主动推荐、不静默下载 |
| `face-det-retina10g-mnn` | 备选人脸检测器 |
| `smolvlm_500m` | **本期移除**（见 §4） |

## 4. 打标收敛

### 4.1 选择器翻转
`app/src/main/java/com/mamba/picme/domain/tag/TaggerModelSelector.kt`
- `defaultKey` → `"florence2_base"`
- `preferredKey` → `"florence2_base"`（首选 = 默认）
- `knownKeys` → `setOf("florence2_base", "qwen3_vl_2b")`
- 删除 `smolvlm_500m` 常量与相关分支；更新类注释（去掉 SmolVLM 首选表述，保留 LFM2 已下线注记）。
- `resolve()` 语义不变：显式已知 key → 用它；否则首选 `florence2_base`；不可用回退 `qwen3_vl_2b`；全不可用 → `defaultKey`。

### 4.2 目录条目
`app/src/main/res/raw/llm_models.json`
- 删除 `smolvlm_500m` 整段。
- `florence2_base.tags`：`"recommended"` → `"must-have"`（primary）。
- `opus-mt-en-zh.tags`：`"recommended"` → `"must-have"`。
- `mediapipe-face-landmarker.tags`：`"must-have"` → `"recommended"`。
- 推荐 6 个模型在 `tags` 中补 `"recommended"`（与必须保持一致的装饰性标记，Tab 实际由 `isRecommended` 驱动）。

### 4.3 SmolVLM 孤儿清理（启动迁移）
设备上已下载的 `smolvlm_500m`（~598MB）在条目移除后将无法在模型中心删除。新增**一次性启动迁移**：
- 触发点：`PoLangApplication.onCreate()`（或既有迁移入口），用 DataStore 布尔位 `migration_smolvlm_purged` 保证只执行一次。
- 行为：删除 `ModelPathConfig.getModelDir(context, "smolvlm_500m")`（若存在）；记录日志 `PoLang:Download`。
- 失败（IO 异常）不阻塞启动；下次启动重试（位未置）。

## 5. 必须列表单一事实来源

消除三处不一致（现状见 spec 调研表）。方案：

`app/src/main/java/com/mamba/picme/data/download/LlmModelDownloadManager.kt`
- `REQUIRED_MODEL_IDS` 按 §3.1 调整为 7 个。
- 新增 `RECOMMENDED_MODEL_IDS`（§3.2，6 个）。
- 新增 `val isRecommended: Boolean get() = id in RECOMMENDED_MODEL_IDS`。

`app/src/main/java/com/mamba/picme/features/settings/SettingsViewModel.kt`
- `GALLERY_REQUIRED_MODEL_IDS` **改为派生**：`LlmModelDownloadManager.REQUIRED_MODEL_IDS.toList()`（保留列表语义以兼容调用方），消除与 `REQUIRED_MODEL_IDS` 的并行维护。

> 说明：`isRequired` 由 `REQUIRED_MODEL_IDS` 驱动（非 JSON tag），「必须」Tab 已按 `isRequired` 过滤——故 JSON `must-have` tag 仅装饰用，但为一致性仍与集合同步。

## 6. 推荐 Tab

`LlmModelManagerScreen.kt` + `LlmModelDownloadManager.kt`（`ModelMarketData`）
- `serviceCategoryTags`：`listOf("must-have", "recommended", "chat", "photo-tagging", "beauty-camera")`（推荐置于必须之后）。
- `getCategories()` / `groupByCategory()`：`must-have` 分支按 `isRequired`、新增 `recommended` 分支按 `isRecommended`，其余按 tag。
- `getCategoryIcon("recommended")`：`Icons.Outlined.Download`（与「必须」的 `Star` 区分）；`getTagColor("recommended")`：`MaterialTheme.colorScheme.tertiary`（与必须的 `0xFFE53935` 红色区分）。
- Tab 翻译：`TagTranslations` 增加 `"recommended" to "推荐"`（EN: "Recommended"）。

`strings.xml`（×3：`values`/`values-zh-rCN`/`values-zh-rTW`）
- 新增 `model_label_recommended`（"Recommended" / "推荐" / "推薦"）。
- 必要时新增推荐 Tab 标题、auto-download 设置项文案（见 §7）。

## 7. WiFi 静默预下载（最小实现）

### 7.1 设置项
`UserPreferencesRepository` + `UserSettingsRepository` + `UserPreferences`
- 新增 DataStore key `AUTO_DOWNLOAD_RECOMMENDED_ON_WIFI = booleanPreferencesKey("auto_download_recommended_on_wifi")`，**默认 `true`**。
- `val autoDownloadRecommendedOnWifiFlow: Flow<Boolean>`（默认 true）。
- 设置页（模型/AI 区块，`SettingsAiAgent` 或模型相关 Section）新增开关；i18n ×3。

### 7.2 自动下载器（ConnectivityManager 触发，非 WorkManager）
> 项目未引入 `androidx.work`（既有「Worker」类为 `IndexingTaskQueue` 驱动的进程内任务，非 WorkManager）。故采用既有网络设施的最小实现。

新增 `RecommendedModelAutoDownloader`（`data/download/`，普通类，便于单测）：
- **纯逻辑**（可单测）：`computeMissing(downloadedIds: Set<String>, inProgressIds: Set<String>): List<String>` = `RECOMMENDED_MODEL_IDS - downloadedIds - inProgressIds`，保持稳定顺序。
- **触发**：`suspend fun triggerIfEligible(context, settings, downloader)`：
  1. `settings.autoDownloadRecommendedOnWifiFlow` 首个值若 `false` → 直接返回。
  2. `NetworkUtils.isWifi(context) == false` → 直接返回。
  3. 计算 `computeMissing(...)`；为空 → 返回。
  4. 对每个缺失 id 调用既有 `LlmModelDownloadManager.downloadModel(id)`（收集 Flow 驱动下载）；串行，单模型失败不中断其余；不自动重试。
- **入口**：复用 `PoLangApplication` 既有的 `ConnectivityManager.NetworkCallback`（onCapabilitiesChanged/onAvailable）——WiFi 可用时调用 `triggerIfEligible`；并在 `onCreate` 末尾做一次初始检查。用 `AtomicBoolean` 防重入（避免回调多次触发重复下载）。

### 7.3 可见性
「静默」= 无弹窗、无提示音；下载进度**可见**于模型中心「推荐」Tab（复用既有 `downloadStates`/进度条）。用户可在该 Tab 取消/暂停。

## 8. 变更清单

**代码（~10）**
| 文件 | 改动 |
|------|------|
| `app/src/main/res/raw/llm_models.json` | 删 smolvlm_500m；retag florence2/opus-en-zh/mediapipe；推荐 6 项补 recommended |
| `domain/tag/TaggerModelSelector.kt` | 默认翻转、删 smolvlm 常量 |
| `data/download/LlmModelDownloadManager.kt` | `REQUIRED_MODEL_IDS` 调整；新增 `RECOMMENDED_MODEL_IDS` + `isRecommended`；`serviceCategoryTags` + 分组逻辑；recommended 翻译 |
| `features/settings/LlmModelManagerScreen.kt` | 推荐 Tab 图标/颜色 |
| `features/settings/SettingsViewModel.kt` | `GALLERY_REQUIRED_MODEL_IDS` 派生 |
| `data/preferences/UserPreferencesRepository.kt` + `UserSettingsRepository.kt` + `domain/model/UserPreferences.kt` | auto-download 设置项 |
| `data/download/RecommendedModelAutoDownloader.kt`（新） | WiFi 预下载（纯逻辑 + 触发器） |
| `PoLangApplication.kt` | 既有 NetworkCallback 中接入触发器 + 初始检查 + SmolVLM 迁移调用 |
| `data/download/ModelPathConfig.kt` | 若无 `smolvlm_500m` 路径常量，迁移按字面 id 复用 `getModelDir` |

**测试（2）**
| 文件 | 改动 |
|------|------|
| `app/src/test/.../tag/TaggerModelSelectionTest.kt` | 重写：Florence-2 默认/首选，Qwen fallback；删 smolvlm 用例 |
| `app/src/test/.../download/ModelFilesMappingTest.kt` | 删 lfm2/smolvlm 文件断言（lfm2 已无条目、smolvlm 移除） |

新增（可选）：`RecommendedModelAutoDownloader.computeMissing` 的纯逻辑测试（给定已下载/进行中集合 → 应下载集合）。

**文档（4，与代码原子提交）**
| 文件 | 改动 |
|------|------|
| `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` | tagger = Florence-2 默认 + Qwen3-VL-2B 备选；删 SmolVLM/LFM2 选项 |
| `docs/03-TECHNICAL-SPECS/ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md` | 顶部加「决策 (2026-07)」段：Florence-2+Qwen3-VL-2B 终选，其余出局；保留调研正文 |
| `docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` | 模型清单表同步（必须/推荐分层、删 smolvlm） |
| `docs/01-PRODUCT/FEATURES.md`（或模型中心相关文档） | 补「推荐 Tab + WiFi 静默预下载」行为说明 |

**strings.xml ×3**：`model_label_recommended`、auto-download 开关标题/摘要、推荐 Tab 文案。

## 9. 测试与验收

- JVM 单测：`TaggerModelSelectionTest`、`ModelFilesMappingTest` 全绿；新增推荐候选计算测试。
- 编译：`./gradlew :app:assembleDebug` 通过。
- 既有质量门：`./gradlew test`（真门槛，见质量门现状）。
- 设备验证（人工，非阻塞）：模型中心「必须」7 项 /「推荐」6 项正确；推荐 Tab 进度可见；WiFi 下触发预下载；开关关闭后不再触发；启动迁移清掉旧 smolvlm 目录。

## 10. 风险与备注

- **静默下载体积**：推荐集合总计 ~1.6GB+（含 qwen3_5_2b 1.3GB）。默认开启可能在用户未察觉时占用带宽/存储——以 `STORAGE_NOT_LOW` 约束 + 可见进度 + 易关闭缓解；若反馈不佳，后续可拆分「仅小模型自动下载」。
- **`qwen3_vl_2b` 既不在必须也不在推荐**：作为内部 fallback 仍由 `TaggerModelSelector` 在 Florence-2 不可用时回退使用；用户需手动下载。若实际 Florence-2 ONNX 在部分设备不稳，可后续再议提升其优先级。
- **三处列表已收敛为单一来源**：后续新增必须/推荐模型只改 `REQUIRED_MODEL_IDS` / `RECOMMENDED_MODEL_IDS`。
- 早期 SmolVLM / LFM2-VL 的选型设计稿（已废弃、模型已移除）已随打标方案定型一并清理。
