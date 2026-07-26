# Agent 终端运行感知层：JS 沙盒可观测性设计

> **日期**：2026-07-26
> **状态**：已确认（用户）
> **范围**：JS 沙盒运行为首个感知对象；架构按「Agent 感知终端运行情况」通用能力设计
> **关联**：`docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`、根 `AGENTS.md` §2.4（结构化可观测性）

---

## 1. 定位：Agent 循环的感知闭环

Agent First 架构中，Agent 循环 = **感知 → 推理（远程 LLM）→ 行动（tool / JS 沙盒）→ 观察（observation 回传）**。

现状缺口：行动在**端侧**执行，而端侧运行状态对 Agent 和人类都是黑盒——唯一的反馈是单次 tool 调用的 observation 文本。一旦端侧行为异常（如 2026-07-26「盘点死循环」：Promise 字符串回传导致 LLM 盲重试 10 次），排查只能靠 LLM 调用日志侧写推断，端侧无任何结构化记录。

本设计建立 **Agent 终端运行感知层（Terminal Runtime Sensing）**：端侧执行产生**结构化事件**（Agent First §2.4 原则：事件即数据，可被 AI 消费），按三个环闭合：

| 环 | 含义 | 状态 |
|----|------|------|
| **环内感知**（in-loop） | 错误即时作为 observation 回传 LLM，Agent 当轮自愈 | ✅ 已具备（evalAsync 两段式，JS 栈回传） |
| **环外感知**（post-hoc） | 事件落库，人 / QA Agent / 自动化脚本事后消费、归因、度量 | 🚧 **本设计落地** |
| **自我感知**（self-diag） | Agent 经 `diag.*` 只读 handler 查询自身端侧运行史，环内主动归因 | 📋 预留演进（本设计定义事件模型使其可行） |

### 三层感知模型

端侧一次 Agent 请求涉及三个执行层，各有一张事件表（同库 `polang_llm_log.db`）：

| 层 | 事件表 | 记录者 | 状态 |
|----|--------|--------|------|
| 推理层：远程 LLM 调用 | `llm_call_log` | `RoomLlmCallRecorder` | ✅ 已有 |
| 行动层：tool（Capability）执行 | `tool_call_log` | `RoomToolCallRecorder` | ✅ 已有 |
| **端侧执行层：JS 沙盒运行** | **`js_run_log`** | **`RoomJsRunRecorder`** | 🚧 **本设计** |

三表按时间对齐即可还原一次请求的完整端侧链路（LLM 决策 → tool 分发 → 沙盒执行），这就是「Agent 感知终端运行情况」的最小可用闭环。

## 2. 结构化事件模型（runtime-core，引擎无关）

`agent/core/js/` 新增（不依赖任何具体 JS 引擎、不依赖 Room）：

```kotlin
/** 一次 JS 沙盒运行的结构化事件。Agent First §2.4：事件即数据，可被 AI 消费。 */
data class JsRunEvent(
    val createdAt: Long,
    val source: String,        // 运行来源：chat / debug_page（JsRuntime 构造注入，默认 unknown）
    val kind: String,          // eval / evalAsync / callFunction
    val script: String?,       // 脚本文本（仅 DEBUG；release 为 null）
    val scriptLength: Int,     // 脚本长度（release 的唯一内容指标）
    val success: Boolean,
    val errorCode: String?,    // JsBridgeException.code / UNKNOWN；成功为 null
    val errorMessage: String?, // 含 JS 栈（cap 500）
    val resultPreview: String?,// 结果 JSON 预览（仅 DEBUG，cap 1000）
    val latencyMs: Long,
)

interface JsRunRecorder {
    fun record(event: JsRunEvent)  // 要求：fire-and-forget，绝不阻塞/影响执行链路
}
```

错误分类（`errorCode`）：

| code | 场景 |
|------|------|
| `SCRIPT_ERROR` | JS 语法/运行时错误（含 Promise rejection，message 带 JS 栈） |
| `SCRIPT_TIMEOUT` | eval 超时（5s / 120s 写通路） |
| `HANDLER_ERROR` / `HANDLER_NOT_FOUND` / `HANDLER_NOT_ASYNC_CALLABLE` | bridge 分发失败（直穿 JS 时表现为此类） |
| `UNKNOWN` | 无法归类的异常 |

## 3. 埋点与错误归一

- **埋点位置**：`JsRuntime`（引擎无关门面）。`eval` / `evalAsync` / `callFunction` 统一计时 + `runCatching` + record + **错误原样重抛**（不改变任何现有行为）。
- **注入方式**：`JsRuntime.companion.recorder: JsRunRecorder?` + `captureContent: Boolean` 静态注入——与 `RemoteModelFactory.recorder`、`CommandExecutor.recorder` 同一既定模式；`PoLangApplication` 启动时装配（`captureContent = BuildConfig.DEBUG`）。
- **来源区分**：`JsRuntime` 构造新增 `source: String = "unknown"`；ChatViewModel 传 `chat`，JsBridgeDemo 传 `debug_page`。
- **错误归一**：`QuickJsEngine` 把 dokar3 `QuickJsException` 包装为 `JsBridgeException(SCRIPT_ERROR, ...)`（runtime-core 不可见 dokar3 类型）；超时已是 `SCRIPT_TIMEOUT`。JsRuntime 分类逻辑：`JsBridgeException → code`，其余 → `UNKNOWN`。

## 4. 持久化（polang_llm_log.db，v2→v3）

新表 `js_run_log`（独立诊断库，沿用 `fallbackToDestructiveMigration`「诊断数据可丢」约定，无需迁移代码）：

`id, createdAt, source, kind, script(仅DEBUG, cap 4000), scriptLength, success, errorCode, errorMessage(cap 500), resultPreview(仅DEBUG, cap 1000), latencyMs`

- `RoomJsRunRecorder`：镜像 `RoomLlmCallRecorder`——IO 协程异步写入、日级 guard prune（保留最近 200 条）、异常吞掉只打日志、绝不冒泡到执行链路。
- 隐私：release 构建 `script`/`resultPreview` 为 null，仅落指标（与 llm_call_log 现有约定一致）。

## 5. 环外感知消费入口

- **Debug 查看页**：`LlmCallLogScreen` 增加「JS 运行」Tab/入口：列表（时间/来源/kind/成功/耗时/错误码），详情展开脚本 + 错误栈 + 结果预览。手机上即可完成排查闭环，无需 adb。
- **adb 导出**：`run-as com.mamba.picme cat databases/polang_llm_log.db`（连 `-wal` 一起拉），供 Agent/脚本消费 sqlite。

## 6. 已知限制

- **游离 Promise**：脚本内创建但未 await 的 Promise，其 rejection 无法捕获（dokar3 1.0.5 不暴露 host promise rejection tracker）。文档化；缓解：工具描述要求脚本所有异步操作必须 await。
- **C 层死循环不可中断**（2026-07-26 真机实测确认）：`while(true){}` 类脚本卡住 native `evaluate`，`withTimeout` 的协程取消无法送达——dokar3 1.0.5 未暴露 `evaluationTimeoutMillis` / JS 中断 handler。后果：该次执行**永不产生事件**（埋点只能记录有返回的执行），且 chat 链路 `jsEvalMutex` 被占死，需重启 App 恢复；Debug 页因每次新建 runtime 互不影响。缓解方向：升级 dokar3 或自接 QuickJS interrupt handler。
- `jsEvalMutex` 串行 chat 链路；debug 页每次新建 runtime，互不干扰。

## 7. 测试

- JVM 单测：`JsRuntime` 埋点（fake engine 成功/失败 → fake recorder 断言事件字段；错误重抛语义）；`JsRunLogDao` 测试（镜像 `LlmCallLogDaoTest`）。
- 真机验证：chat「盘点我的相册」成功事件；debug 页故意执行报错脚本 → `SCRIPT_ERROR` 事件含 JS 栈；Debug 查看页可见。

## 8. 演进路线（本设计不做，仅预留）

1. `diag.*` 只读 handler（如 `diag.recent_js_runs({limit, onlyError})`）：Agent 环内查询自身端侧运行史，实现自我感知环——事件模型已按可回读设计。
2. 感知对象扩展：MNN 推理、模型加载、打标扫描等端侧长任务复用同一事件模型 + 独立事件表。
3. `LogAnalysisTool`（AGENTS.md 愿景）：结构化事件 → Agent 自我诊断报告。

## 9. 文档同步

- `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`：新增「运行可观测性」章节（事件模型、js_run_log schema、已知限制）。
- 根 `AGENTS.md` §2.4 实现状态注记：JS 沙盒成为结构化可观测性首个落地件。
