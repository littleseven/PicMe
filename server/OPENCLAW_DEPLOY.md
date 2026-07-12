# OpenClaw：发布 PicMe Server 指令

> 当用户说「发布 picme」「上线 picme」「deploy picme」时，按本指令执行。
> 服务器上的发布**一律走 `~/deploy-switch.sh`**（自带 healthz 校验 + 失败自动回滚），
> **不要**直接 `systemctl restart` 或手改 `~/picme-server` 目录。

## 发布

```bash
bash ~/deploy-switch.sh
```

## 解读输出

- `✅ 发布成功` → 报告：发布完成、healthz 正常。可顺带 `curl -s http://127.0.0.1:8080/healthz` 确认。
- `❌ 健康检查未通过，回滚` → **已自动回滚到上一版**。报告「新版启动失败、已回滚」，并从脚本末尾的 `journalctl` 输出里摘关键错误行给用户。
- `picme-server.new 不存在`（exit 2）→ 报告：现网没有待发布的新版本，需先在开发机/CI 跑 `deploy.sh` 把新版本 rsync 成 `.new`。

## 排查（用户要求深入时）

```bash
journalctl -u picme-api -n 50 --no-pager   # 服务日志
systemctl status picme-api                  # 服务状态
curl -s http://127.0.0.1:8080/healthz       # 本地探活（绕过 nginx）
ls -la ~/picme-server ~/picme-server.prev   # 看现网版本 / 回滚备份
```

## 边界

- 只读操作（查日志、status、healthz、ls）可自由执行。
- 写操作（restart、改目录、改 `server.env`）**只**通过 `deploy-switch.sh`；改配置类操作先和用户确认。
- 回滚备份在 `~/picme-server.prev`，每次发布会覆盖，只剩上一版。
