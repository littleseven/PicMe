# 远程诊断 Worker（云主机）

常驻 poller：从 picme server 领诊断/修复任务，驱动 Claude Code（GLM 后端）headless 定位根因、推修复分支。egress-only，纯 git（不用 gh）。

## 部署到云主机

1. 把本目录（`scripts/diag-worker/`）弄到主机，或整仓 clone 后 `cd scripts/diag-worker`。
2. `cp worker.env.example worker.env`，填：
   - `DIAG_SERVER`（默认 `https://api.polang.net`）
   - `DIAG_WORKER_TOKEN`（**必须与 server 的 `DIAG_WORKER_TOKEN` 一致**）
   - `DIAG_REPO`（公开仓库 URL；已配 git/SSH）
3. 确认 PATH 上有 `claude`（Claude Code，GLM 后端）、`jq`、`curl`、`git`、`./gradlew`（修复自检用，可选）。
4. 运行：`bash poll.sh`（或用 tmux/systemd 保活，见下）。

## 成本

- poll 不调 LLM → 免费；云主机常驻 0.6%/天（Kimi 额度）。
- 推理走 GLM 按量。护栏：`DIAG_MAX_TURNS`（Claude Code 单次最大迭代）、`DIAG_PHASE_TIMEOUT`（单阶段超时）。
- 不用时直接 `rm -rf` 云主机即可停止 0.6%/天扣费（Kimi 无「暂停」，只能删除）。

## 保活（任选）

tmux：`tmux new -s diag 'bash poll.sh'`。
systemd：写一个 unit 跑 `poll.sh`，`Restart=always`。

## 冒烟（本地，验证胶水）

`bash smoke/run-smoke.sh` —— 用 stub claude + 本地仓 + stub HTTP 验证 compare_url / claim 解析 / claude 输出解析 / report_result。真 Claude Code + 真 server 在云主机上验证。

## 文件

| 文件 | 职责 |
|---|---|
| `poll.sh` | 常驻主循环：claim → 分发 diagnose/fix |
| `run-diagnose.sh` | clone @gitSha → `claude -p`（只读）→ 回根因 |
| `run-fix.sh` | 建分支 → `claude -p` 修复 → 自检 → commit → push → 回结果 |
| `lib.sh` | 共享工具（claim/result/compare_url/json_escape） |
| `prompts/*.md` | 诊断/修复 prompt 模板 |
| `worker.env.example` | 配置模板 |
