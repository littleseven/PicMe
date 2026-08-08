# iOS Ad-Hoc 自测分发页（api.polang.net /download/ios）

- 日期：2026-08-08
- 状态：已实现（待提交；Phase 5 IPA 上线前页面显示「暂无可用 iOS 版本」）
- 受众：开发者本人 + 小名单朋友（约 10–50 人）

## 背景与目标

Phase 5 iOS 应用骨架尚未启动，尚无 IPA / TestFlight。需要一个**自测分发入口**，让小名单设备能扫码安装 Ad-Hoc 签名的 IPA，并登记 UDID 以便重新签名。

iOS 无法像 Android 那样扫码直装：

- **Ad-Hoc**（选定方案）：≤100 设备/年，每个设备 UDID 须加入 provisioning profile。需用户**手动报 UDID**。
- Enterprise：仅企业内部，不适用。
- TestFlight：公开 beta，需 App Store Connect 配置，当前无 IPA。
- 扫码装 IPA：Apple 不支持（需 itms-services OTA + HTTPS plist + Ad-Hoc 签名）。

## 决策（逐项澄清结果）

- **UDID 采集方式 = 手动报 UDID**（放弃 mobileconfig 自动采集：需生成/签名 .mobileconfig，过重）。页内提供表单 + 获取 UDID 教程。
- **访问控制 = 公开链接**（放弃令牌/口令：受众信任，URL 即权限）。
- **存储 = COS PublicRead**，与 Android APK 一致（`cos.polang.net/ios/polang.ipa`）。

## 分发机制

```
扫码 / 点「安装」
  → itms-services://?action=download-manifest&url=<HTTPS manifest.plist>
  → iOS 拉 manifest.plist（https://api.polang.net/download/ios/manifest.plist）
  → plist 指向 IPA 直链（https://cos.polang.net/ios/polang.ipa）
  → 下载 + 校验设备 UDID 是否在 Ad-Hoc profile → 安装/拒绝
```

HTTPS 前置条件已满足（api.polang.net、cos.polang.net 均为 HTTPS）。

## 路由与鉴权

| 路由 | 方法 | 鉴权 | 说明 |
|------|------|------|------|
| `/download` | GET | public | 新增 UA 检测：iPhone/iPad/iPod → 302 `/download/ios` |
| `/download/ios` | GET | public | SSR 页（二维码编码 itms URL + 安装钮 + UDID 表单 + 教程 + 排错 + 联系作者） |
| `/download/ios/manifest.plist` | GET | public | 动态生成 OTA plist（`Application.Xml`）；IPA 不存在时 404 |
| `/download/ios/udid` | POST | public | 接收 udid + nickname，校验后入库，返回 SSR 成功/错误页 |

iOS 路由已加入 `Application.kt` `publicRoutes` 集合（auth interceptor 对其跳过 token 校验）。

## 数据模型

`IosUdidRegistrations`（`Tables.kt`，已接入 `Migrations.kt` 建表）：

| 列 | 类型 | 说明 |
|----|------|------|
| id | autoIncrement | 主键 |
| udid | varchar(64) | 去连字符、小写后的 UDID |
| nickname | varchar(128)? | 备注，可空 |
| createdAt | long | 毫秒时间戳 |
| status | varchar(16) | 默认 `pending` |

后台（`/admin/ios`）：IPA 版本/大小展示 + multipart 上传 + UDID 表格 + 一键导出（textarea / .txt 下载 / 复制全部）。

## 安全考量

- **UDID 校验**：去连字符后须为 25 或 40 位十六进制，否则 400。
- **SQL 注入**：UDID/nickname 经 Exposed 参数化 insert（非字符串拼接）。
- **XSS**：所有页用 kotlinx.html 类型化构建（自动转义）；成功/错误页**不回显**用户输入；`unsafe{raw{}}` 仅用于硬编码 SVG/CSS 常量。
- **manifest 注入**：plist 中 `version` 取自 COS 元数据（admin 设置，可信），`bundle-identifier` 为常量；IPA 不存在时 plist 返回 404，不生成空 manifest。
- **HTTPS**：manifest 与 IPA 均走 HTTPS（OTA 强制要求）。

## 已知限制（自测规模可接受，非阻塞）

- UDID 端点**无限流 / 无去重**：重复提交产生多行（admin 可清理）。自测受众 ~10–50 人，风险可控。
- plist `version` 未做 XML 转义（admin 可信输入，值形如 `1.0.34`）。

## 涉及文件（server/）

- `db/Tables.kt`、`db/Migrations.kt`（新表 + 建表）
- `cos/CosService.kt`（`uploadIpa` / `getIpaInfo` / `ipaPublicUrl`）
- `routes/DownloadRoute.kt`（iOS SSR + manifest + UDID + UA 跳转；深色 CSS 抽常量复用）
- `admin/AdminQueries.kt`、`admin/AdminRoutes.kt`、`admin/AdminViews.kt`（`/admin/ios` 后台）
- `Application.kt`（`publicRoutes` + `iosDownloadRoute` 注册）

## 验证

- `./gradlew -p server compileKotlin` ✅
- `./gradlew -p server build -x test`（含 ktlint / detekt / checkNoFullyQualifiedName）✅
- `./gradlew -p server test` ✅

## 后续

Phase 5 产出首个 IPA 后：admin 经 `/admin/ios/upload` 上传 → 页面自动显示版本/二维码 → 登记 UDID 的设备加入 profile 重新签名 → 用户扫码安装。
