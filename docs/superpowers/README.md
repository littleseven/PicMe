# docs/superpowers/ — AI 协作产物唯一事实来源（SSOT）

> **版本**：1.0
> **生效**：2026-08-01
> **适用工具**：Claude Code · Kimi · AndroidStudio Qwen 插件 · OpenCode（四工具共同遵守）

---

## 1. 为什么需要这个目录

本项目同时使用四个 AI 编码工具，每个工具自带的「superpowers / planning」插件**默认把 plan / spec 写到各自私有目录**（如 `~/.claude/plans/`、`.omo/plans/`），导致：

- 同一个功能的 design 分散在多处，跨工具不可见
- Plan 与 spec 对不上号
- 代码评审、CR 审计无法定位权威文档

**解决方案**：把所有**可分享的协作产物**统一收到本目录（`docs/superpowers/`），入库、版本化、四工具读写同一份。各工具的**私有状态**（会话缓存、brainstorm mockup）仍留在点目录，保持 gitignore。

---

## 2. 目录结构

```
docs/superpowers/
├── README.md            ← 本文件（SSOT 声明）
├── plans/               ← 执行计划（work plans）
│   └── YYYY-MM-DD-<slug>.md
├── specs/               ← 设计规格（design specs）
│   └── YYYY-MM-DD-<slug>-design.md
└── *.md                 ← 阶段性汇总文档（如 nightly-*.md、*-summary.md）
```

> `decisions/`（ADR 风格跨工具决策记录）为可选扩展，目前未启用，需要时新建即可。

---

## 3. 命名规范（强制）

| 类型 | 格式 | 示例 |
|------|------|------|
| **Plan** | `YYYY-MM-DD-<kebab-case-slug>.md` | `2026-08-01-ai-engineer-diag-merge.md` |
| **Spec** | `YYYY-MM-DD-<kebab-case-slug>-design.md` | `2026-07-22-js-engine-jsbridge-design.md` |
| **汇总** | `YYYY-MM-DD-<topic>-summary.md` 或 `nightly-YYYY-MM-DD.md` | `2026-07-20-batch-mlkit-on-demand-summary.md` |

规则：
- 日期取**创建日**，不随后续修改变更
- slug 用英文小写 + 连字符，简洁表意（如 `chat-memory-passive-injection`）
- Spec 文件名一律以 `-design.md` 结尾，便于程序化识别
- 同一主题的 plan 与 spec 通过 slug 对应（如 plan `2026-07-20-batch-mlkit` ↔ spec `2026-07-20-batch-mlkit-on-demand-summary-design.md`）

---

## 4. 四工具写入约定（关键）

| 工具 | 默认位置 | **本项目要求** |
|------|----------|----------------|
| **Claude Code**（superpowers 插件） | `~/.claude/plans/` | ❌ 禁止；plan/spec 一律写 `docs/superpowers/{plans,specs}/` |
| **OpenCode**（ulw-plan / Momus） | `.omo/plans/` | ✅ 已做软链 `.omo/plans → ../docs/superpowers/plans`，写入自动落到公共目录 |
| **Kimi** | 无固定位置 | 直接写 `docs/superpowers/{plans,specs}/` |
| **AndroidStudio Qwen 插件** | 无固定位置 | 直接写 `docs/superpowers/{plans,specs}/` |

**Claude Code 配置提示**：项目级 `.claude/CLAUDE.md` 已声明本约定；若使用 superpowers 插件的 `/writing-plans` 等命令，请在生成后**立即移动**到 `docs/superpowers/plans/` 并按本规范改名。

---

## 5. 公共产物 vs 工具私有状态（边界）

| 类型 | 位置 | 是否入库 | 说明 |
|------|------|----------|------|
| ✅ Plan / Spec / 设计决策 | `docs/superpowers/` | ✅ 入库 | 四工具共享 |
| 🔒 Brainstorm mockup / HTML | `.superpowers/brainstorm/` | ❌ gitignore | 工具私有 UI 草稿 |
| 🔒 会话续接状态 | `.omo/run-continuation/` | ❌ gitignore | OpenCode 会话态 |
| 🔒 个人权限缓存 | `.claude/settings.local.json` | ❌ gitignore | Claude Code 个人配置 |
| 🔒 临时 plan 草稿 | `~/.claude/plans/` | ❌ 用户级 | **建议及时迁移到公共目录** |

**判断准则**：能被另一个工具复用、能被 CR 审计、能被 git 追踪 → 放公共目录。否则留私有。

---

## 6. 索引

- 现有 specs（53 篇）：见 `specs/` 目录
- 现有 plans（2 篇）：见 `plans/` 目录
- 阶段汇总：`claude-tunnel-summary.md`、`nightly-2026-07-19.md`

---

## 7. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-01 | 建立 SSOT 约定，统一四工具 plan/spec 写入位置；新增 `.omo/plans` 软链 |
