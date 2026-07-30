# AI 设置二级页重组设计

日期：2026-07-30
状态：待评审
范围：`app` 模块 UI / 设置页

## 1. 背景与动机

设置主菜单（`SettingsCategoryGrid`）已存在「模型中心」一级卡片入口。而「AI 助手」二级页（`SettingsCategory.AI_AGENT`）的头部第一项又是「模型中心」行——二者重复，二级页这一项多余。

同时，本地推理链路（MNN-LLM/Qwen）与远程推理链路（OpenAI 兼容 API）经 ADR-005 已正式分离，但二级页仍以「REMOTE/LOCAL 互斥切换」组织：选中一侧就藏起另一侧的配置。这与「链路分离」的现实不符，用户无法同时看到/调整两条链路。

本次重组：把该模块改名为「AI 设置」，删除冗余的头部模型中心入口，并按「本地链路 / 远程链路」两个维度重新组织页面。

## 2. 目标与非目标

### 目标
1. 「AI 助手」→「AI 设置」改名（用户可见文案，三语）。
2. 删除二级页头部冗余的「模型中心」行。
3. 按「默认链路 / 远程链路 / 本地链路 / 语音控制」重组页面，本地与远程配置区**常驻可见**，不再互斥隐藏。
4. 修复「自动执行计划」开关：当前为页面内本地 state（退出即丢、且不传给 chat，是死开关），接 ViewModel 持久化并真正生效。
5. 顺带提取 `SettingsAiAgent.kt` 中 4 处硬编码中文为字符串资源（I18N 硬规则）。

### 非目标
- 不改 `SettingsCategory.AI_AGENT` 枚举名（避免大面积引用变更）。
- 不动 runtime 路由逻辑：推理偏好（`AiAgentInferencePreference`）与「默认链路单选」的轻微语义重叠（如 LOCAL + FORCE_REMOTE）本次仅做视觉归属，不消除。
- 不实现 `autoExecutePlans` 的实际生效（详见 3.4）：本次仅持久化该开关值，不在 chat/Agent 层消费它。
- 不改本地/远程链路的底层配置数据结构（`RemoteModelConfigs`、本地模型选择逻辑等不变）。
- 不调整「语音控制」区内部内容，仅调整其分区归属/位置。

## 3. 改动详述

### 3.1 改名（用户可见文案）

字符串 key `ai_assistant` 与 `ai_assistant_desc`，三语（实际存在的 `values*` 目录全部同步：`values` / `values-zh` / `values-zh-rCN` / `values-zh-rTW`）：

| key | values（EN） | values-zh / -rCN（中） | values-zh-rTW（繁） |
|---|---|---|---|
| `ai_assistant` | AI Assistant → **AI Settings** | AI 助手 → **AI 设置** | AI 助理 → **AI 設定** |
| `ai_assistant_desc` | Agent mode, models, voice control and remote channel → **On-device & remote inference, voice control** | Agent 模式、模型、语音控制与远程通道 → **本地与远程推理链路、语音控制** | 同步对应繁体 |

- 一级卡片图标 `Icons.Rounded.SmartToy` 保留。
- `SettingsCategory.AI_AGENT` 枚举值与 `titleRes` 映射不变；仅更新源码注释「AI 助手」→「AI 设置」。

### 3.2 删除冗余模型中心入口

删除 `SettingsScreen.kt` 中 AI_AGENT 区块头部第一项（当前 `SettingsScreen.kt:462-473`，含 `SettingsClickableRow(模型中心)` 及其后的 `HorizontalDivider`）。

**保留** `AiAgentLocalModelSection` 内部的「模型中心 ⤓」入口（`SettingsAiAgent.kt:144-172`）：本地链路下载模型需要，语义贴合。

### 3.3 模块重组（核心）

页面骨架（自上而下）：

```
默认链路   [远程] [本地]   + 自动执行计划[开]
远程链路   ✓ 当前模型 + 添加模型          ← 常驻可见
本地链路   本地模型⤓ / 推理偏好 / 推理后端 / L1 缓存  ← 常驻可见
语音控制   模式 / ASR 模型 / KWS 模型
```

关键变化（相对现状）：
- **两区常驻**：当前 `when (aiAgentMode) { LOCAL -> …; REMOTE/FEISHU -> … }` 的互斥分支被拆除，改为「远程链路」「本地链路」两个 `SettingsSection` 均无条件渲染。顶部「默认链路」单选（`AiAgentModeSelection`，REMOTE/LOCAL chips）保留，仅决定 chat 实际路由。
- **推理偏好 / 推理后端**：当前仅 `if (aiAgentMode == LOCAL)` 显示，重组后归入「本地链路」区，无条件显示（它们本就是本地链路的配置）。
- **L1 缓存**：归入「本地链路」区（本地 LLM 缓存）。
- **远程模型列表**（`AiAgentRemoteModelsSection`）：归入「远程链路」区，无条件显示。
- **自动执行计划**：属全局 chat 行为，置于顶部「默认链路」区。
- **语音控制**：作为独立第四区（交互维度，非推理链路），内部内容不变。
- 「默认链路」单选的 OFF 分支提示：两区常驻后不再有互斥 `when`，原 OFF 提示文字（`ai_agent_mode_off`）随之移除。OFF 状态仅经 a11y delegate 触达（UI chips 不暴露 OFF），此时配置区仍正常可见可改。

> 远程链路置于本地链路之上（用户指定）。

### 3.4 自动执行计划持久化（仅持久化，生效留后续）

现状：`SettingsScreen.kt:475` 为 `remember { mutableStateOf(true) }`（退出即丢）。进一步核实发现 `autoExecutePlans` 是**全链路死参数**：`AiChatScreen`/`AiChatScreenContent` 仅在参数间传递（`AiChatScreen.kt:135/189/207`），函数体内从不消费；3 个调用方（`CameraPreviewContent` / `AiChatPanel` / `MediaPager`）也都不传值。因此即便持久化，开关仍不影响 chat 行为。

本次范围（仅持久化）：让设置页开关值写入 DataStore、不再「退出即丢」。

```
DataStore key (auto_execute_plans, 默认 true)
  → UserPreferencesRepository.autoExecutePlansEnabledFlow + updateAutoExecutePlansEnabled()
  → SettingsViewModel.autoExecutePlansEnabled: StateFlow<Boolean> + setAutoExecutePlansEnabled()
  → SettingsScreen 接线（读 VM、onCheckedChange 调 setter）
```

实现参照现有 `aiAgentL1CacheEnabled` 模式（`UserPreferencesRepository.kt:799-815`、`SettingsViewModel.kt:187 / 998`），但更简单（无需同步 runtime 单例）。

**已知限制（非本次范围）**：开关仍不影响 chat。真正生效需后续独立任务——在 `AiChatScreenContent`/Agent 执行层实现「收到 plan 后自动执行 vs 手动确认」的消费逻辑，并在 chat 调用方接入持久化值。本次不做。

### 3.5 I18N 修复（硬规则）

`SettingsAiAgent.kt` 4 处硬编码中文提取为字符串资源并同步三语：

| 现硬编码 | 新 key（建议） |
|---|---|
| `"当前使用"` | `ai_agent_current_model` |
| `"默认远程模型有时长限制"` | `remote_model_default_limit_title` |
| `"添加自有模型以解除限制"` | `remote_model_default_limit_desc` |
| `"自定义"`（provider 兜底名） | `provider_custom` |

## 4. 受影响文件

| 文件 | 改动 |
|---|---|
| `app/src/main/res/values*/strings.xml`（4 套） | `ai_assistant`/`ai_assistant_desc` 改名；新增 4 个 I18N key |
| `app/src/main/java/.../features/settings/SettingsScreen.kt` | 删头部模型中心行；重组 AI_AGENT 区块（顺序 + 两区常驻）；接线 `autoExecutePlans` 到 VM；更新注释 |
| `app/src/main/java/.../features/settings/SettingsAiAgent.kt` | 4 处硬编码 → `stringResource` |
| `app/src/main/java/.../features/settings/SettingsViewModel.kt` | 新增 `autoExecutePlansEnabled` StateFlow + setter |
| `app/src/main/java/.../data/preferences/UserPreferencesRepository.kt` | 新增对应 flow + updater |
| DataStore key 定义处（`UserPreferences.kt` 等） | 新增 `auto_execute_plans` key |

## 5. 验证

- 编译通过（`./gradlew :app:assembleDebug`）。
- 设置页：主菜单「AI 设置」入口文案正确；进入后无头部模型中心行；远程链路在上、本地链路在下，两区同时可见；切换「默认链路」不隐藏任一区。
- 「自动执行计划」开关：修改后退出设置再进入，值保留；并在 chat 侧生效（关闭后不自动执行 plan）。
- 切换系统语言（中/英/繁），新文案与 4 个新提取字符串均正确显示，无硬编码中文残留。
- 现有本地/远程模型配置功能（选择、添加、删除、下载入口）行为不变。
