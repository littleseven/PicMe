# ios-follow / gallery-crop — 相册「砍头杀」对齐验收

- **日期**：2026-08-11
- **分支**：`fix/ios-gallery-crop`（commit `e74fd863`，已 merge `--no-ff` 入 main `60115682`）
- **模式**：B（功能追齐），spec 基线 `docs/08-UI-SPECS/screens/gallery-grid.yaml`（R3 / gap#5）

## 根因

iOS 相册网格「砍头杀」**不在算法、不在调用点，而在数据没流到**：
- `ThumbnailView.faceAwareImage` 裁切公式正确（1:1 对齐 Android `FaceAwareAlignment.kt`，biasUp=1/6 + clamp）。
- `GalleryGridView` 调用点也传了 `asset.faceFocusY?.floatValue`。
- 但 iOS `MediaAsset` 由 `IosMediaRepository.toDomain()` 从 `IosMediaItem`（PHAsset 投影）构造，**不带 `faceFocusY`**（PHAsset 无人脸数据）；`faceFocusY` 只存独立 TagDatabase（Pass1 写）。
- → `asset.faceFocusY` 恒 nil → face-aware 分支永不触发 → 居中裁切 → 人像头部被裁 → 砍头杀。
- Android 对照：Room 驱动的 `MediaAsset.faceFocusY` 由 tag 生成回填，相册直接读到。

## 修复（Swift-only，+43/−5，4 文件）

| 文件 | 改动 |
|---|---|
| `TagDatabase+Person.swift` | 新增 `faceFocusYByLocalIdentifier() -> [String: Float]`（批量 SELECT uri,faceFocusY WHERE NOT NULL） |
| `GalleryViewModel.swift` | `applyGrouping` 起始批量查一次 → `@Published faceFocusYMap` |
| `GalleryGridView.swift` | 缩略图 `faceFocusY: vm.faceFocusYMap[asset.uri]`（替 `asset.faceFocusY?.floatValue`） |
| `gallery-grid.yaml` | 精化 `face_aware_vertical_alignment` 公式 + 登记 `data_source_platform_parity` 台账 |

## 验收（三栏）

### ✅ 自动通过
- xcodebuild 真机 Debug 编译：**BUILD SUCCEEDED**
- 设备安装 + 启动：成功（iPhone 15）
- 截图非黑屏体检：通过
- syslog 崩溃信号检查：无（App 进程正常）
- `GalleryViewModelTests.testGroupingSortsDescAndGroupsByDay`：**通过**（唯一触及本改动的测试）

### ⚠️ 待真机终验（用户已确认）
- 观感「砍头杀消除」：**用户真机终验通过**（用户原话「看起来砍头问题修复了」）。
  - 自动化截图未能捕获相册网格（dev-loop 启动后 App 状态恢复到 Settings 页，非默认相册首页），故观感由用户手持设备直接确认。
- 数据新鲜度：`faceFocusYMap` 在 `applyGrouping`（资产 Flow emit）时刷新；若 Pass1 扫描在相册已加载后完成，需下次 emit 才更新（当前用户已扫描，不影响）。

### 📋 技术债 / 未做
- **R4「固定 3 列 → Adaptive(110)」未做**：独立 gap，与砍头杀无关（face-aware 护头与列数无关）。列数影响 cell 大小但不影响护头算法。留待单列对齐。
- **源级 parity 方案未取**：未把 `faceFocusY` 加进 `IosMediaItem` 桥接 DTO + `toDomain` 映射（需 XCFramework 重建 + K/N init 签名联动）。本次取低风险 Swift-only；源级方案可让 chat 轮播等其他 MediaAsset 消费者也自动获得 faceFocusY，留作后续。
- **运行时 Vision 人脸检测兜底未引入**：会偏离 Android「依赖预计算 faceFocusY、null 退回居中」契约（spec `fallback: center_crop`）。
- **2 项既有测试失败（非本变更回归）**：
  - `shared:jvmTest`：`:shared:kmpPartiallyResolvedDependenciesChecker FAILED`（Gradle KMP 依赖解析基础设施层；零 Kotlin 改动不可能影响）。
  - `PoLangTests`：`MediaPipe468AdapterTests` 2 个前置摄像头 X 镜像断言失败（陈旧测试，与 [[ios-landmark-adapter-no-mirror]]「iOS 适配器故意不做镜像」设计冲突）。
- **工作区残留（非本变更）**：`iosApp/PoLang/Platform/FaceAlignment.swift`（+69/−14，CGContext warp 矩阵重做、修「88% 黑像素」，注释未写完——并行会话在途调试）与 `project.pbxproj`（dev-loop xcodegen 重生成）**未纳入本提交**，留待对应作者处理。

## 偏离记录
- 未用独立 worktree（命令 Mode B 字面要求）：主 checkout 已具 Pods/MNN/workspace 的 iOS 环境，且 SharedKit.xcframework/`*.task` 为 gitignored，新 worktree 需重建二进制产物（见 [[ios-worktree-gitignored-build-artifacts]]）。改在主 checkout 新建分支做 iOS-only 改动，git 层隔离 main，未污染 Android 代码。
- 本地 main 领先 origin/main 5 提交，**未 push**（待用户指令）。
