# 整删 .qoder/ 目录 — 设计

- 日期:2026-08-03
- 状态:待评审(与实施同步,用户已授权"接着做 spec 4")
- 范围:删除整个 `.qoder/` 目录(Qoder 工具已停用),清理全仓对 `.qoder` 的引用。前置 spec 2(删根 `agents/`)、spec 3(skills 抽到顶层)已完成。

## 1. 背景与决策

`.qoder/` 是已停用工具 Qoder(「原主力环境」)的残留。spec 3 已把 skills 从 `.qoder/skills/` 抽到顶层 `skills/`,`.kimi/skills` 也重指 `../skills`。现在 `.qoder/` 只剩 6 个文件,无任何活工具读取。用户 2026-08-03 决策:整删。

## 2. .qoder/ 现状(6 文件)

- `.qoder/agents/{co,rd,pm,qa,cr}-agent.md`(5 个):根 `agents/` CO 团队的 legacy 副本(spec 2 已删根版本)。
- `.qoder/settings.local.json`:Qoder 工具配置(`additionalDirectories` 指向项目根),Qoder 已停用 → 随删。

## 3. 引用爆炸半径(全仓 `.qoder`,排除 worktrees/git/.qoder/spec 文档)

| 文件 | 命中 | 性质 | 处置 |
|---|---|---|---|
| `AI_TOOLS.md` | 7 | 工具表(OpenCode/Qoder/kimi 行)+ IDE助手表 + changelog | 移除死工具行、去 .qoder 措辞;**changelog 留**(历史) |
| `docs/07-STANDARDS/REPO_REORGANIZATION_PLAN.md` | 4 | 「.qoder/ 保留」「agents/ 留根」等过期保留声明 | 改为已移除 + 加 2026-08-03 注 |
| `.claude/CLAUDE.md` | 3 | 迁移史叙述 + 死指针 `migrate.py`(脚本不存在) | 删死指针、补注 canonical skills 源在 `skills/` |
| `docs/02-ARCHITECTURE/ADR/ADR-011` | 2 | 历史 ADR 提及归档源 | **不动**(ADR 是不可变历史) |

> `migrate.py`/`fix_skills.py` 经查**不存在**(`.claude/` 下无 `.py`),`.claude/CLAUDE.md` 里那俩引用是死指针。

## 4. 动作

### 4.1 删除
- `git rm -r .qoder`(整目录,6 文件)。

### 4.2 `AI_TOOLS.md`
- **工具配置速查表**:移除 **OpenCode**(已卸载)、**Qoder**(已停用)两行;**kimi-cli** 行去掉「.kimi/skills 为 .qoder/skills 符号链接」→ 改「.kimi/skills → ../skills」。
- **IDE 内置 AI 助手表**:移除 **Qoder** 行;**Lingma** 行「原 Skills 已迁移至 .qoder/skills」→ 改「已迁移(经 .qoder/skills → 现 skills/)」或直接移除该行。
- **兼容性变更记录**(changelog 表):**保留**——记录的是历史迁移事件,改了反而失真。

### 4.3 `docs/07-STANDARDS/REPO_REORGANIZATION_PLAN.md`
- 顶部状态加「2026-08-03 更新:`.qoder/` 已整删(spec 4)、根 `agents/` 已删(spec 2)、skills 已迁顶层 `skills/`(spec 3)」。
- §3 Tier 1 表与目标结构里的「`.qoder/` 保留」「`agents/` 留根」改为「已移除」。
- 注:此文档本身是 2026-07-12 的一次性整理计划(多数已执行),只修过期保留声明,不做整体重写。

### 4.4 `.claude/CLAUDE.md`
- 删除死指针行「同步脚本:`.claude/migrate.py`(.qoder/skills/ → .claude/commands/)」(脚本不存在)。
- 「历史源文件」注补一句:现 canonical skills 源在顶层 `skills/`(kimi 读此),CC 命令仍在 `.claude/commands/`。
- 「历史命令曾从 .qoder/skills/ 迁移而来」属历史叙述,可保留口径。

## 5. 验证

- `git rm -r .qoder` 后 `.qoder/` 不存在;`find . -name .qoder` 仅 worktrees 副本(无视)。
- `grep -rn '\.qoder' .`(排除 worktrees/git/spec 文档)→ 仅剩 **历史叙述**:AI_TOOLS changelog、ADR-011、`.claude/CLAUDE.md` 迁移史口径。无活跃路径/配置引用。
- `.kimi/skills` 仍解析到 `skills/`(spec 3 已修,不受影响)。

## 6. 落地

- 纯删除 + 文档编辑,无代码/构建影响;走 main,无需 worktree。
- 单次原子提交:`chore(repo): 整删 .qoder/ 残留目录,清理引用`。含 `.qoder/` 删除 + `AI_TOOLS.md`/`REPO_REORGANIZATION_PLAN.md`/`.claude/CLAUDE.md` 三处编辑。
