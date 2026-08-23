# Chat 助手性格/语言风格设定（双端）

- 日期：2026-08-22
- 状态：**已确认（用户批准设计），待实现**
- 前置文档：`2026-08-22-chat-reply-language-design.md`（回复语言跟随界面语言，本 spec 复用其 `ReplyLanguage` 模型与 `AgentContext` 传递模式）
- 适用：Android + iOS 双端同步落地（符合 [PARITY] 红线）
- 红线关联：[I18N]（设置 UI 三语同步）、[PARITY]（性格段定义双端同源）

---

## 0. 背景与目标

**问题**：Chat 回复内容中性、缺乏情绪价值，用户无法定制助手的性格与语言方式。

**现状**（经代码勘查确认）：

1. Chat system prompt 硬编码于 `RemoteChatEngine.buildChatSystemPrompt`（`shared/src/commonMain/.../inference/remote/RemoteChatEngine.kt:57-134`），无任何 persona/语气定制入口；全仓库仅相机 prompt（`RemotePromptBuilder.kt:119`）有一句「语气简洁友好」。
2. iOS 使用独立精简版 prompt（`shared/src/iosMain/.../IosChatPrompt.kt:22-46`），与 Android 不共用——persona 段必须双端同源注入。
3. 设置体系已有成熟模板：`AiAgentMode` 枚举的「UI → DataStore → ViewModel → 运行时」完整链路（`UserPreferencesRepository.kt:584-602` / `SettingsViewModel.kt:217-221` / `SettingsAiAgent.kt:48-73`）。
4. 同日 spec `2026-08-22-chat-reply-language-design.md` 已确立 `ReplyLanguage` 三语枚举与 `AgentContext` 显式字段 + 请求期拼接的模式，本特性直接复用。（实现裁决 2026-08-22：persona/语言作为 chat agent 缓存 key 的一部分，变更时按需重建 agent——见 §1 末尾实现裁决注记。）

**目标**：用户在设置中选择助手性格（预设），Chat 回复的性格与语言方式随之改变，下一条消息即生效，双端行为一致。

### 已确认的决策（用户逐项裁决）

| # | 问题 | 裁决 |
|---|---|---|
| Q1 | 生效范围？ | **仅 Chat**；相机指令、打标、AI 优化等链路不动 |
| Q2 | 交互形态？ | **预设性格**（4 个预设单选），不做自由文本 custom instructions，不做多维度滑块 |
| Q3 | 注入段语言策略？ | **跟随界面语言**：性格描述段按 `ReplyLanguage` 提供 zh-CN / zh-TW / EN 三套文案 |
| Q4 | 双端同步？ | **Android + iOS 同步落地**（[PARITY]） |
| Q5 | 实现路径？ | **方案 A：枚举进 shared commonMain + `AgentContext` 显式字段 + 请求期拼接**（与回复语言 spec 同模式） |

---

## 1. 方案对比（已裁决）

| 方案 | 说明 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. 枚举进 shared + AgentContext 请求期拼接** | `AssistantPersona` 枚举与三语性格段定义在 commonMain，经 `AgentContext.persona` 逐条消息传递，prompt 组装点拼接 | 双端同源零漂移；与回复语言 spec 同模式；设置变更下条消息即生效，无需 agent 重建；符合「显式优于隐式」 | 改动面含 commonMain 模型 + 双端 ViewModel | ✅ **采纳** |
| B. 纯 App 层 prompt 前缀拼接 | persona 文本 provider 注入 `RemoteChatEngine` 构造参数 | 改动文件最少 | 性格定义双端各写一份，漂移风险大；违背 shared 引擎无关层原则 | ❌ |
| C. 服务端网关注入 | server 拼性格段进 messages | 客户端零改动 | 直连第三方 LLM 链路不经过 server，行为不一致；`LlmProxy` 纯透传设计被破坏 | ❌ |

早期讨论过的「`orchestrator.configure(persona)` + agent 重建」路径被方案 A 取代：回复语言 spec 已证明请求期拼接更简单（无重建触发条件维护），且生效时机相同。

> **实现裁决注记（2026-08-22，实现阶段定稿）**：落地时「请求期拼接」具体化为 `RemoteChatEngine` 的 `buildPromptSuffix(persona, replyLanguage, today)` 尾段拼接；由于 Koog agent 的 system prompt 在构建期定型，persona/回复语言被纳入单条目 `ChatAgentCache` 的缓存 key——**设置变更会触发该 chat agent 的按需重建**（下一条消息生效，用户可见行为与本 spec 承诺一致）。这与上文「无需 agent 重建机制」的表述差异在于：无需的是额外的「配置变更 → 主动重建」编排机制，缓存 key 失配导致的惰性重建是实现细节，不影响方案 A 的架构取舍结论。

---

## 2. 设计

### 2.1 数据模型（commonMain，双端同源）

新增 `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/config/AssistantPersona.kt`（与 `AiAgentConfig.kt` 同包）：

```kotlin
enum class AssistantPersona { DEFAULT, WARM, LIVELY, CONCISE }

/** 按 persona + 回复语言取性格描述段；DEFAULT 返回 null（不注入，行为与现状逐字节一致）。 */
fun personaPromptSegment(persona: AssistantPersona, language: ReplyLanguage): String?
```

- `ReplyLanguage` 复用回复语言 spec 的三语枚举（`SIMPLIFIED_CHINESE / TRADITIONAL_CHINESE / ENGLISH`），**不引入 AppLanguage 依赖**——若回复语言 spec 尚未落地，本特性以同一定义先行/合并实现。
- 性格段三语文本为 commonMain 常量（与现有 prompt 硬编码风格一致），双端共用同一份。

### 2.2 AgentContext 扩展

`AgentModels.kt` 的 `AgentContext` 增加字段：

```kotlin
val persona: AssistantPersona = AssistantPersona.DEFAULT
```

默认值 `DEFAULT` 保证 camera（`KoogReActAgent`）、飞书等不传该字段的调用方行为不变（同 `replyLanguage` 的兼容策略）。

### 2.3 数据流

**Android**：

- `ChatViewModel.sendMessage` 构造 `AgentContext` 时，从 `UserPreferencesRepository.assistantPersonaFlow` 读当前值填入（与 `replyLanguage` 同点，**每次发消息读取**，设置变更下一条消息即生效，无需新会话）。
- **消费点**：`RemoteChatEngine.streamChat` 组装 prompt 时从 `AgentContext` 读取 persona + `replyLanguage`，在日期行之后追加性格段（§2.4），语言规则段仍保持最后（近因效应）。

**iOS**：

- `ChatAgentBridge.sendMessage` 增加 `persona` 参数（互操作摩擦时降级 String，按 `kmp-ios-interop` 约定取舍）。
- Swift 侧 `iosApp/PoLang/Features/Chat/ChatViewModel.swift` 调用点从设置存储读取传入。
- `IosChatPrompt.build` 追加同构性格段（同一份 commonMain 常量）。

**服务端**：零改动。

### 2.4 Prompt 性格段（核心内容）

拼接位置：现有 prompt 末尾日期行（`RemoteChatEngine.kt:292` 拼接点）之后、语言规则段之前。`DEFAULT` 不追加任何内容。

**WARM（温暖贴心）**：

- zh-CN：`你的语气温暖贴心：先回应用户的情绪，共情之后再给出回答或建议；多使用肯定与鼓励的措辞，让用户感到被理解和支持。`
- zh-TW：`你的語氣溫暖貼心：先回應使用者的情緒，共情之後再給出回答或建議；多使用肯定與鼓勵的措辭，讓使用者感到被理解和支持。`
- EN：`Your tone is warm and caring: acknowledge the user's feelings first, then respond or advise; use affirming and encouraging words so the user feels understood and supported.`

**LIVELY（活泼幽默）**：

- zh-CN：`你的语气轻松活泼、幽默有趣：可适度使用 emoji 和俏皮表达，偶尔玩梗，让聊天氛围轻松愉快；但回答的实质内容必须保持准确、有用。`
- zh-TW：`你的語氣輕鬆活潑、幽默有趣：可適度使用 emoji 和俏皮表達，偶爾玩梗，讓聊天氛圍輕鬆愉快；但回答的實質內容必須保持準確、有用。`
- EN：`Your tone is lively and humorous: use emojis and playful expressions in moderation and crack the occasional joke to keep the conversation fun — while keeping the substance of your answers accurate and useful.`

**CONCISE（简洁干练）**：

- zh-CN：`你的语气简洁干练：直接给出结论与建议，省去寒暄与铺垫，优先使用结构化输出（列表/要点）。`
- zh-TW：`你的語氣簡潔幹練：直接給出結論與建議，省去寒暄與鋪陳，優先使用結構化輸出（列表/要點）。`
- EN：`Your tone is crisp and efficient: lead with conclusions and recommendations, skip pleasantries, and prefer structured output (lists and bullet points).`

### 2.5 设置链路（Android，复刻 `AiAgentMode` 模板）

- **存储**：`UserPreferencesRepository` 新增 `assistant_persona` stringPreferencesKey（默认 `DEFAULT`），`assistantPersonaFlow` + `updateAssistantPersona()`，`runCatching { valueOf }` 坏值回落 `DEFAULT`；`UserSettingsRepository` 接口同步加成员。
- **ViewModel**：`SettingsViewModel` 暴露 `assistantPersona` stateIn + `setAssistantPersona()`。
- **UI**：`SettingsAiAgent.kt` 新增「助手性格」区块，复用 `CompactOptionChips` 单选，4 个选项各带一句话描述（i18n）。
- **消费**：无 `PoLangApplication` sync——改由 `ChatViewModel.sendMessage` 逐条消息读取（§2.3）。

### 2.6 iOS 设置与 PARITY

- `@AppStorage("assistant_persona")` 存枚举 name；设置页新增同款单选区块（选项文案与 Android 一致）。
- 更新 `docs/08-UI-SPECS/screens/settings.yaml` 固化新条目；chat 行为变化在 `chat.yaml` 备注。

### 2.7 i18n

设置 UI 新增约 9 个 key × 4 文件（Android `values/`、`values-zh-rCN/`、`values-zh-rTW/` + iOS `Localizable.xcstrings`）：

| key | EN | zh-CN | zh-TW |
|---|---|---|---|
| `assistant_persona` | Assistant Personality | 助手性格 | 助手性格 |
| `assistant_persona_default` | Default | 默认 | 預設 |
| `assistant_persona_default_desc` | Balanced, neutral standard replies | 中性简洁的标准回复 | 中性簡潔的標準回覆 |
| `assistant_persona_warm` | Warm & Caring | 温暖贴心 | 溫暖貼心 |
| `assistant_persona_warm_desc` | Empathizes first, encouraging and supportive | 先共情再回答，多肯定鼓励 | 先共情再回答，多肯定鼓勵 |
| `assistant_persona_lively` | Lively & Playful | 活泼幽默 | 活潑幽默 |
| `assistant_persona_lively_desc` | Relaxed and fun, with light emoji use | 轻松俏皮，适度使用 emoji | 輕鬆俏皮，適度使用 emoji |
| `assistant_persona_concise` | Crisp & Direct | 简洁干练 | 簡潔幹練 |
| `assistant_persona_concise_desc` | Straight to conclusions, minimal pleasantries | 直给结论，少寒暄 | 直給結論，少寒暄 |

### 2.8 边界情况

| 场景 | 行为 |
|---|---|
| 存储值非法（旧版本/手改） | `runCatching` 回落 `DEFAULT`，不注入 |
| `DEFAULT` 预设 | 不追加任何段落，prompt 与现状逐字节一致 |
| 设置变更时机 | 下一条发送的消息生效（请求期读取），无需新会话/重启 |
| 相机/飞书等未传 persona 的调用方 | 默认 `DEFAULT`，行为不变 |
| 与回复语言段叠加 | 性格段在前、语言规则段在后（语言规则保持末尾近因位） |

### 2.9 测试与验收

- **Golden test**：`DEFAULT` 不改变现有 golden；为 `WARM/LIVELY/CONCISE × 三语` 增加参数化断言（或独立 golden），锁定性格段内容与位置。
- **单测**：`personaPromptSegment` 全分支（4 persona × 3 语言 + DEFAULT→null）；DataStore 坏值回落。
- **实机验收**：
  1. 选择「温暖贴心」，发送消息 → 回复明显带共情与鼓励语气；
  2. 切换「简洁干练」，**同会话**发下一条 → 回复直给结论；
  3. 界面语言切 English，性格段按英文注入，回复仍英文；
  4. iOS 同流程复验（双端一致）。

---

## 3. 明确不做（YAGNI）

- 自由文本 custom instructions（纯预设，零注入风险）
- 多维度滑块（温暖度/简洁度独立调节）
- 相机（`KoogReActAgent`）、打标、AI 一键优化等链路的性格化
- 服务端（`server/`）任何改动
- System prompt 基础段的多语言化（沿用回复语言 spec 的边界）

---

## 4. 改动清单（实现索引）

| 端 | 文件 | 改动 |
|---|---|---|
| commonMain | 新增 `agent/core/model/config/AssistantPersona.kt` | 枚举 + `personaPromptSegment(persona, language)` + 三语性格段常量 |
| commonMain | `agent/core/model/context/AgentModels.kt` | `AgentContext` 加 `persona` 字段（默认 `DEFAULT`） |
| commonMain | `agent/core/inference/remote/RemoteChatEngine.kt` | prompt 组装点读 `AgentContext.persona`，日期行后追加性格段 |
| iosMain | `agent/core/inference/remote/IosChatPrompt.kt` | 追加同构性格段 |
| iosMain | `agent/core/inference/remote/ChatAgentBridge.kt` | `sendMessage` 加 `persona` 参数 |
| Android | `data/preferences/UserPreferencesRepository.kt` | `assistant_persona` key + flow + update |
| Android | `domain/repository/UserSettingsRepository.kt` | 接口加成员 |
| Android | `features/settings/SettingsViewModel.kt` | stateIn + setter |
| Android | `features/settings/SettingsAiAgent.kt` | 「助手性格」CompactOptionChips 区块 |
| Android | `features/chat/ChatViewModel.kt` | `sendMessage` 读 persona 填入 `AgentContext` |
| Android | `res/values*/strings.xml` ×3 | 9 个 i18n key |
| iOS | `iosApp/PoLang/Features/Settings/` | 单选区块 + `@AppStorage` |
| iOS | `iosApp/PoLang/Features/Chat/ChatViewModel.swift` | 调用点传 persona |
| iOS | `iosApp/PoLang/Resources/Localizable.xcstrings` | 9 个 i18n key |
| spec | `docs/08-UI-SPECS/screens/settings.yaml` | 固化新设置条目 |
| 测试 | `shared/src/jvmTest/.../ChatSystemPromptGoldenTest.kt` + 新增单测 | 参数化性格段断言、`personaPromptSegment` 全分支 |
