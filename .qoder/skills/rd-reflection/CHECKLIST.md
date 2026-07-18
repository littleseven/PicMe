# RD 动态检查清单（Dynamic Checklist）

> **使用方式**：每次任务启动时自动读取，根据任务关键词匹配相关条目。
> **进化规则**：新陷阱自动追加，高频陷阱自动置顶，已验证通过的标记 ✅。

---

## 🔴 高频陷阱（最近 30 天内已确认）

### [通用] 前置调研缺失
- [ ] **编码前检索相关 skill**：`ls .qoder/skills/` 查看是否有相关 skill（adb-bot / av-gl-expert / coordinate-system-standard 等）
- [ ] **阅读模块 AGENTS.md**：确认架构约束和常见陷阱
- [ ] **画出核心数据流/命令流图**：文字描述亦可，确保理解 Broadcast → Receiver → Dispatcher → Screen 的完整链路

**关联经验**: [2026-05-10] Gallery adb 命令扩展 — 未读 adb-bot skill，浪费 25 分钟调试广播接收

### [Android] BroadcastReceiver 动态注册
- [ ] **检查目标 Screen 是否有动态 receiver**：如果命令需要在多个 Screen 间切换，每个 Screen 都必须通过 `DisposableEffect` 动态注册
- [ ] **验证 receiver 注册/注销日志**：`PoLang:CameraTest` / `PoLang:GalleryTest` 标签中应有 "registered/unregistered dynamically"

**关联经验**: [2026-05-10] Gallery adb 命令扩展 — CameraScreen 注销后 GalleryScreen 未注册，广播被吞

### [Compose] LaunchedEffect 闭包捕获
- [ ] **禁止在 collect lambda 中读取 remember 局部变量**：`val xxx = remember(...)` 在 lambda 中被捕获的是挂载时刻的值
- [ ] **必须使用 StateFlow.value 读取最新状态**：`viewModel.xxxStateFlow.value`
- [ ] **验证状态值非空后再执行操作**：尤其是异步加载的数据（媒体库、网络请求）

**关联经验**: [2026-05-10] Gallery adb 命令扩展 — `allFlatMedia` 被捕获为空列表，`open_photo` 始终失败

---

## 🟠 中频陷阱（最近 90 天内已确认）

### [Android] adb 调试规范
- [ ] **清除日志后再测试**：`adb logcat -c`
- [ ] **确认进程号**：`adb shell pidof com.mamba.picme`，日志过滤时使用确切 pid
- [ ] **修改代码后必须重新打包**：`./gradlew :app:assembleDebug`，`compileDebugKotlin` 不会生成新 APK
- [ ] **强制停止后安装**：`adb shell am force-stop com.mamba.picme && adb install -r ...`，避免热更新残留

**关联经验**: [2026-05-10] Gallery adb 命令扩展 — 多次安装旧 APK，改代码"没效果"

### [Kotlin] 密封类 when 分支完整性
- [ ] **新增密封子类后检查所有 when 表达式**：如果没有 `else` 分支，编译会失败
- [ ] **增量开发**：先添加 1 个命令 + 1 个处理分支，编译通过后再扩展

**关联经验**: [2026-05-10] Gallery adb 命令扩展 — 一次性添加 16 个子类，CameraScreen when 编译失败

---

## 🟡 低频陷阱（历史记录，仍有效）

*暂无*

---

## 📊 统计

| 类别 | 数量 | 最近新增 |
|------|------|----------|
| 🔴 高频 | 3 | 2026-05-10 (3) |
| 🟠 中频 | 2 | 2026-05-10 (2) |
| 🟡 低频 | 0 | — |

**下次清单进化时间**: 2026-05-17（每周日自动 review）
