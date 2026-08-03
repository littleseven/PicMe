# Claude Code 自定义 glm-5.2 强模型子代理 — 设计

- 日期:2026-08-03
- 状态:待评审
- 范围:仅为 Claude Code(CC)新增 4 个固定使用 glm-5.2 的自定义子代理;不改动 kimi-code 任何配置。

## 1. 背景与目标

CC 主会话当前走 glm-5.2(Fable 档),而**全局子代理默认**走 glm-5.1(由 `CLAUDE_CODE_SUBAGENT_MODEL=glm-5.1` 强制,覆盖对主会话模型的继承)。这是有意的成本优化:子代理会裂变(单任务常 3–10 个,workflow 几十个),大量机械活(grep/读片段/定位)用便宜模型即可。

但部分**复杂子任务**(架构设计、代码评审、根因调试、强推理执行)确实值得用强模型。目标:**在不放弃成本优化的前提下,让复杂子任务能显式走 glm-5.2**,而非把所有子代理一刀切到 5.2(那样成本×N、网关并发限流、延迟全恶化)。

## 2. 设计原则:工具/模型分工(硬约束)

- **kimi-code = 用户第一选择**,K3 + kimi-code 是最佳拍档(kimi-code 原生支持「强模型写码 + 弱模型 review」,已由用户在 `~/.kimi-code/` 试验性配好)。
- **Claude Code = 用户第二选择(日常备选),只用 GLM,绝不使用 Kimi 模型。**
  - 现有 CC 配置已天然强制这一点:四个模型档(Fable/Opus/Sonnet/Haiku)全部映射到 glm-5.2 / glm-5.1,经智谱 GLM 网关(`open.bigmodel.cn/api/anthropic`),CC 侧未配置任何 Kimi provider。
  - 本设计新增的 4 个子代理全部 `model: glm-5.2`,继续维持 GLM-only。
- **kimi 侧本次零改动**:不共享文件、不统一模型。两套系统**仅标准共享**(都引用项目 `AGENTS.md` 治理)。
- 「强模型」在两工具里指代不同的实际模型(CC = glm-5.2;kimi = K3),因此**不存在一份 agent 文件同时让两边都跑强模型**的可能 —— 文件必须各管各。

## 3. 现状(配置事实)

- `.claude/agents/` 目录不存在(本次新建,零冲突起点)。
- 全局 `~/.claude/settings.json`:`ANTHROPIC_MODEL=glm-5.2`、`CLAUDE_CODE_SUBAGENT_MODEL=glm-5.1`;Fable→glm-5.2,Opus/Sonnet/Haiku→glm-5.1。
- 项目根 `agents/`(CO/PM/RD/CR/QA)是 **kimi-code** 的流水线团队(正文使用 `todo_write`/`search_memory`/`grep_code` 等 kimi 工具名),CC 不读取该目录。详见第 7 节。

## 4. 方案:4 个 CC glm-5.2 自定义子代理

位置:`.claude/agents/{planner,reviewer,debugger,reasoner}.md`(项目级,进 git)。
模型:均 `model: glm-5.2`,**不加 `[1M]`**(普通上下文够用;planner/reviewer 若日后读大代码库撑爆,再单独升级 `glm-5.2[1M]`)。

| agent | 工具集 | 角色 | 可写文件 |
|---|---|---|---|
| `planner` | Read, Grep, Glob, Bash, WebFetch, WebSearch | 架构/方案设计:探库→循既有模式与模块边界→出分步实现计划 | ❌ |
| `reviewer` | 同上(只读) | 代码评审/对抗验证:猎真缺陷、先反驳再确认、按严重度排序 | ❌ |
| `debugger` | 同上(+Bash 跑测试/复现) | 根因调试:复现→假设→收窄,**根因先于修复** | ❌ |
| `reasoner` | Read, Write, Edit, Bash, Grep, Glob, WebFetch, WebSearch, NotebookEdit, TodoWrite | 通用强推理兜底:全工具可执行 | ✅ |

设计要点:
- `planner`/`reviewer`/`debugger` **不配 Edit/Write/NotebookEdit** = 硬边界只读。`Bash` 保留用于跑 `./gradlew`/`git log`/复现等只读或验证命令,正文软约束「不落改动」。这对应「规划≠实现、评审≠修改、调试只查不改」。
- `debugger` 故意只读:根因定位后由**主循环(已是 glm-5.2)**落修复,避免调试代理同时查和改产生半成品改动。
- `reasoner` 显式列出全部工具但**不含 Task/Agent** → 防止子代理递归派生失控。
- 内置类型(`Plan`/`general-purpose` 等)仍继承 glm-5.1,只有显式按名调用这 4 个自定义 agent 才走 glm-5.2。

## 5. Frontmatter 公约

```markdown
---
name: <agent 名,与内置类型不撞>
description: <一句话:何时该用,显示在 agent 选择列表>
model: glm-5.2
tools: <逗号分隔;reasoner 全列,其余只读集>
---

<system prompt 正文>
```

- `name` 取 `planner`/`reviewer`/`debugger`/`reasoner`,均与内置类型(`claude`/`Plan`/`Explore`/`general-purpose` 等)不冲突。
- `model` 用显式 id `glm-5.2`(确定可被 CC 解析),不用别名 `fable`(agent frontmatter 对别名的支持不如显式 id 稳)。

## 6. System Prompt 要点

每个 agent 正文统一包含:(1) 角色与硬约束;(2) 指向 `CLAUDE.md` 守红线(`[PRIVACY]`/`[PERF]`/`[I18N]`);(3) 指向项目 `AGENTS.md` 治理。

- **planner**:软件架构师。先探代码库、沿用既有模式;识别关键文件/依赖/权衡;输出分步实现计划;**只读,绝不改文件**;遵守 Clean Architecture 模块边界与现有 ADR。
- **reviewer**:严谨代码评审。猎真缺陷(correctness/simplification/security/efficiency/test-coverage);**对抗式验证**(先尝试反驳再确认);按严重度排序、给具体失败场景;**只报告不修**;**对齐 kimi `cr-agent` 审计清单**:隐式 `it`、三语 `strings.xml` 同步、`PoLang:[Module]` 日志、魔法值、分层纯净度(domain 纯 Kotlin / data 无 UI 逻辑)。
- **debugger**:系统性调试。复现→假设→收窄,**定位根因先于任何修复**;Bash 跑测试/复现;**只查不改**,根因交回主循环;不掩盖症状。
- **reasoner**:强推理兜底。复杂多步任务;全工具可执行;拆步 + 完成前自验。

## 7. 与 kimi agents 的关系(消歧,避免日后混淆)

- 根 `agents/`(CO/PM/RD/CR/QA)是 **kimi-code 流水线团队**(CO 编排 PM→RD→CR→QA 接力,正文用 kimi 工具名)。CC 不读取它。
- `~/.kimi-code/agents/review.md` 与 `~/.kimi-code/AGENTS.md` 是 kimi 全局的强/弱模型路由(已配:plan/coder→primary=K3,review/explore→secondary=glm-5.2)。
- 本次新增的 `.claude/agents/` 是 **CC 单次聚焦子代理**(主循环按名调一个,跑完返回),与 kimi 流水线团队是**两套独立系统**,仅标准共享 `AGENTS.md`。
- 角色名重叠(`reviewer`↔`cr-agent`、`reasoner`↔`rd-agent`、`planner`↔`pm-agent`)在认知层面存在,但因分属不同工具、不同目录、不同模型(K3 vs glm-5.2),不构成技术冲突;`reviewer` 显式对齐 `cr-agent` 清单可保证两套评审标准不漂移。

## 8. 调用方式

主循环用 Agent 工具传 `subagent_type: "planner"`(等)即走 glm-5.2;不指定则仍是内置类型 → glm-5.1。成本优化不丢。

## 9. 非目标(Out of Scope)

- ❌ 不与 kimi 共享 agent 文件(强模型语义相反,原理上不可行,见第 2 节)。
- ❌ 不改动 kimi-code 配置(`~/.kimi-code/`、根 `agents/`)。
- ❌ 不改 CC 全局 `CLAUDE_CODE_SUBAGENT_MODEL`(保持 glm-5.1 默认)。
- ❌ 不引入 Kimi 模型到 CC(CC 维持 GLM-only)。

## 10. 待定 / 后续

- planner/reviewer 在超大代码库上若上下文不足,单独升级 `glm-5.2[1M]`。
- 落地后观察 4 个 agent 实际调用频率与产出质量,再决定是否增删角色。
