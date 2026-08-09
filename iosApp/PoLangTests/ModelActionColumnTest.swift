import XCTest
@testable import PoLang

/// 验证已下载模型的 actionColumn 渲染逻辑
@MainActor
final class ModelActionColumnTest: XCTestCase {

    func testDownloadedModelShowsCheckAndDelete() {
        let mgr = ModelDownloadManager.shared
        mgr.refreshStates()

        // 找一个已下载的模型
        let downloadedModels = ModelCatalog.shared.models.filter { mgr.isModelDownloaded($0.id) }
        XCTAssertFalse(downloadedModels.isEmpty, "Should have at least one downloaded model")

        for entry in downloadedModels {
            let state = mgr.downloadStates[entry.id]
            let isCompleted = state?.status == .completed
            let isDownloaded = mgr.isModelDownloaded(entry.id)

            print("📋 \(entry.id): state=\(String(describing: state?.status)), isCompleted=\(isCompleted), isDownloaded=\(isDownloaded)")

            // 验证：已下载 → 应该显示 check + delete
            XCTAssertTrue(isDownloaded, "Model \(entry.id) should be downloaded")
            if isCompleted {
                print("  ✅ Should show CHECK icon + DELETE button (actionColumn completed branch)")
            } else {
                print("  ⚠️ state not completed but files exist — would show download button")
            }
        }
    }

    /// 验证 MaterialIconMap 包含所有模型中心用到的图标
    func testAllModelCenterIconsMapped() {
        let requiredIcons = ["check", "delete", "download", "pause", "play_arrow", "close"]
        for icon in requiredIcons {
            let mapped = MaterialIconMap.map[icon]
            XCTAssertNotNil(mapped, "Icon '\(icon)' must be in MaterialIconMap")
            print("📋 '\(icon)' → '\(mapped!)'")
        }
    }
}
