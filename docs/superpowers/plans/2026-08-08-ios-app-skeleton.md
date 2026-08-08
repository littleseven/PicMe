# Phase 5 iOS App 骨架（iosApp）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `iosApp/`（SwiftUI），交付 TestFlight/ad-hoc 内测包：相册浏览（含 Limited 权限一等公民）+ 相机预览（MVP 美颜：磨皮/美白/瘦脸/大眼 + LUT 滤镜）+ 拍照，双端体验对齐 Android。

**Architecture:** 分模块边界（spec S1）——相册 Swift 主导 presentation（消费 shared 领域层），相机管线纯 Swift/Metal（方案 A：GLSL→MSL 翻译 + 宿主重写），Agent 链路薄壳（Phase 6 才接入）。组合根 `DI/AppContainer.swift` 把 iOS actual 注入 shared 接口。

**Tech Stack:** Swift 5.9+ / SwiftUI（iOS 16+）· AVFoundation / Metal / Photos framework · shared（KMP，Phase 4 产物，XCFramework `SharedKit`）· MediaPipeTasksVision iOS（人脸 468→106 关键点）· MNN.framework（仅收编，Phase 5 相机美颜不用）· XCTest

**上游设计文档（必读）**：`docs/superpowers/specs/2026-08-08-ios-app-skeleton-design.md`（决策锁定 S1–S10、风险 R1–R7）
**关键输入产物**：
- `tmp/beauty-metal-spike/BeautyMetalSpike/main.mm` + `Shaders.metal`（相机+美白已验证 Metal 基线，Task 12/13 直接参照）
- `tmp/mnn-ios-spike/MNN.framework`（10MB arm64，Task 6 搬正）
- `tmp/mnn-ios-spike/spm-ios-build/CMakeLists.txt`（sentencepiece iOS 构建，Task 6 归档）
- `engines/beauty-engine/src/main/assets/shaders/`（20+7 个 GLSL，Task 13/14/16/17 翻译源）
- `engines/beauty-engine/.../facedetect/adapter/MediaPipe468Adapter.kt`（468→106 映射表，Task 15 移植源）
- spike 工程 `DEVELOPMENT_TEAM = 6NPE45262A`（沿用）

**执行现场**：Phase 4 合并入 main 后，从 main 建 worktree `.worktrees/feat-ios-app-skeleton/` + 分支 `feat/ios-app-skeleton`（遵循 using-git-worktrees）。所有 Task 在此 worktree 执行；提交只落该分支。

**执行顺序与工期**：Task 0–6 基建（~1–1.5w）→ Task 7–11 相册（~1.5–2w）→ Task 12–19 相机（~3w）→ Task 20–22 验收出口（~1w）。

**⚠️ 对 spec 的一处修正（2026-08-08 计划编制时发现）**：spec §5.4 写「MNN RetinaFace + 106 关键点」不准确——相机美颜 warp 用的 106 关键点实际来自 **MediaPipe Face Landmarker（468 点）+ `MediaPipe468Adapter` 映射**（Android 默认路径，`engines/beauty-engine/.../facedetect/`）。iOS 侧用官方 **MediaPipeTasksVision** pod/SPM + 移植同一映射表。MNN 在 Phase 5 只做产物收编（Task 6），不接推理（Phase 6.1 TAG 才用）。

---

> **K3 相册段实例执行记录（2026-08-08，worktree `.worktrees/ios-gallery`，分支 `refactor/ios-gallery`）**
> - **Task 0 核对结论**：`MediaRepository`/`AccessState` ✅ 已存在（`domain/repository/`，AccessState 为 4 data object 密封接口）；`BeautySettings`/`FilterType`/`StyleFilter` ✅；**`GetGroupedMediaUseCase` ❌ commonMain 不存在**（Task 9 暂用 Swift 侧等价分组，shared 落地后替换）；**文档偏差**：`MediaAsset.id` 实为 `Long`（非启动包/spec §4.1 所述 String），iOS id 由 `localIdentifier.hashCode()` 派生；`MediaRepository` 仍含 Android 删除授权四方法（iOS 实现为 no-op）。
> - **Kotlin 2.3.10 DSL 偏差**：`XCFramework` 类已改名 `XCFrameworkConfig`（构造器首参 `Project`），Task 1 代码块已按此落地。
> - **Intel 主机（x86_64）偏差**：`iosSimulatorArm64Test` 被 KGP 禁用（host arch 不匹配），iOS 单测用 `:shared:iosX64Test` 验证。
> - **Task 3 阻塞**：Xcode 工程（Task 2 产物）在 GLM 分支 `refactor/ios-camera-track`，本 worktree 无工程可 embed；冒烟待两分支会合后补做。Swift 半代码已写（经 SharedKit.h 导出签名核对 + `swiftc -parse` 语法检查），xcodebuild 编译验证随 Task 3 一并补。

## Task 0: 前置核对（Phase 4 出口验证，不写代码）

**Files:**
- 只读验证，无文件变更

- [x] **Step 1: 确认 Phase 4 已合并且 main 绿**（K3 注：`:shared:compileKotlinIosSimulatorArm64` + `:shared:jvmTest` 绿；worktree 基于已同步 main，androidApp assemble 由主会话收口验证）

- [x] **Step 2: 验证 shared commonMain 导出面**（K3 注：四项命中；`GetGroupedMediaUseCase` 缺失见段首执行记录）

- [x] **Step 3: 验证 iOS target 可用**（`iosX64/iosArm64/iosSimulatorArm64` 编译通过；XCFramework 任务不存在，Task 1 新建）

- [x] **Step 4: 建执行 worktree**（主会话已建 `.worktrees/ios-gallery` + `refactor/ios-gallery`）

---

## Task 1: shared iOS framework 打包配置（SharedKit XCFramework）

**Files:**
- Modify: `shared/build.gradle.kts`（在 `kotlin {}` 块内追加）
- Test: 构建产物验证（无单测）

**背景**：Phase 4 的 `shared/build.gradle.kts` 只声明了 5 个 target（android/jvm/iosX64/iosArm64/iosSimulatorArm64），**没有** framework/XCFramework 配置。参照 `tmp/kmp-koog-spike/sharedSpike/build.gradle.kts` 的已验证模式。

- [x] **Step 1: 加 XCFramework 配置**（K3 注：Kotlin 2.3.10 中类名为 `XCFrameworkConfig(project, "SharedKit")`，原 `XCFramework` 已移除；iOS target 已具名化）

在 `shared/build.gradle.kts` 的 `kotlin { ... }` 块内（iOS target 声明之后）追加（实际落地用 `XCFrameworkConfig`）：

```kotlin
// iOS framework 产物（iosApp 消费；Task 1）
val sharedKit = org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework("SharedKit")
listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
        baseName = "SharedKit"
        isStatic = false
        xcFramework.add(this)
    }
}
```

注意：若 iOS target 已用 `iosX64()` 等形式声明在上方，改为先 `val iosX64 = iosX64()` 等具名声明再复用，避免重复注册 target。

- [x] **Step 2: 构建 Debug XCFramework**（✅ 4m22s 首跑成功；双切片 + SharedKit.h 齐）

- [x] **Step 3: 验证导出符号**（✅ 22 处命中；另确认 `AccessStateFull.shared`、`IosMediaRepository(bridge:)`、`FlowWatchersKt.watch(_:onEach:)` 等相册段符号导出形态）

- [x] **Step 4: 验证 Release 构建（一次性成本，记录耗时）**（✅ 双切片 111MB；`:shared:assemble` 已含 release 变体，增量复核 6s）

- [x] **Step 5: Commit**（✅ c0a06215）

---

## Task 2: Xcode 工程骨架（iosApp/ + Tab 骨架）

**Files:**
- Create: `iosApp/PoLang.xcodeproj`（Xcode 模板生成）
- Create: `iosApp/PoLang/App/PoLangApp.swift`
- Create: `iosApp/PoLang/Features/Main/MainTabView.swift`
- Create: `iosApp/PoLang/Features/Gallery/GalleryPlaceholderView.swift`
- Create: `iosApp/PoLang/Features/Camera/CameraPlaceholderView.swift`
- Create: `iosApp/.gitignore`
- Test: `xcodebuild` 编译 + 模拟器运行截图验证

- [x] **Step 1: Xcode 模板建工程** ✅ GLM 完成（XcodeGen CLI 路径替代 GUI：`iosApp/project.yml` + `xcodegen generate` → `PoLang.xcodeproj`）

手动步骤（GUI 一次）：Xcode → New Project → iOS App → Product Name `PoLang`，Team 选 `6NPE45262A` 对应账号，Org Identifier `com.mamba`，Interface **SwiftUI**，Language **Swift**，Storage **None**，Include Tests ✅。保存到 `iosApp/`（工程路径 `iosApp/PoLang.xcodeproj`，源码目录 `iosApp/PoLang/`）。

随后 Xcode 内设置：General → Bundle Identifier 改 `com.mamba.picme`（与 Android applicationId 同名，spec §2 决策）；Deployment Target **iOS 16.0**。

- [ ] **Step 2: 删除模板默认文件，写 App 入口**

删除模板生成的 `ContentView.swift`。写 `iosApp/PoLang/App/PoLangApp.swift`：

```swift
import SwiftUI

@main
struct PoLangApp: App {
    var body: some Scene {
        WindowGroup {
            MainTabView()
        }
    }
}
```

- [ ] **Step 3: 写 Tab 骨架**

`iosApp/PoLang/Features/Main/MainTabView.swift`：

```swift
import SwiftUI

struct MainTabView: View {
    var body: some View {
        TabView {
            GalleryPlaceholderView()
                .tabItem { Label("相册", systemImage: "photo.on.rectangle") }
            CameraPlaceholderView()
                .tabItem { Label("相机", systemImage: "camera") }
        }
        .accessibilityIdentifier("mainTabView")
    }
}

#Preview {
    MainTabView()
}
```

`iosApp/PoLang/Features/Gallery/GalleryPlaceholderView.swift`：

```swift
import SwiftUI

struct GalleryPlaceholderView: View {
    var body: some View {
        Text("相册（Task 7-11 实现）")
            .accessibilityIdentifier("galleryPlaceholder")
    }
}

#Preview { GalleryPlaceholderView() }
```

`iosApp/PoLang/Features/Camera/CameraPlaceholderView.swift`：

```swift
import SwiftUI

struct CameraPlaceholderView: View {
    var body: some View {
        Text("相机（Task 12-19 实现）")
            .accessibilityIdentifier("cameraPlaceholder")
    }
}

#Preview { CameraPlaceholderView() }
```

把三个 Swift 文件按目录加入 Xcode 工程（拖拽勾选 Copy items + target PoLang）。

- [ ] **Step 4: 写 iosApp/.gitignore**

```
xcuserdata/
*.xcuserstate
DerivedData/
Frameworks/MNN.framework/
!Frameworks/.gitkeep
```

（MNN.framework 10MB 二进制不入 git——Task 6 由构建脚本产出/拷贝；SharedKit.xcframework 同理不入库。）

- [x] **Step 5: 编译 + 模拟器运行验证** ✅ GLM 完成（`xcodebuild build` BUILD SUCCEEDED + 7 XCTest passed）

Run: `xcodebuild -project iosApp/PoLang.xcodeproj -scheme PoLang -destination 'platform=iOS Simulator,name=iPhone 16' build`
Expected: `** BUILD SUCCEEDED **`。

Run: 模拟器启动 App（Xcode Run 或 `xcrun simctl install/launch`）→ 截图
Expected: 两个 Tab「相册/相机」可见，各显示占位文案。

- [ ] **Step 6: Commit**

```bash
git add iosApp/
git commit -m "feat(ios): PoLang Xcode 工程骨架 + Tab 骨架（Task 2）"
```

---

## Task 3: SharedKit embed 集成 + SharedBridge 冒烟

> **✅ K3 完成记录（2026-08-08，commit `112c1e92` + 勾选 commit）**：阻塞解除（camera-track 合并入 `739ac8cf`）。实际路径与原计划两处偏差：① 工程为 XcodeGen 生成（`project.yml`），embed 走 yml `dependencies: framework: ... embed: true` + `preBuildScripts` 挂 `build-shared-kit.sh`，无 GUI 步骤；② 冒烟 XCTest 2 项（smokeIds 递增 + AccessState 四态单例相等性）。**全量 `xcodebuild test` 11/11 通过**（含 GLM MediaPipe 7 项 + 相册权限/分组 2 项）。typecheck 修复 4 处见 Step 5 注；`build-shared-kit.sh` 踩坑修复见 Step 2 注。

**Files:**
- Modify: `iosApp/PoLang.xcodeproj`（GUI：加 framework + Run Script 阶段）
- Create: `iosApp/scripts/build-shared-kit.sh`
- Create: `iosApp/PoLang/SharedBridge/KotlinBridge.swift`
- Test: `iosApp/PoLangTests/KotlinBridgeTests.swift`

- [x] **Step 1: embed SharedKit.xcframework**（K3 注：XcodeGen yml 声明式 embed，Debug 链 `shared/build/XCFrameworks/debug/`；Release 切换留待首次发版）

GUI：把 `shared/build/XCFrameworks/debug/SharedKit.xcframework` 拖进工程（不勾 Copy items，引用相对路径 `../shared/build/...`）；target → General → Frameworks 里设为 **Embed & Sign**。

（Release 配置后续切 `release/` 产物；日常开发全用 debug——2.3 spike 实测增量 ~6s。）

- [x] **Step 2: 写 Gradle 同步脚本**（K3 注：新增两坑修复——① hash 文件缺失时 BSD find `-newer` 退出码非零 + pipefail + set -e 静默中止，改分支处理 + `|| true`；② Xcode 构建环境无 java/ANDROID_HOME，脚本兜底注入 `/usr/libexec/java_home` 与 `$HOME/Library/Android/sdk`；另加重建后 `touch` framework 强制 embed 重拷）

`iosApp/scripts/build-shared-kit.sh`（chmod +x）：

```bash
#!/bin/bash
# Xcode Run Script 阶段调用：SharedKit debug XCFramework 增量构建
# 只在 shared 源码 hash 变化时触发 Gradle，避免每次 Xcode 编译都跑 Kotlin
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HASH_FILE="$REPO_ROOT/iosApp/build/.shared-kit-hash"
CUR_HASH="$(find "$REPO_ROOT/shared/src" -name '*.kt' -newer "$HASH_FILE" 2>/dev/null | head -1)"
if [ -z "$CUR_HASH" ] && [ -d "$REPO_ROOT/shared/build/XCFrameworks/debug/SharedKit.xcframework" ]; then
    echo "SharedKit up-to-date, skip gradle"
    exit 0
fi
cd "$REPO_ROOT"
./gradlew :shared:assembleSharedKitDebugXCFramework
mkdir -p "$(dirname "$HASH_FILE")" && touch "$HASH_FILE"
```

Xcode：target → Build Phases → + Run Script，命名「Build SharedKit」，内容 `"$SRCROOT/scripts/build-shared-kit.sh"`，**取消勾选** "Based on dependency analysis"（脚本自判增量），移到 Compile Sources 之前。

- [x] **Step 3: 写 SharedBridge 冒烟封装**

`iosApp/PoLang/SharedBridge/KotlinBridge.swift`：

```swift
import Foundation
import SharedKit

/// shared（Kotlin）边界的统一入口。
/// 约定：Kotlin 异常不经 @Throws 导出会 signal 6 崩溃（2.3 spike 坑 1），
/// shared 侧所有跨边界调用已在 Kotlin 内 try/catch 兜底；本层只做类型转接。
enum KotlinBridge {
    /// 冒烟：连续两次取 id，验证 K/N 对象导出与调用链通畅
    static func smokeIds() -> (Int32, Int32) {
        let a = AgentIdGenerator.shared.nextId()
        let b = AgentIdGenerator.shared.nextId()
        return (a, b)
    }
}
```

（`AgentIdGenerator` 是 shared commonMain 的 `expect object`，导出为 `SharedKitAgentIdGenerator`，Swift 名 `AgentIdGenerator.shared`。）

- [x] **Step 4: 写 XCTest**（K3 注：加第 2 项 AccessState 四态单例相等性）

`iosApp/PoLangTests/KotlinBridgeTests.swift`：

```swift
import XCTest
@testable import PoLang

final class KotlinBridgeTests: XCTestCase {
    func testSmokeIdsAreIncreasing() {
        let (a, b) = KotlinBridge.smokeIds()
        XCTAssertGreaterThan(b, a, "AgentIdGenerator 连续调用应递增")
    }
}
```

- [x] **Step 5: 跑测试验证集成**（✅ 11/11 passed。相册段 Swift 首跑 typecheck 修 4 处：`presentLimitedLibraryPicker` 在 **PhotosUI** 扩展（非 Photos）；`GalleryAccessState.map` tuple switch 穷举补 `(.limited,.addOnly)`→denied；`GalleryGridView` 缺 `import SharedKit`；init 解耦 AppContainer 改默认直构 repository——**Task 4 需切回 `container.mediaRepository`**）

Run: `xcodebuild -project iosApp/PoLang.xcodeproj -scheme PoLang -destination 'platform=iOS Simulator,name=iPhone 16' test`
Expected: `** TEST SUCCEEDED **`，1 test passed（证明 embed/link/签名/调用链全通）。

- [x] **Step 6: Commit**（`112c1e92`）

```bash
git add iosApp/
git commit -m "feat(ios): SharedKit embed 集成 + KotlinBridge 冒烟测试（Task 3）"
```

---

## Task 4: 调试与组合根基建（DebugOverlay / AppContainer / I18N / Privacy Manifest）

**Files:**
- Create: `iosApp/PoLang/App/DebugOverlay.swift`
- Create: `iosApp/PoLang/DI/AppContainer.swift`
- Create: `iosApp/PoLang/Resources/Localizable.xcstrings`
- Create: `iosApp/PrivacyInfo.xcprivacy`
- Test: `iosApp/PoLangTests/AppContainerTests.swift`

- [ ] **Step 1: 写 DebugOverlay（状态画屏，spike 调试 SOP 固化）**

`iosApp/PoLang/App/DebugOverlay.swift`：

```swift
import SwiftUI

/// iOS 设备日志工具链不可用（spike 实测），内部状态直接画屏。
/// 用法：DebugOverlayState.shared.set("camera.fps", "30.0")，
/// 在根视图叠 .overlay(alignment: .topLeading) { DebugOverlayView() }
@MainActor
final class DebugOverlayState: ObservableObject {
    static let shared = DebugOverlayState()
    @Published private(set) var entries: [(key: String, value: String)] = []
    private var map: [String: String] = [:]
    var isEnabled = true  // Release/TestFlight 可关

    func set(_ key: String, _ value: String) {
        guard isEnabled else { return }
        map[key] = value
        entries = map.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }
}

struct DebugOverlayView: View {
    @ObservedObject var state = DebugOverlayState.shared

    var body: some View {
        if state.isEnabled && !state.entries.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(state.entries, id: \.key) { entry in
                    Text("\(entry.key): \(entry.value)")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(.green)
                }
            }
            .padding(6)
            .background(Color.black.opacity(0.55))
            .padding(.top, 48)
            .padding(.leading, 8)
            .allowsHitTesting(false)
        }
    }
}
```

`MainTabView` 的 body 末尾加 `.overlay(alignment: .topLeading) { DebugOverlayView() }`。

- [ ] **Step 2: 写 AppContainer（组合根，spec D7 模式）**

`iosApp/PoLang/DI/AppContainer.swift`：

```swift
import Foundation
import SharedKit

/// 组合根：shared 接口的 iOS actual 在此构造并注入。
/// shared 不知道任何 iOS 类型（spec §2.3）。各 feature 的实际注入在对应 Task 追加。
@MainActor
final class AppContainer: ObservableObject {
    static let shared = AppContainer()

    /// shared 平台原语（DispatcherProvider 等经 SharedDispatcherProvider 全局惰性）
    let dispatcherProvider: DispatcherProvider

    private init() {
        self.dispatcherProvider = SharedDispatcherProvider.shared.instance
    }
}
```

`PoLangApp` 改为注入环境：

```swift
@main
struct PoLangApp: App {
    @StateObject private var container = AppContainer.shared

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(container)
        }
    }
}
```

- [ ] **Step 3: 写 AppContainer 单测**

`iosApp/PoLangTests/AppContainerTests.swift`：

```swift
import XCTest
@testable import PoLang

@MainActor
final class AppContainerTests: XCTestCase {
    func testContainerProvidesDispatchers() {
        XCTAssertNotNil(AppContainer.shared.dispatcherProvider)
    }
}
```

- [ ] **Step 4: 建三语字符串目录**

GUI：Xcode → New File → String Catalog，命名 `Localizable`，放 `iosApp/PoLang/Resources/`。加 Chinese(Simplified)/English/Japanese 三语言（Project → Info → Localizations）。先把现有三处文案入目录：`相册`/`相机`/`设置`（Tab 与后续页面用，key 用英文原文，如 `"Gallery" = 相册/相册/アルバム`）。

MainTabView 的 Label 改为 `Label(String(localized: "Gallery"), systemImage: ...)` 等。[I18N] 红线：此后所有 UI 文案一律走 `String(localized:)`，禁硬编码——review 门禁项。

- [ ] **Step 5: 写 PrivacyInfo.xcprivacy**

`iosApp/PrivacyInfo.xcprivacy`（加入 target Resources）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>NSPrivacyTracking</key>
    <false/>
    <key>NSPrivacyAccessedAPITypes</key>
    <array>
        <dict>
            <key>NSPrivacyAccessedAPIType</key>
            <string>NSPrivacyAccessedAPICategoryFileTimestamp</string>
            <key>NSPrivacyAccessedAPITypeReasons</key>
            <array><string>DDA9.1</string></array>
        </dict>
        <dict>
            <key>NSPrivacyAccessedAPIType</key>
            <string>NSPrivacyAccessedAPICategoryDiskSpace</string>
            <key>NSPrivacyAccessedAPITypeReasons</key>
            <array><string>E174.1</string></array>
        </dict>
    </array>
</dict>
</plist>
```

同时补 Info.plist 权限用途文案（三语走 InfoPlist.xcstrings 或后续 Task 补）：`NSCameraUsageDescription`、`NSPhotoLibraryUsageDescription`、`NSPhotoLibraryAddUsageDescription`——**Task 7/12 用到前必须有**，本 Task 先占位英文/中文/日文三语。

- [ ] **Step 6: 编译 + 测试**

Run: `xcodebuild -project iosApp/PoLang.xcodeproj -scheme PoLang -destination 'platform=iOS Simulator,name=iPhone 16' test`
Expected: `** TEST SUCCEEDED **`（累计 2 tests）。

- [ ] **Step 7: Commit**

```bash
git add iosApp/
git commit -m "feat(ios): DebugOverlay/AppContainer/三语 xcstrings/Privacy Manifest 基建（Task 4）"
```

---

## Task 5: CI + ios-dev-loop 闭环脚本

**Files:**
- Create: `scripts/ios-dev-loop.sh`
- Modify: CI 配置（`.github/workflows/` 现有 Android workflow 旁加 iOS job；若无 gh Actions 则用 `scripts/ai-gate.sh` 增量）

- [ ] **Step 1: 写 ios-dev-loop.sh（对标 scripts/auto-dev-loop.sh）**

`scripts/ios-dev-loop.sh`（chmod +x）：

```bash
#!/bin/bash
# iOS 闭环验证：编译 → 安装模拟器 → 启动 → 截图 → （可选）基线对比
# 用法：./scripts/ios-dev-loop.sh [截图名]
set -euo pipefail
cd "$(dirname "$0")/.."
SCHEME=PoLang
PROJ=iosApp/PoLang.xcodeproj
DEST='platform=iOS Simulator,name=iPhone 16'
SHOT=${1:-ios-loop}
mkdir -p tmp/shots

echo "== build =="
xcodebuild -project "$PROJ" -scheme "$SCHEME" -destination "$DEST" build -quiet

echo "== install & launch =="
APP_PATH=$(xcodebuild -project "$PROJ" -scheme "$SCHEME" -destination "$DEST" -showBuildSettings -quiet \
    | awk -F' = ' '/TARGET_BUILD_DIR/{d=$2} /WRAPPER_NAME/{w=$2} END{print d"/"w}')
xcrun simctl boot "iPhone 16" 2>/dev/null || true
xcrun simctl install booted "$APP_PATH"
xcrun simctl launch booted com.mamba.picme
sleep 5

echo "== screenshot =="
xcrun simctl io booted screenshot "tmp/shots/${SHOT}.png"
echo "OK: tmp/shots/${SHOT}.png"
```

- [ ] **Step 2: 跑通脚本**

Run: `./scripts/ios-dev-loop.sh task5-smoke`
Expected: 输出 `OK: tmp/shots/task5-smoke.png`；ReadMediaFile 查看截图，Tab 骨架 + DebugOverlay（空）可见。

- [ ] **Step 3: CI 加 iOS build job**

在现有 CI 配置中追加（无签名 build-only）：

```yaml
  ios-build:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :shared:assembleSharedKitDebugXCFramework
      - run: xcodebuild -project iosApp/PoLang.xcodeproj -scheme PoLang -destination 'generic/platform=iOS Simulator' build
```

（若仓库 CI 不是 gh Actions，则把等价两行加进 `scripts/ai-gate.sh` 的 iOS 段。）

- [ ] **Step 4: Commit**

```bash
git add scripts/ios-dev-loop.sh .github/workflows/ 2>/dev/null || git add scripts/ios-dev-loop.sh scripts/ai-gate.sh
git commit -m "ci(ios): ios-dev-loop 闭环脚本 + CI build job（Task 5）"
```

---

## Task 6: 引擎产物收编（MNN / sentencepiece / 美颜 assets / MediaPipe 模型）

**Files:**
- Create: `iosApp/Frameworks/.gitkeep`（MNN.framework 由脚本拷入，不入 git）
- Create: `engines/mnn-core/ios/build-ios-framework.sh`
- Create: `engines/sentencepiece/ios/CMakeLists.txt`（从 tmp 搬正）
- Create: `scripts/ios-fetch-mediapipe-model.sh`
- Modify: `iosApp/PoLang.xcodeproj`（bundle resources 注册美颜 assets）
- Create: `iosApp/PoLang/Features/Camera/Beauty/Assets/`（GLSL shader + lookup PNG 拷贝，从 engines/beauty-engine 同步）

- [ ] **Step 1: MNN.framework 搬正 + 构建脚本收编**

`engines/mnn-core/ios/build-ios-framework.sh`（chmod +x）：

```bash
#!/bin/bash
# MNN iOS framework 构建（真机 arm64）并同步到 iosApp/Frameworks/
# 源码：MNN 官方 build_lib.sh（2.1 spike 已验证全配置覆盖项目需求）
set -euo pipefail
cd "$(dirname "$0")/../.."
MNN_SRC=${MNN_SRC:-engines/mnn-core/src/main/cpp/third_party/MNN}
if [ -d "$MNN_SRC" ]; then
    (cd "$MNN_SRC" && ./build_lib.sh --ios)
    cp -R "$MNN_SRC/build_ios/MNN.framework" ../../iosApp/Frameworks/
else
    # 无 MNN 源码树时，用 2.1 spike 预编译产物兜底
    cp -R tmp/mnn-ios-spike/MNN.framework iosApp/Frameworks/
fi
ls -lh iosApp/Frameworks/MNN.framework/MNN
```

Run: `./engines/mnn-core/ios/build-ios-framework.sh`
Expected: `iosApp/Frameworks/MNN.framework/MNN` 存在（~10MB）。

> Phase 5 不集成 MNN 推理（修正后相机美颜走 MediaPipe）；本步仅为 Phase 6.1 铺路 + tmp/ 产物转正。

- [ ] **Step 2: sentencepiece iOS 构建归档**

```bash
mkdir -p engines/sentencepiece/ios
cp tmp/mnn-ios-spike/spm-ios-build/CMakeLists.txt engines/sentencepiece/ios/
```

在文件头加注释注明来源（`tmp/mnn-ios-spike/spm-ios-build/`，2.2 spike 验证产物，`libsentencepiece-static.a` 1.6MB arm64）。Phase 6 使用前不需改动。

- [ ] **Step 3: MediaPipe face_landmarker.task 模型获取**

Android 侧模型路径 `mediapipe/face_landmarker.task`（`MediaPipeFaceDetector.kt:34`，构建期下载，不入库）。`scripts/ios-fetch-mediapipe-model.sh`（chmod +x）：

```bash
#!/bin/bash
# 下载 MediaPipe Face Landmarker 模型到 iosApp bundle 资源目录
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=iosApp/PoLang/Features/Camera/Beauty/Assets/face_landmarker.task
mkdir -p "$(dirname "$DEST")"
if [ ! -f "$DEST" ]; then
    curl -L -o "$DEST" \
      "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float32/1/face_landmarker.task"
fi
ls -lh "$DEST"
```

Run: `./scripts/ios-fetch-mediapipe-model.sh`
Expected: 文件存在（~4MB）。模型文件不入 git（`.gitignore` 加 `*.task`），脚本幂等。

- [ ] **Step 4: 美颜 assets 同步到 iOS bundle**

```bash
mkdir -p iosApp/PoLang/Features/Camera/Beauty/Assets/shaders/style
cp engines/beauty-engine/src/main/assets/shaders/*.glsl iosApp/PoLang/Features/Camera/Beauty/Assets/shaders/
cp engines/beauty-engine/src/main/assets/shaders/style/*.glsl iosApp/PoLang/Features/Camera/Beauty/Assets/shaders/style/
cp engines/beauty-engine/src/main/assets/lookup_*.png iosApp/PoLang/Features/Camera/Beauty/Assets/
mkdir -p iosApp/PoLang/Features/Camera/Beauty/Assets/filters
cp -R androidApp/src/main/assets/filters/ iosApp/PoLang/Features/Camera/Beauty/Assets/filters/
```

（GLSL 源文本入 git 做翻译基准；翻译产物 `.metal` 也入 git。lookup PNG 二进制小（<1MB），入 git。）

Xcode GUI：把 `Assets/` 目录以 **folder reference（蓝色文件夹）** 加入 target，保持子目录结构（shader concat 按路径读）。

- [ ] **Step 5: Commit**

```bash
git add engines/mnn-core/ios/ engines/sentencepiece/ios/ scripts/ios-fetch-mediapipe-model.sh iosApp/
git commit -m "chore(ios): MNN/sentencepiece/MediaPipe 模型/美颜 assets 收编（Task 6）"
```

---

## Task 7: 相册数据通路（shared iosMain IosMediaRepository + Swift PHPhotoLibrary 桥）

**Files:**
- Create: `shared/src/iosMain/kotlin/com/mamba/picme/data/IosMediaRepository.kt`
- Create: `shared/src/iosMain/kotlin/com/mamba/picme/data/IosMediaRepositoryBridge.kt`
- Create: `shared/src/iosMain/kotlin/com/mamba/picme/shared/FlowWatchers.kt`
- Create: `iosApp/PoLang/Platform/PhMediaBridge.swift`
- Create: `iosApp/PoLang/Platform/ThumbnailLoader.swift`
- Test: `shared/src/iosTest/kotlin/com/mamba/picme/data/IosMediaRepositoryTest.kt`
- Test: `iosApp/PoLangTests/PhMediaBridgeTests.swift`

**设计**：shared 的 `MediaRepository`（Phase 4 Task 4 产物，Flow 接口）的 iOS actual 写在 **Kotlin iosMain**，通过 ObjC 导出的 `IosMediaRepositoryBridge` 协议把 Photos framework 调用下沉到 Swift——Swift 只见 5 字段 DTO，不见 Kotlin Flow；相册 presentation（Swift）反向经 `FlowWatcher` 订阅 Flow。

- [x] **Step 1: 写桥协议（shared/iosMain）**（K3 注：DTO 去掉 `id` 字段——Swift `hashValue` 每次启动随机化，id 改由 Kotlin 侧 `localIdentifier.hashCode()` 派生；桥协议增补 `deleteMedia(localIdentifiers:)` 承载 PHAssetChangeRequest 删除）

`shared/src/iosMain/kotlin/com/mamba/picme/data/IosMediaRepositoryBridge.kt`：

```kotlin
package com.mamba.picme.data

import com.mamba.picme.domain.model.AccessState

/** Swift（Photos framework）→ Kotlin 的桥。DTO 字段全原始类型，K/N 导出友好。 */
data class IosMediaItem(
    val id: Long,
    val localIdentifier: String,   // PHAsset.localIdentifier，作 MediaAsset.uri
    val mediaType: String,         // "PHOTO" | "VIDEO"
    val captureDateMs: Long,
    val durationMs: Long? = null
)

interface IosMediaRepositoryBridge {
    fun currentAccessState(): AccessState
    fun fetchAllMedia(): List<IosMediaItem>
    fun requestReadWriteAuthorization()   // 异步弹窗；完成后经 changeListener 通知
    fun addChangeListener(listener: () -> Unit)
}
```

- [x] **Step 2: 写 IosMediaRepository（shared/iosMain）**（K3 注：按 Task 0 核对的接口全集覆写；Android 删除授权三方法 no-op；`insertMedia` 返回 -1 待相机段落地）

`shared/src/iosMain/kotlin/com/mamba/picme/data/IosMediaRepository.kt`：

```kotlin
package com.mamba.picme.data

import com.mamba.picme.domain.model.AccessState
import com.mamba.picme.domain.model.MediaAsset
import com.mamba.picme.domain.model.MediaType
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class IosMediaRepository(
    private val bridge: IosMediaRepositoryBridge
) : MediaRepository {

    val accessState: AccessState get() = bridge.currentAccessState()

    override val allMedia: Flow<List<MediaAsset>> = callbackFlow {
        trySend(fetch())
        bridge.addChangeListener { trySend(fetch()) }
        awaitClose { }
    }

    private fun fetch(): List<MediaAsset> = bridge.fetchAllMedia().map { item ->
        MediaAsset(
            id = item.id,
            uri = item.localIdentifier,
            type = if (item.mediaType == "VIDEO") MediaType.VIDEO else MediaType.PHOTO,
            captureDate = item.captureDateMs,
            fileName = item.localIdentifier,
            duration = item.durationMs
        )
    }

    override suspend fun getMediaById(id: Long): MediaAsset? =
        bridge.fetchAllMedia().firstOrNull { it.id == id }?.let { item ->
            MediaAsset(
                id = item.id, uri = item.localIdentifier,
                type = MediaType.PHOTO, captureDate = item.captureDateMs,
                fileName = item.localIdentifier
            )
        }
}
```

> ⚠️ Task 0 已核对 Phase 4 的 `MediaRepository` 实际方法集——上覆写仅示例 `allMedia`/`getMediaById`；其余接口方法按 Task 0 核对结果逐个覆写（删除/插入走 `PHAssetChangeRequest`，在 Step 4 桥侧补方法）。`MediaAsset` 字段名以 commonMain 实际定义为准。

- [x] **Step 3: 写 FlowWatcher（Kotlin Flow → Swift 可消费）**（导出形态已核对：`FlowWatchersKt.watch(_:onEach:)`）

`shared/src/iosMain/kotlin/com/mamba/picme/shared/FlowWatchers.kt`：

```kotlin
package com.mamba.picme.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Swift 侧持有并 cancel()；避免 Kotlin Job 直接暴露。 */
class FlowWatcher(private val job: Job) {
    fun cancel() { job.cancel() }
}

fun <T> Flow<T>.watch(onEach: (T) -> Unit): FlowWatcher {
    val job = CoroutineScope(Dispatchers.Default).launch {
        collect { onEach(it) }
    }
    return FlowWatcher(job)
}
```

- [x] **Step 4: 写 Kotlin 侧 iosTest**（K3 注：Intel 主机 `iosSimulatorArm64Test` 被 KGP 禁用，改 `:shared:iosX64Test` ✅ 6 用例全过：DTO 映射/id 派生/权限快照/getMediaById/删除解析/no-op）

`shared/src/iosTest/kotlin/com/mamba/picme/data/IosMediaRepositoryTest.kt`：

```kotlin
package com.mamba.picme.data

import com.mamba.picme.domain.model.AccessState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeBridge : IosMediaRepositoryBridge {
    override fun currentAccessState(): AccessState = AccessState.Full
    override fun fetchAllMedia(): List<IosMediaItem> = listOf(
        IosMediaItem(1L, "ABC-1", "PHOTO", 1000L),
        IosMediaItem(2L, "ABC-2", "VIDEO", 2000L, 5000L)
    )
    override fun requestReadWriteAuthorization() {}
    override fun addChangeListener(listener: () -> Unit) {}
}

class IosMediaRepositoryTest {
    @Test
    fun allMediaMapsDtoToDomain() = runTest {
        val repo = IosMediaRepository(FakeBridge())
        val list = repo.allMedia.first()
        assertEquals(2, list.size)
        assertEquals("ABC-1", list[0].uri)
        assertEquals(com.mamba.picme.domain.model.MediaType.VIDEO, list[1].type)
    }
}
```

Run: `./gradlew :shared:iosSimulatorArm64Test`
Expected: PASS（`AccessState.Full` 的具体构造以 commonMain 实际 sealed 定义为准）。

- [x] **Step 5: 写 Swift 桥实现**（K3 注：`AccessStateFull.shared` 扁平导出名已按 SharedKit.h 核对；`swiftc -parse` 过；xcodebuild 编译待 Task 3 工程）

`iosApp/PoLang/Platform/PhMediaBridge.swift`：

```swift
import Foundation
import Photos
import SharedKit

/// IosMediaRepositoryBridge 的 Photos framework 实现。
@objc final class PhMediaBridge: NSObject, IosMediaRepositoryBridge {
    private var changeListener: (() -> Void)?

    override init() {
        super.init()
        PHPhotoLibrary.shared().register(self)
    }

    deinit { PHPhotoLibrary.shared().unregisterChangeObserver(self) }

    func currentAccessState() -> AccessState {
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized: return AccessState.Full.shared
        case .limited: return AccessState.Limited.shared
        case .denied, .restricted: return AccessState.Denied.shared
        case .notDetermined: return AccessState.Denied.shared  // 未决按拒绝态呈现，请求后刷新
        @unknown default: return AccessState.Denied.shared
        }
    }

    func fetchAllMedia() -> [IosMediaItem] {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let result = PHAsset.fetchAssets(with: opts)
        var items: [IosMediaItem] = []
        items.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            items.append(IosMediaItem(
                id: Int64(truncatingIfNeeded: asset.localIdentifier.hashValue),
                localIdentifier: asset.localIdentifier,
                mediaType: asset.mediaType == .video ? "VIDEO" : "PHOTO",
                captureDateMs: Int64((asset.creationDate?.timeIntervalSince1970 ?? 0) * 1000),
                durationMs: asset.mediaType == .video ? Int64(asset.duration * 1000) : nil
            ))
        }
        return items
    }

    func requestReadWriteAuthorization() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] _ in
            DispatchQueue.main.async { self?.changeListener?() }
        }
    }

    func addChangeListener(listener: @escaping () -> Void) {
        self.changeListener = listener
    }
}

extension PhMediaBridge: PHPhotoLibraryChangeObserver {
    func photoLibraryDidChange(_ changeInstance: PHChange) {
        DispatchQueue.main.async { [weak self] in self?.changeListener?() }
    }
}
```

> `AccessState.Full.shared` 的 ObjC 导出形态以 Task 0 核对的 sealed 实现为准（data object → `.shared`；class → 构造器）。若 `AccessState` 在 Kotlin 侧是 expect/枚举，按实际调整。

- [x] **Step 6: 写 ThumbnailLoader（Swift 侧，不进 shared）**（补注：`isNetworkAccessAllowed = false`，[PRIVACY] 不触发 iCloud 拉取）

`iosApp/PoLang/Platform/ThumbnailLoader.swift`：

```swift
import Foundation
import Photos
import UIKit

/// PHCachingImageManager 缩略图加载；键 = PHAsset.localIdentifier。
/// 缩略图是纯平台行为，presentation 层自治（spec S1），不经 shared。
@MainActor
final class ThumbnailLoader {
    static let shared = ThumbnailLoader()
    private let manager = PHCachingImageManager()

    func thumbnail(for localIdentifier: String, size: CGSize) async -> UIImage? {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = result.firstObject else { return nil }
        return await withCheckedContinuation { cont in
            let opts = PHImageRequestOptions()
            opts.deliveryMode = .opportunistic
            opts.isNetworkAccessAllowed = false
            var resumed = false
            manager.requestImage(for: asset, targetSize: size, contentMode: .aspectFill,
                                 options: opts) { image, _ in
                // opportunistic 可能回调两次（先低清后高清），只取第一次非 nil
                guard !resumed, let image else { return }
                resumed = true
                cont.resume(returning: image)
            }
        }
    }

    func startCaching(identifiers: [String], size: CGSize) {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: identifiers, options: nil)
        manager.startCachingImages(for: assets.objects(at: IndexSet(integersIn: 0..<assets.count)),
                                   targetSize: size, contentMode: .aspectFill, options: nil)
    }
}
```

- [ ] **Step 7: 接 AppContainer + 真机/模拟器验证**（⛔ 阻塞：AppContainer/Xcode 工程在 GLM 分支；wiring 契约——`AppContainer` 增 `let mediaRepository: IosMediaRepository`，init 内 `IosMediaRepository(bridge: PhMediaBridge())`）

`AppContainer` 的 init 追加：

```swift
let mediaRepository: IosMediaRepository
// init 内：
self.mediaRepository = IosMediaRepository(bridge: PhMediaBridge())
```

模拟器跑通（模拟器相册预置照片即可）：

```swift
// 临时验证（在 MainTabView onAppear，验证后删除）：
Task {
    let list = AppContainer.shared.mediaRepository.fetchAllMedia()
    DebugOverlayState.shared.set("gallery.count", "\(list.count)")
}
```

Expected: DebugOverlay 显示模拟器照片数 > 0。

- [x] **Step 8: Commit**（K3 注：随 Task 7 提交）

```bash
git add shared/src/iosMain/ shared/src/iosTest/ iosApp/
git commit -m "feat(ios): 相册数据通路——IosMediaRepository + PhMediaBridge + FlowWatcher（Task 7）"
```

---

## Task 8: 相册权限状态机（GalleryPermissionStore）

**Files:**
- Create: `iosApp/PoLang/Features/Gallery/GalleryPermissionStore.swift`
- Test: `iosApp/PoLangTests/GalleryPermissionStoreTests.swift`

- [x] **Step 1: 写状态机（映射函数纯化，可无设备单测）**（K3 注：另加 DebugOverlay 画屏 `gallery.permission`）

`iosApp/PoLang/Features/Gallery/GalleryPermissionStore.swift`：

```swift
import Foundation
import Photos
import Combine

/// 相册权限 UI 态（spec §4.2 四态；映射 shared AccessState，但 UI 层用本 Swift 枚举）
enum GalleryAccessState: Equatable {
    case full, limited, addOnly, denied, notDetermined

    /// 纯函数，单测直接打表（PHAuthorizationStatus 不可构造，用自定义输入枚举解耦）
    static func map(status: AuthStatusInput, level: AuthLevelInput) -> GalleryAccessState {
        switch (status, level) {
        case (.authorized, .readWrite): return .full
        case (.limited, .readWrite): return .limited
        case (.authorized, .addOnly): return .addOnly
        case (.notDetermined, _): return .notDetermined
        case (.denied, _), (.restricted, _): return .denied
        }
    }
}

enum AuthStatusInput { case authorized, limited, denied, restricted, notDetermined }
enum AuthLevelInput { case readWrite, addOnly }

@MainActor
final class GalleryPermissionStore: ObservableObject {
    @Published private(set) var state: GalleryAccessState = .notDetermined

    func refresh() {
        let raw = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        let status: AuthStatusInput = switch raw {
        case .authorized: .authorized
        case .limited: .limited
        case .denied: .denied
        case .restricted: .restricted
        default: .notDetermined
        }
        state = GalleryAccessState.map(status: status, level: .readWrite)
    }

    func requestAccess() async {
        await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        refresh()
    }

    func presentLimitedLibraryPicker(from vc: UIViewController) {
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: vc)
    }
}
```

- [x] **Step 2: 写单测（打表覆盖全部分支）**（K3 注：XCTest 已写，xcodebuild 执行待 Task 3 工程）

`iosApp/PoLangTests/GalleryPermissionStoreTests.swift`：

```swift
import XCTest
@testable import PoLang

final class GalleryPermissionStoreTests: XCTestCase {
    func testMapAllBranches() {
        typealias S = GalleryAccessState
        XCTAssertEqual(S.map(status: .authorized, level: .readWrite), .full)
        XCTAssertEqual(S.map(status: .limited, level: .readWrite), .limited)
        XCTAssertEqual(S.map(status: .authorized, level: .addOnly), .addOnly)
        XCTAssertEqual(S.map(status: .notDetermined, level: .readWrite), .notDetermined)
        XCTAssertEqual(S.map(status: .denied, level: .readWrite), .denied)
        XCTAssertEqual(S.map(status: .restricted, level: .addOnly), .denied)
    }
}
```

Run: `xcodebuild ... test`（同 Task 3 命令）
Expected: PASS，累计 4 tests。

- [x] **Step 3: Commit**（K3 注：随 Task 8 提交）

```bash
git add iosApp/
git commit -m "feat(ios): 相册权限状态机四态 + 映射打表单测（Task 8）"
```

---

## Task 9: 相册网格页（GalleryViewModel + 网格/分组视图）

**Files:**
- Create: `iosApp/PoLang/Features/Gallery/GalleryViewModel.swift`
- Create: `iosApp/PoLang/Features/Gallery/GalleryGridView.swift`
- Create: `iosApp/PoLang/Features/Gallery/ThumbnailView.swift`
- Modify: `iosApp/PoLang/Features/Main/MainTabView.swift`（GalleryPlaceholderView → GalleryGridView）
- Test: `iosApp/PoLangTests/GalleryViewModelTests.swift`

- [x] **Step 1: 写 GalleryViewModel**（K3 注：分组在 Swift 侧（`GetGroupedMediaUseCase` commonMain 缺失，见 Task 0 记录）；`FlowWatchersKt.watch` 订阅）

`iosApp/PoLang/Features/Gallery/GalleryViewModel.swift`：

```swift
import Foundation
import SharedKit
import Combine

/// UI 态唯一持有者（spec S4 单一状态源）。
/// 数据：shared MediaRepository Flow（FlowWatcher 订阅）；
/// 分组：shared GetGroupedMediaUseCase（签名以 Task 0 核对为准）。
@MainActor
final class GalleryViewModel: ObservableObject {
    struct DayGroup: Identifiable, Equatable {
        let id: String          // "yyyy-MM-dd"
        let items: [MediaAsset]
    }

    @Published private(set) var groups: [DayGroup] = []
    @Published private(set) var isLoading = true

    private var watcher: FlowWatcher?
    private let repository: IosMediaRepository

    init(repository: IosMediaRepository) {
        self.repository = repository
    }

    func start() {
        watcher = repository.allMedia.watch { [weak self] assets in
            Task { @MainActor in
                self?.applyGrouping(assets)
            }
        }
    }

    func stop() { watcher?.cancel() }

    /// 按日分组（与 Android GroupingMode.DATE 对齐：captureDate 降序，同日一组）。
    /// shared use case 可用后替换为本函数的等价调用，双端分组边界必须一致（spec §4.6 验收）。
    private func applyGrouping(_ assets: [MediaAsset]) {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        var map: [String: [MediaAsset]] = [:]
        for a in assets {
            let key = fmt.string(from: Date(timeIntervalSince1970: TimeInterval(a.captureDate) / 1000))
            map[key, default: []].append(a)
        }
        groups = map.sorted { $0.key > $1.key }.map { DayGroup(id: $0.key, items: $0.value) }
        isLoading = false
        DebugOverlayState.shared.set("gallery.count", "\(assets.count)")
    }
}
```

- [x] **Step 2: 写 ViewModel 单测（分组逻辑纯函数验证）**（K3 注：时间戳取 UTC 正午保证任意时区分组边界稳定；xcodebuild 执行待 Task 3 工程）

`iosApp/PoLangTests/GalleryViewModelTests.swift`：

```swift
import XCTest
@testable import PoLang
import SharedKit

/// 桩 bridge：固定两条不同日期的数据（2026-08-07 / 2026-08-06）
private final class StubBridge: NSObject, IosMediaRepositoryBridge {
    func currentAccessState() -> AccessState { AccessState.Full.shared }
    func fetchAllMedia() -> [IosMediaItem] {
        [
            IosMediaItem(id: 1, localIdentifier: "L-1", mediaType: "PHOTO",
                         captureDateMs: 1_786_003_200_000),  // 2026-08-07 00:00 UTC
            IosMediaItem(id: 2, localIdentifier: "L-2", mediaType: "PHOTO",
                         captureDateMs: 1_785_916_800_000)   // 2026-08-06 00:00 UTC
        ]
    }
    func requestReadWriteAuthorization() {}
    func addChangeListener(listener: @escaping () -> Void) {}
}

@MainActor
final class GalleryViewModelTests: XCTestCase {
    func testGroupingSortsDescAndGroupsByDay() async throws {
        let vm = GalleryViewModel(repository: IosMediaRepository(bridge: StubBridge()))
        vm.start()
        try await Task.sleep(nanoseconds: 500_000_000)  // 等 Flow 首帧
        vm.stop()
        XCTAssertEqual(vm.groups.count, 2)
        XCTAssertGreaterThan(vm.groups[0].id, vm.groups[1].id, "分组按日期降序")
        XCTAssertEqual(vm.groups[0].items.count + vm.groups[1].items.count, 2)
        XCTAssertFalse(vm.isLoading)
    }
}
```

> `AccessState.Full.shared` 形态同 Task 7 Step 5 的注（以 commonMain 实际 sealed 定义为准）。

- [x] **Step 3: 写网格视图（AI 生成，Preview + accessibilityIdentifier 全配）**（K3 注：文案全部 `String(localized:)` 英文原文键，待 GLM 侧 xcstrings 补三语——需新增键：Authorize Photo Access / Add-Only Access Hint / Manage Accessible Photos / Photo Library Unavailable / Open Settings；MainTabView 替换在 GLM 分支，随会合落地）

`iosApp/PoLang/Features/Gallery/ThumbnailView.swift`：

```swift
import SwiftUI

struct ThumbnailView: View {
    let localIdentifier: String
    let size: CGSize
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Color.gray.opacity(0.2)
            }
        }
        .frame(width: size.width, height: size.height)
        .clipped()
        .task {
            image = await ThumbnailLoader.shared.thumbnail(for: localIdentifier, size: size)
        }
        .accessibilityIdentifier("thumb_\(localIdentifier)")
    }
}
```

`iosApp/PoLang/Features/Gallery/GalleryGridView.swift`：

```swift
import SwiftUI

struct GalleryGridView: View {
    @StateObject private var vm: GalleryViewModel
    @StateObject private var permission = GalleryPermissionStore()
    private let columns = [GridItem(.flexible(), spacing: 2),
                           GridItem(.flexible(), spacing: 2),
                           GridItem(.flexible(), spacing: 2)]

    init(container: AppContainer) {
        _vm = StateObject(wrappedValue: GalleryViewModel(repository: container.mediaRepository))
    }

    var body: some View {
        NavigationStack {
            Group {
                switch permission.state {
                case .full, .limited:
                    gridBody
                    if permission.state == .limited { limitedBanner }
                case .notDetermined:
                    Button("授权访问相册") { Task { await permission.requestAccess() } }
                        .accessibilityIdentifier("gallery_auth_button")
                case .addOnly:
                    Text("当前仅可添加照片").accessibilityIdentifier("gallery_addonly_hint")
                case .denied:
                    deniedBody
                }
            }
            .navigationTitle(String(localized: "Gallery"))
        }
        .onAppear { permission.refresh(); vm.start() }
        .onDisappear { vm.stop() }
    }

    private var gridBody: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 2, pinnedViews: .sectionHeaders) {
                ForEach(vm.groups) { group in
                    Section(header: Text(group.id)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8).background(.background)
                        .accessibilityIdentifier("group_\(group.id)")) {
                        ForEach(group.items, id: \.uri) { asset in
                            ThumbnailView(localIdentifier: asset.uri,
                                          size: CGSize(width: 200, height: 200))
                        }
                    }
                }
            }
        }
        .accessibilityIdentifier("gallery_grid")
    }

    private var limitedBanner: some View {
        Button("管理可访问照片") {
            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let vc = scene.windows.first?.rootViewController {
                permission.presentLimitedLibraryPicker(from: vc)
            }
        }
        .accessibilityIdentifier("gallery_limited_manage")
    }

    private var deniedBody: some View {
        VStack(spacing: 12) {
            Text("无法访问相册")
            Button("去设置开启") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
        }
        .accessibilityIdentifier("gallery_denied")
    }
}

#Preview("Loading") {
    GalleryGridView(container: AppContainer.shared)
}
```

`MainTabView` 里 `GalleryPlaceholderView()` 替换为 `GalleryGridView(container: container)`（container 从 `@EnvironmentObject` 取）。

- [ ] **Step 4: dev-loop 验证**（⛔ 阻塞：无 Xcode 工程，随 Task 3 冒烟一并补）

Run: `./scripts/ios-dev-loop.sh task9-gallery`
Expected: 截图中网格展示模拟器照片、按日分组 header 正确；无崩溃。

- [x] **Step 5: Commit**（K3 注：随 Task 9/10 提交）

```bash
git add iosApp/ scripts/
git commit -m "feat(ios): 相册网格页——ViewModel/分组/缩略图/权限四态 UI（Task 9）"
```

---

## Task 10: 大图浏览 + 相簿列表

**Files:**
- Create: `iosApp/PoLang/Features/Gallery/MediaPagerView.swift`
- Create: `iosApp/PoLang/Features/Gallery/AlbumListView.swift`
- Modify: `iosApp/PoLang/Features/Gallery/GalleryGridView.swift`（cell 包 NavigationLink）

- [x] **Step 1: 写大图分页浏览**（swiftc -parse 过，xcodebuild 待 Task 3）

`iosApp/PoLang/Features/Gallery/MediaPagerView.swift`：

```swift
import SwiftUI

/// 大图浏览（对标 Android MediaPager）：左右滑动切换，原图异步加载。
struct MediaPagerView: View {
    let items: [MediaAsset]
    @State private var selection: String

    init(items: [MediaAsset], initial: String) {
        self.items = items
        _selection = State(initialValue: initial)
    }

    var body: some View {
        TabView(selection: $selection) {
            ForEach(items, id: \.uri) { asset in
                FullImageView(localIdentifier: asset.uri)
                    .tag(asset.uri)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .automatic))
        .background(.black)
        .accessibilityIdentifier("media_pager")
    }
}

private struct FullImageView: View {
    let localIdentifier: String
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                ProgressView()
            }
        }
        .task {
            image = await ThumbnailLoader.shared.thumbnail(
                for: localIdentifier,
                size: CGSize(width: 1200, height: 1200))  // 接近屏宽的原图档
        }
    }
}
```

- [x] **Step 2: 网格 cell 接入 NavigationLink**（随 GalleryGridView 一并落地）

`GalleryGridView` 的 `ThumbnailView` 外包一层：

```swift
NavigationLink {
    MediaPagerView(items: group.items, initial: asset.uri)
} label: {
    ThumbnailView(localIdentifier: asset.uri, size: CGSize(width: 200, height: 200))
}
.accessibilityIdentifier("cell_\(asset.uri)")
```

- [x] **Step 3: 写相簿列表（Swift 直连 Photos，presentation 自治）**

`iosApp/PoLang/Features/Gallery/AlbumListView.swift`：

```swift
import SwiftUI
import Photos

/// 相簿列表（对标 Android 相簿页）：系统相簿 + 用户相簿，entry 从 Gallery 导航进。
struct AlbumListView: View {
    struct Album: Identifiable {
        let id: String        // collection localIdentifier
        let title: String
        let count: Int
    }

    @State private var albums: [Album] = []

    var body: some View {
        List(albums) { album in
            HStack {
                Text(album.title)
                Spacer()
                Text("\(album.count)").foregroundStyle(.secondary)
            }
            .accessibilityIdentifier("album_\(album.id)")
        }
        .navigationTitle(String(localized: "Albums"))
        .onAppear(perform: load)
    }

    private func load() {
        var result: [Album] = []
        let smart = PHAssetCollection.fetchAssetCollections(
            with: .smartAlbum, subtype: .any, options: nil)
        let user = PHAssetCollection.fetchAssetCollections(
            with: .album, subtype: .any, options: nil)
        [smart, user].forEach { list in
            list.enumerateObjects { collection, _, _ in
                let count = PHAsset.fetchAssets(in: collection, options: nil).count
                result.append(Album(id: collection.localIdentifier,
                                    title: collection.localizedTitle ?? "—",
                                    count: count))
            }
        }
        albums = result
    }
}

#Preview { NavigationStack { AlbumListView() } }
```

`GalleryGridView` toolbar 加入口：

```swift
.toolbar {
    ToolbarItem(placement: .topBarTrailing) {
        NavigationLink(String(localized: "Albums")) { AlbumListView() }
            .accessibilityIdentifier("gallery_albums_entry")
    }
}
```

- [ ] **Step 4: dev-loop 验证 + Commit**（⛔ dev-loop 阻塞同 Task 9 Step 4；代码随 Task 9/10 提交）

Run: `./scripts/ios-dev-loop.sh task10-pager`
Expected: 网格点 cell 进大图可滑动；相簿入口进列表有数据。

```bash
git add iosApp/
git commit -m "feat(ios): 大图分页浏览 + 相簿列表（Task 10）"
```

---

## Task 11: 相册性能实测（5.3 出口）

> **⛔ K3 注（2026-08-08）**：依赖 Xcode 工程 + 真机，随 Task 3 会合后安排；代码侧已预埋调优点（`ThumbnailLoader.startCaching` 预热窗口注释、DebugOverlay `gallery.count` 画屏）。

**Files:**
- 无代码变更；产出记录到本文件勾选备注

- [ ] **Step 1: 真机灌 1000+ 照片**

iPhone 测试机经「照片」导入或用模拟器批量生成（`xcrun simctl` 不支持批量照片时，用真机 AirDrop/导入 1000+ 张）。

- [ ] **Step 2: Instruments 实测**

Xcode → Profile → Instruments（Core Animation FPS + Allocations）：滚动网格全程。
验收（spec §4.5）：滚动 55–60fps；内存峰值 < 500MB；`PHCachingImageManager` 预热生效（快速滚动无大面积灰块）。

- [ ] **Step 3: 不达标处置**

仅调 `ThumbnailLoader.startCaching` 预热窗口（在 `GalleryGridView` 滚动位置变化时，对可见区 ±2 屏 identifiers 调 `startCaching`）——不改架构。达标后记录实测数值。

- [ ] **Step 4: Commit（记录实测结论）**

```bash
git add docs/superpowers/plans/2026-08-08-ios-app-skeleton.md
git commit -m "test(ios): 相册 1000+ 照片性能实测达标（Task 11，实测值见计划备注）"
```

---

## Task 12: 相机采集 → Metal 直渲（spike 基线 Swift 移植）

**Files:**
- Create: `iosApp/PoLang/Features/Camera/Capture/CaptureSessionController.swift`
- Create: `iosApp/PoLang/Features/Camera/Preview/MetalPreviewRenderer.swift`
- Create: `iosApp/PoLang/Features/Camera/Preview/CameraPreviewView.swift`
- Create: `iosApp/PoLang/Features/Camera/Preview/Shaders/yuv.metal`
- Modify: `iosApp/PoLang/Features/Main/MainTabView.swift`（CameraPlaceholderView → CameraPreviewView）
- Test: 真机 dev-loop 截图验证（渲染逻辑无单测，靠 DebugOverlay 状态画屏）

**移植源**：`tmp/beauty-metal-spike/BeautyMetalSpike/main.mm`（298 行已验证管线）+ `Shaders.metal`（BT.601 YUV→RGB）。本 Task 只做**无美颜直渲**，美颜从 Task 13 起逐层叠加。

- [x] **Step 1: 写 YUV→RGB shader（spike 生产版，去调试 bleach）** ✅ GLM 完成

`iosApp/PoLang/Features/Camera/Preview/Shaders/yuv.metal`：

```metal
#include <metal_stdlib>
using namespace metal;

struct Vout {
    float4 position [[position]];
    float2 uv;
};

vertex Vout quad_vertex(uint vid [[vertex_id]]) {
    // 全屏四边形（无 VBO，TriangleStrip）
    float2 pos[4] = { {-1,-1}, {1,-1}, {-1,1}, {1,1} };
    float2 uv[4]  = { {0,1}, {1,1}, {0,0}, {1,0} };
    Vout o;
    o.position = float4(pos[vid], 0, 1);
    o.uv = uv[vid];
    return o;
}

fragment float4 yuv_fragment(Vout in [[stage_in]],
    texture2d<float, access::sample> yTexture  [[texture(0)]],
    texture2d<float, access::sample> uvTexture [[texture(1)]],
    sampler bilinear [[sampler(0)]])
{
    float y  = yTexture.sample(bilinear, in.uv).r;
    float2 cbcr = uvTexture.sample(bilinear, in.uv).rg;
    // BT.601 limited range（与 spike 一致）
    float y1 = 1.164 * (y - 16.0 / 255.0);
    float cb = cbcr.x - 128.0 / 255.0;
    float cr = cbcr.y - 128.0 / 255.0;
    float r = y1 + 1.596 * cr;
    float g = y1 - 0.392 * cb - 0.813 * cr;
    float b = y1 + 2.017 * cb;
    return float4(saturate(float3(r, g, b)), 1.0);
}
```

加入 Xcode target（metal 文件自动编进 default library——与 spike `newDefaultLibraryWithBundle` 路径一致）。

- [ ] **Step 2: 写 CaptureSessionController**

`iosApp/PoLang/Features/Camera/Capture/CaptureSessionController.swift`：

```swift
import Foundation
import AVFoundation

/// 相机采集（spike main.mm startCapture 的 Swift 版）：
/// 720p、YUV bi-planar、丢弃迟到帧、串行队列、Portrait 方向。
final class CaptureSessionController: NSObject {
    let session = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "polang.camera.capture")

    /// 最新帧（渲染线程读取；retain/release 由 ARC 管理 CVPixelBuffer）
    private(set) var currentPixelBuffer: CVPixelBuffer?
    private let bufferLock = NSLock()
    private(set) var frameCount: Int = 0

    var onFirstFrame: (() -> Void)?

    func checkAuthorizationAndStart() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: start(); return true
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            if granted { start() }
            return granted
        default:
            return false
        }
    }

    private func start() {
        queue.async { [self] in
            session.beginConfiguration()
            session.sessionPreset = .hd1280x720
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                       for: .video, position: .back),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input), session.canAddOutput(videoOutput) else {
                session.commitConfiguration()
                DebugOverlayState.shared.set("camera.error", "session config failed")
                return
            }
            session.addInput(input)
            videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            ]
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.setSampleBufferDelegate(self, queue: queue)
            session.addOutput(videoOutput)
            if let conn = videoOutput.connection(with: .video) {
                conn.videoOrientation = .portrait   // spike 踩坑：修正传感器横置
            }
            session.commitConfiguration()
            session.startRunning()
        }
    }

    func stop() { queue.async { [self] in session.stopRunning() } }

    fileprivate func swapBuffer(_ pb: CVPixelBuffer) {
        bufferLock.lock()
        currentPixelBuffer = pb
        bufferLock.unlock()
        frameCount += 1
        if frameCount == 1 { DispatchQueue.main.async { self.onFirstFrame?() } }
    }

    func readBuffer() -> CVPixelBuffer? {
        bufferLock.lock(); defer { bufferLock.unlock() }
        return currentPixelBuffer
    }
}

extension CaptureSessionController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        swapBuffer(pb)
    }
}
```

- [ ] **Step 3: 写 MetalPreviewRenderer**

`iosApp/PoLang/Features/Camera/Preview/MetalPreviewRenderer.swift`：

```swift
import Foundation
import Metal
import MetalKit
import CoreVideo

/// YUV→RGB 直渲（spike drawInMTKView 的 Swift 版）。
/// ⚠️ spike 踩坑：commandQueue 漏初始化 → 黑屏；本类构造即建。
final class MetalPreviewRenderer: NSObject {
    let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private let pipeline: MTLRenderPipelineState
    private let sampler: MTLSamplerState
    private var textureCache: CVMetalTextureCache?

    init?(device: MTLDevice) {
        self.device = device
        guard let queue = device.makeCommandQueue(),
              let lib = device.makeDefaultLibrary(bundle: .main),
              let vert = lib.makeFunction(name: "quad_vertex"),
              let frag = lib.makeFunction(name: "yuv_fragment") else { return nil }
        self.commandQueue = queue
        let pd = MTLRenderPipelineDescriptor()
        pd.vertexFunction = vert
        pd.fragmentFunction = frag
        pd.colorAttachments[0].pixelFormat = .bgra8Unorm
        do {
            self.pipeline = try device.makeRenderPipelineState(descriptor: pd)
        } catch { return nil }
        let sd = MTLSamplerDescriptor()
        sd.minFilter = .linear; sd.magFilter = .linear
        sd.sAddressMode = .clampToEdge; sd.tAddressMode = .clampToEdge
        guard let sampler = device.makeSamplerState(descriptor: sd) else { return nil }
        self.sampler = sampler
        super.init()
        CVMetalTextureCacheCreate(nil, nil, device, nil, &textureCache)
    }

    func draw(pixelBuffer: CVPixelBuffer, in view: MTKView) {
        guard let textureCache,
              let desc = view.currentRenderPassDescriptor,
              let drawable = view.currentDrawable,
              let cmd = commandQueue.makeCommandBuffer() else { return }
        let w = CVPixelBufferGetWidth(pixelBuffer)
        let h = CVPixelBufferGetHeight(pixelBuffer)
        var yRef: CVMetalTexture?
        var uvRef: CVMetalTexture?
        guard CVMetalTextureCacheCreateTextureFromImage(
                nil, textureCache, pixelBuffer, nil, .r8Unorm, w, h, 0, &yRef) == kCVReturnSuccess,
              CVMetalTextureCacheCreateTextureFromImage(
                nil, textureCache, pixelBuffer, nil, .rg8Unorm, w / 2, h / 2, 1, &uvRef) == kCVReturnSuccess,
              let yRef, let uvRef,
              let yTex = CVMetalTextureGetTexture(yRef),
              let uvTex = CVMetalTextureGetTexture(uvRef) else { return }

        desc.colorAttachments[0].loadAction = .clear
        desc.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1)
        guard let enc = cmd.makeRenderCommandEncoder(descriptor: desc) else { return }
        enc.setRenderPipelineState(pipeline)
        enc.setFragmentTexture(yTex, index: 0)
        enc.setFragmentTexture(uvTex, index: 1)
        enc.setFragmentSamplerState(sampler, index: 0)
        enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        enc.endEncoding()
        cmd.present(drawable)
        cmd.commit()
    }
}
```

- [ ] **Step 4: 写 CameraPreviewView（MTKView 桥 + 权限流 + DebugOverlay FPS）**

`iosApp/PoLang/Features/Camera/Preview/CameraPreviewView.swift`：

```swift
import SwiftUI
import MetalKit

struct CameraPreviewView: View {
    @State private var authorized = false
    @State private var controller = CaptureSessionController()
    @State private var fpsTimer: Timer?

    var body: some View {
        ZStack {
            if authorized {
                MetalViewRepresentable(controller: controller)
            } else {
                VStack(spacing: 12) {
                    Text("需要相机权限")
                    Button("去设置开启") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                }
                .accessibilityIdentifier("camera_denied")
            }
        }
        .task {
            authorized = await controller.checkAuthorizationAndStart()
            DebugOverlayState.shared.set("camera.auth", authorized ? "granted" : "denied")
        }
        .onDisappear { controller.stop() }
        .accessibilityIdentifier("camera_preview")
    }
}

private struct MetalViewRepresentable: UIViewRepresentable {
    let controller: CaptureSessionController

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView()
        view.device = MTLCreateSystemDefaultDevice()
        view.delegate = context.coordinator
        view.enableSetNeedsDisplay = false
        view.isPaused = false
        context.coordinator.renderer = view.device.flatMap { MetalPreviewRenderer(device: $0) }
        context.coordinator.controller = controller
        return view
    }

    func updateUIView(_ uiView: MTKView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MTKViewDelegate {
        var renderer: MetalPreviewRenderer?
        var controller: CaptureSessionController?
        private var frames = 0
        private var lastFpsTick = Date()

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        func draw(in view: MTKView) {
            guard let pb = controller?.readBuffer() else { return }
            renderer?.draw(pixelBuffer: pb, in: view)
            frames += 1
            if Date().timeIntervalSince(lastFpsTick) >= 1.0 {
                DebugOverlayState.shared.set("camera.fps", "\(frames)")
                frames = 0
                lastFpsTick = Date()
            }
        }
    }
}
```

- [ ] **Step 5: 真机验证（无美颜直渲 30fps）**

Run: 真机安装（Xcode Run 到设备），相机 Tab
Expected: 预览出图；DebugOverlay `camera.fps: 30`、`camera.auth: granted`；方向竖屏正确；无黑屏（黑屏先查 commandQueue/纹理缓存初始化顺序，spike 踩坑 1）。

- [ ] **Step 6: Commit**

```bash
git add iosApp/
git commit -m "feat(ios): 相机采集→Metal YUV 直渲（spike 基线 Swift 移植，Task 12）"
```

---

## Task 13: 美颜宿主骨架 + 美白（BeautyRenderer + whiten）

**Files:**
- Create: `iosApp/PoLang/Features/Camera/Beauty/BeautyUniforms.swift`
- Create: `iosApp/PoLang/Features/Camera/Beauty/BeautyRenderer.swift`
- Create: `iosApp/PoLang/Features/Camera/Beauty/Shaders/beauty.metal`
- Create: `iosApp/PoLang/Features/Camera/Beauty/BeautyPanelView.swift`
- Modify: `iosApp/PoLang/Features/Camera/Preview/CameraPreviewView.swift`（渲染链路切 BeautyRenderer）

**翻译源**：`engines/beauty-engine/src/main/assets/shaders/skin.glsl`（`whitenSkin` 行 81-112；spike 已逐行验证）+ spike `Shaders.metal` 生产化（去调试 bleach 行）。

- [ ] **Step 1: 写 BeautyUniforms（MVP 子集；后续 Task 逐字段扩展）**

`iosApp/PoLang/Features/Camera/Beauty/BeautyUniforms.swift`：

```swift
import Foundation
import simd

/// 对应 Android uniforms.glsl 的 MVP 子集（spec §5.3 翻译纪律：标量/向量→struct 字段）。
/// 内存布局与 .metal 侧 BeautyUniforms 必须一致（swift/simd 对齐规则相同）。
struct BeautyUniforms {
    var smoothing: Float = 0
    var whitening: Float = 0
    var sharpen: Float = 0
    var bigEyes: Float = 0
    var slimFace: Float = 0
    var hasFace: Float = 0
    var aspectRatio: Float = 1
    var useGpupixelWarp: Int32 = 1
    // uFacePoints[212] 走独立 MTLBuffer（数组不可进 setFragmentBytes 内联结构），Task 16 接
}
```

- [ ] **Step 2: 写 beauty.metal（concat 顺序对应 ShaderModuleLoader 2D 版的 MVP 子集）**

`iosApp/PoLang/Features/Camera/Beauty/Shaders/beauty.metal`：

```metal
#include <metal_stdlib>
using namespace metal;

// ===== 对应 uniforms_2d.glsl（MVP 子集）=====
struct BeautyUniforms {
    float smoothing; float whitening; float sharpen;
    float bigEyes; float slimFace; float hasFace;
    float aspectRatio; int useGpupixelWarp;
};

// ===== 对应 skin.glsl whitenSkin()（spike 逐行验证版；GLSL const→constexpr）=====
static float3 whitenSkin(float3 rgb, float intensity, float mask) {
    if (intensity < 0.001 || mask < 0.01) return rgb;
    constexpr float levelBlack = 0.0258820;
    constexpr float levelRangeInv = 1.02657;
    float3 leveled = saturate((rgb - float3(levelBlack)) * levelRangeInv);
    float3 brightened = mix(rgb, leveled, 0.5);
    float whitenAlpha = intensity * mask;
    float3 whitened = mix(rgb, brightened, whitenAlpha);
    whitened.b *= 1.0 + whitenAlpha * 0.05;   // blueBoost
    whitened.r *= 1.0 - whitenAlpha * 0.03;   // redReduce
    return saturate(whitened);
}

struct Vout { float4 position [[position]]; float2 uv; };

vertex Vout beauty_vertex(uint vid [[vertex_id]]) {
    float2 pos[4] = { {-1,-1}, {1,-1}, {-1,1}, {1,1} };
    float2 uv[4]  = { {0,1}, {1,1}, {0,0}, {1,0} };
    Vout o; o.position = float4(pos[vid], 0, 1); o.uv = uv[vid]; return o;
}

// ===== 对应 main.glsl（MVP：仅美白；磨皮/形变 Task 14/16 插入）=====
fragment float4 beauty_fragment(Vout in [[stage_in]],
    texture2d<float, access::sample> inputTexture [[texture(0)]],
    sampler bilinear [[sampler(0)]],
    constant BeautyUniforms& uni [[buffer(0)]])
{
    float3 rgb = inputTexture.sample(bilinear, in.uv).rgb;
    rgb = whitenSkin(rgb, uni.whitening, 1.0);
    return float4(rgb, 1.0);
}
```

- [ ] **Step 3: 写 BeautyRenderer（双 pass：yuv→rgb 离屏纹理，beauty 上屏）**

`iosApp/PoLang/Features/Camera/Beauty/BeautyRenderer.swift`：

```swift
import Foundation
import Metal
import MetalKit
import CoreVideo

/// 美颜渲染宿主（对应 Android BeautyRenderer 的 MVP 版）。
/// 管线：camera YUV →(yuv pass)→ rgbTexture →(beauty pass)→ MTKView drawable。
/// pass 链结构对齐 Android；Task 14 磨皮插入为中间 pass。
final class BeautyRenderer: NSObject {
    struct Params {            // 对标 shared BeautySettings 的 MVP 子集
        var whitening: Float = 0
        var smoothing: Float = 0
        var slimFace: Float = 0
        var bigEyes: Float = 0
    }

    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private let yuvPipeline: MTLRenderPipelineState
    private let beautyPipeline: MTLRenderPipelineState
    private let sampler: MTLSamplerState
    private var textureCache: CVMetalTextureCache?
    private var rgbTexture: MTLTexture?

    var params = Params()

    init?(device: MTLDevice) {
        self.device = device
        guard let queue = device.makeCommandQueue(),
              let lib = device.makeDefaultLibrary(bundle: .main),
              let yuvVert = lib.makeFunction(name: "quad_vertex"),
              let yuvFrag = lib.makeFunction(name: "yuv_fragment"),
              let bVert = lib.makeFunction(name: "beauty_vertex"),
              let bFrag = lib.makeFunction(name: "beauty_fragment") else { return nil }
        self.commandQueue = queue
        func pipeline(_ v: MTLFunction, _ f: MTLFunction) -> MTLRenderPipelineState? {
            let pd = MTLRenderPipelineDescriptor()
            pd.vertexFunction = v; pd.fragmentFunction = f
            pd.colorAttachments[0].pixelFormat = .bgra8Unorm
            return try? device.makeRenderPipelineState(descriptor: pd)
        }
        guard let p1 = pipeline(yuvVert, yuvFrag),
              let p2 = pipeline(bVert, bFrag) else { return nil }
        self.yuvPipeline = p1
        self.beautyPipeline = p2
        let sd = MTLSamplerDescriptor()
        sd.minFilter = .linear; sd.magFilter = .linear
        sd.sAddressMode = .clampToEdge; sd.tAddressMode = .clampToEdge
        guard let s = device.makeSamplerState(descriptor: sd) else { return nil }
        self.sampler = s
        super.init()
        CVMetalTextureCacheCreate(nil, nil, device, nil, &textureCache)
    }

    private func ensureRgbTexture(w: Int, h: Int) {
        if rgbTexture?.width == w && rgbTexture?.height == h { return }
        let td = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
        td.usage = [.renderTarget, .shaderRead]
        rgbTexture = device.makeTexture(descriptor: td)
    }

    func draw(pixelBuffer: CVPixelBuffer, in view: MTKView) {
        guard let textureCache,
              let rgbTexSrc = makeTextures(pixelBuffer: pixelBuffer),
              let drawable = view.currentDrawable,
              let cmd = commandQueue.makeCommandBuffer() else { return }
        let (yTex, uvTex) = rgbTexSrc
        let w = CVPixelBufferGetWidth(pixelBuffer), h = CVPixelBufferGetHeight(pixelBuffer)
        ensureRgbTexture(w: w, h: h)
        guard let rgbTex = rgbTexture else { return }

        // Pass 1: YUV → RGB 离屏
        let d1 = MTLRenderPassDescriptor()
        d1.colorAttachments[0].texture = rgbTex
        d1.colorAttachments[0].loadAction = .dontCare
        d1.colorAttachments[0].storeAction = .store
        if let enc = cmd.makeRenderCommandEncoder(descriptor: d1) {
            enc.setRenderPipelineState(yuvPipeline)
            enc.setFragmentTexture(yTex, index: 0)
            enc.setFragmentTexture(uvTex, index: 1)
            enc.setFragmentSamplerState(sampler, index: 0)
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            enc.endEncoding()
        }

        // Pass 2: beauty 上屏
        guard let d2 = view.currentRenderPassDescriptor else { return }
        d2.colorAttachments[0].loadAction = .clear
        var uniforms = BeautyUniforms()
        uniforms.whitening = params.whitening
        uniforms.smoothing = params.smoothing
        uniforms.slimFace = params.slimFace
        uniforms.bigEyes = params.bigEyes
        uniforms.aspectRatio = Float(w) / Float(h)
        if let enc = cmd.makeRenderCommandEncoder(descriptor: d2) {
            enc.setRenderPipelineState(beautyPipeline)
            enc.setFragmentTexture(rgbTex, index: 0)
            enc.setFragmentSamplerState(sampler, index: 0)
            enc.setFragmentBytes(&uniforms, length: MemoryLayout<BeautyUniforms>.stride, index: 0)
            enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            enc.endEncoding()
        }
        cmd.present(drawable)
        cmd.commit()
    }

    private func makeTextures(pixelBuffer: CVPixelBuffer)
        -> (MTLTexture, MTLTexture)? {
        guard let textureCache else { return nil }
        let w = CVPixelBufferGetWidth(pixelBuffer), h = CVPixelBufferGetHeight(pixelBuffer)
        var yRef: CVMetalTexture?
        var uvRef: CVMetalTexture?
        guard CVMetalTextureCacheCreateTextureFromImage(
                nil, textureCache, pixelBuffer, nil, .r8Unorm, w, h, 0, &yRef) == kCVReturnSuccess,
              CVMetalTextureCacheCreateTextureFromImage(
                nil, textureCache, pixelBuffer, nil, .rg8Unorm, w/2, h/2, 1, &uvRef) == kCVReturnSuccess,
              let yRef, let uvRef,
              let y = CVMetalTextureGetTexture(yRef),
              let uv = CVMetalTextureGetTexture(uvRef) else { return nil }
        return (y, uv)
    }
}
```

- [ ] **Step 4: 切换渲染链路 + 美白滑杆**

`CameraPreviewView` 的 Coordinator：`MetalPreviewRenderer` 换成 `BeautyRenderer`（draw 签名相同）。

`iosApp/PoLang/Features/Camera/Beauty/BeautyPanelView.swift`：

```swift
import SwiftUI

/// 美颜面板（对标 Android BeautyPanel；MVP 先美白+磨皮两条滑杆）
struct BeautyPanelView: View {
    @Binding var whitening: Float
    @Binding var smoothing: Float

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(String(localized: "Whitening"))
                Slider(value: $whitening, in: 0...1)
                    .accessibilityIdentifier("beauty_whitening_slider")
            }
            HStack {
                Text(String(localized: "Smoothing"))
                Slider(value: $smoothing, in: 0...1)
                    .accessibilityIdentifier("beauty_smoothing_slider")
            }
        }
        .padding()
        .background(.ultraThinMaterial)
    }
}
```

`CameraPreviewView` 底部叠 `BeautyPanelView`。接线方式：`CameraPreviewView` 持有 `@State var whitening: Float = 0` / `@State var smoothing: Float = 0`，以 `Binding` 传给 `BeautyPanelView`；`updateUIView` 里把两个值写入 `context.coordinator.renderer?.params`（`updateUIView` 在 @State 变化时必被调用，无需每帧轮询）。

- [ ] **Step 5: 真机验证（美白滑杆即时可见）**

Expected: 滑杆拖动美白即时生效（spike 同级体验）；`camera.fps: 30` 保持。

- [ ] **Step 6: Commit**

```bash
git add iosApp/
git commit -m "feat(ios): BeautyRenderer 宿主骨架 + 美白 shader 翻译 + 面板滑杆（Task 13）"
```

---

## Task 14: 磨皮 pass（pass_smoothing 翻译 + 4 张 LUT）

**Files:**
- Create: `iosApp/PoLang/Features/Camera/Beauty/Shaders/smoothing.metal`
- Modify: `iosApp/PoLang/Features/Camera/Beauty/BeautyRenderer.swift`（磨皮 pass 插入链）
- Modify: `iosApp/PoLang/Features/Camera/Beauty/BeautyUniforms.swift`

**翻译源**：`engines/beauty-engine/src/main/assets/shaders/pass_smoothing.glsl`（195 行；uniform：uInputTexture/uLookUpGray/uLookUpOrigin/uLookUpSkin/uLookUpLight + uBlurAlpha/uSharpen/uWhiten + uWidthOffset/uHeightOffset）。LUT 资产已在 Task 6 拷入 bundle（lookup_gray/origin/skin/light.png）。

- [x] **Step 1: 翻译 smoothing.metal** ✅ GLM 完成

逐段翻译 `pass_smoothing.glsl`（195 行）为 MSL：
- `texture2D(t, uv)` → `t.sample(bilinear, uv)`；`clamp(x,0.,1.)` → `saturate`；`vec*→float*`；GLSL `const` 局部量 → `constexpr`；
- 4 张 LUT 采样纹理 `[[texture(1..4)]]`；`uWidthOffset/uHeightOffset` 进 `SmoothingUniforms` struct（`[[buffer(0)]]`）；
- 入口 `fragment float4 smoothing_fragment(...)`，vertex 复用 `beauty_vertex`。

- [ ] **Step 2: LUT 纹理加载**

`BeautyRenderer` 增加：

```swift
private func loadLut(_ name: String) -> MTLTexture? {
    guard let url = Bundle.main.url(forResource: name, withExtension: "png", subdirectory: "Assets"),
          let data = try? Data(contentsOf: url),
          let image = UIImage(data: data)?.cgImage else { return nil }
    let loader = MTKTextureLoader(device: device)
    return try? loader.newTexture(cgImage: image, options: [
        .SRGB: false,
        .textureUsage: MTLTextureUsage.shaderRead.rawValue,
        .textureStorageMode: MTLStorageMode.private.rawValue
    ])
}
```

init 中加载四张：`lookup_gray`/`lookup_origin`/`lookup_skin`/`lookup_light`（加载失败设 DebugOverlay `beauty.lut: missing <name>`）。

- [ ] **Step 3: 磨皮 pass 插入管线（yuv→rgb → smoothing → beauty 上屏）**

`BeautyRenderer.draw` 在 Pass 1 与 Pass 2 之间插入 Pass 1.5（仅 `params.smoothing > 0.01` 时启用，否则直通）：
- 新增 `smoothingTexture`（同 `ensureRgbTexture` 模式）；
- `smoothing_fragment` 输入 rgbTex + 4 LUT + `SmoothingUniforms{ blurAlpha=params.smoothing, sharpen, whiten, widthOffset=1/w, heightOffset=1/h }`；
- 输出到 `smoothingTexture`，Pass 2 的输入从 `rgbTex` 换为 `smoothingTexture`。

- [ ] **Step 4: 真机验证**

Expected: 磨皮滑杆生效（皮肤区域柔化）、无全屏异常色块；`camera.fps ≥ 28`（磨皮多一次全屏采样，允许小幅下降）；与 Android 同场景观感对照（主观一致，spec S5）。

- [ ] **Step 5: Commit**

```bash
git add iosApp/
git commit -m "feat(ios): 磨皮 pass_smoothing GLSL→MSL 翻译 + 4 LUT 接入（Task 14）"
```

---

## Task 15: 人脸关键点（MediaPipeTasksVision + 468→106 适配器移植）

**Files:**
- Modify: `iosApp/PoLang.xcodeproj`（SPM 加 MediaPipeTasksVision）
- Create: `iosApp/PoLang/Features/Camera/Beauty/FaceLandmarkService.swift`
- Create: `iosApp/PoLang/Features/Camera/Beauty/MediaPipe468Adapter.swift`
- Test: `iosApp/PoLangTests/MediaPipe468AdapterTests.swift`

**移植源**：`engines/beauty-engine/.../facedetect/adapter/MediaPipe468Adapter.kt`——轮廓 33 点 FACE_OVAL 插值 + 非轮廓 73 点 `NON_CONTOUR_MAPPING` 固定表 + 前置镜像。

- [ ] **Step 1: SPM 加 MediaPipeTasksVision**

Xcode → Package Dependencies → `https://github.com/google-ai-edge/mediapipe`（或官方 pods；选 SPM 方式与工程一致），产品 `MediaPipeTasksVision`，版本与 Android 对齐 `0.10.26`（若 SPM 无精确版本取最近 0.10.x）。

⚠️ 验证项（spike 未覆盖）：确认 iOS 产物含 `FaceLandmarker` 视频模式 API（`FaceLandmarker(videoOptions:)` + `detect(videoFrame:timestampInMilliseconds:)`）。若 API 不符，回退方案：MNN det_500m 检测 + 自有关键点模型——**阻塞上报，不静默降级**。

- [ ] **Step 2: 写 FaceLandmarkService（单线程推理队列）**

`iosApp/PoLang/Features/Camera/Beauty/FaceLandmarkService.swift`：

```swift
import Foundation
import AVFoundation
import MediaPipeTasksVision

/// 人脸 468 点检测（video 模式）→ 106 点输出。
/// ⚠️ 单线程串行队列（shared iOS DispatcherProvider.modelDispatcher 不保证串行的教训）；
/// 跳帧策略：推理中丢帧不排队（alwaysDiscardsLateVideoFrames 同义）。
final class FaceLandmarkService {
    struct Result {
        let points106: [SIMD2<Float>]   // 归一化坐标（与 Android mapNormalizedToUv 上游同态）
        let timestampMs: Int
    }

    private let queue = DispatchQueue(label: "polang.face.landmark")
    private var landmarker: FaceLandmarker?
    private var busy = false
    private(set) var latest: Result?

    init() {
        queue.async { [self] in
            guard let modelPath = Bundle.main.path(
                    forResource: "face_landmarker", ofType: "task",
                    inDirectory: "Assets") else {
                DebugOverlayState.shared.set("face.error", "model missing")
                return
            }
            let opts = FaceLandmarkerOptions()
            opts.baseOptions.modelAssetPath = modelPath
            opts.runningMode = .video
            opts.numFaces = 1
            landmarker = try? FaceLandmarker(options: opts)
            DebugOverlayState.shared.set("face.engine", landmarker != nil ? "ok" : "init failed")
        }
    }

    func enqueue(pixelBuffer: CVPixelBuffer, timestampMs: Int) {
        queue.async { [self] in
            guard !busy, let landmarker else { return }
            busy = true
            defer { busy = false }
            guard let mpImage = try? MPImage(pixelBuffer: pixelBuffer),
                  let result = try? landmarker.detect(videoFrame: mpImage,
                                                      timestampInMilliseconds: timestampMs),
                  let face = result.faceLandmarks.first else { return }
            let points = MediaPipe468Adapter.map(face)
            latest = Result(points106: points, timestampMs: timestampMs)
            DebugOverlayState.shared.set("face.points", "\(points.count)")
        }
    }
}
```

`CaptureSessionController` 的帧回调里（在 `swapBuffer` 旁）追加 `faceService.enqueue(pixelBuffer:timestampMs:)`（`CMSampleBufferGetPresentationTimeStamp` 转 ms——对应 Android framesync 时间戳源改 `presentationTime` 的决策）。

- [ ] **Step 3: 移植 MediaPipe468Adapter（Swift）**

`iosApp/PoLang/Features/Camera/Beauty/MediaPipe468Adapter.swift`：

- `NON_CONTOUR_MAPPING` 73 项 int 表**逐行照抄** Kotlin 版（`MediaPipe468Adapter.kt` companion 内 intArray）；
- 轮廓 33 点：按 Kotlin 版 FACE_OVAL 路径插值逻辑逐行移植（含前置摄像头 x 镜像分支——iOS 后置不镜像，前置保留参数）；
- 输出 `[SIMD2<Float>]` 106 点归一化坐标。

- [ ] **Step 4: 写适配器单测（金样本对照）**

`iosApp/PoLangTests/MediaPipe468AdapterTests.swift`：

```swift
import XCTest
@testable import PoLang
import MediaPipeTasksVision

final class MediaPipe468AdapterTests: XCTestCase {
    /// 金样本：固定 468 输入 → 106 输出与 Android Kotlin 版逐点一致。
    /// 样本生成：Android 侧单测打印同输入的 Kotlin 输出（执行时跑一次生成，贴入本文件常量）。
    func testMapMatchesAndroidGolden() {
        let input468 = GoldenSamples.input468          // [NormalizedLandmark] 构造
        let expected106 = GoldenSamples.expected106    // [(Float, Float)]
        let got = MediaPipe468Adapter.map(input468)
        XCTAssertEqual(got.count, 106)
        for (i, p) in got.enumerated() {
            XCTAssertEqual(p.x, expected106[i].0, accuracy: 1e-5, "point \(i) x")
            XCTAssertEqual(p.y, expected106[i].1, accuracy: 1e-5, "point \(i) y")
        }
    }
}
```

> 金样本生成步骤（执行时）：在 Android worktree 跑一个临时 Kotlin 测试，输入固定伪随机 468 点（seed 固定），打印 106 输出，粘贴为 `GoldenSamples.swift` 常量。这保证双端形变锚点逐点一致（spec S5 的硬验收）。

- [ ] **Step 5: 真机验证**

Expected: DebugOverlay `face.engine: ok`、`face.points: 106`；人脸入镜时 points 更新，出镜后 `face.points` 停更。

- [ ] **Step 6: Commit**

```bash
git add iosApp/
git commit -m "feat(ios): MediaPipe FaceLandmarker 接入 + 468→106 适配器移植 + 金样本单测（Task 15）"
```

---

## Task 16: warp 瘦脸/大眼（hard shader ×2 + uFacePoints buffer）

**Files:**
- Create: `iosApp/PoLang/Features/Camera/Beauty/Shaders/warp.metal`
- Modify: `iosApp/PoLang/Features/Camera/Beauty/BeautyRenderer.swift`
- Modify: `iosApp/PoLang/Features/Camera/Beauty/BeautyUniforms.swift`
- Modify: `iosApp/PoLang/Features/Camera/Beauty/BeautyPanelView.swift`（加两条滑杆）

**翻译源**：`warp_gpupixel_thinface.glsl`（129 行）+ `warp_gpupixel_bigeye.glsl`（146 行）——GLSL→MSL 难度分级中仅有的 hard 之二（几何反向 UV 形变 + `uFacePoints[212]` 动态索引，**逐行理解后翻译**，不得机械替换）。

- [x] **Step 1: 翻译 warp.metal** ✅ GLM 完成（warp_gpupixel_thinface + bigeye 双 hard shader 逐行翻译 + 编译验证通过）

- 两个 warp 函数逐行翻译（`warpThinFace`/`warpBigEye`），反向形变数学不变，只换语法：`texture2D→sample`、`fract/mix/step` 同名、`vec2 数组→device/constant float2*`；
- `uFacePoints[212]` → `constant float2* facePoints [[buffer(1)]]`（数组必须独立 buffer，spec §5.3）；
- 在 `beauty_fragment` 主入口插入：`useGpupixelWarp == 1 && hasFace > 0.5` 时先算形变 UV 再采样（对应 Android main.glsl 的调用序）。

- [ ] **Step 2: BeautyRenderer 接 106 点 + warp 参数**

- 新增 `facePointsBuffer: MTLBuffer`（`length = 106 * 2 * MemoryLayout<Float>.stride`，`.storageModeShared`）；
- 每帧从 `FaceLandmarkService.latest` 取 106 点，**坐标映射**：归一化 → 纹理 UV（对应 Android `CameraPreviewRenderer.mapNormalizedToUv()`——前置镜像/旋转矩阵逻辑参照其实现；后置竖屏为恒等+y 翻转，按实测校准，用 DebugOverlay 打首点坐标验证）；
- 写入 buffer；`hasFace` 按 `latest != nil && timestampMs 距帧时间 < 200ms`（帧同步窗口，对应 Android framesync）；
- `beauty_fragment` 编码时 `setFragmentBuffer(facePointsBuffer, offset: 0, index: 1)`。

- [ ] **Step 3: 面板加瘦脸/大眼滑杆**

`BeautyPanelView` 加 `slimFace`/`bigEyes` 两条滑杆（0...1），绑定 `BeautyRenderer.params`；`BeautyUniforms.slimFace/bigEyes` 已在 Task 13 Step 1 结构中就位。

- [ ] **Step 4: 真机验证（对照 Android）**

Expected: 瘦脸/大眼滑杆即时可见、形变中心对人脸正确（不偏移/不镜像）；人脸转动时形变跟随；`camera.fps ≥ 28`；与 Android 同角度同参数截图对照（主观一致）。

- [ ] **Step 5: Commit**

```bash
git add iosApp/
git commit -m "feat(ios): warp 瘦脸/大眼 hard shader 翻译 + uFacePoints buffer 接入（Task 16）"
```

---

## Task 17: LUT 色彩滤镜（FilterType 九款；StyleFilter 卡通等五款明确移 Phase 6）

**Files:**
- Create: `iosApp/PoLang/Features/Camera/Beauty/Shaders/lut.metal`
- Create: `iosApp/PoLang/Features/Camera/Beauty/FilterSelectorView.swift`
- Modify: `iosApp/PoLang/Features/Camera/Beauty/BeautyRenderer.swift`

**范围**：`FilterType`（NONE/LEICA_CLASSIC/LEICA_VIBRANT/LEICA_BW/FILM_GOLD/FILM_FUJI/VINTAGE/COOL/WARM）经 LUT 纹理映射实现；`StyleFilter`（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH，style/*.glsl）**移 Phase 6**（spec S3 第一批只含 LUT 风格）。

- [x] **Step 1: 确认滤镜 LUT 资产清单** ✅ GLM 完成（9 款 filter_*.jpg 已在 Task 6 拷入 bundle Assets/filters/；Android FilterType 走 ColorMatrix 而非纹理 LUT，已核对 FilterTypeExt.kt）

Run: `ls androidApp/src/main/assets/filters/`
Expected: 9 款 FilterType 对应 LUT 图（PNG/JPG）。Task 6 已拷入 iOS bundle `Assets/filters/`；若缺某款，回 Android 侧 `FilterType`→资产映射代码（`LutTextureLoader.kt`）核对文件名对照表，抄入本计划备注。

- [x] **Step 2: 翻译 lut.metal（LUT 采样 pass）** ✅ GLM 完成（ColorMatrix 路径：colorgrade.glsl + main.glsl ColorMatrix → lut.metal，非纹理 LUT）

参照 Android `LutTextureLoader` + LUT 采样 shader（`colorgrade.glsl` 或 LUT 专用 pass）：
- 标准 512×512（64³）LUT 采样逻辑：`fragment float4 lut_fragment(input [[texture(0)]], lut [[texture(1)]], intensity [[buffer(0)]])`；
- 蓝色通道选 tile、红绿通道 tile 内寻址——逐行对齐 Android 实现（LUT 布局约定错 = 全屏偏色，用滤镜前后对照验证）。

- [x] **Step 3: BeautyRenderer 加滤镜 pass 与切换** ✅ GLM 完成（lutPipeline + Pass 2 插入；FilterType Swift 枚举 + ColorMatrix 逐值照抄 Android）

- `params` 加 `colorFilter: Int32`（对应 shared `FilterType` 枚举 ordinal——滤镜名/排序与 Android 一致，spec S5）；
- 9 张 LUT 纹理惰性加载缓存（`loadLut` 模式复用）；
- pass 链：`yuv→rgb → (smoothing) → (lut) → beauty 上屏`，lut pass 仅 `colorFilter != NONE` 时启用。

- [x] **Step 4: 滤镜选择 UI（对标 Android FilterSelector）** ✅ GLM 完成（FilterSelectorView.swift 横向滚动条 + 三语名称 + accessibilityIdentifier）

`FilterSelectorView.swift`：横向滚动的滤镜条（9 款，名称与 Android 一致走 `String(localized:)`），选中写 `BeautyRenderer.params.colorFilter`。`accessibilityIdentifier("filter_<name>")`。

- [ ] **Step 5: 真机验证 + Commit**

Expected: 9 款滤镜切换即时生效、色彩与 Android 同款主观一致；无滤镜时 pass 直通零开销。

```bash
git add iosApp/
git commit -m "feat(ios): FilterType 九款 LUT 色彩滤镜（StyleFilter 移 Phase 6，Task 17）"
```

---

## Task 18: 拍照链路（AVCapturePhotoOutput → 离屏美颜 → 保存相册）

**Files:**
- Create: `iosApp/PoLang/Features/Camera/Capture/PhotoCaptureController.swift`
- Modify: `iosApp/PoLang/Features/Camera/Capture/CaptureSessionController.swift`
- Modify: `iosApp/PoLang/Features/Camera/Beauty/BeautyRenderer.swift`（离屏渲染入口）
- Create: `iosApp/PoLang/Features/Camera/Capture/ShutterButton.swift`

- [x] **Step 1: 写 PhotoCaptureController** ✅ GLM 完成（使用 AVCapturePhoto API，非已弃用的 CMSampleBuffer 变体；含 AVCapturePhoto→CVPixelBuffer 转换）

```swift
import Foundation
import AVFoundation
import Photos

/// 拍照：全分辨率静态图捕获（与预览 720p 流并行）。
final class PhotoCaptureController: NSObject {
    private let photoOutput = AVCapturePhotoOutput()
    private var continuation: CheckedContinuation<CMSampleBuffer?, Never>?

    func attach(to session: AVCaptureSession) {
        if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
    }

    func capture() async -> CVPixelBuffer? {
        let sample: CMSampleBuffer? = await withCheckedContinuation { cont in
            self.continuation = cont
            photoOutput.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
        }
        guard let sample, let pb = CMSampleBufferGetImageBuffer(sample) else { return nil }
        return pb
    }
}

extension PhotoCaptureController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto sampleBuffer: CMSampleBuffer?,
                     previewPhoto previewPhotoSampleBuffer: CMSampleBuffer?,
                     resolvedSettings: AVCaptureResolvedPhotoSettings,
                     bracketSettings: AVCaptureBracketedStillImageSettings?,
                     error: Error?) {
        continuation?.resume(returning: sampleBuffer)
        continuation = nil
    }
}
```

（若部署目标 SDK 该 delegate 签名已弃用，按 Xcode 提示改用 `AVCapturePhoto` 变体——取 `photo.fileDataRepresentation` 转 `CGImage` 路径，等价。）

- [x] **Step 2: BeautyRenderer 离屏渲染入口** ✅ GLM 完成（renderToImage + runPass 封装 + textureToCGImage 回读）

新增 `func renderToImage(pixelBuffer: CVPixelBuffer) -> CGImage?`：复用预览同一 pass 链（yuv→rgb→smoothing→lut→beauty），最终渲染到全分辨率 `MTLTexture`，`CIContext(mtlDevice:)` 或 `MTLTexture.getBytes` 转 `CGImage`。共享 pass 函数，不复制 shader 逻辑。

- [x] **Step 3: 保存 + 快门 UI + AddOnly 权限衔接** ✅ GLM 完成（ShutterButton + CaptureFlow 异步封装 + PhotoSaver.saveToLibrary）

```swift
// 保存（触发系统 AddOnly 授权流，spec §5.5 与 §4.2 衔接）
func saveToLibrary(_ image: CGImage) async throws {
    try await PHPhotoLibrary.shared().performChanges {
        PHAssetChangeRequest.creationRequestForAsset(from: UIImage(cgImage: image))
    }
}
```

`ShutterButton.swift`：圆形快门按钮（对标 Android 相机主按钮），点击 → `capture()` → `renderToImage` → `saveToLibrary`；过程异步、**不阻塞快门回弹**（spec §5.5 PERF：快门响应 <50ms——离屏渲染在后台 Task 进行，按钮即刻复位）；`accessibilityIdentifier("camera_shutter")`。

- [ ] **Step 4: 真机验证**

Expected: 拍照存进系统相册（含美颜效果与预览一致）；连拍 10 张无掉帧卡顿（PERF 验收 Task 20 复测）。

- [ ] **Step 5: Commit**

```bash
git add iosApp/
git commit -m "feat(ios): 拍照链路——全分辨率捕获/离屏美颜/保存相册（Task 18）"
```

---

## Task 19: 相机手势（对焦/变焦/曝光）

**Files:**
- Create: `iosApp/PoLang/Features/Camera/Preview/CameraGesturesView.swift`
- Modify: `iosApp/PoLang/Features/Camera/Capture/CaptureSessionController.swift`

- [x] **Step 1: CaptureSessionController 加控制方法** ✅ GLM 完成（首轮已写入：focus/zoom/exposure + DebugOverlay）

```swift
func focus(at point: CGPoint) {   // view 坐标 → devicePointOfInterest 转换由调用侧完成
    queue.async { [self] in
        guard let device = (session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
        try? device.lockForConfiguration()
        if device.isFocusPointOfInterestSupported {
            device.focusPointOfInterest = point
            device.focusMode = .autoFocus
        }
        if device.isExposurePointOfInterestSupported {
            device.exposurePointOfInterest = point
            device.exposureMode = .autoExpose
        }
        device.unlockForConfiguration()
        DebugOverlayState.shared.set("camera.focus", "\(point.x), \(point.y)")
    }
}

func setZoom(_ factor: CGFloat) {
    queue.async { [self] in
        guard let device = (session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
        try? device.lockForConfiguration()
        device.videoZoomFactor = max(1.0, min(factor, device.activeFormat.videoMaxZoomFactor))
        DebugOverlayState.shared.set("camera.zoom", String(format: "%.1f", device.videoZoomFactor))
        device.unlockForConfiguration()
    }
}

func setExposureBias(_ bias: Float) {
    queue.async { [self] in
        guard let device = (session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
        try? device.lockForConfiguration()
        device.setExposureTargetBias(bias)
        DebugOverlayState.shared.set("camera.exposure", String(format: "%.2f", bias))
        device.unlockForConfiguration()
    }
}
```

- [x] **Step 2: 手势视图叠加** ✅ GLM 完成（CameraGesturesView：onTapGesture 对焦 + MagnifyGesture 变焦 + DragGesture 曝光补偿 + FocusRing 动画）

`CameraGesturesView.swift`：透明叠加层，`onTapGesture` → 换算 `view.pointForCaptureDevicePoint`/`captureDevicePointForView` 后调 `focus`；`MagnifyGesture` → `setZoom`（基准倍率 × 手势增量，钳制 [1, maxZoom]）；垂直拖动 → `setExposureBias`（[-2, +2] 钳制）。

- [ ] **Step 3: 真机验证 + Commit**

Expected: 点按对焦框出现且合焦；捏合变焦顺滑；滑动曝光明暗即时；DebugOverlay 三值正确。

```bash
git add iosApp/
git commit -m "feat(ios): 对焦/变焦/曝光手势（Task 19）"
```

---

## Task 20: 双端一致验收 + PERF 红线验证

**Files:**
- 无代码变更；验收记录回写本文件勾选备注

- [ ] **Step 1: 相册双端对照（spec §4.6）**

同一 Apple ID/相册集（或经 iCloud/导入对齐的照片集）：Android PoLang 与 iOS PoLang 并排——网格排序、按日分组边界、照片总数完全一致。差异逐项记录，属 bug 则修复后重验。

- [ ] **Step 2: 美颜观感对照（spec S5/§5.6）**

同场景（同光源同人脸角度）双端各截 4 组：默认/美白 0.6/磨皮 0.6/瘦脸 0.5+大眼 0.5。主观对照一致；`BeautySettings` 默认值/滑杆范围与 Android 逐项核对（shared 纯类型同源，应天然一致）。

- [ ] **Step 3: PERF 红线（spec §5.5/§5.7）**

- 预览：`camera.fps` 连续 5 分钟 ≥ 28（DebugOverlay 读数）；
- 快门：连拍 10 张，快门按钮响应即时（主观 <50ms 无感知延迟），无掉帧卡顿；
- 交互：滤镜切换/滑杆拖动 <100ms 生效（主观即时）。

- [ ] **Step 4: Commit（验收记录）**

```bash
git add docs/superpowers/plans/2026-08-08-ios-app-skeleton.md
git commit -m "test(ios): 双端一致 + PERF 红线验收（Task 20，记录见计划备注）"
```

---

## Task 21: 5.5 打包与出口检查单

**Files:**
- Modify: `iosApp/PoLang.xcodeproj`（Release 配置/签名）
- 产出：TestFlight 或 ad-hoc 包

- [ ] **Step 1: 确认分发路径（spec S7/R1）**

付费 Developer Program 已落实 → TestFlight（内部测试组，开发者设备 + 少量测试员）；未落实 → ad-hoc/开发签名真机包交付，TestFlight 顺延（本 Task 标记受限完成）。

- [ ] **Step 2: Release 配置**

- Build Settings → Release 切 `shared/build/XCFrameworks/release/SharedKit.xcframework`（先 `./gradlew :shared:assembleSharedKitReleaseXCFramework`）；
- Archive（`xcodebuild -scheme PoLang -configuration Release -archivePath iosApp/build/PoLang.xcarchive archive`）；
- TestFlight：`xcodebuild -exportArchive` + App Store Connect 上传（GUI Xcode Organizer 亦可）。

- [ ] **Step 3: 出口检查单（spec §6）**

逐项打勾：
- [ ] 相机预览 + MVP 美颜（磨皮/美白/瘦脸/大眼）可用
- [ ] LUT 九款滤镜可用
- [ ] 拍照保存可用（含 AddOnly 权限流）
- [ ] 相册浏览（网格/分组/大图/相簿）可用
- [ ] Limited 权限一等公民（选择器入口 + 变更刷新）
- [ ] PrivacyInfo.xcprivacy 完整（FileTimestamp/DiskSpace 已声明）
- [ ] 三语文案无硬编码（`grep -rn 'Text("' iosApp/PoLang --include="*.swift" | grep -v localized | grep -v accessibilityIdentifier` 应为空或仅 DebugOverlay 调试串）
- [ ] CI 双端绿（Android 零回归 + iOS build job）
- [ ] PERF 红线达标（Task 20 Step 3）

- [ ] **Step 4: Commit**

```bash
git add iosApp/ docs/
git commit -m "release(ios): Phase 5 出口——内测包 + 出口检查单全勾（Task 21）"
```

---

## Task 22: 文档同步（[DOC-SYNC] 红线）

**Files:**
- Modify: `docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md`（Phase 5 勾选 + 修订记录）
- Modify: `docs/superpowers/specs/2026-08-08-ios-app-skeleton-design.md`（变更记录：MediaPipe 修正 + 执行偏差）
- Modify: `AGENTS.md`（架构说明段：iosApp 存在性 + 分模块边界一句）
- Modify: `docs/02-ARCHITECTURE/`（如双端架构图存在则更新）

- [ ] **Step 1: roadmap 勾选 Phase 5（5.1–5.5）并加修订行**

修订行要点：实际工期、美颜方案 A 落地确认、人脸关键点走 MediaPipe（非 MNN）修正、StyleFilter 五款移 Phase 6、补验 B 状态不变。

- [ ] **Step 2: spec 变更记录补执行偏差**

`2026-08-XX | 执行修订：§5.4 人脸关键点修正为 MediaPipe Face Landmarker + 468→106（原述 MNN RetinaFace 仅检测）；StyleFilter 移 Phase 6；Task 22 同步`

- [ ] **Step 3: AGENTS.md 架构说明加一行**

在「架构说明（2026-08-07 更新）」段追加：`- **iOS 应用（iosApp/）**：SwiftUI，Phase 5 落地相机+相册骨架；分模块边界（相册 Swift 主导/相机纯 Swift+Metal/Agent 薄壳）见 specs/2026-08-08-ios-app-skeleton-design.md`

- [ ] **Step 4: review 子 agent 审 diff + Commit**

派 review 子 agent（GLM）审本 Phase 全量 diff（重点：Kotlin/Swift 边界异常兜底、[I18N] 硬编码、[PRIVACY] 媒体无外发路径）；意见处理后：

```bash
git add docs/ AGENTS.md
git commit -m "docs(phase5): 文档同步——roadmap 勾选/spec 偏差/AGENTS.md 架构说明（Task 22）"
```

---

## 风险与回退（执行期）

| 风险 | 触发点 | 回退/处置 |
|------|--------|-----------|
| Phase 4 未收口 | Task 0 Step 1 | 停止，等收口（用户既定决策） |
| `AccessState` 未进 commonMain | Task 0 Step 2 | 停止，Phase 4 Task 4 先补 |
| MediaPipeTasksVision iOS API 不符 | Task 15 Step 1 | **阻塞上报**，不静默降级（spec 无此预案） |
| warp 翻译形变偏移/镜像错误 | Task 16 Step 4 | 坐标映射逐环节打 DebugOverlay（首点 UV/像素值），对照 Android `mapNormalizedToUv` 逐行核；金样本单测（Task 15 Step 4）先行兜底 |
| 免费签名 7 天到期打断真机调试 | 全 Phase | 重签继续；5.5 前落实付费账号（R1） |
| Kotlin/Native 边界崩溃（signal 6） | Task 3/7 | shared 侧补 try/catch 兜底（spike 坑 1 约定），不 bypass |
| LUT 布局约定错 → 全屏偏色 | Task 17 Step 5 | 对照 Android `LutTextureLoader` 逐行核 tile 寻址； NONE 滤镜直通不受影响 |

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-08 | 初版：23 个 Task（Task 0 前置核对 → Task 22 文档同步）；基于 spec（S1–S10）+ explore 实测（shared 当前态/spike 产物/shader 清单/468→106 适配器）。对 spec 一处修正：人脸关键点走 MediaPipe 而非 MNN |
| 2026-08-08 | GLM 相机段执行进度：Task 12 shader (yuv.metal) ✅、Task 13 shader (beauty.metal + whitenSkin) ✅、Task 14 shader (smoothing.metal + pass_smoothing 全量翻译) ✅、Task 16 shader (warp.metal: 瘦脸+大眼 hard×2) ✅ —— 全部编译通过（含 concat 路径零 error 零 warning）；Task 15 MediaPipe468Adapter Swift 移植 + 金样本测试 ✅；Task 6 引擎产物收编 ✅（MNN/sentencepiece 构建脚本 + MediaPipe fetch + GLSL/LUT/filter assets 同步）；Task 2/4/5 Swift 基建骨架 ✅（DebugOverlay/AppContainer/DebugOverlay/I18N xcstrings/PrivacyInfo/ios-dev-loop/CI iOS job）。阻塞：Xcode .xcodeproj 需 GUI 创建（GLM 无 GUI 能力）；Kotlin/SharedKit embed 需 K3 侧 XCFramework 产出 |
| 2026-08-08 | GLM 相机段续跑：Task 17 ✅（lut.metal ColorMatrix+ColorGrade 翻译 + FilterColorMatrix.swift 9 款矩阵逐值照抄 Android + FilterSelectorView.swift 三语滤镜条）；Task 18 ✅（PhotoCaptureController AVCapturePhoto API + BeautyRenderer.renderToImage 离屏全管线 + ShutterButton 异步快门 + CaptureFlow captureAndSave 异步流程 + PhotoSaver AddOnly 权限衔接）；Task 19 ✅（CameraGesturesView 对焦框/捏合变焦/垂直曝光 + FocusRing 动画）。5 shader 全量编译通过（含 concat） |
| 2026-08-08 | GLM XcodeGen 突破：`project.yml` + `xcodegen generate` 生成 `PoLang.xcodeproj`，16 Swift 文件首次整体 `xcodebuild build` **BUILD SUCCEEDED**（iOS 16 target，simulator），7 XCTest **全绿**。Metal shader 链接修复：struct 每文件独立定义（Metal 无跨 TU struct 链接），`quad_vertex` 唯一定义在 yuv.metal（linker 解析）。FaceLandmarkService 用 `#if canImport(MediaPipeTasksVision)` 条件编译（SPM URL 待确认）。CI iOS job 升级为 `xcodegen generate && xcodebuild build && test` |
