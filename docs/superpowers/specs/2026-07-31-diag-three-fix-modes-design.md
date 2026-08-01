# 诊断确认三选项（保守修复 / 修复待审 / 自动修复）

> **状态**：⛔ SUPERSEDED（2026-08-01）——诊断模式已合并入 AI 工程师模式，见
> `2026-08-01-ai-engineer-diag-merge-design.md`。本文仅作历史存档。
> 注：交付三档（push/pr/auto）部分仍被 claude-deliver 复用，仍然有效。

> **日期**：2026-07-31
> **状态**：已确认，待实现
> **范围**：跨端 —— `app/`（chat-UI）+ `server/`（confirm 状态机）+ `scripts/diag-worker/`（修复交付）
> **关联**：`docs/superpowers/specs/2026-07-30-remote-diagnosis-design.md`（远程诊断主设计）；`2026-07-31-diag-admin-actions-design.md`（管理操作）

## 1. 背景与目标

现状诊断确认只有两个选项（push / pr），其中 pr 仅回传 GitHub compare URL、不开真 PR。用户希望扩到**三个交付方式**：

1. **保守修复**（= 现 push）：worker 改码 + push `diag-fix/<jobId>` 分支，用户手动 fetch 合并。
2. **修复待审**（升级现 pr）：worker 改码 + push 分支 + **用 `gh` 建真 GitHub PR**（不再只给 compare URL）。
3. **自动修复**（新增 auto）：worker 改码 + 自检过则 **ff-merge 进 main 并 push origin main**，自检失败/冲突则降级留分支。

## 2. 已确认决策

1. **mode 值**：`push` | `pr` | `auto`。沿用 `push`/`pr` 名（兼容旧数据），`pr` 语义升级，新增 `auto`。
2. **文案（三语）**：保守修复 / 修复待审 / 自动修复（`diag_sheet_push` / `diag_sheet_pr` / `diag_sheet_auto`）。
3. **pr 失败策略**：**不降级**。`gh pr create` 失败（未配 token / gh 不可用 / 超时）→ `FIX_FAILED`。强制 worker 正确配置 gh。
4. **auto 门槛**：worker 自检（`./gradlew -p server test`，现有）**通过**才 `merge --ff-only` 进 main 并 push origin main；**自检失败或 ff 冲突 → 不合并 main**，标 `FIXED_UNVERIFIED`，留 `diag-fix/<jobId>` 分支供人工处理。
5. **GitHub 操作执行端**：**worker**（已 clone repo）。worker 装 `gh` + `GITHUB_TOKEN`。配置由用户在云主机完成（egress-only，开发者无法 SSH）。
6. main 分支当前**无保护**，可直推（auto 技术可行）。

## 3. 协议（mode 三值）

- `DiagService.confirmFix`：`require(mode == "push" || "pr" || "auto")`（现仅 push|pr）。
- `DiagRoute.DiagConfirmRequest`：`mode: String`（不改签名）。
- `DiagClient.confirmFix(token, jobId, mode)`：不改签名。
- 旧 `fixMode=push|pr` 数据无影响（值不变，pr 行为升级）。

## 4. app

- `DiagController.onResolved`：`(String?) -> Unit`，值 `"push"|"pr"|"auto"|null`（注释更新）。
- `ChatScreen` 根因气泡：2 按钮 → **3 按钮**，`Button(onClick={onDiagConfirm(dc.jobId,"push"|"pr"|"auto")})`，文案 `R.string.diag_sheet_push|pr|auto`。
- `strings.xml` 三语同步新增/改：`diag_sheet_push`=保守修复、`diag_sheet_pr`=修复待审、新增 `diag_sheet_auto`=自动修复（EN: Conservative fix / Fix for review / Auto-fix；繁体同步）。
- 现有 `diag_sheet_push/pr` key 沿用（改 value），避免迁移。

## 5. server

- `DiagService.confirmFix` require 加 `auto`。
- `/admin/diag` 详情页 `fixMode` 展示映射：push→保守、pr→待审、auto→自动（`diagDetailPage` 的 statCard「修复方式」）。

## 6. worker（`run-fix.sh` mode 分支重写）

公共：改码（Claude）+ 自检（`./gradlew -p server test` → `tested`）+ `git add -A && git commit` + `git push origin diag-fix/<jobId>`（失败→FIX_FAILED，现有）。

之后按 mode 分支：

```bash
case "$mode" in
  push)  # 保守：只推分支
     status=$(tested? FIXED : FIXED_UNVERIFIED)
     report fixBranch=diag-fix/$id, status, tested ;;
  pr)    # 待审：建真 PR；失败不降级 → FIX_FAILED
     if gh pr create --base "$DIAG_BASE_BRANCH" --head "$branch" --title "fix(diag): job #$id" ; then
        pr_url=$(gh pr view "$branch" --json url -q .url)
        report fixBranch, status, tested, compareUrl=pr_url   # 复用 compareUrl 字段放 PR URL
     else
        report FIX_FAILED, error="gh pr create failed" ;;
     fi ;;
  auto)  # 自动：自检过才 ff-merge main
     if [ "$tested" = true ] && git fetch origin "$DIAG_BASE_BRANCH" \
        && git checkout -B main "origin/$DIAG_BASE_BRANCH" \
        && git merge --ff-only "$branch" && git push origin main ; then
        report fixBranch=main, status=FIXED, tested
     else
        # 自检失败 / ff 冲突 / push 失败 → 不合并 main，留 diag-fix 分支
        report fixBranch=diag-fix/$id, status=FIXED_UNVERIFIED, tested ;;
     fi ;;
esac
```

- `gh` 需先 `gh auth login --with-token <<<"$GITHUB_TOKEN"`（lib.sh load_env 后执行一次）。
- `compareUrl` 字段复用放 PR URL（app 气泡点击进 PR，体验一致）。

## 7. 凭证与配置（用户在云主机执行，开发者给指令）

- 云主机装 `gh`（apt/二进制）。
- `worker.env` 加 `GITHUB_TOKEN=<PAT, 需 repo 写权限>`。
- `worker.env.example` + `scripts/diag-worker/README.md` 补步骤。
- ⚠️ **配好前 pr/auto 选项会 `FIX_FAILED`**（保守 push 不受影响、始终可用）。

## 8. 安全

- auto 仅以 **server JVM 单测**为门槛（app 测试需设备、worker 跑不了）；merge 用 `--ff-only`，冲突直接降级、不强行合。
- auto push origin main：main 无保护可直推；`GITHUB_TOKEN` 需写权限，token 存云主机 `worker.env`（不进 repo）。
- pr/auto 失败均留 `diag-fix/<jobId>` 分支，可人工收尾或在 `/admin/diag` 激活重跑。

## 9. 测试

- **server**（`DiagServiceTest`）：`confirmFix` 接受 `auto`，拒绝非法值。
- **app**（`androidTest`）：根因气泡 pending 时渲染 3 按钮、点击触发对应 mode 的 confirm。
- **worker**（`smoke`）：mock `gh`/git，覆盖 push（只分支）/ pr（gh 成功→PR url，gh 失败→FIX_FAILED）/ auto（tested 真+ff 成功→main，tested 假→UNVERIFIED 留分支）。

## 10. 不做（YAGNI）

- 不在 server 端做任何 GitHub 操作（用户选 worker 执行）。
- auto 不跑 app 测试、不处理非 ff 冲突（降级人工）。
- 不改 mode 命名（沿用 push/pr/auto，兼容旧数据）。

## 11. 验收

- [ ] app 根因气泡出现「保守修复 / 修复待审 / 自动修复」三按钮，点击分别 confirm push/pr/auto。
- [ ] server `confirmFix` 接受 auto（单测）；admin 详情展示修复方式中文。
- [ ] worker：push 只推分支；pr 建真 PR（gh 失败→FIX_FAILED）；auto 自检过 ff-merge main、失败留分支 UNVERIFIED（smoke 覆盖）。
- [ ] `worker.env.example` + README 含 gh 安装与 GITHUB_TOKEN 配置步骤。
- [ ] 三语文案同步（values / values-zh-rCN / values-zh-rTW）。
- [ ] 配好 gh 前：保守修复可用，pr/auto 会 FIX_FAILED（符合预期）。
