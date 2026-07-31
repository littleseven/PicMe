# 诊断确认三选项（保守修复 / 修复待审 / 自动修复）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans（本环境子代理不可用，inline 执行）。步骤用 `- [ ]` 跟踪。

**Goal:** 诊断确认由 2 选项（push/pr）扩到 3 选项（push 保守 / pr 真 PR / auto 合并 main），三端联动。

**Architecture:** `mode` 协议值 `push|pr|auto`。app 加第三按钮 + 三语文案；server `confirmFix` 放行 auto；worker `run-fix.sh` 按 mode 分支（pr 用 `gh pr create` 失败不降级、auto 自检过才 ff-merge main 失败降级留分支）。GitHub 操作全在 worker（用户在云主机配 gh + GITHUB_TOKEN）。

**Tech Stack:** Kotlin/Ktor（server）、Jetpack Compose + strings.xml（app）、Bash + gh（worker）。

**关联 spec：** `docs/superpowers/specs/2026-07-31-diag-three-fix-modes-design.md`

**前置：** worktree 隔离（复杂跨端 feature）。基线含 spec 提交 `92a200ce`。

---

## 文件结构

| 文件 | 责任 | 动作 |
|------|------|------|
| `server/.../diag/DiagService.kt` | confirmFix 状态机 | require 加 `auto` |
| `server/.../admin/AdminViews.kt` | 详情 fixMode 中文展示 | 映射 push→保守/pr→待审/auto→自动 |
| `server/src/test/.../DiagServiceTest.kt` | confirmFix 测试 | 加 auto 接受/拒绝 |
| `app/.../features/chat/ChatScreen.kt` | 根因气泡按钮 | 加第三个「自动修复」按钮 |
| `app/.../features/chat/DiagController.kt` | onResolved 注释 | 注释补 auto |
| `app/src/main/res/values*/strings.xml` ×4 | 文案 | 改 push/pr + 新增 diag_sheet_auto |
| `scripts/diag-worker/run-fix.sh` | mode 分支 | case push/pr/auto 重写 |
| `scripts/diag-worker/lib.sh` | gh auth helper | 加 `gh_auth` |
| `scripts/diag-worker/worker.env.example` | 配置模板 | 加 GITHUB_TOKEN |
| `scripts/diag-worker/README.md` | 部署文档 | gh 安装 + token 步骤 |
| `scripts/diag-worker/smoke/run-smoke.sh` | worker 冒烟 | 覆盖三 mode |

**验证命令：** server `./gradlew -p server test`；worker `bash scripts/diag-worker/smoke/run-smoke.sh`；app 需设备（androidTest，标注）。

---

### Task 1: server —— confirmFix 接受 auto + admin 详情展示

**Files:** Modify `server/.../diag/DiagService.kt`；`server/.../admin/AdminViews.kt`；Test `DiagServiceTest.kt`

- [ ] **Step 1: 写失败测试**

在 `DiagServiceTest.kt` 的 `confirmFix rejects wrong owner and non-DIAGNOSED state` 测试后追加：

```kotlin
@Test
fun `confirmFix accepts auto mode and stores it`() {
    TestDb.init(DiagJobs)
    val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
    runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
    assertTrue(runBlocking { DiagService.confirmFix(id, "o", "auto") })
    val row = transaction(Db.instance) { DiagJobs.selectAll().where { DiagJobs.id eq id }.single() }
    assertEquals(DiagStatus.FIX_REQUESTED.name, row[DiagJobs.status])
    assertEquals("auto", row[DiagJobs.fixMode])
}

@Test
fun `confirmFix rejects unknown mode`() {
    TestDb.init(DiagJobs)
    val id = runBlocking { DiagService.createJob("o", null, "d", "{}", "sha") }
    runBlocking { DiagService.submitDiagnosis(id, "rc", DiagStatus.DIAGNOSED, null) }
    try {
        runBlocking { DiagService.confirmFix(id, "o", "weird") }
        fail("expected IllegalArgumentException for unknown mode")
    } catch (e: IllegalArgumentException) {
        // ok
    }
}
```

若 `DiagServiceTest` 未 import `fail`，加 `import org.junit.Assert.fail`。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.confirmFix*auto*"`
Expected: 第一个测试 FAIL（confirmFix require 拒绝 auto 抛 IllegalArgumentException）。

- [ ] **Step 3: 放行 auto**

`DiagService.kt` `confirmFix` 内：

```kotlin
require(mode == "push" || mode == "pr") { "mode must be push or pr" }
```

改为：

```kotlin
require(mode == "push" || mode == "pr" || mode == "auto") { "mode must be push, pr or auto" }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.diag.DiagServiceTest.confirmFix*"`
Expected: PASS（auto 接受、weird 拒绝）。

- [ ] **Step 5: admin 详情 fixMode 中文映射**

`AdminViews.kt` `diagDetailPage` 的 statCard：

```kotlin
statCard("修复方式", d.fixMode ?: "—")
```

改为：

```kotlin
statCard("修复方式", when (d.fixMode) { "push" -> "保守"; "pr" -> "待审"; "auto" -> "自动"; else -> d.fixMode ?: "—" })
```

- [ ] **Step 6: 跑 server 全量 + 提交**

Run: `./gradlew -p server test`
Expected: BUILD SUCCESSFUL。

```bash
git add server/src/main/kotlin/com/mamba/picme/server/diag/DiagService.kt \
        server/src/main/kotlin/com/mamba/picme/server/admin/AdminViews.kt \
        server/src/test/kotlin/com/mamba/picme/server/diag/DiagServiceTest.kt
git commit -m "feat(diag): confirmFix 放行 auto mode + 详情 fixMode 中文映射"
```

---

### Task 2: app —— 第三按钮 + 三语文案

**Files:** Modify `app/.../features/chat/ChatScreen.kt`；`DiagController.kt`；`values*/strings.xml` ×4

- [ ] **Step 1: 三语文案（4 文件）**

`values/strings.xml`（EN）：
```xml
<string name="diag_sheet_push">Conservative fix</string>
<string name="diag_sheet_pr">Fix for review</string>
<string name="diag_sheet_auto">Auto-fix</string>
```
`values-zh/strings.xml` 与 `values-zh-rCN/strings.xml`：
```xml
<string name="diag_sheet_push">保守修复</string>
<string name="diag_sheet_pr">修复待审</string>
<string name="diag_sheet_auto">自动修复</string>
```
`values-zh-rTW/strings.xml`：
```xml
<string name="diag_sheet_push">保守修復</string>
<string name="diag_sheet_pr">修復待審</string>
<string name="diag_sheet_auto">自動修復</string>
```
（改 `diag_sheet_push`/`diag_sheet_pr` 的值，紧随其后新增 `diag_sheet_auto`。）

- [ ] **Step 2: 加第三按钮**

`ChatScreen.kt:996-1003` 的 `Row { ... }`，在 pr 按钮后加 auto 按钮：

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { onDiagConfirm(dc.jobId, "push") }) {
        Text(stringResource(R.string.diag_sheet_push))
    }
    Button(onClick = { onDiagConfirm(dc.jobId, "pr") }) {
        Text(stringResource(R.string.diag_sheet_pr))
    }
    Button(onClick = { onDiagConfirm(dc.jobId, "auto") }) {
        Text(stringResource(R.string.diag_sheet_auto))
    }
}
```

（`onDiagConfirm` → `viewModel.confirmDiagnosis(mode)` 已透传 mode，无需改 ViewModel/DiagClient。）

- [ ] **Step 3: DiagController 注释**

`DiagController.kt:11` 注释 `// "push" | "pr" | null(取消)` 改为 `// "push" | "pr" | "auto" | null(取消)`。

- [ ] **Step 4: 编译 app（验文案/按钮无误）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（无需设备）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt \
        app/src/main/java/com/mamba/picme/features/chat/DiagController.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat(diag): app 诊断确认加「自动修复」第三按钮 + 三语文案"
```

> Compose UI 测试（3 按钮渲染/点击）放 `app/src/androidTest`（需设备，本机不强求；若手头有设备可补 `DiagConfirmButtonsTest`）。

---

### Task 3: worker —— run-fix.sh 三 mode 分支 + gh

**Files:** Modify `scripts/diag-worker/run-fix.sh`；`lib.sh`；`worker.env.example`；`README.md`

- [ ] **Step 1: lib.sh 加 gh_auth helper**

在 `lib.sh` 的 `load_env()` 之后新增：

```bash
# 用 GITHUB_TOKEN 给 gh 鉴权（pr/auto 需要）。未配 token 则跳过（push 模式不需要）。
gh_auth() {
  if [ -z "${GITHUB_TOKEN:-}" ]; then return 1; fi
  if command -v gh >/dev/null 2>&1; then
    printf '%s' "$GITHUB_TOKEN" | gh auth login --with-token >/dev/null 2>&1
  else
    return 1
  fi
}
```

- [ ] **Step 2: 重写 run-fix.sh 的 commit/push + mode 分支**

把 `run-fix.sh` 第 29 行起（`git add -A` 到文件末尾的 `report_result`）替换为：

```bash
git -C "$repo" add -A
git -C "$repo" commit --quiet -m "fix(diag): 远程诊断自动修复 job #$jobId" >/dev/null 2>&1 || true
wlog "job #$jobId push $branch"
if ! run_with_timeout 120 git -C "$repo" push --quiet origin "$branch" >/dev/null 2>&1; then
  wlog "job #$jobId push FAILED"
  report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIX_FAILED\",\"error\":\"push failed\"}"; exit 0
fi

status="FIXED"; [ "$tested" = "false" ] && status="FIXED_UNVERIFIED"

case "$mode" in
  push)
    wlog "job #$jobId mode=push (保守：仅推分支)"
    report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested}"
    ;;
  pr)
    wlog "job #$jobId mode=pr (待审：建真 PR)"
    if ! gh_auth; then
      report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIX_FAILED\",\"fixBranch\":\"$branch\",\"error\":\"gh not configured (GITHUB_TOKEN missing or gh absent)\"}"; exit 0
    fi
    pr_url="$(cd "$repo" && gh pr create --base "$DIAG_BASE_BRANCH" --head "$branch" \
      --title "fix(diag): 远程诊断自动修复 job #$jobId" \
      --body "由远程诊断 worker 自动修复。根因见 server /admin/diag job #$jobId。" 2>/dev/null)"
    if [ -n "$pr_url" ]; then
      wlog "job #$jobId PR created: $pr_url"
      report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested,\"compareUrl\":\"$(printf '%s' "$pr_url" | json_escape)\"}"
    else
      wlog "job #$jobId gh pr create FAILED"
      report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIX_FAILED\",\"fixBranch\":\"$branch\",\"error\":\"gh pr create failed\"}"
    fi
    ;;
  auto)
    wlog "job #$jobId mode=auto (自动：自检过则 ff-merge main)"
    if [ "$tested" = "true" ] && \
       git -C "$repo" fetch --quiet origin "$DIAG_BASE_BRANCH" && \
       git -C "$repo" checkout --quiet -B "$DIAG_BASE_BRANCH" "origin/$DIAG_BASE_BRANCH" && \
       git -C "$repo" merge --ff-only "$branch" && \
       run_with_timeout 120 git -C "$repo" push --quiet origin "$DIAG_BASE_BRANCH"; then
      wlog "job #$jobId auto-merged to $DIAG_BASE_BRANCH"
      report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIXED\",\"fixBranch\":\"$DIAG_BASE_BRANCH\",\"tested\":true}"
    else
      wlog "job #$jobId auto-merge aborted (自检失败/ff冲突/push失败)，留 $branch 分支"
      git -C "$repo" checkout --quiet "$branch" 2>/dev/null || true
      report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"FIXED_UNVERIFIED\",\"fixBranch\":\"$branch\",\"tested\":$tested}"
    fi
    ;;
  *)
    wlog "job #$jobId unknown mode=$mode，按 push 处理"
    report_result "$jobId" "{\"phase\":\"fix\",\"status\":\"$status\",\"fixBranch\":\"$branch\",\"tested\":$tested}"
    ;;
esac
```

- [ ] **Step 3: worker.env.example 加 GITHUB_TOKEN**

在 `DIAG_BASE_BRANCH=main` 行后加：

```
GITHUB_TOKEN=                   # GitHub PAT（需 repo 写权限）；pr/auto 模式必需，留空则仅 push 可用
```

- [ ] **Step 4: README 补 gh 安装 + 配置**

`scripts/diag-worker/README.md` 加一节：

```markdown
## pr / auto 模式（建真 PR / 合并 main）

需要 `gh` 与 `GITHUB_TOKEN`（保守 push 模式不需要）：

1. 安装 gh：`sudo apt update && sudo apt install gh`（Ubuntu）。
2. 在 GitHub 生成 PAT（repo 写权限，classic token 即可）。
3. `worker.env` 填 `GITHUB_TOKEN=ghp_xxx`。
4. 重启 worker（`poll.sh`）。worker 首次跑 pr/auto 时自动 `gh auth login --with-token`。

未配置时：push 模式正常；pr/auto 触发会立即 `FIX_FAILED`（error=gh not configured）。
```

- [ ] **Step 5: 更新 smoke（mock gh/git 覆盖三 mode）**

`smoke/run-smoke.sh` 加三个 mode 场景（stub `gh`、用本地 git 临时 repo）：覆盖 push（仅分支）/ pr（stub gh 成功→compareUrl 是 PR url；stub gh 失败→FIX_FAILED）/ auto（stub tested=true + ff 成功→fixBranch=main；tested=false→FIXED_UNVERIFIED 留分支）。若现有 smoke 框架难 mock gh，至少加 push 分支断言 + pr/auto 的 FIX_FAILED（未配 gh）路径。

- [ ] **Step 6: 跑 smoke + 提交**

Run: `bash scripts/diag-worker/smoke/run-smoke.sh`
Expected: 各场景断言通过（或标注需云主机环境的部分）。

```bash
git add scripts/diag-worker/run-fix.sh scripts/diag-worker/lib.sh \
        scripts/diag-worker/worker.env.example scripts/diag-worker/README.md \
        scripts/diag-worker/smoke/run-smoke.sh
git commit -m "feat(diag-worker): run-fix 三 mode 分支（push/pr 真PR/auto 合并main）+ gh"
```

---

### Task 4: 部署 + 用户配置 + 端到端验证

- [ ] **Step 1: server 部署 prod**

Run: `./server/deploy.sh`
Expected: `✅ 发布成功`，`curl https://api.polang.net/healthz` ok。
app 新 APK 装机（`./gradlew :app:assembleDebug && adb install -r ...`）。

- [ ] **Step 2: worker 脚本同步到云主机（用户）**

云主机 `cd /root/.openclaw/workspace/langchain4android && git pull`（拉取 run-fix.sh/lib.sh/worker.env.example 改动），重启 `tmux` 里的 `poll.sh`。

- [ ] **Step 3: 用户配 gh + GITHUB_TOKEN（用户）**

按 README：云主机 `sudo apt install gh`；`worker.env` 填 `GITHUB_TOKEN=ghp_xxx`；重启 worker。`gh auth status` 验证。

- [ ] **Step 4: 端到端验证三 mode**

app 报一个测试诊断 → 出根因 → 分别试：
- 保守修复：worker 推 `diag-fix/<id>`，app 显示分支名，不建 PR。
- 修复待审：worker 建 GitHub PR，app 显示 PR 链接。
- 自动修复：worker 自检过则合并 main（`/admin/diag` 详情 fixBranch=main），app 显示已合并。

每步在 `/admin/diag` 看状态/fixBranch/compareUrl。

- [ ] **Step 5: 合并 worktree 回 main + push + 部署**

worktree 内完成 Task 1-3 后，按 finishing-a-development-branch 合并 main、push、部署（Task 4 Step 1）。

---

## 自审（writing-plans self-review）

**1. Spec 覆盖**：
- mode push/pr/auto（决策1）→ Task 1（server require）+ Task 2（app 按钮）+ Task 3（worker case）。✓
- 三语文案（决策2）→ Task 2 Step 1（4 文件）。✓
- pr 不降级 FIX_FAILED（决策3）→ Task 3 Step 2 pr 分支。✓
- auto 自检过 ff-merge、失败降级（决策4）→ Task 3 Step 2 auto 分支。✓
- worker 执行 + gh 配置（决策5、7）→ Task 3 gh_auth + worker.env + README + Task 4 Step 3。✓
- admin fixMode 展示 → Task 1 Step 5。✓

**2. Placeholder**：smoke Step 5 给了场景描述但未给完整 mock 代码——标注"按现有 smoke 框架"，执行时若框架难 mock gh，至少覆盖 FIX_FAILED 路径（可执行判定）。其余步骤均含完整代码。

**3. 类型一致性**：mode 值 "push"/"pr"/"auto" 全链路一致（app onDiagConfirm → confirmDiagnosis → DiagClient.confirmFix → server confirmFix → worker run-fix case）。✓
