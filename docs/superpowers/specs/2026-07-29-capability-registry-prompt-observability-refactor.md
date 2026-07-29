# 能力注册与提示词体系重构 Spec

> **日期**：2026-07-29
> **状态**：待评审
> **触发**：chat 输入「盘点一下我的相册」返回"盘点功能暂不支持"（证据链见 §1）
> **前置热修**：2026-07-29 已落地止血（`GlobalCapabilityHost.clear(expected)` 守卫 + 4 个 chat Capability 注册全局兜底，见 `CAPABILITY_REGISTRY.md` 变更说明）。本 Spec 是止血之后的体系性根治。

---

## 1. 背景：故障证据链

1. 远程 ReAct 正常调用 `run_gallery_script` / `get_gallery_summary`，observation 均为 `Error: 暂不支持此操作`（`polang_llm_log.db` llm_call_log id=153/154/156）。
2. 该字符串来自 `CapabilityRegistry.dispatchWithQueueSupport` 的 `METHOD_NOT_FOUND` 分支（`CapabilityRegistry.kt:209`）——`findCapabilityForCommand` 落空。
3. 同进程 `search_media` 正常：只有 `ChatSearchCapability` 在 `PoLangApplication` 注册了全局 registry；`chat_run_script` 等 4 个只经 Compose `RegisterCapability` 注册到 `ComposeCapabilityHost`。
4. **force-stop 重启进程后同一句话立刻恢复**（ExecuteScript/DrawChart 正常分发）。证明长存进程内全局宿主链接已断：Activity recreate 时旧 composition 的 `onDispose { GlobalCapabilityHost.clear() }` 晚于新宿主 `set()` 执行，把 `CapabilityHost.instance` 覆盖成空 stub，本进程内 Compose 注册的 Capability 全部不可见。
5. 排查期间 `tool_call_log` **零记录**——失败发生在 `CommandExecutor` 之前的查找阶段，未过埋点。

## 2. 问题定义

| # | 问题 | 后果 |
|---|------|------|
| P1 | **注册双轨制**：`ComposeCapabilityHost`（UI 生命周期）与 `CapabilityRegistry` 本地 registry（进程级）两个容器，同类 Capability 走不同轨道 | "搜索能用、盘点不能"的半坏状态；能力能否找到取决于用户是否触发过 recreate |
| P2 | **注册时机与分发线程无生命周期契约**：分发在后台线程，注册挂在 Compose 生命周期，靠静态可变单例桥接，set/clear 无排序保证 | 竞态必然发生，且一旦发生持续到进程结束 |
| P3 | **失败路径不可观测**：`findCapabilityForCommand` 落空、命令入队、`ChatToolService` 5s 等待超时，均不写 `tool_call_log` | 故障只能靠 LLM 请求体反推，QA/Agent 自查无能 |
| P4 | **提示词与能力表面两张皮**：`chatSystemPrompt` 手写工具清单、`@Tool` 注解、Capability `supportedCommands()`、docs 四处手工同步，无校验 | prompt 承诺的能力运行时可不存在；能力增删要改多处，漂移无告警 |

## 3. 目标 / 非目标

**目标**

- G1：任何"能力未找到 / 未执行"在 `tool_call_log` 有结构化记录，可被 QA 与 Agent 直接消费。
- G2：system prompt 的工具清单与 `@Tool` 实际表面机器校验一致，漂移在开发期 fail-fast。
- G3：能力"能否找到"与 UI 生命周期彻底解耦——单一注册表，进程级注册；UI 侧只管 delegate 绑定。

**非目标**

- 不改 Capability / scene / queue 的既有语义（`activeScenes()` / `isAvailable()` / 跨页入队行为保持不变）。
- 不改 LLM 提供商、协议、记忆体系。
- 不追求 prompt 全量程序化生成（行为规则段仍手写，见 §5 边界）。

## 4. Phase 1：失败路径可观测（P0，先行）

> **状态（2026-07-29）：已落地**——`CommandExecutor.recordDispatchEvent` 上报入口 +
> `CapabilityRegistry` 查找失败（METHOD_NOT_FOUND）/ 入队（COMMAND_QUEUED=-32006）两分支 +
> `ChatToolService` / `RemoteControlToolService` 5s 等待超时埋点；
> 单测 `CapabilityRegistryDispatchObservabilityTest`（4 用例）覆盖。
>
> 原则：重构之前先有观测，后续 Phase 的回归有日志兜底。只记纯指标，不含命令参数与业务内容（隐私红线，同 `CommandExecutionRecorder` 既有约束）。

### 4.1 改动点

1. **`CapabilityRegistry.dispatchWithQueueSupport` — `capability == null` 分支**
   记录 `success=0, errorCode=METHOD_NOT_FOUND(-32601)`，`errorMessage` 含当前 scene（不含命令参数）。
   `capability` 字段：查找失败无 Capability 名，填固定占位（如 `(unresolved)`），`commandType` 用 `AgentCommand.getMethodName(command)`。
2. **入队分支（`!sceneMatch || !isAvailable`）**
   记录 `success=0, errorCode=COMMAND_QUEUED（新增码，建议 -32006)`，`errorMessage` 为入队原因（`scene mismatch` / `delegate not bound`）。入队非失败，但"未立即执行"必须可见，否则跨页指令黑洞无法排查。
3. **`ChatToolService.dispatchCommand` 5s 等待超时（`deferred.get(5, SECONDS)` catch 路径）**
   记录 `success=0, errorCode=ERROR_CODE_TIMEOUT(-32002)`，capability 填 `(chat_tool)`。注意与被等待命令自身的执行记录区分（后者若最终完成仍由 `CommandExecutor` 正常记录，二者 traceId 相同可关联）。
4. **`RemoteControlToolService.dispatchCommand` 同路径**（飞书等通道），一并补齐。

### 4.2 实现约束

- 复用 `CommandExecutionRecorder` 接口，不新增持久化通道；上报点在 runtime-core，recorder 为 null 时零开销。
- 上报不得影响主流程（沿用 `notifyRecorder` 吞异常范式）。
- `AgentErrorCode`（`AgentModels.kt`）补 `COMMAND_QUEUED` 常量。

### 4.3 验证

- 单测：`CapabilityRegistry` 在 capability 缺失 / 入队两种场景调用 recorder 且字段正确。
- 真机：制造 METHOD_NOT_FOUND（如 debug 页派发未注册命令）→ Debug 日志查看页可见 success=0 记录。

## 5. Phase 2：提示词与能力表面一致性（P1）

> **状态（2026-07-29）：已落地**——`ToolInventory`（@Tool 元数据 → 确定性清单段，按 name 字典序 +
> 首句截断 + name 缺省回退方法名）替换 `RemoteChatEngine.chatSystemPrompt` 手写清单（prompt 移入
> companion 供测试访问）；门禁单测 `ToolInventoryTest`（清单生成/排序/截断/确定性 + prompt 全覆盖）与
> `ChatToolCapabilityCoverageTest`（每个 @Tool 有 Capability 覆盖或登记例外 + 例外表防腐 + 别名目标覆盖）。

### 5.1 方案

1. **工具清单段生成化**：`RemoteChatEngine.chatSystemPrompt` 开头「可用工具：…」段改为由 `ChatToolService` 的 `@Tool` 元数据（反射取 `name` + `value` 首行）确定性生成（按 name 字典序），替换手写清单。行为规则段（画图规则、refine 窄化规则、写通路说明等）保留手写——这是生成边界，不追求全量生成。
2. **一致性校验（单测，CI 门禁）**：
   - `ChatToolService` 全部 `@Tool` name 必须出现在最终 system prompt 文本中；
   - 每个 `@Tool` name 必须有对应 Capability 的 `supportedCommands()` 覆盖（注册表视角的完整映射）；
   - 不等则测试失败，能力增删漏改 prompt/注册在开发期爆炸。
3. **缓存稳定性**：生成段位置固定、排序确定、内容仅随版本变更——不破坏 DeepSeek prompt 前缀缓存（当前 cachedTokens ≈ 33k/36k，收益必须保住）。生成结果在 Agent 构建时计算一次并缓存，不随请求变化。

### 5.2 范围说明

- `RemoteControlToolService`（飞书等通道）与 `ChatToolService` 共享 `GalleryToolDocs`，校验同样覆盖；其 prompt 若引用了工具清单，同法生成。
- 本地小模型路径（`LocalInferencePipeline` 经 `getCapabilitiesForCurrentScene()` 构建能力描述）本就从注册表取数，Phase 3 收敛后天然一致，无需额外改动。

### 5.3 验证

- 新增单测全绿；故意删除某 `@Tool` 的 prompt 提及 → 测试变红（自证有效）。
- 真机对比生成前后 prompt 的 tools 段语义等价；远程对话主流场景（搜索/盘点/画图/编辑）无回归。

## 6. Phase 3：能力注册体系收敛（P2，核心重构）

> **状态（2026-07-29）：已落地**——`CapabilityHost`/`ComposeCapabilityHost`/`LocalCapabilityHost`/
> `GlobalCapabilityHost` 全部删除；`CapabilityRegistry` 唯一注册表（新增 `unregister`）；
> 应用级注册收口 `PoLangApplication.initializeCapabilities()`（含 SettingsCapability 补注册）；
> CameraCapability 随 CameraScreen DisposableEffect register/unregister；
> ChatScreen/CameraScreen `RegisterCapability` 与 MainActivity 根宿主移除；
> `AiAgentUseCase.registerCameraCapability`（死代码）删除；`GlobalCapabilityHostTest` 随守卫一并移除。

### 6.1 目标结构

- **`CapabilityRegistry` 为唯一注册表**：所有 Capability 进程启动期一次性注册（`PoLangApplication.initializeCapabilities()` 收口），运行期不增删。
- **注册与可用性彻底分离**：能否找到 = 注册表（静态）；能否执行 = `activeScenes()` + `isAvailable()`（动态，delegate 绑定状态）。跨页/未绑定仍走既有 `CrossPageCommandQueue`。
- **Compose 侧只绑 delegate**：`ChatScreen` / `CameraScreen` / `GalleryScreen` / `SettingsScreen` 保留 `bindDelegate`/`unbindDelegate`，删除全部 `RegisterCapability` 调用。
- **`ComposeCapabilityHost` / `GlobalCapabilityHost` / `LocalCapabilityHost` 退役**：`CapabilityRegistry.findCapabilityForCommand` / `getCapabilitiesForCurrentScene` 只查本地 registry，删除 `CapabilityHost.get()` 静态桥。`MainActivity` 的 `NavigationCapability` / `SystemCapability` 改为 Application 期注册（NavController 依赖后置注入，需评估：可保留 `MainActivity` 创建时注册到 registry，但**不再经过 Compose 生命周期**，用 `DisposableEffect` 只做 delegate 式更新）。
- 2026-07-29 热修中加入的 `clear(expected)` 守卫随静态桥一并删除（问题根除后无存在必要）；热修中 4 个 chat Capability 的全局兜底注册即为本 Phase 的正式形态，保留。

### 6.2 注册点现状清单（重构输入）

| Capability | 当前注册位置 | 收敛后 |
|------------|--------------|--------|
| Navigation / System | `MainActivity` Compose host + registry（双轨） | Application/Activity 期 registry 单轨 |
| Camera | `CameraScreen` RegisterCapability + `AiAgentUseCase.registerCameraCapability` | Application 期 registry，Screen 只绑 delegate |
| Gallery | `PoLangApplication`（全局，飞书后台搜索依赖） | 不变 |
| Settings | **未在任何 registry 注册**（仅 SettingsScreen 绑 delegate）——现状疑似死能力，需先核实再补注册 | Application 期 registry |
| Chat 系 ×5 | ChatScreen RegisterCapability；ChatSearch + 4 个已兜底进 registry | Application 期 registry，Screen 只绑 delegate |
| AiOptimize / ImageEdit / PersonRelation / Memory | `PoLangApplication` | 不变 |

### 6.3 回归风险点（必须逐项验证）

1. **跨页指令**：CHAT 场景下发 Gallery 命令 → 入队 → 切页后执行（"正在为您切换页面"路径）。
2. **飞书后台搜索**：App 在前台任意页/后台时 `GalleryCapability` 可命中（`RemoteControlToolService` 通道）。
3. **FloatingChatBubbleService**：悬浮窗场景的 `SystemCapability` 注册（`FloatingChatBubbleService.kt:173`）。
4. **本地小模型 prompt**：`LocalInferencePipeline` / `LocalCameraAgent` 的 `getCapabilitiesForCurrentScene()` 输出收敛前后一致。
5. **进程恢复**：后台被杀 → 恢复 → chat 盘点可用（本次故障的原生场景）。
6. **Activity recreate**：设置页切语言触发 recreate → chat 各能力可用。

### 6.4 验证

- 存量单测全绿；`AgentOrchestratorParseTest` 等 dispatch 用例适配单轨查找。
- 新增单测：注册表进程级注册后，无 Compose 环境直接 dispatch chat 命令 → 命中（delegate 未绑定时入队而非 METHOD_NOT_FOUND）。
- 真机按 §6.3 六项回归。

## 7. 里程碑

| Phase | 内容 | 依赖 | 可独立交付 |
|-------|------|------|-----------|
| 1 | 失败路径 tool_call_log 埋点 | 无 | ✅ 先行为后续重构提供观测兜底 |
| 2 | prompt 工具清单生成 + 一致性单测 | 无（与 1 并行亦可） | ✅ |
| 3 | 注册体系单轨收敛 | 建议 1 完成后进行 | ✅ |

## 8. 风险与回退

- **Phase 3 触碰面大**（Registry / Host / 各 Screen / MainActivity）：以 Phase 1 日志 + §6.3 回归清单兜底；任何一项回归不过即回退到热修状态（热修已保证功能正确，仅是结构冗余）。
- **prompt 生成破坏缓存前缀**：Phase 2 验收必须对比 cachedTokens 比例不显著下降。
- **queue 语义变化风险**：本 Spec 不改入队/出队逻辑本身，只改"在哪里找到 Capability"，出队执行链路（`CrossPageCommandQueue` → `CommandExecutor`）不动。

## 9. 附录：关键文件索引

- `runtime-core/.../runtime/capability/CapabilityRegistry.kt`（dispatch / findCapabilityForCommand / 入队）
- `runtime-core/.../runtime/capability/CommandExecutor.kt` + `CommandExecutionRecorder.kt`（执行与埋点汇聚）
- `app/.../data/local/llmlog/RoomToolCallRecorder.kt`（tool_call_log 持久化）
- `runtime-core/.../inference/remote/RemoteChatEngine.kt`（chatSystemPrompt）
- `runtime-core/.../inference/remote/tool/ChatToolService.kt` / `RemoteControlToolService.kt` / `GalleryToolDocs.kt`（@Tool 表面）
- `app/.../domain/agent/CapabilityHostCompose.kt`（Compose host，Phase 3 退役）
- `app/.../PoLangApplication.kt`（`initializeCapabilities()`，Phase 3 收口点）
