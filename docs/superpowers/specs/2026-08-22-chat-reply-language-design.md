# Chat 回复语言跟随 App 界面语言（双端）

- 日期：2026-08-22
- 状态：**已确认（用户批准设计），待实现**
- 适用：Android + iOS 双端同步落地（符合 [PARITY] 红线）
- 红线关联：[I18N]（三语体验一致性）、[PARITY]（双端行为一致）

---

## 0. 背景与目标

**问题**：用户使用英语提问时，chat 回复内容仍多为中文。

**根因**（经代码勘查确认）：

1. Chat system prompt 全文为中文（角色定位、工具规则、全部示例），但**没有任何语言指令**——既未要求「用中文回答」，也未要求「跟随某语言」，LLM 在中文 prompt + 中文工具描述环境下自然跟随 prompt 语言输出中文。
   - Android prompt 构造：`shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/RemoteChatEngine.kt:57-134` `buildChatSystemPrompt(toolDescriptors)`
   - iOS prompt 构造：`shared/src/iosMain/kotlin/com/mamba/picme/agent/core/inference/remote/IosChatPrompt.kt:22-46`（精简版，同样全中文无语言指令）
2. 整条链路（Android `ChatViewModel` → `AgentContext` → `RemoteChatEngine`；iOS `ChatAgentBridge`；服务端 `LlmProxy` 纯透传）**均不携带任何语言信息**。`AgentContext`（`shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/AgentModels.kt:24-41`）无 locale/language 字段。
3. App 已有三语设置 `AppLanguage { SYSTEM, ENGLISH, CHINESE, TRADITIONAL_CHINESE }`（`shared/src/commonMain/kotlin/com/mamba/picme/domain/model/UserPreferences.kt:21-23`），持久化于 DataStore（`UserPreferencesRepository.appLanguageFlow`），但 **chat 远程推理链路完全没有消费它**。

**目标**：Chat 的自然语言回复始终跟随 **App 界面语言**设置，双端行为一致。

### 已确认的决策（用户逐项裁决）

| # | 问题 | 裁决 |
|---|---|---|
| Q1 | 回复语言跟随什么信号？ | **跟随 App 界面语言设置**（非跟随用户当次输入语言） |
| Q2 | 改造范围？ | **仅回复语言**；工具 observation（`ChatToolService` 中文返回文案）保持不动（属内部信号，改动面大、收益低） |
| Q3 | 双端是否同步？ | **Android + iOS 同步落地** |
| Q4 | 信号传递方案？ | **方案 A：`AgentContext` 显式携带 `replyLanguage` 字段**（非组合根闭包注入、非服务端注入） |

---

## 1. 方案对比（信号传递路径，已裁决）

| 方案 | 说明 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. AgentContext 显式字段** | `AgentContext` 加 `replyLanguage`，双端沿现有数据链路传递，prompt 组装点消费 | 符合 Agent First「显式优于隐式」；未来 camera 等场景可复用；golden test 可锁定三语行为 | 改动面稍大（commonMain 模型 + Android ViewModel + iOS bridge + Swift 调用点） | ✅ **采纳** |
| B. 组合根闭包注入 | 不动 commonMain 接口；Android 在 `AndroidAgentComposition` 覆盖默认 `chatPromptBuilder`，闭包内读 DataStore；iOS 组合根闭包捕获 | 改动文件最少 | 信号隐式，双端各实现一份语言解析逻辑易漂移；违背显式原则 | ❌ |
| C. 服务端网关注入 | 客户端 HTTP header 传语言，server 在 messages 前注入 system 消息 | 客户端零改动 | server 目前纯透传设计（`LlmProxy.forward` 逐键透传），引入消息解构破坏收口；直连第三方 LLM 时失效 | ❌ |

---

## 2. 设计

### 2.1 语言模型（commonMain）

新增 `ReplyLanguage` 枚举（与 `AppLanguage` 分离——`AppLanguage.SYSTEM` 不是具体回复语言，必须解析后使用）：

```kotlin
// shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/ （与 AgentModels 同包）
enum class ReplyLanguage { SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ENGLISH }
```

提供解析函数（commonMain 纯函数，可单测）：

```kotlin
fun AppLanguage.toReplyLanguage(systemLocaleTag: String): ReplyLanguage
```

解析规则：

| AppLanguage | 结果 |
|---|---|
| `ENGLISH` | `ENGLISH` |
| `CHINESE` | `SIMPLIFIED_CHINESE` |
| `TRADITIONAL_CHINESE` | `TRADITIONAL_CHINESE` |
| `SYSTEM` | 按 `systemLocaleTag` 解析：`zh-Hans` / `zh-CN` / `zh-SG` 系 → `SIMPLIFIED_CHINESE`；`zh-Hant` / `zh-TW` / `zh-HK` / `zh-MO` 系 → `TRADITIONAL_CHINESE`；其余（含日/韩/法等非中英语言）→ `ENGLISH` |

### 2.2 AgentContext 扩展

`AgentModels.kt` 的 `AgentContext` 增加字段：

```kotlin
val replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE
```

默认值取 `SIMPLIFIED_CHINESE` 是为**保持现状兼容**：camera（`KoogReActAgent`）、飞书等现有不传该字段的调用方行为不变。

### 2.3 数据流

**Android**：

- `ChatViewModel.sendMessage`（`androidApp/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt:1138` 起）在构造 `AgentContext`（:1209-1216）时，经 `UserPreferencesRepository` 读取 `AppLanguage` + 系统 Locale（`Locale.getDefault().toLanguageTag()`）解析为 `ReplyLanguage` 填入。
- **每次发消息时解析**（非会话级缓存）：用户在设置中切换语言后，下一条消息即生效，无需重建会话。

**iOS**：

- `ChatAgentBridge.sendMessage`（`shared/src/iosMain/kotlin/com/mamba/picme/agent/core/inference/remote/ChatAgentBridge.kt:60-65`）增加 `replyLanguage` 参数（枚举经 KMP 暴露给 Swift；若互操作有摩擦则降级为 String 参数，按 `kmp-ios-interop` skill 约定取舍）。
- Swift 侧 `iosApp/PoLang/Features/Chat/ChatViewModel.swift:206` 调用点传入：读取应用语言设置；若 iOS 当前无应用内语言选择（跟随系统），则取 `Locale.preferredLanguages.first` 解析（复用 commonMain 的 `toReplyLanguage`）。

**消费点**：

- Android：`RemoteChatEngine.streamChat` 组装 prompt 时从 `AgentContext` 读取，`buildChatSystemPrompt(toolDescriptors, replyLanguage)` 追加语言规则段（§2.4）。
- iOS：`IosChatPrompt.build` 同步追加同构规则段。

**服务端**：零改动（`LlmProxy` 保持纯透传）。

### 2.4 Prompt 语言规则段（核心）

在现有 prompt 末尾（**日期行之后**，利用近因效应，`RemoteChatEngine.kt:292` 拼接点）追加一段规则。**规则文本本身用目标语言书写**（自我强化），并显式对抗全中文 prompt 与中文工具输出的引力：

- `ENGLISH`：

  > The app's UI language is English. Always reply to the user in English, regardless of the language of this prompt, tool descriptions, or tool outputs. Tool results may be in Chinese — summarize and present them in English.

- `SIMPLIFIED_CHINESE`：

  > App 界面语言为简体中文。请始终用简体中文回复用户，无论本提示词、工具描述或工具返回内容使用何种语言。

- `TRADITIONAL_CHINESE`：

  > App 介面語言為繁體中文。請始終用繁體中文回覆使用者，無論本提示詞、工具描述或工具回傳內容使用何種語言。

实现上按 `replyLanguage` 从三语常量中取对应段落拼接（常量集中在 commonMain，双端共用同一份文本，避免双端漂移）。

### 2.5 边界情况

| 场景 | 行为 |
|---|---|
| `SYSTEM` + 系统语言非中英（如日语） | 回退 `ENGLISH` |
| 简体/繁体区分 | 依 locale tag 的 script/region（`Hans`/`CN`/`SG` → 简；`Hant`/`TW`/`HK`/`MO` → 繁） |
| 工具 observation 仍为中文 | prompt 规则显式要求用界面语言转述，主流模型可稳定做到 |
| 记忆快照（【关于用户】）含中文 | 不影响，回复语言规则优先级在后（近因效应） |
| camera / 飞书等未传 `replyLanguage` 的调用方 | 默认 `SIMPLIFIED_CHINESE`，行为与现状完全一致 |

### 2.6 测试与验收

- **Golden test**：更新 `shared/src/jvmTest/.../ChatSystemPromptGoldenTest.kt`，参数化三语 golden，锁定语言规则段的内容与位置（防回归）。
- **单测**：`toReplyLanguage` 覆盖全部 `AppLanguage` 分支 + `SYSTEM` 下 `zh-Hans`/`zh-Hant`/`ja`/`en` 等 locale tag。
- **实机验收**：
  1. 界面语言设为 English，发送英文消息 → 回复全英文；
  2. 切换为繁体中文，**不重启会话**发下一条消息 → 回复繁体中文；
  3. iOS 同流程复验（双端一致）。

---

## 3. 明确不做（YAGNI）

- 工具 observation / `@LLMDescription` 多语言化（内部信号，改动面大收益低）
- System prompt 全文翻译
- 服务端（`server/`）任何改动
- camera（`KoogReActAgent`）/ 飞书链路的语言跟随（本次仅 chat；`replyLanguage` 默认值保证其不受影响，后续如需复用该字段即可）
- 用户输入语言自动检测（用户已裁决跟随界面语言，非输入语言）

---

## 4. 改动清单（实现索引）

| 端 | 文件 | 改动 |
|---|---|---|
| commonMain | `agent/core/model/context/AgentModels.kt` | `AgentContext` 加 `replyLanguage` 字段（默认 `SIMPLIFIED_CHINESE`） |
| commonMain | 新增（同包）`ReplyLanguage.kt` | 枚举 + `AppLanguage.toReplyLanguage(systemLocaleTag)` + 三语规则段常量 |
| commonMain | `agent/core/inference/remote/RemoteChatEngine.kt` | `buildChatSystemPrompt` 加 `replyLanguage` 参数，末尾追加规则段；`streamChat` 从 `AgentContext` 读取传入 |
| iosMain | `agent/core/inference/remote/IosChatPrompt.kt` | `build` 加 `replyLanguage` 参数，追加同构规则段 |
| iosMain | `agent/core/inference/remote/ChatAgentBridge.kt` | `sendMessage` 加 `replyLanguage` 参数 |
| iosMain | `IosAgentComposition.kt` | 组合根接线调整 |
| Android | `features/chat/ChatViewModel.kt` | `sendMessage` 读 `AppLanguage` + 系统 Locale 解析填入 `AgentContext` |
| iOS | `iosApp/PoLang/Features/Chat/ChatViewModel.swift` | 调用点传语言 |
| 测试 | `shared/src/jvmTest/.../ChatSystemPromptGoldenTest.kt` | 三语参数化 golden |
| 测试 | 新增 `toReplyLanguage` 单测 | 全分支覆盖 |

---

## 5. 执行注记（2026-08-22 会师后定稿）

实现分两条线先后落地，最终机制以本注记为准（§2/§4 中「`buildChatSystemPrompt` 加参数」「三语参数化 golden」「`IosChatPrompt.build` 追加规则段」的原始表述已被以下实际机制取代）：

1. **ReplyLanguage 模型与引擎级注入点由「助手性格」线（2026-08-22-assistant-persona-design.md）先行落地**：`RemoteChatEngine.buildPromptSuffix(persona, replyLanguage, today)` 是 chat system prompt 动态尾段的唯一注入点（`ChatAgentCache` 以 config/persona/replyLanguage 为重建键，语言切换经缓存键失效自动重建 agent，无需热更）。`ChatSystemPromptGoldenTest` 保持逐字节不动（锁 `buildChatSystemPrompt` 基座，suffix 在其外），**未做三语参数化 golden**；suffix 行为由 commonTest `PromptSuffixTest` 逐字断言锁定。
2. **语言规则段（本 spec §2.4）最终并入 `buildPromptSuffix` 最末尾**（日期行 → persona 段 → 语言规则段），`replyLanguageRuleSegment` 三语常量集中于 `ReplyLanguage.kt`（internal，双端共用防漂移）；DEFAULT 性格同样注入语言规则段（语言规则与性格无关）。
3. **locale 解析合体**：严格前缀边界（`zh`/`zh-`/`yue`/`yue-`，`zhcn` 等畸形 tag → ENGLISH）+ `_`→`-` 归一（iOS `zh_Hant_TW` 形态）+ lowercase；`yue`（粤语）按 zh 同规则解析（iOS 粤语系统语言下 UI 回退繁中，chat 须一致）。`ReplyLanguageTest` 为两侧断言并集。
4. **iOS 无需改 `IosChatPrompt`**：`ChatAgentBridge.sendMessage` 走与 Android 相同的 `RemoteChatEngine.streamChat`，规则段经共享引擎自动注入；iOS 侧仅需 Swift `currentReplyLanguage()` 解析（含 yue/下划线，与 Kotlin 侧同规则）。
5. **附带修复（用户实测发现）**：chat 状态文案（「正在调用工具…」等 85 处）切语言后仍中文——根因 `MainActivity` 只覆盖 Activity context locale，`applicationContext.getString` 始终按系统 locale 取词。修复为 `ChatViewModel.stringContext()` helper（按 `getAppLanguageBlocking()` 构造 Configuration override context，SYSTEM 档直返 applicationContext，非 SYSTEM 档缓存）+ 全量替换；`ChatViewModelStringContextTest` 钉住，测试基座 stub `getAppLanguageBlocking` 返回 SYSTEM（mockk relaxed 枚举返回随机值，必须 stub）。
6. **验收记录**：Android 真机验收通过（英文界面 → 英文状态文案 + 英文回复；热切繁体不重启会话 → 繁体回复；简中请求确认携带 SIMPLIFIED_CHINESE 规则段）。iOS 真机验收待补。
