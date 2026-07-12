# PicMe 仓库整理方案

> **状态总览**
> - ✅ 已完成：模块命名文档修正（`CLAUDE.md`/`PRODUCT.md`/`beauty-api/AGENTS.md`/`ON_DEVICE_INFERENCE_INVENTORY` 等）；`server/` 后端骨架落地；Tier 1 安全挪动（`analyze_commits.py`→`scripts/`、`cloudflare/`+`tencentscf/`→`infra/`）。
> - ⏳ 待定：根 `DEVELOPMENT.md` 去留（§6 #1）；`shared/` 占位（可选）。
> - ❌ 不做：模块改名（Tier 2，已否决）。
> - ➖ 留根（有原因）：`CHANGELOG.md`/`RELEASE_NOTE_*`（`release-automation.sh` 写死路径）、`input_images/`（4 个 viz 脚本使用）、`agents/`（AI 工具约定）。
>
> **关键决定**：① 后端 **Monorepo**（`server/` 已建，独立 Gradle build）；② **模块不改名**（`:runtime-core`=本地 Agent Runtime、`:agent-core`=langchain4j 适配）；③ `.claude/`、`.qoder/` 均为 AI 协作工具目录，**保留**。
> **最后更新**：2026-07-12

---

## 1. 现状审计

| 类别 | 问题 | 处置 |
|------|------|------|
| **A. 顶层杂乱** | 根目录约 10 项需归位：`analyze_commits.py`、`input_images/`、`cloudflare/`、`tencentscf/`、`RELEASE_NOTE_*.md`、`CHANGELOG.md`、`agents/*.md` | Tier 1 归位 |
| **B. 文档误归因模块** | 旧文档把 Agent Runtime 错归到 `agent-core` | ✅ 已改文档（不改模块名） |
| **C. 失效残留** | `.claude/worktrees` 旧条目 | Tier 1 清理 |

**模块语义（保持现状，不改名）**：
- `:runtime-core` = **本地 Agent Runtime**（编排本地 Qwen + 远程推理；包 `com.mamba.picme.agent.core`）
- `:agent-core` = **langchain4j 的 Android 适配层**（远程推理库；包 `com.mamba.client`）
- 依赖链：`:app → :runtime-core → :agent-core`

> `.qoder/`（Qoder）与 `.claude/`（Claude Code）均为 AI 协作工具目录，**保留不动**。

---

## 2. 目标结构（monorepo · 模块名不变）

> 下方为整理后的**目标**结构。带 `←` 的为 Tier 1 归位目标（当前尚不存在）；`server/` 已落地、`shared/` 待建。

```
langchain4android/
├── app/  beauty-api/  beauty-engine/      # 安卓（不动）
├── runtime-core/           # 本地 Agent Runtime —— 不改名
├── agent-core/             # langchain4j 适配 —— 不改名
├── mnn-core/  sentencepiece/
├── server/                 # 【已建】Ktor 后端（独立 Gradle build，详见下）
│   ├── settings.gradle.kts        # rootProject.name=picme-server，不纳入安卓 settings
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/mamba/picme/server/
│   │   ├── Application.kt · config/AppConfig.kt
│   │   ├── db/{Db,Tables,Migrations}.kt          # ✅ SQLite + Exposed
│   │   ├── recommend/RuleEngine.kt                # ✅ 规则型推荐
│   │   ├── routes/{Healthz,Recommend,Telemetry}Route.kt   # ✅ 已实现
│   │   ├── routes/{Llm,Assets}Route.kt            # 🚧 待实现
│   │   ├── llm/ · cos/ · ratelimit/               # 🚧 待实现（DeepSeek 代理 / COS 预签名 / 100·min+¥20·day）
│   ├── src/main/resources/logback.xml
│   ├── migrations/{001_init.sql, seed_rules.sql}
│   └── .env.example · deploy.sh · picme-api.service · README.md · .gitignore
├── shared/                 # 【待建】端云共享 Kotlin（占位）
├── docs/                   # 文档根（changelog/agents 未单列：发版记录留根、agents/ 留根）
├── infra/                  # ✅ cloudflare/ + tencentscf/（无服务器实验，已挪入）
├── scripts/                # ✅ analyze_commits.py 已挪入
├── tools/                  # json-schema-to-gbnf（input_images/ 留根，viz 脚本使用）
├── .claude/  .qoder/       # AI 工具目录，保留
├── AGENTS.md  AI_TOOLS.md  CLAUDE.md  PRODUCT.md  README.md   # 约定，留根
├── settings.gradle.kts · build.gradle.kts · gradle/ · gradlew · buildSrc/
└── ...
```

---

## 3. 改动清单

### Tier 1　顶层归位（低风险 · 不动构建）—— 部分已执行

| 现位置 | 去向 | 状态 |
|--------|------|------|
| `analyze_commits.py` | `scripts/` | ✅ 已挪 |
| `cloudflare/`、`tencentscf/` | `infra/` | ✅ 已挪 |
| `CHANGELOG.md`、`RELEASE_NOTE_*.md` | **留根** | `scripts/release-automation.sh` 写死 `$PROJECT_ROOT/CHANGELOG.md`，挪了会断 |
| `input_images/` | **留根** | `scripts/visualize_eyes_landmarks.py` 等 4 个脚本使用 |
| `agents/*.md` | **留根** | AI 工具约定（与根 `AGENTS.md` 同级） |
| 根 `DEVELOPMENT.md` | 待定 | 见 §6 #1（删 / 指针 / 留） |
| `.claude/worktrees`、`.qoder/`、`.claude/` | **保留** | 工具目录（worktrees 由 EnterWorktree 工具管理） |

**风险**：`grep` 引用（CI、脚本路径、文档相对链接）避免断链——本次挪动的 3 项均仅文档提及、无脚本/CI 引用，已验证安全。

### Tier 2　模块重命名 —— ❌ 已否决（不做）

曾拟 `runtime-core → agent-core`、`agent-core → picme-http-client`。否决理由：模块名本有语义，真正的问题在旧文档误归因——已通过改文档（CLAUDE.md 等）解决，改名无必要且有构建风险。

### Tier 3　文档同步 —— ✅ 已完成

已修正：`CLAUDE.md`（模块清单+语义）、`PRODUCT.md`、`beauty-api/AGENTS.md`、`ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`（路径审计）。
保留不改：`ADR-004/005/006`（历史决策）；`app/.../capability/AGENTS.md`（语义歧义，待 owner 复核）。

### Tier 4　Monorepo 占位

- ✅ `server/`：Ktor 后端骨架已落地（`/healthz`、`/recommend`、`/telemetry` 编译通过；`/llm`、`/assets` 待实现）。
- ⏳ `shared/`：占位待建（端云真有共享需求时）。

---

## 4. 风险与验证

| 项 | 风险 | 验证 |
|----|------|------|
| Tier 1 | 文档相对链接 / 脚本 / CI 路径断 | grep 引用；本地脚本试跑 |
| server | 独立构建 | ✅ `./gradlew -p server installDist` 已通过 |

小步提交、可回滚。

---

## 5. 执行顺序

1. ✅ Tier 1 安全挪动已执行（`analyze_commits.py` / `cloudflare/` / `tencentscf/`）。
2. ⏳ 根 `DEVELOPMENT.md` 去留（§6 #1 定后处理）。
3. `shared/` 占位（可选，按需）。

> Tier 2 已否决；Tier 3 已完成；`server/` 已落地（`/llm`、`/assets` 另行实现）。

---

## 6. 待拍板 ⚠️

> `cloudflare/`/`tencentscf/` 已挪入 `infra/`（保留）；`agents/`、`CHANGELOG`/`RELEASE_NOTE`、`input_images/` 经评估**留根**（见 §3 理由）。

1. **根 `DEVELOPMENT.md`**（6KB，与 `docs/05-DEVELOPMENT/DEVELOPMENT.md` 25KB 重复）：删除 / 改一行指针 / 保留？
2. **`shared/` 占位**：现在建 / 等端云真有共享需求再建？
