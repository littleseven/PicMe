# RD 累积经验日志（Experience Log）

> **格式规范**：按时间倒序，每次任务后追加。禁止删除旧记录。
> 
> **关联查询**：通过技能标签（如 `@adb-bot`, `@compose`）快速检索相关经验。

---

## [2026-05-10] Gallery adb 测试命令体系扩展

**任务描述**: 为 Gallery 模块（MediaPager/相册编辑）扩展与 Camera 同源的 adb 广播命令系统，支持 16 个新命令
**关联技能**: `@adb-bot`, `@compose-lifecycle`, `@gallery`, `@broadcast-receiver`
**预估耗时**: 40 分钟
**实际耗时**: 约 100 分钟
**时间偏差**: +150%

### 陷阱清单

| # | 陷阱描述 | 级别 | 已有 skill 覆盖 | 时间浪费 | 根因类别 |
|---|----------|------|-----------------|----------|----------|
| 1 | 未读 `adb-bot` skill，不知道动态注册机制和 `Background execution not allowed` 问题 | P0 | ❌ 有但未读 | 25min | 知识盲区 |
| 2 | 未意识到 CameraScreen 和 GalleryScreen 各自需要独立动态注册 BroadcastReceiver | P0 | ❌ 有但未读 | 15min | 架构盲区 |
| 3 | `LaunchedEffect(Unit)` 闭包捕获了初始空的 `allFlatMedia`，命令处理时始终 `size=0` | P1 | ❌ 无 | 20min | 知识盲区 |
| 4 | 调试方法原始：不清日志、不查 pid、不重新打包 APK | P1 | ❌ 无 | 20min | 工具不熟 |
| 5 | 一次性添加 16 个密封子类，CameraScreen `when` 编译失败 | P2 | ❌ 无 | 5min | 流程缺失 |

### 根因详解

**陷阱 1 & 2 — 前置调研缺失**
项目已有 `adb-bot` skill 明确记载了：
- `CameraScreen` 通过 `DisposableEffect` 动态注册 receiver
- Android 8.0+ 后台广播限制导致静态 receiver 不可靠
- `Background execution not allowed` 的排查方法

编码前直接跳过阅读，导致花了大量时间从零调试广播接收链路。

**陷阱 3 — Compose 闭包捕获**
```kotlin
// ❌ 错误：allFlatMedia 被闭包捕获为空列表
val allFlatMedia = remember(groupedMedia) { ... }
LaunchedEffect(Unit) {
    commandFlow.collect { command ->
        // 这里的 allFlatMedia 永远是挂载时的值
        if (allFlatMedia.isNotEmpty()) { ... }
    }
}

// ✅ 正确：在 when 分支内直接读取 StateFlow 最新值
LaunchedEffect(Unit) {
    commandFlow.collect { command ->
        when (command) {
            is OpenPhoto -> {
                val currentMedia = viewModel.allMedia.value  // 实时读取
                ...
            }
        }
    }
}
```

**陷阱 4 — 调试 SOP 缺失**
| 错误做法 | 正确做法 |
|----------|----------|
| 不清日志，新旧进程混杂 | `adb logcat -c` |
| 不查 pid，grep 抓到旧进程 | `adb shell pidof com.mamba.picme` |
| `compileDebugKotlin` 后直接安装 | `./gradlew :androidApp:assembleDebug` 重新打包 |
| 不强制停止，热更新残留 | `adb shell am force-stop com.mamba.picme` |

**陷阱 5 — 批量修改**
密封类新增子类后，所有 `when` 表达式必须同步更新。应增量开发：先加 1 个命令，验证编译通过后再批量扩展。

### 措施落地

| 措施 | 目标资产 | 状态 |
|------|----------|------|
| 新增 `rd-reflection` 自我进化 skill | `skills/rd-reflection/` | ✅ 已提交 |
| 更新 `adb-bot` skill：Gallery 命令 + 故障排除 | `skills/adb-bot/SKILL.md` | ✅ 已提交 |
| 新增 `gallery/AGENTS.md` §3 adb 测试命令 | `app/src/main/java/com/picme/features/gallery/AGENTS.md`（重组前路径） | ✅ 已提交 |
| 更新 `CameraTestCommand` 16 个 Gallery 命令 | `features/camera/test/CameraTestCommand.kt` | ✅ 已提交 |
| 更新 `GalleryScreen` 动态 receiver + 收集器 | `features/gallery/GalleryScreen.kt` | ✅ 已提交 |
| 更新 `MediaPager` 命令收集器 | `features/gallery/components/MediaPager.kt` | ✅ 已提交 |
| 更新 `CameraScreen` `EnterGallery` + else 容错 | `features/camera/CameraScreen.kt` | ✅ 已提交 |

### 检查清单更新

- [x] 新增：编码前必须检索相关 skill
- [x] 新增：检查目标 Screen 是否有动态 receiver
- [x] 新增：LaunchedEffect 中禁止读取 remember 局部变量，必须用 StateFlow.value
- [x] 新增：adb 调试必须清日志、查 pid、重打包、强制停止
- [x] 新增：新增密封子类后检查所有 when 表达式

### 一句话总结

> **"先读 skill 再画流图，闭包捕获用 StateFlow，调试必清日志查 pid，增量验证再批量扩。"**

---

## [模板] 任务标题

**任务描述**: 
**关联技能**: 
**预估耗时**: 
**实际耗时**: 
**时间偏差**: 

### 陷阱清单

| # | 陷阱描述 | 级别 | 已有 skill 覆盖 | 时间浪费 | 根因类别 |
|---|----------|------|-----------------|----------|----------|
| 1 | | | | | |

### 根因详解

### 措施落地

| 措施 | 目标资产 | 状态 |
|------|----------|------|
| | | |

### 检查清单更新

- [ ] 

### 一句话总结

> 
