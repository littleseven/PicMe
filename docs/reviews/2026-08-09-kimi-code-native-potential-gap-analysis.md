# Kimi Code 原生潜力 Gap 分析 + ROI 路线图

> 日期：2026-08-09 ｜ 性质：顾问式分析（不动手） ｜ 数据来源：`~/.kimi-code/config.toml`、`AGENTS.md`、`agents/*.md`、`bin/kimi-review`、`kimi --help`、官方文档（moonshotai.github.io/kimi-code）、live env

## 0. TL;DR（三条头条）

1. **🔴 你误以为 kimi-code「无原生 hook」**——`bin/kimi-review` 注释白纸黑字写着 *"kimi-code（无原生 hook）场景下的穷人版自动 review"*。**这是错的。** kimi-code 有完整的原生 Hooks 系统（`PreToolUse`/`Stop`/`UserPromptSubmit` 可拦截，`PostToolUse`/`SubagentStop`/`Notification`/`SessionStart` 可观测）。你为了绕这个不存在的限制手搓了 `kimi-review`，而原生 hook 能把 review、i18n、doc-sync、ktlint 这些质量门禁**全自动**钉进流程。**这是 ROI 最高的一块。**
2. **🟠 你的模型路由「写了但没生效」**——`KIMI_CODE_EXPERIMENTAL_SECONDARY_MODEL=1` 已开，原生 `model_preference: primary|secondary` frontmatter 字段可用。但你的 4 个 agent 一个都没用：`review.md` 用的是被忽略的 Claude-Code 式 `model:` 字段（你自己在 kimi-review 注释里实测确认了）；`plan.md`/`explore.md`/`test.md` 干脆没写。AGENTS.md 里「plan/coder 默认 primary」是**靠 K3 主 agent 自觉传 model=**才成立，frontmatter 没兜底。
3. **🟡 6 个原生 config 段 + 1 个并发 env 闲置**——`[permission]`/`[subagent]`/`[token_counting]`/`[identity]`/`[tools]`/`[image]` 都没配；`KIMI_CODE_AGENT_SWARM_MAX_CONCURRENCY` 未设（AgentSwarm 走默认上限）。

---

## 1. 原生能力 × 当前用法：完整 gap 表

| 能力域 | 原生机制 | 你当前用法 | Gap |
|---|---|---|---|
| **Hooks** | `[[hooks]]`：PreToolUse/Stop/UserPromptSubmit 可拦截；PostToolUse/SubagentStop/Notification/SessionStart/PreCompact 可观测；fail-open | **零配置**（误以为不存在） | 🔴 关键 |
| **模型路由 frontmatter** | `model_preference: primary\|secondary`（需 secondary 实验开关，你已开） | review.md 用被忽略的 `model:`；其余无 | 🔴 高 |
| **Headless 脚本化** | `kimi -p <prompt> --output-format stream-json` | 仅 `kimi-review` 一处 | 🟠 高 |
| **ACP 服务器** | `kimi acp`（Agent Client Protocol，stdio，可被外部程序/编排器驱动） | 未用 | 🟠 高 |
| **AgentSwarm 并发上限** | `[subagent]` config + `KIMI_CODE_AGENT_SWARM_MAX_CONCURRENCY` env | env 未设，`background.max_running_tasks=6` | 🟡 中高 |
| **权限白名单** | `[permission]` allowlist + `/permission` 始终允许（自动继承给子 agent） | `default_permission_mode="manual"`，未调优 | 🟡 中高 |
| **多工作区** | `--add-dir`（单会话跨仓库：iOS+Android） | 未用 | 🟡 中 |
| **可观测性** | `kimi web`（本地 server+UI）、`kimi vis`（会话可视化）、`kimi export`（zip 归档） | 未用 | 🟡 中 |
| **主 agent 提示词覆盖** | `$KIMI_CODE_HOME/SYSTEM.md` + 模板变量（`${base_prompt}`/`${agents_md}`/`${skills}`…） | 只用 AGENTS.md 注入 | 🟡 中 |
| **Skill 体系** | `/skill:` 调用 + 自动调用（whenToUse）+ `${KIMI_SKILL_DIR}` + arguments | 仅 3 个项目 skill；`.claude/commands/` 那 28 个未同步成 kimi skill | 🟡 中 |
| **原生 plan 模式** | `--plan`（只读直到计划批准，写锁） | `default_plan_mode=false`；用自定义 plan agent 代替 | 🔵 中低 |
| **自治模式** | `--yolo` / `--auto`（批量/受信场景免确认） | 未用 | 🔵 中低 |
| **配置体检** | `kimi doctor` | 未用 | 🟢 低 |
| **Plugin 打包** | 把 skill/agent 打成可安装插件（团队共享） | 未用 | 🟢 低 |
| **Agent 工具白名单** | frontmatter `tools`/`disallowedTools`/`subagents` | 自定义 agent 均未限定 | 🟢 低 |
| **模板变量（agent body）** | `${cwd}`/`${now}`/`${os}`/`${agents_md}` 等 | 自定义 agent 全是静态文本 | 🟢 低 |

> 已充分用上的（不列为 gap）：多 provider/model 路由、`[secondary_model]`、`[thinking] effort=high`、`[loop_control]`、`[experimental] micro_compaction`、`background.*`、MCP（4 个 GLM 服务）、AgentSwarm×6、4 个自定义子 agent、token-audit.py、双会员额度经济学、K3/GLM 交叉审查流程。**这些已经是顶配水平。**

---

## 2. ROI 排序路线图

ROI = 影响 ÷ 上手成本。P0 = 立刻做、改动极小、收益最大。

| 优先级 | 动作 | 影响 | 成本 | 一句话 |
|---|---|---|---|---|
| **P0-1** | 接入原生 **Hooks** | 🔴 极高 | 中 | 把 review/i18n/doc-sync/ktlint 钉成自动门禁，退役 kimi-review「穷人版」 |
| **P0-2** | frontmatter 改用 **`model_preference`** | 🔴 高 | 极低 | 让「plan/coder=primary、review/explore/test=secondary」从 AGENTS.md 的口号变成 frontmatter 兜底 |
| **P1-1** | **`[permission]` 白名单** + 并发 env + `[subagent]` | 🟠 高 | 低 | 降日常确认摩擦 + 抬高 AgentSwarm 并发天花板 |
| **P1-2** | **Headless 流水线库**（`-p --output-format stream-json`） | 🟠 高 | 中 | 把 kimi-review 泛化成一族：doctor/doctsync/定时批量/CI 入口 |
| **P1-3** | **ACP 程序化编排** | 🟠 高 | 中高 | 用脚本/编排器驱动 kimi 会话，把你「手动开多实例」的 iOS 并行模型原生化 |
| **P2-1** | `kimi vis`/`web`/`export` 观测长 swarm | 🟡 中 | 低 | 直接用，解决 swarm「黑盒跑完看不清」 |
| **P2-2** | `--add-dir` 跨仓库单会话 | 🟡 中 | 极低 | iOS+Android 一个会话搞定 |
| **P2-3** | `SYSTEM.md` 塑造主 agent | 🟡 中 | 中低 | 比 AGENTS.md 注入更强的主 agent 行为控制 |
| **P2-4** | Skill 跨工具同步（`.claude/commands` → kimi skill） | 🟡 中 | 中 | 让 28 个 skill 在 kimi 侧也能 `/skill:` 直调 |
| **P3** | `doctor`、原生 `--plan`、`--yolo/--auto`、plugin 打包 | 🔵 低~中低 | 低 | 按需试 |

---

## 3. P0 深挖

### P0-1 原生 Hooks（最大杠杆）

**认知修正**：kimi-code **有**原生 hooks，配置写在 `~/.kimi-code/config.toml` 的 `[[hooks]]` 数组，每条 = `event` + `matcher`(正则) + `command` + `timeout`。事件 JSON 经 stdin 传给脚本；退出码 `0`=放行、`2`=拦截、其它=fail-open 放行。只有 `PreToolUse`/`Stop`/`UserPromptSubmit` 能拦截主流程，其余是观测型。

**可落地的门禁（举几个最贴你流程的）**：

```toml
# 写/改文件后，观测型跑质量检查（i18n 三语同步 / doc-sync / ktlint）
[[hooks]]
event = "PostToolUse"
matcher = "Write|Edit|MultiEdit"
command = "~/.kimi-code/hooks/quality-gate.sh"
timeout = 60

# 一轮结束时，观测型触发 GLM 交叉审查（替代手动 kimi-review）
[[hooks]]
event = "Stop"
command = "~/.kimi-code/hooks/auto-cross-review.sh"
timeout = 120

# 拦截危险 shell（rm -rf / 强推 main 等）——可拦截型
[[hooks]]
event = "PreToolUse"
matcher = "Bash"
command = "node ~/.kimi-code/hooks/block-dangerous-bash.mjs"
timeout = 5

# 子 agent 完成后桌面通知（长 swarm 回来看结果）
[[hooks]]
event = "SubagentStop"
matcher = ".*"
command = "terminal-notifier -title Kimi -message 'subagent done'"
```

**注意**：hooks 是 fail-open，**不能当隐私红线的唯一闸门**（`[PRIVACY]` 上传图片那类仍要靠权限审批 + 人工）。它适合「提醒 + 轻量拦截 + 自动观测门禁」。

**收益**：你为「无 hook」绕了多远的路——`kimi-review` 是手动一键、i18n/doc-sync 全靠 skill 自觉调、危险命令靠肉眼。接 hooks 后这些全自动化且强制。

### P0-2 frontmatter `model_preference`（极低成本的正确性修复）

当前问题：路由规则写在 AGENTS.md 散文里，靠 K3 主 agent「记得」派 agent 时传 model=。一旦它忘了，plan 就静默跑在 GLM 上——而 plan 决定实现走向，正是你最不愿下放 GLM 的。

**修复（frontmatter 加一行）**：

```markdown
# plan.md / coder（如自建）  →  确保规划/编码走 K3
---
name: plan
model_preference: primary      # ← 关键：兜底走 K3，主 agent 不传也对
---
```

```markdown
# review.md / explore.md / test.md  →  省 K3 额度走 GLM
---
name: review
model_preference: secondary     # ← 替换掉被忽略的 `model: glm/glm-5.2`
---
```

`model_preference` 只在 secondary 实验开关下生效——你的 env 已是 `=1`，立即可用。改完 `kimi-review` 里的 `-m glm/glm-5.2` 兜底也仍可保留（双保险）。

---

## 4. 建议采纳顺序

1. **本周**：P0-2（frontmatter，10 分钟）→ P0-1 接 1~2 个最痛的 hook（如 PostToolUse 质量门禁 + Stop 自动审查）。立刻能感到「自动化接管」。
2. **下周**：P1-1 权限白名单 + 并发调优（一次配置，长期受益）；P2-1 把 `kimi vis` 纳入 swarm 复盘习惯（零成本）。
3. **后续**：P1-2 headless 流水线库、P1-3 ACP 编排——这是把你「手动多实例」升级为「程序化编排」的中期工程，建议等 hooks/model_preference 稳了再做。
4. P2/P3 按需。

> 下一步如果你想把某一项（最可能是 P0-1 hooks 或 P0-2 frontmatter）**落地成实现方案**，我再切到设计+实现流程出 spec。
