# iOS TAG 扫描核心（SP-B）实现计划

> ✅ **已合并 main（`b78d7081`，2026-08-12）**：SP-B/SP-C/SP-D（Pass2/Pass3/控制页）全部落地，Pass3 Florence-2 真机验证通过。本文归档（git 留史）。现行状态见 `IOS_TASK_STATUS.md` §6.1；归类见 `docs/01-PRODUCT/IOS_DOC_INDEX.md` §1。


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 iOS 把 Pass 1（人脸检测 + 嵌入 + MobileCLIP）跑遍整个相册，配一个完全复刻 Android `TagGenerationControlScreen` 的扫描页 + 后台任务系统（状态机 / 任务队列 / 暂停-恢复-取消 / ETA / 中断恢复）。

**Architecture:** 数据层扩 `TagDatabase`（新建 `media_assets` 单表对齐 Android `MediaEntity` + 新建 `tag_scan_tasks` 任务队列，废弃 `media_tags`；`mediaId = Int64` + `localIdentifier` 映射）。纯逻辑（状态机 / ETA / 任务规划）抽成可单测类型。`TagScanOrchestrator` 在单一后台 `Task(.utility)` 上串行调 `Pass1Pipeline`（MNN 单线程约束），协作式 pause/cancel，前台优先。`TagScanViewModel` 桥接 `@Published` 给 `TagScanScreen`（SwiftUI，8 section 完全复刻，Pass2/3 控件置灰）。

**Tech Stack:** Swift 5 / SwiftUI / iOS 16+；raw SQLite3（`TagDatabase`，对齐 Android Room schema）；PhotoKit（`PHAsset` / `PHImageManager`）；MNN.framework（人脸检测/嵌入，既有）；ONNXRuntime（MobileCLIP，既有）；XCTest 单测。

**Spec:** `docs/superpowers/specs/2026-08-10-ios-tag-scan-core-design.md`

---

## 构建环境（务必先读）

- 开发机为 **Intel Mac**：`MNN.framework` 仅 arm64-device → **模拟器 build/test 必 link 失败**。所有 build 与 test 都打真机。
- xcodegen 工作流：**新增/改动源文件后必须先 `xcodegen generate`**（从 `project.yml` 重生成 `.xcodeproj`，目录 glob 会自动纳入新文件）。仅在 `Podfile` 变化时才需 `pod install`（本计划不改 Podfile）。
- 所有命令在 `iosApp/` 下执行。

**常量命令**（后续任务引用）：
```bash
# 重新生成 Xcode 工程（新增源文件后必跑）
XGEN     = cd /Users/guoshuai/AndroidStudioProjects/polang/iosApp && xcodegen generate
# 真机构建（generic，绿构建）
BUILD    = xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -sdk iphoneos -configuration Debug -destination 'generic/platform=iOS' build
# 真机跑单测（把 <DEVICE> 换成连接的设备名，xcrun devicectl list devices 查）
TEST     = xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'platform=iOS,name=<DEVICE>' test
# 只编译测试目标（无设备时至少保证编译通过）
BFT      = xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS' build-for-testing
```

> 单测目标 `PoLangTests` 是 hosted bundle（TEST_HOST=PoLang.app），会 link MNN → 真机跑。无设备时用 `BFT` 至少做编译校验。

---

## 文件结构

**新增（源）：**
| 文件 | 职责 |
|---|---|
| `PoLang/Platform/Tag/ScanSessionState.swift` | 会话状态机枚举 + 合法迁移（纯逻辑，可单测） |
| `PoLang/Platform/Tag/ScanEtaEstimator.swift` | 滑动窗口中位数 ETA（纯逻辑，可单测） |
| `PoLang/Platform/Tag/ScanTaskPlanner.swift` | 扫描模式（全量/增量）+ Pass1 任务集合计算（纯逻辑，可单测） |
| `PoLang/Platform/TagDatabase+Scan.swift` | `TagDatabase` 扩展：`media_assets` get-or-create / 更新扫描列；`tag_scan_tasks` DAO |
| `PoLang/Platform/Tag/ScanImageLoader.swift` | 非主线程 `PHImageManager` 图片请求 |
| `PoLang/Platform/Tag/TagScanOrchestrator.swift` | 状态机驱动 + 后台运行循环 + pause/resume/cancel/retry + 中断恢复 |
| `PoLang/Features/TagScan/TagScanViewModel.swift` | `ObservableObject`，`@Published` 进度/状态/统计，暴露控制方法 |
| `PoLang/Features/TagScan/TagScanScreen.swift` | 扫描页主视图（8 section 编排） |
| `PoLang/Features/TagScan/TagScanComponents.swift` | 扫描页子组件（进度卡/统计卡/PassControlCard/精细控制等） |

**新增（测试）：** `PoLangTests/Tag/ScanSessionStateTests.swift`、`ScanEtaEstimatorTests.swift`、`ScanTaskPlannerTests.swift`、`TagDatabaseScanTests.swift`

**修改：**
| 文件 | 改动 |
|---|---|
| `PoLang/Platform/TagDatabase.swift` | 注入式 init（支持临时库做测试）+ schema 迁移（建 `media_assets`/`tag_scan_tasks`、drop `media_tags`） |
| `PoLang/Platform/Pass1Pipeline.swift` | 写入目标 `media_tags` → `media_assets`；加单图耗时埋点 |
| `PoLang/DI/AppContainer.swift` | 持有 `tagScanOrchestrator` / `tagScanViewModel` |
| `PoLang/Features/Main/MainTabView.swift` | TAG tab 占位 → `TagScanScreen` |
| `PoLang/Features/Gallery/GalleryGridView.swift` | 扫描图标 toast → `.fullScreenCover` 打开 `TagScanScreen` |
| `PoLang/Resources/Localizable.xcstrings` | 扫描页全部三语键 |

---

## Task 1: 会话状态机（纯逻辑）

**Files:**
- Create: `PoLang/Platform/Tag/ScanSessionState.swift`
- Test: `PoLangTests/Tag/ScanSessionStateTests.swift`

- [ ] **Step 1: 写失败测试**

```swift
// PoLangTests/Tag/ScanSessionStateTests.swift
import XCTest
@testable import PoLang

final class ScanSessionStateTests: XCTestCase {
    func testIdleCanStart() {
        XCTAssertEqual(ScanSessionState.idle.transition(.start), .running)
    }
    func testRunningCanPauseResumeCancelComplete() {
        XCTAssertEqual(ScanSessionState.running.transition(.pause), .pausing)
        XCTAssertEqual(ScanSessionState.running.transition(.cancel), .cancelling)
        XCTAssertEqual(ScanSessionState.running.transition(.complete), .completed)
    }
    func testPausingReachesPaused() {
        XCTAssertEqual(ScanSessionState.pausing.transition(.pauseAcknowledged), .paused)
    }
    func testPausedResumesToRunning() {
        XCTAssertEqual(ScanSessionState.paused.transition(.resume), .running)
    }
    func testCancellingReachesCancelled() {
        XCTAssertEqual(ScanSessionState.cancelling.transition(.cancelAcknowledged), .cancelled)
    }
    func testTerminalStatesAreSticky() {
        // 终态不接受任何非终态迁移
        for ev in ScanSessionEvent.allCases {
            XCTAssertNil(ScanSessionState.cancelled.transition(ev),
                         "cancelled 不应响应 \(ev)")
            XCTAssertNil(ScanSessionState.completed.transition(ev),
                         "completed 不应响应 \(ev)")
        }
    }
    func testIllegalTransitionsReturnNil() {
        XCTAssertNil(ScanSessionState.idle.transition(.pause))   // 未启动不能暂停
        XCTAssertNil(ScanSessionState.paused.transition(.pause)) // 已暂停不能再暂停
    }
    func testIsTerminal() {
        XCTAssertTrue(ScanSessionState.completed.isTerminal)
        XCTAssertTrue(ScanSessionState.cancelled.isTerminal)
        XCTAssertFalse(ScanSessionState.running.isTerminal)
    }
    func testLocalizedStateKey() {
        XCTAssertEqual(ScanSessionState.idle.localizationKey, "scan_state_idle")
        XCTAssertEqual(ScanSessionState.running.localizationKey, "scan_state_running")
        XCTAssertEqual(ScanSessionState.pausing.localizationKey, "scan_state_pausing")
        XCTAssertEqual(ScanSessionState.paused.localizationKey, "scan_state_paused")
        XCTAssertEqual(ScanSessionState.cancelling.localizationKey, "scan_state_cancelling")
        XCTAssertEqual(ScanSessionState.completed.localizationKey, "scan_state_completed")
        XCTAssertEqual(ScanSessionState.cancelled.localizationKey, "scan_state_cancelled")
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang/iosApp
xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'platform=iOS,name=<DEVICE>' test -only-testing:PoLangTests/ScanSessionStateTests
```
Expected: 编译失败（`ScanSessionState` 未定义）。

- [ ] **Step 3: 写最小实现**

```swift
// PoLang/Platform/Tag/ScanSessionState.swift
import Foundation

/// TAG 扫描会话状态机（对齐 Android ScanSessionState）。
enum ScanSessionState: String, CaseIterable, Sendable {
    case idle, running, pausing, paused, cancelling, cancelled, completed

    var isTerminal: Bool { self == .cancelled || self == .completed }

    /// UI 本地化键（在 Localizable.xcstrings 中补三语）
    var localizationKey: String {
        switch self {
        case .idle: return "scan_state_idle"
        case .running: return "scan_state_running"
        case .pausing: return "scan_state_pausing"
        case .paused: return "scan_state_paused"
        case .cancelling: return "scan_state_cancelling"
        case .cancelled: return "scan_state_cancelled"
        case .completed: return "scan_state_completed"
        }
    }

    private static let table: [ScanSessionState: [ScanSessionEvent: ScanSessionState]] = [
        .idle:       [.start: .running],
        .running:    [.pause: .pausing, .cancel: .cancelling, .complete: .completed],
        .pausing:    [.pauseAcknowledged: .paused, .cancel: .cancelling],
        .paused:     [.resume: .running, .cancel: .cancelling],
        .cancelling: [.cancelAcknowledged: .cancelled]
    ]

    /// 合法迁移返回新状态；非法或终态返回 nil。
    func transition(_ event: ScanSessionEvent) -> ScanSessionState? {
        if isTerminal { return nil }
        return ScanSessionState.table[self]?[event]
    }
}

enum ScanSessionEvent: CaseIterable, Sendable {
    case start, pause, pauseAcknowledged, resume
    case cancel, cancelAcknowledged, complete
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'platform=iOS,name=<DEVICE>' test -only-testing:PoLangTests/ScanSessionStateTests
```
Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang
git add iosApp/PoLang/Platform/Tag/ScanSessionState.swift iosApp/PoLangTests/Tag/ScanSessionStateTests.swift
git commit -m "feat(ios): TAG 扫描会话状态机（纯逻辑+单测）"
```

---

## Task 2: ETA 中位数估算器（纯逻辑）

**Files:**
- Create: `PoLang/Platform/Tag/ScanEtaEstimator.swift`
- Test: `PoLangTests/Tag/ScanEtaEstimatorTests.swift`

- [ ] **Step 1: 写失败测试**

```swift
// PoLangTests/Tag/ScanEtaEstimatorTests.swift
import XCTest
@testable import PoLang

final class ScanEtaEstimatorTests: XCTestCase {
    func testColdStartDefault() {
        // 无样本：用 Pass1 默认 800ms/img
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: [])
        XCTAssertEqual(eta.estimateMillis(remaining: 10), 8_000)
    }
    func testMedianOfWindow() {
        // 样本 [100,200,300,400,10000] → 中位数 300（>30min=1_800_000ms 的才过滤，10000 保留）
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: [100,200,300,400,10_000])
        XCTAssertEqual(eta.perItemMillis(), 300)
        XCTAssertEqual(eta.estimateMillis(remaining: 4), 1_200)
    }
    func testAnomalyFiltering() {
        // 含 >30min 的异常样本（1_900_000ms）应被过滤
        let eta = ScanEtaEstimator(pass: .faceDetection,
                                   samples: [200, 300, 1_900_000])
        XCTAssertEqual(eta.perItemMillis(), 250) // 中位数(200,300)=250
    }
    func testWindowCapsAt20() {
        let many = Array(repeating: 500, count: 30) + [9_000] // 最后一项进窗口
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: many)
        // 取最后 20 个：20×500 + 9000 → 中位数 500
        XCTAssertEqual(eta.perItemMillis(), 500)
    }
    func testEvenCountMedianIsAverageOfTwoMiddle() {
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: [100, 200, 300, 400])
        XCTAssertEqual(eta.perItemMillis(), 250) // (200+300)/2
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `xcodegen generate && xcodebuild ... test -only-testing:PoLangTests/ScanEtaEstimatorTests`，Expected: 编译失败。

- [ ] **Step 3: 写最小实现**

```swift
// PoLang/Platform/Tag/ScanEtaEstimator.swift
import Foundation

/// 扫描 Pass（与 tag_scan_tasks.pass 文本对齐；SP-B 只用 faceDetection）。
enum ScanPass: String, Sendable {
    case faceDetection = "FACE_DETECTION"
    case dbscan = "DBSCAN"
    case imageTagging = "IMAGE_TAGGING"
    case mobileClipEncoding = "MOBILE_CLIP_ENCODING"

    /// 冷启动默认单图耗时（对齐 Android TagScanOrchestrator 冷启值）。
    var coldStartMillis: Int {
        switch self {
        case .faceDetection: return 800
        case .dbscan: return 5_000
        case .imageTagging: return 7_000
        case .mobileClipEncoding: return 1_000
        }
    }
}

/// 滑动窗口中位数 ETA（对齐 Android：窗口 20、过滤 >30min 异常、冷启动默认值）。
struct ScanEtaEstimator: Sendable {
    static let windowSize = 20
    static let anomalyThresholdMs = 30 * 60 * 1_000 // 30 min

    let pass: ScanPass
    let samples: [Int] // 已观测的单图耗时 ms（按时间顺序）

    /// 中位数每图耗时；样本为空或全异常时回退冷启动默认。
    func perItemMillis() -> Int {
        let valid = samples.filter { $0 <= Self.anomalyThresholdMs }
        guard !valid.isEmpty else { return pass.coldStartMillis }
        let window = valid.suffix(Self.windowSize).sorted()
        let n = window.count
        let mid = n / 2
        if n % 2 == 1 { return window[mid] }
        return (window[mid - 1] + window[mid]) / 2
    }

    func estimateMillis(remaining: Int) -> Int {
        guard remaining > 0 else { return 0 }
        return perItemMillis() * remaining
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — `xcodebuild ... test -only-testing:PoLangTests/ScanEtaEstimatorTests`，Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add iosApp/PoLang/Platform/Tag/ScanEtaEstimator.swift iosApp/PoLangTests/Tag/ScanEtaEstimatorTests.swift
git commit -m "feat(ios): TAG 扫描 ETA 中位数估算器（纯逻辑+单测）"
```

---

## Task 3: 扫描模式 + 任务规划（纯逻辑）

**Files:**
- Create: `PoLang/Platform/Tag/ScanTaskPlanner.swift`
- Test: `PoLangTests/Tag/ScanTaskPlannerTests.swift`

- [ ] **Step 1: 写失败测试**

```swift
// PoLangTests/Tag/ScanTaskPlannerTests.swift
import XCTest
@testable import PoLang

final class ScanTaskPlannerTests: XCTestCase {
    func testIncrementalSkipsCovered() {
        // 相册 [1,2,3,4]，已覆盖 {2,4} → 增量只扫 [1,3]
        let plan = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: [1, 2, 3, 4],
            pass1CoveredMediaIds: Set([2, 4]),
            mode: .incremental
        )
        XCTAssertEqual(plan, [1, 3])
    }
    func testFullScansAll() {
        let plan = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: [1, 2, 3],
            pass1CoveredMediaIds: Set([1, 2]),
            mode: .full
        )
        XCTAssertEqual(plan, [1, 2, 3])
    }
    func testIncrementalEmptyWhenAllCovered() {
        let plan = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: [1, 2],
            pass1CoveredMediaIds: Set([1, 2]),
            mode: .incremental
        )
        XCTAssertTrue(plan.isEmpty)
    }
    func testPreservesInputOrder() {
        let plan = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: [5, 3, 1, 4],
            pass1CoveredMediaIds: Set([3]),
            mode: .incremental
        )
        XCTAssertEqual(plan, [5, 1, 4]) // 保持输入顺序，剔除 3
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `xcodegen generate && xcodebuild ... test -only-testing:PoLangTests/ScanTaskPlannerTests`，Expected: 编译失败。

- [ ] **Step 3: 写最小实现**

```swift
// PoLang/Platform/Tag/ScanTaskPlanner.swift
import Foundation

/// 扫描模式（对齐 Android SCAN_ALL / SCAN_INCREMENTAL）。
enum ScanMode: String, Sendable { case incremental, full }

enum ScanTaskPlanner {
    /// 计算 Pass1 待扫 mediaId 列表（保持输入顺序）。
    /// - incremental：剔除已覆盖 pass1 的 mediaId（去重）。
    /// - full：全部重扫。
    static func pass1TaskIds(
        allImageMediaIds: [Int64],
        pass1CoveredMediaIds: Set<Int64>,
        mode: ScanMode
    ) -> [Int64] {
        switch mode {
        case .full:
            return allImageMediaIds
        case .incremental:
            return allImageMediaIds.filter { !pass1CoveredMediaIds.contains($0) }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过** — Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add iosApp/PoLang/Platform/Tag/ScanTaskPlanner.swift iosApp/PoLangTests/Tag/ScanTaskPlannerTests.swift
git commit -m "feat(ios): TAG 扫描任务规划（全量/增量，纯逻辑+单测）"
```

---

## Task 4: TagDatabase 注入式 init + schema 迁移 + media_assets get-or-create

**Files:**
- Modify: `PoLang/Platform/TagDatabase.swift`（init 注入路径；schema 迁移）
- Create: `PoLang/Platform/TagDatabase+Scan.swift`（`media_assets` get-or-create / 更新扫描列）
- Test: `PoLangTests/Tag/TagDatabaseScanTests.swift`（本任务先写 media_assets 部分）

- [ ] **Step 1: 写失败测试**

```swift
// PoLangTests/Tag/TagDatabaseScanTests.swift
import XCTest
@testable import PoLang

final class TagDatabaseScanTests: XCTestCase {
    /// 用临时库测试，避免污染 Documents/polang_tag.db。
    func makeDb() -> TagDatabase {
        let tmp = NSTemporaryDirectory() + "tag_test_\(UUID().uuidString).db"
        return TagDatabase(dbPath: tmp)
    }

    // MARK: media_assets get-or-create
    func testGetOrCreateIsStable() {
        let db = makeDb()
        let id1 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE",
                                      captureDateMs: 1_000, fileName: "a.jpg")
        let id2 = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE",
                                      captureDateMs: 1_000, fileName: "a.jpg")
        XCTAssertEqual(id1, id2, "同一 localIdentifier 必须返回同一 id")
        XCTAssertGreaterThan(id1, 0)
    }
    func testGetOrCreateDistinct() {
        let db = makeDb()
        let a = db.getOrCreateMedia(localIdentifier: "A", type: "IMAGE", captureDateMs: 1, fileName: "a")
        let b = db.getOrCreateMedia(localIdentifier: "B", type: "IMAGE", captureDateMs: 2, fileName: "b")
        XCTAssertNotEqual(a, b)
    }
    func testUpdateScanFieldsWritesAndPreservesLabels() {
        let db = makeDb()
        let id = db.getOrCreateMedia(localIdentifier: "L-1", type: "IMAGE", captureDateMs: 1, fileName: "a")
        db.updateMediaAssetsScanFields(
            mediaId: id, hasFace: true, faceRoiResult: "{\"boxes\":[]}",
            faceFocusY: 0.4, semanticEmbedding: "BASE64",
            lastTagScanPasses: "{\"1\":1234}"
        )
        let covered = db.pass1CoveredMediaIds()
        XCTAssertEqual(covered, Set([id]))
        let stats = db.scanStats()
        XCTAssertEqual(stats.totalMedia, 1)
        XCTAssertEqual(stats.withFace, 1)
        XCTAssertEqual(stats.withSemantic, 1)
        XCTAssertEqual(stats.faceEmbeddingCount, 0) // 未写 embeddings
    }
}
```

- [ ] **Step 2: 跑测试确认失败** — `xcodegen generate && xcodebuild ... test -only-testing:PoLangTests/TagDatabaseScanTests`，Expected: 编译失败（`TagDatabase(dbPath:)` / 新方法不存在）。

- [ ] **Step 3a: 改 `TagDatabase.swift` —— 注入式 init**

打开 `PoLang/Platform/TagDatabase.swift`。找到现有：
```swift
static let shared = ...
private init() { ... queue.sync { openAndCreateSchema() } }
let dbFileName = "polang_tag.db"  // Documents/polang_tag.db
```
替换为（保持单例默认路径不变，新增注入式 init 供测试）：
```swift
static let shared = TagDatabase(dbPath: TagDatabase.defaultPath())

static func defaultPath() -> String {
    let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    return docs.appendingPathComponent("polang_tag.db").path
}

/// 注入式初始化（测试用临时库；生产用 defaultPath）。
init(dbPath: String) {
    self.dbPath = dbPath
    queue.sync { openAndCreateSchema() }
}

private let dbPath: String
```
（删除原 `private init()` 与 `dbFileName` 常量；把 `openAndCreateSchema` 内用到的 `dbFileName` 拼接路径替换为 `dbPath`。`sqlite3_open` 直接开 `dbPath`。）

- [ ] **Step 3b: 改 `TagDatabase.swift` —— schema 迁移**

在 `openAndCreateSchema()` 末尾（现有 `face_embeddings` / `persons` 建表之后，`media_tags` 建表删除/替换）：
```swift
// === TAG 扫描核心 schema（对齐 Android MediaEntity / TagScanTaskEntity）===
// 版本迁移：旧库可能已有空的 media_tags，废弃之（数据为空，可直接 drop）。
let PRAGMA_USER_VERSION // 读取当前 user_version
// 若 user_version < 2：DROP TABLE IF EXISTS media_tags; 并建新表
sqlite3_exec(db, "DROP TABLE IF EXISTS media_tags;", nil, nil, nil)

// media_assets：单表，扫描列并入（对齐 Android MediaEntity 27 列 + localIdentifier 唯一）
sqlite3_exec(db, """
CREATE TABLE IF NOT EXISTS media_assets (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uri TEXT NOT NULL,
  type TEXT NOT NULL,
  captureDate INTEGER NOT NULL,
  fileName TEXT NOT NULL,
  duration INTEGER,
  hasFace INTEGER DEFAULT 0,
  faceId TEXT,
  source TEXT,
  labels TEXT, labelsEn TEXT, labelsZh TEXT,
  mlKitLabels TEXT, mlKitLabelsZh TEXT,
  ocrText TEXT,
  latitude REAL, longitude REAL, locationName TEXT, city TEXT,
  indexedAt INTEGER,
  faceRoiResult TEXT, faceFocusY REAL,
  aestheticScore REAL, faceQualityScore REAL,
  semanticEmbedding TEXT,
  lastTagScanAt INTEGER,
  lastTagScanPasses TEXT,
  localIdentifier TEXT NOT NULL UNIQUE
);
""", nil, nil, nil)
sqlite3_exec(db, "CREATE INDEX IF NOT EXISTS idx_media_assets_captureDate ON media_assets(captureDate);", nil, nil, nil)
sqlite3_exec(db, "CREATE INDEX IF NOT EXISTS idx_media_assets_hasFace ON media_assets(hasFace);", nil, nil, nil)

// tag_scan_tasks：任务队列（对齐 Android TagScanTaskEntity + 3 索引）
sqlite3_exec(db, """
CREATE TABLE IF NOT EXISTS tag_scan_tasks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sessionId TEXT NOT NULL,
  mediaId INTEGER NOT NULL,
  pass TEXT NOT NULL,
  tagCategories TEXT,
  status TEXT NOT NULL DEFAULT 'PENDING',
  priority INTEGER NOT NULL DEFAULT 0,
  attemptCount INTEGER NOT NULL DEFAULT 0,
  createdAt INTEGER NOT NULL,
  scheduledAt INTEGER,
  startedAt INTEGER,
  completedAt INTEGER,
  errorMessage TEXT
);
""", nil, nil, nil)
sqlite3_exec(db, "CREATE INDEX IF NOT EXISTS idx_tasks_sched ON tag_scan_tasks(status, priority, scheduledAt);", nil, nil, nil)
sqlite3_exec(db, "CREATE INDEX IF NOT EXISTS idx_tasks_media ON tag_scan_tasks(mediaId, pass, status);", nil, nil, nil)
sqlite3_exec(db, "CREATE INDEX IF NOT EXISTS idx_tasks_session ON tag_scan_tasks(sessionId, status);", nil, nil, nil)

// 写入 user_version = 2
sqlite3_exec(db, "PRAGMA user_version = 2;", nil, nil, nil)
```
> 注意：保留现有 `face_embeddings` / `persons` 建表语句不动。删掉原 `media_tags` 的 `CREATE TABLE`（已由 DROP + 不再建替代）。

- [ ] **Step 3c: 新建 `TagDatabase+Scan.swift` —— media_assets 方法**

```swift
// PoLang/Platform/TagDatabase+Scan.swift
import Foundation
import SQLite3

extension TagDatabase {
    private static let msecNow: Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }()

    /// get-or-create：按 localIdentifier 返回稳定 Int64 id（对齐 Android media_assets.id）。
    @discardableResult
    func getOrCreateMedia(localIdentifier: String, type: String,
                          captureDateMs: Int64, fileName: String) -> Int64 {
        queue.sync {
            // 先查
            var id: Int64 = -1
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id FROM media_assets WHERE localIdentifier = ? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, localIdentifier, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW { id = sqlite3_column_int64(stmt, 0) }
            sqlite3_finalize(stmt)
            if id > 0 { return id }
            // 插入
            sqlite3_prepare_v2(db, """
            INSERT INTO media_assets (uri, type, captureDate, fileName, localIdentifier)
            VALUES (?, ?, ?, ?, ?);
            """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, localIdentifier, -1, nil) // uri = localIdentifier
            sqlite3_bind_text(stmt, 2, type, -1, nil)
            sqlite3_bind_int64(stmt, 3, captureDateMs)
            sqlite3_bind_text(stmt, 4, fileName, -1, nil)
            sqlite3_bind_text(stmt, 5, localIdentifier, -1, nil)
            sqlite3_step(stmt)
            sqlite3_finalize(stmt)
            return sqlite3_last_insert_rowid(db)
        }
    }

    /// 写 Pass1 产出列（对齐原 updateMediaTags 的字段集，目标改为 media_assets）。
    func updateMediaAssetsScanFields(mediaId: Int64, hasFace: Bool,
                                     faceRoiResult: String?, faceFocusY: Double?,
                                     semanticEmbedding: String?, lastTagScanPasses: String?) {
        queue.sync {
            sqlite3_exec(db, "BEGIN TRANSACTION;", nil, nil, nil)
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO media_assets(id) VALUES (?);", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
            sqlite3_prepare_v2(db, """
            UPDATE media_assets SET hasFace=?, faceRoiResult=?, faceFocusY=?,
                semanticEmbedding=?, lastTagScanPasses=?, lastTagScanAt=?
            WHERE id=?;
            """, -1, &stmt, nil)
            sqlite3_bind_int(stmt, 1, hasFace ? 1 : 0)
            if let s = faceRoiResult { sqlite3_bind_text(stmt, 2, s, -1, nil) } else { sqlite3_bind_null(stmt, 2) }
            if let f = faceFocusY { sqlite3_bind_double(stmt, 3, f) } else { sqlite3_bind_null(stmt, 3) }
            if let e = semanticEmbedding { sqlite3_bind_text(stmt, 4, e, -1, nil) } else { sqlite3_bind_null(stmt, 4) }
            if let p = lastTagScanPasses { sqlite3_bind_text(stmt, 5, p, -1, nil) } else { sqlite3_bind_null(stmt, 5) }
            sqlite3_bind_int64(stmt, 6, Self.msecNow)
            sqlite3_bind_int64(stmt, 7, mediaId)
            sqlite3_step(stmt); sqlite3_finalize(stmt)
            sqlite3_exec(db, "COMMIT;", nil, nil, nil)
        }
    }

    /// 已完成 Pass1 的 mediaId 集合（lastTagScanPasses 含 "1"）。
    func pass1CoveredMediaIds() -> Set<Int64> {
        queue.sync {
            var out = Set<Int64>()
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id, lastTagScanPasses FROM media_assets WHERE lastTagScanPasses IS NOT NULL;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                let id = sqlite3_column_int64(stmt, 0)
                if let cs = sqlite3_column_text(stmt, 1) {
                    let s = String(cString: cs)
                    if s.contains("\"1\"") { out.insert(id) }
                }
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// 所有图片类型的 mediaId（按 captureDate 降序，对齐 fetchAllMedia 排序）。
    func allImageMediaIds() -> [Int64] {
        queue.sync {
            var out: [Int64] = []
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id FROM media_assets WHERE type = 'IMAGE' ORDER BY captureDate DESC;", -1, &stmt, nil)
            while sqlite3_step(stmt) == SQLITE_ROW { out.append(sqlite3_column_int64(stmt, 0)) }
            sqlite3_finalize(stmt)
            return out
        }
    }

    /// localIdentifier → id 查询（给图片加载用）。
    func mediaId(forLocalIdentifier lid: String) -> Int64? {
        queue.sync {
            var id: Int64 = -1
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT id FROM media_assets WHERE localIdentifier = ? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, lid, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW { id = sqlite3_column_int64(stmt, 0) }
            sqlite3_finalize(stmt)
            return id > 0 ? id : nil
        }
    }

    /// 统计（对齐 Android TagScanDbStats；SP-B 未实现列返回 0）。
    func scanStats() -> ScanDbStats {
        queue.sync {
            func countInt(_ sql: String) -> Int {
                var n = 0; var stmt: OpaquePointer?
                sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
                if sqlite3_step(stmt) == SQLITE_ROW { n = Int(sqlite3_column_int(stmt, 0)) }
                sqlite3_finalize(stmt); return n
            }
            let total = countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE';")
            let withFace = countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND hasFace=1;")
            let withSemantic = countInt("SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND semanticEmbedding IS NOT NULL;")
            let emb = countInt("SELECT COUNT(*) FROM face_embeddings;")
            let remainingPass1 = countInt("""
            SELECT COUNT(*) FROM media_assets WHERE type='IMAGE' AND (
                lastTagScanPasses IS NULL OR lastTagScanPasses NOT LIKE '%\"1\"%');
            """)
            return ScanDbStats(totalMedia: total, withFace: withFace,
                               withLabels: 0, withSemantic: withSemantic,
                               personCount: 0, namedPersonCount: 0,
                               faceEmbeddingCount: emb,
                               remainingPass1: remainingPass1, remainingPass3: 0)
        }
    }
}

/// 扫描 DB 统计（对齐 Android TagScanDbStats；SP-B 未实现列恒 0）。
struct ScanDbStats: Sendable, Equatable {
    let totalMedia: Int
    let withFace: Int
    let withLabels: Int      // SP-D
    let withSemantic: Int
    let personCount: Int     // SP-C
    let namedPersonCount: Int// SP-C
    let faceEmbeddingCount: Int
    let remainingPass1: Int
    let remainingPass3: Int  // SP-D
}
```

- [ ] **Step 4: 跑测试确认通过** — `xcodegen generate && xcodebuild ... test -only-testing:PoLangTests/TagDatabaseScanTests`，Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add iosApp/PoLang/Platform/TagDatabase.swift iosApp/PoLang/Platform/TagDatabase+Scan.swift iosApp/PoLangTests/Tag/TagDatabaseScanTests.swift
git commit -m "feat(ios): TagDatabase schema 对齐 Android(media_assets 单表+任务队列) + get-or-create"
```

---

## Task 5: tag_scan_tasks DAO（队列入队/轮询/状态/mark/session 控制）

**Files:**
- Modify: `PoLang/Platform/TagDatabase+Scan.swift`（追加任务 DAO）
- Modify: `PoLangTests/Tag/TagDatabaseScanTests.swift`（追加任务测试）

- [ ] **Step 1: 写失败测试（追加到 TagDatabaseScanTests）**

```swift
    // MARK: tag_scan_tasks
    func testEnqueueAndPollFifo() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [10, 20, 30], now: now)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 3)
        let first = db.pollNextPending(sessionId: "S1", now: now)
        XCTAssertEqual(first?.mediaId, 10)
        XCTAssertNotNil(first?.taskId)
        XCTAssertEqual(first?.pass, "FACE_DETECTION")
        // poll 不改状态；markRunning 后再 poll 拿下一个
        db.markRunning(taskId: first!.taskId, now: now)
        let second = db.pollNextPending(sessionId: "S1", now: now)
        XCTAssertEqual(second?.mediaId, 20)
    }
    func testMarkCompletedReducesPending() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1, 2], now: now)
        let t = db.pollNextPending(sessionId: "S1", now: now)!
        db.markRunning(taskId: t.taskId, now: now)
        db.markCompleted(taskId: t.taskId, now: now)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "COMPLETED"), 1)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 1)
    }
    func testMarkFailedSetsBackoff() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1], now: now)
        let t = db.pollNextPending(sessionId: "S1", now: now)!
        db.markFailed(taskId: t.taskId, now: now, errorMessage: "boom", backoffMs: 5_000)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "FAILED"), 1)
        // backoff 未到 → poll 不到；模拟 now+6000 后能 poll 到
        XCTAssertNil(db.pollNextPending(sessionId: "S1", now: now + 1_000))
        XCTAssertNotNil(db.pollNextPending(sessionId: "S1", now: now + 6_000))
    }
    func testPauseCancelSession() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1, 2, 3], now: now)
        db.pauseSession(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PAUSED"), 3)
        XCTAssertNil(db.pollNextPending(sessionId: "S1", now: now), "PAUSED 不应被 poll")
        db.resumeSession(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 3)
        db.cancelSession(sessionId: "S1")
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "CANCELLED"), 3)
    }
    func testResetRunningToPending() {
        let db = makeDb()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        db.enqueuePass1Tasks(sessionId: "S1", mediaIds: [1, 2], now: now)
        let t = db.pollNextPending(sessionId: "S1", now: now)!
        db.markRunning(taskId: t.taskId, now: now)
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "RUNNING"), 1)
        db.resetRunningToPending(sessionId: "S1") // 中断恢复用
        XCTAssertEqual(db.countTasks(sessionId: "S1", status: "PENDING"), 2)
    }
```

- [ ] **Step 2: 跑测试确认失败** — Expected: 新方法不存在，编译失败。

- [ ] **Step 3: 实现 DAO（追加到 `TagDatabase+Scan.swift`）**

```swift
extension TagDatabase {
    struct QueuedTask: Sendable {
        let taskId: Int64
        let mediaId: Int64
        let pass: String
    }

    /// 批量入队 Pass1 任务。
    func enqueuePass1Tasks(sessionId: String, mediaIds: [Int64], now: Int64) {
        queue.sync {
            sqlite3_exec(db, "BEGIN TRANSACTION;", nil, nil, nil)
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            INSERT INTO tag_scan_tasks (sessionId, mediaId, pass, status, priority, attemptCount, createdAt, scheduledAt)
            VALUES (?, ?, 'FACE_DETECTION', 'PENDING', 0, 0, ?, ?);
            """, -1, &stmt, nil)
            for mid in mediaIds {
                sqlite3_bind_text(stmt, 1, sessionId, -1, nil)
                sqlite3_bind_int64(stmt, 2, mid)
                sqlite3_bind_int64(stmt, 3, now)
                sqlite3_bind_int64(stmt, 4, now)
                sqlite3_step(stmt)
                sqlite3_reset(stmt)
            }
            sqlite3_finalize(stmt)
            sqlite3_exec(db, "COMMIT;", nil, nil, nil)
        }
    }

    /// 取下一条可执行 PENDING（status=PENDING 且 scheduledAt<=now），FIFO。
    func pollNextPending(sessionId: String, now: Int64) -> QueuedTask? {
        queue.sync {
            var out: QueuedTask?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            SELECT id, mediaId, pass FROM tag_scan_tasks
            WHERE sessionId = ? AND status = 'PENDING' AND (scheduledAt IS NULL OR scheduledAt <= ?)
            ORDER BY priority ASC, id ASC LIMIT 1;
            """, -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, nil)
            sqlite3_bind_int64(stmt, 2, now)
            if sqlite3_step(stmt) == SQLITE_ROW {
                out = QueuedTask(taskId: sqlite3_column_int64(stmt, 0),
                                 mediaId: sqlite3_column_int64(stmt, 1),
                                 pass: String(cString: sqlite3_column_text(stmt, 2)))
            }
            sqlite3_finalize(stmt)
            return out
        }
    }

    func markRunning(taskId: Int64, now: Int64) {
        queue.sync {
            exec("UPDATE tag_scan_tasks SET status='RUNNING', startedAt=\(now) WHERE id=\(taskId);")
        }
    }
    func markCompleted(taskId: Int64, now: Int64) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='COMPLETED', completedAt=\(now) WHERE id=\(taskId);") }
    }
    func markFailed(taskId: Int64, now: Int64, errorMessage: String, backoffMs: Int64) {
        queue.sync {
            let sched = now + backoffMs
            let attempt = "(SELECT attemptCount FROM tag_scan_tasks WHERE id=\(taskId))+1"
            let sql = "UPDATE tag_scan_tasks SET status='FAILED', completedAt=\(now), " +
                      "errorMessage='\(errorMessage.replacingOccurrences(of: "'", with: "''"))', " +
                      "scheduledAt=\(sched), attemptCount=\(attempt) WHERE id=\(taskId);"
            exec(sql)
        }
    }
    func pauseSession(sessionId: String) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='PAUSED' WHERE sessionId='\(sessionId)' AND status IN ('PENDING','RUNNING');") }
    }
    func resumeSession(sessionId: String) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='PENDING' WHERE sessionId='\(sessionId)' AND status='PAUSED';") }
    }
    func cancelSession(sessionId: String) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='CANCELLED' WHERE sessionId='\(sessionId)' AND status IN ('PENDING','RUNNING','PAUSED');") }
    }
    func resetRunningToPending(sessionId: String) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='PENDING' WHERE sessionId='\(sessionId)' AND status='RUNNING';") }
    }
    func retryFailed(sessionId: String) {
        queue.sync { exec("UPDATE tag_scan_tasks SET status='PENDING', errorMessage=NULL WHERE sessionId='\(sessionId)' AND status='FAILED';") }
    }
    func countTasks(sessionId: String, status: String) -> Int {
        queue.sync {
            var n = 0; var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM tag_scan_tasks WHERE sessionId=? AND status=?;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, nil)
            sqlite3_bind_text(stmt, 2, status, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW { n = Int(sqlite3_column_int(stmt, 0)) }
            sqlite3_finalize(stmt); return n
        }
    }
    /// 统计 session 内各状态计数（给进度用）。
    func sessionCounts(_ sessionId: String) -> (pending: Int, running: Int, completed: Int, failed: Int, total: Int) {
        queue.sync {
            func c(_ status: String) -> Int { countTasks(sessionId: sessionId, status: status) }
            let pending = c("PENDING") + c("PAUSED")
            let running = c("RUNNING")
            let completed = c("COMPLETED")
            let failed = c("FAILED")
            return (pending, running, completed, failed, pending + running + completed + failed)
        }
    }
    /// 是否存在未完成 session（给中断恢复提示用）。
    func unfinishedSessionId() -> String? {
        queue.sync {
            var sid: String?
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, """
            SELECT sessionId FROM tag_scan_tasks
            WHERE status IN ('PENDING','RUNNING','PAUSED','FAILED') LIMIT 1;
            """, -1, &stmt, nil)
            if sqlite3_step(stmt) == SQLITE_ROW, let cs = sqlite3_column_text(stmt, 0) {
                sid = String(cString: cs)
            }
            sqlite3_finalize(stmt); return sid
        }
    }

    /// 内部：执行无参数 SQL（仅用于已转义的常量拼接）。
    fileprivate func exec(_ sql: String) {
        sqlite3_exec(db, sql, nil, nil, nil)
    }
}
```
> 注意：`countTasks` 在 `sessionCounts` 内部被嵌套调用时已在同一 `queue.sync` 外层锁里会死锁——因此 `sessionCounts` 内的 `c(_:)` 直接调 `countTasks`（自身再 `queue.sync`）会重入死锁。修正：把 `sessionCounts` 改为直接在自身 `queue.sync` 内用一条 GROUP BY 查询：
```swift
    func sessionCounts(_ sessionId: String) -> (pending: Int, running: Int, completed: Int, failed: Int, total: Int) {
        queue.sync {
            var p=0,r=0,co=0,f=0
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT status, COUNT(*) FROM tag_scan_tasks WHERE sessionId=? GROUP BY status;", -1, &stmt, nil)
            sqlite3_bind_text(stmt, 1, sessionId, -1, nil)
            while sqlite3_step(stmt) == SQLITE_ROW {
                let st = String(cString: sqlite3_column_text(stmt, 0))
                let n = Int(sqlite3_column_int(stmt, 1))
                switch st {
                case "PENDING","PAUSED": p += n
                case "RUNNING": r += n
                case "COMPLETED": co += n
                case "FAILED": f += n
                default: break
                }
            }
            sqlite3_finalize(stmt)
            return (p, r, co, f, p+r+co+f)
        }
    }
```

- [ ] **Step 4: 跑测试确认通过** — Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add iosApp/PoLang/Platform/TagDatabase+Scan.swift iosApp/PoLangTests/Tag/TagDatabaseScanTests.swift
git commit -m "feat(ios): tag_scan_tasks 任务队列 DAO（入队/轮询/mark/session 控制/中断恢复）"
```

---

## Task 6: 非主线程图片加载器

**Files:**
- Create: `PoLang/Platform/Tag/ScanImageLoader.swift`

> 说明：`ThumbnailLoader` 是 `@MainActor`，后台扫描每张图 hop 主线程不可接受。本任务加一条 off-main 的 `PHImageManager` 请求路径。无可单测的纯逻辑，做实现 + 构建校验。

- [ ] **Step 1: 实现**

```swift
// PoLang/Platform/Tag/ScanImageLoader.swift
import UIKit
import Photos

/// 扫描循环用的非主线程图片加载（ThumbnailLoader 是 @MainActor，不适合后台批扫）。
/// 请求长边 ~1024，覆盖 Pass1 检测(640) + 嵌入对齐裁剪 + MobileCLIP 编码。
enum ScanImageLoader {
    private static let maxPixel = 1024

    /// 根据 localIdentifier 加载图片（阻塞当前线程，须在后台 Task 调）。
    static func load(localIdentifier: String) -> UIImage? {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = assets.firstObject else { return nil }
        let opts = PHImageRequestOptions()
        opts.isNetworkAccessAllowed = false
        opts.deliveryMode = .highQualityFormat
        opts.isSynchronous = true
        var result: UIImage?
        // PHImageManager.requestImage 本身回调，isSynchronous=true 时阻塞至首帧
        let side = maxPixel
        _ = PHImageManager.default().requestImage(
            for: asset,
            targetSize: CGSize(width: side, height: side),
            contentMode: .aspectFit,
            options: opts
        ) { img, _ in result = img }
        return result
    }
}
```

- [ ] **Step 2: 构建校验**

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang/iosApp
xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -sdk iphoneos -configuration Debug -destination 'generic/platform=iOS' build
```
Expected: BUILD SUCCEEDED。

- [ ] **Step 3: 提交**

```bash
git add iosApp/PoLang/Platform/Tag/ScanImageLoader.swift
git commit -m "feat(ios): 扫描循环非主线程图片加载器 ScanImageLoader"
```

---

## Task 7: Pass1Pipeline 写入目标改 media_assets + 单图耗时埋点

**Files:**
- Modify: `PoLang/Platform/Pass1Pipeline.swift`

> 当前 `process(...)` 末尾调 `database.insertEmbeddings(...)` + `database.updateMediaTags(...)`（写 `media_tags`）。改为写 `media_assets`（`updateMediaAssetsScanFields`）+ 加耗时埋点。

- [ ] **Step 1: 改写入目标**

打开 `PoLang/Platform/Pass1Pipeline.swift`，定位 `process(...)` 末尾的 DB 写入（约 line 186-193）：
```swift
// 旧
database.insertEmbeddings(mediaId: mediaId, embeddings: result.embeddings)
database.updateMediaTags(mediaId: mediaId, hasFace: ..., faceRoiResult: ..., faceFocusY: ..., semanticEmbedding: ...)
```
替换为：
```swift
// 新：embeddings 仍写 face_embeddings（不变）；扫描列改写 media_assets（对齐 Android）
database.insertEmbeddings(mediaId: mediaId, embeddings: result.embeddings)
database.updateMediaAssetsScanFields(
    mediaId: mediaId,
    hasFace: result.hasFace,
    faceRoiResult: result.faceRoiJson,
    faceFocusY: result.faceFocusY,
    semanticEmbedding: result.semanticEmbeddingBase64,
    lastTagScanPasses: "{\"1\":\(Int64(Date().timeIntervalSince1970 * 1000))}"
)
```
> `updateMediaTags` 方法本身可保留（以防其他引用），但 `process` 不再调用它。若全仓再无引用，可在本任务一并删除该方法；用 `git grep updateMediaTags` 确认。

- [ ] **Step 2: 加单图耗时埋点（供 ETA 标定 / 日志）**

在 `process(...)` 入口加计时：
```swift
func process(_ image: UIImage, mediaId: Int64) -> Pass1Result {
    let _t0 = CFAbsoluteTimeGetCurrent()
    defer {
        let ms = Int((CFAbsoluteTimeGetCurrent() - _t0) * 1000)
        print("PoLang:TagScan pass1 mediaId=\(mediaId) cost=\(ms)ms")
    }
    // ...原有逻辑...
}
```

- [ ] **Step 3: 构建校验**

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang/iosApp
xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -sdk iphoneos -configuration Debug -destination 'generic/platform=iOS' build
```
Expected: BUILD SUCCEEDED。

- [ ] **Step 4: 提交**

```bash
git add iosApp/PoLang/Platform/Pass1Pipeline.swift
git commit -m "refactor(ios): Pass1Pipeline 写入目标改 media_assets + 单图耗时埋点"
```

---

## Task 8: TagScanOrchestrator（状态机驱动 + 后台运行循环 + 控制 + 中断恢复）

**Files:**
- Create: `PoLang/Platform/Tag/TagScanOrchestrator.swift`

> 设计：`final class TagScanOrchestrator: @unchecked Sendable`。状态/控制标志用 `OSAllocatedUnfairLock` 保护；单一后台 `Task(.utility)` 串行调 `Pass1Pipeline.shared`（MNN 单线程约束）；进度通过 `onEvent` 回调发出（ViewModel 在 MainActor 上转 `@Published`）。纯逻辑（状态机/ETA/规划）复用 Task 1-3。

- [ ] **Step 1: 实现**

```swift
// PoLang/Platform/Tag/TagScanOrchestrator.swift
import Foundation
import os

/// 扫描进度快照（对齐 Android TagScanSessionProgress）。
struct TagScanSessionProgress: Sendable, Equatable {
    var sessionId: String
    var state: ScanSessionState
    var currentPass: ScanPass
    var processed: Int
    var total: Int
    var pending: Int
    var failed: Int
    var estimatedRemainingMs: Int
    var message: String
}

/// 扫描事件（orchestrator → ViewModel）。
enum ScanEvent: Sendable {
    case progress(TagScanSessionProgress)
    case finished(ScanSessionState)   // completed / cancelled
}

final class TagScanOrchestrator: @unchecked Sendable {
    static let shared = TagScanOrchestrator()

    /// 进度回调（ViewModel 订阅；在后台线程发出，订阅方需自行切主线程）。
    var onEvent: (@Sendable (ScanEvent) -> Void)?

    private let db = TagDatabase.shared
    private let media = PhMediaBridge()
    private let lock: OSAllocatedUnfairLock<Box> = .init(initialState: Box())

    private struct Box {
        var state: ScanSessionState = .idle
        var sessionId: String? = nil
        var pauseRequested: Bool = false
        var cancelRequested: Bool = false
        var samples: [Int] = []          // Pass1 单图耗时样本
        var processed: Int = 0
        var failed: Int = 0
        var total: Int = 0
        var task: Task<Void, Never>? = nil
    }

    private init() {
        // 中断恢复：把上次 RUNNING 重置为 PENDING，等用户在扫描页点恢复。
        if let sid = db.unfinishedSessionId() {
            db.resetRunningToPending(sessionId: sid)
        }
    }

    // MARK: - 公共控制（UI 在 MainActor 调）

    /// 启动新扫描会话。
    func start(mode: ScanMode) {
        lock.withUnsafeMutatingField { box in
            guard box.state == .idle || box.state.isTerminal else { return }
            // 1) 同步 media_assets 索引（全量 get-or-create）
            let now = Self.nowMs()
            for item in media.fetchAllMedia() where item.mediaType == "PHOTO" {
                db.getOrCreateMedia(localIdentifier: item.localIdentifier,
                                    type: "IMAGE",
                                    captureDateMs: Int64(item.captureDateMs ?? now),
                                    fileName: item.fileName ?? "")
            }
            // 2) 规划 Pass1 任务集
            let allIds = db.allImageMediaIds()
            let covered = db.pass1CoveredMediaIds()
            let planned = ScanTaskPlanner.pass1TaskIds(
                allImageMediaIds: allIds, pass1CoveredMediaIds: covered, mode: mode)
            guard !planned.isEmpty else {
                box.state = .completed
                emit(.finished(.completed)); emit(progress(box)); return
            }
            // 3) 建会话入队
            let sid = "tag-\(UUID().uuidString.prefix(8))"
            db.enqueuePass1Tasks(sessionId: sid, mediaIds: planned, now: now)
            box.sessionId = sid
            box.state = box.state.transition(.start) ?? .running
            box.total = planned.count
            box.processed = 0; box.failed = 0
            box.samples = []
            box.pauseRequested = false; box.cancelRequested = false
            emit(progress(box))
            // 4) 启后台运行循环
            box.task?.cancel()
            box.task = Task.detached(priority: .utility) { [weak self] in
                await self?.runLoop()
            }
        }
    }

    func pause() {
        lock.withUnsafeMutatingField { box in
            guard box.state == .running else { return }
            box.pauseRequested = true
            box.state = box.state.transition(.pause) ?? .pausing
        }
        emit(progress(lock.withUnsafeMutableField { $0 }))
    }
    func resume() {
        lock.withUnsafeMutatingField { box in
            guard box.state == .paused, let sid = box.sessionId else { return }
            db.resumeSession(sessionId: sid)
            box.pauseRequested = false
            box.state = box.state.transition(.resume) ?? .running
            box.task?.cancel()
            box.task = Task.detached(priority: .utility) { [weak self] in
                await self?.runLoop()
            }
        }
        emit(progress(lock.withUnsafeMutableField { $0 }))
    }
    func cancel() {
        lock.withUnsafeMutatingField { box in
            guard let sid = box.sessionId, !box.state.isTerminal else { return }
            box.cancelRequested = true
            db.cancelSession(sessionId: sid)
            box.state = .cancelling
            box.task?.cancel()
            box.state = box.state.transition(.cancelAcknowledged) ?? .cancelled
            emit(progress(box)); emit(.finished(.cancelled))
            resetBox(box)
        }
    }
    func retryFailed() {
        lock.withUnsafeMutatingField { box in
            guard let sid = box.sessionId else { return }
            db.retryFailed(sessionId: sid)
            if box.state == .paused || box.state == .idle || box.state.isTerminal {
                box.state = .running
                box.task = Task.detached(priority: .utility) { [weak self] in
                    await self?.runLoop()
                }
            }
        }
    }

    /// 当前进度快照（UI 刷新用）。
    func snapshot() -> TagScanSessionProgress? {
        lock.withUnsafeMutableField { box in
            guard box.state != .idle else { return nil }
            return progress(box)
        }
    }
    /// 是否有未完成 session（扫描页顶部「恢复」提示用）。
    var hasUnfinishedSession: Bool { db.unfinishedSessionId() != nil }

    // MARK: - 运行循环（后台 Task）

    private func runLoop() async {
        let sid = lock.withUnsafeMutableField { $0.sessionId } ?? ""
        var etaSamples: [Int] = []
        while !Task.isCancelled {
            // 取标志
            let (shouldPause, shouldCancel) = lock.withUnsafeMutableField {
                ($0.pauseRequested, $0.cancelRequested)
            }
            if shouldCancel { return }
            if shouldPause {
                // 落库 PAUSED 并退出循环，等 resume 重启
                db.pauseSession(sessionId: sid)
                lock.withUnsafeMutatingField { box in
                    box.state = box.state.transition(.pauseAcknowledged) ?? .paused
                    emit(progress(box))
                }
                return
            }
            let now = Self.nowMs()
            guard let task = db.pollNextPending(sessionId: sid, now: now) else {
                // 队列空 → 完成
                lock.withUnsafeMutatingField { box in
                    box.state = box.state.transition(.complete) ?? .completed
                    emit(progress(box)); emit(.finished(.completed))
                    resetBox(box)
                }
                return
            }
            db.markRunning(taskId: task.taskId, now: now)
            // 执行 Pass1（同步、串行、后台线程）
            let lid = localIdentifier(for: task.mediaId)
            let img = lid.flatMap { ScanImageLoader.load(localIdentifier: $0) }
            if let image = img {
                let t0 = CFAbsoluteTimeGetCurrent()
                _ = Pass1Pipeline.shared.process(image, mediaId: task.mediaId)
                let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
                etaSamples.append(ms)
                db.markCompleted(taskId: task.taskId, now: Self.nowMs())
                lock.withUnsafeMutatingField { box in
                    box.processed += 1
                    box.samples = etaSamples
                    emit(progress(box))
                }
            } else {
                db.markFailed(taskId: task.taskId, now: now,
                              errorMessage: "image load failed", backoffMs: 5_000)
                lock.withUnsafeMutatingField { box in
                    box.failed += 1
                    emit(progress(box))
                }
            }
            // 任务间轻让步（对齐 Android POLL_INTERVAL≈100ms，但不阻塞过久）
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    // MARK: - 辅助

    private func localIdentifier(for mediaId: Int64) -> String? {
        // media_assets.uri 即 localIdentifier
        var lid: String?
        db.queryMediaAssetUri(mediaId: mediaId) { s in lid = s }
        return lid
    }
    private func progress(_ box: Box) -> TagScanSessionProgress {
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: box.samples)
            .estimateMillis(remaining: max(0, box.total - box.processed))
        let pending = max(0, box.total - box.processed - box.failed)
        return TagScanSessionProgress(
            sessionId: box.sessionId ?? "",
            state: box.state,
            currentPass: .faceDetection,
            processed: box.processed, total: box.total,
            pending: pending, failed: box.failed,
            estimatedRemainingMs: eta,
            message: box.state.localizationKey)
    }
    private func resetBox(_ box: inout Box) {
        box.task = nil
        // 保留 sessionId 供 finished 后查询；state 留在终态，下次 start 重置
    }
    private func emit(_ ev: ScanEvent) { onEvent?(ev) }
    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
```

> 需要 `TagDatabase` 暴露 `queryMediaAssetUri(mediaId:)` —— 在 `TagDatabase+Scan.swift` 追加：
```swift
    func queryMediaAssetUri(mediaId: Int64, _ cb: (String?) -> Void) {
        queue.sync {
            var stmt: OpaquePointer?
            sqlite3_prepare_v2(db, "SELECT uri FROM media_assets WHERE id=? LIMIT 1;", -1, &stmt, nil)
            sqlite3_bind_int64(stmt, 1, mediaId)
            if sqlite3_step(stmt) == SQLITE_ROW, let cs = sqlite3_column_text(stmt, 0) {
                cb(String(cString: cs))
            } else { cb(nil) }
            sqlite3_finalize(stmt)
        }
    }
```

- [ ] **Step 2: 构建校验**

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang/iosApp
xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -sdk iphoneos -configuration Debug -destination 'generic/platform=iOS' build
```
Expected: BUILD SUCCEEDED。修掉编译错误（注意 `PhMediaBridge` 的 `fetchAllMedia()` 返回 `[IosMediaItem]`，字段 `localIdentifier/mediaType/captureDateMs/fileName` 对齐 `IosMediaItem` 定义；`IosMediaItem.mediaType` 字符串值用 `"PHOTO"`，见 `GalleryViewModelTests` 桩）。

- [ ] **Step 3: 提交**

```bash
git add iosApp/PoLang/Platform/Tag/TagScanOrchestrator.swift iosApp/PoLang/Platform/TagDatabase+Scan.swift
git commit -m "feat(ios): TagScanOrchestrator 状态机+后台运行循环+pause/resume/cancel/retry+中断恢复"
```

---

## Task 9: TagScanViewModel（ObservableObject，@Published 进度/统计）

**Files:**
- Create: `PoLang/Features/TagScan/TagScanViewModel.swift`

- [ ] **Step 1: 实现**

```swift
// PoLang/Features/TagScan/TagScanViewModel.swift
import SwiftUI

@MainActor
final class TagScanViewModel: ObservableObject {
    @Published private(set) var progress: TagScanSessionProgress?
    @Published private(set) var stats: ScanDbStats = .init(totalMedia: 0, withFace: 0, withLabels: 0,
        withSemantic: 0, personCount: 0, namedPersonCount: 0,
        faceEmbeddingCount: 0, remainingPass1: 0, remainingPass3: 0)
    @Published private(set) var hasUnfinishedSession: Bool = false

    private let orchestrator = TagScanOrchestrator.shared

    init() {
        orchestrator.onEvent = { [weak self] ev in
            Task { @MainActor in
                guard let self else { return }
                switch ev {
                case .progress(let p): self.progress = p
                case .finished: self.refreshStats()
                }
            }
        }
        refreshStats()
    }

    var isScanning: Bool {
        let s = progress?.state ?? .idle
        return s == .running || s == .pausing
    }

    func startFull() { orchestrator.start(mode: .full); refreshStats() }
    func startIncremental() { orchestrator.start(mode: .incremental); refreshStats() }
    func pause() { orchestrator.pause() }
    func resume() { orchestrator.resume() }
    func cancel() { orchestrator.cancel() }
    func retryFailed() { orchestrator.retryFailed() }

    func refreshStats() {
        stats = TagDatabase.shared.scanStats()
        hasUnfinishedSession = orchestrator.hasUnfinishedSession
    }

    /// 恢复上次未完成 session（进入扫描页提示后用）。
    func resumeUnfinished() {
        // 若当前不在 running，触发一次 resume（orchestrator 内部按 sessionId 续）
        if progress?.state != .running { resume() }
    }
}
```

- [ ] **Step 2: 构建校验**

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang/iosApp
xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -sdk iphoneos -configuration Debug -destination 'generic/platform=iOS' build
```
Expected: BUILD SUCCEEDED。

- [ ] **Step 3: 提交**

```bash
git add iosApp/PoLang/Features/TagScan/TagScanViewModel.swift
git commit -m "feat(ios): TagScanViewModel（@Published 进度/统计，桥接 Orchestrator）"
```

---

## Task 10: i18n 键（三语 xcstrings）

**Files:**
- Modify: `PoLang/Resources/Localizable.xcstrings`

> REQUIRED SKILL: 调用 `/ios-i18n-validator` 保证三语（en/zh-Hans/zh-Hant）同步、键命名对齐 Android 字符串、无硬编码。

- [ ] **Step 1: 用 ios-i18n-validator skill 增补下列键的三语**

需新增键（扫描页 8 section 全覆盖）：
- 标题：`scan_title`
- 后台守护横幅：`scan_background_banner`
- 状态：`scan_state_idle/running/pausing/paused/cancelling/cancelled/completed`
- 控制按钮：`scan_action_pause/resume/cancel/retry`
- 快捷操作：`scan_action_scan_full/scan_incremental`
- ETA/统计标签：`scan_eta_remaining`, `scan_stat_total/with_face/with_labels/with_semantic/person_count/embedding_count/remaining_pass1/remaining_pass3`
- 管线概览：`scan_pipeline_face/cluster/content`, `scan_pipeline_coming`
- PassControlCard：`scan_pass1_title/pass2_title/pass3_title/aesthetic_title`, `scan_action_incremental/full`
- 精细控制：`scan_cat_face/scene/activity/objects/tags/summary`, `scan_range_all/7d/30d/90d`, `scan_full_regenerate`, `scan_regenerate_selected`
- 占位/toast：`scan_coming_soon_badge`, `scan_coming_soon_toast`
- 中断恢复：`scan_resume_unfinished`

- [ ] **Step 2: 校验三语同步** — 运行 `/ios-i18n-validator`，Expected: 无缺失语言、无硬编码。

- [ ] **Step 3: 提交**

```bash
git add iosApp/PoLang/Resources/Localizable.xcstrings
git commit -m "i18n(ios): TAG 扫描页三语（en/zh-Hans/zh-Hant）键补齐"
```

---

## Task 11: TagScanScreen（SwiftUI，8 section 完全复刻）

**Files:**
- Create: `PoLang/Features/TagScan/TagScanScreen.swift`
- Create: `PoLang/Features/TagScan/TagScanComponents.swift`

> 完全复刻 `androidApp/.../features/gallery/components/TagGenerationControlScreen.kt` 的 8 个 section（顺序一致）。SP-B 仅 Pass1 可用，依赖 Pass2/Pass3 的控件**渲染但置灰 +「后续阶段」徽标**，点击 toast。下面给出主视图编排 + 关键组件（进度卡 / PassControlCard 带置灰态 / 精细控制），其余组件按相同模式补齐。

- [ ] **Step 1: 主视图 `TagScanScreen.swift`**

```swift
// PoLang/Features/TagScan/TagScanScreen.swift
import SwiftUI

struct TagScanScreen: View {
    @StateObject private var vm = TagScanViewModel()
    @Environment(\.dismiss) private var dismiss
    @State private var showComingSoon = false
    @State private var showResumePrompt = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if vm.hasUnfinishedSession && vm.progress?.state != .running {
                        resumePromptRow
                    }
                    ScanBackgroundBanner()                     // 1) 后台守护横幅
                    ScanProgressCard(progress: vm.progress)    // 2) 进度卡
                    ScanControlRow(vm: vm)                     // 3) 会话控制条
                    ScanStatsCard(stats: vm.stats)             // 4) 统计卡
                    ScanPipelineOverview()                     // 5) 管线概览
                    ScanQuickActions(vm: vm)                   // 6) 全量/增量
                    ScanPassControlSection(vm: vm,             // 7) 4 张 PassControlCard
                                           onDisabled: { showComingSoon = true })
                    ScanFineControlSection(onDisabled: { showComingSoon = true }) // 8) 精细控制
                }
                .padding()
            }
            .navigationTitle(Text("scan_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) {
                Button(action: { dismiss() }) { Image(systemName: "xmark.circle.fill") }
            } }
            .onAppear {
                vm.refreshStats()
                if vm.hasUnfinishedSession { showResumePrompt = true }
            }
            .alert(Text("scan_coming_soon_toast"), isPresented: $showComingSoon) {
                Button("OK", role: .cancel) {}
            }
        }
    }

    private var resumePromptRow: some View {
        HStack {
            Text("scan_resume_unfinished").font(.subheadline)
            Spacer()
            Button("scan_action_resume") { vm.resumeUnfinished() }.buttonStyle(.bordered)
        }
        .padding(12).background(.thinMaterial).clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
```

- [ ] **Step 2: 组件 `TagScanComponents.swift`（含置灰态模式）**

```swift
// PoLang/Features/TagScan/TagScanComponents.swift
import SwiftUI

// 1) 后台守护横幅（iOS：静态电量/热态提示）
struct ScanBackgroundBanner: View {
    var body: some View {
        Label("scan_background_banner", systemImage: "battery.25")
            .font(.footnote).foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10).background(.thinMaterial).clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// 2) 进度卡
struct ScanProgressCard: View {
    let progress: TagScanSessionProgress?
    var body: some View {
        let p = progress
        return VStack(alignment: .leading, spacing: 8) {
            Text(p?.state.localizationKey ?? "scan_state_idle").font(.headline)
            if let p {
                ProgressView(value: Double(p.processed), total: max(1, Double(p.total)))
                HStack(spacing: 16) {
                    Label("\(p.processed)/\(p.total)", systemImage: "photo")
                    Label("\(p.failed)", systemImage: "exclamationmark.triangle")
                    Label(etaText(p.estimatedRemainingMs), systemImage: "clock")
                }.font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(14).background(.thinMaterial).clipShape(RoundedRectangle(cornerRadius: 14))
    }
    private func etaText(_ ms: Int) -> String {
        let s = ms / 1000
        return String(format: NSLocalizedString("scan_eta_remaining", comment: ""), s >= 60 ? "\(s/60)m" : "\(s)s")
    }
}

// 3) 会话控制条
struct ScanControlRow: View {
    @ObservedObject var vm: TagScanViewModel
    var body: some View {
        let state = vm.progress?.state ?? .idle
        HStack {
            switch state {
            case .running: Button("scan_action_pause") { vm.pause() }.buttonStyle(.borderedProminent)
            case .paused:  Button("scan_action_resume") { vm.resume() }.buttonStyle(.borderedProminent)
            default: EmptyView()
            }
            if state == .running || state == .paused {
                Button("scan_action_cancel") { vm.cancel() }.buttonStyle(.bordered)
            }
            if vm.progress?.failed ?? 0 > 0 {
                Button("scan_action_retry") { vm.retryFailed() }.buttonStyle(.bordered)
            }
        }
    }
}

// 4) 统计卡
struct ScanStatsCard: View {
    let stats: ScanDbStats
    var body: some View {
        let rows: [(String, Int)] = [
            ("scan_stat_total", stats.totalMedia),
            ("scan_stat_with_face", stats.withFace),
            ("scan_stat_with_labels", stats.withLabels),
            ("scan_stat_with_semantic", stats.withSemantic),
            ("scan_stat_person_count", stats.personCount),
            ("scan_stat_embedding_count", stats.faceEmbeddingCount),
            ("scan_stat_remaining_pass1", stats.remainingPass1),
            ("scan_stat_remaining_pass3", stats.remainingPass3)
        ]
        return LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
            ForEach(rows, id: \.0) { row in
                statCell(row.0, row.1)
            }
        }
    }
    private func statCell(_ key: String, _ value: Int) -> some View {
        VStack(alignment: .leading) {
            Text("\(value)").font(.title3).bold()
            Text(LocalizedStringKey(key)).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(10)
        .background(.thinMaterial).clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// 5) 管线概览
struct ScanPipelineOverview: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            pipelineRow("scan_pipeline_face", enabled: true)
            pipelineRow("scan_pipeline_cluster", enabled: false)
            pipelineRow("scan_pipeline_content", enabled: false)
        }.padding(14).background(.thinMaterial).clipShape(RoundedRectangle(cornerRadius: 14))
    }
    private func pipelineRow(_ key: String, enabled: Bool) -> some View {
        HStack {
            Image(systemName: enabled ? "checkmark.circle.fill" : "hourglass")
                .foregroundStyle(enabled ? .green : .secondary)
            Text(key)
            if !enabled { Text("scan_pipeline_coming").font(.caption2).foregroundStyle(.secondary) }
            Spacer()
        }
    }
}

// 6) 快捷操作（idle）
struct ScanQuickActions: View {
    @ObservedObject var vm: TagScanViewModel
    var body: some View {
        let idle = (vm.progress?.state ?? .idle) == .idle && !vm.hasUnfinishedSession
        return Group { if idle {
            HStack {
                Button("scan_action_scan_full") { vm.startFull() }.buttonStyle(.borderedProminent)
                Button("scan_action_scan_incremental") { vm.startIncremental() }.buttonStyle(.bordered)
            }
        }}
    }
}

// 7) PassControlCard（带置灰态）
struct PassControlCard: View {
    let titleKey: String
    let enabled: Bool
    var onIncremental: () -> Void
    var onFull: () -> Void
    var onDisabled: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(titleKey).font(.subheadline).bold()
                if !enabled { Text("scan_coming_soon_badge").font(.caption2)
                    .padding(.horizontal,6).padding(.vertical,2)
                    .background(.secondary.opacity(0.2)).clipShape(Capsule()) }
            }
            HStack {
                actionBtn("scan_action_incremental")
                actionBtn("scan_action_full")
            }
        }
        .padding(12).background(enabled ? .thinMaterial : Color.gray.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .opacity(enabled ? 1 : 0.6)
    }
    private func actionBtn(_ key: String) -> some View {
        Button(action: { enabled ? onIncremental() : onDisabled() }) {
            Text(key).font(.caption)
        }.buttonStyle(.bordered).disabled(!enabled)
    }
}

// 7 容器：4 张卡，仅 Pass1 可用
struct ScanPassControlSection: View {
    @ObservedObject var vm: TagScanViewModel
    var onDisabled: () -> Void
    var body: some View {
        VStack(spacing: 10) {
            PassControlCard(titleKey: "scan_pass1_title", enabled: true,
                            onIncremental: { vm.startIncremental() }, onFull: { vm.startFull() },
                            onDisabled: onDisabled)
            PassControlCard(titleKey: "scan_pass2_title", enabled: false,
                            onIncremental: {}, onFull: {}, onDisabled: onDisabled)
            PassControlCard(titleKey: "scan_pass3_title", enabled: false,
                            onIncremental: {}, onFull: {}, onDisabled: onDisabled)
            PassControlCard(titleKey: "scan_aesthetic_title", enabled: false,
                            onIncremental: {}, onFull: {}, onDisabled: onDisabled)
        }
    }
}

// 8) 精细控制（分类 chips / 时间范围 / fullRegenerate / 按选择重生成）
struct ScanFineControlSection: View {
    var onDisabled: () -> Void
    private let categories = ["scan_cat_face","scan_cat_scene","scan_cat_activity",
                              "scan_cat_objects","scan_cat_tags","scan_cat_summary"]
    private let ranges = ["scan_range_all","scan_range_7d","scan_range_30d","scan_range_90d"]
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("scan_cat_face").font(.caption).foregroundStyle(.secondary) // section 标题复用
            FlowChips(items: categories, enabledIndices: [0], onDisabled: onDisabled)
            Divider()
            HStack { ForEach(ranges.indices, id: \.self) { i in
                Button(ranges[i]) {}.buttonStyle(.bordered).disabled(i != 0)
                    .onTapGesture { if i != 0 { onDisabled() } }
            }}
            Toggle("scan_full_regenerate", isOn: .constant(false)).disabled(true)
            Button("scan_regenerate_selected") {}.buttonStyle(.bordered).disabled(true)
                .onTapGesture { onDisabled() }
        }
        .padding(14).background(.thinMaterial).clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

/// 简易 chips 流式布局（分类用）。
struct FlowChips: View {
    let items: [String]; let enabledIndices: Set<Int>; var onDisabled: () -> Void
    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items.indices, id: \.self) { i in
                let enabled = enabledIndices.contains(i)
                Text(items[i]).font(.caption)
                    .padding(.horizontal,10).padding(.vertical,5)
                    .background(enabled ? Color.accentColor.opacity(0.15) : Color.gray.opacity(0.12))
                    .clipShape(Capsule())
                    .foregroundStyle(enabled ? .primary : .secondary)
                    .onTapGesture { if !enabled { onDisabled() } }
            }
        }
    }
}
```

> `FlowLayout`：项目若已无现成流式布局，在 `TagScanComponents.swift` 末尾追加一个最小实现（基于 `Layout` 协议，iOS 16）：
```swift
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxW = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > maxW { x = 0; y += rowH + spacing; rowH = 0 }
            x += s.width + spacing; rowH = max(rowH, s.height)
        }
        return CGSize(width: maxW, height: y + rowH)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let maxW = bounds.width
        var x: CGFloat = bounds.minX, y: CGFloat = bounds.minY, rowH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > bounds.minX + maxW { x = bounds.minX; y += rowH + spacing; rowH = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing; rowH = max(rowH, s.height)
        }
    }
}
```

- [ ] **Step 3: 构建校验**

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang/iosApp
xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -sdk iphoneos -configuration Debug -destination 'generic/platform=iOS' build
```
Expected: BUILD SUCCEEDED。

- [ ] **Step 4: 提交**

```bash
git add iosApp/PoLang/Features/TagScan/TagScanScreen.swift iosApp/PoLang/Features/TagScan/TagScanComponents.swift
git commit -m "feat(ios): TagScanScreen 扫描页（完全复刻 Android 8 section，Pass2/3 置灰）"
```

---

## Task 12: 接线（TAG tab + 相册扫描图标 + AppContainer）

**Files:**
- Modify: `PoLang/Features/Main/MainTabView.swift`
- Modify: `PoLang/Features/Gallery/GalleryGridView.swift`
- Modify: `PoLang/DI/AppContainer.swift`

- [ ] **Step 1: MainTabView —— TAG tab 激活**

打开 `PoLang/Features/Main/MainTabView.swift`，找到 TAG tab 的占位分支（约 line 54，`showPlaceholder == "tag"` 渲染 `PlaceholderPage`）。把 `PlaceholderPage` 替换为 `TagScanScreen()`：
```swift
// 旧：PlaceholderPage(...)
// 新：
TagScanScreen()
```
（保留该 tab 的页面切换/ZStack 逻辑不变，仅替换内容视图。）

- [ ] **Step 2: GalleryGridView —— 扫描图标 secondary 入口**

打开 `PoLang/Features/Gallery/GalleryGridView.swift`。当前扫描图标闭包（约 line 114）：
```swift
comingSoonFeature = String(localized: "Tag scanning is not available in this version.")
```
改为 `@State var showScanScreen = false` + `.fullScreenCover`：
```swift
// 顶部加 @State private var showScanScreen = false
// 闭包改为：
showScanScreen = true
// 在 GalleryGridView body 上加（沿用已有 .fullScreenCover 模式，约 line 72-75 旁）：
.fullScreenCover(isPresented: $showScanScreen) { TagScanScreen() }
```

- [ ] **Step 3: AppContainer —— 持有 orchestrator/viewModel**

打开 `PoLang/DI/AppContainer.swift`，在现有属性（`mediaRepository` 等）旁追加：
```swift
let tagScanOrchestrator = TagScanOrchestrator.shared
@MainActor lazy var tagScanViewModel = TagScanViewModel()
```
（ViewModel 用 `lazy` + `@MainActor`，首次访问在主线程构造。）

- [ ] **Step 4: 构建校验**

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang/iosApp
xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -sdk iphoneos -configuration Debug -destination 'generic/platform=iOS' build
```
Expected: BUILD SUCCEEDED。

- [ ] **Step 5: 提交**

```bash
git add iosApp/PoLang/Features/Main/MainTabView.swift iosApp/PoLang/Features/Gallery/GalleryGridView.swift iosApp/PoLang/DI/AppContainer.swift
git commit -m "feat(ios): 接线 TAG tab + 相册扫描图标 + AppContainer 持有扫描组件"
```

---

## Task 13: 真机端到端验证 + 收尾

**Files:** 无新文件（验证 + 修复）

- [ ] **Step 1: 真机全量构建安装**

```bash
cd /Users/guoshuai/AndroidStudioProjects/polang/iosApp
xcodegen generate
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -sdk iphoneos -configuration Debug -destination 'platform=iOS,name=<DEVICE>' build
# 安装到设备（devicectl）
xcrun devicectl device install app --device <DEVICE_UDID> build/Build/Products/Debug-iphoneos/PoLang.app
```

- [ ] **Step 2: 跑全部单测**

```bash
xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'platform=iOS,name=<DEVICE>' test -only-testing:PoLangTests/ScanSessionStateTests -only-testing:PoLangTests/ScanEtaEstimatorTests -only-testing:PoLangTests/ScanTaskPlannerTests -only-testing:PoLangTests/TagDatabaseScanTests
```
Expected: 全绿。

- [ ] **Step 3: 手动端到端验证（对照 spec 验收标准）**

设备上逐项确认：
1. 从 TAG tab 与相册扫描图标都能进入扫描页。
2. 页面 8 个 section 齐全（横幅/进度/控制/统计/管线/快捷/4 张 Pass 卡/精细控制）。
3. Pass2/Pass3/美学卡、非 FACE 类别、时间范围、重生成 均**置灰 + 徽标**，点击弹「后续版本」toast。
4. 点「增量扫描」→ Pass1 跑起，进度/ETA/计数实时更新；`adb logcat` 等价的 Xcode console 能见 `PoLang:TagScan pass1 mediaId=… cost=…ms`。
5. 暂停/恢复/取消/重试 失败 均亚秒级响应。
6. App 进后台自动 pause；杀进程重启后进扫描页提示「恢复未完成」并能续扫。
7. i18n：切英文/简/繁，扫描页文案随语言切换，无硬编码。
8. 落库校验（用 Mac 浏览 `polang_tag.db` 或加临时 debug 打印）：`media_assets` 有 `hasFace/faceRoiResult/faceFocusY/semanticEmbedding/lastTagScanPasses` 写入；`face_embeddings` 有行。

- [ ] **Step 4: 修复发现的问题，逐个 commit**

每个修复独立 commit（如 `fix(ios): <具体问题>`）。

- [ ] **Step 5: 最终提交（如有遗留改动）+ 分支状态**

```bash
git status   # 确认干净
git log --oneline feat/ios-tag-scan-core ^main   # 查看本分支所有 commit
```

---

## 自查（Self-Review）

**1. Spec 覆盖：** 逐条对照 spec 各节——
- §5.1 media_assets（28 列，drop media_tags）→ Task 4 ✓
- §5.2 tag_scan_tasks + DAO → Task 5 ✓
- §5.3 getStats/ScanDbStats → Task 4 `scanStats()` ✓
- §6 状态机/运行循环/ETA/pause/resume/cancel/retry/中断恢复 → Task 1+2+8 ✓
- §7 单一 .utility Task / 前台优先 / 非主线程图片加载 → Task 6+8 ✓（注：`scenePhase` pause + `beginBackgroundTask` grace 见下方「已知简化」）
- §8 ViewModel/Screen 8 section/入口 → Task 9+11+12 ✓
- §9 i18n → Task 10 ✓

**2. 占位符扫描：** 无 TBD/TODO/"add error handling"；每个代码步骤都给了完整代码。

**3. 类型一致性：** `ScanPass`（Task 2）与 `TagScanSessionProgress.currentPass: ScanPass`（Task 8）一致；`ScanDbStats`（Task 4 定义）与 `TagScanViewModel.stats`（Task 9）一致；`TagScanOrchestrator.shared`（Task 8）与 ViewModel/DI（Task 9/12）一致；`getOrCreateMedia/updateMediaAssetsScanFields/scanStats/...`（Task 4/5）与调用方（Task 7/8）签名一致。

**已知简化（后续迭代，非 SP-B 阻塞）：**
- 前台优先的 `scenePhase`→pause 与 `UIApplication.beginBackgroundTask` grace flush 未在 Task 8 展开（Orchestrator 已是协作式 pause，挂到 `scenePhase` 是几行接线；可在 Task 12 之后追加一个 Task，或留到 SP-A 一并）。如需严格闭环，在 Task 12 后补：`PoLangApp` 加 `.onChange(scenePhase)` → `TagScanOrchestrator.shared.pause()` + `beginBackgroundTask` 收尾。
- `PHPhotoLibraryChangeObserver` 增量同步未做（spec §5.1 明确留后续）。
- 视频扫描未做（spec 非目标）。
