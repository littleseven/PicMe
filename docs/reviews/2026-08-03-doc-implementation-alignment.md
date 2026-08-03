# 文档与实现一致性对齐审计报告

> **日期**：2026-08-03
> **范围**：根级文档（README/PRODUCT/AI_TOOLS/CLAUDE/AGENTS）、docs/ 全目录（01~07 + ADR）、各模块 AGENTS.md、skills/ 与 .claude/ 的 AI 指令文档、scripts/README
> **不含**：docs-site/（用户明确排除）、docs/superpowers/（plans/specs 归档）、CHANGELOG/RELEASE_NOTE（历史发布记录）
> **背景**：2026-07-03 ~ 08-03 约 1047 个 commit 的大变更（NCNN 整删、ML Kit image-labeling/face-detection 移除、端侧文本 LLM 移除、语音 KWS 落地 + ASR 迁 Sherpa-ONNX、NIMA 人物封面、备份 v5、AI 工程师模式、服务端 7+ 新路由、Room v14→v19、去角色化）后，文档体系与实现严重漂移。
> **方法**：8 路并行只读审计（逐文档对照代码取证）→ 分 8 阶段修复 → 主代理全局复核（关键词残留扫描 + `scripts/check_doc_sync.py`）。

---

## 一、对齐采用的统一口径（经代码核实）

| 事实 | 证据 |
|------|------|
| TAG 流水线为 **3 个活跃 Pass**（FACE_DETECTION 人脸+语义编码内联 / DBSCAN / IMAGE_TAGGING），枚举保留 legacy `MOBILE_CLIP_ENCODING` | `TagScanTaskEntity.kt:68-80`、`TagGenerationControlScreen` 三段进度 |
| Room 数据库 **version = 19** | `AppDatabase.kt:55` |
| `AiAgentMode` 两个同名枚举：runtime-core = OFF/REMOTE/FEISHU；app 层 `UserPreferences.AiAgentMode` = OFF/LOCAL/REMOTE（LOCAL 遗留兜底值，仍被设置/相机/聊天引用） | `AiAgentConfig.kt:11-15`、`UserPreferences.kt:147-151` |
| 人脸检测 = MediaPipe（468→106）+ MNN 双引擎；ML Kit 仅剩 OCR 文字识别 | `app/build.gradle.kts:248-249` |
| ASR = `SherpaOnnxAsrEngine`（不再共享 libMNN.so）；KWS 已落地（`KeywordSpotterEngine`，WAKE_WORD 模式下优先分支） | `runtime-core/.../platform/voice/` |
| 打标模型：Florence-2 默认 / Qwen3-VL-2B / SmolVLM 分流；MobileCLIP **S2**（~397MB）；Pass3 冷却 `DEFAULT_PASS3_COOLDOWN_MS = 800L`；`QWEN_MAX_TOKENS = 256` | `TagGenerationScheduler.kt`、`TagGenerationPipeline.kt:79`、`ModelPathConfig.kt` |
| `MnnResourceManager` 引用计数双方 = VLM 打标 + MNN 人脸检测（ASR 已退出） | `mnn-core/.../MnnResourceManager.kt` |
| IM 远程控制线 2026-07-27 **已重新激活**（RemoteChannel 多通道：飞书+Telegram），根 AGENTS.md 旧索引「已冻结」（2026-07-16 写入）已修正 | git log `-S` 取证 |
| `AutoTagCapability` / `RemoteControlCapability` 代码存在但**未注册**；实际生效路径为 `ChatStartTagScanCapability` / RemoteChannel 直连 | 全仓 grep `registerCapability` |
| `ImageEditCapability`（image_edit/edit_image，CHAT 场景）已注册；`adjust_image` 为 ChatToolService inline 工具 | `PoLangApplication.kt:676`、`ChatToolService.kt:183-225` |

## 二、各区域处置摘要（96 个文件，+1487/-1035 行）

### 根级文档
- **README.md**：ADR 范围 →001~012；5-Pass→3-Pass；`run_script`→`run_gallery_script`；帧同步指向 BEAUTY_ENGINE_TECH_SPEC；补用户问题上报特性。
- **PRODUCT.md**：NCNN 残留 3 处、5-Pass 4 处、`run_script` 2 处清除；维护者去角色化；补 NIMA 封面/备份 v5/report-issue/Qwen3-VL-2B。
- **AI_TOOLS.md**：删 `ncnn-integration` skill 与 FRAME_SYNC 死链；补 `layout-inspector-expert`；5-Pass→3-Pass。
- **AGENTS.md（根）**：§7 AiAgentMode 枚举双枚举实况修正；IM 远程控制索引「已冻结」→「2026-07-27 重新激活」。
- **CLAUDE.md**：On-device Agent 重写（端侧文本 LLM 已移除，远程 tool_calls）；架构图 LOCAL/REMOTE 双链改单一远程链；能力清单标注两个未注册 Capability；PrivacyGuard 改输入分级职责；删 NCNN/SherpaMnn/CAMERA_PREVIEW 死链。

### 01-PRODUCT
- **FEATURES.md**：§1.4.1 重写（fast 路径 = `Scene.GENERAL` 通用预设直应用，场景感知推荐标注规划中）；人脸聚类改 InsightFace(MNN)；新增人物封面（NIMA/eDifFIQA）、§2.7 AI 工程师模式、§2.8 上报问题、§1.6 备份恢复、模型中心横滑分类页；`run_script` 残留 2 处清除。
- **NFR_SPEC.md**：离线指标改「核心媒体处理 100% 离线；AI 对话/指令需网络」；v1.1。
- **SETUP_GUIDE.md**：新增前提⑦ 备份与恢复；TAG 链接改 3-Pass。

### 02-ARCHITECTURE + ADR
- **MODULE_ARCHITECTURE**：ML Kit SO 用途改仅 OCR；ToolSpecification 包 `com.mamba.tool`；补 NIMA/eDifFIQA。
- **AGENT_ARCHITECTURE**：补 NIMA 与 §2.5.4 用户问题上报。
- **ADR-004/006/007**：加「⛔ 状态更新」块（ncnn/ggml 移除、本地指令体系删除、ML Kit 打标被 VLM 取代），正文保留为历史。
- **ADR-001**：包路径 `com/picme/beauty/`→`com/mamba/picme/beauty/`，目录树补齐；**ADR-002**：残留包路径修正；**ADR-003**：NCNN 引用清除；**ADR-005**：删重复引用；**ADR-008**：PrivacyGuard 现状核实更新（`assertLocalOnly` 已删、重定位输入分级）；**ADR-012**：三项任务经代码核实全部完成，「相机专用」前提标注过时。

### 03-TECHNICAL-SPECS
- **TAG_GENERATION.md**（大改）：全篇 3-Pass 口径；删 Pass 5 与 `MlKitTagExtractor`；MobileCLIP-S0→S2；DB v7→v19 + 迁移表补全 12 条；冷却常量 800ms；`mlKitLabels` 标遗留字段；新增 NIMA/eDifFIQA 封面章节；失效优化建议标注。
- **MNN_LLM_OPERATIONS.md**（大改）：§2 整章重写为 VLM+人脸检测双方引用计数（ASR 退出）；路径/类名/日志 tag 全量修正；TC-001~005 验证对象改写。
- **ON_DEVICE_INFERENCE_INVENTORY**：ML Kit 改仅 OCR；删两个已移除模型行；新增 §4.5.1 美学打分模型；计数改「6 套框架、15+ 模型」。
- **VOICE_STACK.md**（大改）：Phase 2 KWS 改「已实施」并勾选验收；SherpaMnn→SherpaOnnx 全量；死链修正。
- **GALLERY_SEARCH.md**：DB v6→v19；Pass 5 删除；Pass 3 模型分流描述；`mlKitLabels` 标历史数据。
- **SERVER_IMPLEMENTATION_PLAN.md**：补 5 个路由文件、10 行 API 契约、migrations 001~009、settings#whitelist/diagnosis。
- **OVERSEAS_SERVER_DEPLOYMENT.md**：路由清单与管理后台页补齐。
- **AI_OPTIMIZATION.md / SMART_OPTIMIZE_VLM_DESIGN.md / ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md / CHAT_UI_UNIFICATION.md / IM_REMOTE_CONTROL_TECH_SPEC.md / BEAUTY_ENGINE_TECH_SPEC.md**：ML Kit/Qwen3.5-2B/SmolVLM 过时引用标注或更正；补 `CommandExecution` 消息类型；§13 重号改 §14 + 失效文件标注；人脸检测改 MediaPipe/MNN。
- 未动（审计无差异）：JS_ENGINE、FACE_DETECTION_ENGINE_ARCHITECTURE、FACE_LANDMARKS、MNN_LANDMARK_DIAGNOSIS、RELEASE_PACKAGE_BACKUP_RESTORE。

### 04-AGENT-CAPABILITIES
- **CAPABILITY_REGISTRY.md**：新增 §14 ImageEditCapability；PersonRelation 命令 2→3（补 query）；AutoTag/RemoteControl 更正为「代码存在但未注册」；§1.1 场景映射校正。
- **COMMAND_REFERENCE.md**：switch_mode 枚举 PHOTO/VIDEO/PRO/DOCUMENT + 新增 §2.6.1 switch_scene；新增 §11 图片编辑命令（edit_image/adjust_image）与 §3.9 人物关系三命令；人脸引擎 MNN/MEDIAPIPE/CUSTOM；滤镜大写；§4 auto_tag 加未注册警示。

### 模块 AGENTS.md
- **camera**（大改）：§2.5 坐标转换数据源改 beauty-engine FaceDetector；§2.6.1 ML Kit 属性表废弃标注；InsightFace2D106Detector 回退改 MNN；§3.0 角色段落删除。
- **gallery**（大改）：3-Pass 口径；PERSON 分组引擎更正；chat 搜索意图经远程 LLM 注明。
- **app**（大改）：features 补 6 目录；domain 补 aesthetic/backup、删 MlKitTagExtractor；Room v14→v19 + v15~v19 迁移要点；NavHost 路由对照 Screen.kt/MainActivity 重写；补 report-issue。
- **core**：Hilt 改「预留扩展（当前手动 DI AppContainer）」；**settings**：模型分类去「本地 LLM」改语音；**capability**：冻结声明修正为「设计未落地、IM 线已经 RemoteChannel 激活」；**data/editor/chat/search**：类名/路由参数/口径/维护者修正。
- 未动（审计无差异）：runtime-core、server、beauty-api、mnn-core、sentencepiece、debug、core/designsystem、di。

### 05/06/07 + 脚本 + skills/.claude
- **DEVELOPMENT.md**：`[kimi-task]`→`[agent-task]` 22 处；失效 spec/脚本引用标注或修正。
- **CODE_REFACTORING_PLAN.md**：顶部加「⚠️ 历史方案」声明（Agent Runtime 实际在 :runtime-core）；删重复 Phase 2。
- **REPO_REORGANIZATION_PLAN.md**：agents//.qoder/「保留」行加删除标注。
- **PERFORMANCE_BASELINE_REPORT.md**：§2 补 NCNN 历史基准警告。
- **LOCAL_ENVIRONMENT / GLOSSARY / COORDINATE_SYSTEM / claude-tunnel README**：sherpa 文件名、新术语、审查日期、标题 Phase 标记修正。
- **skills/ 与 .claude/commands/**：CAMERA_PREVIEW_TECH_SPEC / INSIGHTFACE_106_MAPPING / BEAUTY_ENGINE_FALLBACK 三类死链全部改指 BEAUTY_ENGINE_TECH_SPEC / FACE_LANDMARKS；MNN/NCNN 残留改 MNN（mnn-landmark-diagnosis、intent-router、av-gl-expert、.claude/CLAUDE.md）。
- 全局去角色化：维护者/阅读对象标签统一「项目开发者（、AI Agent）」，角色协作段落删除。

## 三、审计中发现的代码侧问题（本任务只记录，未改代码）

1. `ChatToolService.kt`（runtime-core/.../inference/remote/tool/，约 :343）`switch_face_engine` @Tool 描述写 "mlkit"，与 `FaceDetectionEngineMode` 枚举（MNN/MEDIAPIPE/CUSTOM）不一致。
2. `PrivacyGuard.isRemoteAllowed()` 仍存在但全仓无调用方（ADR-008 已标注）。
3. `MemoryManager.buildContextMessages`（:218）当前无调用方，写回链路暂无消费者（ADR-012 已标注）。
4. `KeywordSpotterEngine.kt` KDoc 写 KWS 模型 ~14MB，`llm_models.json` 实际 ~5MB（文档采用 5MB）。
5. app 层 `UserPreferences.AiAgentMode.LOCAL` 为遗留枚举值（端侧文本 LLM 已移除），设置链路仍在引用，建议后续清理。

## 四、已知残留与边界说明

- `scripts/check_doc_sync.py` 仍报 ~111 项「断裂链接」：绝大部分为 skills/.claude/commands 中**根相对路径书写惯例**（如 `docs/01-PRODUCT/FEATURES.md`，检查器按文件相对解析误报，文件实际存在）+ `.claude/worktrees/` 残留副本 + docs-site（本次排除）+ skills/TEMPLATE.md 占位符。真正指向不存在文件的链接已全部修复。
- `docs/superpowers/`（plans/specs 过程归档）与 `docs/reviews/` 历史报告按惯例不改。
- `ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md` 中 MobileCLIP-S0 为调研候选评估，非现状描述，保留。
- **并发提示**：本任务执行期间检测到另一会话在改动 docs/agents 相关文件（commit c04ac950「新增虚拟产品技术团队」及 skills/.claude/commands 若干文件、新建 ARCHITECTURE.md/tasks.md）。本报告仅覆盖本任务清单内文件的改动；提交时建议只 add 本任务相关文件。
