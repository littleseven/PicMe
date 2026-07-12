# PicMe 仓库整理方案 — Review

> **状态**：Tier 1/4 待执行；**Tier 2（模块改名）已决定不做**；Tier 3 文档修正已基本完成。
> **两个关键决定**：① 后端 **Monorepo**（`server/` 已落地）；② **模块不改名**——命名问题通过改文档解决（`:runtime-core`=本地 Agent Runtime、`:agent-core`=langchain4j 适配，已更正 CLAUDE.md 等）。
> **最后更新**：2026-07-12

---

## 1. Audit：现在"乱"在哪

| 类别 | 问题 | 状态 |
|------|------|------|
| **A. 顶层杂乱** | 根目录堆 15+ 松散项：脚本、测试图、两个无服务器实验、重复文档、废弃目录 | 待整理（Tier 1） |
| **B. 文档误归因模块** | 旧文档把 Agent Runtime 错误归到 `agent-core`；实际 `runtime-core`=本地 Agent Runtime、`agent-core`=langchain4j 适配 | ✅ 已改文档解决（**不改模块名**） |
| **C. 文档重复** | 根 `DEVELOPMENT.md`(6KB) 与 `docs/05-DEVELOPMENT/DEVELOPMENT.md`(25KB) 并存 | 待处理（Tier 1） |
| **D. 失效残留** | `.claude/worktrees` 旧条目（`.qoder/` 是 Qoder AI 工具目录，**保留**） | 待清理（Tier 1） |

**模块语义（保持现状，不改名）**：
- `:runtime-core` = **本地 Agent Runtime**（编排本地 Qwen + 远程推理；包 `com.mamba.picme.agent.core`）。
- `:agent-core` = **langchain4j 的 Android 适配层**（远程推理库；包 `com.mamba.client`）。
- 依赖链：`:app → :runtime-core → :agent-core`。

---

## 2. 目标结构（monorepo · 模块名不变）

```
langchain4android/
├── app/                     # Android app（不动）
├── beauty-api/              # 纯 Kotlin 契约
├── beauty-engine/           # OpenGL 引擎
├── runtime-core/            # 本地 Agent Runtime（包 com.mamba.picme.agent.core）—— 不改名
├── agent-core/              # langchain4j 的 Android 适配层（包 com.mamba.client）—— 不改名
├── mnn-core/                # MNN JNI
├── sentencepiece/           # tokenizer
├── server/                  # 【新】Ktor 后端（独立 Gradle build，见下展开）
│   ├── settings.gradle.kts  # rootProject.name=picme-server，不纳入安卓 settings
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/mamba/picme/server/
│   │   ├── Application.kt            # 入口 + 插件 + 路由装配
│   │   ├── config/AppConfig.kt       # 环境变量配置
│   │   ├── db/{Db,Tables,Migrations}.kt   # SQLite + Exposed
│   │   ├── recommend/RuleEngine.kt   # 规则型推荐
│   │   ├── routes/{Healthz,Recommend,Telemetry,Llm,Assets}Route.kt
│   │   ├── llm/                      # OpenAiProxy（DeepSeek/TokenHub/CF，待实现）
│   │   ├── cos/                      # CosSigner（COS 预签名，待实现）
│   │   └── ratelimit/                # 100/min + 日预算¥20（待实现）
│   ├── src/main/resources/logback.xml
│   ├── migrations/{001_init.sql, seed_rules.sql}
│   └── .env.example  deploy.sh  picme-api.service  README.md  .gitignore
├── shared/                  # 【新】端云共享 Kotlin（占位，将来 DTO/规则）
├── docs/
│   ├── changelog/           # ← RELEASE_NOTE_*.md、CHANGELOG.md
│   └── agents/              # ← agents/*.md（AI persona，可选）
├── infra/                   # ← cloudflare/(Workers) + tencentscf/(SCF) 无服务器实验
├── scripts/                 # ← analyze_commits.py + 现有脚本
├── tools/                   # json-schema-to-gbnf + test-images/(← input_images/)
├── buildSrc/                # Gradle 构建逻辑（保留）
├── AGENTS.md  AI_TOOLS.md  CLAUDE.md  PRODUCT.md  README.md   # 约定，留根
├── .claude/  .qoder/       # AI 协作工具目录（Claude Code / Qoder），保留
├── settings.gradle.kts  build.gradle.kts  gradle/  gradlew
└── ...
```

> 模块名一律保持现状，**不做改名**。结构上真正的新增项只有 `server/`（已建）与 `shared/`（占位）。

---

## 3. 改动清单（分 Tier）

### Tier 1　顶层归位（低风险 · 不动构建）

| 现位置 | 去向 | 说明 |
|--------|------|------|
| `analyze_commits.py` | `scripts/` | 脚本归位 |
| `input_images/` | `tools/test-images/` | 测试人脸图 |
| `cloudflare/`（Workers） | `infra/cloudflare/` | 无服务器实验 |
| `tencentscf/`（SCF） | `infra/tencentscf/` | 无服务器实验 |
| `RELEASE_NOTE_*.md`、`CHANGELOG.md` | `docs/changelog/` | 发版记录归位 |
| `DEVELOPMENT.md`（根） | **删除**（docs/05-DEVELOPMENT 更全）或改一行指针 | 去重 |
| `agents/*.md` | `docs/agents/`（可选） | AI persona 定义 |
| `.claude/worktrees` | 清理失效条目 | |
| `AGENTS.md`/`AI_TOOLS.md`/`CLAUDE.md`/`PRODUCT.md`/`README.md` | **留根** | AI 协作约定 |

**风险**：`grep` 引用（CI、脚本路径、文档相对链接）避免断链。

### ~~Tier 2　模块重命名~~ —— ❌ 已决定不做

原方案拟把 `runtime-core/ → agent-core/`、`agent-core/ → picme-http-client/`。**改为不改名**：模块名本就有语义（`runtime-core`=本地运行时、`agent-core`=langchain4j 适配），真正的问题只是旧文档把 Agent Runtime 错归到 `agent-core`——已通过修正 CLAUDE.md 等文档解决（零构建风险）。改名纯属可选的整洁性优化，**取消**。

### Tier 3　文档同步（✅ 基本完成）

已完成：`CLAUDE.md`（模块清单 + 语义）、`PRODUCT.md`、`DEVELOPMENT.md`、`beauty-api/AGENTS.md`、`ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`（完整路径审计）。
保留不改：`ADR-004/005/006`（历史决策记录）；`app/.../capability/AGENTS.md`（`agent-core` 语义有歧义，待 owner 复核）。

### Tier 4　新增 monorepo 占位

- ✅ `server/`：Ktor 后端骨架已落地（`/healthz`、`/recommend`、`/telemetry`；`/llm`、`/assets` 待实现）。
- ⏳ `shared/`：占位 `README.md`（端云共享 Kotlin，待端云真有共享需求时建）。

---

## 4. 风险与验证

| Tier | 风险 | 验证 |
|------|------|------|
| 1 | 文档相对链接、脚本/CI 路径断 | grep 引用；本地脚本试跑 |
| 3 | 文档自洽 | ✅ 已交叉检查（agent-core 引用扫描完成） |
| 4 | 无 | server 骨架独立构建（`./gradlew -p server installDist` 已验证） |

全程**小步提交、可回滚**。

---

## 5. 执行顺序

1. Tier 1（纯挪文件 + 删废弃）→ 提交 → 验证无断链。
2. ~~Tier 2 模块改名~~（取消）。
3. Tier 3 文档同步（✅ 已基本完成）。
4. Tier 4：`server/` ✅ 已建；`shared/` 占位待建。

---

## 6. 待你拍板 ⚠️

> Tier 2（模块改名）已定**不做**。剩余决策：

1. **`cloudflare/`、`tencentscf/`**（无服务器实验）：归 `infra/` 保留 / 删除（若已弃用）？
2. **根 `DEVELOPMENT.md`**：删 / 改指针？
3. **`agents/*.md`**：留根 / 挪 `docs/agents/`？
4. **范围**：只做 Tier 1（最稳）/ Tier 1 + Tier 4 的 `shared/` 占位？
