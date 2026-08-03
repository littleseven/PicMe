# 虚拟产品技术团队(Virtual Team)

一个由 6 个角色 agent 组成的虚拟互联网应用开发团队,通过 workflow 编排自主协作:PM → 架构 → 设计 → (Dev → Review → QA) → 验收。

## 文件清单

### 角色定义(`.claude/agents/`)
| 文件 | 角色 | 职责 |
|------|------|------|
| `vt-pm.md` | 产品经理 | 需求 → PRD(可机器判定的验收标准);终验 |
| `vt-architect.md` | 技术架构师 | PRD → 技术方案 + 任务清单(带改动范围/验收命令) |
| `vt-designer.md` | UI/UX 设计师 | PRD → DESIGN.md(界面/交互/组件规范) |
| `vt-dev.md` | 开发 | 按架构+设计实现单个任务,自测编译通过 |
| `vt-reviewer.md` | 代码审查 | 守项目硬规则/边界/隐私/i18N,通过或打回 |
| `vt-qa.md` | 测试 | 写自动化测试、跑验收命令、判定通过/Bug |

### 编排脚本
- `.claude/workflows/virtual-team.js` — 主 workflow

## 怎么跑(重要:必须开新会话)

> ⚠️ Claude Code 的自定义 agent 在**会话启动时一次性扫描**。这些 `vt-*` 文件在创建它们的会话里**调不了**,必须**开一个新会话**才能用。

开新会话后,用 Workflow 工具跑:

```
Workflow({
  scriptPath: ".claude/workflows/virtual-team.js",
  args: "做一个本地待办清单 App:增删改查、按优先级排序、数据存 Room"
})
```

`args` 也支持对象形式:
```
args: { requirement: "...", maxReviewRounds: 3 }
```

## 流水线

```
PM ──PRD──▶ Architect ──任务清单──▶ Designer ──DESIGN.md──▶
   pipeline(每个任务独立):
     Dev → Reviewer ──打回则重做(最多 maxReviewRounds 轮)──▶ QA
                                                            ↓
                                              全部通过 → PM 验收
```

每个任务在 pipeline 里**独立流转**,互不阻塞;Reviewer 打回自动循环回 Dev,超限才放弃并标记。

## 环境约束(必读)

1. **所有角色实际都跑主会话模型(当前 glm-5.2)**。`model: fable` frontmatter 在本环境是**空操作**——子代理恒继承主会话模型,无法靠换模型分强弱档。角色差异**完全靠 system prompt**(职责/输出格式/审查清单)实现。
2. **自定义 agent 不热加载**(见上"必须开新会话")。
3. **Reviewer/QA 的硬规则**绑定 PoLang 项目(无 FQN、无 wildcard import、lambda 显式命名、log tag、i18N 三语、隐私红线、模块边界)。若把这套团队搬到别的项目,需改 `vt-reviewer.md` / `vt-dev.md` 的规则段。

## 接到"长时自主运行"(跑一晚上)

workflow 本身是一次性编排。要让它**持续跑到目标完成、出错不被中断**,在外层套监督循环:

```bash
# scripts/vteam-loop.sh(示意,需按实际目标定制)
cd <worktree>  # 强制在隔离 worktree 跑,别污染主仓库
BUDGET_TOKENS=2000000   # 预算硬上限
while [ ! -f .vteam-done ] && [ $spent -lt $BUDGET_TOKENS ]; do
  claude -p "读 .vteam-state.json 续跑 virtual-team workflow,目标未达成则继续,达成则写 .vteam-done" \
    2>&1 | tee -a .vteam-log.txt    # 全量日志,早上能回放
  # claude session 崩了 → while 自动开下一个,从检查点恢复
done
```

四件必备(无人值守红线):
1. **可机器判定的完成条件**(workflow 已要求 PM 写可执行验收标准;外层判 `.vteam-done` 或验收命令退出码)。
2. **预算硬上限**(token/美元到点强制停,优先于"完成")。
3. **worktree 隔离 + 禁 push**(凌晨跑偏不污染远端;早上 review 再合)。
4. **检查点 + 全量日志**(进度落 `.vteam-state.json`,日志落文件)。

> 监督循环脚本 `scripts/vteam-loop.sh` 尚未生成——它的具体形态取决于"第一个试跑做什么应用"。确定应用后补。
