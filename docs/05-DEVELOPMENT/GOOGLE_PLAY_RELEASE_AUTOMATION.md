# Google Play 发布自动化（Release Automation）

> **版本**：1.0
> **最后更新**：2026-08-20
> **适用范围**：PoLang Android（`com.mamba.picme`）Google Play 发布链路
> **底层组件**：[gradle-play-publisher（GPP）4.1.1](https://github.com/Triple-T/gradle-play-publisher)（插件 `com.github.triplet.play`，官方维护模式但功能稳定，支持 AGP 9）

## 1. 总览

```
release-automation.sh          play-publish.sh                Play Console
（版本号/CHANGELOG/git tag）→（GPP 封装：上传/文案/晋升）→（审核 → 上架）
        │                            │
        └─ CI: .github/workflows/release.yml（tag v* 触发，只发 internal）
```

- **构建**：`./scripts/build.sh aab`（release 签名，`POLANG_RELEASE_*` 环境变量）
- **发布**：`./scripts/play-publish.sh`（GPP 封装，默认 internal 轨道）
- **商店文案 SSOT**：`androidApp/src/main/play/listings/{en-US,zh-CN,zh-TW}/`（自 `google-play-listing/` 迁移，文件名不变）
- **发布说明**：`androidApp/src/main/play/release-notes/<lang>/<track>.txt`（≤500 字符）

## 2. 一次性准备（已完成项打勾）

- [x] Play Console 创建 app 记录并**手动上传首版 AAB**（Play API 不能创建 app 记录，首传必须手动）
- [ ] GCP 项目启用 **AndroidPublisher API** → 创建 service account + JSON key
- [ ] Play Console → 用户和权限 → 邀请 service account 邮箱 → 授予「发布到测试轨道 + 管理商店文案」权限（**不给 production 直发权限**，防 CI 误发全量）
- [ ] 本地：`export POLANG_PLAY_SERVICE_ACCOUNT_JSON=/path/to/key.json`（**JSON key 禁止入库**）
- [ ] CI：GitHub Secrets 配置 `ANDROID_PUBLISHER_CREDENTIALS`（JSON 全文）+ `POLANG_RELEASE_STORE_PASSWORD` / `POLANG_RELEASE_KEY_ALIAS` / `POLANG_RELEASE_KEY_PASSWORD`
- [ ] 验证连通：`./gradlew :androidApp:bootstrapReleaseListing`（注意：会用线上内容重置本地 `play/` 目录）

> 若开发者账号为 2023-11 后注册的**个人账号**：production 权限需 closed testing 满 12 名测试者 / 14 天，提前规划。

## 3. 日常操作

### 3.1 发新版本到 internal（本地）

```bash
./scripts/release-automation.sh --type patch --aab        # 版本号+CHANGELOG+tag+构建 AAB
./scripts/play-publish.sh --notes /tmp/notes.txt          # 上传 internal（默认 completed）
```

### 3.2 发新版本到 internal（CI）

```bash
git tag v1.0.37 && git push origin v1.0.37                # tag 触发 release.yml
# 或 GitHub Actions 页面手动 workflow_dispatch（可选 track/status）
```

### 3.3 只同步商店文案

```bash
./scripts/play-publish.sh --listing-only
```

### 3.4 晋升到 production（人工，CI 禁止直发）

```bash
# 方式一：脚本晋升（推荐 draft，Console 复核后再 roll out）
./scripts/play-publish.sh --promote --from-track internal --track production --status draft

# 方式二：Play Console 手动 promote
```

### 3.5 分阶段发布与比例调整

```bash
./scripts/play-publish.sh --promote --from-track internal --track production \
    --status inProgress --user-fraction 0.1               # 10% 灰度
./scripts/play-publish.sh --update-rollout production --user-fraction 0.5
./scripts/play-publish.sh --promote --from-track production --track production \
    --status completed                                    # 收尾必须 completed（userFraction=1.0 非法）
```

## 4. 认证机制

| 场景 | 环境变量 | 内容 |
|------|----------|------|
| 本地 | `POLANG_PLAY_SERVICE_ACCOUNT_JSON` | service account JSON **文件路径**（`play {}` 块读取） |
| CI | `ANDROID_PUBLISHER_CREDENTIALS` | JSON **全文**（GPP 内置读取，不落盘） |

两者都未设置时 `play-publish.sh` 直接报错退出。

## 5. 硬约束与常见坑

| 约束 | 说明 |
|------|------|
| versionCode 严格递增 | 同 code 重传报错；冲突可选 GPP `ResolutionStrategy.AUTO` 自动抬 |
| 文案字符上限 | title 30 / short 80 / full 4000 / release notes 500（脚本预检 notes 长度） |
| `userFraction=1.0` 非法 | 灰度收尾改 `--status completed` |
| edit 并发冲突 | Console 手动改动会使未 commit 的 API 事务失效；发布窗口内不要在 Console 改文案 |
| production 首次被拒 | 新 app 需先在 internal/closed 轨道发过至少一版 |
| 审核时长 | internal 几分钟~几小时；production 数小时~2 天（新账号可达 7 天） |

## 6. CI 工作流（`.github/workflows/release.yml`）

- 触发：tag `v*` 推送，或 `workflow_dispatch`（可选 track/status）
- 护栏：`production` 轨道直接报错退出（Guard rail step）
- 环境：`environment: google-play`（可在 GitHub 设置里加人工审批门）
- 产物：AAB 作为 workflow artifact 保留 30 天

## 7. 相关文档

- 商店文案与 ASO：`google-play-listing/README.md`（含迁移说明）、`docs/superpowers/specs/2026-08-08-google-play-aso-design.md`
- 构建与签名：`scripts/build.sh` 头注释、`androidApp/build.gradle.kts` signing 段
- 版本发布：`scripts/release-automation.sh` 头注释
