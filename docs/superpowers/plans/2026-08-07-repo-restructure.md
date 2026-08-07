# Phase 3：项目改名与目录重组（repo-restructure）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将仓库目录结构从 `app/` + 平铺引擎模块重组为 `androidApp/` + `engines/`，Gradle rootProject 改名 `polang`，零行为变更、构建常绿。

**Architecture:** 纯机械重组——`git mv` 保历史 + 引用批量更新。模块改名采用**方案 B**（Gradle 模块名同步改：`:app`→`:androidApp`、`:beauty-engine`→`:engines:beauty-engine` 等，对齐总计划 §3.2）。`:runtime-core` 留根级不搬（Phase 4 消亡）。包名 `com.mamba.picme`、applicationId 不动。本地目录改名与 GitHub repo rename 放最后两个 Task（外向操作 + 会话干扰风险）。

**Tech Stack:** Gradle (Kotlin DSL) · git mv · bash sed/grep 批量替换

**前置条件（开工前必须满足）：**
- main 已包含 Koog 迁移全部内容（✅ merge `614a4fef`）**以及 R8 proguard 修复**（`app/proguard-rules.pro` 补 Ktor `java.lang.management` 两条 dontwarn——开工时确认该 commit 已合入 main）
- 依据文档：总计划 `docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` §Phase 3；影响面盘点已做（2026-08-07 explore agent 全仓扫描，本计划文件清单即据此）

**命名映射表（全文统一，Type consistency 锚点）：**

| 旧 | 新 |
|----|----|
| `app/`（目录） | `androidApp/` |
| `:app`（Gradle 模块） | `:androidApp` |
| `beauty-api/` → `:beauty-api` | `engines/beauty-api/` → `:engines:beauty-api` |
| `beauty-engine/` → `:beauty-engine` | `engines/beauty-engine/` → `:engines:beauty-engine` |
| `mnn-core/` → `:mnn-core` | `engines/mnn-core/` → `:engines:mnn-core` |
| `sentencepiece/` → `:sentencepiece` | `engines/sentencepiece/` → `:engines:sentencepiece` |
| `runtime-core/` → `:runtime-core` | **不动** |
| `rootProject.name = "langchain4android"` | `rootProject.name = "polang"` |
| GitHub repo `littleseven/langchain4android` | `littleseven/polang`（Task 7） |
| 本地目录 `~/AndroidStudioProjects/langchain4android` | `~/AndroidStudioProjects/polang`（Task 8） |

---

### Task 1: 隔离工作区与基线验证

**Files:**
- 无（环境准备）

- [ ] **Step 1: 确认前置——main 含 R8 修复**

Run: `git -C /Users/guoshuai/AndroidStudioProjects/langchain4android log --oneline -3 main`
Expected: 列表中含 proguard/Ktor dontwarn 相关 commit；若无，先回合并再继续。

- [ ] **Step 2: 创建 worktree（遵循 using-git-worktrees）**

```bash
cd /Users/guoshuai/AndroidStudioProjects/langchain4android
git worktree add .worktrees/refactor-repo-restructure -b refactor/repo-restructure main
```

- [ ] **Step 3: 基线构建（后续所有 Task 的对照组）**

Run（cwd = `.worktrees/refactor-repo-restructure`）: `./gradlew :app:assembleDebug 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

> ⚠️ 本计划所有 Gradle/脚本命令的 cwd 均为该 worktree，绝不回主工作区构建。

---

### Task 2: `app/` → `androidApp/`（含模块名 `:app` → `:androidApp`）

**Files:**
- Move: `app/` → `androidApp/`（git mv）
- Modify: `settings.gradle.kts:28`

- [ ] **Step 1: git mv**

```bash
git mv app androidApp
```

- [ ] **Step 2: 改 settings.gradle.kts**

`settings.gradle.kts:28`：`include(":app")` → `include(":androidApp")`
（目录 `androidApp/` 与模块名 `:androidApp` 自动映射，无需 projectDir 行。）

- [ ] **Step 3: 构建验证**

Run: `./gradlew :androidApp:assembleDebug 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`（`androidApp/build.gradle.kts` 内 `project(":beauty-api")` 等引用此时仍指向未搬的引擎模块，合法）

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "refactor(repo): app/ → androidApp/，模块 :app → :androidApp（Phase 3 Task 2）"
```

---

### Task 3: 引擎模块迁入 `engines/` + rootProject 改名

**Files:**
- Move: `beauty-api/`、`beauty-engine/`、`mnn-core/`、`sentencepiece/` → `engines/`
- Modify: `settings.gradle.kts:27,29-33`
- Modify: `androidApp/build.gradle.kts:270-281`
- Modify: `engines/beauty-engine/build.gradle.kts:54-55`
- Modify: `runtime-core/build.gradle.kts:44-45`

- [ ] **Step 1: git mv 四模块**

```bash
mkdir engines
git mv beauty-api engines/beauty-api
git mv beauty-engine engines/beauty-engine
git mv mnn-core engines/mnn-core
git mv sentencepiece engines/sentencepiece
```

- [ ] **Step 2: settings.gradle.kts（6 处）**

```kotlin
rootProject.name = "polang"                 // L27，原 "langchain4android"
include(":androidApp")                       // L28，Task 2 已改
include(":engines:beauty-api")               // 原 include(":beauty-api")
include(":engines:beauty-engine")            // 原 include(":beauty-engine")
include(":runtime-core")                     // 不动
include(":engines:mnn-core")                 // 原 include(":mnn-core")
include(":engines:sentencepiece")            // 原 include(":sentencepiece")
```

- [ ] **Step 3: androidApp/build.gradle.kts 依赖路径（5 处，L270-281）**

```kotlin
implementation(project(":engines:beauty-api"))     // 原 ":beauty-api"
implementation(project(":engines:beauty-engine"))  // 原 ":beauty-engine"
implementation(project(":runtime-core"))           // 不动
implementation(project(":engines:mnn-core"))       // 原 ":mnn-core"
implementation(project(":engines:sentencepiece"))  // 原 ":sentencepiece"
```

注意 L276 `implementation(files("../runtime-core/libs/sherpa-onnx-1.13.3.aar"))` **不改**——`androidApp/` 与 `runtime-core/` 同在根级，相对路径仍正确。

- [ ] **Step 4: engines/beauty-engine/build.gradle.kts（L54-55）**

`project(":beauty-api")` → `project(":engines:beauty-api")`；`project(":mnn-core")` → `project(":engines:mnn-core")`

- [ ] **Step 5: runtime-core/build.gradle.kts（L44-45）**

同 Step 4 两条替换。

- [ ] **Step 6: 构建 + 单测验证**

Run: `./gradlew :androidApp:assembleDebug :runtime-core:testDebugUnitTest :androidApp:testDebugUnitTest 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "refactor(repo): 引擎模块迁入 engines/，rootProject 改名 polang（Phase 3 Task 3）"
```

---

### Task 4: 脚本 / CI / .gitignore 路径批量更新

**Files:**
- Modify: `.gitignore:68,84`
- Modify: `.github/workflows/ai-gate.yml:46,54-55`
- Modify: `scripts/` 下 23 个文件（清单与替换规则见下）

**替换规则（按序应用，防止二次替换；`androidApp/` 已含 `app/` 子串，必须锚定边界）：**

| 规则 | 模式 → 替换 | 说明 |
|------|------------|------|
| R1 | 行内独立路径 `app/`（前面是行首/空格/引号/`=`/`(`）→ `androidApp/` | 如 `app/build/outputs`、`app/src`、`app/keystore` |
| R2 | `:app:` → `:androidApp:` | Gradle task 路径（如 `:app:assembleDebug`） |
| R3 | `beauty-engine/` → `engines/beauty-engine/`；`mnn-core/` → `engines/mnn-core/`；`sentencepiece/` → `engines/sentencepiece/`；`beauty-api/` → `engines/beauty-api/` | 物理路径 |
| R4 | `:beauty-engine` → `:engines:beauty-engine`（其余三模块同） | Gradle 模块路径；**先 R4 后 R3**，避免 `engines/` 前缀被重复加 |
| R5 | `^app/`（正则/glob 语境，如 quick-compile.sh `^app/`、impact-analyzer.sh `app/*)`）→ `^androidApp/`、`androidApp/*)` | 前缀匹配语境 |

- [ ] **Step 1: .gitignore（2 处）**

`.gitignore:68` `app/keystore/` → `androidApp/keystore/`；`.gitignore:84` `beauty-engine/src/main/assets/models/llm/` → `engines/beauty-engine/src/main/assets/models/llm/`

- [ ] **Step 2: CI（3 处）**

`.github/workflows/ai-gate.yml:46` `app/build/reports/detekt/` → `androidApp/build/reports/detekt/`；L54 `app/build/reports/tests/` → `androidApp/...`；L55 `beauty-engine/build/reports/tests/` → `engines/beauty-engine/build/reports/tests/`

- [ ] **Step 3: scripts 逐文件应用 R1-R5**

文件清单（explore 盘点，括号为匹配行数）：`quick-compile.sh`(22)、`doc-sync-guardian.sh`(35)、`release-automation.sh`(12)、`impact-analyzer.sh`(9)、`build.sh`(8)、`change-report.sh`(6)、`ai-gate.sh`(3)、`auto-dev-loop.sh`(4)、`generate_tag_translations.py`(4)、`generate-wiki.sh`(4)、`test-generator.py`(2)、`kimi-cli.sh`(2)、`gen_admin_centroids.py`(2)、`merge_ml_kit_translations.py`(2)、`fix_camera_screen.py`(2)、`debug_offsets.py`(2)、`insert_test.py`(2)、`fix_pipeline.py`(1)、`check_doc_sync.py`(1)、`smart-commit.sh`(1)、`app-data-backup.sh`(1)、`check_alignment.py`(1)、`generate_from_user_image.py`(1)

逐文件用 Edit 修改（**不用全局 sed**——R1 边界判定需要人眼）。特例：
- `impact-analyzer.sh:72-83` `get_module_for_file()` 的 case 分支（`app/*)`、`beauty-engine/*)` 等）按 R5 改
- `doc-sync-guardian.sh:50-68` `DOC_MAP_PAIRS` 数组内路径前缀按 R1/R3 改
- `fix_pipeline.py:3`、`kimi-cli.sh:8` 的**绝对路径**本 Task 只改 `app/` 段，目录名 `langchain4android` 段留 Task 8
- `screenshot-diff.py`、`adb-resource-monitor.sh` 无引用，不动

- [ ] **Step 4: 残留扫描（必须为 0）**

Run:
```bash
grep -rnE '(^|[="'(])app/|:app:|(^|[^s])beauty-engine/|:beauty-engine|(^|[^s])mnn-core/|:mnn-core|(^|[^s])sentencepiece/|:sentencepiece|(^|[^s])beauty-api/|:beauty-api' scripts/ .github/ .gitignore
```
Expected: 无输出（或仅剩注释中明确的历史引用，逐条确认后保留）

- [ ] **Step 5: 语法 + 闭环验证**

Run: `for f in scripts/*.sh; do bash -n "$f" || echo "SYNTAX FAIL: $f"; done`
Expected: 无 FAIL
Run: `./scripts/ai-gate.sh`
Expected: 全绿（含 APK 找到并安装——验证 R1 替换正确性）

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor(repo): scripts/CI/gitignore 路径批量更新（Phase 3 Task 4）"
```

---

### Task 5: 文档批量更新

**Files:**
- Modify: `AGENTS.md`（根）、`AI_TOOLS.md`、`PRODUCT.md:485`、`README.md`、`CHANGELOG.md`
- Modify: `androidApp/AGENTS.md`、`engines/beauty-api/AGENTS.md`、`engines/beauty-engine/AGENTS.md`、`engines/mnn-core/AGENTS.md`、`engines/sentencepiece/AGENTS.md`、`runtime-core/AGENTS.md`
- Modify: `docs/02-ARCHITECTURE/MODULE_ARCHITECTURE.md`、`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`、`docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md`、`docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`、`docs/03-TECHNICAL-SPECS/VOICE_STACK.md`
- Modify: `docs-site/` 6 个 HTML（`index.html`、`getting-started.html`、`privacy-policy/index.html`、`en/` 同三名）
- Modify: `.claude/CLAUDE.md:1`
- Mark superseded: `docs/07-STANDARDS/REPO_REORGANIZATION_PLAN.md`（头部加 superseded 说明，指向总计划与本计划）

- [ ] **Step 1: 根 AGENTS.md**

项目名 `langchain4android` → `polang`（标题 L1、正文各处）；模块路径引用 `app/` → `androidApp/`、引擎模块加 `engines/` 前缀；模块 AGENTS.md 清单同步；`littleseven/langchain4android` → `littleseven/polang`（L297 附近）。**「基础库」实验定位段更新**：agent-core 已删，项目是 PoLang Monorepo（对齐总计划 §1）。

- [ ] **Step 2: 各模块 AGENTS.md**

`:app` → `:androidApp`、物理路径、依赖关系图（`engines/beauty-api/AGENTS.md` L9/68/110/121、`engines/beauty-engine/AGENTS.md` L4/42/108/536、`engines/mnn-core/AGENTS.md` L1/35/49、`runtime-core/AGENTS.md` L34/45/170、`engines/sentencepiece/AGENTS.md` L1/27-30/84、`androidApp/AGENTS.md` L1/161/184/216）。Gradle 命令示例同步改（如 `:mnn-core:assembleDebug` → `:engines:mnn-core:assembleDebug`）。

- [ ] **Step 3: README.md 重写（非简单改名）**

- 标题/项目名 → polang
- clone URL → `https://github.com/littleseven/polang.git`
- **删除/重写「作为库使用：langchain4android」整段（L162/183 JitPack 坐标 `com.github.littleseven.langchain4android:agent-core`）**——`:agent-core` 已删，库分发已不存在；badge（L2）同步删或改

- [ ] **Step 4: docs/ 交叉引用扫描修复**

Run: `./scripts/check_doc_sync.py 2>/dev/null; grep -rln 'langchain4android' docs/ | head -20`
逐文件改项目名与模块路径；`REPO_REORGANIZATION_PLAN.md` 头部加 `> **状态：superseded by docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md（Phase 3）**`；`CHANGELOG.md` 加 Phase 3 条目。

- [ ] **Step 5: docs-site 6 HTML + .claude/CLAUDE.md**

`langchain4android` 出现处（共约 20 处）改 `polang`；repo URL 同步。注意 `docs-site/docs/` 是 sync-docs.sh 生成物（.gitignore 忽略），只改手工维护的 6 个 HTML。

- [ ] **Step 6: 残留扫描**

Run: `grep -rln 'langchain4android' AGENTS.md AI_TOOLS.md PRODUCT.md README.md docs/ docs-site/ .claude/ androidApp/AGENTS.md engines/*/AGENTS.md runtime-core/AGENTS.md 2>/dev/null`
Expected: 仅剩 `LOCAL_ENVIRONMENT.md` 等处的**本地绝对路径**（留 Task 8）与明确历史引用

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "docs(repo): 项目改名 polang + 模块路径文档批量更新（Phase 3 Task 5）"
```

---

### Task 6: server 侧配置

**Files:**
- Modify: `server/src/main/kotlin/com/mamba/picme/server/config/AppConfig.kt:75`
- Modify: `server/AGENTS.md:170`、`server/run-local.sh:27`（注释）
- Modify: `scripts/claude-tunnel/tunnel.env.example:2`

- [ ] **Step 1: AppConfig.kt 默认值**

`githubIssueRepo = env("GITHUB_ISSUE_REPO", "littleseven/langchain4android")` → 默认值改 `"littleseven/polang"`

- [ ] **Step 2: server 文档/注释 + tunnel.env.example**

`server/AGENTS.md:170`「寄居在 `langchain4android/` 下」→ `polang/`；`run-local.sh:27` 注释路径；`tunnel.env.example:2` `CT_REPO_URL=https://github.com/guoshuai/langchain4android.git` → `.../polang.git`

- [ ] **Step 3: 提示用户部署侧动作（代码外，不阻塞）**

部署环境 `GITHUB_ISSUE_REPO` 环境变量需在 GitHub rename（Task 7）后同步；旧名有 GitHub 自动重定向兜底，不急。

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "chore(server): issue 目标仓库默认 polang + 文档路径更新（Phase 3 Task 6）"
```

---

### Task 7: GitHub repo rename（🔴 外向操作，需用户显式确认后执行）

**Files:** 无（远端操作）

- [ ] **Step 1: 用户确认**——rename 影响所有协作者 clone URL 与外部链接（GitHub 对旧名保留自动重定向兜底）

- [ ] **Step 2: rename + remote 更新**

```bash
gh repo rename polang --repo littleseven/langchain4android --yes
git remote set-url origin https://github.com/littleseven/polang.git
git ls-remote origin HEAD   # 验证连通
```
Expected: rename 成功、ls-remote 返回 HEAD hash

- [ ] **Step 3: 合并 worktree 回 main 并推送**

确认 `main` 与本分支无分叉后：`git checkout main && git merge --no-ff refactor/repo-restructure`，经用户确认后 `git push origin main`

---

### Task 8: 本地目录改名 + 出口验证（🔴 干扰性操作，无会话占用时执行）

**Files:**
- Modify: `AI_TOOLS.md:174,177`、`scripts/kimi-cli.sh:8`、`scripts/fix_pipeline.py:3`、`docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md`（绝对路径段）

- [ ] **Step 1: 本地目录改名（用户在场、无其他会话时）**

```bash
cd ~/AndroidStudioProjects
mv langchain4android polang
cd polang && git worktree repair   # 修复 .worktrees/* 内记录的绝对路径
```
Expected: `git worktree list` 全部正常；各 worktree `git status` 可用

- [ ] **Step 2: 绝对路径引用更新（4 处文件）**

`~/AndroidStudioProjects/langchain4android` → `~/AndroidStudioProjects/polang`

- [ ] **Step 3: 出口验证（总计划 3.6）**

Run（主工作区）: `./gradlew :androidApp:assembleDebug && adb push androidApp/build/outputs/apk/debug/polang-debug.apk /data/local/tmp/ && adb shell pm install -r /data/local/tmp/polang-debug.apk`
Expected: 安装成功；设备冒烟：相机预览/拍照、相册浏览、Chat 发消息、TAG 扫描入口各一次无 crash

- [ ] **Step 4: review 子 agent 审全量 diff**

派 `review` 子 agent（GLM）审 `main..refactor/repo-restructure` diff，确认零逻辑变更（除 AppConfig 默认值外），阻塞项清零后收口

- [ ] **Step 5: 总计划回写**

`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` Phase 3 各 checkbox 标 ✅ + 变更记录加一行

---

## Self-Review 记录（2026-08-07）

- **Spec coverage**：总计划 3.1→Task 2/3；3.2→Task 2/3；3.3→Task 4；3.4→Task 5；3.5→Task 6/7；3.6→Task 8。覆盖无缺口。
- **方案选择**：采用方案 B（模块名同步改）——总计划 §3.2 明示 `include(":androidApp")`，且 Phase 4 将引入 `:shared`，清晰命名收益大于 8 处 `project()` 引用 + 脚本 `:app:` 前缀的修改成本。
- **顺序依赖**：Task 2 先于 Task 3（app 移动独立可验证）；R4 先于 R3（防 `engines/` 前缀重复）；Task 7/8 必须最后（外向 + 干扰性）。
- **已知风险**：Task 4 R1 的边界判定不可全局 sed（`androidApp` 含 `app` 子串），逐文件 Edit + grep 归零验证兜底。
