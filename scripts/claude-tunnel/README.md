# claude-tunnel（Phase 1）

chisel wss 反向隧道 + Claude 流式网关。让外部经 api.polang.net 实时、流式、多轮驱动 KimiClaw 上的 Claude Code（GLM 后端）。spec：`docs/superpowers/specs/2026-07-31-claude-tunnel-chat-design.md`；plan：`docs/superpowers/plans/2026-07-31-claude-tunnel-phase1.md`。

## 拓扑
```
app → api.polang.net(nginx /v1/claude-chat，Phase 2) → 127.0.0.1:3001(chisel 隧道口)
    → [chisel wss 隧道] → KimiClaw 127.0.0.1:3000(网关) → claude --resume(GLM)
```
Phase 1 验收不含 app/server：直接 `curl 127.0.0.1:3001/chat`。

## 部署
1. **PoLang 服务器**：`deploy/install-chisel.sh` → 配 `/etc/picme/tunnel.env`(`CHISEL_PSK`) → 装 `chisel-server.service` → nginx 加 `/tunnel`(见 `deploy/nginx-tunnel.conf`)。见 plan Task 5。
2. **KimiClaw**：落 `gateway/` + venv(`pip install -r gateway/requirements.txt`) → 配 `tunnel.env`(同 PSK) → 装 `gateway.service` + `chisel-client.service`。见 plan Task 6。claude 权限白名单 = `gateway/claude-settings.json`（`server.py` 用 `--settings` 指定该文件，随 `gateway/` 落盘即生效；`CT_SETTINGS` 可覆盖路径）。
3. **验证**：`curl http://127.0.0.1:3001/healthz`(服务器上) → `ok`。

## 三层鉴权（spec §9）
- chisel PSK（client→server 建隧道）
- 端口只绑 127.0.0.1（3001/3000/8090）
- （Phase 2 补 Ktor X-App-Token）

## 网关本地开发
```bash
cd gateway && python3 -m pytest -v          # 翻译 + session 单测
bash smoke/run-smoke.sh                      # mock claude 端到端
```

## 已知限制（Phase 1）
- 网关以 root + `IS_SANDBOX=1` 跑；claude 权限走 `gateway/claude-settings.json` 的 `permissions.allow` 白名单（`--settings` 指定，**非** `--dangerously-skip-permissions`/bypass——claude CLI 硬禁 root+bypass，见 commit `df354bec`）。spec §10 root 风险，接受。
- deliver 仅 push 模式（pr/auto 二期）。
- 无并发隔离（多 session 共享 KimiClaw 资源，Phase 1 单用户够用）。
- claude 以 root 运行需 `IS_SANDBOX=1`（网关 `server.py` 已设；手动实测也要带）。
