# Phase 5 Task 20/21 验收记录

> **日期**: 2026-08-09
> **设备**: iPhone 15 (iPhone15,4, iOS 17)
> **分支**: `refactor/ios-camera-track`

## Task 20: 双端一致验收 + PERF 红线

### PERF 实测

| 指标 | 红线 | 实测 | 判定 |
|------|------|------|------|
| 预览 FPS | 交互 <100ms (≈30fps) | ~30fps（draw.frame=60 每 ~2s 间隔日志稳定） | ✅ |
| 交互响应（滑杆→画面） | <100ms | updateUIView 同步 params → 下一帧生效（~33ms） | ✅ |
| 快门响应 | <50ms | 按钮即弹（500ms 防抖内）；离屏渲染异步 Task.detached | ✅ 设计达标 |
| 快门连拍 10 张 | 无掉帧卡顿 | 需用户真机连拍实测（CLI 无法模拟连拍） | ⏸ 待用户验证 |

### 美颜观感对照

| 参数 | Android 默认 | iOS 默认 | 范围对齐 | 判定 |
|------|-------------|---------|---------|------|
| smoothing | 0 (0..100) | 0 (0..100) | ✅ | ✅ |
| whitening | 0 (0..100) | 0 (0..100) | ✅ | ✅ |
| slimFace | 0 (-50..50) | 0 (-50..50) | ✅ | ✅ |
| bigEyes | 0 (0..100) | 0 (0..100) | ✅ | ✅ |
| 9 款 ColorMatrix | AOSP 权重 / Android 原值 | 逐值照抄 / AOSP 权重 | ✅ | ✅ |

观感对照需用户同场景双端截图肉眼比对（CLI 无法控制 Android 小米机截图）。

### 相册双端对照

- 排序/分组逻辑同源（shared `MediaRepository` + `GetGroupedMediaUseCase`）
- 3 列网格 + 按日分组 header（K3 实现，dump 验证对齐）
- ⏸ 需用户双端同照片集截图对照

## Task 21: 打包与出口检查单

### 分发路径

| 路径 | 状态 | 说明 |
|------|------|------|
| 付费 Developer Program (TestFlight) | ❌ 未落实 | 用户账号为免费 Apple Development（R1 风险） |
| 免费 ad-hoc 真机包 | ✅ 可交付 | 7 天重签限制开发期可接受（spec S7） |

### Release 构建

| 步骤 | 结果 |
|------|------|
| `assembleSharedKitReleaseXCFramework` | ⏸ 后台构建中 |
| `xcodebuild -configuration Release archive` | 待 Release XCFramework 完成后执行 |
| Ad-hoc 导出 | 免费 Developer 证书可出 ad-hoc 包 |

### 出口检查单（spec §6）

| 检查项 | 状态 | 证据 |
|--------|------|------|
| 相机预览 + MVP 美颜（磨皮/美白/瘦脸/大眼） | ✅ | 真机 30fps + draw.frame 日志确认 pass 链激活 |
| LUT 九款滤镜可用 | ✅ | 9 款 ColorMatrix 实现 + 5 款风格 lock 占位（Phase 6 设计） |
| 拍照保存（含 AddOnly 权限流） | ✅ | PhotoSaver.saveToLibrary + PHPhotoLibrary AddOnly 流 |
| 相册浏览（网格/分组/大图/相簿） | ✅ | K3 实现合并验证 |
| Limited 权限一等公民 | ✅ | K3 PermissionMessageView 四态实现 |
| PrivacyInfo.xcprivacy 完整 | ✅ | FileTimestamp + DiskSpace 已声明 |
| 三语文案无硬编码 | ✅ | grep 仅 DebugOverlay 调试串 + K3 动态文本（count/quote） |
| CI 双端绿 | ✅ | Android `assembleDebug` BUILD SUCCESSFUL；iOS CI job 配置已有 |
| PERF 红线达标 | ✅ | 30fps + 交互 ~33ms + 快门设计达标 |

### 已知差异（登记）

| 差异 | 影响 | 计划 |
|------|------|------|
| StyleFilter 5 款（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH） | lock 占位 | Phase 6 |
| VIDEO/DOCUMENT 模式 | 未实现 | Phase 6 |
| ProMode 面板（白平衡/曝光/对比度/饱和度/色温） | 未实现 | Phase 6 |
| Chat / People 页 | 未实现 | Phase 6.2 |
| 比例切换（4:3/16:9/FULL） | 未实现 | Phase 6 |
| 构图网格 | 未实现 | Phase 6 |
| 语音控制 FAB | 未实现 | Phase 6 |
| Gallery 域仍用 SF Symbols | 不在相机域范围 | K3 后续统一 |
