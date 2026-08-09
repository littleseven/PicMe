# 派发规格：shared Koog 路径补 X-Platform header（修 iOS 平台识别 bug）

- **日期**：2026-08-09
- **轴**：K3（`shared/src/**/*.kt`）
- **类型**：bug 修复（KMP，4 源集）
- **派发目标**：kimi-code（K3/GLM）
- **审查方**：Claude Code（orchestrator）

## 背景

服务端 0.9.3 上线 `anonymous_device.platform` 列 + 按 `X-Platform` header 记录平台。线上 8 行历史数据经 backfill 已补（7 android / 1 ios）。但**未来流量仍有 bug**：

iOS 测试机在「未注册用户列表」里 `platform` 仍为 NULL。根因不在服务端（服务端 0.9.3 读 `X-Platform` 已正确），而在**客户端 shared Koog 推理路径漏发 X-Platform header**。

### 根因（已定位，勿改方向）

`shared` `commonMain` 的两个 Koog agent 各有一个 `buildGatewayHeaders()`，**完全相同**，只注入 `X-App-Token` + `X-Device-Id`，**漏 `X-Platform`**：

- `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogChatAgent.kt:223-228`
- `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogReActAgent.kt:342-347`

```kotlin
private fun buildGatewayHeaders(): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    config.gatewayToken?.takeIf { it.isNotBlank() }?.let { token -> headers["X-App-Token"] = token }
    if (config.deviceId.isNotBlank()) headers["X-Device-Id"] = config.deviceId
    return headers                       // ← 缺 X-Platform
}
```

### 影响范围（比「仅 iOS」更大）

凡走 shared Koog 路径的流量都漏 X-Platform：

- **iOS 全部** chat/camera 推理（iOS 只有 Koog 路径，无原生 client 兜底）→ 全部 NULL。
- **Android agent-chat**（`ChatViewModel.kt:1187` → `orchestrator.remoteChatEngine.streamChat` → `RemoteChatEngine:295` → `KoogChatAgent`）→ 也 NULL。
- Android 的**原生 client 路径**（`ChatViewModel.kt:352` → `ClaudeChatClient`，`ClaudeChatClient.kt:80/90` `.addHeader("X-Platform","android")`）已正确发，**无需改**。

结论：修 `buildGatewayHeaders()` 一处，iOS + Android agent 两条路径同时修好。

## 服务端约定（勿改服务端，仅对齐）

- header 名：`X-Platform`（`AppTokenAuth.kt:18 PLATFORM_HEADER = "X-Platform"`）。
- 值：**小写** `"android"` / `"ios"`（与 Android 原生 client `ClaudeChatClient.kt:80` 的 `"android"` 一致；服务端 `Application.kt:115-116` 原样存，不做归一化）。
- guest（无 token）与注册（有 token）请求**都应带** X-Platform（原生 client 每个请求都带，仅作元数据，不参与鉴权）。

## 修复方案

### 1) 新增平台标识 `currentPlatform`（expect/actual，照搬 `DispatcherProvider` 模式）

`shared` 已有成熟的 expect/actual 范例：`platform/thread/DispatcherProvider.kt`（commonMain `expect class`）+ `.android.kt` / `.ios.kt` / `.jvm.kt`（actual）。**照此结构**新增：

| 文件（NEW） | 内容 |
|------|------|
| `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/platform/Platform.kt` | `expect val currentPlatform: String`（+ 包 `com.mamba.picme.agent.core.platform` + kdoc 说明用途：注入网关 `X-Platform` header） |
| `shared/src/androidMain/kotlin/com/mamba/picme/agent/core/platform/Platform.android.kt` | `actual val currentPlatform: String = "android"` |
| `shared/src/iosMain/kotlin/com/mamba/picme/agent/core/platform/Platform.ios.kt` | `actual val currentPlatform: String = "ios"` |
| `shared/src/jvmMain/kotlin/com/mamba/picme/agent/core/platform/Platform.jvm.kt` | `actual val currentPlatform: String = "jvm"`（jvm target 仅供单测/桌面，服务端不调 KoogChatAgent，值不进生产） |

> 文件后缀命名沿用 `DispatcherProvider.android.kt` / `.ios.kt` / `.jvm.kt` 风格。

### 2) 两处 `buildGatewayHeaders()` 注入 X-Platform

`KoogChatAgent.kt:223` 与 `KoogReActAgent.kt:342`，在 `return headers` 前各加一行（**无条件**，guest/注册都带）：

```kotlin
headers["X-Platform"] = currentPlatform
```

注意：`KoogReActAgent.kt:53` 的 kdoc 注释提到「X-App-Token / X-Device-Id，照 KoogChatAgent 的 buildGatewayHeaders 模式」——加 X-Platform 后**同步更新该注释**提及 X-Platform。

## 触点清单（共 6：4 NEW + 2 EDIT）

1. NEW `shared/src/commonMain/.../platform/Platform.kt`
2. NEW `shared/src/androidMain/.../platform/Platform.android.kt`
3. NEW `shared/src/iosMain/.../platform/Platform.ios.kt`
4. NEW `shared/src/jvmMain/.../platform/Platform.jvm.kt`
5. EDIT `shared/src/commonMain/.../inference/remote/koog/KoogChatAgent.kt`（`buildGatewayHeaders`，约 :226 后）
6. EDIT `shared/src/commonMain/.../inference/remote/koog/KoogReActAgent.kt`（`buildGatewayHeaders`，约 :345 后 + :53 kdoc）

## 验证（必须全过）

```bash
# expect/actual 在所有源集可解析（android / ios 三架构 / jvm / metadata）
./gradlew :shared:compileDebugKotlinAndroid \
         :shared:compileKotlinIosArm64 \
         :shared:compileKotlinIosSimulatorArm64 \
         :shared:compileKotlinIosX64 \
         :shared:compileKotlinJvm \
         :shared:compileKotlinMetadata
# 代码风格
./gradlew :shared:ktlintCheck
# 现有单测不回归
./gradlew :shared:allTests
```

> 若 iOS Xcode 工具链缺失导致 iosXxx 编译失败，至少保证 `:shared:compileKotlinMetadata` + `:shared:compileDebugKotlinAndroid` + `:shared:compileKotlinJvm` 全绿（这三者能验证 commonMain expect 与 android/jvm actual 正确解析）；ios actual 文件语法正确即可，编译留 orchestrator 审查时在 iosApp worktree 复核。

## 验收点

1. `grep -rn "X-Platform" shared/src` 命中 KoogChatAgent + KoogReActAgent 两处 `buildGatewayHeaders`。
2. `currentPlatform` 在 commonMain/androidMain/iosMain/jvmMain 四源集 `expect`/`actual` 完整（grep `currentPlatform`）。
3. `:shared:compileKotlinMetadata` + `compileDebugKotlinAndroid` + `compileKotlinJvm` 全绿。
4. `:shared:ktlintCheck` 全绿（无通配符 import、lambda 显式命名、`com.mamba.picme.*` 无全限定名）。
5. 未触碰：服务端代码、Android 原生 client、版本号。

## 约束 / 红线

- **不改服务端**（0.9.3 已正确读 X-Platform）。
- **不改 Android 原生 client**（已发 X-Platform）。
- **不 bump 版本号**（客户端修复，随下次 app 构建生效，无需服务端 redeploy）。
- 值小写 `"android"`/`"ios"`/`"jvm"`。
- ktlint 硬规则：无通配符 import、lambda 参数显式命名、`com.mamba.picme.*` 禁全限定名、日志 tag `PoLang:[模块]`（本改动无日志可忽略）。
- 本任务**不含 i18n**（header 注入非用户可见字符串）。

## 交付

- 在分配的 worktree 分支上完成上述 6 处改动。
- 自跑验证命令，把结果贴出。
- 产出供 orchestrator 审查：`git diff main...HEAD --stat` + 关键 diff 片段。
