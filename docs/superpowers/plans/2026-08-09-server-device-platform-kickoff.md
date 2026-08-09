# 未注册设备列表 · 平台字段 + 筛选

> 编排器(Claude Code+GLM)派发的 **K3 轴**任务。执行者:kimi-code。worktree:`.worktrees/feat-device-platform`。

## 目标
后台 `/admin/devices`「未注册设备」列表加 `platform` 字段(android|ios),支持 `?platform=` 筛选。

## 背景
- "未注册设备" = `AnonymousDevices` 表(`anonymous_device`,访客设备额度)。当前**无 platform 字段**。
- 先例:`LlmCallLogs.platform`(`Tables.kt:111`,`varchar("platform",16).nullable() // android|ios|null`),migration `006_llm_call_log_platform.sql`。
- platform 来源:请求头 `PLATFORM_HEADER`(`Application.kt:115`)→ `PlatformKey` attribute。

## 涉及文件(编排器已定位)

文件路径前缀:`server/src/main/kotlin/com/mamba/picme/server/` 或 `server/`

| 层 | 文件 | 改动 |
|---|---|---|
| 模型 | `db/Tables.kt:82` | `AnonymousDevices` 加 `platform` nullable |
| 迁移 | `migrations/010_anonymous_device_platform.sql`(新建) | `ALTER TABLE anonymous_device ADD COLUMN platform VARCHAR(16)`,参照 006;核对 `db/Migrations.kt:19,25` |
| 写入 | `auth/GuestService.kt:32-36` | `insert` 写 platform;签名加 `platform` 参数;调用方从 `PlatformKey` 传入 |
| 查询 | `admin/AdminQueries.kt:456-465` | `listDevices` 加 platform select + optional `where`;`DeviceRow` 加字段 + `platform:String?` 筛选参数 |
| 视图 | `admin/AdminViews.kt:185-225` | `devicesPage` 加「平台」表头列 + 行单元格 + 筛选 UI |
| 路由 | `/admin/devices` handler(`grep -rn "devicesPage\|/admin/devices" server/src/main`) | 解析 `?platform=` query,传给 listDevices + devicesPage |
| 测试 | `AdminRoutesTest.kt` / `AdminViewsTest.kt` | platform 字段 + 筛选用例 |

## 步骤
1. **DB**:`Tables.kt` `AnonymousDevices` 加 `platform` nullable;写 migration `010`(参照 `006_llm_call_log_platform.sql`)。
2. **写入**:`GuestService` insert 写 platform,方法签名加 `platform` 参数;定位调用方(匿名 chat 路径,`LlmRoute`/`ClaudeChatRoute`)从 `PlatformKey` 取 platform 传入。
3. **查询**:`AdminQueries.listDevices` 加 platform select + optional where(`platform:String?`);`DeviceRow` 加 platform。
4. **视图**:`AdminViews.devicesPage` 加「平台」表头列 + 行单元格 + 筛选 UI(`?platform=ios|android|all`,参照其他筛选页风格)。
5. **路由**:`/admin/devices` handler 解析 `?platform=` query,传给 listDevices + devicesPage。
6. **测试**:`AdminRoutesTest`/`AdminViewsTest` 加 platform + 筛选用例。

## 验收
- [ ] 新访客设备带 platform(android/ios)写入 DB
- [ ] 老数据 platform=null 不报错
- [ ] `/admin/devices` 显示平台列
- [ ] `?platform=ios|android` 筛选生效
- [ ] `AdminRoutesTest` / `AdminViewsTest` 通过(`./gradlew :server:test`)

## 约束
- nullable(老数据 null),与 `LlmCallLogs.platform` 一致。
- platform 非媒体数据,不入隐私红线(`[PRIVACY]`)。
- migration 序号 010(现有最大 009),避免重名。
- 项目硬规则:无全限定名(`com.mamba.picme.*`)、无通配符 import、lambda 显式命名、日志 tag `PoLang:[Server]`、i18n(若有 UI 文案)。
- **完成后**:在 worktree 内 commit(Conventional Commits,如 `feat(server): 未注册设备加平台字段与筛选`),报告改动文件清单 + 验收达成情况。**不要 push、不要合并到 main**(由编排器 review 后处理)。
