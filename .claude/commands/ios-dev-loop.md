# iOS Dev Loop Skill

> **定位**：iOS 一键闭环验证（编译 → 安装 → 启动 → 截屏 → 基线对比）。
> **触发时机**：iOS 改动后做端到端验证、回归对比截屏时。

## 闭环流程

对标 Android `scripts/auto-dev-loop.sh`，iOS 用 `scripts/ios-dev-loop.sh`：

```bash
./scripts/ios-dev-loop.sh
```

内部步骤：

1. **编译**：`xcodebuild -scheme PoLang -destination 'generic/platform=iOS' build`（build-only，无签名）
2. **安装**：`xcrun simctl install booted <DerivedData>/Build/Products/Debug-iphonesimulator/PoLang.app`
3. **启动**：`xcrun simctl launch booted com.mamba.picme`
4. **截屏**：`xcrun simctl io booted screenshot /tmp/ios-shot.png`
5. **对比**：`python3 scripts/screenshot-diff.py /tmp/ios-shot.png <baseline>`

## 基线管理

- 基线截屏存于项目资源目录（如 `tests/ios-baselines/`）。
- 首次或故意改 UI 时刷新基线；回归对比时**禁止自动刷新**（避免掩盖回归）。

## 与 Android dev-loop 对齐

| 步骤 | Android | iOS |
|------|---------|-----|
| 编译 | `./gradlew :androidApp:assembleDebug` | `xcodebuild ... build` |
| 安装 | `adb install -r ...apk` | `simctl install booted ...app` |
| 启动 | `adb shell am start ...` | `simctl launch booted <bundle>` |
| 截屏 | `adb exec-out screencap` | `simctl io screenshot` |
| 对比 | `screenshot-diff.py` | `screenshot-diff.py`（同脚本，跨平台） |

## 相关文件

- [ios-build-debug](/ios-build-debug) — 分层编译与 simctl 细节
- [dev-loop](/dev-loop) — Android 闭环对照
- [screenshot-diff.py](scripts/screenshot-diff.py) — 像素对比脚本

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-08-08 | 初始版本（Phase 5 基建） |
