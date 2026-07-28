# langchain4android AI Agent 系统：唯一事实来源 (SSOT)

> **版本**：2.2（服务端对齐版）  
> **状态**：生效中  
> **最后更新**：2026-07-16  
> **维护者**：CO Agent  
> **历史合并说明**：本文档由根目录 `AGENTS.md` 与 `agents/README.md` 合并而成。`agents/README.md` 中的角色速查、状态板模板、Token 节省、回流机制、Tools 输入输出、工具调用速查等内容已并入本文档对应章节或附录，原文件已删除。

> 本文档为**顶层治理文档**，定义 Agent First 的研发流程与协作规范。
>
> **langchain4android** 是面向 Android 的 AI Agent 基础库（Java），Demo 工程 **PoLang（破浪相册）** 验证其在真实场景中的可行性。

---

## 1. 项目背景：Agent First 三重实验

langchain4android 是一个元实验（meta-experiment），同时探索三个层次：

| 层次 | 实验对象 | 核心问题 |
|------|----------|----------|
| **基础库** | langchain4android（`:agent-core`） | LangChain4j 风格 API 能否在 Android 高效运行？ |
| **运行时** | PoLang Agent 编排层（`:runtime-core` + `:app`） | LLM 能否成为应用的中枢神经系统？ |
| **服务端** | PoLang Server（`server/` Ktor 后端） | AI 网关、账号体系、管理后台能否支撑端侧 Agent？ |
| **架构层** | Agent First 客户端框架 | 什么样的架构让 Agent 最高效？ |
| **流程层** | Agent First 研发流程 | Agent 如何通过编排 Tools 完成开发？ |

**核心假设**：当基础设施原子化为 Tools 层后，Agent 可以从「辅助工具」进化为「主导力量」。

---

## 2. Agent First 的代码架构原则

langchain4android 的所有代码遵循以下原则，确保 Agent 能高效理解、修改、验证：

### 2.1 显式优于隐式（Explicit > Implicit）

```kotlin
// ❌ 隐式依赖：AI 需要全局搜索理解生命周期
object BeautyEngine {
    fun getInstance() = instance
}

// ✅ 显式注入：构造函数即文档
class CameraViewModel(
    private val beautyEngine: BeautyEngine,
    private val agentUseCase: AiAgentUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel()
```

**收益**：通过构造函数签名，AI 即可理解组件协作关系，无需跨文件搜索。

### 2.2 枚举优于条件（Exhaustive > Conditional）

```kotlin
// ❌ 布尔标志组合爆炸
class CameraState(
    val isLoading: Boolean,
    val hasError: Boolean,
    val isPreviewing: Boolean
)

// ✅ 枚举所有合法状态
sealed interface CameraState {
    data object Initializing : CameraState
    data class Previewing(val settings: BeautySettings) : CameraState
    data class Error(val reason: String) : CameraState
}
```

**收益**：状态空间显式编码，AI 可枚举所有边界情况，不会遗漏。

### 2.3 自描述优于注释（Self-Describing > Commented）

```kotlin
// ❌ 注释与代码可能脱节
// 调节美颜参数
fun adjust(params: Map<String, Int>) // AI 不知道有哪些参数

// ✅ 类型系统即文档
data class BeautyParameters(
    val smooth: IntRange = 0..100,
    val whiten: IntRange = 0..100,
    val slimFace: IntRange = -50..50
)
fun adjust(params: BeautyParameters) // 类型即契约
```

**收益**：类型系统强制一致性，AI 可靠类型推导而非易腐烂的注释。

### 2.4 结构化可观测性（Structured Observability）

```kotlin
// ❌ 纯文本日志，需正则解析
Log.d("Camera", "Agent parsed: $input -> $intent")

// ✅ 结构化事件，AI 可直接消费
data class AgentCommandParsedEvent(
    val rawInput: String,
    val parsedIntent: Intent,
    val confidence: Float,
    val timestamp: Long
) : LogEvent

Logger.log(AgentCommandParsedEvent(...))
```

**收益**：结构化日志可被 AI 消费，实现自我诊断和自我改进。

> **实现状态（2026-07-26）**：结构化可观测性已有首个落地件——Agent 终端运行感知层三件套（`polang_llm_log.db` 的 `llm_call_log` 推理层 / `tool_call_log` 行动层 / `js_run_log` 端侧 JS 沙盒执行层），事件模型引擎无关、可被 AI 消费（详见 `docs/superpowers/specs/2026-07-26-js-sandbox-observability-design.md`）。其余模块仍以 `PoLang:` 前缀标签 + `Log.d/w/e` 为主，结构化事件（如 `AgentCommandParsedEvent`）尚未在全局范围强制要求，是后续 Phase 3 的推进方向。

---

## 3. Agent 角色与协作流程

PoLang 采用**角色化协作模型**：每个 Agent 角色有明确的职责边界、输入输出契约。

### 3.1 角色定义

| 角色 | 标识 | 核心职责 | 关键能力 | 参考文档 | 激活方式 |
|------|------|----------|----------|----------|----------|
| **[CO]** 协调者 | `🤖CO` | 任务分级、状态板维护、流程推进 | 复杂度分析、状态板维护 | [`agents/co_agent.md`](agents/co_agent.md) | **所有请求默认激活** |
| **[PM]** 产品经理 | `🤖PM` | 需求澄清、PRD 维护、验收标准 | 需求拆解、文档同步 | [`agents/pm_agent.md`](agents/pm_agent.md) | 由 CO 在需求类任务中激活 |
| **[RD]** 全栈工程师 | `🤖RD` | 端到端实现、Self-Heal、Tools 编排 | 代码生成、Tools 编排 | [`agents/rd_agent.md`](agents/rd_agent.md) | 由 CO 在实现类任务中激活 |
| **[CR]** 规范守护者 | `🤖CR` | 架构合规审查、代码质量裁决 | 红线检查、影响分析 | [`agents/review_agent.md`](agents/review_agent.md) | 由 CO 在 RD 完成后激活 |
| **[QA]** 质量专家 | `🤖QA` | 边界测试、性能基线、端到端验收 | 场景设计、回归检测 | [`agents/qa_agent.md`](agents/qa_agent.md) | 由 CO 在 CR 通过后激活 |

**设计原则**：
- 每个角色有**明确的输入输出契约**
- 每个角色有**可验证的交付标准**
- 角色间通过 **CO 协调**传递信息，非直接沟通
- **CO 是所有用户请求的唯一入口**

### 3.2 协作流程（CO 驱动）

```
用户请求
    ↓
[CO] 分析任务类型 → 复杂度分级（L1/L2/L3）→ 创建状态板
    ↓
[PM] 需求对齐 → 输出可执行结论（AC）
    ↓
[RD] 原子化实现 → 代码 + 文档同步
    ↓  调用 Tools 完成验证
[RD] Self-Heal 闭环 → 编译 → 安装 → 测试 → 日志
    ↓  [CO 检测到"编译通过"自动推进]
[CR] 规范审查 → 架构合规、代码质量
    ↓  [CO 检测到"审计通过"自动推进]
[QA] 验收测试 → 边界、性能、体验
    ↓  [CO 检测到"验收通过"自动推进]
[CO] 汇总交付 → 更新状态板 → 报告闭环
```

**CO 推进规则**：
- RD 报告编译通过 → CO **必须**立即启动 CR 审计
- CR 报告无 Critical → CO **必须**立即启动 QA 验收
- QA 报告无 P0 缺陷 → CO **必须**立即生成最终交付报告
- **严禁**在 L1/L2 任务中间环节要求用户确认

### 3.3 Tools 层

基础设施原子化为 **Tools**，供 Agent 编排调用：

| Tool | 功能 | 输入 | 输出 | 调用者 | 状态 |
|------|------|------|------|--------|------|
| `CompileTool` | 代码编译检查 | 源码变更 | 编译结果/错误日志 | RD | 🔄 脚本实现 (`./gradlew`) |
| `InstallTool` | 安装到设备 | APK | 安装状态 | RD | 🔄 脚本实现 (`adb install`) |
| `ScreenshotTool` | 自动截屏 | 设备连接 | 截图文件 | RD/QA | 🔄 脚本实现 (`adb screencap`) |
| `LogAnalysisTool` | 结构化日志分析 | Logcat | 结构化事件 | RD | 📋 设计愿景 |
| `DocSyncTool` | 文档同步检查 | Git diff | 需更新文档列表 | CR | 📋 设计愿景 |
| `ScreenshotDiffTool` | UI 回归检测 | 截图对比 | Diff 报告 | QA | 🔄 脚本实现 (`screenshot-diff.py`) |
| `PerfBaselineTool` | 性能基线对比 | 性能指标 | 对比报告 | QA | 📋 设计愿景 |

> **实现状态（2026-07）**：`CompileTool`、`InstallTool`、`ScreenshotTool` 已通过 Gradle 脚本和 adb 命令落地；`ScreenshotDiffTool` 已有 `scripts/screenshot-diff.py` 实现；`LogAnalysisTool`、`DocSyncTool`、`PerfBaselineTool` 仍为设计愿景，待 Phase 3 基础设施完善。

**关键转变**：从「人类操作脚本」到「Agent 编排 Tools」。

### 3.4 触发口令与执行模式

| 口令 | 模式 | 自动化程度 | CO 行为 | 适用场景 |
|------|------|-----------|--------|----------|
| （无口令） | **默认模式** | L1 全自动 / L2 半自动 | 自动分析分级并启动对应流程 | 日常开发任务 |
| `自动执行` | 全链路自动 | L1/L2 全自动 | 强制启动完整 CO→PM→RD→CR→QA 流程 | 明确的全链路需求 |
| `保守执行` | 全链路可控 | 关键节点暂停 | 每阶段完成后暂停等待用户确认 | 高风险变更、不可逆操作 |
| `仅分析` | 诊断模式 | 不执行 | CO 仅输出分析，不启动任何角色 | 需求澄清、方案比选 |

**默认模式分级行为**：
- **L1 任务**（单文件修改、已知模式）：CO→RD→CR→QA，全自动推进，仅最终报告
- **L2 任务**（跨多文件、新功能）：CO→PM→RD→CR→QA，半自动，关键节点简报
- **L3 任务**（架构变更、无先例）：CO→PM→RD→CR→QA，手动，每阶段确认

### 3.5 状态板管理（强制）

CO 必须使用 `todo_write` 工具维护任务状态板，确保跨消息持久化。

**状态板模板**：

```markdown
## 任务状态板：[任务简述]

| 阶段 | 负责 | 状态 | 输出物 |
|------|------|------|--------|
| 需求分析 | [PM] | ⏸️/🔄/✅/❌ | 需求确认 |
| 技术实现 | [RD] | ⏸️/🔄/✅/❌ | 代码 + 构建结果 |
| 规范审计 | [CR] | ⏸️/🔄/✅/❌ | 审计报告 |
| 质量验收 | [QA] | ⏸️/🔄/✅/❌ | 测试报告 |
| 最终交付 | [CO] | ⏸️/🔄/✅/❌ | 汇总报告 |

**当前阶段**：[角色]
**任务分级**：[L1/L2/L3]
**RD 自愈次数**：[0/1/2]
**阻塞项**：[如有]
```

### 3.6 回流机制

- CR 不通过 → CO 回流 RD，不通过计数 +1
- QA 不通过 → CO 回流 RD，标记为 Bug
- RD 自愈 2 次仍失败 → CO 上报用户，提供选项

### 3.7 Token 节省

- L1 任务阶段间推进消息 ≤ 3 行
- 状态板替代长篇进度汇报
- 各角色仅输出增量信息，不重复已知上下文

---

## 4. Self-Heal 与自动化工具链

langchain4android 的核心创新是赋予 RD **闭环验证能力**——不仅能写代码，还能通过 Tools 自动验证正确性。

### 4.1 自愈工作流

```kotlin
// RD Agent 的标准执行循环
fun implementTask(task: CoTask) {
    // 1. 理解需求（基于 CO 传递的 PM 结论）
    val requirement = parsePmConclusion(task.pmOutput)
    val spec = parseFeaturesMd(task.featureRef)

    // 2. 分析上下文
    analyzeCodebase(task.affectedModules)

    // 3. 编码实现
    writeCode(requirement, spec)

    // 4. 闭环验证
    var attempts = 0
    while (attempts < MAX_RETRY) {
        val result = execute("./scripts/auto-dev-loop.sh")
        when {
            result.success -> {
                reportToCo("✅ 编译通过，变更摘要：...")
                return
            }
            result.recoverable -> {
                analyzeAndFix(result.errors)
                attempts++
                reportToCo("🔄 第${attempts}次自愈...")
            }
            else -> {
                reportToCo("❌ 不可恢复错误：...")
                return
            }
        }
    }
    reportToCo("❌ 自愈${MAX_RETRY}次仍失败...")
}
```

### 4.2 自动化脚本

| 脚本 | 用途 | 调用者 |
|------|------|--------|
| `./scripts/ai-gate.sh` | 代码质量门禁 | CI / RD |
| `./scripts/auto-dev-loop.sh` | 编译→安装→启动→截屏→日志 | RD |
| `./scripts/impact-analyzer.sh` | 变更影响分析 | CO |
| `./scripts/doc-sync-guardian.sh` | 文档同步检查 | CR |
| `./scripts/test-generator.py` | 基于 public 方法生成测试骨架 | RD |
| `./scripts/screenshot-diff.py` | UI 回归检测 | QA |

**收益**：标准化工具消除人工操作的不确定性，AI 可编排完成复杂验证。

---

## 5. 文档体系（AI 可解析）

langchain4android 的文档设计为**机器可读、交叉引用完整**，AI 可直接解析为执行计划。

### 5.1 文档层级

```
PRODUCT.md (What: 目标与约束)
    ↓ 引用
docs/01-PRODUCT/FEATURES.md (How: 交互与体验)
    ↓ 引用
模块 AGENTS.md (Implementation: 实现约束)
    ↓ 反向链接
代码实现
```

### 5.2 任务标记规范 `[agent-task]`

AI 可直接解析 Spec 中的任务标记，生成执行计划：

```markdown
### 调节美颜参数 [agent-task:beauty-001]
- **Assignee**: RD
- **Scope**: `domain/agent/capability/ImageEditCapability.kt`
- **Expected Change**:
  1. 实现 Capability 接口
  2. 注册到 CapabilityRegistry
  3. 添加单元测试
- **Priority**: P0
- **Acceptance**: AC-P0-1
```

**收益**：需求→任务→代码的转换自动化，减少信息损耗。

---

## 6. 全局红线（不可突破）

| 红线 | 定义 | 验证方式 |
|------|------|----------|
| **[PRIVACY]** | 禁止向远程大模型/推理服务器上传用户图片/视频文件（媒体处理 100% 端侧）；文本/元数据/相册摘要可走远程推理；飞书/Telegram 等用户自配置通道回传媒体不在此列（ADR-008） | 网络抓包（远程推理请求体无图片/视频）、权限清单扫描 |
| **[PERF]** | 交互 < 100ms，快门 < 50ms | 性能测试、人工体感 |
| **[I18N]** | 禁止硬编码，三语同步 | 资源文件检查 |
| **[DOC-SYNC]** | 代码变更必须同步文档 | CI 文档检查 |
| **[AGENT-FIRST]** | 新代码必须遵循 Agent First 原则 | CR 审查 |

---

## 7. 研究问题与度量

### 7.1 待验证的假设

1. **AI 可处理代码规模上限**：当前项目含 agent-core（Java）+ Demo 工程（Kotlin）共约 3 万行代码，上限是多少？
2. **AI 重构能力**：AI 能否主导跨模块架构重构？
3. **Self-Heal 成功率**：RD Agent 自动修复编译/运行时错误的成功率？
4. **文档驱动开发的效率**：相比传统流程，AI 协作的效率提升？
5. **Tools 扩展性**：新 Tools 能否被 Agent 自动发现和集成？

### 7.2 度量指标

| 指标 | 当前基线 | 目标 |
|------|----------|------|
| RD Self-Heal 成功率 | 待收集 | > 70% |
| 文档→代码一致性 | 待评估 | > 95% |
| AI 生成代码占比 | 待评估 | > 60% |
| 人工介入频次 | 待评估 | < 20% |

> **实现状态（2026-07）**：以上度量指标目前均为手动统计或待收集状态。自动化采集代码尚未落地（如 Self-Heal 成功率统计脚本、文档一致性 CI 检查工具等），是后续 Phase 3 的基础设施建设重点。

---

## 8. 文档索引

| 类型 | 文档 |
|------|------|
| **顶层治理** | `AGENTS.md`（本文档） |
| **产品定义** | `PRODUCT.md` |
| **交互规范** | `docs/01-PRODUCT/FEATURES.md` |
| **AI 协作角色** | `agents/co_agent.md`, `agents/rd_agent.md`, `agents/pm_agent.md`, `agents/review_agent.md`, `agents/qa_agent.md` |
| **模块规范** | 各模块 `AGENTS.md`（`app/`、`beauty-engine/`、`agent-core/`、`runtime-core/`、`mnn-core/`、`sentencepiece/`、`server/` 等） |
| **技术专项** | `docs/03-TECHNICAL-SPECS/*.md` |
| **端侧推理全景** | `docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`（含优化评估与多模型生命周期改造清单） |
| **IM 远程控制技术规格** | `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`（IM 远程控制线已冻结，服务端替代方案优先） |
| **AI 一键优化** | `docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md` |
| **TAG 生成** | `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` |
| **MNN LLM 运维** | `docs/03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md` |
| **语音栈** | `docs/03-TECHNICAL-SPECS/VOICE_STACK.md`（含 ASR Language Model 说明） |
| **大美丽美颜引擎** | `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`（含相机预览比例、帧同步美妆、容灾降级） |
| **人脸关键点** | `docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md` |
| **JS Engine** | `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`（QuickJS 沙箱 + JSBridge：run_gallery_script 取数、draw_chart 图卡、capability.dispatch 写通路） |
| **能力注册与实现** | `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`（含实现指南与生命周期规范） |
| **开发规范** | `docs/05-DEVELOPMENT/DEVELOPMENT.md`（含代码审查与任务标记规范） |
| **本地开发环境** | `docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md` |
| **QA 验收** | `docs/06-QA/QA_EXECUTION_CHECKLIST.md`（含自动化测试与核心功能测试指引） |
| **性能基线** | `docs/06-QA/PERFORMANCE_BASELINE_REPORT.md` |
| **服务端部署** | `docs/03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md`（香港 VPS + Nginx + certbot，DNS-only 无 Cloudflare 代理） |
| **服务端实现** | `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`（Ktor 后端：AI 网关、账号、管理后台） |
| **备份恢复** | `docs/05-DEVELOPMENT/RELEASE_PACKAGE_BACKUP_RESTORE.md`（Release 包数据备份与恢复） |

> **架构说明（2026-07-15）**：
> - **`:agent-core` 是 Java Android Library**（非 Kotlin），提供 LangChain4j 风格的 ChatModel、@Tool、AiServices、ChatMemory 等 API
> - **Agent 编排层在 `:runtime-core` 模块**（Kotlin）：`AgentOrchestrator`、`CapabilityRegistry`、`PrivacyGuard`、`MemoryManager`、`SceneManager` 等均位于 `runtime-core/src/main/java/com/mamba/picme/agent/core/`
> - **TAG 生成在 `:app` 模块**：`TagScanOrchestrator`、`TagGenerationScheduler`、`OpenClGuardian` 位于 `app/src/main/java/com/mamba/picme/domain/tag/`；`TagGenerationService` 为前台 Service；`TagGenerationControlScreen` 提供 3-Pass 控制与按类别/时间范围重新生成 UI
> - **OpenCL 超时与降级**：`OpenClGuardian` 在 Pass 3 前执行 warmup，单次推理带超时；连续失败/超时后标记设备降级为 CPU，黑名单持久化到 DataStore；`TagGenerationScheduler.ensureModelLoaded()` 自动按 Guardian 策略选择后端
> - **OpenAI 协议兼容**：`OpenAiChatModel` / `OpenAiStreamingChatModel` 支持所有兼容 OpenAI API 的服务（DeepSeek、通义千问等），含 tool_calls、流式、多轮对话
> - **DeepSeek 适配**：API 请求自动禁用 thinking 模式；ToolSpec 自动添加 `additionalProperties: false` 兼容 strict 模式；`tool_choice: REQUIRED` 正确映射为 `"required"`
> - `AiAgentUseCase` 作为 Facade 兼容层存在（:app 模块），内部委托给 `AgentOrchestrator` 执行。默认 agentMode 已从 LOCAL 改为 REMOTE（远程推理优先策略）
> - **JS Engine（QuickJS 沙箱）**：引擎无关层在 `:runtime-core` `agent/core/js/`（JsEngine/JsValue/JsBridge/JsRuntime/NativeHandler），QuickJS 实现与应用 handler 在 `:app` `features/chat/js/`（QuickJsEngine/GalleryScriptHandlers/ChartJs/CapabilityDispatchHandler）；除只读取数 handler 外，已存在 `capability.dispatch` **写通路**（CommandRisk 分级 + 用户确认 + ChatMediaWriteCapability）。详见 `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`
> - **服务端（`server/`）**：独立 Ktor 工程，提供 AI 网关（Channel 路由 / LLM 代理）、账号体系（邮箱注册 / Token 认证）、管理后台（Admin 视图）、推荐引擎（RuleEngine）、限流（RateLimiter）、COS 对象存储。与 Android 客户端通过 Monorepo 管理，但不纳入 Android `settings.gradle`。

---

## 9. 交付审计清单

- [ ] 代码遵循 Agent First 原则（显式、枚举、自描述、结构化）
- [ ] PRODUCT.md 已更新或保持一致
- [ ] FEATURES.md 已更新或保持一致
- [ ] 模块 AGENTS.md 已更新实现细节
- [ ] 满足 [PRIVACY]、[PERF]、[I18N] 红线
- [ ] Self-Heal 闭环验证通过
- [ ] CR 架构合规审查通过
- [ ] QA 核心验收通过

---

## 附录 A：工具调用速查

| 场景 | 工具 | 示例 |
|------|------|------|
| 代码修改 | `Edit` | 原子化修改 |
| 批量读取 | `Read` | 多文件并行分析 |
| 编译验证 | `Bash` | `./gradlew assembleDebug` |
| 设备操作 | `Bash` | `adb install/logcat` |
| 任务追踪 | `TodoWrite` | **状态板维护（强制）** |
| 知识存储 | `Skill` | 关键决策记录 |

## 附录 B：快速参考

### 文档体系
```
PRODUCT.md (What)
    ↓
FEATURES.md (How)
    ↓
模块 AGENTS.md (Implementation)
    ↓
代码
```

### 角色流转
```
用户 → CO → PM → RD → CR → QA → CO → 用户
         ↑              ↓______↓
         └────────────── 回流机制
```

### 关键指标
- RD Self-Heal 成功率：目标 > 70%
- 文档同步率：目标 > 95%
- 人工介入率：目标 < 20%
- 阶段遗漏率：目标 = 0%
