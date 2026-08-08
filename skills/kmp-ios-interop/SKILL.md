---
name: kmp-ios-interop
description: |
  Kotlin/Native ↔ Swift 互操作铁律与 shared XCFramework 集成：signal 6 崩溃、Flow→AsyncStream、SharedBridge 约定、组合根 D7。Use when integrating the shared KMP framework into iosApp, crossing Kotlin↔Swift boundaries, or debugging signal 6/retain-cycle/interop issues.
version: 1.0.0
created: 2026-08-08
updated: 2026-08-08
maintainer: [RD] 全栈工程师
tags:
  - ios
  - kmp
  - kotlin-native
  - interop
  - shared
---


# KMP iOS 互操作 Skill

> **定位**：Kotlin/Native ↔ Swift 互操作铁律与 shared framework 集成（无 Android 对标，iOS 独有痛点）。
> **触发时机**：shared framework 集成、Kotlin ↔ Swift 边界、XCFramework embed、Flow → Swift、signal 6 崩溃时。

## 核心铁律

### 1. `@Throws` 不导出异常 → signal 6 崩溃（最高频坑）

Kotlin 异常**不**经 `@Throws` 自动导出到 Swift；未兜底的异常跨 KN 边界会直接 **signal 6（SIGABRT）崩溃**，Swift 侧无法 catch。

**纪律**：所有 shared → Swift 边界，在 **Kotlin 侧** try/catch，兜底为 `Result` / 可空 / 字符串。`SharedBridge/` 统一此约定。

```kotlin
// shared commonMain —— 永远不让异常逃逸到 Swift
fun doSomethingSafe(): Result<Data> = runCatching { doSomethingRisky() }
```

```swift
// iosApp/SharedBridge —— Swift 只消费 Result，绝不指望 catch Kotlin 异常
switch SharedBridge.shared.doSomethingSafe() {
case let .success(data): // ...
case let .failure(message): // 字符串，非异常
}
```

### 2. 组合根 D7 模式

- shared **不知任何 iOS 类型**；无 `PlatformContext` expect。
- `iosApp/DI/AppContainer.swift` 构造注入 Swift actual 进 shared。
- shared 接口在 commonMain，actual 实现在 `iosApp/Platform/`。

### 3. Flow → Swift AsyncStream

Kotlin `Flow` 经 `SharedBridge` 转 Swift `AsyncStream`（打字机流式渲染等）：

```swift
for try await chunk in SharedBridge.shared.chatStream() {
    // Kotlin Flow → Swift AsyncStream
}
```

## XCFramework embed

```bash
./gradlew :shared:assembleSharedDebugXCFramework
# debug 日常 ~6s；Release ~4min（一次性）
```

- Build Phase 脚本按 **Gradle 构建 hash 重拷**，避免每次 Xcode 编译触发 Kotlin 全量重编。
- 产物 embed 到 iosApp；framework 体积监控（防 KN 二进制膨胀）。

## 常见坑

| 坑 | 症状 | 修复 |
|----|------|------|
| Kotlin 异常逃逸 | signal 6 崩溃 | 边界 try/catch 兜底 Result（铁律 1） |
| Flow 未释放 | 流泄漏 / retain cycle | AsyncStream + `[weak self]` |
| `PlatformContext` expect | shared 耦合 iOS 类型 | D7 组合根，删 expect |
| framework 体积暴涨 | 启动慢 / 包超限 | 监控体积；查无用符号 |
| 跨线程改 UI | 主线程断言 | `@MainActor` + `DispatchQueue.main` |

## 相关文件

- [ios-build-debug](/ios-build-debug) — XCFramework 编译 / 安装
- [doc-sync-guardian](/doc-sync-guardian) — shared 接口漂移治理
- spec：`docs/superpowers/specs/2026-08-08-ios-app-skeleton-design.md` §2.3 依赖方向

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-08-08 | 初始版本（Phase 4/5 跨切面 R2） |
