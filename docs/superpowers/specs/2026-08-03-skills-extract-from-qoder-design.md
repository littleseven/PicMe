# Skills 从 .qoder 抽取到顶层 skills/ — 设计

- 日期:2026-08-03
- 状态:待评审
- 范围:把 `.qoder/skills/` 移到顶层 `skills/`(与 `agents/` 平级),让 skills 不再埋在已死工具目录下;修所有 `.qoder/skills` 引用。不统一 `skills/` 与 `.claude/commands/` 两份表示(另案),不删 `.qoder/` 本体(下一个 spec)。

## 1. 背景与决策

skills 当前埋在 `.qoder/`(Qoder 工具,已停用的「原主力环境」)下。用户要求提取到顶层 `skills/`(repo 根,与 `agents/` 平级),脱离死工具目录。

经查证,skills 实际有**两份分叉副本**:

| 位置 | 格式 | 数量 | 读它的人 | 状态 |
|---|---|---|---|---|
| `.qoder/skills/` | 嵌套(`skill/SKILL.md` + scripts) | 23 skill + TEMPLATE,5 个带 scripts(共 15 脚本) | **只有 kimi**(`.kimi/skills → ../.qoder/skills`) | 旧副本 |
| `.claude/commands/` | 扁平(`name.md`) | 21 命令 | Claude Code(slash commands) | 新副本,CC 的 SSOT |

两份已分叉:`.claude/commands/` 持续维护(2026-07-28 决策4 下线 `agent-test`/`qa-acceptance`、修 19 个过期引用);`.qoder/skills/` 没跟进,仍留退役 skill + 旧引用 → **kimi 实际在读过期内容**。

**决策(用户 2026-08-03 确认方案 A)**:最小搬——`.qoder/skills/` → `skills/`,搬时丢退役 skill、修引用;`.claude/commands/` 维持原样。不追求两份统一(CC 不直接读嵌套 `skills/` 当命令,格式不同,统一是更大改动,另案)。

## 2. 方案 A:最小搬

### 2.1 移动(`git mv` 保留历史)
- `.qoder/skills/*` → `skills/`(顶层,与 `agents/` 平级)。
- **丢弃不搬**:`agent-test/`、`qa-acceptance/`(2026-07-28 已退役,与 `.claude/commands/` 对齐)。
- **保留**:`TEMPLATE.md` + 21 个在用 skill(含 5 个带 scripts 的:adb-bot、image-quality-checker、doc-sync-guardian、av-gl-expert、rd-reflection,共 15 脚本)。
- 净结果:`skills/` = 21 skill 目录 + TEMPLATE.md,内容与 `.claude/commands/` 的 21 命令对齐。

### 2.2 重指符号链接
- `.kimi/skills`:`../.qoder/skills` → `../skills`。kimi 改读干净位置。

### 2.3 修引用(全仓 `.qoder/skills` → `skills`,按语境)
需扫描并更新的非 legacy 文件(查证命中):
- `.claude/commands/*.md`——至少 `rd-reflection.md` 引用 `.qoder/skills/...`(迁移残留);逐一改 `skills/...`。注意:这里改的是**脚本路径引用**(`.qoder/skills/X/scripts/...` → `skills/X/scripts/...`),CC 命令调脚本才不会断。
- `skills/` 内部自引用——搬动后文件里仍写着 `.qoder/skills/Y/scripts/...` 的互引用,改为 `skills/Y/...`(或相对路径)。
- `AI_TOOLS.md`——把「`.qoder/skills/` 是 SSOT」叙述改为 `skills/`。
- `docs/07-STANDARDS/COORDINATE_SYSTEM.md`、`docs/02-ARCHITECTURE/ADR/ADR-011-*.md`——引用路径改 `skills/`。
- `docs/07-STANDARDS/REPO_REORGANIZATION_PLAN.md`——原文「`.qoder/` 保留」需标注 skills 已迁出(`.qoder/` 本体的去留是下一个 spec)。
- `.claude/CLAUDE.md`——「历史源文件 `.qoder/skills/`」属历史叙述,可保留口径但补注「现 canonical 源在 `skills/`」;`.claude/migrate.py`/`fix_skills.py` 若硬编码 `.qoder/skills`,评估是否仍在用,在用则改路径、停用则标注废弃。

### 2.4 不动的
- `.claude/commands/` 维持扁平格式不动(CC 专用)。两份表示(嵌套 `skills/` 给 kimi/人读,扁平 `.claude/commands/` 给 CC)并存——这是方案 A 接受的取舍,统一属方案 B(另案)。

## 3. 验证

- `grep -rn '\.qoder/skills' .`(排除 .worktrees/.git)→ 命中应只剩**历史叙述**语境(AI_TOOLS 变更日志、ADR 历史记录等),无活跃路径引用。
- `ls -L .kimi/skills/` → 列出 `skills/` 的 21 skill,kimi 可读。
- `skills/<带脚本 skill>/scripts/` 下 15 脚本就位;`.claude/commands/` 里调脚本的命令路径解析到 `skills/...`。
- kimi 实测加载一个 skill(如 `ls .kimi/skills/adb-bot/`)正常。

## 4. 风险

- **脚本路径断链**:`.claude/commands/` 扁平命令与 skills 内部用 `.qoder/skills/X/scripts/...` 调脚本;搬动后路径变 `skills/X/scripts/...`,**必须全量改引用**,否则命令调脚本 404。这是实施重点。
- **`.claude/migrate.py`/`fix_skills.py`**:若仍被使用且硬编码 `.qoder/skills`,会断;需核实用途。
- **两份表示继续分叉**:`skills/`(kimi)与 `.claude/commands/`(CC)今后仍可能各自演化、再次分叉。已知、已接受;统一留方案 B。
- **REPO_REORGANIZATION_PLAN「保留 .qoder」措辞**:与本次迁出冲突,需在同文件补注,避免文档自相矛盾。

## 5. 后续(下一个 spec)

抽完 skills 后,`.qoder/` 只剩:`agents/`(CO 团队 legacy 副本)+ `settings.local.json`。届时整删 `.qoder/`:
- `.qoder/agents/` 是根 `agents/` CO 团队的 legacy 副本,随 cleanup spec(`2026-08-03-retire-co-agent-team-design.md`)删根 `agents/` 一并清掉。
- 删 `.qoder/` 后,`.kimi/skills` 已指 `skills/`,无悬空。
- 同步改 `REPO_REORGANIZATION_PLAN`、`AI_TOOLS.md` 移除 `.qoder` 条目。

## 6. 落地

- 纯文件移动 + 文档/路径编辑,无代码/编译影响;按规则文档类改可走 main,无需 worktree。
- 单次原子提交:`refactor(skills): skills 从 .qoder 抽取到顶层 skills/,退役 skill 清理`。含 `git mv` + 软链重指 + 引用修复,一次 commit。
- 顺序建议:在 cleanup spec(删根 `agents/`)之后做,或独立做均可——两者文件集不重叠(本 spec 动 `.qoder/skills`→`skills/` + 引用;cleanup 动根 `agents/` + AGENTS.md)。
