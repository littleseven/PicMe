# PicMe 仓库整理方案

> **状态总览**
> - ✅ 已完成：模块命名文档修正（`CLAUDE.md`/`PRODUCT.md`/`beauty-api/AGENTS.md`/`ON_DEVICE_INFERENCE_INVENTORY` 等）；`server/` 后端骨架落地。
> - ⏳ 待执行：Tier 1 顶层归位；`shared/` 占位（可选）。
> - ❌ 不做：模块改名（Tier 2，已否决）。
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
├── docs/
│   ├── changelog/          # ← RELEASE_NOTE_*.md、CHANGELOG.md
│   └── agents/             # ← agents/*.md（可选）
├── infra/                  # ← cloudflare/ + tencentscf/（无服务器实验）
├── scripts/                # ← analyze_commits.py
├── tools/                  # json-schema-to-gbnf + test-images/（← input_images/）
├── .claude/  .qoder/       # AI 工具目录，保留
├── AGENTS.md  AI_TOOLS.md  CLAUDE.md  PRODUCT.md  README.md   # 约定，留根
├── settings.gradle.kts · build.gradle.kts · gradle/ · gradlew · buildSrc/
└── ...
```

---

## 3. 改动清单

### Tier 1　顶层归位（低风险 · 不动构建）

| 现位置 | 去向 | 说明 |
|--------|------|------|
| `analyze_commits.py` | `scripts/` | 脚本归位 |
| `input_images/` | `tools/test-images/` | 测试人脸图 |
| `cloudflare/`（Workers）、`tencentscf/`（SCF） | `infra/` | 无服务器实验归拢 |
| `RELEASE_NOTE_*.md`、`CHANGELOG.md` | `docs/changelog/` | 发版记录归位 |
| `agents/*.md` | `docs/agents/`（可选） | AI persona 定义 |
| `.claude/worktrees` 失效条目 | 清理 | |
| `.qoder/`、`.claude/` | **保留** | AI 工具目录 |

**风险**：`grep` 引用（CI、脚本路径、文档相对链接）避免断链。

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

## 5. 执行顺序（仅待办）

1. Tier 1：归位 + 清 `.claude/worktrees`（`.qoder/` 不动）→ 提交 → 验证无断链。
2. `shared/` 占位（可选，按需）。

> Tier 2 已否决；Tier 3 已完成；`server/` 已落地（`/llm`、`/assets` 另行实现）。

---

## 6. 待拍板 ⚠️

1. **根 `DEVELOPMENT.md`**（6KB，与 `docs/05-DEVELOPMENT/DEVELOPMENT.md` 25KB 重复）：删除 / 改一行指针 / 保留？（其余发版记录、脚本、无服务器实验的归位已在 Tier 1 默认执行）
2. **`cloudflare/`、`tencentscf/`**：归 `infra/` 保留 / 删除（若已弃用）？
3. **`agents/*.md`**：留根 / 挪 `docs/agents/`？
4. **范围**：只做 Tier 1 / 含 `shared/` 占位？
