# 设置页开发者选项收口（Android pace-setter）

- 日期：2026-08-12
- 状态：**Design — 自主推进稿（用户离线，已标注所有假设待 review）**
- 适用：Android 先行（pace-setter）→ iOS 经 `/ios-follow` 对等跟随
- 架构契约：ADR-013（§2.1 UI 各端原生、§2.6 不共享代码共享设计规范 SSOT、§2.7 Android 为 pace-setter）

---

## 0. 背景与目标

用户目标：**将所有开发/测试性质的选项与功能收口到「开发者选项」，避免泄漏到用户的功能设置中**。先 Android，再 iOS 跟进。

现状两个问题：
1. **入口泄漏**：设置主页网格里的「开发者选项」卡片对所有用户常驻可见。
2. **内容泄漏**：若干明显属于开发/调参/原始配置的项，散落在用户可见的分类页（AI 助手 / 相册功能 / 相机与美颜）里。

---

## 1. 自主推进期间已替代的澄清问答（用户回来请逐条核对）

> 用户离线（约 2h），无法逐题问答。以下是我替用户做的默认裁决，**每条都标注了依据与可逆性**。不认同者改一行即可回退。

| # | 问题 | 我的默认裁决 | 依据 / 备注 |
|---|---|---|---|
| Q1 | 「开发者选项」入口对普通用户是否应完全不可见？ | **是**，采用 Android 标准的「版本号连点 7 次」解锁 | 直接服务「避免泄漏」目标；最规范、零泄漏 |
| Q2 | 解锁状态是否持久化（跨重启保留）？ | **是**，新增 DataStore `developer_options_unlocked` | 对标 Android 系统；解锁后重启仍可见 |
| Q3 | 远程模型配置（API key / baseUrl / protocol / 自定义供应商）算开发项还是用户项？ | **移入开发者选项**（开发/高级） | 普通用户用服务端默认模型（deepseek-v4-flash）或预置供应商；编辑原始 baseUrl/protocol/自定义 key 是高级/开发行为。**最可争议项，已重点标注** |
| Q4 | 人脸检测阶段配置（ROI/Landmark 模型类型 MediaPipe/MNN + 推理设备 CPU/GPU）算开发项？ | **移入开发者选项** | 默认 MediaPipe 自动选择已满足用户；选模型/选设备是调参 |
| Q5 | 自适应人脸检测间隔 + 档位（保守/均衡/激进）算开发项？ | **移入开发者选项** | 性能调参，普通用户不感知 |
| Q6 | 「人脸关键点模式」开关算开发项？ | **移入开发者选项** | 美颜渲染的调参开关，偏调试 |
| Q7 | 相册打标模型选择（Florence-2 / Qwen3-VL / 自动）算开发项？ | **移入开发者选项** | 模型 ID 是开发术语；默认 AUTO 已满足用户 |
| Q8 | 「自动执行计划」（Agent 自动执行 tool_calls）算开发项？ | **保留在 AI 助手（用户可见）** | 这是 Agent 行为/安全偏好，用户合理需要；**保留，仅标注** |
| Q9 | AI Agent 模式选择（目前仅 REMOTE 单选）？ | **保留**，但标注为待清理冗余 | 单选项已无意义（OFF 已从 UI 移除），属另一轮清理，不在本次范围 |
| Q10 | TAG 生成 OpenCL/GPU 加速算开发项？ | **保留在相册功能（用户可见）** | 代码注释明确「真实性能配置（非调试项）」；是合理性能开关 |
| Q11 | 「模型中心」入口算开发项？ | **保留用户可见** | 用户下载端侧模型以启用功能的正常入口，非开发项 |
| Q12 | 是否提供「重新隐藏开发者选项」？ | **本期不做（YAGNI）** | 解锁即可；重隐藏留待需要时再加 |
| Q13 | 连点解锁的阈值与提示？ | **7 次**，过程中 Toast 倒计数「再点击 N 次进入开发者选项」 | 对标 Android 系统 |

---

## 2. 方案对比（解锁机制）

| 方案 | 说明 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. 版本号连点解锁** | 设置主页底部加版本页脚，连点 7 次解锁并持久化 | 规范、零泄漏、用户无感；对标 Android 系统 | 需新增页脚 + 解锁状态 | ✅ **推荐** |
| B. 仅 Debug 构建可见 | `BuildConfig.DEBUG` 才显示开发者入口 | 实现最简 | release 自测/诊断不可用；与「运行时开发者选项」语义不符；当前 release 本就展示诊断/日志配置 | ❌ |
| C. 常驻但下沉 | 入口仍在，移到底部/「更多」 | 改动小 | 不满足「避免泄漏」 | ❌ |

**裁决：采用 A。**

---

## 3. 设计

### 3.1 入口解锁机制（核心）

- 设置主页（`SettingsMainMenu`）底部新增 `SettingsVersionFooter`：小字 `破浪相册 v{BuildConfig.VERSION_NAME}`。
- 页脚 `clickable`：累计点击计数；每次点击若尚未解锁，显示 Toast 倒计数（「再点击 N 次进入开发者选项」，N = 7 - count）。
- 计数满足 7 → 调用 `viewModel.setDeveloperOptionsUnlocked(true)`（持久化 DataStore）；显示「已开启开发者选项」。
- 计数超时窗口：最后一次点击后 ~4 秒无点击则计数归零（防误触累积）。
- `SettingsCategoryGrid` 的「开发者选项」卡片仅在 `developerOptionsUnlocked == true` 时加入网格。

> 版本页脚本身是用户可见的良性信息（版本号），不构成「开发项泄漏」。

### 3.2 数据层（androidApp，不触碰 commonMain）

`UserSettingsRepository`（接口，`domain/repository/`）新增：
- `val developerOptionsUnlockedFlow: Flow<Boolean>`
- `suspend fun updateDeveloperOptionsUnlocked(enabled: Boolean)`

`UserPreferencesRepository`（实现，`data/preferences/`）新增：
- `PreferencesKeys.DEVELOPER_OPTIONS_UNLOCKED = booleanPreferencesKey("developer_options_unlocked")`，默认 `false`
- 对应 flow + update（照搬 `DEBUG_UI_ENABLED` 写法）

> 契约合规：纯 androidApp 平台实现，无 `:shared/commonMain` 改动，ADR-013 §2.1 纯度不受影响。

### 3.3 ViewModel

`SettingsViewModel` 新增：
- `val developerOptionsUnlocked: StateFlow<Boolean>`（stateIn，初值 false）
- `fun setDeveloperOptionsUnlocked(enabled: Boolean)`

### 3.4 UI 迁移（在 `SettingsContent` 的 `when(category)` 块之间搬迁渲染，状态/回调签名不变）

**移入 DEVELOPER 分类（新增分组）：**

| 原位置 | 迁移项 | DEVELOPER 内新分组 |
|---|---|---|
| CAMERA_BEAUTY | `StageConfigSection(ROI)` | 「人脸检测引擎」组 |
| CAMERA_BEAUTY | `StageConfigSection(LANDMARK)` | 「人脸检测引擎」组 |
| CAMERA_BEAUTY | 人脸关键点模式 + 自适应间隔 + 档位 | 「人脸检测引擎」组（合并现有「高级」语义） |
| AI_AGENT | `AiAgentRemoteModelsSection` | 「AI 推理链路（高级）」组 |
| GALLERY | 打标模型选择（Florence-2/Qwen3-VL） | 「相册打标（高级）」组 |

**保留原位（用户可见）：**
- AI 助手：自动执行计划、Agent 模式（单选，待清理）、语音控制（模式 + ASR/KWS 模型选择）
- 相册功能：标签管理 / 标签查看 / 去重 / TAG 生成 OpenCL 加速
- 相机与美颜：相机页语音入口开关（迁出检测引擎配置后，该页仅剩此项——可接受）
- 系统与权限：悬浮聊天气泡、电池优化、MIUI 自启/权限

> 相机与美颜页迁出后内容变薄（仅语音入口）。这是可接受的代价——优于让开发项继续泄漏。若后续该页过空，可在另一轮产品迭代中合并到 AI 助手或调整信息架构，**不在本次范围**。

### 3.5 DEVELOPER 分类重排后的结构

```
开发者选项
├── 调试浮层（已有）：debug_ui 总开关 / 相机信息 / 人脸调试 / 日志浮层 / shader debug
├── 人脸检测引擎（新增，迁入）：ROI 阶段配置 / Landmark 阶段配置 / 关键点模式 / 自适应间隔+档位
├── AI 推理链路·高级（新增，迁入）：远程模型配置（API key/baseUrl/protocol/供应商）
├── 相册打标·高级（新增，迁入）：打标模型选择（Florence-2/Qwen3-VL/自动）
├── 诊断（已有）：LLM 调用日志
├── 测试工具与服务（已有，DEBUG 构建）：调试图片下载 / 搜索测试 / JSBridge / 无障碍服务
└── 日志配置（已有）：按模块日志开关
```

### 3.6 i18n（硬规则：三语同步）

新增字符串（`values/` + `values-zh-rCN/` + `values-zh-rTW/`）：
- `dev_options_unlock_countdown`：「再点击 %1$d 次进入开发者选项」/ "Tap %1$d more times to enable developer options" / 繁体
- `dev_options_unlocked_toast`：「已开启开发者选项」/ "Developer options enabled" / 繁体
- 复用既有：`developer_options` / `developer_options_desc` / `stage_roi_title` / `stage_landmark_title` / `face_detection_advanced` 等

版本页脚文本：`破浪相册 v%s`——应用名为品牌词不翻译；版本号来自 `BuildConfig.VERSION_NAME`。是否抽字符串资源：**抽取**（`app_version_footer`），三语统一为「应用名 v版本」格式。

### 3.7 不做（YAGNI / 超范围）

- 不清理 `SettingsCategory.PERSONALIZATION`（已死的枚举项）——另一轮清理。
- 不动 AI Agent 模式单选冗余——另一轮清理。
- 不做「重新隐藏开发者选项」。
- 不改任何迁移项的底层状态/Repository key——只搬 UI 渲染位置。
- 不触碰 `:shared/commonMain`（契约纯度）。

---

## 4. 验收标准（可机器/人工判定）

1. 全新安装（`developer_options_unlocked=false`）：设置主页网格**无**「开发者选项」卡片；底部显示版本页脚。
2. 连点版本页脚 7 次：出现倒计数 Toast；第 7 次显示「已开启开发者选项」；网格出现「开发者选项」卡片。
3. 杀进程重启：开发者选项卡片仍在（持久化生效）。
4. 进入开发者选项：可见调试浮层 / 人脸检测引擎（ROI+Landmark+关键点+自适应间隔） / AI 推理链路·高级（远程模型） / 相册打标·高级（打标模型） / 诊断 / 测试工具(DEBUG) / 日志配置。
5. AI 助手页：**不再**出现远程模型 API key/baseUrl 配置；仍保留自动执行计划、语音控制。
6. 相机与美颜页：**不再**出现 ROI/Landmark 阶段配置与自适应间隔；仍保留语音入口。
7. 相册功能页：**不再**出现打标模型选择；仍保留标签管理/查看/去重/OpenCL 加速。
8. 三语 strings.xml 同步，`./gradlew ktlintCheck detekt` 通过，`:androidApp:assembleDebug` 编译通过。
9. 迁移项的功能本身不受影响（相机美颜仍按 StageConfig 渲染、远程推理仍按所选模型路由——仅配置入口位置变了）。

---

## 5. 架构契约（ADR-013）合规

- §2.1：本次仅改 `androidApp` Compose UI + androidApp 内 DataStore；**零** `:shared/commonMain` 改动，纯度不受影响。
- §2.6：**本文档即双端 UI 设计规范 SSOT**。iOS 侧经 `/ios-follow` 对等跟随：版本页脚连点解锁 + 同样的分类收口。`screenshot-diff.py`/`swiftui-expert`/`ios-i18n-validator` 做 parity 校验（链路已存在）。
- §2.7：Android 为 pace-setter，属用户域；iOS 跟进属 AI 域。

---

## 6. iOS 跟进要点（给 `/ios-follow`）

1. iOS 设置主页底部加版本页脚，同样连点 7 次解锁，持久化到 UserDefaults（key 例 `developer_options_unlocked`）。
2. 解锁前主页不显示「开发者选项」入口。
3. 同样把以下迁入开发者选项：远程模型原始配置、人脸检测阶段配置、自适应检测间隔、打标模型选择。
4. 解锁倒计数用 iOS 本地化文案；三语（en/zh-Hans/zh-Hant）xcstrings 同步。
5. 解锁阈值、超时窗口（4s）、Toast 文案与 Android 对齐（行为 parity）。

---

## 7. 风险与回退

- **最大风险**：Q3（远程模型配置）被用户判定应留在用户侧。回退成本：把 `AiAgentRemoteModelsSection` 渲染块从 DEVELOPER 挪回 AI_AGENT（一处）。
- 其余迁移项回退同理（搬渲染块，零状态变更）。
- 解锁机制若不认可：移除页脚点击 + 网格条件，恢复常驻（git revert 该 commit）。

---

## 8. v2 信息架构调整（2026-08-12 用户真机反馈后的迭代）

用户装机真机测试后给 5 条反馈 + 3 条澄清，将「功能设置」上提为一级入口（更扁平），而非全部塞进开发者选项。**本节覆盖 §1 的 Q3/Q8 默认裁决**。

### 8.1 最终主页一级入口（网格卡片）

AI记忆 / 人物 / 通信通道 / 相册打标(GALLERY) / **远程模型** / **本地模型** / **语音控制** / 备份恢复 / 数据隐私 / [开发者选项·解锁后]

### 8.2 三个新一级入口页内容

| 一级入口 | 内容 | 来源 |
|---|---|---|
| **远程模型** (REMOTE_MODEL) | 远程模型配置(AiAgentRemoteModelsSection: API key/baseUrl/protocol/供应商) + 自动执行多步骤计划 + 推理模式(AiAgentMode=REMOTE) | 原 AI助手 + v1 曾误收口到开发者选项(已纠正回用户侧) |
| **本地模型** (LOCAL_MODEL) | 模型中心入口(下载管理) + 本地语音识别模型(ASR) + 本地唤醒词模型(KWS) | 模型中心卡片并入 + AI助手语音区的 ASR/KWS 选择 |
| **语音控制** (VOICE_CONTROL) | 语音交互模式(VoiceCommandMode) + 语音控制入口(相机页语音FAB) | AI助手语音模式 + 相机与美颜的 voiceEntry |

### 8.3 取消的分类/卡片

- **AI 设置**二级页：内容拆入远程模型(自动执行/Agent模式)/本地模型(ASR-KWS)/语音控制(模式)。枚举 `AI_AGENT` 删除。
- **模型中心**卡片：并入「本地模型」页（作为其下载入口）。
- **相机与美颜**卡片：人脸检测引擎配置已在开发者选项、语音入口移入语音控制 → 该卡片空，连带取消。枚举 `CAMERA_BEAUTY` 删除。`PERSONALIZATION`(死枚举) 顺带清理。

### 8.4 开发者选项最终结构（远程模型移出后，重排）

```
开发者选项
├── 调试浮层：debug_ui / 相机信息 / 人脸调试 / 日志浮层 / shader debug
├── 人脸检测引擎：ROI/Landmark 阶段配置 + 关键点模式 + 自适应间隔+档位
├── 相册打标·高级：打标模型选择(Florence-2/Qwen3-VL)
├── 诊断：LLM 调用日志
├── 测试工具(DEBUG构建)：调试图片下载 / 搜索测试 / JSBridge / 无障碍服务
└── 日志配置：按模块日志开关
```

### 8.5 对 v1 裁决的修正

- **Q3（远程模型配置）**：v1 移入开发者选项 → **v2 纠正：上提为「远程模型」一级入口（用户侧）**。这是真正的功能配置，用户需要。
- **Q8（自动执行计划）/ Agent 模式**：v1 留 AI助手 → v2 并入「远程模型」一级入口页。
- 解锁机制（§3.1 版本连点 7 次）**不变**，仍用于隐藏「开发者选项」入口。

### 8.6 实现提交

`0da4dea8 feat(settings): 信息架构重组—功能设置上提为一级入口`（枚举增删 + 网格重组 + 三新分类页 + 开发者选项重排 + 三语）。

