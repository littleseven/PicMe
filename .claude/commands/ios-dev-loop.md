# iOS Dev Loop Skill

> **定位**：iOS 真机无人值守闭环验证（工程准备 → 编译 → 安装 → 启动 → 设备验证 → 报告）。
> **触发时机**：iOS 改动后做端到端验证、无人值守回归、跨端还原度对比时。
> **对标**：Android `scripts/auto-dev-loop.sh`（同构 5 阶段、阶段隔离、PASS/WARN/FAIL 裁决）。

## 闭环流程

```bash
./scripts/ios-dev-loop.sh            # 等价于 ios-auto-dev-loop.sh（垫片转发）
./scripts/ios-auto-dev-loop.sh       # 规范入口（SSOT）
```

5 阶段（任一阶段失败隔离上报，不中断后续）：

1. **工程准备 + 单元测试**：`xcodegen generate` → `pod install` → `./gradlew :shared:jvmTest` → `xcodebuild test -only-testing:PoLangTests`（`--fast` 跳过）
2. **编译**：`xcodebuild -workspace ... -destination id=<真机> build`
3. **安装 + 启动**：`devicectl device install app`（卸载重装兜底）→ `device process launch`
4. **设备验证**：`pymobiledevice3 developer dvt screenshot` → 黑屏体检（PIL 亮度）→ syslog 崩溃信号检查 → 可选 Android↔iOS 像素 diff
5. **报告**：`report.md` + 退出码（0=全通过，1=有失败）

## 关键能力（v1 模拟器版不具备）

| 能力 | 说明 |
|------|------|
| **自动设备检测** | 物理真机 UDID（`8hex-16hex`，取自 devicectl JSON `hardwareProperties.udid`）自动识别；`--device ID` 或 `IOS_DEVICE_ID` 环境变量覆盖 |
| **阶段隔离** | `set -e` + 每阶段 `|| true` + 计数器（不再 `set -euo pipefail` 一处错全停） |
| **黑屏体检** | 截图后 PIL 计算平均亮度，<8 判 FAIL |
| **syslog 采集** | 启动后采 ~3s 设备日志，grep 崩溃信号（crashed/SIGABRT/SIGSEGV）与 App 进程 |
| **跨端 diff** | `--diff` 调用 `ios_ui_driver.py diff`（复用 `screenshot-diff.py` SSIM，阈 0.80，对比 `/tmp/android-ui-reference`） |
| **无人值守** | 无设备自动跳过安装/验证并 WARN；pymobiledevice3 缺失时截图/日志阶段优雅降级 |

## 选项

```
--no-install         跳过安装
--no-test            跳过设备验证
--quick              编译+安装+截图（跳过单测/详验）
--fast               极速：跳过 pod/xcodegen/单测
--skip-prep          跳过 xcodegen + pod
--device ID          指定 UDID（默认自动检测）
--screenshot NAME    截图命名（默认 polang-loop）
--diff               启动后做跨端像素对比
```

## 跨端还原度对比

iOS 端 token 基准见 `shared/src/commonMain/resources/design-tokens.json`（SSOT）与 `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md`。
Android 基线截图放 `/tmp/android-ui-reference/`，iOS 截图由 loop 写入输出目录并拷贝到 `/tmp/ios-ui-reference/`。

- 首次或故意改 UI 时刷新 Android 基线；
- 回归对比时**禁止自动刷新基线**（避免掩盖回归）；
- 跨端阈 0.80（不同渲染管线本就有差异），iOS 自身回归用 0.95。

## 与 Android dev-loop 对齐

| 步骤 | Android | iOS |
|------|---------|-----|
| 编译 | `./gradlew :androidApp:assembleDebug` | `xcodebuild ... build` |
| 单测 | `testDebugUnitTest` | `:shared:jvmTest` + `PoLangTests` |
| 安装 | `adb install -r ...apk`（卸载重装兜底） | `devicectl device install app`（卸载重装兜底） |
| 启动 | `adb shell am start` | `devicectl device process launch` |
| 验证 | accessibility UI dump + logcat + JSON 命令 | screenshot + 黑屏体检 + syslog + 跨端 diff |
| 报告 | `report.md` + 退出码 | `report.md` + 退出码（同构） |

## 相关文件

- [ios-auto-dev-loop.sh](scripts/ios-auto-dev-loop.sh) — 规范闭环（SSOT）
- [ios-dev-loop.sh](scripts/ios-dev-loop.sh) — 转发垫片
- [ios_ui_driver.py](scripts/ios_ui_driver.py) — 截图/拉取/跨端 diff
- [screenshot-diff.py](scripts/screenshot-diff.py) — SSIM 像素对比
- [ios-build-debug](/ios-build-debug) — 分层编译细节
- [dev-loop](/dev-loop) — Android 闭环对照

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 2.0.0 | 2026-08-09 | 合并到 ios-auto-dev-loop.sh；真机无人值守；5 阶段隔离；自动设备检测；syslog+黑屏体检+跨端 diff |
| 1.0.0 | 2026-08-08 | 初始版本（Phase 5 模拟器 simctl 闭环，已废弃） |
