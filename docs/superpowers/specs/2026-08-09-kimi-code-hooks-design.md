# Kimi Code Hooks 设计 Spec(P0-1)

> 日期：2026-08-09 ｜ 状态：**已核准(Option A)** ｜ 关联：`docs/reviews/2026-08-09-kimi-code-native-potential-gap-analysis.md`(P0-1)
> 数据来源：`~/.kimi-code/bin/kimi` 二进制反编译(strings + 代码区段) + 官方文档(moonshotai.github.io/kimi-code) + polang `scripts/` + wire.jsonl 实测工具名 + live env

## 0. 关键前置结论(反编译核实，高信度)

> 本节取代任何"项目级 config.toml"的设想——经二进制核实，**kimi-code 不支持项目级 config.toml**。

| 问题 | 结论 |
|---|---|
| kimi-code 有原生 hooks 吗？ | **有**。`HookEngine` / `runHook(command, input, opts)`。`~/.kimi-code/bin/kimi-review` 里"无原生 hook"的注释是**错的**。 |
| hook 配置 schema | `HookDefSchema$1`(strict)：`event`(枚举)、`matcher`(可选,**RegExp** 字符串,空=全匹配)、`command`(必填,shell 串)、`timeout`(可选,1–600s,默认 30)。 |
| 配置落在哪？ | **仅 user 级** `~/.kimi-code/config.toml`(`[[hooks]]` 数组)，或**插件 manifest**(`manifest.hooks`，插件经 `installPlugin` 全局安装)。 |
| 项目级 `.kimi-code/` 认什么？ | 只认 `mcp.json` / `skills/` / `AGENTS.md`。**不认 config.toml，不认项目插件**。 |
| `config.toml` 解析路径 | `resolveKimiHome($KIMI_CODE_HOME ?? ~/.kimi-code) + "config.toml"` —— 纯 user 级，无项目级回退。 |
| 事件枚举(全 16) | `PreToolUse` `PostToolUse` `PostToolUseFailure` `PermissionRequest` `PermissionResult` `UserPromptSubmit` `Stop` `StopFailure` `Interrupt` `SessionStart` `SessionEnd` `SubagentStart` `SubagentStop` `PreCompact` `PostCompact` `Notification` |
| matcher 语义 | `matches$2(pattern,value)` = `new RegExp(pattern).test(value)` → `matcher="Edit|Write"` 合法。 |
| PostToolUse stdin JSON | snake_case：`hook_event_name` `session_id` `cwd` `matcher_value` `tool_name` `tool_input{...}` `tool_call_id`。**被编辑文件路径 = `tool_input.path`**(Edit/Write 的 args 含 `path`)。 |
| Stop 事件 | `triggerBlock("Stop", …)` **可阻断**，turn 结束触发；stdin 含 `stop_hook_active`。无 matcherValue(空 matcher)。 |
| Notification 事件 | `fireAndForgetTrigger`；`matcherValue = notification.type`；stdin 含 `title`/`body`/`severity`/`notification_type`。 |
| 文件编辑工具名(wire.jsonl 实测) | `Edit`、`Write`(无 MultiEdit) → Hook A matcher = `Edit|Write`。 |

**→ 结论：要实现 P0-1 的三个 hook，`[[hooks]]` 块必须落在 `~/.kimi-code/config.toml`(user 级)。** 经用户决策采 **Option A**。

## 1. 目标与范围

**目标**：用 kimi-code 原生 Hooks 把目前手动/靠自觉的三类质量动作自动化——① 改代码/文档后的轻量检查、② 一轮结束的 GLM 交叉审查、③ 完成(子 agent / 通知)桌面通知。

**范围(本次实现)**：
- Hook A：`PostToolUse`(`Edit|Write`) → i18n 硬编码 grep + doc-sync 检查（观察型，不阻断）
- Hook B：`Stop` → 异步触发 GLM 交叉审查（异步，不阻塞 Stop）
- Hook C：`SubagentStop` + `Notification` → 桌面通知（osascript，非 terminal-notifier——本机未装）

**明确不在范围**：
- 危险命令拦截（PreToolUse Bash block）——后续可加。
- 重门禁（ktlint/detekt/compile/单测）——**不进 hook**（同步阻塞、超时、破坏 <100ms 交互红线），继续走手动 skill / `scripts/ai-gate.sh`。
- 隐私红线 `[PRIVACY]` 不依赖 hook——hook 是 fail-open，不能当唯一闸门；仍由权限审批 + 人工兜底。

## 2. 核心原则(Option A)

> 用户决策：kimi 无项目级 config，故 `[[hooks]]` 落 user 级；但**满足"修改安全"本意**——SSOT 在仓内，user config 仅受管追加、可一键删、其他仓库零副作用。

1. **仓内 SSOT**：所有 hook 脚本 + 配置片段落在 polang 仓内 `.kimi-code/`。user 级 `~/.kimi-code/config.toml` 只做**受管追加**(带醒目 marker 的 `[[hooks]]` 块)，由 `install-hooks.sh` 幂等写入/替换/移除。
2. **cwd 自隔离**：hook 的 `command` 用相对路径 `[ -x .kimi-code/hooks/X.sh ] && … || true`。polang 会话 cwd = polang 根 → 脚本存在 → 执行；其他仓库 cwd 下无该文件 → `[ -x ]` 失败 → `|| true` → 静默 exit 0，**零副作用**。
3. **零阻断**：三 hook 全观察/异步，始终 fail-open。
4. **P0-2 不动**：4 个 agent 的 `model_preference` 保留 user 级，本次不迁移。

## 3. 架构

每门禁一个独立脚本(匹配 kimi「每条 `[[hooks]]` = 一条规则」)。脚本统一放 `.kimi-code/hooks/`，共享逻辑放 `.kimi-code/hooks/lib/`。配置片段放 `.kimi-code/hooks.toml`(SSOT)；`install-hooks.sh` 把它受管追加进 user config。

**隔离原理**：kimi 以 cwd = 会话工作目录执行 hook 命令；polang 根下 `.kimi-code/hooks/` 存在，其他仓库不存在 → 相对路径 + `[ -x ]` guard 天然把行为限制在 polang。无需硬编码绝对路径。

**事件语义(反编译核实)**：
- `PostToolUse`(matcher=工具名 RegExp)：工具成功后触发，**观察型**(返回值不影响主流程，仅 stdout 回显给 agent)。
- `Stop`(matcher=空)：模型即将结束本轮时触发，**可阻断**；我们只用来异步派活后立即返回。
- `SubagentStop` / `Notification`：观察型(fire-and-forget)。
- hook 事件 JSON 经 **stdin** 传入；退出码 `0`=放行、`2`=拦截、其它/超时/崩溃=fail-open 放行。

## 4. 详细设计

### `.kimi-code/hooks.toml`(SSOT 配置片段)

```toml
# >>> polang kimi-code hooks (managed; SSOT=polang/.kimi-code/hooks.toml) >>>
[[hooks]]
event = "PostToolUse"
matcher = "Edit|Write"
command = "[ -x .kimi-code/hooks/post-edit-check.sh ] && .kimi-code/hooks/post-edit-check.sh || true"
timeout = 30

[[hooks]]
event = "Stop"
command = "[ -x .kimi-code/hooks/stop-auto-review.sh ] && .kimi-code/hooks/stop-auto-review.sh || true"
timeout = 10

[[hooks]]
event = "SubagentStop"
command = "[ -x .kimi-code/hooks/notify.sh ] && .kimi-code/hooks/notify.sh subagent || true"
timeout = 5

[[hooks]]
event = "Notification"
command = "[ -x .kimi-code/hooks/notify.sh ] && .kimi-code/hooks/notify.sh notification || true"
timeout = 5
# <<< polang kimi-code hooks <<<
```

### Hook A — 改文件后快速检查(观察型)

`post-edit-check.sh` 逻辑：
1. 读 stdin JSON，jq 取 `tool_input.path`(被编辑文件绝对路径)。解析失败 → exit 0(fail-open)。
2. 路径分发(**只跑轻量检查，绝不跑 gradle**)：
   - `*.kt`/`*.java` → 调 `.kimi-code/hooks/lib/i18n-hardcode.sh` 单文件 grep → 命中则 stdout 打印 ⚠️ 行号清单。
   - `docs/**/*.md` 或 `{PRODUCT,FEATURES}.md` → 调 `python3 scripts/check_doc_sync.py` → stdout 打印摘要(exit 非 0 时说明有不一致)。
   - 其余 → exit 0(no-op)。
3. 始终 exit 0(观察型)。

`lib/i18n-hardcode.sh`(**新建**，`scripts/check-i18n-hardcode.sh` / `check_i18n_sync.py` 当前不存在)：对单 `.kt` 文件 grep 双引号内 ≥3 字符可见文案，排除 `Log.`/`TAG`/`http`/注释/常量 key；正则基线取自 `.claude/commands/i18n-validator.md`(收敛高信号子集)。v1 只做源码硬编码检测；跨三语 key 完整性延后。

### Hook B — turn 结束自动 GLM 交叉审查(异步)

`stop-auto-review.sh` 逻辑(**派活后立即返回**)：
1. **递归 guard**(防审查进程的 Stop 再触发审查)：环境变量 `KIMI_POLANG_HOOK_REVIEW=1` 已设 → exit 0。
2. **代码改动 guard**：`git status --porcelain` 含 `*.kt`/`*.xml`？无则 exit 0(纯问答轮跳过)。
3. **冷却 guard**：读 `.kimi-code/.last-review` 时间戳；距上次 < 600s 则 exit 0。
4. **异步派活**：`KIMI_POLANG_HOOK_REVIEW=1 nohup ~/.kimi-code/bin/kimi-review "$PWD" > .kimi-code/.last-review.log 2>&1 & disown`；写时间戳；立即 exit 0。
5. 后台 `kimi-review`(复用 review agent + GLM)跑完，结果落 log。

> 递归防护三重：env 哨兵 + 冷却(审查必在 10min 内完成 → 其 Stop 落在冷却窗) + review 只读不改代码(改动 guard 也不满足)。若实测 `kimi-review` 同仓多会话争用，回退为直连 GLM `chat/completions` 的 curl(轻量但丢 review.md 提示)。

### Hook C — 完成桌面通知(osascript，非 terminal-notifier)

`notify.sh <kind>`：`kind=subagent` → 标题"子agent 完成"；`kind=notification` → 从 stdin 取 `title`/`body`/`severity` 组装消息。统一 `osascript -e 'display notification "…" with title "Kimi" sound name "Glass"'`(本机未装 terminal-notifier，osascript 为 macOS 原生零依赖)。始终 exit 0。

## 5. 文件清单

**polang 仓内(可提交)**：
| 文件 | 作用 |
|---|---|
| `.kimi-code/hooks.toml` | `[[hooks]]` 配置 SSOT 片段(受管追加源) |
| `.kimi-code/hooks/post-edit-check.sh` | Hook A 入口 |
| `.kimi-code/hooks/stop-auto-review.sh` | Hook B 入口(异步) |
| `.kimi-code/hooks/notify.sh` | Hook C 入口(SubagentStop/Notification) |
| `.kimi-code/hooks/lib/i18n-hardcode.sh` | i18n 硬编码 grep |
| `.kimi-code/install-hooks.sh` | 受管追加/替换/移除 user config 的 `[[hooks]]` 块(幂等) |

**user 级(受管追加，带 marker，可一键移除)**：
- `~/.kimi-code/config.toml`：追加 `.kimi-code/hooks.toml` 内容(`>>> polang kimi-code hooks`/`<<<` marker 包裹)。

**运行态(加入 .gitignore)**：`.kimi-code/.last-review`、`.kimi-code/.last-review.log`。

## 6. 安全边界与风险

| 风险 | 处理 |
|---|---|
| hook 阻断 agent 主流程 | 三 hook 全观察/异步，exit 0，零阻断 |
| Hook B 同步阻塞 Stop | `nohup … & disown` 后台化，Stop 秒返 |
| Hook B 每轮堆 GLM 调用(成本) | 「代码改动」+「10min 冷却」+「env 哨兵」三 guard |
| Hook B 递归(审查的 Stop 再触发审查) | env 哨兵 + 冷却窗 + review 只读 |
| hook 在其他仓库误触发 | 相对路径 + `[ -x ]` guard + `|| true` 静默 no-op |
| hook 脚本自身报错拖累 | fail-open；脚本包 `set -u`、解析失败即 exit 0 |
| 隐私红线 | **不依赖 hook**；权限审批 + 人工兜底 |
| user config 误污染 | marker 包裹 + `install-hooks.sh` 幂等替换/移除 |

## 7. 验收标准

- polang 仓内 kimi 会话编辑 `.kt` → Hook A 打印 i18n 检查(不阻断)；编辑 `docs/*.md` → 打印 doc-sync 摘要。
- 一轮代码改动结束 → Hook B 后台触发 GLM 审查，主流程不阻塞，日志落 `.kimi-code/.last-review.log`，10min 内不重复。
- 子 agent / 通知 → osascript 桌面通知。
- 其他仓库(ncnn 等)启动 kimi → **无任何 hook 触发/报错**(隔离生效)。
- `install-hooks.sh` 幂等：重复运行不重复追加；`--remove` 干净移除 marker 块，user config 其余内容逐字节不变。

> 原 spec 的「项目级 `.kimi-code/config.toml`、系统 config 零修改」前提已证伪；本 v2 反映 Option A(user config 受管追加 + 仓内 SSOT + cwd 自隔离)，经用户核准。
