# 移除 CO 团队管线遗物 + 瘦身根 AGENTS.md — 设计

- 日期:2026-08-03
- 状态:待评审
- 范围:删除项目根 `agents/`(kimi 旧版 CO/PM/RD/CR/QA 团队管线),瘦身根 `AGENTS.md` 去掉角色协作机制,修复因删除产生的断链引用。不涉及 `.qoder/`、`.claude/agents/`(另案)。

## 1. 背景与决策

项目根 `agents/`(CO/PM/RD/CR/QA 五个角色文件)是早期「Agent First 范式重构」产物,描述了一套 CO 编驱的 PM→RD→CR→QA 接力管线。经查证(2026-08-03)它已是**冗余遗物**,用户确认**当前不在 kimi 里实际运行**:

- 最后改动停在 2026-07-18(PicMe→PoLang 重命名),2 周未迭代。
- **未被当 live agent 加载**:kimi 项目级配置在 `.kimi/`,但无 `.kimi/agents/` 目录;kimi 全局只读 `~/.kimi-code/agents/`(仅 review.md)。根 `agents/` 既不在 `.kimi/agents/` 也不在全局,不会被 kimi 当可调度 agent。
- 项目级 `.kimi/AGENTS.md`(kimi 实际指令)未激活 CO 团队,只把根 `AGENTS.md` 当「治理/红线/标准」引用。
- 实际 kimi 执行已切到全局 `~/.kimi-code/`(内置 plan/explore/review/coder 子代理 + K3/GLM 档)。CO 团队正文用的工具名(`todo_write`/`search_codebase`/`grep_code`)与 `.kimi/AGENTS.md` 用的(`StrReplaceFile`/`ReadFile`/`Grep`)都对不上,像是更早一版残留。

**决策**:删 `agents/` + 把根 `AGENTS.md` 的「角色协作」机制砍掉、只留标准/红线/文档治理(用户 2026-08-03 确认)。与 Claude Code 自定义 agent 线独立(见 `2026-08-03-custom-glm52-agents-design.md`)。

## 2. 范围

### In scope

| # | 文件 | 动作 |
|---|---|---|
| 1 | `agents/`(整目录,5 文件) | **删除** |
| 2 | `AGENTS.md`(根,457 行) | **瘦身**(详见 §3) |
| 3 | `AI_TOOLS.md` | 删第 151–155 行 CO/PM/RD/CR/QA 角色表 |
| 4 | `.kimi/AGENTS.md` | 第 53 行去掉「角色协作」措辞 |

### Out of scope(另案)

- **`.qoder/`**:非干净死物——`.kimi/skills` 软链指向 `.qoder/skills`(kimi 项目级 skills 仍在读);`docs/07-STANDARDS/REPO_REORGANIZATION_PLAN.md` 明确写「保留」;且它是 `.claude/commands/` 的陈旧副本。删除需先决策 kimi 项目 skills 改走哪 + 改 REPO_REORGANIZATION → 另起 spec。
- **`.openclaw/` / `.lingma/` / `.opencode/`**:已停用/已卸载工具残留;引用基本只剩 `AI_TOOLS.md` 历史叙述 → 另案。
- **18 个模块 `AGENTS.md`** 里的 `[CO]/[RD]/Self-Heal/状态板` 提及:删 `agents/` 后成「软孤儿」(非断链,术语悬浮)。逐个去角色化是大扫除,本 spec 不碰,留作可选 Phase 2。

## 3. 根 AGENTS.md 瘦身明细

原则:去「角色协作/CO 编排」机制,留「架构原则/红线/文档治理/真实脚本」。估计 457 行 → ~330 行。

| 节 | 处置 | 说明 |
|---|---|---|
| 头 `> 维护者:CO Agent` | 改为 `> 维护者:开发者` 或删 | 不再有 CO |
| §1 项目背景 | 留 | Agent First 三重实验框架 |
| §2 架构原则(显式/枚举/自描述/可观测) | **留** | 核心**标准** |
| §3.1 角色定义表 | **砍** | 引用 `agents/*_agent.md`,删 agents/ 后断链 |
| §3.2 协作流程(CO 驱动) | **砍** | CO 管线机制 |
| §3.3 Tools 层表 | 留,去「调用者=RD/CR/QA」列 | 记的是真实脚本,去角色化 |
| §3.4 触发口令与执行模式 | **砍** | CO 触发机制 |
| §3.5 状态板管理 | **砍** | CO 状态板(可保留一句「用 TodoWrite 追踪任务」并入通用) |
| §3.6 回流机制 | **砍** | CO/CR/QA 回流 |
| §3.7 Token 节省 | 留,去角色化 | 通用效率提示 |
| §3.8 工作区隔离(强制) | **留** | 近期新增强制规约(commit 7a4f7194) |
| §4.1 Self-Heal 工作流(RD 叙事) | 去角色化 | 删 RD Agent 自愈叙事与伪代码;保留「编译→安装→测试→日志」闭环验证习惯作为通用约定 |
| §4.2 自动化脚本表 | **留** | 真实脚本索引 |
| §5 文档体系(AI 可解析) | **留** | 文档治理 |
| §6 全局红线 | **留** | 正是要保留的红线 |
| §7 研究问题与度量 | 留,去角色化 | RD Self-Heal→「自动修复成功率」 |
| §8 文档索引 第 374 行「AI 协作角色」 | **砍该行** | 指向 `agents/*_agent.md` |
| §8 其余索引行 | 留 | |
| §9 交付审计清单 | 留,去角色化 | 「CR 架构合规」→「架构审查」;「QA 核心验收」→「验收测试」 |
| 附录 A 工具调用速查 | **留** | |
| 附录 B 角色流转图(`用户→CO→PM→RD→CR→QA`) | **砍** | 文档体系图/关键指标去角色化后留 |

## 4. 引用修复明细

- **`AI_TOOLS.md:151-155`**:删除 `[CO]/[PM]/[RD]/[CR]/[QA]` 角色表(引用 `agents/*_agent.md`)。注意:`AI_TOOLS.md` 其他处对 `.qoder/skills` 的「SSOT」描述已陈旧(skills 已迁 `.claude/commands/`),但属 `.qoder` 另案,**本 spec 不动**,避免范围蔓延。
- **`.kimi/AGENTS.md:53`**:文档索引行 `顶层治理 | ../AGENTS.md | 角色协作、全局红线、文档治理` → 去掉「角色协作」,改为 `全局红线、文档治理、架构原则`。

## 5. 验证

- `grep -rn 'agents/\(co\|rd\|pm\|qa\|review\)_agent\.md'` → 删除后应**零命中**(排除 .worktrees/.git/.qoder)。
- 根 `AGENTS.md` 通读无悬空「角色/CO/状态板/回流」断链;章节编号连续(砍掉的子节重排或上级节直接收口)。
- `AI_TOOLS.md` 角色表删除后,上下文表格边界完整。
- `.kimi/AGENTS.md` 第 53 行措辞与根 `AGENTS.md` 实际内容一致。

## 6. 风险

- **模块 AGENTS.md 软孤儿**:18 个模块文件的 `[CO]/[RD]/Self-Heal` 术语成悬浮(非断链)。已知、已接受、Phase 2 处理。
- **肌肉记忆**:若曾依赖 CO 管线口令(「自动执行」「保守执行」),删除后不再有文档依据——但本就未 live 运行,影响仅限文档预期。
- **AI_TOOLS.md `.qoder` 陈旧描述残留**:本 spec 不修,留待 `.qoder` spec;此处仅记录为已知项,避免实施时顺手改超范围。

## 7. 落地

- 纯文档改动,按项目规则(`复杂feature用worktree` memory:文档可走 main)直接在 main 提交,无需 worktree。
- **单次原子提交**(文档一致性变更):`docs(agents): 移除 CO 团队管线遗物,瘦身根 AGENTS.md`。含 `agents/` 删除 + `AGENTS.md`/`AI_TOOLS.md`/`.kimi/AGENTS.md` 三处编辑,一次 commit。
- 不影响编译、不影响运行时;仅文档层。
